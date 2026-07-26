package com.byd.dashcast.ui.welcome

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.MainActivity
import com.byd.dashcast.R
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.LocaleHelper

/**
 * WelcomeActivity — shown only on the first launch.
 *
 * Proposes the language choice. Once the user selects a language, the locale is applied,
 * the "setup_done" flag is saved, and MainActivity is started. On subsequent launches,
 * MainActivity starts directly (see the onCreate setup-done check below).
 */
@SuppressLint("SetTextI18n")
class WelcomeActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.lifecycle(javaClass.simpleName, "onCreate")

        // Already configured → go directly to MainActivity
        if (LocaleHelper.isSetupDone(this)) {
            startMainActivity()
            return
        }

        setContentView(R.layout.activity_welcome)

        // Dynamic version subtitle: "BYD CLUSTER MIRROR · v<versionName>"
        val subtitle = findViewById<TextView>(R.id.tv_welcome_subtitle)
        subtitle?.text = "BYD CLUSTER MIRROR · v" + BuildConfig.VERSION_NAME

        setLanguageButton(R.id.btn_lang_fr, LocaleHelper.LANG_FR)
        setLanguageButton(R.id.btn_lang_en, LocaleHelper.LANG_EN)
        setLanguageButton(R.id.btn_lang_de, LocaleHelper.LANG_DE)
        setLanguageButton(R.id.btn_lang_tr, LocaleHelper.LANG_TR)
        setLanguageButton(R.id.btn_lang_it, LocaleHelper.LANG_IT)
        setLanguageButton(R.id.btn_lang_es, LocaleHelper.LANG_ES)
        setLanguageButton(R.id.btn_lang_ru, LocaleHelper.LANG_RU)
        setLanguageButton(R.id.btn_lang_uk, LocaleHelper.LANG_UK)
        setLanguageButton(R.id.btn_lang_ar, LocaleHelper.LANG_AR)
        setLanguageButton(R.id.btn_lang_uz, LocaleHelper.LANG_UZ)
        setLanguageButton(R.id.btn_lang_kk, LocaleHelper.LANG_KK)
        setLanguageButton(R.id.btn_lang_be, LocaleHelper.LANG_BE)
        setLanguageButton(R.id.btn_lang_pl, LocaleHelper.LANG_PL)

        // "Continue without changing" — keep the current locale (no setLocale call), mark
        // setup as done so we don't show the welcome screen again, go to MainActivity.
        val btnContinue = findViewById<View>(R.id.btn_continue_without_change)
        btnContinue?.setOnClickListener {
            LocaleHelper.markSetupDone(this)
            startMainActivity()
        }
    }

    private fun setLanguageButton(buttonId: Int, lang: String) {
        // 1.2.30 — defensive null-check: a future layout variant could omit one of the
        // language buttons, and findViewById would silently return null → NPE.
        val button = findViewById<Button>(buttonId) ?: return
        button.setOnClickListener { selectLanguage(lang) }
    }

    private fun selectLanguage(lang: String) {
        LocaleHelper.setLocale(this, lang)
        LocaleHelper.markSetupDone(this)
        startMainActivity()
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish() // WelcomeActivity does not stay in the back stack
    }
}
