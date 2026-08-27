package com.iqra.quran.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iqra.quran.audio.AudioRecorder
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.WordStatus
import com.iqra.quran.ml.ModelManager
import com.iqra.quran.ml.TextCtcDecoder
import com.iqra.quran.ml.TilawaEngine
import com.iqra.quran.ml.VerseMatcher
import com.iqra.quran.ml.WordAligner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PracticeResult(
    val match: VerseMatcher.VerseMatch?,
    val words: List<Pair<String, WordStatus>>,
)

data class WordDisplay(val text: String, val status: WordStatus)

class PracticeViewModel(app: Application) : AndroidViewModel(app) {
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _data = MutableStateFlow<QuranData?>(null)
    val data: StateFlow<QuranData?> = _data

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

    private val _focusedVerse = MutableStateFlow<Pair<Int, Int>?>(null)
    val focusedVerse: StateFlow<Pair<Int, Int>?> = _focusedVerse

    private val _displayWords = MutableStateFlow<List<WordDisplay>>(emptyList())
    val displayWords: StateFlow<List<WordDisplay>> = _displayWords

    private val _result = MutableStateFlow<PracticeResult?>(null)
    val result: StateFlow<PracticeResult?> = _result

    private var engine: TilawaEngine? = null
    private var decoder: TextCtcDecoder? = null
    private var matcher: VerseMatcher? = null
    private val recorder = AudioRecorder(16000)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val d = QuranData.load(getApplication())
            withContext(Dispatchers.Main) {
                _data.value = d
                decoder = TextCtcDecoder(d.vocab, d.blankId)
                matcher = VerseMatcher(d)
                _loading.value = false
            }
        }
    }

    fun toggleHide() { _hideVerse.value = !_hideVerse.value }

    /** Select a verse to practice (without starting the mic). Use surah<0 to clear. */
    fun selectVerse(surah: Int, ayah: Int) {
        if (surah < 0) {
            _focusedVerse.value = null
            _result.value = null
            _displayWords.value = emptyList()
            return
        }
        _focusedVerse.value = surah to ayah
        _result.value = null
        _displayWords.value = emptyList()
    }

    /** Arabic word tokens for a verse (full text, for read mode / pre-recitation). */
    fun verseWords(surah: Int, ayah: Int): List<String> {
        val dec = decoder ?: return emptyList()
        return _data.value?.getWordTokens(surah, ayah)?.map { dec.tokenIdsToText(it) }
            ?: emptyList()
    }

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

    fun startRecording(surah: Int, ayah: Int) {
        if (_recording.value || _preparing.value) return
        _result.value = null
        _displayWords.value = emptyList()
        _focusedVerse.value = surah to ayah
        viewModelScope.launch(Dispatchers.IO) {
            if (engine == null && !ensureEngine()) return@launch
            val dec = decoder ?: return@launch
            val d = _data.value ?: return@launch
            val initWords = d.getWordTokens(surah, ayah)
                .map { WordDisplay(dec.tokenIdsToText(it), WordStatus.SKIPPED) }
            withContext(Dispatchers.Main) {
                _displayWords.value = initWords
            }
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
            // Live, progressive recognition while recording.
            while (_recording.value) {
                delay(450)
                val audio = recorder.currentSamples()
                if (audio.size < 4800) continue
                try {
                    val lp = engine!!.run(audio)
                    val decoded = dec.decode(lp.data, lp.timeSteps, lp.vocabSize)
                    val wt = d.getWordTokens(surah, ayah)
                    val arabic = wt.map { dec.tokenIdsToText(it) }
                    val align = if (wt.isNotEmpty()) {
                        WordAligner.align(decoded.tokenIds, wt, arabic)
                    } else {
                        emptyList()
                    }
                    withContext(Dispatchers.Main) {
                        _displayWords.value = align.map { WordDisplay(it.first, it.second) }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stopAndProcess(surah: Int? = null, ayah: Int? = null) {
        if (!_recording.value) return
        _recording.value = false
        _status.value = "Recognizing…"
        val f = _focusedVerse.value
        val tS = surah ?: f?.first
        val tA = ayah ?: f?.second
        val audio = recorder.stop()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val eng = engine ?: return@launch
                val dec = decoder ?: return@launch
                val mat = matcher ?: return@launch
                val d = _data.value ?: return@launch

                val lp = eng.run(audio)
                val decoded = dec.decode(lp.data, lp.timeSteps, lp.vocabSize)
                val match = mat.bestMatch(decoded.text)

                val targetS = tS ?: match?.surah
                val targetA = tA ?: match?.ayah
                val words = if (targetS != null && targetA != null) {
                    val wt = d.getWordTokens(targetS, targetA)
                    val arabic = wt.map { dec.tokenIdsToText(it) }
                    WordAligner.align(decoded.tokenIds, wt, arabic)
                } else {
                    emptyList()
                }

                withContext(Dispatchers.Main) {
                    _displayWords.value = words.map { WordDisplay(it.first, it.second) }
                    _result.value = PracticeResult(match, words)
                    _status.value = if (match != null) {
                        "Detected ${match.surah}:${match.ayah}  •  ${(match.score * 100).toInt()}%"
                    } else {
                        "No confident match"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _status.value = "Error: ${e.message}" }
            }
        }
    }

    override fun onCleared() {
        engine?.close()
        super.onCleared()
    }
}
