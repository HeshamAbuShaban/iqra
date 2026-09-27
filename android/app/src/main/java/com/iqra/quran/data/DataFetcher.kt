package com.iqra.quran.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches the freely redistributable data files straight from their original
 * sources when they are missing. The acoustic model is gated upstream and is
 * deliberately NOT here - the user supplies that one by USB.
 *
 * Downloads land in a .part file and are moved into place only on success, so an
 * interrupted transfer can never leave a truncated file that later looks valid.
 */
object DataFetcher {
    data class Progress(
        val done: Int,
        val total: Int,
        val label: String,
        val bytesThisFile: Long,
        val fileTotal: Long,
    )

    class Cancelled : Exception("cancelled")

    suspend fun fetchAll(
        context: Context,
        onProgress: (Progress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val missing = AssetPaths.allStatuses(context).filter { !it.present && it.spec.kind == AssetPaths.Kind.FETCHABLE }
        val total = missing.size
        if (total == 0) return@withContext

        var done = 0
        for (st in missing) {
            val spec = st.spec
            if (spec.page in 1..AssetPaths.PAGE_COUNT) continue // handled below, as a set
            val dest = AssetPaths.file(context, spec.key)
            download(spec.key, spec.fetchUrl, dest) { bytes ->
                onProgress(Progress(done, total, spec.key, bytes, spec.bytes))
            }
            AssetPaths.writeManifest(context, mapOf(spec.key to dest))
            done++
        }

        // The 604 page images.
        val pageStatus = AssetPaths.allStatuses(context).firstOrNull { it.spec.key == "pages" }
        if (pageStatus != null && !pageStatus.present) {
            for (p in 1..AssetPaths.PAGE_COUNT) {
                val dest = AssetPaths.pageFile(context, p)
                if (dest.isFile && dest.length() > 1024) continue
                download("page %03d".format(p), AssetPaths.pageUrl(p), dest) { bytes ->
                    onProgress(Progress(total, total, "page %03d/%d".format(p, AssetPaths.PAGE_COUNT), bytes, 0))
                }
            }
            AssetPaths.writeManifest(context, emptyMap())
        }
    }

    private fun download(
        label: String,
        url: String?,
        dest: File,
        onBytes: (Long) -> Unit,
    ) {
        if (url == null) return
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} for $label")
            }
            val total = conn.contentLengthLong
            var got = 0L
            conn.inputStream.use { ins ->
                part.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        outs.write(buf, 0, n)
                        got += n
                        if (total > 0) onBytes(got)
                    }
                }
            }
            if (total > 0 && got < total) throw IllegalStateException("short read for $label")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        } finally {
            conn.disconnect()
        }
    }
}
