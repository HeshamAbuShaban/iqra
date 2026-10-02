package com.iqra.quran.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * What the user actually practised, kept across process death.
 *
 * Until this existed the app remembered two things: which page was last open,
 * and which pages were bookmarked. Both are navigation. Nothing recorded that
 * you had recited, so a session ended and its result - the one thing worth
 * coming back to - was gone. The engine keeps very good telemetry (`diag.log`,
 * with moves, reversals, jumps, stalls), but that is written for debugging and
 * is never pruned, so it cannot also be the thing a user reads.
 *
 * So this is a second, deliberately small record: one row per recitation, with
 * the counts that a person can act on, and nothing that would make it grow
 * without bound.
 *
 * ## What is NOT stored
 *
 * The per-word verdicts. They are the richest thing the engine produces and the
 * worst thing to persist: `WordStatus` is a judgement about one audio attempt,
 * and yesterday's "WRONG" on a word is not today's. Storing them would invite
 * the UI to render a stale verdict as if it were current. Aggregates survive
 * the same rewrite; verdicts do not.
 *
 * ## Storage
 *
 * A single JSON array under `filesDir`, rewritten whole on each append. That is
 * the right trade at this size: [MAX_RECORDS] rows of eight small fields is
 * tens of kilobytes, and a whole-file rewrite cannot leave a half-written log
 * behind the way an append can. Written to a `.tmp` and renamed, the same
 * atomic pattern [DataFetcher] uses for downloads.
 *
 * Not `java.time`: `minSdk` is 24 and there is no core-library desugaring in the
 * build, so day keys come from [Calendar].
 */
object PracticeLog {
    private const val FILE = "practice.json"
    private const val TAG = "PracticeLog"

    /**
     * Cap on retained rows.
     *
     * 500 sessions is several months of daily use. Past that the oldest rows
     * stop describing how the user practises now, which is the only reason to
     * keep them, and the file is rewritten on every append so its size is on
     * the critical path of starting a session.
     */
    const val MAX_RECORDS = 500

    // ---- the record ------------------------------------------------------

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
        /** Words the engine actually judged. `UNKNOWN` is excluded on purpose. */
        val judged: Int get() = correct + wrong + skipped

        /**
         * Share of judged words marked CORRECT.
         *
         * Null when nothing was judged, which is a real outcome - a session can
         * record audio and never resolve a single word - and reporting 0% for it
         * would put a failure on the user's record for an absence of data.
         */
        val accuracy: Float?
            get() = if (judged <= 0) null else correct.toFloat() / judged

        fun toJson(): JSONObject = JSONObject().apply {
            put("s", surah)
            put("a", ayahs)
            put("c", correct)
            put("w", wrong)
            put("k", skipped)
            put("u", unknown)
            put("wpm", wpm)
            put("t", millis)
        }

