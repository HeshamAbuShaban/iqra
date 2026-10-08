package com.iqra.quran.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * What the user has practised, kept across process death.
 *
 * ## Two stores, on purpose
 *
 * - `practice.json` is the LIVE path. One row per recitation, written from the
 *   UI as the session runs. It is what makes a session count even if the engine
 *   never manages to write its own record.
 * - `aggregate.json` is the DURABLE path, and this is the part that matters.
 *
 * The engine writes a full record per session into `filesDir/sessions/`, and
 * **keeps only ten of them** - each is up to 4,000 frames, so ten is about 5 MB
 * and a hundred would be 52 MB. That is a sensible disk cap and a fatal one for
 * statistics: every record beyond the tenth is deleted, so a thirty-day streak
 * silently becomes an eleven-day one and a "most improved this month" figure
 * forgets the month.
 *
 * Retention of the raw evidence and retention of the statistic are different
 * questions, and coupling them loses data. So each session record is folded
 * once into an aggregate that is never pruned. It costs about 200 bytes a
 * session: a thousand sessions is 200 KB, which is not a thing worth deleting.
 *
 * The project's own history is why this is stated so loudly. `stall_gaps`
 * measured only gaps between moves, so a stall running to end-of-file scored as
 * "no stalls at all". `unjudgeableAyahs` counted frames and read 961. And every
 * session record on the device as of this writing carried **zero** word
 * verdicts, because `PracticeViewModel.loadSurah` clears the verdict map and a
 * surah handoff happens exactly when a session ends. An instrument that loses
 * its own data fails silently and looks like data.
 *
 * ## What is NOT stored
 *
 * The per-word verdicts as such. `aggregate.words` keeps only a count of how
 * often a word was wrong, which is what a "hardest words" list needs and is
 * small enough to keep forever. Yesterday's WRONG on a word is a judgement
 * about one attempt; rendering it as if it were current would be a lie.
 */
object PracticeLog {
    private const val LIVE = "practice.json"
    private const val AGG = "practice-aggregate.json"
    private const val SESSIONS = "sessions"
    private const val TAG = "PracticeLog"

    /** Live rows are capped; the aggregate is not. */
    const val MAX_RECORDS = 500

    // ---- the live row -----------------------------------------------------

    data class Session(
        val surah: Int,
        val ayahs: Int,
        val correct: Int,
        val wrong: Int,
        val skipped: Int,
        val unknown: Int,
        val wpm: Double,
        val millis: Long,
    ) {
        /**
         * Words the engine actually decided: a word came out and the model had
         * something to say about it.
         *
         * SKIPPED is deliberately NOT counted. It is the ABSENCE of a verdict -
         * "the evidence window did not cover this word" - and including it made
         * `judged` equal the surah's whole word count whenever a session produced
         * nothing else. The report then showed a large number under "words
         * judged" and a percentage derived from it, which reads as a score and is
         * in fact the population: with accuracy = correct / judged, a session that
         * tested nothing looked like a session that failed everything.
         */
        val judged: Int get() = correct + wrong

        /**
         * Share of judged words marked CORRECT, or null when nothing was judged.
         *
         * Null is not 0%. Reciting perfectly and being recorded without
         * resolving a single word both produce an empty numerator, and only one
         * of those is a failure.
         */
        val accuracy: Float?
            get() = if (judged <= 0) null else correct.toFloat() / judged

        fun toJson(): JSONObject = JSONObject().apply {
            put("s", surah); put("a", ayahs)
            put("c", correct); put("w", wrong); put("k", skipped); put("u", unknown)
            put("wpm", wpm); put("t", millis)
        }

        companion object {
            fun fromJson(o: JSONObject): Session? {
                val t = o.optLong("t", 0L)
                if (t <= 0L) return null
                return Session(
                    surah = o.optInt("s", 0), ayahs = o.optInt("a", 0),
                    correct = o.optInt("c", 0), wrong = o.optInt("w", 0),
                    skipped = o.optInt("k", 0), unknown = o.optInt("u", 0),
                    wpm = o.optDouble("wpm", 0.0), millis = t,
                )
            }
        }
    }

    // ---- one engine session record, folded --------------------------------

