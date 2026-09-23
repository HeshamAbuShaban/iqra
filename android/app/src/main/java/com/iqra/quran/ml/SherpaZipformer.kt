package com.iqra.quran.ml

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File

/**
 * Quran-Lab zipformer_p-arabic-v3 streaming phoneme recognizer behind the
 * sherpa-onnx runtime (gated weights, user-supplied — never bundled, never
 * committed; adb-push model.int8.onnx + tokens.txt into filesDir/zipformer/).
 *
 * Any failure degrades to null/false so the Tilawa path keeps working.
 */
object SherpaZipformer {
    private const val TAG = "SherpaZipformer"
    const val DIR = "zipformer"
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val PHONEME_MAP_FILE = "ordered_quran_phonemes.json"

    data class PhonemeResult(
        val symbols: List<String>,
        val timestamps: FloatArray,
        /** Chosen-token probabilities parallel to symbols; drives the
         *  confident-disagreement gate for WRONG flags. */
        val probs: FloatArray,
        val text: String,
    )

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var stream: OnlineStream? = null
    @Volatile private var failed = false
    @Volatile var lastError: String? = null
        private set

    fun filesPresent(context: Context): Boolean {
        val dir = modelDir(context)
        val model = File(dir, MODEL_FILE)
        val tokens = File(dir, TOKENS_FILE)
        return model.exists() && model.length() > 0 && tokens.exists() && tokens.length() > 0
    }

    fun modelDir(context: Context): File = File(context.filesDir, DIR)

    /** True when gated files are present AND the streaming recognizer started. */
    fun ensure(context: Context): Boolean {
        if (recognizer != null) return true
        if (failed) return false
        return try {
            val dir = modelDir(context)
            val model = File(dir, MODEL_FILE)
            val tokens = File(dir, TOKENS_FILE)
            if (!model.exists() || model.length() == 0L || !tokens.exists() || tokens.length() == 0L) {
                return false
            }
            val modelConfig = OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = model.absolutePath),
                tokens = tokens.absolutePath,
                numThreads = maxOf(1, Runtime.getRuntime().availableProcessors() / 2),
            )
            val config = OnlineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
                enableEndpoint = false,
            )
            recognizer = OnlineRecognizer(null, config)
            Log.i(TAG, "zipformer streaming ready (${model.length()} bytes)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "zipformer unavailable, no voice engine", t)
            lastError = (t::class.simpleName ?: "err") + ": " + (t.message?.take(60) ?: "")
            failed = true
            false
        }
    }

    fun startStream(): Boolean {
        val rec = recognizer ?: return false
        return try {
            stream?.let { runCatching { rec.reset(it) } }
            stream = rec.createStream()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "stream start failed", t)
            false
        }
    }

    @Volatile var lastOpError: String? = null
        private set
    @Volatile var acceptedSamples = 0L
        private set
    @Volatile var decodeCalls = 0L
        private set

    private fun noteOpError(op: String, t: Throwable) {
        lastOpError = "$op: ${(t::class.simpleName ?: "err")}: ${(t.message?.take(80) ?: "")}"
        Log.w(TAG, lastOpError, t)
    }

    fun accept(samples: FloatArray) {
        val rec = recognizer
        val s = stream
        if (rec == null || s == null || samples.isEmpty()) return
        try {
            s.acceptWaveform(samples, 16000)
            acceptedSamples += samples.size
        } catch (t: Throwable) {
            noteOpError("accept", t)
        }
    }

    fun decodeIfReady(): PhonemeResult? {
        val rec = recognizer
        val s = stream
        if (rec == null || s == null) return null
        return try {
            if (!rec.isReady(s)) return null
            decodeCalls++
            rec.decode(s)
            val r = rec.getResult(s)
            val syms = r.tokens.toList()
            PhonemeResult(syms, r.timestamps, r.ysProbs, syms.joinToString(" "))
        } catch (t: Throwable) {
            noteOpError("decode", t)
            null
        }
    }



    fun resetStream() {
        val rec = recognizer
        val s = stream
        if (rec == null || s == null) return
        try {
            rec.reset(s)
        } catch (t: Throwable) {
            Log.w(TAG, "stream reset failed", t)
        }
    }

    fun closeStream() {
        stream = null
    }

    fun close() {
        try {
            recognizer?.release()
        } catch (_: Throwable) {
        }
        recognizer = null
        stream = null
    }
}
