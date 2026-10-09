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
 * committed. The user supplies them into /sdcard/Iqra (or filesDir/zipformer).
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
        val model = com.iqra.quran.data.AssetPaths.file(context, MODEL_FILE)
        val tokens = com.iqra.quran.data.AssetPaths.file(context, TOKENS_FILE)
        return model.exists() && model.length() > 0 && tokens.exists() && tokens.length() > 0
    }

    /** Which root each engine file resolved from, and where it did not. */
    fun fileReport(context: Context): String =
        "$MODEL_FILE[${com.iqra.quran.data.AssetPaths.resolveReport(context, MODEL_FILE)}] " +
            "$TOKENS_FILE[${com.iqra.quran.data.AssetPaths.resolveReport(context, TOKENS_FILE)}]"

    /** True when gated files are present AND the streaming recognizer started. */
    fun ensure(context: Context): Boolean {
        if (recognizer != null) return true
        if (failed) return false
        return try {
            val model = com.iqra.quran.data.AssetPaths.file(context, MODEL_FILE)
            val tokens = com.iqra.quran.data.AssetPaths.file(context, TOKENS_FILE)
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

    /** True when a live native stream exists.
     *
     *  "The recognizer is loaded" is NOT the same question. stopRecite()
     *  releases the stream, and jumpToPage() calls stopRecite(), so any page
     *  swipe mid-session destroys the stream. A readiness check that only asks
     *  whether the recognizer is up will happily report ready for a stream that
     *  no longer exists, and every accept() then fails silently. */
    fun hasStream(): Boolean = stream != null

    // Native stream access is serialised: the session coroutine feeds and
    // decodes while the UI thread can stop/close. Without this, closeStream()
    // could free the stream underneath an in-flight acceptWaveform/decode.
    @Synchronized
    fun startStream(): Boolean {
        val rec = recognizer ?: return false
        return try {
            // Release, do not just reset: reset() leaves the native buffers
            // allocated, so resetting here leaked one OnlineStream per session.
            stream?.let { runCatching { it.release() } }
            stream = rec.createStream()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "stream start failed", t)
            stream = null
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

    /**
     * Feed audio to the recogniser. Returns whether it was actually accepted.
     *
     *  This used to return Unit and swallow the "no stream" case, while the
     *  caller unconditionally counted the samples as fed. The diagnostics then
     *  reported tens of thousands of "fed" samples for a stream that had been
     *  released - which is exactly why the dead-stream bug read as "audio was
     *  flowing fine, the model just produced no tokens".
     */
    @Synchronized
    fun accept(samples: FloatArray): Boolean {
        val rec = recognizer
        val s = stream
        if (rec == null || s == null) {
            if (samples.isNotEmpty()) lastOpError = "accept: no live stream"
            return false
        }
        if (samples.isEmpty()) return false
        return try {
            s.acceptWaveform(samples, 16000)
            acceptedSamples += samples.size
            true
        } catch (t: Throwable) {
            noteOpError("accept", t)
            false
        }
    }

    @Synchronized
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

    @Synchronized
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

    @Synchronized
    fun closeStream() {
        val s = stream
        stream = null
        s?.let { runCatching { it.release() } }
    }

    /**
     * Zero the run counters at the start of a session.
     *
     * These are process-lifetime, so without this a healthy second session
     * still shows the first session's numbers - the "audio was flowing" illusion
     * in its original form. `lastOpError` mattered most: it is assigned on the
     * first failed accept and never cleared, so one error in session 1 made
     * every later session report a fault that was not happening.
     */
    fun resetCounters() {
        acceptedSamples = 0L
        decodeCalls = 0L
        lastOpError = null
    }

    @Synchronized
    fun close() {
        runCatching { stream?.release() }
        try {
            recognizer?.release()
        } catch (_: Throwable) {
        }
        recognizer = null
        stream = null
    }
}
