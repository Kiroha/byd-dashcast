package com.byd.myapp;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Manages persistence and application of the selected language.
 *
 * Used in:
 *  - WelcomeActivity  : premier lancement → choix de langue → sauvegarde
 *  - MainActivity     : applies the saved language on each launch
 *  - Application      : applique la langue au niveau global si besoin
 */
public class LocaleHelper {

    public static final String PREF_FILE     = "byd_prefs";
    public static final String PREF_LANGUAGE = "language";
    public static final String PREF_SETUP_DONE = "setup_done";

    public static final String LANG_FR = "fr";
    public static final String LANG_EN = "en";
    public static final String LANG_DE = "de";
    public static final String LANG_TR = "tr";
    public static final String LANG_IT = "it";
    public static final String LANG_ES = "es";
    public static final String LANG_RU = "ru";
    public static final String LANG_UK = "uk";
    public static final String LANG_AR = "ar";
    public static final String LANG_UZ = "uz";
    public static final String LANG_KK = "kk";
    public static final String LANG_BE = "be";

    /** Applies the saved locale to the given context. */
    public static Context applyLocale(Context context) {
        String lang = getSavedLanguage(context);
        if (lang == null) return context;
        return setLocale(context, lang);
    }

    /** Changes the locale and updates the resource configuration. */
    public static Context setLocale(Context context, String lang) {
        saveLanguage(context, lang);

        Locale locale = new Locale(lang);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }

    public static void saveLanguage(Context context, String lang) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putString(PREF_LANGUAGE, lang).apply();
    }

    public static String getSavedLanguage(Context context) {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString(PREF_LANGUAGE, null);
    }

    public static boolean isSetupDone(Context context) {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(PREF_SETUP_DONE, false);
    }

    public static void markSetupDone(Context context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_SETUP_DONE, true).apply();
    }
}
