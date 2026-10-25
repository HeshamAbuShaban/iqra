package com.iqra.quran.ui

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's UI language, applied per-app rather than following the system
 * wholesale only.
 *
 * WHY NOT APPCOMPAT
 *
 * `AppCompatDelegate.setApplicationLocales` is the usual answer, and it would
 * drag in the whole appcompat stack for one call. The same capability exists
 * natively in two pieces:
 *
 * - **API 33+** (`LocaleManager`): the system owns the per-app locale, shows it
 *   in system settings alongside every other app, and recreates the activity
 *   itself when it changes. Nothing here has to survive the change.
 * - **Pre-33**: the locale is applied by wrapping the base context in
 *   [attachBaseContext] with a `createConfigurationContext`, and a language
 *   change asks the activity to [android.app.Activity.recreate]. The stored
 *   tag is the only source of truth on those devices.
 *
 * The stored tag is written in both paths so the Settings row always reads one
 * place, and so a pre-33 install that later lands on a 33+ device keeps its
 * choice.
 *
 * WHAT DOES NOT FLOW THROUGH HERE
 *
 * The mushaf pages are bitmap images and the session records are UTF-8 JSON:
 * neither is affected by locale. Diagnostic strings (TSV dumps, record reasons,
 * replay tags) deliberately stay untranslated so the replay tools keep parsing
 * them; only UI chrome is localized.
 */
object AppLocales {
    /** Follow the system language. Stored as "" so it cannot collide with a tag. */
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val ARABIC = "ar"

    private const val F = "iqra_app"
    private const val K_LANG = "lang"

    /** The user's stored choice: [SYSTEM], [ENGLISH] or [ARABIC]. */
    fun stored(ctx: Context): String =
        prefs(ctx).getString(K_LANG, SYSTEM) ?: SYSTEM

    /**
     * Change the language. On 33+ the system applies it (and recreates the
     * activity); below that the caller must [android.app.Activity.recreate].
     * Returns true when the caller owes the recreate.
     */
    fun apply(ctx: Context, tag: String): Boolean {
        prefs(ctx).edit().putString(K_LANG, tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = ctx.getSystemService(LocaleManager::class.java) ?: return true
            if (tag == SYSTEM) {
                lm.applicationLocales = LocaleList.getEmptyLocaleList()
            } else {
                lm.setApplicationLocales(LocaleList.forLanguageTags(tag))
            }
            return false
        }
        return true
    }

    /**
     * The context the activity should actually inflate with pre-33. Returns the
     * base context unchanged when the choice is [SYSTEM] — wrapping with the
     * system's own locale would pin it, and a later system language change
     * would then never be seen.
     */
    fun wrap(base: Context): Context {
        val tag = stored(base)
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(F, Context.MODE_PRIVATE)
}