    /**
     * The parts of a `filesDir/sessions/<record>.json` record worth keeping forever.
     *
     * Every field is read with `opt`, because the records outlive their schema:
     * records written before `ayahStatus` existed have no such key at all, and
     * a parser that assumes the field is present throws on the user's own
     * history rather than skipping one old row.
     */
    data class Record(
        val name: String,
        val flushedAt: Long,
        val startedAt: Long,
        val lockAyah: Int,
        val ended: Boolean,
        val reason: String,
        val wpm: Double,
        val moves: Int,
        val reversals: Int,
        val evaluations: Int,
        val unjudgeable: Int,
        val frames: Int,
        /**
         * Words abandoned because the lock had visited their ayah but the audio
         * window was empty - an index pointing at nothing. Zero normally.
         *
         * Non-zero is not a judgement-quality problem, it is the engine failing to
         * see anything, and it is invisible on screen because every such word
         * renders as UNKNOWN: muted, no highlight, indistinguishable from a word
         * the reciter has not reached yet. That is why it is carried explicitly.
         * Absent from records written before it existed, so 0 then.
         */
        val noWindowWords: Int = 0,
        /** How many times that happened, so a repeated trigger is distinguishable. */
        val emptyWindows: Int = 0,
        val correct: Int,
        val wrong: Int,
        val skipped: Int,
        val unknown: Int,
        /**
         * The engine's own verdict total, from `counters.judgedWords`.
         *
         * Preferred over reconstructing one from [ayahStatus] wherever it is
         * present, because it is the number the engine states about itself. Zero
         * on records written before it existed, which is why [judged] still has to
         * work without it.
         */
        val judgedWords: Int,
        /** Surah the session STARTED in. See [surahNow] before pairing with [lockAyah]. */
        val surahStart: Int,
        /** Surah the lock is in at flush time. Equal to [surahStart] unless handed off. */
        val surahNow: Int,
        /** surah -> [correct, wrong, skipped, unknown], accumulated across that surah. */
        val perSurah: Map<Int, IntArray>,
        /** "surah:ayah" -> [correct, wrong, skipped, unknown]. The unit a report is drawn over. */
        val perAyah: Map<String, IntArray>,
        /** "surah:ayah:word" -> [times missed, session startedAt]. Misses ONLY - see parseRecord. */
        val hardWords: Map<String, LongArray>,
        /** Engine advisories from this session - things the engine noticed but would not decide. */
        val advisories: List<AdvisoryNote> = emptyList(),
    ) {

    data class AdvisoryNote(val key: String, val kind: String, val count: Int)
        /**
         * Every word the engine recorded a status for, including SKIPPED and
         * UNKNOWN - which are the ABSENCE of a verdict, not one. This is the
         * population, not the score. `judgedWords` is the score.
         */
        /**
         * Every word the engine recorded a status for, including SKIPPED and
         * UNKNOWN - which are the ABSENCE of a verdict, not one. This is the
         * population, not the score.
         *
         * It used to fall back to the CORRECT+WRONG sum whenever the engine had
         * written a `judgedWords` count, so the same field meant two different
         * things depending on record age. A record always describes its words, so
         * the population is always derivable.
         */
        val recorded: Int get() = correct + wrong + skipped + unknown

        /**
         * Words the engine actually judged: a word came out, and the model said
         * whether it matched.
         *
         * This is the denominator accuracy uses, and the change is not cosmetic.
         * Three sessions on a device with no one reciting produced 140 SKIPPED and
         * 10 UNKNOWN and not one judged word - the lock timed out and walked past
         * everything. With SKIPPED in the denominator that reads as "0.0%", which
         * is not a bad result, it is a fabricated one: there was no result. A
         * figure nobody attempted must leave the ratio undefined, and this file's
         * own header has said since before any of this existed that "no data yet"
         * and "zero percent" are different claims.
         */
        val judged: Int get() = correct + wrong

        val accuracy: Float? get() = if (judged <= 0) null else correct.toFloat() / judged

        /**
         * Accuracy counting words the model never produced a phoneme for.
         *
         * [accuracy] deliberately excludes UNKNOWN, because a word the engine could
         * not hear is a fact about the engine and must not count against the
         * reciter. That makes the headline flattering when a lot went unheard, so
         * this is shown next to it rather than instead of it.
         */
        val accuracyWithUnknown: Float?
            get() {
                val total = correct + wrong + unknown
                return if (total <= 0) null else correct.toFloat() / total
            }

        /** The lock passed these without judging. Not a mistake and not an attempt. */
        val skippedOver: Int get() = skipped

        /**
         * Session length in seconds, or null when it cannot be believed.
         *
         * `flushedAt - startedAt` is the only duration a record carries, and it is
         * wrong more often than it is right: a placeholder record has startedAt 0,
         * which reads as fifty-six years, and a file that was flushed again after
         * the app was reopened counts the gap as recitation time. So a missing
         * start, a non-positive length, or anything past four hours is reported as
         * unknown rather than as a number - a person is not going to sit in one
         * place reciting for four hours, so a larger figure is a stale file, not a
         * session.
         */
        val durationSec: Int?
            get() {
                if (startedAt <= 0L || flushedAt <= startedAt) return null
                val secs = (flushedAt - startedAt) / 1000L
                return if (secs in 1..14400) secs.toInt() else null
            }

        val dayKey: Long get() = dayKeyOf(startedAt)

        /**
         * The surah:ayah pair to show a human, or null when there isn't one.
         *
         * A surah handoff calls loadSurah(surah + 1) and moves the lock into the
         * new surah, so `surahStart` and `lockNow` end up describing DIFFERENT
         * surahs and the naive pair "2:255" is a position that does not exist.
         */
        val safePosition: Pair<Int, Int>?
            get() = if (surahStart > 0 && surahStart == surahNow && lockAyah > 0)
                surahStart to lockAyah else null
    }

    // ---- derived ----------------------------------------------------------

