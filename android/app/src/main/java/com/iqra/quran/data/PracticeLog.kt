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
        val judged: Int get() = correct + wrong + skipped

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
        val surah: Int,
        val lockAyah: Int,
        val ended: Boolean,
        val reason: String,
        val wpm: Double,
        val moves: Int,
        val reversals: Int,
        val evaluations: Int,
        val unjudgeable: Int,
        val frames: Int,
        val correct: Int,
        val wrong: Int,
        val skipped: Int,
        val unknown: Int,
        /** surah -> judged word count, for per-surah mastery. */
        val perSurah: Map<Int, IntArray>,
        /** "surah:ayah:word" -> times it came out wrong or unknown. */
        val hardWords: Map<String, LongArray>,
    ) {
        val judged: Int get() = correct + wrong + skipped
        val accuracy: Float? get() = if (judged <= 0) null else correct.toFloat() / judged
        val dayKey: Long get() = dayKeyOf(startedAt)
    }

    // ---- derived ----------------------------------------------------------

    data class SurahProgress(
        val surah: Int,
        val sessions: Int,
        val ayahs: Int,
        val correct: Int,
        val wrong: Int,
        val skipped: Int,
        val lastPractised: Long,
    ) {
        val judged: Int get() = correct + wrong + skipped
        val accuracy: Float? get() = if (judged <= 0) null else correct.toFloat() / judged

        /**
         * 0..1 for a progress bar, and deliberately not [accuracy].
         *
         * One perfect surah is not a mastered surah, so accuracy is weighted by
         * how much has actually been recited through. The volume term is
         * logarithmic, so the bar keeps moving after the first few sessions
         * instead of pinning at full immediately.
         */
        val mastery: Float
            get() {
                val a = accuracy ?: 0f
                val volume = (kotlin.math.ln(1f + ayahs) / kotlin.math.ln(1f + 60f))
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

    data class Summary(
        val sessions: Int,
        val judgedWords: Int,
        val ayahs: Int,
        val accuracy: Float?,
        val currentStreak: Int,
        val longestStreak: Int,
        val surahs: List<SurahProgress>,
        val days: List<Long>,
        val recent: List<Record>,
        val hardest: List<HardWord>,
        val recordsIngested: Int,
        val wordsSeen: Long,
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
        var ayahs = 0L
        val surahs = HashMap<Int, IntArray>()      // correct, wrong, skipped, unknown, sessions
        val hard = HashMap<String, LongArray>()   // misses, lastAt
        val days = TreeSetSet()
        val recent = ArrayList<String>()           // record names, newest first
        val folded = HashMap<String, Long>()       // name -> flushedAt folded
    }

    /** A sorted set of Long day keys, without pulling in java.util.TreeSet ceremony. */
    private class TreeSetSet {
        private val m = HashMap<Long, Boolean>()
        fun add(k: Long) { m[k] = true }
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
        runCatching { liveFile(ctx).delete() }
        runCatching { File(ctx.filesDir, AGG).delete() }
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
            if (seen != null && seen >= flushed) return@forEach
            val r = try { parseRecord(f.name, f.readText()) } catch (e: Exception) {
                Log.w(TAG, "unreadable session record ${f.name}", e); null
            }
            agg.folded[f.name] = flushed
            if (r == null) return@forEach
            // A re-fold of the same name replaces its contribution rather than
            // adding to it, which is why the old numbers are subtracted first.
            agg.recent.remove(f.name)
            agg.sessions++
            agg.correct += r.correct; agg.wrong += r.wrong
            agg.skipped += r.skipped; agg.unknown += r.unknown
            agg.ayahs += r.perSurah.size
            agg.days.add(r.dayKey)
            r.perSurah.forEach { (s, a) ->
                val acc = agg.surahs.getOrPut(s) { IntArray(5) }
                acc[0] += a[0]; acc[1] += a[1]; acc[2] += a[2]; acc[3] += a[3]; acc[4]++
            }
            r.hardWords.forEach { (k, v) ->
                val h = agg.hard.getOrPut(k) { LongArray(2) }
                h[0] += v[0].toLong(); h[1] = maxOf(h[1], v[1])
            }
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
        val hard = LinkedHashMap<String, LongArray>()
        var correct = 0; var wrong = 0; var skipped = 0; var unknown = 0
        val ah = o.optJSONArray("ayahStatus")
        if (ah != null) {
            for (i in 0 until ah.length()) {
                val a = ah.optJSONObject(i) ?: continue
                val key = a.optString("ayah")
                val s = key.substringBefore(':').toIntOrNull() ?: continue
                val v = per.getOrPut(s) { IntArray(4) }
                val cc = a.optInt("correct", 0); val ww = a.optInt("wrong", 0)
                val ss = a.optInt("skipped", 0); val uu = a.optInt("unknown", 0)
                v[0] += cc; v[1] += ww; v[2] += ss; v[3] += uu
                correct += cc; wrong += ww; skipped += ss; unknown += uu
            }
        }
        // `words` holds only WRONG and UNKNOWN by the engine's own design, which
        // is exactly the set a "hardest words" list is made of.
        val ws = o.optJSONArray("words")
        if (ws != null) {
            for (i in 0 until ws.length()) {
                val w = ws.optJSONObject(i) ?: continue
                val k = w.optString("key")
                if (k.isEmpty()) continue
                val st = w.optString("st")
                val bump = if (st == "WRONG") 2 else 1
                val cur = hard.getOrPut(k) { LongArray(2) }
                cur[0] += bump; cur[1] = o.optLong("startedAt", 0L)
            }
        }
        return Record(
            name = name,
            flushedAt = o.optLong("flushedAt", 0L),
            startedAt = o.optLong("startedAt", 0L),
            surah = o.optInt("surahStart", 0),
            lockAyah = o.optInt("lockNow", 0),
            ended = o.optBoolean("ended", false),
            reason = o.optString("reason", ""),
            wpm = o.optDouble("wpmEma", 0.0),
            moves = c.optInt("moves", 0),
            reversals = c.optInt("reversals", 0),
            evaluations = c.optInt("evaluations", 0),
            unjudgeable = c.optInt("unjudgeableAyahs", 0),
            frames = o.optJSONArray("frames")?.length() ?: 0,
            correct = correct, wrong = wrong, skipped = skipped, unknown = unknown,
            perSurah = per, hardWords = hard,
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
            a.ayahs = o.optLong("a", 0)
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
            o.optJSONObject("folded")?.let { fo ->
                fo.keys().forEach { key -> a.folded[key] = fo.optLong(key, 0L) }
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
            o.put("k", a.skipped); o.put("u", a.unknown); o.put("a", a.ayahs)
            o.put("surahs", JSONObject().apply {
                a.surahs.forEach { (k, v) -> put("$k", JSONArray(v.toList())) }
            })
            o.put("hard", JSONObject().apply {
                a.hard.forEach { (k, v) -> put(k, JSONArray(v.toList())) }
            })
            o.put("days", JSONArray().apply { a.days.sorted().forEach { put(it) } })
            o.put("recent", JSONArray().apply { a.recent.forEach { put(it) } })
            o.put("folded", JSONObject().apply { a.folded.forEach { (k, v) -> put(k, v) } })
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
    fun summarise(ctx: Context): Summary {
        ingest(ctx)
        val a = loadAgg(ctx)
        val days = a.days.sorted()
        val judged = (a.correct + a.wrong + a.skipped).toInt()

        // Live rows the engine's own records never saw - a session that ended
        // before its record was flushed still counts.
        val live = load(ctx)
        val liveOnly = live.filter { l ->
            days.none { d -> kotlin.math.abs(d - dayKeyOf(l.millis)) == 0L } ||
                a.surahs[l.surah] == null
        }
        val liveCorrect = liveOnly.sumOf { it.correct }
        val liveWrong = liveOnly.sumOf { it.wrong }
        val liveSkipped = liveOnly.sumOf { it.skipped }
        val totalCorrect = a.correct + liveCorrect
        val totalWrong = a.wrong + liveWrong
        val totalSkipped = a.skipped + liveSkipped
        val totalJudged = (totalCorrect + totalWrong + totalSkipped).toInt()

        // Per-surah mastery is assembled from BOTH stores. Folding only the
        // engine records left the list empty, because those records carry no
        // verdicts at all - the live rows are the only place the numbers exist,
        // so ignoring them meant "By surah" always read zero while the accuracy
        // tile above it showed a real figure. Two numbers from one screen, one
        // of them a lie by omission.
        val bySurah = HashMap<Int, SurahProgress>()
        a.surahs.entries.filter { it.key > 0 }.forEach { (s, v) ->
            bySurah[s] = SurahProgress(
                surah = s, sessions = v[4], ayahs = 0,
                correct = v[0], wrong = v[1], skipped = v[2],
                lastPractised = 0L,
            )
        }
        live.forEach { l ->
            if (l.surah <= 0) return@forEach
            val p = bySurah[l.surah]
            bySurah[l.surah] = if (p == null) {
                SurahProgress(
                    surah = l.surah, sessions = 1, ayahs = l.ayahs,
                    correct = l.correct, wrong = l.wrong, skipped = l.skipped,
                    lastPractised = l.millis,
                )
            } else {
                p.copy(
                    sessions = p.sessions + 1,
                    ayahs = p.ayahs + l.ayahs,
                    correct = p.correct + l.correct,
                    wrong = p.wrong + l.wrong,
                    skipped = p.skipped + l.skipped,
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

        return Summary(
            sessions = a.sessions + liveOnly.size,
            judgedWords = totalJudged,
            ayahs = a.ayahs.toInt() + liveOnly.size,
            accuracy = if (totalJudged > 0) totalCorrect.toFloat() / totalJudged else null,
            currentStreak = currentStreak(allDays),
            longestStreak = longestRun(allDays),
            surahs = surahs,
            days = allDays,
            recent = recent,
            hardest = hardest,
            recordsIngested = a.sessions,
            wordsSeen = a.hard.values.sumOf { it[0].toLong() },
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
            val judged = correct + wrong + skipped
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
    fun isWorthRecording(s: Session): Boolean = s.judged >= 3
}
