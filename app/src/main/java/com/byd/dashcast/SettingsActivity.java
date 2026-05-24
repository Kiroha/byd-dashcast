package com.byd.dashcast;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Display;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import com.byd.dashcast.beta.BetaConfig;
import com.byd.dashcast.platform.Platform;

/**
 * User-facing settings screen.
 *
 * Currently covers:
 *  1. Cluster screen type (sendInfo cmd: 29 = 8.8", 30 = 12.3" Seal EU, 31 = 10.25")
 *  2. Display overscan margins (left/right and top/bottom in pixels)
 *     Applied via: wm overscan LEFT,TOP,RIGHT,BOTTOM -d <cluster_display_id>
 */
@android.annotation.SuppressLint("SetTextI18n")
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
    // ── Stop Projection behaviour ───────────────────────────────────────────────
    // false (default) = Stop button restores the origin cluster with the size
    //                   selected in Settings (sendInfo 29/30/31 + 18 + 0).
    // true            = quick stop: only sendInfo(18) + sendInfo(0), no size
    //                   reconfiguration of the original cluster.
    public static final String PREF_QUICK_STOP            = "quick_stop_enabled";
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
    private View        tvOverscanSectionTitle;
    private View        cardOverscan;
    // Note: fields below are typed as CompoundButton so the layout can use either
    // <CheckBox> or <MaterialSwitch> without breaking the cast. Both inherit
    // setChecked/isChecked/setOnCheckedChangeListener from CompoundButton.
    private CompoundButton cbPrerelease;
    private CompoundButton cbVisualMode;
    private CompoundButton cbBootAutoStart;
    private CompoundButton cbShowCategoryFilters;
    private CompoundButton cbReconnectPopup;
    private CompoundButton cbQuickStop;
    private CompoundButton cbBetaProxyDaemon;
    private CompoundButton cbBetaSystemContext;
    private CompoundButton swDilink5Mode;
    private TextView tvBetaDilink5Support;
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
        wireSettingsNavRail();
        applyDiLink2OverscanGuard();

        // Mockup top-bar Apply button: mirrors the in-card Apply action.
        View btnApplyTop = findViewById(R.id.btn_apply_top);
        if (btnApplyTop != null) {
            btnApplyTop.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { btnApply.performClick(); }
            });
        }

        // Populate dynamic version text ("0.9.2 · build 121").
        TextView tvVersion = (TextView) findViewById(R.id.tv_version_value);
        if (tvVersion != null) {
            tvVersion.setText(BuildConfig.VERSION_NAME + " · build " + BuildConfig.VERSION_CODE);
        }

        // "Rechercher une mise à jour" — runs the same OTA check as the 3-dot menu in MainActivity.
        View btnCheckUpdate = findViewById(R.id.btn_check_update);
        if (btnCheckUpdate != null) {
            btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    UpdateChecker.checkUpdate(SettingsActivity.this,
                            MainActivity.makeOtaProgressListener(SettingsActivity.this, true));
                }
            });
        }

        // "Code source" row — opens the GitHub repository.
        View rowSourceCode = findViewById(R.id.row_source_code);
        if (rowSourceCode != null) {
            rowSourceCode.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openUrl("https://github.com/Kiroha/byd-dashcast"); }
            });
        }
    }

    /** Open the given URL in an external browser. Null-safe and intent-resolve-safe. */
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                    getString(R.string.settings_no_browser_fmt, url),
                    android.widget.Toast.LENGTH_LONG).show();
        }
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

    private void wireSettingsNavRail() {
        View navApps    = findViewById(R.id.nav_apps_set);
        View navDiag    = findViewById(R.id.nav_diag_set);
        View navSysinfo = findViewById(R.id.nav_sysinfo_set);
        View navLog     = findViewById(R.id.nav_log_set);
        View navLogo    = findViewById(R.id.iv_nav_logo_set);
        if (navApps != null)    navApps.setOnClickListener(v -> { startActivity(new android.content.Intent(this, MainActivity.class)); finish(); });
        if (navDiag != null)    navDiag.setOnClickListener(v -> { startActivity(new android.content.Intent(this, DiagActivity.class)); finish(); });
        if (navSysinfo != null) navSysinfo.setOnClickListener(v -> { startActivity(new android.content.Intent(this, SysInfoActivity.class)); finish(); });
        if (navLog != null)     navLog.setOnClickListener(v -> { startActivity(new android.content.Intent(this, LogActivity.class)); finish(); });
        if (navLogo != null)    navLogo.setOnClickListener(v -> { startActivity(new android.content.Intent(this, MainActivity.class)); finish(); });
    }

    private void bindViews() {
        rgClusterType = findViewById(R.id.rg_cluster_type);
        sbInsetH      = findViewById(R.id.sb_inset_h);
        sbInsetV      = findViewById(R.id.sb_inset_v);
        tvInsetHValue = findViewById(R.id.tv_inset_h_value);
        tvInsetVValue = findViewById(R.id.tv_inset_v_value);
        btnApply      = findViewById(R.id.btn_apply_overscan);
        btnReset      = findViewById(R.id.btn_reset_overscan);
        tvResult      = findViewById(R.id.tv_overscan_result);
        tvOverscanSectionTitle = findViewById(R.id.tv_overscan_section_title);
        cardOverscan  = findViewById(R.id.card_overscan);
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
        cbQuickStop      = findViewById(R.id.cb_quick_stop);
        cbBetaProxyDaemon   = findViewById(R.id.cb_beta_proxy_daemon);
        cbBetaSystemContext = findViewById(R.id.cb_beta_system_context);
        swDilink5Mode       = findViewById(R.id.sw_dilink5_mode);
        tvBetaDilink5Support = findViewById(R.id.tv_beta_dilink5_support);
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

        // Reconnect popup toggle (default: disabled — users find it intrusive)
        boolean reconnectPopup = prefs.getBoolean(PREF_RECONNECT_POPUP, false);
        cbReconnectPopup.setChecked(reconnectPopup);

        // Quick stop toggle (default: disabled — Stop button restores origin cluster
        // with size from Settings; when enabled, Stop only sends sendInfo 18 + 0).
        boolean quickStop = prefs.getBoolean(PREF_QUICK_STOP, false);
        cbQuickStop.setChecked(quickStop);

        // Beta Engine toggles (default OFF — restart required after change)
        cbBetaProxyDaemon.setChecked(BetaConfig.isProxyDaemonEnabled(this));
        cbBetaSystemContext.setChecked(BetaConfig.isSystemContextEnabled(this));

        // DiLink 5 mode: tri-state override (AUTO / FORCE_ON / FORCE_OFF).
        // Switch ON ↔ effective DL5; if user has never touched it (AUTO),
        // the initial position mirrors the auto-detect result.
        com.byd.dashcast.platform.Platform p = com.byd.dashcast.platform.Platform.get();
        swDilink5Mode.setChecked(p.isDiLink5(this));
        if (tvBetaDilink5Support != null) {
            String prod = p.rawProductName();
            if (prod == null || prod.isEmpty()) prod = "?";
            tvBetaDilink5Support.setText(getString(
                    R.string.settings_dilink5_mode_support_fmt,
                    prod, p.androidApi(), p.describeMode(this)));
        }
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

        // Row click delegation — RadioButtons are non-clickable (clickable=false in XML),
        // and they live INSIDE LinearLayout rows (not direct RadioGroup children), so neither
        // the RadioButton nor the RadioGroup auto-check mechanism fires on tap. We forward
        // the row click to RadioGroup.check(rb_id), which itself triggers the listener above.
        View row88   = findViewById(R.id.row_cluster_88);
        View row123  = findViewById(R.id.row_cluster_123);
        View row1025 = findViewById(R.id.row_cluster_1025);
        if (row88   != null) row88.setOnClickListener(v   -> rgClusterType.check(R.id.rb_88));
        if (row123  != null) row123.setOnClickListener(v  -> rgClusterType.check(R.id.rb_123));
        if (row1025 != null) row1025.setOnClickListener(v -> rgClusterType.check(R.id.rb_1025));

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

        // Quick stop checkbox
        cbQuickStop.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_QUICK_STOP, isChecked).apply();
        });

        // Beta Engine — both toggles require app restart to take effect
        cbBetaProxyDaemon.setOnCheckedChangeListener((b, isChecked) -> {
            BetaConfig.setProxyDaemonEnabled(this, isChecked);
            AppLogger.i("SettingsActivity", "beta_proxy_daemon=" + isChecked);
            showRestartRequiredDialog();
        });
        cbBetaSystemContext.setOnCheckedChangeListener((b, isChecked) -> {
            BetaConfig.setSystemContextEnabled(this, isChecked);
            AppLogger.i("SettingsActivity", "beta_system_context=" + isChecked);
            showRestartRequiredDialog();
        });
        swDilink5Mode.setOnCheckedChangeListener((b, isChecked) -> {
            com.byd.dashcast.platform.Platform.setForcedBoolean(this, isChecked);
            AppLogger.i("SettingsActivity", "dilink5_mode override=" + isChecked);
            if (tvBetaDilink5Support != null) {
                com.byd.dashcast.platform.Platform p = com.byd.dashcast.platform.Platform.get();
                String prod = p.rawProductName();
                if (prod == null || prod.isEmpty()) prod = "?";
                tvBetaDilink5Support.setText(getString(
                        R.string.settings_dilink5_mode_support_fmt,
                        prod, p.androidApi(), p.describeMode(this)));
            }
            showRestartRequiredDialog();
        });
    }

    /**
     * Beta Engine toggles take effect on next launch — show a non-blocking
     * dialog so the user knows their change isn't live yet.
     */
    private void showRestartRequiredDialog() {
        if (mDestroyed || isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.beta_restart_title)
                .setMessage(R.string.beta_restart_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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
     * Sends "wm overscan H,V,H,V -d <clusterId>" to the cluster display.
     * The cluster display id is resolved dynamically via {@link #resolveClusterDisplayId()}
     * (was hardcoded {@code -d 1} until build 189). If no cluster is detected
     * (e.g. DL2 without secondary display), the call is aborted with a user-visible
     * error — critical to prevent the BYD MTK ROM from silently applying the
     * overscan to display 0 (the main screen), as reported in the field on 22/05/2026.
     * The result is shown in tvResult.
     */
    private void applyOverscan() {
        final int h = sbInsetH.getProgress();
        final int v = sbInsetV.getProgress();
        saveInsets(h, v);

        // DL2 HARD GUARD — never reach a wm command on DL2 even if the card
        // somehow got displayed (defence in depth on top of applyDiLink2OverscanGuard
        // and AdbLocalClient.blockDiLink2Resize). The MTK ROM silently falls back
        // to display 0 on missing -d N → shrinks the main screen.
        if (Platform.get().isDiLink2(this)) {
            tvResult.setVisibility(View.VISIBLE);
            tvResult.setText(R.string.settings_warn_dl2_no_margins);
            AppLogger.w("SettingsActivity",
                    "applyOverscan aborted: DL2 platform (no cluster display)");
            return;
        }

        final int clusterId = resolveClusterDisplayId();
        if (clusterId < 0) {
            tvResult.setVisibility(View.VISIBLE);
            tvResult.setText(R.string.settings_warn_no_cluster);
            AppLogger.w("SettingsActivity",
                    "applyOverscan aborted: no cluster display found (h=" + h + " v=" + v + ")");
            return;
        }

        final String cmd = "wm overscan " + h + "," + v + "," + h + "," + v + " -d " + clusterId;
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
                        tvResult.setText(getString(R.string.settings_error_prefix_fmt, error.trim()));
                        AppLogger.e("SettingsActivity", "overscan error: " + error);
                    }
                });
            }
        });
    }

    /**
     * Resolves the cluster display id dynamically.
     *
     * <p>Returns the id of the first non-default display reported by
     * {@link DisplayManager} (PRESENTATION category first, then any non-default).
     * Returns {@code -1} when no cluster display is present (typically DL2,
     * which has only display 0). This guard is critical: on DL2 the previous
     * hardcoded {@code -d 1} was silently applied to display 0 by the BYD MTK
     * ROM, shrinking the main screen (field report 22/05/2026 — user changed
     * margins to 80/50 in Settings and the main screen got smaller).
     */
    private int resolveClusterDisplayId() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm == null) return -1;
            Display[] presentations = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            if (presentations != null) {
                for (Display d : presentations) {
                    if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d.getDisplayId();
                }
            }
            Display[] all = dm.getDisplays();
            if (all != null) {
                for (Display d : all) {
                    if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d.getDisplayId();
                }
            }
        } catch (Throwable t) {
            AppLogger.w("SettingsActivity", "resolveClusterDisplayId failed: " + t.getMessage());
        }
        return -1;
    }

    /**
     * Hides the overscan margins section on DL2 (alps / k65v1 / API 28) because the
     * platform has no cluster display — the previous hardcoded {@code wm overscan -d 1}
     * was silently applied to display 0 by the MTK ROM, shrinking the main screen.
     * Also hides on any device where no secondary display is detected at activity
     * launch (defensive — covers DL3/DL5 in a state where the cluster is not connected).
     */
    private void applyDiLink2OverscanGuard() {
        boolean hide = false;
        String reason = null;
        try {
            if (Platform.get().isDiLink2(this)) {
                hide = true;
                reason = "DL2 platform detected (alps/k65v1) — no cluster display";
            } else if (resolveClusterDisplayId() < 0) {
                hide = true;
                reason = "no secondary display present at launch";
            }
        } catch (Throwable t) {
            AppLogger.w("SettingsActivity", "applyDiLink2OverscanGuard probe failed: " + t.getMessage());
        }
        if (hide) {
            if (tvOverscanSectionTitle != null) tvOverscanSectionTitle.setVisibility(View.GONE);
            if (cardOverscan != null)           cardOverscan.setVisibility(View.GONE);
            AppLogger.i("SettingsActivity", "Overscan section hidden: " + reason);
        }
    }
}