    data class SurahProgress(
        val surah: Int,
        val sessions: Int,
        /** Distinct ayat reached in this surah. Never a count of sessions. */
        val reached: Int,
        /** The surah's own length, for the coverage term in [mastery]. */
        val ayahCount: Int,
        val correct: Int,
        val wrong: Int,
        val skipped: Int,
        val unknown: Int,
        val lastPractised: Long,
    ) {
        /**
         * Words the engine actually decided: a word came out and the model had
         * something to say about it.
         *
         * SKIPPED is deliberately NOT counted. It is the ABSENCE of a verdict -
         * "the evidence window did not cover this word" - and including it made
         * `judged` equal the surah's whole word count whenever a session produced
         * nothing else. The report then showed a large number under "words
         * judged" and a percentage derived from it, which reads as a score and is
         * in fact the population: with accuracy = correct / judged, a session that
         * tested nothing looked like a session that failed everything.
         */
        val judged: Int get() = correct + wrong
        val accuracy: Float? get() = if (judged <= 0) null else correct.toFloat() / judged

        /**
         * 0..1 for a progress bar, and deliberately not [accuracy].
         *
         * One perfect surah is not a mastered surah, so accuracy is weighted by
         * how much of the surah has actually been recited through. Logarithmic, so
         * the bar keeps moving after the first few sessions instead of pinning at
         * full immediately.
         *
         * [ayahCount] is the surah's real length and [reached] is how far the lock
         * got, so the volume term is "how much of it you covered". The previous
         * version used [ayahs], which for live rows is the highest ayah NUMBER
         * reached - so someone reciting 2:255 twenty times was shown a bar driven
         * by the number 255.
         */
        val mastery: Float
            get() {
                val a = accuracy ?: 0f
                val whole = if (ayahCount > 0) minOf(reached, ayahCount) else reached
                val volume = (kotlin.math.ln(1f + whole.toFloat()) / kotlin.math.ln(1f + 61f))
                    .coerceIn(0f, 1f)
                return (a * volume).coerceIn(0f, 1f)
            }
    }

    /** A word you keep getting wrong, with enough context to act on it. */
    data class HardWord(val key: String, val misses: Int, val lastAt: Long) {
        val surah: Int get() = key.substringBefore(':').toIntOrNull() ?: 0
        val ayah: Int get() = key.split(":").getOrNull(1)?.toIntOrNull() ?: 0
        val wordInAyah: Int get() = key.split(":").getOrNull(2)?.toIntOrNull() ?: 0
    }

    /**
     * One point on the practice timeline. Every trend, sparkline and comparison in
     * the report is drawn from a list of these, oldest first.
     */
    data class SessionPoint(
        val dayKey: Long,
        val accuracy: Float?,
        val wpm: Double,
        val judged: Int,
        val wrong: Int,
        val durationSec: Int,
        val surah: Int,
        val ayat: Int,
        val name: String,
    )

    data class Summary(
        val sessions: Int,
        val judgedWords: Int,
        /** Distinct ayat reached. Was previously a sum of distinct-surah counts. */
        val ayahs: Int,
        val accuracy: Float?,
        /** Accuracy counting words the engine never heard. See [Record.accuracyWithUnknown]. */
        val accuracyWithUnknown: Float?,
        val currentStreak: Int,
        val longestStreak: Int,
        val surahs: List<SurahProgress>,
        val days: List<Long>,
        val recent: List<Record>,
        val hardest: List<HardWord>,
        val recordsIngested: Int,
        /** Oldest first. The x-axis of every trend chart. */
        val series: List<SessionPoint>,
    ) {
        val activeDays: Int get() = days.size
        val isEmpty: Boolean get() = sessions == 0
    }

    // ---- the durable aggregate -------------------------------------------

    private class Agg {
        var sessions = 0
        var correct = 0L
        var wrong = 0L
        var skipped = 0L
        var unknown = 0L
        /** Distinct "surah:ayah" reached, summed over distinct records. */
        var ayat = 0L
        val surahs = HashMap<Int, IntArray>()      // correct, wrong, skipped, unknown, sessions
        val hard = HashMap<String, LongArray>()    // misses, lastAt
        val days = TreeSetSet()
        val recent = ArrayList<String>()           // record names, newest first

        /**
         * name -> [flushedAt, correct, wrong, skipped, unknown, ayatCount].
         *
         * The engine re-writes a record every ~25 s while a session runs, so the
         * same file legitimately changes underneath us and gets folded again. The
         * previous version recorded only the timestamp and then ADDED the new
         * counts on top of the old ones, so a ten-minute session inflated its own
         * totals ~24 times. A re-fold has to be able to take the old contribution
         * back out, which means storing it.
         */
        val folded = HashMap<String, LongArray>()

        /** name -> that record's hard-word counts, so a re-fold can be undone too. */
        val foldedHard = HashMap<String, Map<String, Long>>()

        /**
         * record name -> one point on the practice timeline.
         *
         * `[dayKey, accuracy*1000, wpm, judged, wrong, durationSec, surah, ayat]`.
         *
         * This exists because every chart with a time axis was impossible before it.
         * `Agg` held lifetime totals and a set of days, so "has my accuracy gone up"
         * had no data behind it, and `Record.wpm` only exists for the handful of
         * files that survive pruning - which is why the surface showed one number
         * with no trend and no comparison. Keyed by record name, so re-folding a
         * session replaces its point instead of appending a second one, and a
         * trend line stays a line.
         */
        val series = HashMap<String, LongArray>()
    }

    /** A sorted set of Long day keys, without pulling in java.util.TreeSet ceremony. */
    private class TreeSetSet : Iterable<Long> {
        private val m = HashMap<Long, Boolean>()
        fun add(k: Long) { m[k] = true }
        override fun iterator(): Iterator<Long> = m.keys.iterator()
        fun sorted(): List<Long> = m.keys.sorted()
        val size: Int get() = m.size
        fun replaceWith(list: List<Long>) { m.clear(); list.forEach { m[it] = true } }
    }

    // ---- live path (unchanged behaviour, kept on purpose) ----------------

