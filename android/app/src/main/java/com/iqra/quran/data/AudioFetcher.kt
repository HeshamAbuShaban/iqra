package com.iqra.quran.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams one surah mp3 into the audio library. Blocking; call from an IO
 * dispatcher. Writes a `.part` neighbour and renames only after the full
 * length has arrived, so a dropped connection can never leave a truncated
 * file that would later play as a surah cutting off mid-ayah.
 */
object AudioFetcher {
    fun download(target: File, url: String, onProgress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val type = conn.contentType
            if (type != null && !type.startsWith("audio") &&
                !type.startsWith("application/octet-stream") && !type.startsWith("binary")
            ) {
                throw IOException("not audio ($type)")
            }
            val total = conn.contentLengthLong
            var got = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(got, total)
                        }
                    }
                }
            }
            onProgress(got, total)
            if (total > 0 && got != total) throw IOException("short read ($got/$total)")
            if (got < 1024) throw IOException("too small ($got bytes)")
            target.delete()
            if (!part.renameTo(target)) throw IOException("rename failed")
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            conn.disconnect()
        }
    }
}
