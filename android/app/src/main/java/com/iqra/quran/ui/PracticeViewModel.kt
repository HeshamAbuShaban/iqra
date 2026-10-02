package com.iqra.quran.ui

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iqra.quran.audio.AudioRecorder
import com.iqra.quran.data.Mushaf
import com.iqra.quran.data.MushafPage
import com.iqra.quran.data.MushafWord
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.GlyphCoords
import com.iqra.quran.data.WordStatus
import com.iqra.quran.ml.SherpaVad
import com.iqra.quran.ml.SherpaZipformer
import com.iqra.quran.ml.PhonemeMapper
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PracticeViewModel(app: Application) : AndroidViewModel(app) {
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _data = MutableStateFlow<QuranData?>(null)
    val data: StateFlow<QuranData?> = _data

    private val _mushaf = MutableStateFlow<List<MushafPage>?>(null)
    val mushaf: StateFlow<List<MushafPage>?> = _mushaf

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording


    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing

    private val _hideVerse = MutableStateFlow(false)
    val hideVerse: StateFlow<Boolean> = _hideVerse

    private val _statusMap = MutableStateFlow<Map<String, WordStatus>>(emptyMap())
    val statusMap: StateFlow<Map<String, WordStatus>> = _statusMap

    private val _currentKey = MutableStateFlow<String?>(null)
    val currentKey: StateFlow<String?> = _currentKey

    private val _currentPage = MutableStateFlow<Int?>(null)
    val currentPage: StateFlow<Int?> = _currentPage

    private val _activeVerse = MutableStateFlow<Int?>(null)
    val activeVerse: StateFlow<Int?> = _activeVerse


    /** Ayah search index, built once off the main thread. */
    @Volatile private var searchIndex: com.iqra.quran.data.AyahSearch? = null

    fun searchAyat(q: String): List<com.iqra.quran.data.AyahSearch.Hit> =
        searchIndex?.query(q) ?: emptyList()

    fun ensureSearchIndex(d: com.iqra.quran.data.QuranData) {
        if (searchIndex != null) return
        viewModelScope.launch(Dispatchers.IO) {
            searchIndex = com.iqra.quran.data.AyahSearch.build(d.verses)
        }
    }

    private val _engineLabel = MutableStateFlow("")
    val engineLabel: StateFlow<String> = _engineLabel

    private val _lastMatch = MutableStateFlow<Pair<Int, Double>?>(null)
    val lastMatch: StateFlow<Pair<Int, Double>?> = _lastMatch
    private val _wpmFlow = MutableStateFlow(70.0)
    val wpmFlow: StateFlow<Double> = _wpmFlow
    private val _gateReason = MutableStateFlow("")
    val gateReason: StateFlow<String> = _gateReason

    /**
     * What the lock is actually deciding on, this frame.
     *
     *  The Diagnostics screen used to show only an ARGMAX over the locked ayah
     *  and the next one. That is close to useless for the question it is asked:
     *  "why did it not follow?" A value of `1 @ 0.95` can only mean the lock is
     *  on ayah 1 and is scoring it well - which says nothing about the number
     *  the policy actually advances on. So the three coverages are published
     *  alongside the thresholds they are compared against, and "held" is
     *  derived: the single most useful thing to see is that the gate was closed
     *  and nothing reached the decoder at all.
     */
    data class PolicyLive(
        val next: Float = -1f,
        val here: Float = -1f,
        val back: Float = -1f,
        val gateClosed: Long = 0,
        val held: String = "",
        val vadSpeech: Boolean? = null,
        // Seconds since the lock last moved, and why it has not moved.
        //
        // A frozen highlight is the one failure the user cannot diagnose for
        // themselves: the readout looked identical on a normal frame and on the
        // 86 seconds where a refrain kept the locked ayah scoring 1.00 while
        // the successor sat in the dead band. The corpus found that case
        // (Ar-Rahman 55) and the app had no way to show it.
        val stallSec: Float = 0f,
        val stallNote: String = "",
    )

    private val _policyLive = MutableStateFlow(PolicyLive())
    val policyLive: StateFlow<PolicyLive> = _policyLive

    /** The thresholds, as text, so a readout never disagrees with the policy. */
    fun policyNeeds(): Triple<Float, Float, Float> =
        Triple(ADVANCE_COVERAGE, STUCK_COVERAGE, BACK_COVERAGE)

    /**
     * The event ring, written by the recognition coroutine (IO) and by the
     *  main thread (jump/anchor/resume).
     *
     *  This was a plain java.util.ArrayDeque, which is not thread-safe: a
     *  resize during a concurrent add could silently drop or duplicate
     *  events - the failure mode where the log simply omits the one line that
     *  mattered. ConcurrentLinkedDeque makes the mutation safe from both
     *  threads.
     */
    private val diagBuffer = ConcurrentLinkedDeque<String>()
    private val _diagLog = MutableStateFlow<List<String>>(emptyList())
    val diagLog: StateFlow<List<String>> = _diagLog

    /**
     * Disk writes run on their own thread with a BOUNDED queue.
     *
     *  Every diag() used to open/write/close the file inline, on the same
     *  thread that feeds and decodes audio - so logging a lock move cost the
     *  recogniser a file round-trip, worst exactly at the moments worth
     *  logging. If the writer falls behind, lines are dropped and counted
     *  (diagDropped, reported in the session summary) rather than allowed to
     *  block the loop; diagnostics are not worth stalling recognition for.
     */
    private val diagDropped = java.util.concurrent.atomic.AtomicLong(0)
    private val diagIo = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(256),
        { r -> Thread(r, "iqra-diag") },
        { _, _ -> diagDropped.incrementAndGet() },
    )

    /**
     * Ring-buffer diagnostic event: state changes + errors only, never
     * per-frame spam (per-frame evidence lives in [frameRingDump]). Powers
     * the diagnostics screen; no logcat needed.
     *
     *  The stamp keeps the wall-clock second for human reading and adds
     *  SystemClock.elapsedRealtime(). The old stamp alone was second
     *  resolution with no date and wrapped every ~27.8h, so a pulled log
     *  could not be ordered and could not be split into sessions; the
     *  monotonic component can. `g<sessionGen>` groups lines by session
     *  across a restart.
     *
     *  `gen` overrides the stamp for the one line that cannot use the live
     *  counter: `session end`. stopRecite bumps sessionGen to retire the
     *  polling loop BEFORE tearing down, so the live value there is the NEXT
     *  session's, and every end line was filed under the session that followed
     *  it. The end line is what a reader groups a session by.
     */
    fun diag(msg: String, gen: Int = sessionGen) {
        val line = "${(System.currentTimeMillis() / 1000) % 100000}" +
            ".${SystemClock.elapsedRealtime()} g$gen $msg"
        diagBuffer.addLast(line)
        while (diagBuffer.size > DIAG_RING) diagBuffer.pollFirst()
        _diagLog.value = ArrayList(diagBuffer)
        try {
            diagIo.execute { appendDiagFile(line) }
        } catch (_: RejectedExecutionException) {
            // Only reachable after onCleared() shut the writer down.
            diagDropped.incrementAndGet()
        }
    }

    private fun diagFile(name: String) = File(getApplication<Application>().filesDir, name)

    /**
     * Mirror the event ring to disk. The in-memory copy is lost on process
     * death, which is exactly when you most want to read why a session went
     * wrong - force-stopping the app used to erase the evidence.
     *
     *  The file used to be WIPED outright once it passed 512 KiB, which
     *  destroyed every prior session INCLUDING the one being debugged, with
     *  no marker that anything had been lost. It rotates into numbered
     *  archives instead, and stamps the build so two logs pulled from two
     *  builds are not silently compared.
     */
    private fun appendDiagFile(line: String) {
        try {
            val f = diagFile(DIAG_FILE)
            if (f.length() > DIAG_MAX_BYTES) rotateDiagFiles()
            if (!f.exists()) {
                val stamp = "# iqra-diag v1 build=${buildTag.ifEmpty { "?" }}" +
                    " start=${System.currentTimeMillis()} kept=${DIAG_KEEP - 1}"
                f.writeText("$stamp\n")
            }
            f.appendText(line + "\n")
        } catch (t2: Throwable) {
            // diagnostics must never break recognition
        }
    }

    /** diag.log -> diag.1.log -> ... -> diag.$DIAG_KEEP.log, oldest archive
     *  first, so the sessions surrounding the one being debugged survive. */
    private fun rotateDiagFiles() {
        val cur = diagFile(DIAG_FILE)
        if (!cur.exists()) return
        for (i in DIAG_KEEP downTo 1) {
            val a = diagFile("diag.$i.log")
            if (i == DIAG_KEEP) {
                a.delete()
                continue
            }
            if (a.exists()) a.renameTo(diagFile("diag.${i + 1}.log"))
        }
        cur.renameTo(diagFile("diag.1.log"))
    }

    /**
     * Earlier sessions' events, oldest first and newest last, for the
     * diagnostics screen: the current file plus every rotated archive, so a
     * process death still shows what the session that died was doing. This
     * had no caller at all - the file was written for exactly the case
     * (process death) that the screen then refused to show.
     */
    fun persistedDiag(): List<String> = try {
        val out = ArrayList<String>(DIAG_RING)
        for (i in DIAG_KEEP downTo 1) collectDiagLines(diagFile("diag.$i.log"), out)
        collectDiagLines(diagFile(DIAG_FILE), out)
        out.takeLast(DIAG_RING)
    } catch (t: Throwable) {
        emptyList()
    }

    private fun collectDiagLines(f: File, out: ArrayList<String>) {
        if (!f.isFile) return
        try {
            f.forEachLine(Charsets.UTF_8) { out.add(it) }
        } catch (_: Throwable) {
        }
    }

    /** Clears the current file AND the archives: the old version emptied
     *  only diag.log and left the rotated copies looking like live data. */
    fun clearPersistedDiag() {
        try {
            diagFile(DIAG_FILE).delete()
            for (i in 1..DIAG_KEEP) diagFile("diag.$i.log").delete()
        } catch (t: Throwable) {
        }
    }

    data class EngineFileInfo(val name: String, val present: Boolean, val detail: String, val fix: String?)

    /** Snapshot for the diagnostics screen: every gated file + engine state. */
    fun engineFilesInfo(): List<EngineFileInfo> {
        val app = getApplication<Application>()
        fun info(name: String, f: File, fix: String): EngineFileInfo {
            val ok = f.exists() && f.length() > 0
            val detail = if (!ok) "missing"
            else if (f.length() < 1048576) "${f.length() / 1024} KB"
            else "%.1f MB".format(f.length() / 1048576.0)
            return EngineFileInfo(name, ok, detail, if (ok) null else fix)
        }
        return listOf(
            info("model.int8.onnx", com.iqra.quran.data.AssetPaths.file(app, "model.int8.onnx"), "copy model.int8.onnx → /sdcard/Iqra/"),
            info("tokens.txt", com.iqra.quran.data.AssetPaths.file(app, "tokens.txt"), "copy tokens.txt → /sdcard/Iqra/"),
            info("ordered_quran_phonemes.json", com.iqra.quran.data.AssetPaths.file(app, "ordered_quran_phonemes.json"), "fetched automatically, or copy → /sdcard/Iqra/"),
            info("silero_vad.onnx", com.iqra.quran.data.AssetPaths.file(app, "silero_vad.onnx"), "fetched automatically, or copy → /sdcard/Iqra/"),
        )
    }

    fun micLevel(): Float {
        val s = recorder.currentSamples()
        if (s.isEmpty()) return 0f
        // Mean over the samples actually present. Dividing by a fixed 8000
        // under-reported the level by the ratio of missing samples, so a
        // "near-silence" hint could fire on a perfectly healthy mic during the
        // first half-second of a session.
        val tail = s.takeLast(8000)
        var sum = 0.0
        for (v in tail) sum += v * v
        return kotlin.math.sqrt(sum / tail.size).toFloat()
    }

    fun micSampleCount(): Int = recorder.sampleCount()

    /**
     * One-line streaming counters for the diagnostics screen.
     *
     *  SherpaZipformer.acceptedSamples and decodeCalls are process-lifetime
     *  and never reset anywhere, so they were labelled `fed=` and kept
     *  climbing across sessions: a dead second session still showed a large
     *  rising number, which is the same "audio was flowing" illusion that
     *  hid the dead-stream bug, still live on the screen. The lifetime pair
     *  is now named `fedProc=` for what it is, and the honest session-scoped
     *  count `fedSess` leads the line. fedSess is NOT fedTotal: that is
     *  zeroed by every lock move (it is the clock of the current stream),
     *  so it can never stand for a session.
     */
    fun streamStats(): String =
        "fedSess=$sessionFed fedProc=${SherpaZipformer.acceptedSamples}" +
            " toks=$lastEmitCount decodes=${SherpaZipformer.decodeCalls}" +
            " fedStream=$fedTotal" +
            (SherpaZipformer.lastOpError?.let { " ERR=$it" } ?: "")

    // ---- Per-frame lock-decision ring (offline replay evidence) ----
    /**
     * Every frame's lock-decision inputs, in preallocated fixed storage.
     *
     *  These values were computed and discarded every frame, so a session
     *  could only be reconstructed from the event log - which records what
     *  happened and never why. `lock` is the lock the decision was taken
     *  ABOUT (not the post-move value), so nextCov/hereCov/backCov always
     *  mean coverage of lock+1, lock and lock-1 respectively, whatever the
     *  decision then did. A coverage of -1 means the column was not
     *  computed on that frame's path.
     */
    private val frameRing = FrameRing(FRAME_RING)

    /**
     * The whole ring as TSV, oldest frame first, for offline replay. The
     * header carries the policy that produced it - all seven lock
     * thresholds BY NAME, the mic floor, the counters - so an analysis
     * script never has to hard-code the tuning, and a dump from a retuned
     * build cannot be misread against the previous build's thresholds.
     */
    fun frameRingDump(): String {
        val sb = StringBuilder(FRAME_RING * 64 + 1024)
        sb.append("#iqra-frame-ring v1")
            .append(" build=").append(buildTag.ifEmpty { "?" })
            .append(" surah=").append(activeSurah)
            .append(" wpmEma=").append(f1(wpmEma))
            .append(" noiseFloor=").append(f3(noiseFloor))
            .append(" gateClosedFrames=").append(gateClosedFrames)
            .append(" fedTotal=").append(fedTotal)
            .append(" fedSess=").append(sessionFed)
            .append(" sessionGen=").append(sessionGen)
            .append(" ADVANCE_COVERAGE=").append(f3(ADVANCE_COVERAGE))
            .append(" STRONG_COVERAGE=").append(f3(STRONG_COVERAGE))
            .append(" WEAK_COVERAGE=").append(f3(WEAK_COVERAGE))
            .append(" JUMP_COVERAGE=").append(f3(JUMP_COVERAGE))
            .append(" BACK_COVERAGE=").append(f3(BACK_COVERAGE))
            .append(" HANDOFF_COVERAGE=").append(f3(HANDOFF_COVERAGE))
            .append(" STUCK_COVERAGE=").append(f3(STUCK_COVERAGE))
            .append(" tags=0:decided,1:empty_slice,2:handoff")
            .append(" tag2_nextCov_is=handoff_coverage")
            .append(" rows=").append(frameRing.rowCount())
            .append('/').append(FRAME_RING)
            .append(" oldest_first tRel_ms=since_session_start")
            .append('\n')
        sb.append("#tRel\tsurah\tlock\tnextCov\thereCov\tbackCov\tjumpTo\tjumpCov")
            .append("\tsyms\tobsN\tgen\ttag\n")
        frameRing.appendRows(sb)
        return sb.toString()
    }

    private val _engineHint = MutableStateFlow<String?>(null)
    val engineHint: StateFlow<String?> = _engineHint

    /** Installed build tag (CI run number) so builds are verifiable on-device. */
    val buildTag: String by lazy {
        try {
            val app = getApplication<Application>()
            val code = if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.packageManager.getPackageInfo(
                    app.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                ).versionCode
            } else {
                @Suppress("DEPRECATION") app.packageManager.getPackageInfo(app.packageName, 0).versionCode
            }
            "#$code"
        } catch (_: Exception) {
            ""
        }
    }

    private val prefs = app.getSharedPreferences("iqra", Context.MODE_PRIVATE)
    private val _lastRead = MutableStateFlow(loadLast())
    val lastRead: StateFlow<Pair<Int, Int>?> = _lastRead

    private fun loadLast(): Pair<Int, Int>? {
        val s = prefs.getInt("last_surah", -1)
        val p = prefs.getInt("last_page", -1)
        return if (s > 0 && p > 0) s to p else null
    }

    /**
     * Records the last page read. The surah is derived FROM the page, not
     * passed in: the reader screen's surah parameter is frozen at open time, so
     * passing it stored nonsense like (Al-Fatiha, page 6) after swiping into
     * Al-Baqarah.
     */
    fun saveLastRead(page: Int) {
        val (s, _) = anchorForPage(page) ?: return
        prefs.edit().putInt("last_surah", s).putInt("last_page", page).apply()
        _lastRead.value = s to page
    }

    fun lastReadPage(): Int? = _lastRead.value?.second

    /** Explicitly go back to where the reader was last left. */
    fun resumeLastRead(): Boolean {
        val p = _lastRead.value?.second ?: return false
        jumpToPage(p)
        diag("resume → p=$p")
        return true
    }

    private val _bookmarks = MutableStateFlow(loadBookmarks())
    val bookmarks: StateFlow<Set<Int>> = _bookmarks

    private fun loadBookmarks(): Set<Int> {
        val raw = prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun toggleBookmark(page: Int) {
        val cur = _bookmarks.value.toMutableSet()
        if (!cur.add(page)) cur.remove(page)
        prefs.edit().putStringSet("bookmarks", cur.map { it.toString() }.toSet()).apply()
        _bookmarks.value = cur
    }

    private val recorder = AudioRecorder(16000)
    private var activeSurah: Int = 1
    private var lockedAyah: Int = 1
    @Volatile private var pageNumber: Int = 1
    private var verseWords: Map<Int, List<MushafWord>> = emptyMap()
    private var versePage: Map<Int, Int> = emptyMap()
    private var pendingNextAyah: Int? = null
    private var pendingBackAyah: Int? = null
    private var pendingBackFrames: Int = 0
    private var pendingNextFrames: Int = 0
    private var zipformerOn = false
    /**
     * Bumped whenever a session starts or is torn down. The polling loop
     * captures the value it started with and exits if it changes, so a loop
     * left over from a previous session can never feed the stream that a newer
     * session just created.
     */
    @Volatile private var sessionGen = 0
    /**
     * Session accounting for the end-of-session summary. fedTotal is NOT one
     * of these: advanceLockTo zeroes it on every move (it is the clock of the
     * current stream), so a session total needs its own counter fed from the
     * same place fedTotal is fed. gateClosedFrames is the time the silence
     * gate rejected, which used to be time the decoder never saw - silent,
     * and therefore indistinguishable from a decoder that had nothing.
     */
    private var sessionStartMono = SystemClock.elapsedRealtime()
    private var sessionRan = false
    private var sessionFed = 0L
    private var gateClosedFrames = 0L
    private var sessionMoves = 0
    private var sessionReversals = 0
    private var sessionBackMoves = 0
    private var sessionJumps = 0
    private var lastMoveDir = 0
    /** Absolute samples consumed from the recorder's cumulative buffer. Long,
     *  because that counter is Long and it keeps climbing for the life of the
     *  session. */
    private var fedAbs = 0L
    private val _decoderState = MutableStateFlow("idle")
    val decoderState: StateFlow<String> = _decoderState
    private var noiseFloor = SILENCE_RMS
    private var lastTokenTime = 0L
    private var lastRecoveryTime = 0L
    /** Consecutive zero-token recovery attempts; escalates to a real error
     *  instead of looping a reset that can never succeed. */
    private var starvedRecoveries = 0
    private var lastFrameError: String? = null
    // Streaming clock + tail replay: bounds emission history without losing
    // rolling context on advance. fedTotal counts every fed sample;
    // streamBaseSec carries pre-reset audio time for word timing.
    private var fedTotal = 0L
    private var streamBaseSec = 0f
    private var tailBuf = FloatArray(0)
    private var lastEmitCount = 0
    // Per-ayah emission slice: window ayat align against emissions heard
    // since the lock last moved — never against other ayat's history.
    // Kills cross-ayah ghost matches (partial reveals of unrecited text).
    private var sliceStart = 0
    private var rebaseSlice = false

    // When the lock last moved, on the wall clock. Used ONLY by the pinned-lock
    // escape below; nothing else reads it.
    private var lastLockMoveMs = 0L

    private val _activeWindow = MutableStateFlow<List<Int>>(emptyList())
    val activeWindow: StateFlow<List<Int>> = _activeWindow

    /** Narrow 2–3 ayah active window (locked±1) that matching, statuses and
     *  the hide overlay all consume, so attention stays on what the reciter
     *  is actually saying instead of the whole surah. */
    private fun computeWindow(): List<Int> {
        if (verseWords.isEmpty()) return emptyList()
        val keys = verseWords.keys
        val w = listOf(lockedAyah - 1, lockedAyah, lockedAyah + 1).filter { keys.contains(it) }
        if (w.isNotEmpty()) return w
        val nearest = keys.minOrNull() ?: return emptyList()
        return listOf(nearest)
    }

    private fun refreshWindow() {
        _activeWindow.value = computeWindow()
    }

    // ---- Recognition state ----
    private var wpmEma = 70.0
    private var lastAdvanceAt = 0L
    /** Consecutive backward moves; reset by any forward move. Read by retreatAllowed. */
    private var consecutiveRetreats = 0
    private val wrongStreak = mutableMapOf<String, Int>()
    /** Session verdicts per word key, retained when ayahs leave the active
     *  window so completed recitation stays visible (and stays revealed in
     *  hide mode) instead of reverting to untouched. Cleared on surah load,
     *  jump, anchor and new recitation sessions. */
    private val sessionStatuses = LinkedHashMap<String, WordStatus>()

    /** Advance the lock, measuring reciter speed from SPEECH-ACTIVE time on
     *  the finished ayah (wall silence excluded) so frame patience adapts
     *  to slow/fast reciters instead of fixed counts.
     *
     *  `kind` labels the caller (advance / back / jump) purely for the move
     *  counters the session summary reports. Every move in this file funnels
     *  through here - forward, backward recovery and gated long jump alike -
     *  so counting here is the only way the counters can agree with what the
     *  lock actually did. */
    /**
     * A retreat needs a budget, not just evidence. Two guards: it may not happen
     * within [MIN_RETREAT_GAP_MS] of the last lock move, and after
     * [MAX_CONSECUTIVE_RETREATS] in a row the lock must move forward again.
     *
     * Without these the lock can shuffle 4 -> 3 -> 4 indefinitely. The replay
     * harness reproduces exactly that on a real repetition
     * (engine/replay/hesitation_policy.py, scenario `repeat`), and because each
     * move rebases the emission slice the post-move `hereCov` is near-guaranteed
     * to fall below STUCK_COVERAGE - so the gate is at its weakest exactly when
     * the lock is most able to ping-pong.
     */
    private fun retreatAllowed(): Boolean {
        if (System.currentTimeMillis() - lastAdvanceAt < MIN_RETREAT_GAP_MS) return false
        return consecutiveRetreats < MAX_CONSECUTIVE_RETREATS
    }

    private fun advanceLockTo(next: Int, measureSpeed: Boolean = true, kind: String = "advance") {
        val prev = lockedAyah
        val now = System.currentTimeMillis()
        if (measureSpeed) {
            val dtSec = speechFramesSinceAdvance * SherpaVad.FEED_POLL_SEC
            val prevWords = verseWords[prev]?.size ?: 0
            if (dtSec in 2.0..180.0 && prevWords > 0) {
                val inst = prevWords / dtSec * 60.0
                wpmEma = (0.7 * wpmEma + 0.3 * inst).coerceIn(25.0, 160.0)
            }
        }
        lastAdvanceAt = now
        speechFramesSinceAdvance = 0
        lockedAyah = next
        // A reversal is a move whose direction differs from the previous
        // move's: ping-ponging is exactly what the back-coverage gate exists
        // to prevent, so the count is the evidence for whether it does.
        val dir = if (next > prev) 1 else if (next < prev) -1 else 0
        if (dir != 0) {
            sessionMoves++
            if (lastMoveDir != 0 && dir != lastMoveDir) sessionReversals++
            lastMoveDir = dir
        }
        consecutiveRetreats = if (dir < 0) consecutiveRetreats + 1 else 0
        if (kind == "back") sessionBackMoves++
        if (kind == "jump") sessionJumps++
        _wpmFlow.value = wpmEma
        diag("lock $prev → $next")
        pendingNextAyah = null; pendingNextFrames = 0
        pendingBackAyah = null; pendingBackFrames = 0
        lastLockMoveMs = System.currentTimeMillis()
        rebaseSlice = true
        // The stream is recycled below, so its token count restarts from zero
        // while the growth test compares against the PRE-move count. A fresh
        // stream that happened to reach exactly the old count was therefore
        // read as "no new tokens" and treated as starvation. Harmless on the
        // recorded clips - the pre-move counts are 145/75/55/59/85/83 and a
        // short fresh stream never matches one - but it is a real collision
        // waiting for the right utterance length.
        lastEmitCount = 0
        // And restart the idle clock. The starvation watchdog measures silence
        // from lastTokenTime, which a move does not touch - so a move late in a
        // pause inherited the pre-move idle count and could trip the recovery on
        // its very next frame. resetAudioPipeline() refreshes it; a plain move
        // recycles the stream just as surely and has to as well.
        lastTokenTime = now
        // Recycle the stream with tail replay: bounds emission history
        // (flat per-frame cost forever) while keeping rolling context, so
        // there is no dead zone after an advance.
        streamBaseSec += fedTotal / 16000f
        fedTotal = 0
        SherpaZipformer.resetStream()
        if (tailBuf.isNotEmpty() && SherpaZipformer.accept(tailBuf)) {
            fedTotal = tailBuf.size.toLong()
        }
    }

    /** Frames a WRONG flag must persist before it latches, scaled by measured
     *  words-per-minute so slow reciters' mid-word frames don't flash red. */
    private fun wrongLatchFrames(): Int =
        (1.2 * (60.0 / wpmEma) / SherpaVad.FEED_POLL_SEC).roundToInt().coerceIn(2, 8)

    private var speechFramesSinceAdvance = 0L

    /**
     * Replays the recent audio tail into a FRESH stream after the lock moves,
     * preserving rolling context while bounding emission history (flat
     * per-frame cost no matter how long the session runs). Stream clock
     * continuity is kept via streamBaseSec.
     */
    /**
     * Full audio pipeline reset: recorder buffer + stream + cursors + tail.
     * Call everywhere recorder.reset() is called while recording, so heard
     * audio is never re-fed as new and clocks never skew.
     */
    private fun resetAudioPipeline() {
        recorder.reset()
        SherpaZipformer.resetStream()
        fedAbs = 0
        fedTotal = 0
        streamBaseSec = 0f
        tailBuf = FloatArray(0)
        lastEmitCount = 0
        // The watchdog measures idle time from lastTokenTime. Without this the
        // clock kept running across resets, so idleSec grew without bound and
        // "starved 101s" was really "starved 1s after the fourth reset".
        lastTokenTime = System.currentTimeMillis()
        lastRecoveryTime = lastTokenTime
        // NOTE: starvedRecoveries is deliberately NOT cleared here. This
        // function is itself one of the recovery attempts, so clearing the
        // counter inside it would restart the escalation every time and restore
        // the endless reset loop. It is cleared when tokens actually arrive, and
        // at session start.
        diag("audio pipeline reset")
    }

    /**
     * Per-session UI state, cleared at both ends of a session.
     *
     *  _lastMatch is written in exactly one place and was never cleared, so
     *  after the first match of the process lifetime the "no ayah matched
     *  yet" hint could never fire again and a stale pair from an earlier
     *  session read as current evidence. _decoderState, _gateReason and
     *  _wpmFlow had the same problem: they kept displaying the previous
     *  session's "starved 42s" / "silence" / measured speed while a new
     *  session was just starting.
     */
    private fun resetSessionUi() {
        _lastMatch.value = null
        _decoderState.value = "idle"
        _gateReason.value = ""
        _policyLive.value = PolicyLive()
        _wpmFlow.value = SEED_WPM
    }

    /** Zero the per-session counters so a summary can never report a
     *  previous session's moves. */
    private fun resetSessionCounters() {
        sessionFed = 0
        gateClosedFrames = 0
        sessionMoves = 0
        sessionReversals = 0
        sessionBackMoves = 0
        sessionJumps = 0
        lastMoveDir = 0
    }

    /**
     * End-of-session summary, emitted from BOTH stopRecite paths.
     *
     *  stopRecite used to emit no diag() at all, and returned before
     *  anything else when the session had not reached the recording state -
     *  so a session cancelled while still preparing was completely silent,
     *  and a truncated session and a complete one produced structurally
     *  identical logs. `ran=no` is what tells the two apart, and `reason`
     *  is always one of: user stop, jump, preparing-cancelled.
     */
    private fun endSession(reason: String, via: String, ranGen: Int) {
        val dur = if (sessionRan) SystemClock.elapsedRealtime() - sessionStartMono else 0L
        diag(
            "session end reason=$reason via=$via ran=${if (sessionRan) "yes" else "no"}" +
                " durMs=$dur moves=$sessionMoves reversals=$sessionReversals" +
                " back=$sessionBackMoves jumps=$sessionJumps" +
                " lock=${activeSurah}:${lockedAyah}" +
                " fed=$sessionFed fedStream=$fedTotal" +
                " gateClosed=$gateClosedFrames frames=${frameRing.rowCount()}" +
                " diagDropped=${diagDropped.get()}",
            gen = ranGen,
        )
        sessionRan = false
        resetSessionCounters()
    }

    /** Build per-ayah word + page maps for a surah. The page always follows the
     *  locked verse (derived from it), so it can never jump to a wrong page. */
    private fun loadSurah(surah: Int) {
        val pages = _mushaf.value ?: return
        sessionStatuses.clear()
        activeSurah = surah
        val all = Mushaf.wordsForSurah(pages, surah)
        val byAyah = all.groupBy { it.verse }
        verseWords = byAyah.mapValues { (_, ws) -> ws.sortedBy { it.wordInVerse } }
        versePage = byAyah.mapValues { (_, ws) -> ws.minOf { it.page } }
    }

    /**
     * The anchor for a Mushaf page: the first ayah that BEGINS on it, as
     * (surah, ayah).
     *
     * "Begins on" is the important part. An ayah that continues from the
     * previous page has its opening words already scrolled past, so anchoring
     * there caps that ayah's achievable coverage below 1.0 and the lock could
     * never advance off it. If a page somehow starts no new ayah, fall back to
     * the last ayah that overlaps it.
     *
     * This is the single source of truth for "where is the reader". jumpToPage
     * and startRecite both call it, so the two can never drift apart again -
     * they did once, which is how a session could anchor to 1:1 while the
     * screen showed page 50 of another surah.
     */
    fun anchorForPage(page: Int): Pair<Int, Int>? {
        val pages = _mushaf.value ?: return null
        if (page < 1 || page > pages.size) return null
        val words = pages[page - 1].lines
            .filter { it.type == "text" }
            .flatMap { it.words ?: emptyList() }
        if (words.isEmpty()) return null
        // first ayah whose FIRST word is on this page
        words.firstOrNull { it.wordInVerse == 1 }
            ?.let { return it.surah to it.verse }
        // otherwise the last ayah that has any presence here
        return words.lastOrNull()?.let { it.surah to it.verse }
    }

    /**
     * Last ayah in scope for the current session: the last ayah present on the
     * page the reader is showing. Bounds the session to the screen, so a page
     * that ends mid-surah hands off to the next surah instead of wandering
     * through hundreds of ayat the user never asked about.
     */
    private fun scopeEndAyah(): Int? {
        val pages = _mushaf.value ?: return null
        val page = pageNumber
        if (page < 1 || page > pages.size) return null
        val words = pages[page - 1].lines
            .filter { it.type == "text" }
            .flatMap { it.words ?: emptyList() }
        if (words.isEmpty()) return null
        val here = words.map { it.surah to it.verse }
        // Only ayat of the surah we are on bound the session.
        return here.filter { it.first == activeSurah }.maxOfOrNull { it.second }
    }

    /** Jump to an arbitrary Mushaf page and resync the tracker to its anchor. */
    fun jumpToPage(page: Int) {
        // Unconditional: navigating away must also cancel a session that is
        // still preparing, not just one that is already recording.
        stopRecite("jump")
        val (s, a) = anchorForPage(page) ?: return
        loadSurah(s)
        lockedAyah = a
        lastLockMoveMs = System.currentTimeMillis()
        rebaseSlice = true
        diag("jump → s=$s:$a p=$page")
        wrongStreak.clear()
        sessionStatuses.clear()
        pendingAnchor = null
        refreshWindow()
        _activeVerse.value = null
        _currentKey.value = null
        _statusMap.value = emptyMap()
        setCurrentPage(page)
    }

    // ---- Ayah selection + repeat practice (long-press sheet) ----
    private val _selectedAyah = MutableStateFlow<String?>(null)
    val selectedAyah: StateFlow<String?> = _selectedAyah

    private val _repeatAyahKey = MutableStateFlow<String?>(null)
    val repeatAyahKey: StateFlow<String?> = _repeatAyahKey
    private val _repeatLeft = MutableStateFlow(0)
    val repeatLeft: StateFlow<Int> = _repeatLeft
    private var repeatAyah: Pair<Int, Int>? = null
    private var repeatLeftCount = 0
    private var pendingAnchor: Int? = null


    fun selectAyah(surah: Int, ayah: Int) {
        if (surah in 1..114 && ayah >= 1) _selectedAyah.value = "$surah:$ayah"
    }

    fun clearSelection() {
        _selectedAyah.value = null
    }

    fun pageOfVerse(surah: Int, ayah: Int): Int? {
        val pages = _mushaf.value ?: return null
        return pages.firstOrNull { pg ->
            pg.lines.any { ln ->
                ln.type == "text" && (ln.words ?: emptyList()).any { it.surah == surah && it.verse == ayah }
            }
        }?.page
    }

    fun ayahText(surah: Int, ayah: Int): String {
        val pages = _mushaf.value ?: return ""
        val ws = pages.flatMap { pg -> pg.lines.flatMap { it.words ?: emptyList() } }
            .filter { it.surah == surah && it.verse == ayah }
            .sortedBy { it.wordInVerse }
        if (ws.isEmpty()) return ""
        return ws.joinToString(" ") { it.text } + " ($surah:$ayah)"
    }

    fun startRepeat(surah: Int, ayah: Int, count: Int) {
        anchorToVerse(surah, ayah)
        if (verseWords[ayah].isNullOrEmpty() || activeSurah != surah) return
        repeatAyah = surah to ayah
        repeatLeftCount = count.coerceIn(1, 50)
        _repeatAyahKey.value = "$surah:$ayah"
        _repeatLeft.value = repeatLeftCount
    }

    fun cancelRepeat() {
        repeatAyah = null
        repeatLeftCount = 0
        _repeatAyahKey.value = null
        _repeatLeft.value = 0
    }

    /** Tap-an-ayah: move the practice anchor to a tapped verse. Keeps the
     *  recognition tuning untouched — only reseats lock/page like a swipe. */
    fun anchorToVerse(surah: Int, ayah: Int) {
        if (surah !in 1..114 || ayah < 1) return
        if (_mushaf.value == null) return
        if (surah != activeSurah) loadSurah(surah)
        if (verseWords[ayah].isNullOrEmpty()) return
        cancelRepeat()
        pendingAnchor = ayah
        pendingNextAyah = null; pendingNextFrames = 0
        pendingBackAyah = null; pendingBackFrames = 0
        wrongStreak.clear()
        sessionStatuses.clear()
        _statusMap.value = emptyMap()
        _currentKey.value = null
        val targetPage = versePage[ayah]
        if (targetPage != null && targetPage != pageNumber) {
            pageNumber = targetPage
            _currentPage.value = targetPage
        }
        lockedAyah = ayah
        lastLockMoveMs = System.currentTimeMillis()
        rebaseSlice = true
        _activeVerse.value = ayah
        refreshWindow()
        publishAnchor()
        diag("anchor → $surah:$ayah")
        if (_recording.value) resetAudioPipeline()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val d = QuranData.load(getApplication())
            com.iqra.quran.data.AyahSearch.build(d.verses).also { searchIndex = it }
            withContext(Dispatchers.Main) {
                _data.value = d
                _loading.value = false
            }
            // Reader pages + glyph copy finish in background after first
            // frame. Home is already up. No recognition index anymore.
            val m = Mushaf.load(getApplication())
            // The glyph DB is heavy data the user may not have copied yet. It
            // used to be loaded unguarded, so a fresh install with nothing
            // staged died with an unhandled FileNotFoundException on an IO
            // dispatcher before the UI could offer to fetch it. Word boxes only
            // affect highlighting and hide mode, so the reader is still useful
            // without them.
            val glyphs = runCatching { GlyphCoords.ensure(getApplication()) }
                .onFailure { _engineHint.value = "Word boxes unavailable — copy ayahinfo_1024.db into /sdcard/Iqra. Reading and searching still work." }
                .isSuccess
            withContext(Dispatchers.Main) {
                _mushaf.value = m
            }
        }
    }

    fun toggleHide() { _hideVerse.value = !_hideVerse.value }

    /** Pager bookkeeping only: records which page the user is viewing.
     *  Never touches the lock — the lock follows the reciter (or an
     *  explicit anchor/jump), never the eyes. This kills the phantom
     *  highlight that painted an ayah active on every page open. */
    fun setCurrentPage(page: Int) {
        if (page == pageNumber) return
        pageNumber = page
        _currentPage.value = page
    }

    private fun keyOf(w: MushafWord) = "${w.surah}:${w.verse}:${w.wordInVerse}"

    /** Publish the anchor instantly: active verse set, standing word = its
     *  first word, everything else UNSTARTED (clean). The opening frame must
     *  invite recitation, never pre-accuse it. */
    private fun publishAnchor() {
        _currentKey.value = verseWords[lockedAyah]?.firstOrNull()?.let { keyOf(it) }
    }

    /** Voice engine readiness: gated zipformer files + phoneme table +
     *  stream start. Files are user-supplied via adb push (never bundled,
     *  never downloaded); when absent we refuse to start rather than
     *  silently running nothing. */
    private suspend fun ensureVoice(): Boolean {
        // Short-circuit ONLY when a live stream exists. Asking merely "is the
        // recognizer loaded" was the bug: stopRecite() releases the stream (and
        // any page swipe calls stopRecite), so from the second session onwards
        // this returned true for a stream that no longer existed. accept() then
        // failed silently, fedTotal was incremented anyway, and every session
        // after the first produced zero tokens forever.
        if (zipformerOn && SherpaZipformer.hasStream()) return true
        return try {
            withContext(Dispatchers.Main) { _preparing.value = true }
            val app = getApplication<Application>()
            val okFiles = SherpaZipformer.filesPresent(app)
            if (!okFiles) {
                _engineHint.value = "Voice engine files not found. Copy model.int8.onnx, tokens.txt and ordered_quran_phonemes.json into /sdcard/Iqra — use the file manager or a USB copy, because a folder created by adb push is not readable by the app. See the log for the paths tried."
                diag("engine files missing: ${SherpaZipformer.fileReport(app)}")
                return false
            }
            val ok = SherpaZipformer.ensure(app) &&
                PhonemeMapper.ensureTable(com.iqra.quran.data.AssetPaths.file(app, "ordered_quran_phonemes.json")) &&
                // The expected side must be expressed in the MODEL's unit
                // inventory, otherwise coverage is identically zero.
                PhonemeMapper.ensureUnits(com.iqra.quran.data.AssetPaths.file(app, "tokens.txt")) &&
                SherpaZipformer.startStream()
            zipformerOn = ok
            if (ok) {
                fedAbs = 0
                fedTotal = 0
                streamBaseSec = 0f
                tailBuf = FloatArray(0)
                speechFramesSinceAdvance = 0
            }
            if (!ok) {
                _engineHint.value = "Voice engine failed to start — see logcat (SherpaZipformer)."
            }
            ok
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
            }
            false
        } finally {
            withContext(Dispatchers.Main) { _preparing.value = false }
        }
    }

    /**
     * Start a session on the page the reader is actually showing.
     *
     * `page` is the truth. The surah used to be passed in from the reader
     * screen's entry parameter, which is frozen at open time - so swiping
     * ahead and starting a session loaded the ORIGINAL surah and anchored to
     * its first ayah while a different surah was on screen. The surah now comes
     * from the page.
     */
    fun startRecite(page: Int, explicitAnchor: Pair<Int, Int>? = null) {
        if (_recording.value || _preparing.value) return
        val pages = _mushaf.value ?: return
        // An explicit selection (the ayah sheet's Practice action) wins; the
        // page is the default truth for the Recite button.
        val (s, anchor) = explicitAnchor ?: anchorForPage(page) ?: return
        if (Mushaf.wordsForSurah(pages, s).isEmpty()) return
        loadSurah(s)
        if (verseWords.isEmpty()) return
        lockedAyah = pendingAnchor ?: anchor
        // Armed here, not left at 0: the pinned-lock escape measures elapsed time
        // since the lock last moved, and an un-armed timer reads as "motionless
        // since 1970", which would let the escape fire on the very first frame.
        lastLockMoveMs = System.currentTimeMillis()
        diag("session starts p=$page → s=$s:${lockedAyah}")
        rebaseSlice = true
        pendingAnchor = null
        pageNumber = page
        refreshWindow()
        pendingNextAyah = null
        pendingNextFrames = 0
        wpmEma = SEED_WPM
        lastAdvanceAt = System.currentTimeMillis()
        resetSessionUi()
        resetSessionCounters()
        // The frame ring is emptied per session: frameRingDump() has no
        // session argument, so a dump taken after a cancelled session would
        // otherwise present that session's frames under the NEW
        // sessionGen in the header - stale evidence wearing a current
        // header, the exact illusion this work exists to remove.
        frameRing.clear()
            wrongStreak.clear()
            sessionStatuses.clear()
            _statusMap.value = emptyMap()
        publishAnchor()
        _engineHint.value = null
        _currentPage.value = page
        _activeVerse.value = lockedAyah
        // A new session invalidates any loop still winding down from the last
        // one, so it cannot feed the stream this session is about to create.
        val gen = ++sessionGen
        viewModelScope.launch(Dispatchers.IO) {
            val vadReady = SherpaVad.ensure(getApplication())
            if (!ensureVoice()) return@launch
            if (gen != sessionGen) return@launch
            SherpaZipformer.resetCounters()
            // The session exists from here on. A failure in the mic, the
            // floor probe or the loop still has to leave a record, and the
            // summary from stopRecite says how long it lasted and how much
            // audio it really fed - a session that died at mic start is
            // "ran=yes durMs=3 fed=0", not silence.
            sessionStartMono = SystemClock.elapsedRealtime()
            sessionRan = true
            fedAbs = 0
            fedTotal = 0
            streamBaseSec = 0f
            tailBuf = FloatArray(0)
            starvedRecoveries = 0
            speechFramesSinceAdvance = 0
            _engineLabel.value = "zipformer/" + (if (vadReady) "VAD" else "RMS")
            // A pulled log cannot otherwise be attributed to the gate that
            // produced it, and the gate is a prime suspect when recognition
            // stalls.
            diag("gate poll=${SherpaVad.FEED_POLL_SEC}s vad=$vadReady")
            // build= in the first line of every session: a log pulled off a
            // device is otherwise unattributable to a build.
            diag("session start s=$activeSurah lock=$lockedAyah build=$buildTag engine=${_engineLabel.value}")
            var micOk = true
            withContext(Dispatchers.Main) {
                try {
                    recorder.start()
                    _recording.value = true
                } catch (e: Exception) {
                    micOk = false
                }
            }
            if (!micOk) return@launch
            // Calibrate the silence gate to THIS mic: sample ambient floor
            // once, then require 3x floor (bounded below by the constant).
            // A fixed threshold deafens quiet mics and starves the decoder.
            delay(400)
            val floorProbe = recorder.currentSamples().takeLast(8000).toFloatArray()
            if (floorProbe.isNotEmpty()) {
                noiseFloor = maxOf(SILENCE_RMS, rms(floorProbe) * 3f)
            }
            lastTokenTime = System.currentTimeMillis()
            lastRecoveryTime = System.currentTimeMillis()
            diag("mic floor calibrated: ${"%.4f".format(noiseFloor)}")
            while (_recording.value && gen == sessionGen) {
                // The poll cadence is a single declared constant, shared with the
                // VAD so the gate's hangover cannot drift away from it.
                delay((SherpaVad.FEED_POLL_SEC * 1000).toLong())
                // Warm-up: let ~0.3s accumulate before feeding anything, as
                // before. This used to be tested against the size of the whole
                // captured buffer; with a delta-based read that would compare
                // ~4000 samples per poll against 4800 and skip every frame.
                if (recorder.totalCount() < 4800) continue
                // Read only what has not been fed yet, by absolute index.
                //
                // The delta used to be computed against a 3s SLIDING window
                // whose cursor was itself window-relative: once the captured
                // buffer passed 3s the window was always exactly 3s long, so
                // the cursor sat at the end and the delta was permanently
                // empty. The model then received audio only for the first 3
                // seconds of a session and starved - which is what the
                // "decoder starved 50s/92s" reports actually were.
                val fresh = recorder.readSince(fedAbs)
                if (fresh == null) {
                    // Ring overwrote audio we never consumed (a pause longer
                    // than the ring). Resync to now rather than feed a gap of
                    // unrelated audio as if it were contiguous speech.
                    fedAbs = recorder.totalCount()
                    diag("recorder ring overflow — resynced to sample $fedAbs")
                    continue
                }
                if (fresh.isEmpty()) continue
                fedAbs += fresh.size
                // Gate the audio actually being fed, not a window that is mostly
                // already-seen audio. Diluting 0.25s of speech into 3s of
                // history dropped it below the noise floor and closed the gate on
                // speech the user was still making.
                val quiet = rms(fresh) < noiseFloor
                val vadSilent = if (vadReady) SherpaVad.feedAndDetect(fresh)?.not() else null
                if (quiet && vadSilent != false) {
                    // Time with the gate closed is time the recogniser never
                    // saw, and it used to leave no trace at all: a session
                    // that spent 90s in silence-gate rejection and one that
                    // spent 90s feeding a decoder produced the same log.
                    gateClosedFrames++
                    _gateReason.value = "silence"
                    _policyLive.value = _policyLive.value.copy(
                        gateClosed = gateClosedFrames,
                        held = "gate closed: nothing reached the decoder",
                        vadSpeech = vadSilent,
                    )
                    continue
                }
                _gateReason.value = "decoding"
                speechFramesSinceAdvance++
                try {
                    runZipformerFrame(fresh)
                } catch (e: Exception) {
                    val m = e.message ?: e::class.simpleName ?: "frame error"
                    if (m != lastFrameError) {
                        lastFrameError = m
                        diag("frame error: $m")
                    }
                }
            }
        }
    }

    /** Zipformer streaming frame. Streaming discipline, enforced:
     *  - deltas fed every non-silent frame; stream NEVER reset on advance
     *    (only handoff / anchor / swipe / session / stop);
     *  - tail replay on advance bounds emission history (flat per-frame
     *    cost) while keeping rolling context;
     *  - stream-relative clock for word timing (never recorder-cumulative);
     *  - WRONG requires confident model disagreement (mean chosen-prob),
     *    low-confidence positions stay neutral — the lab's own doctrine. */
    private suspend fun runZipformerFrame(fresh: FloatArray) {
        try {
            // Count only audio the recogniser really took. accept() reports
            // whether it succeeded, because a released stream used to drop
            // every sample silently while fedTotal still climbed - the log
            // then claimed 10s of audio was flowing to a model that had
            // received nothing at all.
            val fedThisFrame = SherpaZipformer.accept(fresh)
            if (fedThisFrame) {
                fedTotal += fresh.size
                // Session-scoped twin of fedTotal. fedTotal is zeroed by every
                // lock move, so it cannot answer "how much audio did THIS
                // session feed" - which is the question the diagnostics
                // screen was really asking while showing fedTotal's stale
                // process-lifetime number instead.
                sessionFed += fresh.size
                tailBuf = (tailBuf + fresh).takeLast(TAIL_SAMPLES).toFloatArray()
            }
            // Decode ONLY when sherpa reports ready: forcing decode with
            // insufficient buffered frames trips a native CHECK abort
            // (features.cc GetFrames) and kills the process instantly.
            val res = SherpaZipformer.decodeIfReady()
            if (res == null || res.symbols.isEmpty() || res.symbols.size == lastEmitCount) {
                // Starvation watch: audio flows but tokens never grow. Recover
                // with ONE attempt per 10s (lock untouched); report it.
                val idleSec = (System.currentTimeMillis() - lastTokenTime) / 1000
                _decoderState.value = "starved ${idleSec}s"
                if (fedThisFrame && idleSec >= 4 && fedTotal > 0 &&
                    System.currentTimeMillis() - lastRecoveryTime > 10000
                ) {
                    lastRecoveryTime = System.currentTimeMillis()
                    val live = SherpaZipformer.hasStream()
                    starvedRecoveries++
                    diag("decoder starved ${idleSec}s: fed=$fedTotal toks=$lastEmitCount " +
                        "stream=${if (live) "live" else "MISSING"} " +
                        "try=$starvedRecoveries " +
                        "opErr=${SherpaZipformer.lastOpError ?: "-"} — " +
                        if (live) "resetting stream (lock untouched)" else "recreating stream (lock untouched)")
                    if (starvedRecoveries >= MAX_STARVATION_RECOVERIES) {
                        // Looping a recovery that cannot succeed just hides the
                        // fault: the old build reset the stream every 10s for
                        // minutes and the session looked like a silent model.
                        // Say what is actually wrong and stop.
                        _decoderState.value = "failed"
                        _engineHint.value = "Voice decoder stopped responding after $starvedRecoveries attempts. Tap Recite to start a new session."
                        diag("decoder gave up: no tokens after $starvedRecoveries recoveries, " +
                            "stream=${if (live) "live" else "MISSING"}")
                        return
                    }
                    if (!live) {
                        // resetStream() on a released stream is a no-op, so the
                        // old recovery path could never fix this case - it only
                        // ever re-ran reset() on null and spun. Rebuild it.
                        resetAudioPipeline()
                        if (!SherpaZipformer.startStream()) {
                            diag("stream recreate failed")
                        }
                    } else {
                        resetAudioPipeline()
                    }
                }
                return
            }
            lastEmitCount = res.symbols.size
            lastTokenTime = System.currentTimeMillis()
            _decoderState.value = "active"
            starvedRecoveries = 0
            // Per-ayah emission slice: after any lock move, window ayat align
            // against emissions heard SINCE the move — never against other
            // ayat's history. Kills cross-ayah ghost matches.
            if (rebaseSlice) {
                sliceStart = res.symbols.size
                rebaseSlice = false
            }
            val base = sliceStart.coerceAtMost(res.symbols.size)
            val obs = res.symbols.drop(base)
            if (obs.isEmpty()) {
                // The evidence a pending streak was armed on is gone: every
                // symbol is behind the slice. Leaving the counters armed let a
                // streak survive the rebase and complete on a later, unrelated
                // frame - which is how "2 consecutive frames" stopped meaning
                // 2 consecutive frames.
                pendingNextAyah = null; pendingNextFrames = 0
                pendingBackAyah = null; pendingBackFrames = 0
                // Recorded rather than skipped. The decoder produced
                // symbols, but every one of them was behind the slice, so
                // the lock policy never ran. This frame bypasses the
                // decision block below, and a silent gap would be
                // indistinguishable in a dump from the policy declining to
                // move - a different fault with a different fix.
                frameRing.add(
                    SystemClock.elapsedRealtime() - sessionStartMono,
                    activeSurah, lockedAyah,
                    -1f, -1f, -1f, 0, -1f,
                    res.symbols.size, 0, sessionGen, TAG_EMPTY_SLICE,
                )
                return
            }
            val obsProbs = res.probs.drop(base)
            val obsTs = res.timestamps.drop(base)
            val audioSec = streamBaseSec + fedTotal / 16000f

            // Handoff: at the end of what is IN SCOPE, check whether the next
            // thing is the next surah's opening.
            //
            // The scope is the visible page, not the whole surah: the user may
            // start on page 50 of Al-Baqarah, and the session must not run on
            // to 2:286 before it will ever consider a surah change. So the
            // boundary is the last ayah that is present on the current page.
            val lastAyah = scopeEndAyah() ?: (verseWords.keys.maxOrNull() ?: lockedAyah)
            if (lockedAyah >= lastAyah && activeSurah < 114) {
                val next = PhonemeMapper.expected(activeSurah + 1, 1)
                if (next != null) {
                    val cov = PhonemeMapper.align(obs, next).coverage
                    if (cov >= HANDOFF_COVERAGE) {
                        loadSurah(activeSurah + 1)
                        lockedAyah = 1
                        lastLockMoveMs = System.currentTimeMillis()
                        rebaseSlice = true
                        lastAdvanceAt = System.currentTimeMillis()
                        // The pending counters are keyed on an ayah NUMBER, not
                        // (surah, ayah), and the handoff changed the surah. A
                        // stale pendingNextFrames=1 for ayah 2 of the OLD surah
                        // therefore matched ayah 2 of the NEW one and a single
                        // qualifying frame advanced the lock.
                        pendingNextAyah = null; pendingNextFrames = 0
                        pendingBackAyah = null; pendingBackFrames = 0
                        resetAudioPipeline()
                        refreshWindow()
                        diag("handoff → s=${activeSurah}:1 coverage=${"%.2f".format(cov)}")
                        // Second early return around the decision block, and
                        // the one that changes the world. surah and lock in
                        // this row are already the NEW surah's, and nextCov
                        // carries the handoff coverage that triggered it (see
                        // the frameRingDump header).
                        frameRing.add(
                            SystemClock.elapsedRealtime() - sessionStartMono,
                            activeSurah, lockedAyah,
                            cov, -1f, -1f, 0, -1f,
                            res.symbols.size, obs.size, sessionGen, TAG_HANDOFF,
                        )
                        return
                    }
                }
            }

            // Lock policy: direct coverage of the NEXT ayah, not an argmax
            // over a window. Argmax was fragile because a partially recited
            // locked ayah often outscores the next one (coverage is relative
            // to each ayah's own length), and ties resolved to the LOWEST
            // index, so advancing stalled and could drift backwards. Coverage
            // of lock+1 alone is monotone in real progress - this is the
            // policy measured at 28/28 in engine/replay/lock_policy.py.
            val nextAyah = lockedAyah + 1
            val nextExp = PhonemeMapper.expected(activeSurah, nextAyah)
            val nextCov = if (nextExp != null) PhonemeMapper.align(obs, nextExp).coverage else 0f
            val hereExp = PhonemeMapper.expected(activeSurah, lockedAyah)
            val hereCov = if (hereExp != null) PhonemeMapper.align(obs, hereExp).coverage else 0f
            val best = listOfNotNull(
                nextExp?.let { nextAyah to nextCov },
                hereExp?.let { lockedAyah to hereCov },
            ).maxByOrNull { it.second }
            if (best != null) _lastMatch.value = best.first to best.second.toDouble()
            val needFrames = if (wpmEma < 50) 3 else 2
            // The lock this frame's coverages were measured against. Captured
            // before anything can move it, because the forward/back blocks
            // below do: recording lockedAyah afterwards would file a frame's
            // coverages under an ayah the policy never saw.
            val lockAtDecision = lockedAyah

            // Forward: the next ayah is sufficiently covered.
            if (nextCov >= ADVANCE_COVERAGE) {
                if (nextCov >= STRONG_COVERAGE) {
                    advanceLockTo(nextAyah)
                } else if (pendingNextAyah == nextAyah) {
                    pendingNextFrames++
                    if (pendingNextFrames >= needFrames) advanceLockTo(nextAyah)
                } else {
                    pendingNextAyah = nextAyah
                    pendingNextFrames = 1
                }
            } else if (nextCov < WEAK_COVERAGE) {
                // Decay, but the key goes with it. The counter alone was left
                // armed against the old ayah, so if coverage climbed back into
                // the middling band the hard reset below cleared the counter but
                // a later band-1 frame re-armed it against a stale target.
                if (pendingNextFrames > 0) pendingNextFrames--
                if (pendingNextFrames == 0) pendingNextAyah = null
            } else {
                pendingNextAyah = null; pendingNextFrames = 0
            }

            // ---- pinned-lock escape ---------------------------------
            // The three gates above cannot cover every state. Forward needs
            // nextCov >= ADVANCE (0.60); decay needs nextCov < WEAK (0.40); the
            // jump needs hereCov < STUCK (0.35). So a frame with nextCov in the
            // dead band [0.40, 0.60) AND hereCov high is one where NO branch can
            // ever fire, and the lock is stuck for good.
            //
            // That is not hypothetical. Scoring 114 surahs (6236 ayat, 26 h of
            // Al-Dosari audio) found surah 55 sitting exactly there: hereCov
            // pinned at 1.00, nextCov frozen at 0.50, for 118 consecutive
            // polls - about 30 seconds and then the rest of the surah. The
            // locked ayah was fully covered, which is precisely why it could
            // never recover: a high hereCov is what keeps the jump gate shut.
            //
            // Lowering ADVANCE would fix that by accident and break the gates
            // everywhere else - ADVANCE exists to stop the lock crediting an
            // ayah on a phrase it has not finished. So this only fires from a
            // state that is already unrecoverable, and only after the lock has
            // demonstrably been motionless for PINNED_ESCAPE_MS. In normal
            // recitation the lock moves every few seconds and this never runs.
            val pinnedFor = System.currentTimeMillis() - lastLockMoveMs
            if (nextCov < ADVANCE_COVERAGE &&
                nextCov >= WEAK_COVERAGE &&
                hereCov >= STRONG_COVERAGE &&
                pinnedFor >= PINNED_ESCAPE_MS
            ) {
                diag("pinned escape: here ${pct(hereCov)} next ${pct(nextCov)} " +
                    "after ${pinnedFor}ms motionless")
                advanceLockTo(nextAyah)
            }

            // Gated long jump: the reciter skipped ahead. Kept deliberately -
            // only when the evidence is near-total and the target is on screen.
            // The candidate is hoisted out of the lambda and the page gate so
            // the frame ring can record it: without this the jump is invisible
            // in a dump whenever the page gate REJECTED it, which is precisely
            // the case worth arguing about.
            //
            // The hereCov gate is not decoration. Coverage is
            // unitsMatched/unitsTotal, so it cannot tell a SKIPPED ayah from a
            // NESTED one: Al-Fatiha 1:3's units are a subsequence of 1:1's, so
            // while the reciter is still on 1:1 the candidate 1:3 already scores
            // 1.00. Without this gate the jump fired on clean audio and skipped
            // 1:2 outright. Requiring the locked ayah to look un-recited is the
            // same evidence the backward branch demands, and it is the only
            // thing that separates the two cases.
            var jumpAyah = 0
            var jumpCov = -1f
            if (nextCov < ADVANCE_COVERAGE && hereCov < STUCK_COVERAGE) {
                val ahead = verseWords.keys.filter { it > lockedAyah + 1 }
                    .mapNotNull { a ->
                        PhonemeMapper.expected(activeSurah, a)?.let { a to PhonemeMapper.align(obs, it).coverage }
                    }
                    .filter { it.second >= JUMP_COVERAGE }
                    .minByOrNull { it.first }
                if (ahead != null) {
                    jumpAyah = ahead.first
                    jumpCov = ahead.second
                    val p = versePage[ahead.first]
                    if (p == null || p in (pageNumber - 1..pageNumber + 1)) {
                        advanceLockTo(ahead.first, measureSpeed = false, kind = "jump")
                    }
                }
            }

            // Backward recovery, but ONLY when the lock is clearly not what is
            // being recited. Requiring hereCov to be low stops the lock
            // ping-ponging: previously any ayat behind the lock scoring well
            // could drag it backwards mid-session.
            val backAyah = lockedAyah - 1
            val backExp = PhonemeMapper.expected(activeSurah, backAyah)
            val backCov = if (backExp != null) PhonemeMapper.align(obs, backExp).coverage else 0f
            // hereCov describes the lock as it stood when the frame began. If the
            // forward or jump block has already moved it, "is the lock stuck?" is
            // being asked about an ayah the policy has already left, so the
            // retreat is not evaluated at all. This also makes a same-frame
            // reversal structurally impossible.
            val lockHeld = lockedAyah == lockAtDecision
            val wantRetreat = lockHeld && backCov >= BACK_COVERAGE && hereCov < STUCK_COVERAGE
            if (wantRetreat) {
                // Speed-scaled like the forward path, which asks for more
                // evidence from a slow reciter rather than a fixed count.
                val needBack = if (wpmEma < 50) 3 else 2
                if (pendingBackAyah == backAyah) {
                    pendingBackFrames++
                    if (pendingBackFrames >= needBack && retreatAllowed()) {
                        advanceLockTo(backAyah, measureSpeed = false, kind = "back")
                        pendingBackAyah = null; pendingBackFrames = 0
                    }
                } else {
                    pendingBackAyah = backAyah
                    pendingBackFrames = 1
                }
            } else if (backCov >= BACK_COVERAGE) {
                // Contradictory: the ayah behind scores well AND so does the
                // locked one, so nothing is being decided. Hard reset, matching
                // how the forward path treats middling evidence. A leaky
                // decrement let a single good frame two polls later complete a
                // streak that was armed before the slice rebased.
                pendingBackAyah = null; pendingBackFrames = 0
            } else if (pendingBackFrames > 0) {
                pendingBackFrames--
            }

            // The lock decision is complete: record its inputs and the state
            // they produced. Placed after the backward block and before the
            // repeat hook, the last point at which every column is in scope
            // and nothing has yet moved the lock for an unrelated reason.
            frameRing.add(
                SystemClock.elapsedRealtime() - sessionStartMono,
                activeSurah, lockAtDecision,
                nextCov, hereCov, backCov,
                jumpAyah, jumpCov,
                res.symbols.size, obs.size, sessionGen, TAG_DECIDED,
            )
            // Published beside the ring, from the same values, so what the screen
            // shows and what a dump contains cannot disagree.
            // The stall, in seconds, and its cause. Each branch names a
            // situation the whole-corpus run actually produced, and each says
            // whether a threshold change could help - because in two of the three
            // it cannot, and pretending otherwise is what would send someone
            // off to retune a number that was never wrong.
            val stallSec = (System.currentTimeMillis() - lastAdvanceAt) / 1000f
            val stallNote = when {
                stallSec < STALL_NOTE_SEC -> ""
                nextCov < WEAK_COVERAGE ->
                    "stalled ${stallSec.toInt()}s: the next ayah is not being heard " +
                        "at all (${pct(nextCov)}). Retuning will not help."
                nextCov < ADVANCE_COVERAGE && hereCov >= STUCK_COVERAGE ->
                    "stalled ${stallSec.toInt()}s: next is close (${pct(nextCov)}) but " +
                        "not past ${pct(ADVANCE_COVERAGE)}, and this ayah still looks " +
                        "recited (${pct(hereCov)}), so neither gate can open."
                nextCov < ADVANCE_COVERAGE ->
                    "stalled ${stallSec.toInt()}s: next is close (${pct(nextCov)}) but " +
                        "not past ${pct(ADVANCE_COVERAGE)}."
                else -> "stalled ${stallSec.toInt()}s: waiting for a confirming frame."
            }
            _policyLive.value = PolicyLive(
                next = nextCov,
                here = hereCov,
                back = backCov,
                gateClosed = gateClosedFrames,
                held = when {
                    nextCov >= STRONG_COVERAGE -> "next covers it outright"
                    nextCov >= ADVANCE_COVERAGE -> "next qualifies, waiting for a 2nd frame"
                    nextCov >= WEAK_COVERAGE -> "next is close but not yet"
                    else -> "next is nowhere near it"
                },
                vadSpeech = null,
                stallSec = stallSec,
                stallNote = stallNote,
            )

            // Repeat practice hook.
            val rep = repeatAyah
            if (rep != null) {
                // `>=` not `>`: the counter used to decrement only once the lock
                // had already moved PAST the target, so a reciter who could not
                // get past it - precisely the case repeat practice exists for -
                // never saw the repeat count fall.
                if (activeSurah == rep.first && lockedAyah >= rep.second && repeatLeftCount > 0) {
                    repeatLeftCount--
                    _repeatLeft.value = repeatLeftCount
                    lockedAyah = rep.second
                    lastLockMoveMs = System.currentTimeMillis()
                    pendingNextAyah = null; pendingNextFrames = 0
                    pendingBackAyah = null; pendingBackFrames = 0
                    rebaseSlice = true
                    wrongStreak.clear()
                    resetAudioPipeline()
                    refreshWindow()
                    diag("repeat loop → ${rep.second} (${repeatLeftCount} left)")
                    if (repeatLeftCount <= 0) {
                        repeatAyah = null
                        _repeatAyahKey.value = null
                    }
                } else if (repeatLeftCount <= 0) {
                    repeatAyah = null
                    _repeatAyahKey.value = null
                }
            }

            refreshWindow()
            val window = _activeWindow.value
            val needWrong = wrongLatchFrames()
            val newMap = LinkedHashMap<String, WordStatus>()
            var timedKey: String? = null
            for (a in window) {
                val ws = verseWords[a] ?: emptyList()
                if (ws.isEmpty()) continue
                val pw = PhonemeMapper.expected(activeSurah, a) ?: continue
                if (pw.wordCount != ws.size) continue
                val al = PhonemeMapper.align(obs, pw, obsProbs.toFloatArray())
                for (i in ws.indices) {
                    val key = keyOf(ws[i])
                    var s = al.statuses.getOrElse(i) { WordStatus.SKIPPED }
                    if (s == WordStatus.WRONG) {
                        // WRONG is only claimed for the ayah the lock is on, and
                        // only for as long as it persists (see the streak below).
                        //
                        // There is deliberately no model-confidence test here
                        // any more. sherpa-onnx returns an EMPTY ys_probs for
                        // streaming CTC with greedy_search - the beam decoders
                        // that populate it are unreachable for this model - so
                        // the old `conf < 0.5f` was permanently true and the
                        // whole WRONG verdict was silently dead code on device.
                        // A gate that can never pass is worse than no gate: it
                        // looks like a safety measure while disabling a
                        // feature. The replacement is the streak plus an
                        // alignment that word_rule_sweep.py measures at zero
                        // collateral over 154 real word observations.
                        if (a != lockedAyah) s = WordStatus.SKIPPED
                    }
                    if (a == lockedAyah && s == WordStatus.WRONG) {
                        val streak = (wrongStreak[key] ?: 0) + 1
                        wrongStreak[key] = streak
                        if (streak < needWrong) s = WordStatus.SKIPPED
                    } else {
                        wrongStreak.remove(key)
                    }
                    if (a != lockedAyah && s == WordStatus.CORRECT) {
                        val old = sessionStatuses[key]
                        if (old == WordStatus.CORRECT) s = WordStatus.CORRECT
                    }
                    // Retain CORRECT only for ayat BEHIND the lock (done work).
                    // WRONG is never retained anywhere: recomputed live every
                    // frame, so stale red can never freeze. Ahead-of-lock
                    // words are recomputed live, never kept - EXCEPT after a
                    // retreat, where the reciter is simply going back over
                    // something they already recited correctly. Dropping those
                    // marks repainted the whole ayah from an emission slice
                    // that no longer contained it, i.e. SKIPPED, which draws as
                    // red strikethrough: a wall of errors for an ayah that was
                    // said perfectly. Going back is a cursor move, not a
                    // retraction.
                    if (a < lockedAyah && s == WordStatus.CORRECT) {
                        sessionStatuses[key] = s
                    } else if (a == lockedAyah) {
                        // UNKNOWN is a non-verdict, so there is nothing worth
                        // retaining: it would otherwise stick around as a
                        // permanent "not quite done" mark.
                        if (s != WordStatus.SKIPPED && s != WordStatus.UNKNOWN) {
                            sessionStatuses[key] = s
                        } else {
                            sessionStatuses.remove(key)
                        }
                    } else if (lastMoveDir < 0 && s == WordStatus.CORRECT &&
                        sessionStatuses[key] == WordStatus.CORRECT
                    ) {
                        sessionStatuses[key] = s
                    } else {
                        sessionStatuses.remove(key)
                    }
                    newMap[key] = s
                }
                if (a == lockedAyah) {
                    val tw = PhonemeMapper.timedWord(al.emitWord, obsTs.toFloatArray(), audioSec)
                    if (tw != null && tw < ws.size) timedKey = keyOf(ws[tw])
                }
            }
            // Expire verdicts outside lock±2: stale paint can never freeze.
            // WRONG is never retained: recomputed live every frame.
            val keep = (lockedAyah - 2)..(lockedAyah + 2)
            sessionStatuses.keys.removeAll { k ->
                val v = k.split(":").getOrNull(1)?.toIntOrNull()
                v == null || v !in keep
            }
            for ((k, v) in sessionStatuses) newMap.putIfAbsent(k, v)
            wrongStreak.keys.removeAll { k -> !k.startsWith("$activeSurah:$lockedAyah:") }

            var currentKey: String? = timedKey
            if (currentKey == null) {
                val ws = verseWords[lockedAyah] ?: emptyList()
                var seen = false
                for (w in ws) {
                    val st = newMap[keyOf(w)] ?: WordStatus.SKIPPED
                    // Only a real verdict counts as "this word is behind us".
                    // UNKNOWN is unfinished, so it must stay a candidate for the
                    // current-word pointer instead of being walked past.
                    if (st == WordStatus.CORRECT || st == WordStatus.WRONG) seen = true
                    if (st != WordStatus.CORRECT && seen) {
                        currentKey = keyOf(w)
                        break
                    }
                }
            }

            val page = versePage[lockedAyah] ?: pageNumber
            withContext(Dispatchers.Main) {
                pageNumber = page
                _statusMap.value = newMap
                _currentKey.value = currentKey
                _currentPage.value = page
                _activeVerse.value = lockedAyah
            }
        } catch (_: Exception) {
        }
    }

    /**
     * End a session. `reason` is the caller's intent ("user stop", "jump");
     * a session that was still preparing when it was cancelled reports
     * "preparing-cancelled" and names the caller in `via`.
     *
     *  Both paths emit the session summary and both clear the per-session
     *  UI state. The old version emitted nothing and returned early, so a
     *  session cancelled while preparing left no record at all and a
     *  truncated session was indistinguishable from a complete one.
     */
    fun stopRecite(reason: String = "user stop") {
        // Retire the session even if it never reached the recording state: a
        // session that is still preparing would otherwise carry on and start
        // recording after the user had already moved on.
        val endedGen = sessionGen
        sessionGen++
        cancelRepeat()
        SherpaZipformer.closeStream()
        if (!_recording.value) {
            resetSessionUi()
            endSession("preparing-cancelled", reason, endedGen)
            return
        }
        _recording.value = false
        recorder.stop()
        _activeVerse.value = null
        resetSessionUi()
        endSession(reason, "recite", endedGen)
    }

    // ---- Reference recitation audio (stream-on-tap, nothing bundled) ----
    // Mirrors quran_android's gapless scheme: download.quranicaudio.com/quran/<reciter>/<NNN>.mp3
    private var mediaPlayer: MediaPlayer? = null
    private val _playingSurah = MutableStateFlow(-1)
    val playingSurah: StateFlow<Int> = _playingSurah

    // Word-by-word follow-along: maps each word key of the playing surah to a
    // global index, and tracks the index currently being recited by the audio.
    private var playWords: List<MushafWord> = emptyList()
    private val _playIndex = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playIndex: StateFlow<Map<String, Int>> = _playIndex
    private val _playHead = MutableStateFlow(-1)
    val playHead: StateFlow<Int> = _playHead
    private var playPoll: Job? = null

    fun togglePlaySurah(surah: Int) {
        if (_playingSurah.value == surah) {
            stopPlayback()
            return
        }
        stopPlayback()
        val url = "https://download.quranicaudio.com/quran/mahmood_khaleel_al-husaree/" +
            "%03d.mp3".format(surah)
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { mp ->
                    mp.start()
                    _playingSurah.value = surah
                    val pages = _mushaf.value
                    if (pages != null) {
                        playWords = Mushaf.wordsForSurah(pages, surah)
                        val map = mutableMapOf<String, Int>()
                        playWords.forEachIndexed { i, w -> map[keyOf(w)] = i }
                        _playIndex.value = map
                        startPlayPoll(mp, playWords.size)
                    }
                }
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); false }
                prepareAsync()
            }
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    private fun startPlayPoll(mp: MediaPlayer, total: Int) {
        playPoll?.cancel()
        playPoll = viewModelScope.launch(Dispatchers.Main) {
            while (mp.isPlaying && total > 0) {
                val d = mp.duration
                val p = mp.currentPosition
                if (d > 0) {
                    val idx = ((p.toFloat() / d) * total).toInt().coerceIn(0, total - 1)
                    _playHead.value = idx
                }
                delay(80)
            }
            _playHead.value = -1
        }
    }

    fun stopPlayback() {
        playPoll?.cancel()
        playPoll = null
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _playingSurah.value = -1
        _playIndex.value = emptyMap()
        _playHead.value = -1
    }

    override fun onCleared() {
        stopPlayback()
        // Queued lines still flush (shutdown() is not shutdownNow()), then
        // diag() starts counting rejections instead of writing.
        diagIo.shutdown()
        SherpaZipformer.close()
        SherpaVad.close()
        super.onCleared()
    }

    companion object {
        /** Rolling context replayed after a lock move: 1.5s at 16kHz. */
        private const val TAIL_SAMPLES = 24000
        /** Consecutive zero-token recoveries before we stop and report failure
         *  rather than looping a recovery that cannot succeed. */
        private const val MAX_STARVATION_RECOVERIES = 3
        // Below this RMS the rolling window is effectively silence -> skip decoding.
        private const val SILENCE_RMS = 0.0025f
        /** Neutral speed estimate a session starts from, and the value the
         *  wpm readout returns to between sessions. */
        private const val SEED_WPM = 70.0
        /** In-memory diagnostic events kept for the screen. */
        private const val DIAG_RING = 200
        private const val DIAG_FILE = "diag.log"
        /** Rotation size for the diag log. Sessions are no longer destroyed
         *  wholesale at this point; the newest DIAG_KEEP-1 archives survive. */
        private const val DIAG_MAX_BYTES = 512L * 1024
        private const val DIAG_KEEP = 3
        /**
         * Per-frame lock-decision rows retained. The poll is 250ms, so 4000
         * rows is ~16 minutes of a continuous session - long enough to cover
         * the point where a lock stopped following the reciter, in about
         * 250KB of text.
         */
        private const val FRAME_RING = 4000

        // Lock thresholds are COVERAGE: the fraction of a candidate ayah's
        // phoneme units that the emission slice accounts for. Calibrated by
        // replaying engine/audio/001.raw (Al-Fatiha, Husary) through the real
        // pipeline - see engine/replay/. On that clip the lock advanced 7/7
        // ayat sequentially at 0.60, with per-advance coverage 0.64-0.78.
        /** Below this a stall is not worth reporting; normal confirmations take
         *  a few seconds and a readout that cries wolf is worse than none. */
        private const val STALL_NOTE_SEC = 8f

        /** Coverages are published as 0-1; the readout says percent. */
        private fun pct(v: Float) = "${(v * 100).toInt()}%"

        private const val ADVANCE_COVERAGE = 0.60f
        private const val STRONG_COVERAGE = 0.85f
        private const val WEAK_COVERAGE = 0.40f
        // How long the lock must be motionless, in a state no gate can leave,
        // before the escape above advances it. Measured deadlock in surah 55 ran
        // ~30 s; 12 s is well clear of that and well above the 3-5 s a healthy
        // lock takes between moves at 100 wpm.
        private const val PINNED_ESCAPE_MS = 12_000L
        private const val JUMP_COVERAGE = 0.92f
        private const val BACK_COVERAGE = 0.80f
        private const val HANDOFF_COVERAGE = 0.60f
        // The lock only yields BACKWARDS when the ayah it holds is clearly not
        // what is being recited. Without this the lock ping-pongs mid-session.
        // It doubles as the long jump's "the lock is not being recited" test,
        // which is what stops the jump from firing on a NESTED ayah: Al-Fatiha
        // 1:3 is a subsequence of 1:1, so its coverage saturates at 1.00 while
        // the reciter is still on 1:1.
        private const val STUCK_COVERAGE = 0.35f
        /** Minimum gap between a lock move and a retreat. At ~1 ayah per 4s of
         *  recitation this only bites on oscillation, never on a real pause. */
        private const val MIN_RETREAT_GAP_MS = 1500L
        /** Consecutive retreats allowed before the lock must move forward. */
        private const val MAX_CONSECUTIVE_RETREATS = 2
    }
}