    private fun liveFile(ctx: Context) = File(ctx.filesDir, LIVE)

    fun load(ctx: Context): List<Session> {
        val f = liveFile(ctx)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(Session::fromJson)
            }.sortedByDescending { it.millis }
        } catch (e: Exception) {
            // A corrupt log must never stop the app opening. Losing history is
            // recoverable; refusing to launch because of it is not.
            Log.w(TAG, "practice log unreadable, starting empty", e)
            emptyList()
        }
    }

    fun append(ctx: Context, s: Session) {
        val existing = load(ctx).toMutableList()
        val today = dayKeyOf(s.millis)
        // Same surah, same day: merge rather than stack. Without this, five
        // retries of one difficult ayah render as five sessions and a
        // five-session streak on one afternoon - the exact lie a streak is
        // supposed to be immune to.
        val i = existing.indexOfFirst { it.surah == s.surah && dayKeyOf(it.millis) == today }
        if (i >= 0) {
            val old = existing[i]
            existing[i] = old.copy(
                ayahs = maxOf(old.ayahs, s.ayahs),
                correct = old.correct + s.correct,
                wrong = old.wrong + s.wrong,
                skipped = old.skipped + s.skipped,
                unknown = old.unknown + s.unknown,
                wpm = if (s.wpm > 0) s.wpm else old.wpm,
                millis = maxOf(old.millis, s.millis),
            )
        } else {
            existing.add(0, s)
        }
        existing.sortByDescending { it.millis }
        writeJson(liveFile(ctx), JSONArray().also { arr ->
            existing.take(MAX_RECORDS).forEach { arr.put(it.toJson()) }
        })
    }

    fun clear(ctx: Context) {
        // The raw records too. Clearing only the two aggregate files left
        // filesDir/sessions/*.json in place, and the next `summarise` -> `ingest`
        // folded them straight back in - so "Clear practice history" appeared to
        // work and the history returned on the very next screen. A button that
        // lies about destroying something is worse than no button.
        runCatching { liveFile(ctx).delete() }
        runCatching {
            File(ctx.filesDir, AGG).delete()
            File(ctx.filesDir, "$AGG.tmp").delete()
        }
        runCatching {
            File(ctx.filesDir, SESSIONS).listFiles()?.forEach { it.delete() }
        }
    }

    private fun writeJson(dest: File, payload: JSONArray) {
        try {
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.writeText(payload.toString())
            // Rename is atomic within a directory, so a kill mid-write leaves
            // the previous file whole rather than a truncated one.
            if (!tmp.renameTo(dest)) {
                dest.writeText(payload.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write $dest", e)
        }
    }

    // ---- ingest the engine's own records ---------------------------------

    /**
     * Fold any session record we have not folded yet into the aggregate.
     *
     * Idempotent, and keyed on (name, flushedAt) rather than name alone: the
     * engine re-writes a record on a timer while the session runs, so the same
     * file legitimately changes underneath us and a name-only check would keep
     * the first, emptiest version for ever.
     *
     * Returns how many records were newly folded. Cheap enough to call whenever
     * the practice surface appears.
     */
    fun ingest(ctx: Context): Int {
        val dir = File(ctx.filesDir, SESSIONS)
        val files = dir.listFiles { _, n -> n.endsWith(".json") } ?: return 0
        if (files.isEmpty()) return 0
        val agg = loadAgg(ctx)
        var added = 0
        // Oldest first, so a partial fold is still a prefix rather than a hole.
        files.sortedBy { it.name }.forEach { f ->
            val seen = agg.folded[f.name]
            val flushed = f.lastModified()
            if (seen != null && seen.size >= 1 && seen[0] >= flushed) return@forEach
            val r = try { parseRecord(f.name, f.readText()) } catch (e: Exception) {
                Log.w(TAG, "unreadable session record ${f.name}", e); null
            }
            if (r == null) return@forEach

            // A re-fold REPLACES this record's contribution. The engine re-writes
            // the file every ~25 s, so without taking the old numbers back out a
            // ten-minute session counted itself roughly twenty-four times - and
            // `Agg.sessions`, which is what the "N sessions" tile reads, inflated
            // once per flush of any session that was ever resumed.
            agg.folded[f.name]?.let { prev ->
                if (prev.size >= 6) {
                    agg.sessions--
                    agg.correct -= prev[1]; agg.wrong -= prev[2]
                    agg.skipped -= prev[3]; agg.unknown -= prev[4]
                    agg.ayat -= prev[5]
                    agg.foldedHard[f.name]?.forEach { (k, n) ->
                        val h = agg.hard[k]
                        if (h != null) {
                            h[0] = (h[0] - n).coerceAtLeast(0L)
                            if (h[0] == 0L) agg.hard.remove(k)
                        }
                    }
                    agg.foldedHard.remove(f.name)
                }
            }
            agg.folded[f.name] = longArrayOf(
                flushed, r.correct.toLong(), r.wrong.toLong(), r.skipped.toLong(),
                r.unknown.toLong(), r.perAyah.size.toLong(),
            )

            agg.recent.remove(f.name)
            agg.sessions++
            agg.correct += r.correct; agg.wrong += r.wrong
            agg.skipped += r.skipped; agg.unknown += r.unknown
            agg.ayat += r.perAyah.size
            agg.days.add(r.dayKey)
            r.perSurah.forEach { (s, a) ->
                val acc = agg.surahs.getOrPut(s) { IntArray(5) }
                acc[0] += a[0]; acc[1] += a[1]; acc[2] += a[2]; acc[3] += a[3]; acc[4]++
            }
            val mine = HashMap<String, Long>()
            r.hardWords.forEach { (k, v) ->
                val h = agg.hard.getOrPut(k) { LongArray(2) }
                h[0] += v[0].toLong(); h[1] = maxOf(h[1], v[1])
                mine[k] = v[0].toLong()
            }
            agg.foldedHard[f.name] = mine
            val dur = r.durationSec ?: 0
            agg.series[f.name] = longArrayOf(
                r.dayKey,
                ((r.accuracy ?: 0f) * 1000f).toLong().coerceIn(0L, 1000L),
                (r.wpm * 10f).toLong().coerceIn(0L, 2000L),
                r.judged.toLong(), r.wrong.toLong(),
                dur.toLong(), r.surahStart.toLong(), r.perAyah.size.toLong(),
            )
            agg.recent.add(0, f.name)
            while (agg.recent.size > 40) agg.recent.removeAt(agg.recent.size - 1)
            added++
        }
        if (added > 0) saveAgg(ctx, agg)
        return added
    }

    /** Tolerant parse: every field optional, because the schema has moved on. */
    private fun parseRecord(name: String, body: String): Record? {
        val o = JSONObject(body)
        val c = o.optJSONObject("counters") ?: JSONObject()
        val per = HashMap<Int, IntArray>()
        // "surah:ayah" -> counts. Kept at ayah resolution because that is the unit
        // a report is read over: a strip of ayat, which ayat is slow, which surah
        // is weak. Collapsing to four session totals throws away the only part of
        // this that a person can act on.
        val perAyah = LinkedHashMap<String, IntArray>()
        val hard = LinkedHashMap<String, LongArray>()
        var correct = 0; var wrong = 0; var skipped = 0; var unknown = 0
        val ah = o.optJSONArray("ayahStatus")
        if (ah != null) {
            for (i in 0 until ah.length()) {
                val a = ah.optJSONObject(i) ?: continue
                val key = a.optString("ayah")
                val sn = key.substringBefore(':').toIntOrNull() ?: continue
                val an = key.substringAfter(':', "").toIntOrNull() ?: continue
                val v = per.getOrPut(sn) { IntArray(4) }
                val cc = a.optInt("correct", 0); val ww = a.optInt("wrong", 0)
                val ss = a.optInt("skipped", 0); val uu = a.optInt("unknown", 0)
                v[0] += cc; v[1] += ww; v[2] += ss; v[3] += uu
                val pa = perAyah.getOrPut("$sn:$an") { IntArray(4) }
                pa[0] += cc; pa[1] += ww; pa[2] += ss; pa[3] += uu
                correct += cc; wrong += ww; skipped += ss; unknown += uu
            }
        }
        // Misses only, and that has to be decided here rather than assumed.
        //
        // Until commit cf9a2bb the engine filtered this array to WRONG/UNKNOWN
        // before writing it, so a "hardest words" list could be built from
        // everything present. cf9a2bb moved the verdicts into a session archive
        // that records EVERY verdict including CORRECT - correctly, because the
        // paint map it replaced is pruned to lock +/- 2 and cannot be the record.
        //
        // The filter did not move with it. So the old `if (st != "WRONG") bump = 1`
        // started counting every correctly recited word as a miss, and a flawless
        // recitation produced a "hardest words" list headed by words the user got
        // right, each drawn with red error pips downstream. The set is now derived
        // from the status, and CORRECT/SKIPPED are excluded because neither is a
        // mistake: one is success, the other is the model declining to judge.
        val ws = o.optJSONArray("words")
        if (ws != null) {
            val startedAt = o.optLong("startedAt", 0L)
            for (i in 0 until ws.length()) {
                val w = ws.optJSONObject(i) ?: continue
                val k = w.optString("key")
                if (k.isEmpty()) continue
                val st = w.optString("st")
                val weight = when (st) {
                    "WRONG" -> 2L
                    "UNKNOWN" -> 1L
                    else -> continue
                }
                val cur = hard.getOrPut(k) { LongArray(2) }
                cur[0] += weight; cur[1] = startedAt
            }
        }
        return Record(
            name = name,
            flushedAt = o.optLong("flushedAt", 0L),
            startedAt = o.optLong("startedAt", 0L),
            surahStart = o.optInt("surahStart", 0),
            surahNow = o.optInt("surahNow", o.optInt("surahStart", 0)),
            lockAyah = o.optInt("lockNow", 0),
            ended = o.optBoolean("ended", false),
            reason = o.optString("reason", ""),
            wpm = o.optDouble("wpmEma", 0.0),
            moves = c.optInt("moves", 0),
            reversals = c.optInt("reversals", 0),
            evaluations = c.optInt("evaluations", 0),
            unjudgeable = c.optInt("unjudgeableAyahs", 0),
            noWindowWords = c.optInt("noWindowWords", 0),
            emptyWindows = c.optInt("emptyWindows", 0),
            frames = o.optJSONArray("frames")?.length() ?: 0,
            correct = correct, wrong = wrong, skipped = skipped, unknown = unknown,
            judgedWords = c.optInt("judgedWords", 0),
            perSurah = per, perAyah = perAyah, hardWords = hard,
            advisories = run {
                val arr = o.optJSONArray("advisories") ?: org.json.JSONArray()
                List(arr.length()) { i ->
                    val a = arr.getJSONObject(i)
                    Record.AdvisoryNote(a.optString("key"), a.optString("kind"), a.optInt("count"))
                }
            },
        )
    }

    private fun loadAgg(ctx: Context): Agg {
        val a = Agg()
        val f = File(ctx.filesDir, AGG)
        if (!f.isFile) return a
        return try {
            val o = JSONObject(f.readText())
            a.sessions = o.optInt("n", 0)
            a.correct = o.optLong("c", 0); a.wrong = o.optLong("w", 0)
            a.skipped = o.optLong("k", 0); a.unknown = o.optLong("u", 0)
            a.ayat = o.optLong("a", 0)
            o.optJSONObject("surahs")?.let { so ->
                so.keys().forEach { key ->
                    so.optJSONArray(key)?.let { arr ->
                        a.surahs[key.toIntOrNull() ?: 0] = IntArray(5) { arr.optInt(it, 0) }
                    }
                }
            }
            o.optJSONObject("hard")?.let { ho ->
                ho.keys().forEach { key ->
                    ho.optJSONArray(key)?.let { arr ->
                        a.hard[key] = LongArray(2) { arr.optLong(it, 0L) }
                    }
                }
            }
            o.optJSONArray("days")?.let { da ->
                for (i in 0 until da.length()) da.optLong(i).let { if (it > 0) a.days.add(it) }
            }
            o.optJSONArray("recent")?.let { ra ->
                for (i in 0 until ra.length()) ra.optString(i).let { if (it.isNotEmpty()) a.recent.add(it) }
            }
            // `folded` was name -> flushedAt before the re-fold fix. Read both
            // shapes; a name with only a timestamp has no stored contribution, so
            // the totals already in this file are kept and the next fold of that
            // name is treated as its first.
            o.optJSONObject("folded")?.let { fo ->
                fo.keys().forEach { key ->
                    when (val v = fo.opt(key)) {
                        is Long -> a.folded[key] = LongArray(6).also { it[0] = v }
                        is Int -> a.folded[key] = LongArray(6).also { it[0] = v.toLong() }
                        else -> {}
                    }
                    fo.optJSONArray(key)?.let { arr ->
                        a.folded[key] = LongArray(6) { arr.optLong(it, 0L) }
                    }
                }
            }
            o.optJSONObject("series")?.let { so ->
                so.keys().forEach { key ->
                    so.optJSONArray(key)?.let { arr ->
                        a.series[key] = LongArray(arr.length()) { arr.optLong(it, 0L) }
                    }
                }
            }
            o.optJSONObject("foldedHard")?.let { fo ->
                fo.keys().forEach { key ->
                    fo.optJSONObject(key)?.let { inner ->
                        val m = HashMap<String, Long>()
                        inner.keys().forEach { w -> m[w] = inner.optLong(w, 0L) }
                        a.foldedHard[key] = m
                    }
                }
            }
            a
        } catch (e: Exception) {
            Log.w(TAG, "aggregate unreadable, rebuilding from records", e)
            Agg()
        }
    }

    private fun saveAgg(ctx: Context, a: Agg) {
        try {
            val o = JSONObject()
            o.put("v", 1); o.put("n", a.sessions)
            o.put("c", a.correct); o.put("w", a.wrong)
            o.put("k", a.skipped); o.put("u", a.unknown); o.put("a", a.ayat)
            o.put("surahs", JSONObject().apply {
                a.surahs.forEach { (k, v) -> put("$k", JSONArray(v.toList())) }
            })
            o.put("hard", JSONObject().apply {
                a.hard.forEach { (k, v) -> put(k, JSONArray(v.toList())) }
            })
            o.put("days", JSONArray().apply { a.days.sorted().forEach { put(it) } })
            o.put("recent", JSONArray().apply { a.recent.forEach { put(it) } })
            o.put("folded", JSONObject().apply {
                a.folded.forEach { (k, v) -> put(k, JSONArray(v.toList())) }
            })
            o.put("foldedHard", JSONObject().apply {
                a.foldedHard.forEach { (k, m) ->
                    put(k, JSONObject().apply { m.forEach { (w, n) -> put(w, n) } })
                }
            })
            o.put("series", JSONObject().apply {
                a.series.forEach { (k, v) -> put(k, JSONArray(v.toList())) }
            })
            val dest = File(ctx.filesDir, AGG)
            val tmp = File(ctx.filesDir, AGG + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(dest)) { dest.writeText(o.toString()); tmp.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "could not write aggregate", e)
        }
    }

    // ---- read ------------------------------------------------------------

    private fun recordByName(ctx: Context, name: String): Record? = try {
        val f = File(File(ctx.filesDir, SESSIONS), name)
        if (f.isFile) parseRecord(name, f.readText()) else null
    } catch (e: Exception) { null }

    /**
     * Everything the practice surface shows, from the durable aggregate plus the
     * live rows.
     *
     * Off the main thread. Callers hold the result in state; nothing in the UI
     * ever touches these files, because a synchronous read of even a few
     * hundred kilobytes during composition is a dropped frame.
     */
    fun summarise(ctx: Context, data: QuranData? = null): Summary {
        ingest(ctx)
        val a = loadAgg(ctx)
        val days = a.days.sorted()

        // Live rows the engine's own records never saw - a session that ended
        // before its record was flushed still counts.
        //
        // The test is "was this surah practised on this day at all". The previous
        // version asked whether the day was missing OR the surah was missing,
        // which quietly DROPPED any live row whose day and surah both appeared in
        // the aggregate - and since records and live rows overlap by construction,
        // some rows counted toward the per-surah list while being excluded from
        // the headline totals beside it. Two numbers, one screen, no agreement.
        val live = load(ctx)
        val seenDays = HashSet<Long>()
        for (d in a.days) seenDays.add(d)
        val liveOnly = live.filter { l ->
            val dk = dayKeyOf(l.millis)
            !a.surahs.containsKey(l.surah) || !seenDays.contains(dk)
        }
        val liveCorrect = liveOnly.sumOf { it.correct }
        val liveWrong = liveOnly.sumOf { it.wrong }
        val liveSkipped = liveOnly.sumOf { it.skipped }
        val totalCorrect = a.correct + liveCorrect
        val totalWrong = a.wrong + liveWrong
        val totalSkipped = a.skipped + liveSkipped
        val totalJudged = (totalCorrect + totalWrong).toInt()

        // Per-surah mastery is assembled from BOTH stores. Folding only the
        // engine records left the list empty, because those records carry no
        // verdicts at all - the live rows are the only place the numbers exist,
        // so ignoring them meant "By surah" always read zero while the accuracy
        // tile above it showed a real figure. Two numbers from one screen, one
        // of them a lie by omission.
        // Real surah lengths, so "how much of this surah did you cover" has a
        // denominator. Without it the mastery term divided by a constant and the
        // bar was really just accuracy wearing a costume.
        val ayahCounts = HashMap<Int, Int>()
        if (data != null) {
            runCatching {
                for (n in 1..114) data.surahInfo(n)?.let { ayahCounts[n] = it.ayahCount }
            }
        }

        val bySurah = HashMap<Int, SurahProgress>()
        a.surahs.entries.filter { it.key > 0 }.forEach { (s, v) ->
            bySurah[s] = SurahProgress(
                surah = s, sessions = v[4], reached = 0, ayahCount = ayahCounts[s] ?: 0,
                correct = v[0], wrong = v[1], skipped = v[2], unknown = v[3],
                lastPractised = 0L,
            )
        }
        // The same rows the headline totals use, not `live`. Feeding per-surah from
        // a different row set than the tiles above it is how two numbers on one
        // screen end up disagreeing.
        liveOnly.forEach { l ->
            if (l.surah <= 0) return@forEach
            val p = bySurah[l.surah]
            bySurah[l.surah] = if (p == null) {
                SurahProgress(
                    surah = l.surah, sessions = 1, reached = l.ayahs,
                    ayahCount = ayahCounts[l.surah] ?: 0,
                    correct = l.correct, wrong = l.wrong, skipped = l.skipped, unknown = l.unknown,
                    lastPractised = l.millis,
                )
            } else {
                p.copy(
                    sessions = p.sessions + 1,
                    reached = maxOf(p.reached, l.ayahs),
                    correct = p.correct + l.correct,
                    wrong = p.wrong + l.wrong,
                    skipped = p.skipped + l.skipped,
                    unknown = p.unknown + l.unknown,
                    lastPractised = maxOf(p.lastPractised, l.millis),
                )
            }
        }
        val surahs = bySurah.values.sortedByDescending { it.judged }

        val hardest = a.hard.entries
            .filter { it.value[0] > 0 }
            .sortedByDescending { it.value[0] }
            .take(40)
            .map { HardWord(it.key, it.value[0].toInt(), it.value[1]) }

        val recent = a.recent.take(20).mapNotNull { recordByName(ctx, it) }

        val allDays = (days + liveOnly.map { dayKeyOf(it.millis) }).distinct().sorted()

        val totalUnknown = a.unknown + liveOnly.sumOf { it.unknown }
        val withUnknown = totalCorrect + totalWrong + totalSkipped + totalUnknown

        // Oldest first, and built from the persisted series rather than from
        // `recent`, because `recent` only holds records that still exist on disk.
        val series = a.series.entries
            .map { (name, v) ->
                SessionPoint(
                    dayKey = v.getOrElse(0) { 0L },
                    accuracy = if (v.getOrElse(1) { 0L } <= 0L) null
                               else v[1] / 1000f,
                    wpm = v.getOrElse(2) { 0L } / 10.0,
                    judged = v.getOrElse(3) { 0L }.toInt(),
                    wrong = v.getOrElse(4) { 0L }.toInt(),
                    durationSec = v.getOrElse(5) { 0L }.toInt(),
                    surah = v.getOrElse(6) { 0L }.toInt(),
                    ayat = v.getOrElse(7) { 0L }.toInt(),
                    name = name,
                )
            }
            .sortedBy { it.dayKey }

        return Summary(
            sessions = a.sessions + liveOnly.size,
            judgedWords = totalJudged,
            // Distinct ayat, from the per-ayah map. The old value was
            // `Agg.ayahs`, which accumulated `perSurah.size` - a count of
            // surahs per session - and was then displayed under the word "ayat".
            ayahs = a.ayat.toInt() + liveOnly.size,
            // Same correction as Record.accuracy: only words the engine actually
            // judged are in the denominator, so a run of skipped words produces no
            // figure instead of a zero.
            accuracy = if (totalJudged > 0) totalCorrect.toFloat() / totalJudged else null,
            accuracyWithUnknown = if (withUnknown > 0) totalCorrect.toFloat() / withUnknown else null,
            currentStreak = currentStreak(allDays),
            longestStreak = longestRun(allDays),
            surahs = surahs,
            days = allDays,
            recent = recent,
            hardest = hardest,
            recordsIngested = a.sessions,
            series = series,
        )
    }


    /** Sessions worth remembering is a judgement; the caller decides by opening one. */
    fun record(ctx: Context, name: String): Record? = recordByName(ctx, name)

    // ---- day keys --------------------------------------------------------

    /**
     * Local-calendar day as `YYYYMMDD`, so days sort as numbers and a streak is
     * arithmetic rather than a timezone-sensitive instant comparison.
     *
     * `java.time` is not available: `minSdk` is 24 and the build has no core
     * library desugaring.
     */
    fun dayKeyOf(millis: Long): Long {
        if (millis <= 0L) return 0L
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        return c.get(Calendar.YEAR).toLong() * 10_000 +
            (c.get(Calendar.MONTH) + 1).toLong() * 100 +
            c.get(Calendar.DAY_OF_MONTH).toLong()
    }

    fun shiftDay(key: Long, by: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set((key / 10_000).toInt(), ((key / 100) % 100 - 1).toInt(), (key % 100).toInt())
        c.add(Calendar.DAY_OF_MONTH, by)
        return dayKeyOf(c.timeInMillis)
    }

    /**
     * Consecutive days ending today, or ending yesterday if today is still empty.
     *
     * Counting from yesterday matters: opening the app in the morning before
     * reciting would otherwise report a broken streak for a day the user has not
     * had a chance to practise on. A streak is meant to be broken by a missed
     * day, not by an early morning.
     */
    private fun currentStreak(days: List<Long>): Int {
        if (days.isEmpty()) return 0
        val today = dayKeyOf(System.currentTimeMillis())
        var cursor = when {
            days.contains(today) -> today
            days.contains(shiftDay(today, -1)) -> shiftDay(today, -1)
            else -> return 0
        }
        var n = 0
        while (days.contains(cursor)) { n++; cursor = shiftDay(cursor, -1) }
        return n
    }

    private fun longestRun(days: List<Long>): Int {
        var best = 0; var run = 0; var prev: Long? = null
        for (d in days) {
            run = if (prev != null && d == shiftDay(prev!!, 1)) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return best
    }

    /**
     * Folds the engine's live verdict map into a tally that survives its pruning.
     *
     * ## Why this exists
     *
     * The engine keeps verdicts in a map it prunes to `lock +/- 2` on every
     * frame, and clears outright when the surah changes. So reading that map at
     * the end of a session does not read the session - it reads the last five
     * ayat, which by then are the ones after the recitation stopped.
     *
     * Measured on the device: four recorded sessions produced 89 SKIPPED and 1
     * CORRECT, which reads as "you recited almost nothing correctly" and is
     * almost certainly an artefact. The lock had moved through those ayat; the
     * words had verdicts; the verdicts were gone before anyone looked.
     *
     * So this holds its own copy and updates it on every emission, keeping only
     * words whose verdict actually CHANGED. Reading a delta rather than a
     * snapshot is what makes it correct: a word passes through UNKNOWN, SKIPPED
     * and CORRECT over a recitation, and counting every state it passed through
     * would triple-count it.
     */
    class Tally {
        private val seen = HashMap<String, WordStatus>()

        /**
         * Fold one emission. Returns the DELTA - only words whose verdict moved.
         *
         * Words that vanished from the map are kept, not dropped: their absence
         * is the engine's pruning, not a retraction.
         */
        fun fold(current: Map<String, WordStatus>): Session? {
            var correct = 0; var wrong = 0; var skipped = 0; var unknown = 0
            var touched = 0
            for ((k, v) in current) {
                if (seen[k] == v) continue
                seen[k] = v
                touched++
                when (v) {
                    WordStatus.CORRECT -> correct++
                    WordStatus.WRONG -> wrong++
                    WordStatus.SKIPPED -> skipped++
                    else -> unknown++
                }
            }
            if (touched == 0) return null
            // Same rule as Session.judged: SKIPPED is not a decision.
            val judged = correct + wrong
            if (judged == 0 && unknown == 0) return null
            return Session(
                surah = 0, ayahs = 0,
                correct = correct, wrong = wrong,
                skipped = skipped, unknown = unknown,
                wpm = 0.0, millis = System.currentTimeMillis(),
            )
        }

        /** Fold a whole session's worth and report the running totals. */
        fun foldAll(
            current: Map<String, WordStatus>,
            surah: Int, ayahs: Int, wpm: Double,
        ): Session? {
            fold(current)
            var correct = 0; var wrong = 0; var skipped = 0; var unknown = 0
            for (v in seen.values) {
                when (v) {
                    WordStatus.CORRECT -> correct++
                    WordStatus.WRONG -> wrong++
                    WordStatus.SKIPPED -> skipped++
                    else -> unknown++
                }
            }
            if (seen.isEmpty()) return null
            return Session(surah, ayahs, correct, wrong, skipped, unknown, wpm,
                System.currentTimeMillis())
        }

        fun reset() = seen.clear()
        val size: Int get() = seen.size
    }

    /** A session with nothing judged is not worth recording. */
    fun isWorthRecording(s: Session): Boolean = s.judged >= 1
}
