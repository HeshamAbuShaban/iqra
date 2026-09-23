package com.iqra.quran.ui

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
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

class PracticeViewModel(app: Application) : AndroidViewModel(app) {
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _data = MutableStateFlow<QuranData?>(null)
    val data: StateFlow<QuranData?> = _data

    private val _mushaf = MutableStateFlow<List<MushafPage>?>(null)
    val mushaf: StateFlow<List<MushafPage>?> = _mushaf

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing

    private val _modelProgress = MutableStateFlow(-1)
    val modelProgress: StateFlow<Int> = _modelProgress

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

    private val _recognized = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognized

    private val _engineLabel = MutableStateFlow("")
    val engineLabel: StateFlow<String> = _engineLabel

    private val _lastMatch = MutableStateFlow<Pair<Int, Double>?>(null)
    val lastMatch: StateFlow<Pair<Int, Double>?> = _lastMatch
    private val _wpmFlow = MutableStateFlow(70.0)
    val wpmFlow: StateFlow<Double> = _wpmFlow
    private val _gateReason = MutableStateFlow("")
    val gateReason: StateFlow<String> = _gateReason

    private val diagBuffer = ArrayDeque<Pair<Long, String>>()
    private val _diagLog = MutableStateFlow<List<String>>(emptyList())
    val diagLog: StateFlow<List<String>> = _diagLog

    /** Ring-buffer diagnostic event: state changes + errors only, never
     *  per-frame spam. Powers the diagnostics screen; no logcat needed. */
    fun diag(msg: String) {
        val t = (System.currentTimeMillis() / 1000) % 100000
        diagBuffer.addLast(t to msg)
        while (diagBuffer.size > 200) diagBuffer.removeFirst()
        _diagLog.value = diagBuffer.map { (tt, m) -> "$tt $m" }
    }

    data class EngineFileInfo(val name: String, val present: Boolean, val detail: String, val fix: String?)

    /** Snapshot for the diagnostics screen: every gated file + engine state. */
    fun engineFilesInfo(): List<EngineFileInfo> {
        val app = getApplication<Application>()
        val dir = SherpaZipformer.modelDir(app)
        fun info(name: String, f: File, fix: String): EngineFileInfo {
            val ok = f.exists() && f.length() > 0
            val detail = if (!ok) "missing"
            else if (f.length() < 1048576) "${f.length() / 1024} KB"
            else "%.1f MB".format(f.length() / 1048576.0)
            return EngineFileInfo(name, ok, detail, if (ok) null else fix)
        }
        return listOf(
            info("model.int8.onnx", File(dir, "model.int8.onnx"), "adb push model.int8.onnx → files/zipformer/"),
            info("tokens.txt", File(dir, "tokens.txt"), "adb push tokens.txt → files/zipformer/"),
            info("ordered_quran_phonemes.json", File(dir, "ordered_quran_phonemes.json"), "adb push ordered_quran_phonemes.json → files/zipformer/"),
            info("silero_vad.onnx", File(app.filesDir, "silero_vad.onnx"), "adb push silero_vad.onnx → files/"),
        )
    }

    fun micLevel(): Float {
        val s = recorder.currentSamples()
        if (s.isEmpty()) return 0f
        var sum = 0.0
        for (v in s.takeLast(8000)) sum += v * v
        return kotlin.math.sqrt(sum / 8000).toFloat()
    }

    fun micSampleCount(): Int = recorder.sampleCount()

