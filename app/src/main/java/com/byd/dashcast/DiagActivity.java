package com.byd.dashcast;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

/**
 * DiagActivity — clean slate (v0.9.88).
 *
 * <p>All previous diagnostic tests have been removed. New tools will be added here
 * from scratch. This activity now only hosts the M3 nav rail and a placeholder hero.
 *
 * <p>Reference invariants preserved:
 * <ul>
 *   <li>Same Activity name / Intent target (no manifest change needed).</li>
 *   <li>Same nav rail wiring pattern as Settings / SysInfo / Log.</li>
 *   <li>No external class API was exposed by the previous DiagActivity, so wiping the
 *       tests does not break any prod code path — all diagnostic logic was private.</li>
 * </ul>
 */
public class DiagActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        wireDiagNavRail();
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");
    }

    private void wireDiagNavRail() {
        View navApps     = findViewById(R.id.nav_apps_diag);
        View navSettings = findViewById(R.id.nav_settings_diag);
        View navSysinfo  = findViewById(R.id.nav_sysinfo_diag);
        View navLog      = findViewById(R.id.nav_log_diag);
        View navLogo     = findViewById(R.id.iv_nav_logo_diag);
        if (navApps != null)     navApps.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
        if (navSettings != null) navSettings.setOnClickListener(v -> { startActivity(new Intent(this, SettingsActivity.class)); finish(); });
        if (navSysinfo != null)  navSysinfo.setOnClickListener(v -> { startActivity(new Intent(this, SysInfoActivity.class)); finish(); });
        if (navLog != null)      navLog.setOnClickListener(v -> { startActivity(new Intent(this, LogActivity.class)); finish(); });
        if (navLogo != null)     navLogo.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
    }
}
