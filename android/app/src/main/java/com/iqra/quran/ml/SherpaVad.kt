package com.iqra.quran.ml

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Silero voice-activity detection behind the sherpa-onnx runtime.
 *
 * The 0.6 MB model downloads once on first recitation (like the acoustic
 * model) and is cached in app-private storage — never bundled, offline
 * afterwards. If the model (or the sherpa native lib) is unavailable for
 * any reason, every call degrades to null and the caller falls back to
 * the RMS silence gate, so recognition can never be nuked by this path.
 */
object SherpaVad {
    private const val TAG = "SherpaVad"
    private const val MODEL_NAME = "silero_vad.onnx"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    @Volatile private var vad: Any? = null
    @Volatile private var failed = false

    /** Blocking; call on a background thread. True when VAD is usable. */
    fun ensure(context: Context): Boolean {
        if (vad != null) return true
        if (failed) return false
        return try {
            val file = File(context.filesDir, MODEL_NAME)
            if (!file.exists() || file.length() == 0L) {
                download(file)
            }
            if (!file.exists() || file.length() == 0L) {
                failed = true
                return false
            }
            val silero = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                model = file.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 30.0f,
            )
            val config = com.k2fsa.sherpa.onnx.VadModelConfig()
            config.sileroVadModelConfig = silero
            config.sampleRate = 16000
            config.numThreads = 1
            vad = com.k2fsa.sherpa.onnx.Vad(context.assets, config)
            Log.i(TAG, "silero VAD ready (${file.length()} bytes)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "VAD unavailable, RMS fallback", t)
            failed = true
            false
        }
    }

    /**
     * @return true if the window contains speech, false if silence,
     *         null when VAD is unavailable (use the RMS gate instead).
     */
    fun speechInWindow(samples: FloatArray): Boolean? {
        val v = vad as? com.k2fsa.sherpa.onnx.Vad ?: return null
        return try {
            v.reset()
            v.acceptWaveform(samples)
            v.isSpeechDetected()
        } catch (t: Throwable) {
            Log.w(TAG, "VAD probe failed", t)
            null
        }
    }

    fun close() {
        try {
            (vad as? com.k2fsa.sherpa.onnx.Vad)?.release()
        } catch (_: Throwable) {
        }
        vad = null
    }

    private fun download(target: File) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "iqra-app")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "VAD download HTTP ${conn.responseCode}")
                return
            }
            val part = File(target.parent, "$MODEL_NAME.part")
            conn.inputStream.buffered(8192).use { input ->
                part.outputStream().buffered(8192).use { output ->
                    input.copyTo(output)
                }
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Log.i(TAG, "downloaded VAD model (${target.length()} bytes)")
        } finally {
            conn?.disconnect()
        }
    }
}