        companion object {
            fun fromJson(o: JSONObject): Session? {
                val t = o.optLong("t", 0L)
                if (t <= 0L) return null
                return Session(
                    surah = o.optInt("s", 0),
                    ayahs = o.optInt("a", 0),
                    correct = o.optInt("c", 0),
                    wrong = o.optInt("w", 0),
                    skipped = o.optInt("k", 0),
                    unknown = o.optInt("u", 0),
                    wpm = o.optDouble("wpm", 0.0),
                    millis = t,
                )
            }
        }
    }

    // ---- derived ---------------------------------------------------------

    /**
     * One surah's accumulated record.
     *
     * [accuracy] is a word-weighted mean over sessions rather than a mean of
     * per-session means, so one long session is not given the same vote as one
     * that judged four words. A mean of means is the more flattering number and
     * the less honest one.
     */
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
         * 0..1 mastery band for the progress bar.
         *
         * Deliberately not [accuracy]: a surah recited once, perfectly, is not
         * mastered. Weighting accuracy by how much has been recited through makes
         * the bar mean "how much of this have you actually done, and how well",
         * which is the question the bar is answering.
         *
         * The volume term is `log10` rather than linear so the bar keeps moving
         * after the first few sessions instead of pinning at full immediately.
         */
        val mastery: Float
            get() {
                val a = accuracy ?: 0f
                val volume = kotlin.math.ln(1f + ayahs) / kotlin.math.ln(1f + 60f)
                return (a * volume.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            }
    }

    /**
     * Everything the log can say.
     *
     * There is deliberately no session-duration field. The engine knows how long
     * a session ran, but wiring that through means touching the recorder, and a
     * duration estimated from word counts would be a number on the progress
     * screen that nobody could trust - which is worse than the field being
     * absent. [Session] carries a timestamp, so real duration can be added
     * without inventing one.
     */
    data class Summary(
        val sessions: Int,
        val judgedWords: Int,
        val ayahs: Int,
        val accuracy: Float?,
        val currentStreak: Int,
        val longestStreak: Int,
        val surahs: List<SurahProgress>,
        /** Ascending by day key, most recent last. One entry per active day. */
        val days: List<Long>,
        val recent: List<Session>,
    ) {
        val activeDays: Int get() = days.size
    }

    // ---- io --------------------------------------------------------------

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): List<Session> {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            // Newest first in the file, so the cap at the end keeps the newest.
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(Session::fromJson)
            }.sortedByDescending { it.millis }
        } catch (e: Exception) {
            // A corrupt log must never stop the app opening. Losing the history
            // is recoverable; refusing to launch because of it is not.
            Log.w(TAG, "practice log unreadable, starting empty", e)
            emptyList()
        }
    }

    /**
     * Append one session, trimming to [MAX_RECORDS].
     *
     * Rows for the same surah on the same day are merged rather than stacked.
     * Without that, five retries of one difficult ayah render as five sessions
     * and a five-session streak on a single afternoon - which is the exact lie
     * a streak is supposed to be immune to.
     */
    fun append(ctx: Context, s: Session) {
        val existing = load(ctx).toMutableList()
        val today = dayKey(s.millis)
        val i = existing.indexOfFirst {
            it.surah == s.surah && dayKey(it.millis) == today
        }
        if (i >= 0) {
            val old = existing[i]
            existing[i] = Session(
                surah = old.surah,
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
        write(ctx, existing.take(MAX_RECORDS))
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
            Log.w(TAG, "could not clear practice log", e)
        }
    }

    private fun write(ctx: Context, rows: List<Session>) {
        try {
            val arr = JSONArray()
            rows.forEach { arr.put(it.toJson()) }
            val f = file(ctx)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(arr.toString())
            // Rename is atomic within a directory, so a kill mid-write leaves
            // the previous log whole rather than a truncated one.
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write practice log", e)
        }
    }

    // ---- day keys --------------------------------------------------------

    /**
     * Local-calendar day as `YYYYMMDD`, so days sort as numbers and a streak is
     * an arithmetic walk rather than a timezone-sensitive instant comparison.
     */
    fun dayKey(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        val y = c.get(Calendar.YEAR).toLong()
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return y * 10_000 + m * 100 + d
    }

    private fun shiftDay(key: Long, by: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set((key / 10_000).toInt(), ((key / 100) % 100 - 1).toInt(), (key % 100).toInt())
        c.add(Calendar.DAY_OF_MONTH, by)
        return dayKey(c.timeInMillis)
    }

    // ---- summarise -------------------------------------------------------

    fun summarise(sessions: List<Session>): Summary {
        if (sessions.isEmpty()) {
            return Summary(0, 0, 0, null, 0, 0, emptyList(), emptyList(), emptyList())
        }
        val judged = sessions.sumOf { it.judged }
        val correct = sessions.sumOf { it.correct }
        val days = sessions.map { dayKey(it.millis) }.distinct().sorted()

        return Summary(
            sessions = sessions.size,
            judgedWords = judged,
            ayahs = sessions.sumOf { it.ayahs },
            accuracy = if (judged > 0) correct.toFloat() / judged else null,
            currentStreak = currentStreak(days),
            longestStreak = longestRun(days),
            surahs = perSurah(sessions),
            days = days,
            recent = sessions.take(20),
        )
    }

    /**
     * Consecutive days ending today, or ending yesterday if today is still empty.
     *
     * Counting from yesterday when today has no row yet matters: opening the app
     * in the morning before reciting would otherwise report a broken streak for
     * a day the user has not had a chance to practise on. A streak is meant to
     * be broken by a missed day, not by an early morning.
     */
    private fun currentStreak(days: List<Long>): Int {
        val today = dayKey(System.currentTimeMillis())
        val yesterday = shiftDay(today, -1)
        var cursor = when {
            days.contains(today) -> today
            days.contains(yesterday) -> yesterday
            else -> return 0
        }
        var streak = 0
        while (days.contains(cursor)) {
            streak++
            cursor = shiftDay(cursor, -1)
        }
        return streak
    }

    private fun longestRun(days: List<Long>): Int {
        var best = 0
        var run = 0
        var prev: Long? = null
        for (d in days) {
            run = if (prev != null && d == shiftDay(prev!!, 1)) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return best
    }

    private fun perSurah(sessions: List<Session>): List<SurahProgress> {
        val out = LinkedHashMap<Int, SurahProgress>()
        sessions.forEach { s ->
            val p = out[s.surah]
            out[s.surah] = if (p == null) {
                SurahProgress(s.surah, 1, s.ayahs, s.correct, s.wrong, s.skipped, s.millis)
            } else {
                p.copy(
                    sessions = p.sessions + 1,
                    ayahs = p.ayahs + s.ayahs,
                    correct = p.correct + s.correct,
                    wrong = p.wrong + s.wrong,
                    skipped = p.skipped + s.skipped,
                    lastPractised = maxOf(p.lastPractised, s.millis),
                )
            }
        }
        return out.values.sortedByDescending { it.lastPractised }
    }

    /**
     * Sessions that produced nothing worth recording are not recorded.
     *
     * A tap that is cancelled, or audio with no resolvable word, would
     * otherwise pad the history and dilute the accuracy figure with rows that
     * have no judged words. Returns false in that case so the caller can skip
     * the write entirely.
     */
    fun isWorthRecording(s: Session): Boolean = s.judged >= MIN_JUDGED

    private const val MIN_JUDGED = 3
}
