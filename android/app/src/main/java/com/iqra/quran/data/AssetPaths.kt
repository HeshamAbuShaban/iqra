package com.iqra.quran.data

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Single source of truth for every heavy on-disk file.
 *
 * Heavy data lives in a shared folder, /sdcard/Iqra, so it survives reinstall
 * and can be populated by plain USB file copy instead of adb. getExternalFilesDir
 * is deliberately NOT used: Android deletes it on uninstall, and Android/data is
 * not browsable over MTP on modern releases.
 *
 * The app's private filesDir is still consulted as a fallback, so anything
 * already pushed there keeps working.
 *
 * Files fall into two classes:
 *  - freely redistributable (pages, glyph DB, phoneme table, silero VAD):
 *    may be fetched from their original source when missing;
 *  - gated (the acoustic model): the user must supply it once.
 */
object AssetPaths {
    const val SHARED_DIR = "Iqra"
    private const val MANIFEST = "manifest.json"
    const val PAGE_COUNT = 604

    // Original sources, matching scripts/fetch_assets.sh.
    private const val IMG_BASE = "https://raw.githubusercontent.com/murtraja/quran-android-images-helper/master/static/images_1024"
    private const val DB_URL = "https://raw.githubusercontent.com/murtraja/quran-android-images-helper/master/static/databases/ayahinfo_1024.db"
    private const val PHONEMES_URL = "https://raw.githubusercontent.com/Quran-Lab/zipformer_p-arabic-v3/main/phonemes/ordered_quran_phonemes.json"
    private const val VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    enum class Kind { GATED, FETCHABLE }

    data class Spec(
        val key: String,
        val bytes: Long,
        val kind: Kind,
        val page: Int = -1,
    ) {
        val fetchUrl: String?
            get() = when (key) {
                "ayahinfo_1024.db" -> DB_URL
                "ordered_quran_phonemes.json" -> PHONEMES_URL
                "silero_vad.onnx" -> VAD_URL
                else -> if (page in 1..PAGE_COUNT) pageUrl(page) else null
            }
    }

    // Upstream names them page001.png, NOT 001.png. Verified against the source:
    // 001.png and 1.png both 404, page001.png returns the 31,248-byte PNG.
    fun pageUrl(page: Int): String = "$IMG_BASE/page%03d.png".format(page)

    /** Everything the app needs on disk, in setup-screen display order. */
    fun specs(): List<Spec> = buildList {
        add(Spec("model.int8.onnx", 72_705_392L, Kind.GATED))
        add(Spec("tokens.txt", 2_346L, Kind.GATED))
        add(Spec("ordered_quran_phonemes.json", 5_106_711L, Kind.FETCHABLE))
        add(Spec("silero_vad.onnx", 643_854L, Kind.FETCHABLE))
        add(Spec("ayahinfo_1024.db", 6_348_800L, Kind.FETCHABLE))
        // 604 Madinah PNGs, ~56.5 MB in total
        add(Spec("pages", 59_249_472L, Kind.FETCHABLE))
    }

    // ---- location resolution -------------------------------------------------

    fun sharedRoot(): File =
        File(Environment.getExternalStorageDirectory(), SHARED_DIR)

    fun privateRoot(context: Context): File = context.filesDir

    /**
     * Where the app is ALLOWED to write.
     *
     * /sdcard/Iqra is preferred because it survives reinstall and is visible
     * over USB, but writing there needs All Files Access on Android 11+. When
     * that is not granted we fall back to the app-specific external dir, which
     * needs no permission but is removed on uninstall. Reading from the shared
     * folder always works either way, so a user who copies files in manually
     * never needs the permission at all.
     */
    fun writableRoot(context: Context): File {
        val shared = sharedRoot()
        return try {
            if (android.os.Environment.isExternalStorageManager() ||
                (shared.isDirectory && shared.canWrite())
            ) {
                shared.mkdirs()
                if (shared.canWrite()) shared else fallbackRoot(context)
            } else {
                fallbackRoot(context)
            }
        } catch (t: Throwable) {
            fallbackRoot(context)
        }
    }

    private fun fallbackRoot(context: Context): File =
        (context.getExternalFilesDir("data") ?: File(context.filesDir, "external")).also { it.mkdirs() }

    /** Resolve one file: shared folder wins, private dir is the fallback. */
    fun file(context: Context, name: String, page: Int = -1): File {
        val shared = File(sharedRoot(), name)
        if (shared.isFile) return shared
        val legacy = File(File(context.filesDir, "zipformer"), name)
        if (legacy.isFile) return legacy
        val top = File(context.filesDir, name)
        if (top.isFile) return top
        // Return the preferred destination so callers that create the file
        // (downloads) land somewhere writable.
        val w = writableRoot(context)
        return if (page in 1..PAGE_COUNT) File(File(w, "pages"), "%03d.png".format(page))
        else File(w, name)
    }

