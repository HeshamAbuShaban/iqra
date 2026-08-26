package com.iqra.quran.ml

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ensures the ~85 MB Tilawa acoustic model is available on disk.
 *
 * Priority: already-downloaded copy → bundled asset (offline builds) →
 * one-time download from the public Tilawa release. The model is cached in
 * app-private storage, so it is fetched at most once; after that the app is
 * fully offline.
 */
object ModelManager {
    private const val MODEL_NAME = "model.onnx"
    private const val MODEL_URL =
        "https://github.com/yazinsai/tilawa/releases/download/v0.2.0/fastconformer_full_mixed.onnx"

    suspend fun ensureModel(context: Context, onProgress: (Int) -> Unit): File {
        val file = File(context.filesDir, MODEL_NAME)
        if (file.exists() && file.length() > 0) {
            onProgress(100)
            return file
        }

        // Offline builds that bundled the model keep it in assets.
        try {
            context.assets.open(MODEL_NAME).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            onProgress(100)
            return file
        } catch (_: Exception) {
            // not bundled — fall through to download
        }

        download(context, MODEL_URL, file, onProgress)
        return file
    }

    private fun download(context: Context, urlStr: String, target: File, onProgress: (Int) -> Unit) {
        val part = File(context.filesDir, "$MODEL_NAME.part")
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "iqra-app")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Model download failed: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            conn.inputStream.buffered(8192).use { input ->
                part.outputStream().buffered(8192).use { output ->
                    val buf = ByteArray(8192)
                    var read = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                        else onProgress(-1)
                    }
                }
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Log.i("ModelManager", "downloaded model to ${target.absolutePath} (${target.length()} bytes)")
        } finally {
            conn?.disconnect()
        }
    }
}
