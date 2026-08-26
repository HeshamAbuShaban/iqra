package com.iqra.quran.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iqra.quran.audio.AudioRecorder
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.WordStatus
import com.iqra.quran.ml.TextCtcDecoder
import com.iqra.quran.ml.TilawaEngine
import com.iqra.quran.ml.VerseMatcher
import com.iqra.quran.ml.WordAligner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PracticeResult(
    val match: VerseMatcher.VerseMatch?,
    val words: List<Pair<String, WordStatus>>,
)

class PracticeViewModel(app: Application) : AndroidViewModel(app) {
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _data = MutableStateFlow<QuranData?>(null)
    val data: StateFlow<QuranData?> = _data

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _result = MutableStateFlow<PracticeResult?>(null)
    val result: StateFlow<PracticeResult?> = _result

    private var engine: TilawaEngine? = null
    private var decoder: TextCtcDecoder? = null
    private var matcher: VerseMatcher? = null
    private val recorder = AudioRecorder(16000)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val d = QuranData.load(getApplication())
            val eng = TilawaEngine(getApplication(), d.vocabSize)
            withContext(Dispatchers.Main) {
                _data.value = d
                decoder = TextCtcDecoder(d.vocab, d.blankId)
                engine = eng
                matcher = VerseMatcher(d)
                _loading.value = false
            }
        }
    }

    fun startRecording() {
        if (_recording.value) return
        _result.value = null
        _status.value = "Listening…"
        recorder.start()
        _recording.value = true
    }

    fun clearStatus(msg: String) {
        if (!_recording.value) _status.value = msg
    }

    fun stopAndProcess(surah: Int? = null, ayah: Int? = null) {
        if (!_recording.value) return
        _recording.value = false
        _status.value = "Recognizing…"
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

                val targetS = surah ?: match?.surah
                val targetA = ayah ?: match?.ayah
                val words = if (targetS != null && targetA != null) {
                    val wt = d.getWordTokens(targetS, targetA)
                    val arabic = wt.map { dec.tokenIdsToText(it) }
                    WordAligner.align(decoded.tokenIds, wt, arabic)
                } else {
                    emptyList()
                }

                withContext(Dispatchers.Main) {
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
