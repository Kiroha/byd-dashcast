package com.byd.myapp;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;

/**
 * WelcomeActivity — affiché uniquement au premier lancement.
 *
 * Propose le choix de langue (FR / EN).
 * Dès que l'utilisateur choisit, la locale est appliquée, le flag
 * "setup_done" est sauvegardé, et MainActivity est démarrée.
 *
 * Aux lancements suivants, MainActivity démarre directement
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

        // Si déjà configuré → aller directement à MainActivity
        if (LocaleHelper.isSetupDone(this)) {
            startMainActivity();
            return;
        }

        setContentView(R.layout.activity_welcome);

        Button btnFr = (Button) findViewById(R.id.btn_lang_fr);
        Button btnEn = (Button) findViewById(R.id.btn_lang_en);

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
