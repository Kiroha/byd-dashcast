package com.byd.myapp;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;

/**
 * WelcomeActivity — shown only on the first launch.
 *
 * Presents the language selection screen (FR / EN / DE / TR / IT).
 * Once the user selects a language, the locale is applied, the
 * "setup_done" flag is saved, and MainActivity is started.
 *
 * On subsequent launches, MainActivity starts directly
 * (see skip logic in onStart below).
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

        Button btnFr = (Button) findViewById(R.id.btn_lang_fr);
        Button btnEn = (Button) findViewById(R.id.btn_lang_en);
        Button btnDe = (Button) findViewById(R.id.btn_lang_de);
        Button btnTr = (Button) findViewById(R.id.btn_lang_tr);
        Button btnIt = (Button) findViewById(R.id.btn_lang_it);

        btnFr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectLanguage(LocaleHelper.LANG_FR);
            }
        });

        btnEn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectLanguage(LocaleHelper.LANG_EN);
            }
        });

        btnDe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectLanguage(LocaleHelper.LANG_DE);
            }
        });

        btnTr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectLanguage(LocaleHelper.LANG_TR);
            }
        });

        btnIt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectLanguage(LocaleHelper.LANG_IT);
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
        finish(); // WelcomeActivity must not remain in the back stack
    }
}