    /** One-line streaming counters for the diagnostics screen. */
    fun streamStats(): String =
        "fed=${SherpaZipformer.acceptedSamples} toks=$lastEmitCount decodes=${SherpaZipformer.decodeCalls}" +
            (SherpaZipformer.lastOpError?.let { " ERR=$it" } ?: "")
    fun clearDiag() {
        diagBuffer.clear()
        _diagLog.value = emptyList()
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

    fun saveLastRead(surah: Int, page: Int) {
        prefs.edit().putInt("last_surah", surah).putInt("last_page", page).apply()
        _lastRead.value = surah to page
    }

    private val _bookmarks = MutableStateFlow(loadBookmarks())
    val bookmarks: StateFlow<Set<Int>> = _bookmarks

    private fun loadBookmarks(): Set<Int> {
        val raw = prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun isBookmarked(page: Int) = _bookmarks.value.contains(page)

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
    private var fedPosition = 0
    private val _decoderState = MutableStateFlow("idle")
    val decoderState: StateFlow<String> = _decoderState
    private var noiseFloor = SILENCE_RMS
    private var lastTokenTime = 0L
    private var lastRecoveryTime = 0L
    private var lastFrameError: String? = null
    // Streaming clock + tail replay: bounds emission history without losing
    // rolling context on advance. fedTotal counts every fed sample;
    // streamBaseSec carries pre-reset audio time for word timing.
    private var fedTotal = 0L
    private var streamBaseSec = 0f
    private var tailBuf = FloatArray(0)
    private var speechFrames = 0L
    private var lastEmitCount = 0
    private var nullFrames = 0
    // Per-ayah emission slice: window ayat align against emissions heard
    // since the lock last moved — never against other ayat's history.
    // Kills cross-ayah ghost matches (partial reveals of unrecited text).
    private var sliceStart = 0
    private var rebaseSlice = false

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
    private val wrongStreak = mutableMapOf<String, Int>()
    /** Session verdicts per word key, retained when ayahs leave the active
     *  window so completed recitation stays visible (and stays revealed in
     *  hide mode) instead of reverting to untouched. Cleared on surah load,
     *  jump, anchor and new recitation sessions. */
    private val sessionStatuses = LinkedHashMap<String, WordStatus>()

    /** Advance the lock, measuring reciter speed from SPEECH-ACTIVE time on
     *  the finished ayah (wall silence excluded) so frame patience adapts
     *  to slow/fast reciters instead of fixed counts. */
    private fun advanceLockTo(next: Int, measureSpeed: Boolean = true) {
        val prev = lockedAyah
        val now = System.currentTimeMillis()
        if (measureSpeed) {
            val dtSec = speechFramesSinceAdvance * 0.25
            val prevWords = verseWords[prev]?.size ?: 0
            if (dtSec in 2.0..180.0 && prevWords > 0) {
                val inst = prevWords / dtSec * 60.0
                wpmEma = (0.7 * wpmEma + 0.3 * inst).coerceIn(25.0, 160.0)
            }
        }
        lastAdvanceAt = now
        speechFramesSinceAdvance = 0
        lockedAyah = next
        _wpmFlow.value = wpmEma
        diag("lock $prev → $next")
        pendingNextAyah = null; pendingNextFrames = 0
        pendingBackAyah = null; pendingBackFrames = 0
        rebaseSlice = true
        // Recycle the stream with tail replay: bounds emission history
        // (flat per-frame cost forever) while keeping rolling context, so
        // there is no dead zone after an advance.
        streamBaseSec += fedTotal / 16000f
        fedTotal = 0
        SherpaZipformer.resetStream()
        if (tailBuf.isNotEmpty()) {
            SherpaZipformer.accept(tailBuf)
            fedTotal = tailBuf.size.toLong()
        }
    }

    /** Frames a WRONG flag must persist before it latches, scaled by measured
     *  words-per-minute so slow reciters' mid-word frames don't flash red. */
    private fun wrongLatchFrames(): Int =
        (1.2 * (60.0 / wpmEma) / 0.25).roundToInt().coerceIn(2, 8)

    private var speechFramesSinceAdvance = 0L

    /**
     * Replays the recent audio tail into a FRESH stream after the lock moves,
     * preserving rolling context while bounding emission history (flat
     * per-frame cost no matter how long the session runs). Stream clock
     * continuity is kept via streamBaseSec.
     */
    private fun recycleStreamForAdvance() {
        streamBaseSec += fedTotal / 16000f
        fedTotal = 0
        SherpaZipformer.resetStream()
        if (tailBuf.isNotEmpty()) {
            SherpaZipformer.accept(tailBuf)
            fedTotal = tailBuf.size.toLong()
        }
    }

    /**
     * Full audio pipeline reset: recorder buffer + stream + cursors + tail.
     * Call everywhere recorder.reset() is called while recording, so heard
     * audio is never re-fed as new and clocks never skew.
     */
    private fun resetAudioPipeline() {
        recorder.reset()
        SherpaZipformer.resetStream()
        fedPosition = 0
        fedTotal = 0
        streamBaseSec = 0f
        tailBuf = FloatArray(0)
        lastEmitCount = 0
        nullFrames = 0
        diag("audio pipeline reset")
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

    private fun ayahOnPage(page: Int): Int {
        val exact = versePage.entries.firstOrNull { it.value == page }?.key
        if (exact != null) return exact
        return versePage.entries.filter { it.value <= page }.maxByOrNull { it.value }?.key ?: lockedAyah
    }

    /** Jump to an arbitrary Mushaf page and resync the tracker to its first verse. */
    fun jumpToPage(page: Int) {
        val pages = _mushaf.value ?: return
        if (page < 1 || page > pages.size) return
        if (_recording.value) stopRecite()
        val pg = pages[page - 1]
        val firstWord = pg.lines
            .filter { it.type == "text" }
            .flatMap { it.words ?: emptyList() }
            .firstOrNull() ?: return
        loadSurah(firstWord.surah)
        lockedAyah = firstWord.verse
        rebaseSlice = true
        diag("jump → s=${firstWord.surah}:${firstWord.verse} p=$page")
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
            withContext(Dispatchers.Main) {
                _data.value = d
                _loading.value = false
            }
            // Reader pages + glyph copy finish in background after first
            // frame. Home is already up. No recognition index anymore.
            val m = Mushaf.load(getApplication())
            GlyphCoords.ensure(getApplication())
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
        if (zipformerOn) return true
        return try {
            withContext(Dispatchers.Main) { _preparing.value = true }
            val app = getApplication<Application>()
            val okFiles = SherpaZipformer.filesPresent(app)
            if (!okFiles) {
                _engineHint.value = "Voice engine files missing — push model.int8.onnx, tokens.txt and ordered_quran_phonemes.json into files/zipformer via adb."
                return false
            }
            val ok = SherpaZipformer.ensure(app) &&
                PhonemeMapper.ensureTable(File(app.filesDir, "zipformer/ordered_quran_phonemes.json")) &&
                SherpaZipformer.startStream()
            zipformerOn = ok
            if (ok) {
                fedPosition = 0
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
                _status.value = "Voice error: ${e.message}"
            }
            false
        } finally {
            withContext(Dispatchers.Main) { _preparing.value = false }
        }
    }

    fun startRecite(surah: Int, page: Int) {
        if (_recording.value || _preparing.value) return
        val pages = _mushaf.value ?: return
        if (Mushaf.wordsForSurah(pages, surah).isEmpty()) return
        loadSurah(surah)
        if (verseWords.isEmpty()) return
        lockedAyah = pendingAnchor ?: ayahOnPage(page)
        rebaseSlice = true
        pendingAnchor = null
        pageNumber = page
        refreshWindow()
            pendingNextAyah = null
            pendingNextFrames = 0
            wpmEma = 70.0
            lastAdvanceAt = System.currentTimeMillis()
            wrongStreak.clear()
            sessionStatuses.clear()
            _statusMap.value = emptyMap()
        publishAnchor()
        _recognized.value = ""
        _engineHint.value = null
        _currentPage.value = page
        _activeVerse.value = lockedAyah
        viewModelScope.launch(Dispatchers.IO) {
            val vadReady = SherpaVad.ensure(getApplication())
            if (!ensureVoice()) return@launch
            fedPosition = 0
            fedTotal = 0
            streamBaseSec = 0f
            tailBuf = FloatArray(0)
            speechFramesSinceAdvance = 0
            _engineLabel.value = "zipformer/" + (if (vadReady) "VAD" else "RMS")
            diag("session start s=$surah lock=$lockedAyah engine=${_engineLabel.value}")
            var micOk = true
            withContext(Dispatchers.Main) {
                _status.value = "Listening…"
                try {
                    recorder.start()
                    _recording.value = true
                } catch (e: Exception) {
                    _status.value = "Mic error: ${e.message}"
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
            while (_recording.value) {
                delay(250)
                val audio = recorder.currentSamples()
                if (audio.size < 4800) continue
                val used = if (audio.size > CAP) audio.copyOfRange(audio.size - CAP, audio.size) else audio
                // Silence gate: skip only when RMS is quiet AND silero VAD (when
                // available) also hears silence. Either one hearing speech keeps
                // the frame, so soft reciters are never cut and loud speech is
                // never missed — VAD can only reduce garbage, never nuke audio.
                val quiet = rms(used) < noiseFloor
                val vadSilent = if (vadReady) SherpaVad.speechInWindow(used)?.not() else null
                if (quiet && vadSilent != false) {
                    _gateReason.value = "silence"
                    withContext(Dispatchers.Main) { _status.value = "Listening… (silence)" }
                    continue
                }
                _gateReason.value = "decoding"
                speechFramesSinceAdvance++
                try {
                    runZipformerFrame(used)
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
    private suspend fun runZipformerFrame(used: FloatArray) {
        try {
            if (used.size < fedPosition) {
                // Recorder buffer was reset elsewhere: pair it with a full
                // pipeline reset so heard audio is never re-fed as new.
                resetAudioPipeline()
            }
            val fresh = if (used.size > fedPosition) used.copyOfRange(fedPosition, used.size) else FloatArray(0)
            fedPosition = used.size
            val fedThisFrame = fresh.isNotEmpty()
            if (fedThisFrame) {
                SherpaZipformer.accept(fresh)
                fedTotal += fresh.size
                tailBuf = (tailBuf + fresh).takeLast(24000).toFloatArray()
            }
            // Decode ONLY when sherpa reports ready: forcing decode with
            // insufficient buffered frames trips a native CHECK abort
            // (features.cc GetFrames) and kills the process instantly.
            val res = SherpaZipformer.decodeIfReady()
            if (res == null || res.symbols.isEmpty() || res.symbols.size == lastEmitCount) {
                // Starvation watch: audio flows but tokens never grow. Recover
                // with ONE stream reset per 10s (lock untouched); report it.
                val idleSec = (System.currentTimeMillis() - lastTokenTime) / 1000
                _decoderState.value = "starved ${idleSec}s"
                if (fedThisFrame && idleSec >= 4 && fedTotal > 0 &&
                    System.currentTimeMillis() - lastRecoveryTime > 10000
                ) {
                    lastRecoveryTime = System.currentTimeMillis()
                    diag("decoder starved ${idleSec}s: fed=${fedTotal} toks=${lastEmitCount} " +
                        "opErr=${SherpaZipformer.lastOpError ?: "-"} — resetting stream (lock untouched)")
                    resetAudioPipeline()
                }
                return
            }
            lastEmitCount = res.symbols.size
            lastTokenTime = System.currentTimeMillis()
            _decoderState.value = "active"
            // Per-ayah emission slice: after any lock move, window ayat align
            // against emissions heard SINCE the move — never against other
            // ayat's history. Kills cross-ayah ghost matches.
            if (rebaseSlice) {
                sliceStart = res.symbols.size
                rebaseSlice = false
            }
            val base = sliceStart.coerceAtMost(res.symbols.size)
            val obs = res.symbols.drop(base)
            if (obs.isEmpty()) return
            val obsProbs = res.probs.drop(base)
            val obsTs = res.timestamps.drop(base)
            val audioSec = streamBaseSec + fedTotal / 16000f
            val emitted = obs.joinToString(" ")

            // Handoff: last ayah + next surah head matches phonetically.
            val lastAyah = verseWords.keys.maxOrNull() ?: lockedAyah
            if (lockedAyah >= lastAyah && activeSurah < 114) {
                val npw = PhonemeMapper.phonemeWords(activeSurah + 1, 1)
                if (npw.isNotEmpty()) {
                    val ns = PhonemeMapper.matchAyah(emitted, listOf(1 to npw.joinToString(" ")))
                    if (ns != null && ns.second >= 0.60) {
                        loadSurah(activeSurah + 1)
                        lockedAyah = 1
                        rebaseSlice = true
                        lastAdvanceAt = System.currentTimeMillis()
                        resetAudioPipeline()
                        refreshWindow()
                        diag("handoff → s=${activeSurah}:1 score=${"%.2f".format(ns.second)}")
                        return
                    }
                }
            }

            val scopeAyahs = (_activeWindow.value +
                verseWords.keys.filter { it < lockedAyah }.takeLast(3) +
                verseWords.keys.filter { it > lockedAyah }.take(3)).distinct().sorted()
            val cands = scopeAyahs.mapNotNull { a ->
                val pw = PhonemeMapper.phonemeWords(activeSurah, a)
                if (pw.isEmpty()) null else a to pw.joinToString(" ")
            }
            if (cands.isEmpty()) return
            val found = PhonemeMapper.matchAyah(emitted, cands) ?: return
            val matchAyah = found.first
            val score = found.second
            _lastMatch.value = matchAyah to score
            val matchPage = versePage[matchAyah]
            val inView = matchPage == null || matchPage in (pageNumber - 1..pageNumber + 1)
            val step = matchAyah - lockedAyah
            val needFrames = if (wpmEma < 50) 3 else 2
            val candidate: Int? =
                if (step in 1..3 && score >= 0.60 && inView) minOf(matchAyah, lockedAyah + 1) else null
            if (candidate != null) {
                val strong = candidate == lockedAyah + 1 && score >= 0.78
                if (strong) {
                    advanceLockTo(candidate)
                } else if (pendingNextAyah == candidate) {
                    pendingNextFrames++
                    if (pendingNextFrames >= needFrames) {
                        advanceLockTo(candidate)
                    }
                } else {
                    pendingNextAyah = candidate
                    pendingNextFrames = 1
                }
            } else if (score < 0.35) {
                // Weak frame: leaky counter instead of a hard reset, so one
                // bad frame can't erase accumulated confidence.
                if (pendingNextFrames > 0) pendingNextFrames--
            } else {
                pendingNextAyah = null; pendingNextFrames = 0
        pendingBackAyah = null; pendingBackFrames = 0
            }
            if (step > 3 && score >= 0.95 && inView) {
                advanceLockTo(matchAyah, measureSpeed = false)
            }
            // Backward recovery: the lock overshoots (swipe reseat, fuzzy
            // relocation, blind probe). Strong multi-frame evidence that the
            // reciter is on an ayah BEHIND the lock reseats it — a lock that
            // cannot return to the recited ayah is definitionally broken.
            if (step in -2..-1 && score >= 0.85 && inView) {
                if (pendingBackAyah == matchAyah) {
                    pendingBackFrames++
                    if (pendingBackFrames >= 2) {
                        advanceLockTo(matchAyah, measureSpeed = false)
                        pendingBackAyah = null; pendingBackFrames = 0
                    }
                } else {
                    pendingBackAyah = matchAyah
                    pendingBackFrames = 1
                }
            } else if (pendingBackFrames > 0) {
                pendingBackFrames--
            }

            // Repeat practice hook.
            val rep = repeatAyah
            if (rep != null) {
                if (activeSurah == rep.first && lockedAyah > rep.second && repeatLeftCount > 0) {
                    repeatLeftCount--
                    _repeatLeft.value = repeatLeftCount
                    lockedAyah = rep.second
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
                val pw = PhonemeMapper.phonemeWords(activeSurah, a)
                if (pw.isEmpty() || pw.size != ws.size) continue
                val al = PhonemeMapper.alignToWords(obs, pw, obsProbs.toFloatArray())
                for (i in ws.indices) {
                    val key = keyOf(ws[i])
                    var s = al.statuses.getOrElse(i) { WordStatus.SKIPPED }
                    if (s == WordStatus.WRONG) {
                        // Confident disagreement only: low model confidence
                        // stays neutral instead of flashing red.
                        val conf = al.wordProb.getOrElse(i) { 0f }
                        if (a != lockedAyah || conf < 0.5f) {
                            s = WordStatus.SKIPPED
                        }
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
                    // words are always recomputed live, never kept.
                    if (a < lockedAyah && s == WordStatus.CORRECT) {
                        sessionStatuses[key] = s
                    } else if (a == lockedAyah) {
                        if (s != WordStatus.SKIPPED) sessionStatuses[key] = s
                        else sessionStatuses.remove(key)
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
                    if (st != WordStatus.SKIPPED) seen = true
                    if (st == WordStatus.SKIPPED && seen) {
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
                _recognized.value = (verseWords[lockedAyah] ?: emptyList()).joinToString(" ") { it.text }
            }
        } catch (_: Exception) {
        }
    }

    fun stopRecite() {
        if (!_recording.value) return
        _recording.value = false
        cancelRepeat()
        SherpaZipformer.closeStream()
        recorder.stop()
        _status.value = "Done — review your recitation below"
        _activeVerse.value = null
        _recognized.value = ""
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
        SherpaZipformer.close()
        SherpaVad.close()
        super.onCleared()
    }

    companion object {
        private const val CAP = 3 * 16000
        // Below this RMS the rolling window is effectively silence -> skip decoding.
        private const val SILENCE_RMS = 0.0025f
    }
}

private fun rms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (v in samples) sum += v * v.toDouble()
    return Math.sqrt(sum / samples.size).toFloat()
}