    fun engineDir(context: Context): File =
        file(context, "model.int8.onnx").parentFile ?: writableRoot(context)


    // ---- manifest ------------------------------------------------------------

    data class Entry(val sha256: String, val bytes: Long)

    fun manifestFile(context: Context): File = File(writableRoot(context), MANIFEST)

    fun readManifest(context: Context): Map<String, Entry> {
        val f = manifestFile(context)
        if (!f.isFile) return emptyMap()
        return try {
            val root = JSONObject(f.readText())
            val out = HashMap<String, Entry>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.optJSONObject(k) ?: continue
                out[k] = Entry(o.optString("sha256"), o.optLong("bytes"))
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    @Synchronized
    fun writeManifest(context: Context, updates: Map<String, File>) {
        val f = manifestFile(context)
        f.parentFile?.mkdirs()
        val merged = readManifest(context).toMutableMap()
        for ((k, file) in updates) {
            if (!file.isFile) continue
            merged[k] = Entry(sha256(file), file.length())
        }
        val root = JSONObject()
        for ((k, e) in merged) {
            root.put(k, JSONObject().put("sha256", e.sha256).put("bytes", e.bytes))
        }
        f.writeText(root.toString(2))
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- status --------------------------------------------------------------

    data class Status(
        val spec: Spec,
        val present: Boolean,
        val actualBytes: Long,
        val actualPath: String,
        val expectedBytes: Long,
        val hashOk: Boolean?,
    )

    /** Still-shipped assets, so an existing install is never told data is missing. */
    fun bundledExists(context: Context, key: String): Boolean = try {
        context.assets.open(key).close(); true
    } catch (t: Throwable) {
        false
    }

    fun status(context: Context, spec: Spec): Status {
        val manifest = readManifest(context)
        // The page set is an aggregate, not a single file, so it never carries
        // a page number and must be routed explicitly. Previously it fell
        // through to the single-file branch and looked for a FILE named
        // "pages", so the whole 604-image set always reported as missing.
        val isPageSet = spec.key == "pages"
        val file = if (spec.page in 1..PAGE_COUNT) {
            pageFile(context, spec.page)
        } else {
            file(context, spec.key)
        }
        val present = if (isPageSet || spec.page in 1..PAGE_COUNT) {
            pagesPresent(context)
        } else {
            file.isFile || bundledExists(context, spec.key)
        }
        val expected = manifest[spec.key]?.bytes ?: spec.bytes
        val hashOk = if (isPageSet || spec.page in 1..PAGE_COUNT || !present || !file.isFile) null
        else manifest[spec.key]?.sha256?.let { sha256(file) == it }
        val where = if (isPageSet) {
            pageRoots(context).joinToString("  ") { it.absolutePath }
        } else {
            file.absolutePath
        }
        return Status(spec, present, if (file.isFile) file.length() else 0L, where, expected, hashOk)
    }

    fun allStatuses(context: Context): List<Status> = specs().map { status(context, it) }

    /**
     * Locate one page image.
     *
     * Several roots are tried because a shared-storage directory created by
     * `adb push` ends up root-owned with no traversal bit for other users, and
     * the app then cannot enter it even with All Files Access granted. A
     * folder copied in through the file manager or MTP is readable, and the
     * app's own external dir always is - so whichever the device permits wins.
     */
    fun pageFile(context: Context, page: Int): File {
        val name = "%03d.png".format(page)
        val shared = sharedRoot()
        val candidates = listOf(
            File(File(shared, "pages"), name),
            File(shared, name),
            File(File(fallbackRoot(context), "pages"), name),
            File(fallbackRoot(context), name),
        )
        for (c in candidates) if (c.isFile) return c
        return candidates.first()
    }

    /** Directories that could hold the page set, in preference order. */
    private fun pageRoots(context: Context): List<File> = listOf(
        File(sharedRoot(), "pages"),
        sharedRoot(),
        File(fallbackRoot(context), "pages"),
        fallbackRoot(context),
    )

    /**
     * Pages are present when specific known files are readable - NOT by counting
     * directory entries. On Android 11+ the app can open a page file but
     * listFiles() on that directory returns only a partial view, so an entry
     * count reports "missing" for a folder that is completely present.
     */
    fun pagesPresent(context: Context): Boolean {
        val probes = intArrayOf(1, 2, 3, 155, 302, 450, 603, 604)
        for (dir in pageRoots(context)) {
            if (probes.all { File(dir, "%03d.png".format(it)).isFile }) return true
        }
        // legacy: still-bundled pages
        return try {
            context.assets.open("pages/001.png").close()
            context.assets.open("pages/604.png").close()
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun ready(context: Context): Boolean = allStatuses(context).all { it.present }
}
