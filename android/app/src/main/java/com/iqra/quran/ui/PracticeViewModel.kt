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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private val recorder = AudioRecorder(16000)
    private var engine: TilawaEngine? = null
    private var decoder: TextCtcDecoder? = null
    private var refWords: List<MushafWord> = emptyList()
    private var ayahs: List<AyahRef> = emptyList()
    private var activeSurah: Int = 1
    private var pageNumber: Int = 1
    private var activeAyahIdx: Int = 0
    private var ayahWordPos: Int = 0
    private val accumulated = LinkedHashMap<String, WordStatus>()

    /** One ayah of a surah: its verse number, starting page, and its words. */
    private data class AyahRef(val verse: Int, val page: Int, val words: List<MushafWord>)

    private fun buildAyahs(words: List<MushafWord>): List<AyahRef> {
        val out = mutableListOf<AyahRef>()
        var i = 0
        while (i < words.size) {
            val v = words[i].verse
            val page = words[i].page
            var j = i
            while (j < words.size && words[j].verse == v) j++
            out.add(AyahRef(v, page, words.subList(i, j)))
            i = j
        }
        return out
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
        if (ayahs.isNotEmpty()) {
            var idx = ayahs.indexOfFirst { it.page >= page }
            if (idx < 0) idx = ayahs.lastIndex
            else if (ayahs[idx].page > page && idx > 0) idx--
            activeAyahIdx = idx
            ayahWordPos = 0
            _activeVerse.value = ayahs[activeAyahIdx].verse
        }
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
        val ref = Mushaf.wordsForSurah(pages, surah)
        if (ref.isEmpty()) return
        activeSurah = surah
        refWords = ref
        ayahs = buildAyahs(ref)
        pageNumber = page
        // Anchor at the FIRST ayah on the page the user is viewing, so
        // recitation starts at a clean ayah boundary (strict ayah-by-ayah).
        var idx = ayahs.indexOfFirst { it.page >= pageNumber }
        if (idx < 0) idx = ayahs.lastIndex
        else if (ayahs[idx].page > pageNumber && idx > 0) idx--
        activeAyahIdx = idx
        ayahWordPos = 0
        accumulated.clear()
        _statusMap.value = emptyMap()
        _currentKey.value = null
        _recognized.value = ""
        _currentPage.value = pageNumber
        _activeVerse.value = ayahs[activeAyahIdx].verse
        viewModelScope.launch(Dispatchers.IO) {
            if (engine == null && !ensureEngine()) return@launch
            val eng = engine ?: return@launch
            val dec = decoder ?: return@launch
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
                try {
                    val lp = eng.run(used)
                    val decoded = dec.decode(lp.data, lp.timeSteps, lp.vocabSize)
                    val pred = decoded.text.split(" ").filter { it.isNotBlank() }
                    if (pred.isEmpty()) continue

                    // STRICT AYAH-SCOPED alignment: we only ever match the
                    // decoder output against the CURRENT ayah's remaining words
                    // (plus a 1-word look-ahead into the next ayah to detect the
                    // transition). This makes it impossible for recognition to
                    // "jump" to a different ayah/page, and keeps the model
                    // focused on one ayah at a time (better accuracy).
                    val cur = ayahs.getOrNull(activeAyahIdx) ?: break
                    val slice = if (ayahWordPos < cur.words.size)
                        cur.words.subList(ayahWordPos, cur.words.size) else emptyList()
                    if (slice.isEmpty()) {
                        advanceAyah()
                        continue
                    }
                    val look = if (activeAyahIdx + 1 < ayahs.size)
                        ayahs[activeAyahIdx + 1].words.take(1) else emptyList()
                    val refAll = slice + look
                    val refNorm = refAll.map { ArabicNormalizer.normalize(it.text) }
                    val statuses = WordAligner.alignWords(refNorm, pred)

                    var lastMatched = -1
                    val recognized = StringBuilder()
                    for (i in slice.indices) {
                        val st = statuses[i]
                        if (st == WordStatus.SKIPPED) continue
                        val w = slice[i]
                        val k = keyOf(w)
                        val prev = accumulated[k]
                        if (prev == null || (prev == WordStatus.WRONG && st == WordStatus.CORRECT)) {
                            accumulated[k] = st
                        }
                        lastMatched = i
                        recognized.append(w.text).append(" ")
                    }
                    val lookMatched = look.isNotEmpty() && statuses[slice.size] != WordStatus.SKIPPED

                    // Advance strictly within the current ayah.
                    if (lastMatched >= 0) ayahWordPos = lastMatched + 1
                    val ayahComplete = ayahWordPos >= cur.words.size || lookMatched
                    if (ayahComplete) advanceAyah()

                    val newCur = ayahs.getOrNull(activeAyahIdx)
                    val curKey = newCur?.let { keyOf(it.words[minOf(ayahWordPos, it.words.lastIndex)]) }
                    val recognizedStr = recognized.toString().trim()
                    withContext(Dispatchers.Main) {
                        _statusMap.value = LinkedHashMap(accumulated)
                        _currentKey.value = curKey
                        _currentPage.value = pageNumber
                        _activeVerse.value = newCur?.verse
                        if (recognizedStr.isNotEmpty()) _recognized.value = recognizedStr
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Move to the next ayah (or next surah at the boundary). Updates private
     *  trackers + pageNumber only; StateFlows are pushed by the caller. */
    private fun advanceAyah() {
        if (activeAyahIdx + 1 < ayahs.size) {
            activeAyahIdx++
            ayahWordPos = 0
            pageNumber = ayahs[activeAyahIdx].page
            return
        }
        // End of current surah.
        if (activeSurah < 114) {
            val pages = _mushaf.value ?: return
            val next = Mushaf.wordsForSurah(pages, activeSurah + 1)
            if (next.isNotEmpty()) {
                activeSurah++
                refWords = next
                ayahs = buildAyahs(next)
                activeAyahIdx = 0
                ayahWordPos = 0
                pageNumber = ayahs[0].page
                recorder.reset()
                return
            }
        }
        // Truly finished.
        _recording.value = false
        recorder.stop()
        _status.value = "Done — review your recitation below"
        _activeVerse.value = null
        _recognized.value = ""
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
                setOnPreparedListener { mp -> mp.start(); _playingSurah.value = surah }
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); false }
                prepareAsync()
            }
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _playingSurah.value = -1
    }

    override fun onCleared() {
        stopPlayback()
        engine?.close()
        super.onCleared()
    }

    companion object {
        private const val CAP = 40 * 16000
    }
}
