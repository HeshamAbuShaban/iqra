package com.iqra.quran.ui

import android.app.Application
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

    private val recorder = AudioRecorder(16000)
    private var engine: TilawaEngine? = null
    private var decoder: TextCtcDecoder? = null
    private var refWords: List<MushafWord>? = null
    private var pageNumber: Int = 1
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
        refWords = ref
        pageNumber = Mushaf.firstPageOfSurah(pages, surah)
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
                    // Window the reference to the CURRENT page (+ next page for
                    // boundary continuity). Reference words are normalized the
                    // same way as the CTC output (no harakat) so they can match.
                    val cur = refWords!!.filter { it.page == pageNumber }
                    val nxt = refWords!!.filter { it.page == pageNumber + 1 }
                    val refAll = cur + nxt
                    if (refAll.isEmpty()) continue
                    val refNorm = refAll.map { ArabicNormalizer.normalize(it.text) }
                    val statuses = WordAligner.alignWords(refNorm, pred)
                    var lastIdx = -1
                    for (i in statuses.indices) {
                        val st = statuses[i]
                        if (st == WordStatus.SKIPPED) continue
                        lastIdx = i
                        val w = refAll[i]
                        val k = keyOf(w)
                        val prev = accumulated[k]
                        if (prev == null || prev == WordStatus.WRONG && st == WordStatus.CORRECT) {
                            accumulated[k] = st
                        }
                    }
                    val curKey = if (lastIdx >= 0) keyOf(refAll[lastIdx]) else null
                    val advance = lastIdx >= cur.size && nxt.isNotEmpty()
                    withContext(Dispatchers.Main) {
                        _statusMap.value = LinkedHashMap(accumulated)
                        _currentKey.value = curKey
                        if (advance) {
                            pageNumber += 1
                            _currentPage.value = pageNumber
                            recorder.reset()
                        } else {
                            _currentPage.value = pageNumber
                        }
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
    }
}