private fun rms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (v in samples) sum += v * v.toDouble()
    return Math.sqrt(sum / samples.size).toFloat()
}

/** Locale.US on every conversion: the dump is TSV, and a comma decimal
 *  separator in a locale like de-DE would put a delimiter inside a field. */
private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun f3(v: Float): String = String.format(Locale.US, "%.3f", v)

// FrameRing row tags. Only tag 0 is a frame the lock policy actually judged;
// the other two mark the frames that bypass it, which are exactly the frames
// whose absence from a dump would be read as "the policy did nothing".
private const val TAG_DECIDED = 0
private const val TAG_EMPTY_SLICE = 1
private const val TAG_HANDOFF = 2

/**
 * Fixed-capacity circular store of the per-frame lock decision.
 *
 *  Preallocated primitive arrays and index-only writes: this is written from
 *  the recognition loop 4x a second, and a row object per frame is exactly
 *  the per-frame allocation the emission slice was rebuilt to avoid. The
 *  writer is the recognition coroutine and the reader is the UI thread
 *  taking a dump, so every method is synchronized; the window is far too
 *  short for that to matter next to a decode.
 */
private class FrameRing(private val cap: Int) {
    /** tRel: ms since session start. */
    private val tRelMs = LongArray(cap)

    /** Packed ints, 7 per row: surah, lock, jumpTo, syms, obsN, gen, tag. */
    private val ints = IntArray(cap * 7)

