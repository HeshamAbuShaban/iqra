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
import com.iqra.quran.data.WordStatus
import com.iqra.quran.ml.ModelManager
import com.iqra.quran.ml.TextCtcDecoder
import com.iqra.quran.ml.TilawaEngine
import com.iqra.quran.ml.ArabicNormalizer
import com.iqra.quran.ml.WordAligner
import com.iqra.quran.ml.VerseMatcher
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
        _activeVerse.value = null
        _currentKey.value = null
        _statusMap.value = emptyMap()
        setCurrentPage(page)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val d = QuranData.load(getApplication())
            val m = Mushaf.load(getApplication())
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
        lockedAyah = ayahOnPage(page)
        pageNumber = page
        verseMatcher = VerseMatcher(d)
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
                delay(400)
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
                    val decoded = dec.decode(lp.data, lp.timeSteps, lp.vocabSize)
                    val transcript = decoded.text
                    val tokenIds = decoded.tokenIds
                    if (transcript.isBlank() || tokenIds.isEmpty()) continue

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
                        when {
                            step in 1..3 -> if (match.score >= 0.45) lockedAyah = minOf(match.ayah, lockedAyah + 1)
                            step > 3 -> if (match.score >= 0.85) lockedAyah = match.ayah
                        }
                    } else if (match.surah == activeSurah + 1 && activeSurah < 114) {
                        val lastAyah = verseWords.keys.maxOrNull() ?: lockedAyah
                        if (lockedAyah >= lastAyah && match.score >= 0.6) {
                            loadSurah(activeSurah + 1)
                            lockedAyah = 1
                            recorder.reset()
                        }
                    }

                    // 3) Word-level alignment for the locked verse (token-based).
                    val words = verseWords[lockedAyah] ?: emptyList()
                    val statuses = if (words.isNotEmpty()) {
                        val targetTokens = d.getWordTokens(activeSurah, lockedAyah)
                        val targetArabic = words.map { it.text }
                        if (targetTokens.isNotEmpty()) {
                            WordAligner.align(tokenIds, targetTokens, targetArabic).map { it.second }
                        } else {
                            WordAligner.alignWords(targetArabic, transcript.split(" "))
                        }
                    } else {
                        emptyList()
                    }

                    // 4) Build keyed status map + current-word highlight.
                    val newMap = LinkedHashMap<String, WordStatus>()
                    var currentKey: String? = null
                    var seenMatched = false
                    for (i in words.indices) {
                        val st = statuses.getOrElse(i) { WordStatus.SKIPPED }
                        val key = keyOf(words[i])
                        newMap[key] = st
                        if (st != WordStatus.SKIPPED) seenMatched = true
                        if (st == WordStatus.SKIPPED && seenMatched && currentKey == null) currentKey = key
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
        private const val CAP = 12 * 16000
        // Below this RMS the 12s window is effectively silence -> skip decoding.
        private const val SILENCE_RMS = 0.0025f
    }
}

private fun rms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (v in samples) sum += v * v.toDouble()
    return Math.sqrt(sum / samples.size).toFloat()
}
