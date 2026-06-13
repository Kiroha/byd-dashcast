package com.byd.dashcast.util

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale

/**
 * Manages persistence and application of the selected language.
 *
 * Used in:
 *  - WelcomeActivity  : premier lancement → choix de langue → sauvegarde
 *  - MainActivity     : applies the saved language on each launch
 *  - Application      : applique la langue au niveau global si besoin
 */
object LocaleHelper {

    const val PREF_FILE = "byd_prefs"
    const val PREF_LANGUAGE = "language"
    const val PREF_SETUP_DONE = "setup_done"

    const val LANG_FR = "fr"
    const val LANG_EN = "en"
    const val LANG_DE = "de"
    const val LANG_TR = "tr"
    const val LANG_IT = "it"
    const val LANG_ES = "es"
    const val LANG_RU = "ru"
    const val LANG_UK = "uk"
    const val LANG_AR = "ar"
    const val LANG_UZ = "uz"
    const val LANG_KK = "kk"
    const val LANG_BE = "be"
    const val LANG_PL = "pl"

    /**
     * Applies the saved locale to the given context without re-saving the preference.
     * setLocale() already persists the language on user selection; calling saveLanguage()
     * on every attachBaseContext() is unnecessary and causes a redundant prefs write.
     */
    @JvmStatic
    fun applyLocale(context: Context): Context {
        val lang = getSavedLanguage(context) ?: return context
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val res = context.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** Changes the locale and updates the resource configuration. */
    @JvmStatic
    fun setLocale(context: Context, lang: String): Context {
        saveLanguage(context, lang)

        val locale = Locale(lang)
        Locale.setDefault(locale)

        val res = context.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    @JvmStatic
    fun saveLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit { putString(PREF_LANGUAGE, lang) }
    }

    @JvmStatic
    fun getSavedLanguage(context: Context): String? =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE, null)

    @JvmStatic
    fun isSetupDone(context: Context): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(PREF_SETUP_DONE, false)

    @JvmStatic
    fun markSetupDone(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit { putBoolean(PREF_SETUP_DONE, true) }
    }
}
