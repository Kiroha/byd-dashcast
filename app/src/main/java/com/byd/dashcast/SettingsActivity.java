package com.byd.dashcast;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * User-facing settings screen.
 *
 * Currently covers:
 *  1. Cluster screen type (sendInfo cmd: 29 = 8.8", 30 = 12.3" Seal EU, 31 = 10.25")
 *  2. Display overscan margins (left/right and top/bottom in pixels)
 *     Applied via: wm overscan LEFT,TOP,RIGHT,BOTTOM -d <cluster_display_id>
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    // ── SharedPreferences file (shared with MainActivity / ClusterService) ───
    static final String PREFS_NAME      = "byd_app_prefs";

    // ── Cluster type ─────────────────────────────────────────────────────────
    static final String PREF_CLUSTER_TYPE = "cluster_screen_size_cmd";
    static final int    DEFAULT_CLUSTER_TYPE = 30;      // 12.3" — Seal EU

    // ── Overscan inset ───────────────────────────────────────────────────────
    public static final String PREF_INSET_H = "overscan_inset_h";
    public static final String PREF_INSET_V = "overscan_inset_v";
    public static final int    DEFAULT_INSET_H = 80;
    public static final int    DEFAULT_INSET_V = 50;
    // ── OTA pre-release ───────────────────────────────────────────────────────────────
    public static final String PREF_OTA_PRERELEASE = "ota_include_prerelease";
    public static final boolean DEFAULT_OTA_PRERELEASE = false;
    // ── Boot / UI toggles ───────────────────────────────────────────────────────────────
    public static final String PREF_BOOT_AUTO_START       = "boot_auto_start_enabled";
    public static final String PREF_SHOW_CATEGORY_FILTERS = "show_category_filters";
    public static final String PREF_RECONNECT_POPUP       = "reconnect_popup_enabled";
    public static final String PREF_VISUAL_OVERSCAN_MODE  = "visual_overscan_mode";
    // ── Recent cluster apps (shared between MainActivity and FloatingRemoteButton) ──
    public static final String PREF_RECENT_APPS = "recent_cluster_apps";
    // ── Per-app inset key prefixes (shared between MainActivity and ClusterService) ──
    public static final String PREF_INSET_H_PREFIX = "inset_h_";
    public static final String PREF_INSET_V_PREFIX = "inset_v_";
    // ── Views ────────────────────────────────────────────────────────────────
    private RadioGroup  rgClusterType;
    private SeekBar     sbInsetH;
    private SeekBar     sbInsetV;
    private TextView    tvInsetHValue;
    private TextView    tvInsetVValue;
    private Button      btnApply;
    private Button      btnReset;
    private TextView    tvResult;
    private CheckBox    cbPrerelease;
    private CheckBox    cbVisualMode;
    private CheckBox    cbBootAutoStart;
    private CheckBox    cbShowCategoryFilters;
    private CheckBox    cbReconnectPopup;
    private View        llSlidersMode;
    private View        llVisualMode;
    private View        flSafeZone;
    private Button      btnHMinus, btnHPlus, btnVMinus, btnVPlus;

    private volatile boolean mDestroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.settings_header));
        }

        bindViews();
        loadPreferences();
        wireListeners();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    private void bindViews() {
        rgClusterType = findViewById(R.id.rg_cluster_type);
        sbInsetH      = findViewById(R.id.sb_inset_h);
        sbInsetV      = findViewById(R.id.sb_inset_v);
        tvInsetHValue = findViewById(R.id.tv_inset_h_value);
        tvInsetVValue = findViewById(R.id.tv_inset_v_value);
        btnApply      = findViewById(R.id.btn_apply_overscan);
        btnReset      = findViewById(R.id.btn_reset_overscan);
        tvResult      = findViewById(R.id.tv_overscan_result);
        cbPrerelease  = findViewById(R.id.cb_prerelease);
        cbVisualMode  = findViewById(R.id.cb_visual_mode);
        cbBootAutoStart = findViewById(R.id.cb_boot_auto_start);
        llSlidersMode = findViewById(R.id.ll_sliders_mode);
        llVisualMode  = findViewById(R.id.ll_visual_overscan);
        btnHMinus     = findViewById(R.id.btn_h_minus);
        btnHPlus      = findViewById(R.id.btn_h_plus);
        btnVMinus     = findViewById(R.id.btn_v_minus);
        btnVPlus      = findViewById(R.id.btn_v_plus);
        flSafeZone    = findViewById(R.id.fl_safe_zone);
        cbShowCategoryFilters = findViewById(R.id.cb_show_category_filters);
        cbReconnectPopup = findViewById(R.id.cb_reconnect_popup);
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Cluster type radio
        int cmd = prefs.getInt(PREF_CLUSTER_TYPE, DEFAULT_CLUSTER_TYPE);
        switch (cmd) {
            case 29: rgClusterType.check(R.id.rb_88);   break;
            case 31: rgClusterType.check(R.id.rb_1025); break;
            default: rgClusterType.check(R.id.rb_123);  break;  // 30 = Seal EU
        }

        // Overscan sliders
        int h = prefs.getInt(PREF_INSET_H, DEFAULT_INSET_H);
        int v = prefs.getInt(PREF_INSET_V, DEFAULT_INSET_V);
        sbInsetH.setProgress(h);
        sbInsetV.setProgress(v);
        tvInsetHValue.setText(h + " px");
        tvInsetVValue.setText(v + " px");

        // Pre-release toggle
        cbPrerelease.setChecked(prefs.getBoolean(PREF_OTA_PRERELEASE, DEFAULT_OTA_PRERELEASE));
        
        // Visual Mode toggle state
        boolean visualMode = prefs.getBoolean(PREF_VISUAL_OVERSCAN_MODE, false);
        cbVisualMode.setChecked(visualMode);
        updateVisualModeState(visualMode);
        updateVisualMockup();
        
        // Auto Boot Projection toggle state
        boolean bootAutoStart = prefs.getBoolean(PREF_BOOT_AUTO_START, false);
        cbBootAutoStart.setChecked(bootAutoStart);
        
        // Category filters toggle
        boolean showCatFilters = prefs.getBoolean(PREF_SHOW_CATEGORY_FILTERS, false);
        cbShowCategoryFilters.setChecked(showCatFilters);

        // Reconnect popup toggle (default: enabled for backward compat)
        boolean reconnectPopup = prefs.getBoolean(PREF_RECONNECT_POPUP, true);
        cbReconnectPopup.setChecked(reconnectPopup);
    }

    private void wireListeners() {
        // Cluster type: save immediately on selection change
        rgClusterType.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int cmd = DEFAULT_CLUSTER_TYPE;
                if      (checkedId == R.id.rb_88)   cmd = 29;
                else if (checkedId == R.id.rb_123)  cmd = 30;
                else if (checkedId == R.id.rb_1025) cmd = 31;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putInt(PREF_CLUSTER_TYPE, cmd).apply();
                AppLogger.i("SettingsActivity", "cluster type → cmd=" + cmd);
            }
        });

        // H slider
        sbInsetH.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                tvInsetHValue.setText(value + " px");
                if (fromUser) saveInsets(value, sbInsetV.getProgress());
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // V slider
        sbInsetV.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                tvInsetVValue.setText(value + " px");
                if (fromUser) saveInsets(sbInsetH.getProgress(), value);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Apply button
        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                applyOverscan();
            }
        });

        // Reset button
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sbInsetH.setProgress(DEFAULT_INSET_H);
                sbInsetV.setProgress(DEFAULT_INSET_V);
                tvInsetHValue.setText(DEFAULT_INSET_H + " px");
                tvInsetVValue.setText(DEFAULT_INSET_V + " px");
                saveInsets(DEFAULT_INSET_H, DEFAULT_INSET_V);
                applyOverscan();
            }
        });

        // Pre-release checkbox
        cbPrerelease.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_OTA_PRERELEASE, isChecked).apply();
            AppLogger.i("SettingsActivity", "ota_include_prerelease=" + isChecked);
        });

        // Visual Mode checkbox
        cbVisualMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_VISUAL_OVERSCAN_MODE, isChecked).apply();
            updateVisualModeState(isChecked);
        });

        // Auto Start Projection checkbox
        cbBootAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_BOOT_AUTO_START, isChecked).apply();
        });

        View.OnClickListener dpadListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int h = sbInsetH.getProgress();
                int val_v = sbInsetV.getProgress();
                if (v == btnHMinus) h = Math.max(0, h - 10);
                if (v == btnHPlus)  h = Math.min(200, h + 10);
                if (v == btnVMinus) val_v = Math.max(0, val_v - 10);
                if (v == btnVPlus)  val_v = Math.min(200, val_v + 10);
                
                sbInsetH.setProgress(h);
                sbInsetV.setProgress(val_v);
                updateVisualMockup();
                // To keep it real-time as requested:
                applyOverscan(); 
            }
        };

        btnHMinus.setOnClickListener(dpadListener);
        btnHPlus.setOnClickListener(dpadListener);
        btnVMinus.setOnClickListener(dpadListener);
        btnVPlus.setOnClickListener(dpadListener);

        // Category filters checkbox
        cbShowCategoryFilters.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_SHOW_CATEGORY_FILTERS, isChecked).apply();
        });

        // Reconnect popup checkbox
        cbReconnectPopup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_RECONNECT_POPUP, isChecked).apply();
        });
    }

    private void updateVisualModeState(boolean visual) {
        llSlidersMode.setVisibility(visual ? View.GONE : View.VISIBLE);
        llVisualMode.setVisibility(visual ? View.VISIBLE : View.GONE);
    }

    private void updateVisualMockup() {
        int h = sbInsetH.getProgress();
        int v = sbInsetV.getProgress();
        if (flSafeZone != null) {
            android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) flSafeZone.getLayoutParams();
            // Scale logic: Mockup is 320x120. Real cluster is 1920x720. Scale is 1/6.
            params.leftMargin = (int) (h / 6f);
            params.rightMargin = (int) (h / 6f);
            params.topMargin = (int) (v / 6f);
            params.bottomMargin = (int) (v / 6f);
            flSafeZone.setLayoutParams(params);
        }
    }

    // ── Logic ─────────────────────────────────────────────────────────────────

    private void saveInsets(int h, int v) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(PREF_INSET_H, h)
                .putInt(PREF_INSET_V, v)
                .apply();
    }

    /**
     * Sends "wm overscan H,V,H,V -d 1" to the cluster display (display id=1 on BYD Seal EU).
     * The result is shown in tvResult.
     */
    private void applyOverscan() {
        final int h = sbInsetH.getProgress();
        final int v = sbInsetV.getProgress();
        saveInsets(h, v);

        final String cmd = "wm overscan " + h + "," + v + "," + h + "," + v + " -d 1";
        AppLogger.i("SettingsActivity", "applyOverscan → " + cmd);

        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mDestroyed) return;
                        tvResult.setVisibility(View.VISIBLE);
                        tvResult.setText(getString(R.string.settings_overscan_applied, h, v));
                        AppLogger.i("SettingsActivity", "overscan applied OK h=" + h + " v=" + v);
                    }
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mDestroyed) return;
                        tvResult.setVisibility(View.VISIBLE);
                        tvResult.setText("❌ " + error.trim());
                        AppLogger.e("SettingsActivity", "overscan error: " + error);
                    }
                });
            }
        });
    }
}
