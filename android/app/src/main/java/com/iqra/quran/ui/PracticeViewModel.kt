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
        if (_recording.value) stopRecite()
        val (s, a) = anchorForPage(page) ?: return
        loadSurah(s)
        lockedAyah = a
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
                PhonemeMapper.ensureTable(com.iqra.quran.data.AssetPaths.file(app, "ordered_quran_phonemes.json")) &&
                // The expected side must be expressed in the MODEL's unit
                // inventory, otherwise coverage is identically zero.
                PhonemeMapper.ensureUnits(com.iqra.quran.data.AssetPaths.file(app, "tokens.txt")) &&
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
        diag("session starts p=$page → s=$s:${lockedAyah}")
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
            diag("session start s=$activeSurah lock=$lockedAyah engine=${_engineLabel.value}")
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
                    diag("listening (silence)")
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
                        rebaseSlice = true
                        lastAdvanceAt = System.currentTimeMillis()
                        resetAudioPipeline()
                        refreshWindow()
                        diag("handoff → s=${activeSurah}:1 coverage=${"%.2f".format(cov)}")
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
                if (pendingNextFrames > 0) pendingNextFrames--
            } else {
                pendingNextAyah = null; pendingNextFrames = 0
            }

            // Gated long jump: the reciter skipped ahead. Kept deliberately -
            // only when the evidence is near-total and the target is on screen.
            if (nextCov < ADVANCE_COVERAGE) {
                val ahead = verseWords.keys.filter { it > lockedAyah + 1 }
                    .mapNotNull { a ->
                        PhonemeMapper.expected(activeSurah, a)?.let { a to PhonemeMapper.align(obs, it).coverage }
                    }
                    .filter { it.second >= JUMP_COVERAGE }
                    .minByOrNull { it.first }
                if (ahead != null) {
                    val p = versePage[ahead.first]
                    if (p == null || p in (pageNumber - 1..pageNumber + 1)) {
                        advanceLockTo(ahead.first, measureSpeed = false)
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
            if (backCov >= BACK_COVERAGE && hereCov < STUCK_COVERAGE) {
                if (pendingBackAyah == backAyah) {
                    pendingBackFrames++
                    if (pendingBackFrames >= 2) {
                        advanceLockTo(backAyah, measureSpeed = false)
                        pendingBackAyah = null; pendingBackFrames = 0
                    }
                } else {
                    pendingBackAyah = backAyah
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
                val pw = PhonemeMapper.expected(activeSurah, a) ?: continue
                if (pw.wordCount != ws.size) continue
                val al = PhonemeMapper.align(obs, pw, obsProbs.toFloatArray())
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
        _activeVerse.value = null
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

        // Lock thresholds are COVERAGE: the fraction of a candidate ayah's
        // phoneme units that the emission slice accounts for. Calibrated by
        // replaying engine/audio/001.raw (Al-Fatiha, Husary) through the real
        // pipeline - see engine/replay/. On that clip the lock advanced 7/7
        // ayat sequentially at 0.60, with per-advance coverage 0.64-0.78.
        private const val ADVANCE_COVERAGE = 0.60f
        private const val STRONG_COVERAGE = 0.85f
        private const val WEAK_COVERAGE = 0.40f
        private const val JUMP_COVERAGE = 0.92f
        private const val BACK_COVERAGE = 0.80f
        private const val HANDOFF_COVERAGE = 0.60f
        // The lock only yields BACKWARDS when the ayah it holds is clearly not
        // what is being recited. Without this the lock ping-pongs mid-session.
        private const val STUCK_COVERAGE = 0.35f
    }
}

private fun rms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (v in samples) sum += v * v.toDouble()
    return Math.sqrt(sum / samples.size).toFloat()
}
