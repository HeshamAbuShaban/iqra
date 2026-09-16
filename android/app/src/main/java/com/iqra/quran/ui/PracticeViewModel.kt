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
import com.iqra.quran.ml.ModelManager
import com.iqra.quran.ml.TextCtcDecoder
import com.iqra.quran.ml.TilawaEngine
import com.iqra.quran.ml.ArabicNormalizer
import com.iqra.quran.ml.WordAligner
import com.iqra.quran.ml.VerseMatcher
import com.iqra.quran.ml.ConstrainedCtcDecoder
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
    private var engine: TilawaEngine? = null
    private var decoder: TextCtcDecoder? = null
    private var activeSurah: Int = 1
    private var lockedAyah: Int = 1
    private var pageNumber: Int = 1
    private var verseWords: Map<Int, List<MushafWord>> = emptyMap()
    private var versePage: Map<Int, Int> = emptyMap()
    private var verseMatcher: VerseMatcher? = null
    private var pendingNextAyah: Int? = null
    private var pendingNextFrames: Int = 0

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

    // ---- Constrained recognition state (Phase 3) ----
    private var constrainedDecoder: ConstrainedCtcDecoder? = null
    private var wpmEma = 70.0
    private var lastAdvanceAt = 0L
    private var coveredPrefix = 0
    private val wrongStreak = mutableMapOf<String, Int>()

    /** Advance the lock, measuring reciter speed from the finished ayah so
     *  frame patience adapts to slow/fast reciters instead of fixed counts. */
    private fun advanceLockTo(next: Int, measureSpeed: Boolean = true) {
        val prev = lockedAyah
        val now = System.currentTimeMillis()
        if (measureSpeed) {
            val dtSec = (now - lastAdvanceAt) / 1000.0
            val prevWords = verseWords[prev]?.size ?: 0
            if (dtSec in 2.0..180.0 && prevWords > 0) {
                val inst = prevWords / dtSec * 60.0
                wpmEma = (0.7 * wpmEma + 0.3 * inst).coerceIn(25.0, 160.0)
            }
        }
        lastAdvanceAt = now
        lockedAyah = next
        coveredPrefix = 0
        pendingNextAyah = null; pendingNextFrames = 0
    }

    /** Frames a WRONG flag must persist before it latches, scaled by measured
     *  words-per-minute so slow reciters' mid-word frames don't flash red. */
    private fun wrongLatchFrames(): Int =
        (1.2 * (60.0 / wpmEma) / 0.25).roundToInt().coerceIn(2, 8)

    private fun applyHarakatRecheck(
        expectedStrict: List<String>,
        transStrict: List<String>,
        statuses: List<WordStatus>,
    ): List<WordStatus> {
        if (expectedStrict.isEmpty() || transStrict.isEmpty()) return statuses
        val rate = transStrict.count { TextCtcDecoder.hasDiacritic(it) } / transStrict.size.toFloat()
        if (rate < 0.3f) return statuses // model isn't emitting harakat; can't judge
        val transNorm = transStrict.map { ArabicNormalizer.normalize(it) }
        return statuses.mapIndexed { i, st ->
            if (st != WordStatus.CORRECT) return@mapIndexed st
            val exp = expectedStrict.getOrNull(i) ?: return@mapIndexed st
            val expNorm = ArabicNormalizer.normalize(exp)
            val cands = transStrict.indices.filter { transNorm[it] == expNorm }
            if (cands.isEmpty()) st
            else if (cands.any { TextCtcDecoder.hasDiacritic(transStrict[it]) } &&
                cands.none { transStrict[it] == exp }
            ) WordStatus.WRONG
            else st
        }
    }

    /** Greedy walk of emissions against the locked ayah's reference tokens;
     *  returns the key of the word holding the most recent emission, or null
     *  when nothing recent matched (caller falls back to the heuristic). */
    private fun timedWordKey(
        words: List<MushafWord>,
        emissions: List<TextCtcDecoder.Emission>,
        refWordTokens: List<IntArray>,
        timeSteps: Int,
    ): String? {
        if (words.isEmpty() || emissions.isEmpty() || refWordTokens.isEmpty()) return null
        val depth = minOf(refWordTokens.size, words.size)
        var wi = 0
        var ti = 0
        var lastWord = -1
        var lastEnd = -1
        for (e in emissions) {
            if (wi >= depth) break
            val need = refWordTokens[wi]
            if (ti < need.size && e.tokenId == need[ti]) {
                ti++
                if (ti >= need.size) {
                    lastWord = wi
                    lastEnd = e.endFrame
                    wi++
                    ti = 0
                }
            } else if (ti == 0) {
                continue // extra sound before the word starts
            } else {
                ti = 0
                if (need.isNotEmpty() && e.tokenId == need[0]) {
                    ti = 1
                    if (need.size == 1) {
                        lastWord = wi
                        lastEnd = e.endFrame
                        wi++
                    }
                }
            }
        }
        if (lastWord < 0) return null
        val recent = timeSteps - maxOf(8, timeSteps / 4)
        if (lastEnd < recent) return null
        return keyOf(words[lastWord])
    }

    /** Build per-ayah word + page maps for a surah. The page always follows the
     *  locked verse (derived from it), so it can never jump to a wrong page. */
    private fun loadSurah(surah: Int) {
        val pages = _mushaf.value ?: return
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
        coveredPrefix = 0
        wrongStreak.clear()
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
        coveredPrefix = 0
        wrongStreak.clear()
        _statusMap.value = emptyMap()
        _currentKey.value = null
        val targetPage = versePage[ayah]
        if (targetPage != null && targetPage != pageNumber) {
            pageNumber = targetPage
            _currentPage.value = targetPage
        }
        lockedAyah = ayah
        _activeVerse.value = ayah
        refreshWindow()
        if (_recording.value) recorder.reset()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val d = QuranData.load(getApplication())
            val m = Mushaf.load(getApplication())
            GlyphCoords.ensure(getApplication())
            withContext(Dispatchers.Main) {
                _data.value = d
                _mushaf.value = m
                decoder = TextCtcDecoder(d.vocab, d.blankId)
                _loading.value = false
            }
        }
    }

    fun toggleHide() { _hideVerse.value = !_hideVerse.value }

    /** Let the reader tell us which page the user is viewing (manual swipe). */
    fun setCurrentPage(page: Int) {
        if (page == pageNumber) return
        pageNumber = page
        _currentPage.value = page
        lockedAyah = ayahOnPage(page)
        _activeVerse.value = lockedAyah
        pendingAnchor = null
        refreshWindow()
        if (_recording.value) recorder.reset()
    }

    private fun keyOf(w: MushafWord) = "${w.surah}:${w.verse}:${w.wordInVerse}"

    private suspend fun ensureEngine(): Boolean {
        if (engine != null) return true
        return try {
            withContext(Dispatchers.Main) { _preparing.value = true }
            val modelFile: File = ModelManager.ensureModel(getApplication()) { p ->
                _modelProgress.value = p
            }
            val d = _data.value ?: return false
            engine = TilawaEngine(modelFile, d.vocabSize)
            withContext(Dispatchers.Main) { _preparing.value = false }
            true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _preparing.value = false
                _status.value = "Model error: ${e.message}"
            }
            false
        }
    }

    fun startRecite(surah: Int, page: Int) {
        if (_recording.value || _preparing.value) return
        val pages = _mushaf.value ?: return
        val d = _data.value ?: return
        if (Mushaf.wordsForSurah(pages, surah).isEmpty()) return
        loadSurah(surah)
        if (verseWords.isEmpty()) return
        lockedAyah = pendingAnchor ?: ayahOnPage(page)
        pendingAnchor = null
        pageNumber = page
        refreshWindow()
            verseMatcher = VerseMatcher(d)
            constrainedDecoder = ConstrainedCtcDecoder(d.vocab, d.blankId)
            pendingNextAyah = null
            pendingNextFrames = 0
            wpmEma = 70.0
            lastAdvanceAt = System.currentTimeMillis()
            coveredPrefix = 0
            wrongStreak.clear()
            _statusMap.value = emptyMap()
        _currentKey.value = null
        _recognized.value = ""
        _currentPage.value = page
        _activeVerse.value = lockedAyah
        viewModelScope.launch(Dispatchers.IO) {
            if (engine == null && !ensureEngine()) return@launch
            val eng = engine ?: return@launch
            val dec = decoder ?: return@launch
            val matcher = verseMatcher ?: return@launch
            val ccDec = constrainedDecoder ?: return@launch
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
            while (_recording.value) {
                delay(250)
                val audio = recorder.currentSamples()
                if (audio.size < 4800) continue
                val used = if (audio.size > CAP) audio.copyOfRange(audio.size - CAP, audio.size) else audio
                // Skip pure silence: real recitation is full of long pauses, and
                // decoding silence floods the matcher with garbage + drains battery.
                if (rms(used) < SILENCE_RMS) {
                    withContext(Dispatchers.Main) { _status.value = "Listening… (silence)" }
                    continue
                }
                try {
                    val lp = eng.run(used)
                    val timed = dec.decodeTimed(lp.data, lp.timeSteps, lp.vocabSize)
                    val transcript = timed.text
                    val tokenIds = timed.tokenIds
                    if (transcript.isBlank() || tokenIds.isEmpty()) continue

                    // 0) Constrained decode against the active-window lexicon.
                    //    Allowed tokens = locked + next ayah only, reference =
                    //    locked ayah. Tracks cumulative prefix coverage so the
                    //    advance gate knows how much of the locked ayah was
                    //    actually heard (across rolling windows, not one frame).
                    val refFlat = d.getWordTokens(activeSurah, lockedAyah).flatMap { it.toList() }.toIntArray()
                    val allowedIds = HashSet<Int>(refFlat.size * 2 + 16)
                    for (id in refFlat) allowedIds.add(id)
                    for (arr in d.getWordTokens(activeSurah, lockedAyah + 1)) for (id in arr) allowedIds.add(id)
                    val constrained = ccDec.decodeConstrained(lp, allowedIds, refFlat)
                    if (refFlat.isNotEmpty()) {
                        coveredPrefix = maxOf(coveredPrefix, (constrained.prefixCoverage * refFlat.size).toInt())
                    }
                    val coverageOk = refFlat.isEmpty() || coveredPrefix >= (0.5 * refFlat.size)

                    // 1) Detect the current verse with the faithful @tilawa joint
                    //    matcher, scoped to this surah (+ the next, for handoff).
                    //    It picks the verse whose FULL text best matches — so it
                    //    can't jump on a stray first word like "Qul".
                    val scope = if (activeSurah < 114) setOf(activeSurah, activeSurah + 1) else setOf(activeSurah)
                    val match = matcher.bestMatch(transcript, scope)
                    if (match == null || match.score < 0.30) {
                        withContext(Dispatchers.Main) { if (transcript.isNotBlank()) _recognized.value = transcript }
                        continue
                    }

                    // 2) Move the lock FORWARD only, one ayah at a time, and only
                    //    when confident -> strict ayah-by-ayah, no cascading jumps.
                    if (match.surah == activeSurah) {
                        val step = match.ayah - lockedAyah
                        // Visible-page attention: the candidate must live on a
                        // page the viewer can see (current ±1), unless it is a
                        // high-confidence relocation. Kills whole-Quran misroutes.
                        val matchPage = versePage[match.ayah]
                        val inView = matchPage == null || matchPage in (pageNumber - 1..pageNumber + 1)
                        // Hysteresis scaled by measured reciter speed: slow
                        // reciters get more confirmation frames. A strong
                        // single-frame match still advances immediately.
                        val needFrames = if (wpmEma < 50) 3 else 2
                        val candidate: Int? =
                            if (step in 1..3 && match.score >= 0.40 && coverageOk && inView) minOf(match.ayah, lockedAyah + 1) else null
                        if (candidate != null) {
                            val strong = candidate == lockedAyah + 1 && match.score >= 0.60 && coverageOk
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
                        } else {
                            pendingNextAyah = null; pendingNextFrames = 0
                        }
                        if (step > 3 && match.score >= 0.85) {
                            advanceLockTo(match.ayah, measureSpeed = false)
                        }
                    } else if (match.surah == activeSurah + 1 && activeSurah < 114) {
                        pendingNextAyah = null; pendingNextFrames = 0
                        val lastAyah = verseWords.keys.maxOrNull() ?: lockedAyah
                        if (lockedAyah >= lastAyah && match.score >= 0.6) {
                            loadSurah(activeSurah + 1)
                            lockedAyah = 1
                            coveredPrefix = 0
                            lastAdvanceAt = System.currentTimeMillis()
                            recorder.reset()
                        }
                    } else {
                        pendingNextAyah = null; pendingNextFrames = 0
                    }

                    // 2b) Repeat practice: when the lock moves past the repeat
                    //     ayah, loop back until the count is exhausted.
                    val rep = repeatAyah
                    if (rep != null) {
                        if (activeSurah == rep.first && lockedAyah > rep.second && repeatLeftCount > 0) {
                            repeatLeftCount--
                            _repeatLeft.value = repeatLeftCount
                            lockedAyah = rep.second
                            coveredPrefix = 0
                            pendingNextAyah = null; pendingNextFrames = 0
                            wrongStreak.clear()
                            recorder.reset()
                            refreshWindow()
                            if (repeatLeftCount <= 0) {
                                repeatAyah = null
                                _repeatAyahKey.value = null
                            }
                        } else if (repeatLeftCount <= 0) {
                            repeatAyah = null
                            _repeatAyahKey.value = null
                        }
                    }

                    // 3) Word-level alignment across the narrow active window.
                    //    Locked ayah: full token alignment. Previous ayah: done,
                    //    all CORRECT so said words stay visible. Next ayah: same
                    //    alignment, so its said words reveal progressively even
                    //    before the lock advances.
                    refreshWindow()
                    val window = _activeWindow.value
                    fun alignAyah(ayah: Int, ws: List<MushafWord>): List<WordStatus> {
                        if (ws.isEmpty()) return emptyList()
                        val targetTokens = d.getWordTokens(activeSurah, ayah)
                        val targetArabic = ws.map { it.text }
                        return if (targetTokens.isNotEmpty()) {
                            WordAligner.align(tokenIds, targetTokens, targetArabic).map { it.second }
                        } else {
                            WordAligner.alignWords(targetArabic, transcript.split(" "))
                        }
                    }
                    val words = verseWords[lockedAyah] ?: emptyList()
                    val rawStatuses = alignAyah(lockedAyah, words)
                    // Harakat-aware recheck: the aligner works on normalized
                    // text, so a wrong harakah is invisible to it. Downgrade
                    // CORRECT -> WRONG only with positive evidence (the model
                    // emitted a diacritic that mismatches), never on bare text.
                    val strictWords = dec.emissionsToStrictWords(timed.emissions)
                    val statuses = applyHarakatRecheck(words.map { it.text }, strictWords, rawStatuses)
                    // Word timing: the word holding the most recent emission is
                    // where the reciter stands; it overrides the heuristic key.
                    val timedKey = timedWordKey(words, timed.emissions, d.getWordTokens(activeSurah, lockedAyah), lp.timeSteps)
                    val windowStatuses = LinkedHashMap<Int, List<WordStatus>>()
                    for (a in window) {
                        val ws = verseWords[a] ?: emptyList()
                        windowStatuses[a] = when {
                            ws.isEmpty() -> emptyList()
                            a < lockedAyah -> List(ws.size) { WordStatus.CORRECT }
                            a == lockedAyah -> statuses
                            else -> alignAyah(a, ws)
                        }
                    }

                    // 4) Build keyed status map across the window + current-word highlight.
                    //    WRONG on the locked ayah must persist for speed-scaled
                    //    frames before it latches, so a slow reciter's mid-word
                    //    frames never flash red.
                    val needWrong = wrongLatchFrames()
                    val newMap = LinkedHashMap<String, WordStatus>()
                    for ((a, sts) in windowStatuses) {
                        val ws = verseWords[a] ?: emptyList()
                        for (i in ws.indices) {
                            val key = keyOf(ws[i])
                            var s = sts.getOrElse(i) { WordStatus.SKIPPED }
                            if (a == lockedAyah && s == WordStatus.WRONG) {
                                val streak = (wrongStreak[key] ?: 0) + 1
                                wrongStreak[key] = streak
                                if (streak < needWrong) s = WordStatus.SKIPPED
                            } else {
                                wrongStreak.remove(key)
                            }
                            newMap[key] = s
                        }
                    }
                    wrongStreak.keys.removeAll { k -> !k.startsWith("$activeSurah:$lockedAyah:") }
                    var currentKey: String? = timedKey
                    var seenMatched = false
                    for (i in words.indices) {
                        val st = statuses.getOrElse(i) { WordStatus.SKIPPED }
                        if (st != WordStatus.SKIPPED) seenMatched = true
                        if (st == WordStatus.SKIPPED && seenMatched && currentKey == null) currentKey = keyOf(words[i])
                    }
                    if (currentKey == null) {
                        for (i in words.indices.reversed()) {
                            val st = statuses.getOrElse(i) { WordStatus.SKIPPED }
                            if (st != WordStatus.SKIPPED) { currentKey = keyOf(words[i]); break }
                        }
                    }

                    val page = versePage[lockedAyah] ?: pageNumber
                    withContext(Dispatchers.Main) {
                        pageNumber = page
                        _statusMap.value = newMap
                        _currentKey.value = currentKey
                        _currentPage.value = page
                        _activeVerse.value = lockedAyah
                        _recognized.value = match.transcript
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stopRecite() {
        if (!_recording.value) return
        _recording.value = false
        cancelRepeat()
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
        engine?.close()
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