    /** Coverages, 4 per row: nextCov, hereCov, backCov, jumpCov. */
    private val cov = FloatArray(cap * 4)

    /** Next write slot; the oldest row is (head - rows) mod cap. */
    private var head = 0
    private var rows = 0

    @Synchronized
    fun clear() {
        head = 0
        rows = 0
    }

    @Synchronized
    fun rowCount(): Int = rows

    @Synchronized
    fun add(
        tRel: Long,
        surah: Int, lock: Int,
        nextCov: Float, hereCov: Float, backCov: Float,
        jumpTo: Int, jumpCov: Float,
        syms: Int, obsN: Int,
        gen: Int, tag: Int,
    ) {
        val i = head
        tRelMs[i] = tRel
        val b = i * 7
        ints[b] = surah
        ints[b + 1] = lock
        ints[b + 2] = jumpTo
        ints[b + 3] = syms
        ints[b + 4] = obsN
        ints[b + 5] = gen
        ints[b + 6] = tag
        val c = i * 4
        cov[c] = nextCov
        cov[c + 1] = hereCov
        cov[c + 2] = backCov
        cov[c + 3] = jumpCov
        head = if (head + 1 == cap) 0 else head + 1
        if (rows < cap) rows++
    }

    /** Oldest row first, so the dump reads in the order it happened. */
    @Synchronized
    fun appendRows(sb: StringBuilder) {
        // head - rows can go negative once the ring has wrapped, and a
        // negative index here would be an ArrayIndexOutOfBounds rather than
        // the oldest row.
        val start = if (head >= rows) head - rows else head + cap - rows
        for (k in 0 until rows) {
            val i = if (start + k < cap) start + k else start + k - cap
            val b = i * 7
            val c = i * 4
            sb.append(tRelMs[i]).append('\t')
            sb.append(ints[b]).append('\t')
            sb.append(ints[b + 1]).append('\t')
            sb.append(f3(cov[c])).append('\t')
            sb.append(f3(cov[c + 1])).append('\t')
            sb.append(f3(cov[c + 2])).append('\t')
            sb.append(ints[b + 2]).append('\t')
            sb.append(f3(cov[c + 3])).append('\t')
            sb.append(ints[b + 3]).append('\t')
            sb.append(ints[b + 4]).append('\t')
            sb.append(ints[b + 5]).append('\t')
            sb.append(ints[b + 6]).append('\n')
        }
    }
}
