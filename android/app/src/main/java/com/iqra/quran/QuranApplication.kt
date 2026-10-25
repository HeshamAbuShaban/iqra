package com.iqra.quran

import android.app.Application
import java.util.Locale

class QuranApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The FORMAT locale stays en-US whatever the UI language is.
        //
        // String.format with no explicit locale uses this category, and under
        // the Arabic UI it made "%03d.png" produce ٠٠٣.png - a filename that
        // matches no file, so every page image silently fell back to the text
        // renderer. File names, cache keys, sha256 hex and the engine's diag
        // numbers are data contracts with tools that read Latin digits, so
        // they are pinned here once instead of at every call site. UI text is
        // unaffected: resource placeholders format with the configuration
        // locale, so Arabic screens still show ٠١٢٣.
        Locale.setDefault(Locale.Category.FORMAT, Locale.US)
    }
}
