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

    /**
     * How much audio the recogniser is fed per poll, in seconds. The gate's
     * `minSilenceDuration` is expressed in terms of it, so the detector's
     * hangover can never silently collapse to a single poll if the cadence
     * changes.
     */
    const val FEED_POLL_SEC = 0.25f
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
            var file = com.iqra.quran.data.AssetPaths.file(context, MODEL_NAME)
            if (!file.exists() || file.length() == 0L) {
                download(file)
                file = com.iqra.quran.data.AssetPaths.file(context, MODEL_NAME)
            }
            if (!file.exists() || file.length() == 0L) {
                failed = true
                return false
            }
            val silero = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                model = file.absolutePath,
                threshold = 0.5f,
                // Two feed polls, NOT one. The app fed a 0.25s delta per poll
                // and asked the detector for 0.25s of minimum silence, so a
                // single quiet poll satisfied it and the gate closed - the
                // hangover did nothing at this cadence, and a gap between two
                // words shorter than one poll ended the speech segment and
                // dropped that poll's audio. sherpa's own default is 0.5
                // (cxx-api.h, SherpaOnnxSileroVadModelConfig), which is also
                // exactly two polls, so this returns to upstream rather than
                // inventing a number.
                minSilenceDuration = 2 * FEED_POLL_SEC,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 30.0f,
            )
            val config = com.k2fsa.sherpa.onnx.VadModelConfig()
            config.sileroVadModelConfig = silero
            config.sampleRate = 16000
            config.numThreads = 1
            // assetManager MUST be null for filesDir paths, else sherpa treats
            // the path as an APK asset, fatally aborts the process (no Java
            // exception — instant death on Recite). See sherpa issue #2562.
            vad = com.k2fsa.sherpa.onnx.Vad(null, config)
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
    /**
     * Feed the next chunk of audio and report whether speech is present.
     *
     * Used to reset() and re-analyse a whole 3s window on every 250ms poll,
     * which re-ran the same audio a dozen times and judged the gate on mostly
     * already-seen history. Feeding the delta keeps the detector's own state
     * continuous and the judgement local to what is being fed.
     */
    fun feedAndDetect(samples: FloatArray): Boolean? {
        val v = vad as? com.k2fsa.sherpa.onnx.Vad ?: return null
        return try {
            v.acceptWaveform(samples)
            v.isSpeechDetected()
        } catch (t: Throwable) {
            Log.w(TAG, "VAD probe failed", t)
            null
        }
    }

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
