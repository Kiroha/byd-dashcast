package com.byd.dashcast;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * WelcomeActivity — shown only on the first launch.
 *
 * Propose le choix de langue.
 * Once the user selects a language, the locale is applied, the
 * "setup_done" flag is saved, and MainActivity is started.
 *
 * On subsequent launches, MainActivity starts directly
 * (voir logique dans onStart ci-dessous).
 */
public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");

        // Already configured → go directly to MainActivity
        if (LocaleHelper.isSetupDone(this)) {
            startMainActivity();
            return;
        }

        setContentView(R.layout.activity_welcome);

        // Dynamic version subtitle: "BYD CLUSTER MIRROR · v<versionName>"
        TextView subtitle = (TextView) findViewById(R.id.tv_welcome_subtitle);
        if (subtitle != null) {
            subtitle.setText("BYD CLUSTER MIRROR · v" + BuildConfig.VERSION_NAME);
        }

        setLanguageButton(R.id.btn_lang_fr, LocaleHelper.LANG_FR);
        setLanguageButton(R.id.btn_lang_en, LocaleHelper.LANG_EN);
        setLanguageButton(R.id.btn_lang_de, LocaleHelper.LANG_DE);
        setLanguageButton(R.id.btn_lang_tr, LocaleHelper.LANG_TR);
        setLanguageButton(R.id.btn_lang_it, LocaleHelper.LANG_IT);
        setLanguageButton(R.id.btn_lang_es, LocaleHelper.LANG_ES);
        setLanguageButton(R.id.btn_lang_ru, LocaleHelper.LANG_RU);
        setLanguageButton(R.id.btn_lang_uk, LocaleHelper.LANG_UK);
        setLanguageButton(R.id.btn_lang_ar, LocaleHelper.LANG_AR);
        setLanguageButton(R.id.btn_lang_uz, LocaleHelper.LANG_UZ);
        setLanguageButton(R.id.btn_lang_kk, LocaleHelper.LANG_KK);
        setLanguageButton(R.id.btn_lang_be, LocaleHelper.LANG_BE);

        // "Continue without changing" — keep the current locale (no setLocale call),
        // mark setup as done so we don't show the welcome screen again, go to MainActivity.
        View btnContinue = findViewById(R.id.btn_continue_without_change);
        if (btnContinue != null) {
            btnContinue.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    LocaleHelper.markSetupDone(WelcomeActivity.this);
                    startMainActivity();
                }
            });
        }
    }

    private void setLanguageButton(int buttonId, final String lang) {
        Button button = (Button) findViewById(buttonId);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectLanguage(lang);
            }
        });
    }

    private void selectLanguage(String lang) {
        LocaleHelper.setLocale(this, lang);
        LocaleHelper.markSetupDone(this);
        startMainActivity();
    }

    private void startMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
        finish(); // WelcomeActivity ne reste pas dans la back stack
    }
}
