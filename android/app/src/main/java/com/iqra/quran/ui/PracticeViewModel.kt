package com.iqra.quran.ui

import android.app.Application
import android.content.Context
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
    private var activeSurah: Int = 1
    private var pageNumber: Int = 1
    private var refPos: Int = 0
    private val accumulated = LinkedHashMap<String, WordStatus>()

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

    fun startRecite(surah: Int) {
        if (_recording.value || _preparing.value) return
        val pages = _mushaf.value ?: return
        val ref = Mushaf.wordsForSurah(pages, surah)
        if (ref.isEmpty()) return
        activeSurah = surah
        refWords = ref
        pageNumber = Mushaf.firstPageOfSurah(pages, surah)
        // Anchor the tracker at the first word of the page the user is viewing,
        // so recognition starts exactly where they are (no global search).
        refPos = ref.indexOfFirst { it.page == pageNumber }.let { if (it < 0) 0 else it }
        accumulated.clear()
        _statusMap.value = emptyMap()
        _currentKey.value = null
        _currentPage.value = pageNumber
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

                    // LOCAL, FORWARD-ONLY alignment: only consider a small window
                    // around the expected position. This prevents the decoder
                    // from "jumping" the match to a random word/surah.
                    val start = maxOf(0, refPos - WINDOW_BACK)
                    val end = minOf(refWords.size, refPos + WINDOW_FRONT)
                    if (start >= end) continue
                    val window = refWords.subList(start, end)
                    val refNorm = window.map { ArabicNormalizer.normalize(it.text) }
                    val statuses = WordAligner.alignWords(refNorm, pred)

                    var first = -1
                    var last = -1
                    for (i in statuses.indices) {
                        if (statuses[i] == WordStatus.SKIPPED) continue
                        if (first < 0) first = i
                        last = i
                        val w = window[i]
                        val k = keyOf(w)
                        val prev = accumulated[k]
                        if (prev == null || (prev == WordStatus.WRONG && statuses[i] == WordStatus.CORRECT)) {
                            accumulated[k] = statuses[i]
                        }
                    }
                    if (first < 0) continue // nothing recognized this tick

                    // Advance the tracker strictly forward to the end of the
                    // matched span — recitation order is monotonic.
                    refPos = start + last + 1

                    val curWord = window[last]
                    val curKey = keyOf(curWord)
                    val curPage = curWord.page

                    // Surah completion -> hand off to the NEXT surah only
                    // (e.g. 113 -> 114). This is the only allowed cross-surah
                    // move, so we can never "end up in another surah".
                    var handedOff = false
                    if (refPos >= refWords.size - SURAH_END_MARGIN && activeSurah < 114) {
                        val next = Mushaf.wordsForSurah(pages, activeSurah + 1)
                        if (next.isNotEmpty()) {
                            activeSurah += 1
                            refWords = next
                            pageNumber = Mushaf.firstPageOfSurah(pages, activeSurah)
                            refPos = 0
                            recorder.reset()
                            _currentPage.value = pageNumber
                            handedOff = true
                        } else {
                            refPos = refWords.size
                        }
                    }

                    withContext(Dispatchers.Main) {
                        _statusMap.value = LinkedHashMap(accumulated)
                        _currentKey.value = curKey
                        if (!handedOff) _currentPage.value = curPage
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
    }

    override fun onCleared() {
        engine?.close()
        super.onCleared()
    }

    companion object {
        private const val CAP = 40 * 16000
        private const val WINDOW_BACK = 3
        private const val WINDOW_FRONT = 90
        private const val SURAH_END_MARGIN = 2
    }
}
