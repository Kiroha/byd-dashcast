package com.byd.dashcast;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.byd.dashcast.beta.BetaConfig;
import com.byd.dashcast.beta.BetaTestRunner;
import com.byd.dashcast.beta.BetaTestRunner.TestDef;
import com.byd.dashcast.beta.BetaTestRunner.TestResult;
import com.byd.dashcast.beta.BetaTestRunner.Status;
import com.byd.dashcast.dilink5.DiLink5TestRunner;
import com.byd.dashcast.dilink5.Dl5ClusterReconRunner;
import com.byd.dashcast.dilink2.DiLink2TestRunner;
import com.byd.dashcast.dilink4.DiLink4TestRunner;
import com.byd.dashcast.platform.Platform;
import com.byd.dashcast.voice.VoiceService;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * DiagActivity — tabbed diagnostic UI (v1.1.0-beta).
 *
 * <p>Tabs (mirroring the mockup):
 * <ul>
 *   <li>Cluster, Display, ADB local, Système, Stress test — Phase-1 stubs
 *       ("Coming soon").</li>
 *   <li><b>Beta Engine</b> — full runner for the Component A / B test suite
 *       (see {@link BetaTestRunner}).</li>
 * </ul>
 */
public class DiagActivity extends AppCompatActivity {

    private static final int TAB_CLUSTER     = 0;
    private static final int TAB_DISPLAY     = 1;
    private static final int TAB_ADB_LOCAL   = 2;
    private static final int TAB_SYSTEM      = 3;
    private static final int TAB_ADAS        = 4;
    private static final int TAB_BETA_ENGINE = 5;
    private static final int TAB_DILINK5     = 6;
    private static final int TAB_DILINK2     = 7;
    private static final int TAB_DILINK4     = 8;
    private static final int TAB_MIRROR      = 9;
    private static final int TAB_SNIFFER     = 10;
    private static final int TAB_CLUSTER_DL5 = 11;
    private static final int TAB_EXPORT_APK  = 12;
    private static final int TAB_VOICE       = 13;

    private TabLayout    tabs;
    private View         panelBeta;
    private View         panelDl5;
    private View         panelDl2;
    private View         panelDl4;
    private View         panelMirror;
    private View         panelSniffer;
    private View         panelAdas;
    private View         panelClusterPoc;
    private View         panelClusterDl5;
    private View         panelExportApk;
    private View         panelVoice;
    private View         panelComingSoon;
    private static final int TAB_COUNT = 14; // cluster,display,adb_local,system,adas,beta,dl5,dl2,dl4,mirror,sniffer,cluster_dl5,export_apk,voice

    // Beta panel views
    private TextView       tvBetaStatusA;
    private TextView       tvBetaStatusB;
    private TextView       tvBetaCounters;
    private MaterialButton btnRunAll;
    private MaterialButton btnCopyReport;
    private LinearLayout   llTestList;

    // DiLink 5 panel views
    private TextView       tvDl5HeaderSubtitle;
    private TextView       tvDl5ModePill;
    private TextView       tvDl5Counters;
    private Spinner        spDl5TargetApp;
    private MaterialButton btnDl5RunAll;
    private MaterialButton btnDl5CopyReport;
    private LinearLayout   llDl5TestList;
    private final List<LaunchableApp> dl5Apps = new ArrayList<>();
    private String                    dl5SelectedPkg;
    private final List<View> dl5RowViews = new ArrayList<>();
    private final List<DiLink5TestRunner.TestResult> dl5LastResults = new ArrayList<>();

    // Cluster DL5 recon panel views (v1.2.37 — capability probing for DL5 cluster resize)
    private TextView       tvDl5ClusterSubtitle;
    private TextView       tvDl5ClusterPill;
    private TextView       tvDl5ClusterCounters;
    private MaterialButton btnDl5ClusterRunAll;
    private MaterialButton btnDl5ClusterCopyReport;
    private MaterialButton btnDl5ClusterSendTelegram;
    private MaterialButton btnDl5ClusterTestFission;   // v1.2.41 — live Fission test (DL5 only)
    private LinearLayout   llDl5ClusterFissionRow;     // v1.2.41 — DL5-only gated container
    private LinearLayout   llDl5ClusterTestList;
    private final List<View> dl5ClusterRowViews = new ArrayList<>();
    private final List<com.byd.dashcast.dilink5.DiLink5TestRunner.TestResult> dl5ClusterLastResults = new ArrayList<>();
    /** v1.2.41 — whether the currently-displayed rows are F-tests (true) or R-tests (false). */
    private boolean dl5ClusterShowingFission = false;

    // DiLink 2 panel views (build 185 — recon-only)
    private TextView       tvDl2HeaderSubtitle;
    private TextView       tvDl2SignaturePill;
    private TextView       tvDl2Counters;
    private MaterialButton btnDl2RunAll;
    private MaterialButton btnDl2CopyReport;
    private LinearLayout   llDl2TestList;
    private final List<View> dl2RowViews = new ArrayList<>();
    private final List<DiLink2TestRunner.TestResult> dl2LastResults = new ArrayList<>();

    // DiLink 4 panel views (v1.2.28 — full DL4 diagnostic suite with live D-tier)
    private TextView       tvDl4HeaderSubtitle;
    private TextView       tvDl4SignaturePill;
    private TextView       tvDl4Counters;
    private MaterialButton btnDl4RunAll;
    private MaterialButton btnDl4CopyReport;
    private LinearLayout   llDl4TestList;
    private final List<View> dl4RowViews = new ArrayList<>();
    private final List<DiLink4TestRunner.TestResult> dl4LastResults = new ArrayList<>();

    // Per-test row views, indexed by test position
    private final List<View> rowViews = new ArrayList<>();
    private final List<TestResult> lastResults = new ArrayList<>();
    private volatile boolean mDestroyed = false;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        wireDiagNavRail();
        bindTabs();
        bindBetaPanel();
        bindDl5Panel();
        bindDl2Panel();
        bindDl4Panel();
        bindMirrorPanel();
        bindSnifferPanel();
        bindAdasPanel();
        bindClusterPocPanel();
        bindClusterDl5Panel();
        bindExportApkPanel();
        bindVoicePanel();
        // v1.2.46 — prepare*TestRows() are now lazy : each catalog's ~10–42 row
        // inflations are deferred to the first showPanelForTab(thatTab) call,
        // shaving ~400–800 ms off the DiagActivity onCreate path on DL3/DL5.
        updateStatusPills();
        restoreSnifferState();
        // Default tab: DiLink 2 when auto-detected as DL2 (build 192), DiLink 5 when DL5,
        // Beta Engine otherwise. DL2 takes priority because its diag surface is the only
        // useful one on that platform (cluster RE workflow).
        int defaultTab;
        if (Platform.get().isAutoDetectedDiLink2())      defaultTab = TAB_DILINK2;
        else if (Platform.get().isAutoDetectedDiLink4()) defaultTab = TAB_DILINK4;
        else if (Platform.get().isAutoDetectedDiLink5()) defaultTab = TAB_DILINK5;
        else                                             defaultTab = TAB_BETA_ENGINE;
        tabs.selectTab(tabs.getTabAt(defaultTab));
        showPanelForTab(defaultTab);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        try { exportApkCleanupHandler.removeCallbacksAndMessages(null); } catch (Throwable ignore) {}
        try { unregisterVoiceReceiver(); } catch (Throwable ignore) {}
        try {
            if (voiceWakeWordEngine != null) {
                VoiceService.setSampleConsumer(null);
                voiceWakeWordEngine.stop();
                voiceWakeWordEngine = null;
            }
        } catch (Throwable ignore) {}
        super.onDestroy();
    }

    // ─── Nav rail ───────────────────────────────────────────────────────────

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
        // v1.2.44 — Hotspot navrail entry (DL3 + use_own_sim runtime-gated)
        NavRailHotspot.apply(this, R.id.nav_hotspot_diag, true);
    }

    // ─── Tabs ───────────────────────────────────────────────────────────────

    private void bindTabs() {
        tabs            = findViewById(R.id.tabs_diag);
        panelBeta       = findViewById(R.id.panel_beta_engine);
        panelDl5        = findViewById(R.id.panel_dilink5);
        panelDl2        = findViewById(R.id.panel_dilink2);
        panelDl4        = findViewById(R.id.panel_dilink4);
        panelMirror     = findViewById(R.id.panel_mirror);
        panelSniffer    = findViewById(R.id.panel_sniffer);
        panelAdas       = findViewById(R.id.panel_adas);
        panelClusterPoc = findViewById(R.id.panel_cluster_poc);
        panelClusterDl5 = findViewById(R.id.panel_cluster_dl5);
        panelExportApk  = findViewById(R.id.panel_export_apk);
        panelVoice      = findViewById(R.id.panel_voice);
        panelComingSoon = findViewById(R.id.panel_coming_soon);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab)   { showPanelForTab(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // Build 185 — horizontal swipe on the content frame to navigate between tabs.
        // Lightweight Option B (see /memories/repo/byd-project.md TODO for proper ViewPager2 refactor).
        attachSwipeNavigation(findViewById(R.id.fl_diag_content));
    }

    private void attachSwipeNavigation(View target) {
        if (target == null) return;
        final GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int MIN_DISTANCE = 120; // px
            private static final int MIN_VELOCITY = 220; // px/s
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < MIN_DISTANCE || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                if (Math.abs(vx) < MIN_VELOCITY) return false;
                int current = tabs.getSelectedTabPosition();
                int next = current + (dx < 0 ? 1 : -1);
                if (next < 0 || next >= TAB_COUNT) return false;
                TabLayout.Tab t = tabs.getTabAt(next);
                if (t != null) t.select();
                return true;
            }
        });
        target.setOnTouchListener((v, ev) -> {
            // Only intercept clear horizontal flings; never consume the event so child scrollers keep working.
            detector.onTouchEvent(ev);
            return false;
        });
    }

    private void showPanelForTab(int position) {
        boolean isBeta       = position == TAB_BETA_ENGINE;
        boolean isDl5        = position == TAB_DILINK5;
        boolean isDl2        = position == TAB_DILINK2;
        boolean isDl4        = position == TAB_DILINK4;
        boolean isMirror     = position == TAB_MIRROR;
        boolean isSniffer    = position == TAB_SNIFFER;
        boolean isAdas       = position == TAB_ADAS;
        boolean isClusterPoc = position == TAB_CLUSTER;
        boolean isClusterDl5 = position == TAB_CLUSTER_DL5;
        boolean isExportApk  = position == TAB_EXPORT_APK;
        boolean isVoice      = position == TAB_VOICE;
        panelBeta.setVisibility(isBeta ? View.VISIBLE : View.GONE);
        panelDl5.setVisibility(isDl5 ? View.VISIBLE : View.GONE);
        panelDl2.setVisibility(isDl2 ? View.VISIBLE : View.GONE);
        panelDl4.setVisibility(isDl4 ? View.VISIBLE : View.GONE);
        panelMirror.setVisibility(isMirror ? View.VISIBLE : View.GONE);
        panelSniffer.setVisibility(isSniffer ? View.VISIBLE : View.GONE);
        panelAdas.setVisibility(isAdas ? View.VISIBLE : View.GONE);
        panelClusterPoc.setVisibility(isClusterPoc ? View.VISIBLE : View.GONE);
        panelClusterDl5.setVisibility(isClusterDl5 ? View.VISIBLE : View.GONE);
        panelExportApk.setVisibility(isExportApk ? View.VISIBLE : View.GONE);
        panelVoice.setVisibility(isVoice ? View.VISIBLE : View.GONE);
        // v1.2.46 — lazy row preparation : inflate only the catalog actually
        // about to be displayed. Each helper is a no-op once already prepared,
        // so subsequent tab switches are free.
        if (isBeta)       prepareTestRowsIfNeeded();
        if (isDl5)        prepareDl5TestRowsIfNeeded();
        if (isDl2)        prepareDl2TestRowsIfNeeded();
        if (isDl4)        prepareDl4TestRowsIfNeeded();
        if (isMirror)     prepareMirrorTestRowsIfNeeded();
        if (isClusterDl5) prepareClusterDl5TestRowsIfNeeded();
        if (isExportApk) refreshExportApkListIfNeeded();
        if (isVoice)     onVoicePanelEntered();
        panelComingSoon.setVisibility((isBeta || isDl5 || isDl2 || isDl4 || isMirror || isSniffer || isAdas || isClusterPoc || isClusterDl5 || isExportApk || isVoice) ? View.GONE : View.VISIBLE);
        if (!isBeta && !isDl5 && !isDl2 && !isDl4 && !isMirror && !isSniffer && !isAdas && !isClusterPoc && !isClusterDl5 && !isExportApk && !isVoice) {
            TextView title = panelComingSoon.findViewById(R.id.tv_coming_soon_title);
            int titleRes;
            switch (position) {
                case TAB_DISPLAY:   titleRes = R.string.diag_tab_display;   break;
                case TAB_ADB_LOCAL: titleRes = R.string.diag_tab_adb_local; break;
                case TAB_SYSTEM:    titleRes = R.string.diag_tab_system;    break;
                default:            titleRes = R.string.diag_coming_soon_title;
            }
            if (title != null) title.setText(titleRes);
        }
    }

    // ─── Beta Engine panel ──────────────────────────────────────────────────

    private void bindBetaPanel() {
        tvBetaStatusA  = panelBeta.findViewById(R.id.tv_beta_status_a);
        tvBetaStatusB  = panelBeta.findViewById(R.id.tv_beta_status_b);
        tvBetaCounters = panelBeta.findViewById(R.id.tv_beta_counters);
        btnRunAll      = panelBeta.findViewById(R.id.btn_beta_run_all);
        btnCopyReport  = panelBeta.findViewById(R.id.btn_beta_copy_report);
        llTestList     = panelBeta.findViewById(R.id.ll_beta_test_list);

        btnRunAll.setOnClickListener(v -> runAllTests());
        btnCopyReport.setOnClickListener(v -> copyReport());
        btnCopyReport.setEnabled(false);
    }

    /** v1.2.46 — lazy : inflate the Beta catalog rows on first panel show. */
    private void prepareTestRowsIfNeeded() {
        if (betaRowsPrepared) return;
        betaRowsPrepared = true;
        prepareTestRows();
    }

    private void prepareTestRows() {
        rowViews.clear();
        lastResults.clear();
        llTestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (TestDef def : BetaTestRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_beta_test, llTestList, false);
            TestResult r = new TestResult(def);
            r.status = Status.PENDING;
            bindRow(row, r);
            llTestList.addView(row);
            rowViews.add(row);
            lastResults.add(r);
        }
    }

    private void bindRow(View row, TestResult r) {
        TextView status = row.findViewById(R.id.tv_test_status);
        TextView id     = row.findViewById(R.id.tv_test_id);
        TextView title  = row.findViewById(R.id.tv_test_title);
        TextView desc   = row.findViewById(R.id.tv_test_description);
        TextView msg    = row.findViewById(R.id.tv_test_message);
        TextView elap   = row.findViewById(R.id.tv_test_elapsed);

        id.setText(r.def.id);
        title.setText(r.def.title);
        desc.setText(r.def.description);

        String glyph; int color;
        switch (r.status) {
            case PASS:    glyph = "✓"; color = 0xFF4CAF50; break;
            case FAIL:    glyph = "✗"; color = 0xFFE53935; break;
            case SKIPPED: glyph = "⊘"; color = 0xFF9E9E9E; break;
            case RUNNING: glyph = "…"; color = 0xFFFFB300; break;
            default:      glyph = "·"; color = 0xFF607D8B; break;
        }
        status.setText(glyph);
        status.setTextColor(color);

        if (r.elapsedMs > 0) {
            elap.setText(r.elapsedMs + " ms");
        } else {
            elap.setText("");
        }

        if (r.message != null && !r.message.isEmpty()) {
            msg.setVisibility(View.VISIBLE);
            msg.setText(r.message);
            msg.setTextColor(r.status == Status.FAIL ? 0xFFE53935
                           : r.status == Status.PASS ? 0xFF4CAF50 : 0xFF9E9E9E);
        } else {
            msg.setVisibility(View.GONE);
        }
    }

    private void updateStatusPills() {
        boolean aOn = BetaConfig.isProxyDaemonEnabled(this);
        boolean bOn = BetaConfig.isSystemContextEnabled(this);
        tvBetaStatusA.setText(aOn ? R.string.diag_beta_pill_a_on : R.string.diag_beta_pill_a_off);
        tvBetaStatusB.setText(bOn ? R.string.diag_beta_pill_b_on : R.string.diag_beta_pill_b_off);
    }

    private void runAllTests() {
        btnRunAll.setEnabled(false);
        btnCopyReport.setEnabled(false);
        BetaTestRunner.runAll(this, new BetaTestRunner.Listener() {
            @Override public void onSuiteStarted(List<TestResult> results) {
                if (mDestroyed) return;
                lastResults.clear();
                lastResults.addAll(results);
                for (int i = 0; i < results.size() && i < rowViews.size(); i++) {
                    bindRow(rowViews.get(i), results.get(i));
                }
                tvBetaCounters.setText(getString(R.string.diag_beta_counters_running));
            }
            @Override public void onTestUpdated(int index, TestResult result) {
                if (mDestroyed) return;
                if (index < rowViews.size()) bindRow(rowViews.get(index), result);
                updateCounters();
            }
            @Override public void onSuiteFinished(List<TestResult> results) {
                if (mDestroyed) return;
                btnRunAll.setEnabled(true);
                btnCopyReport.setEnabled(true);
                updateCounters();
            }
        });
    }

    private void updateCounters() {
        int pass = 0, fail = 0, skip = 0;
        for (TestResult r : lastResults) {
            if (r.status == Status.PASS) pass++;
            else if (r.status == Status.FAIL) fail++;
            else if (r.status == Status.SKIPPED) skip++;
        }
        tvBetaCounters.setText(getString(R.string.diag_beta_counters_fmt, pass, fail, skip));
    }

    private void copyReport() {
        String report = BetaTestRunner.buildReport(lastResults);
        AppLogger.i("DiagActivity", "Beta Engine report:\n" + report);
        // Always send as a .log file attachment — easier for users to forward.
        AppLogger.shareWithReport(this, report);
    }

    // ─── DiLink 5 panel ─────────────────────────────────────────────────────

    private static final class LaunchableApp {
        final String label;
        final String pkg;
        LaunchableApp(String label, String pkg) { this.label = label; this.pkg = pkg; }
        @Override public String toString() { return label + "  (" + pkg + ")"; }
    }

    private void bindDl5Panel() {
        tvDl5HeaderSubtitle = panelDl5.findViewById(R.id.tv_dl5_header_subtitle);
        tvDl5ModePill       = panelDl5.findViewById(R.id.tv_dl5_mode_pill);
        tvDl5Counters       = panelDl5.findViewById(R.id.tv_dl5_counters);
        spDl5TargetApp      = panelDl5.findViewById(R.id.sp_dl5_target_app);
        btnDl5RunAll        = panelDl5.findViewById(R.id.btn_dl5_run_all);
        btnDl5CopyReport    = panelDl5.findViewById(R.id.btn_dl5_copy_report);
        llDl5TestList       = panelDl5.findViewById(R.id.ll_dl5_test_list);

        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        tvDl5HeaderSubtitle.setText(getString(
                R.string.diag_dl5_header_subtitle_fmt, prod, p.androidApi()));

        int pillRes;
        boolean effective = p.isDiLink5(this);
        boolean autoDetected = p.isAutoDetectedDiLink5();
        if (effective && autoDetected)       pillRes = R.string.diag_dl5_pill_mode_auto_on;
        else if (effective)                  pillRes = R.string.diag_dl5_pill_mode_forced_on;
        else if (autoDetected)               pillRes = R.string.diag_dl5_pill_mode_forced_off;
        else                                 pillRes = R.string.diag_dl5_pill_mode_auto_off;
        tvDl5ModePill.setText(pillRes);

        populateLaunchableApps();

        btnDl5RunAll.setOnClickListener(v -> runDl5AllTests());
        btnDl5CopyReport.setOnClickListener(v -> copyDl5Report());
        btnDl5CopyReport.setEnabled(false);
    }

    /**
     * v1.2.57 — runs the launchable-app enumeration on a background thread
     * because {@code queryIntentActivities + ResolveInfo.loadLabel(pm)} for
     * 50–150 installed apps blocks the UI thread for 80–300 ms on DL3/DL5
     * and was previously executed eagerly in {@code onCreate} via
     * {@link #bindDl5Panel()}. The Run-all button is disabled while the scan
     * is in flight so D8 can never be SKIPPED by a race with an empty
     * {@link #dl5SelectedPkg}.
     */
    private void populateLaunchableApps() {
        dl5Apps.clear();
        btnDl5RunAll.setEnabled(false);
        final PackageManager pm = getPackageManager();
        final String selfPkg    = getPackageName();
        new Thread(() -> {
            final List<LaunchableApp> scanned = new ArrayList<>();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolves;
            try {
                resolves = pm.queryIntentActivities(main, 0);
            } catch (Throwable t) {
                resolves = Collections.emptyList();
                AppLogger.w("DiagActivity", "queryIntentActivities failed: " + t.getMessage());
            }
            for (ResolveInfo ri : resolves) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(selfPkg)) continue;
                CharSequence label = ri.loadLabel(pm);
                String lbl = (label != null && label.length() > 0) ? label.toString() : pkg;
                scanned.add(new LaunchableApp(lbl, pkg));
            }
            Collections.sort(scanned, new Comparator<LaunchableApp>() {
                @Override public int compare(LaunchableApp a, LaunchableApp b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
            safeRunOnUiThread(() -> applyLaunchableApps(scanned));
        }, "dl5-apps-scan").start();
    }

    /** v1.2.57 — UI-thread tail of {@link #populateLaunchableApps()}. */
    private void applyLaunchableApps(List<LaunchableApp> scanned) {
        if (mDestroyed) return;
        dl5Apps.clear();
        dl5Apps.addAll(scanned);
        ArrayAdapter<LaunchableApp> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, dl5Apps);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDl5TargetApp.setAdapter(adapter);

        int defaultIdx = 0;
        for (int i = 0; i < dl5Apps.size(); i++) {
            if ("com.android.settings".equals(dl5Apps.get(i).pkg)) { defaultIdx = i; break; }
        }
        if (!dl5Apps.isEmpty()) {
            spDl5TargetApp.setSelection(defaultIdx);
            dl5SelectedPkg = dl5Apps.get(defaultIdx).pkg;
        }
        spDl5TargetApp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (position >= 0 && position < dl5Apps.size()) {
                    dl5SelectedPkg = dl5Apps.get(position).pkg;
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { dl5SelectedPkg = null; }
        });
        btnDl5RunAll.setEnabled(true);
    }

    private void prepareDl5TestRows() {
        dl5RowViews.clear();
        dl5LastResults.clear();
        llDl5TestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DiLink5TestRunner.TestDef def : DiLink5TestRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_beta_test, llDl5TestList, false);
            DiLink5TestRunner.TestResult r = new DiLink5TestRunner.TestResult(def);
            r.status = DiLink5TestRunner.Status.PENDING;
            bindDl5Row(row, r);
            llDl5TestList.addView(row);
            dl5RowViews.add(row);
            dl5LastResults.add(r);
        }
    }

    /** v1.2.46 — lazy : inflate the DL5 catalog rows on first panel show. */
    private void prepareDl5TestRowsIfNeeded() {
        if (dl5RowsPrepared) return;
        dl5RowsPrepared = true;
        prepareDl5TestRows();
    }

    private void bindDl5Row(View row, DiLink5TestRunner.TestResult r) {
        TextView status = row.findViewById(R.id.tv_test_status);
        TextView id     = row.findViewById(R.id.tv_test_id);
        TextView title  = row.findViewById(R.id.tv_test_title);
        TextView desc   = row.findViewById(R.id.tv_test_description);
        TextView msg    = row.findViewById(R.id.tv_test_message);
        TextView elap   = row.findViewById(R.id.tv_test_elapsed);

        id.setText(r.def.id);
        title.setText(r.def.title);
        desc.setText(r.def.description);

        String glyph; int color;
        switch (r.status) {
            case PASS:    glyph = "✓"; color = 0xFF4CAF50; break;
            case FAIL:    glyph = "✗"; color = 0xFFE53935; break;
            case WARN:    glyph = "!"; color = 0xFFFFB300; break;
            case SKIPPED: glyph = "⊘"; color = 0xFF9E9E9E; break;
            case RUNNING: glyph = "…"; color = 0xFFFFB300; break;
            default:      glyph = "·"; color = 0xFF607D8B; break;
        }
        status.setText(glyph);
        status.setTextColor(color);

        elap.setText(r.elapsedMs > 0 ? (r.elapsedMs + " ms") : "");

        if (r.message != null && !r.message.isEmpty()) {
            msg.setVisibility(View.VISIBLE);
            msg.setText(r.message);
            int textColor;
            switch (r.status) {
                case FAIL: textColor = 0xFFE53935; break;
                case PASS: textColor = 0xFF4CAF50; break;
                case WARN: textColor = 0xFFFFB300; break;
                default:   textColor = 0xFF9E9E9E; break;
            }
            msg.setTextColor(textColor);
        } else {
            msg.setVisibility(View.GONE);
        }
    }

    private void runDl5AllTests() {
        btnDl5RunAll.setEnabled(false);
        btnDl5CopyReport.setEnabled(false);
        DiLink5TestRunner.D8Params d8 = dl5SelectedPkg != null
                ? new DiLink5TestRunner.D8Params(dl5SelectedPkg, -1)
                : null;
        if (d8 != null) {
            Toast.makeText(this,
                    getString(R.string.diag_dl5_d8_running_toast, dl5SelectedPkg),
                    Toast.LENGTH_SHORT).show();
        }
        DiLink5TestRunner.runAll(this, d8, new DiLink5TestRunner.Listener() {
            @Override public void onSuiteStarted(List<DiLink5TestRunner.TestResult> results) {
                if (mDestroyed) return;
                dl5LastResults.clear();
                dl5LastResults.addAll(results);
                for (int i = 0; i < results.size() && i < dl5RowViews.size(); i++) {
                    bindDl5Row(dl5RowViews.get(i), results.get(i));
                }
                tvDl5Counters.setText(getString(R.string.diag_beta_counters_running));
            }
            @Override public void onTestUpdated(int index, DiLink5TestRunner.TestResult result) {
                if (mDestroyed) return;
                if (index < dl5RowViews.size()) bindDl5Row(dl5RowViews.get(index), result);
                updateDl5Counters();
            }
            @Override public void onSuiteFinished(List<DiLink5TestRunner.TestResult> results) {
                if (mDestroyed) return;
                btnDl5RunAll.setEnabled(true);
                btnDl5CopyReport.setEnabled(true);
                updateDl5Counters();
            }
        });
    }

    private void updateDl5Counters() {
        int pass = 0, fail = 0, skip = 0, warn = 0;
        for (DiLink5TestRunner.TestResult r : dl5LastResults) {
            switch (r.status) {
                case PASS:    pass++; break;
                case FAIL:    fail++; break;
                case SKIPPED: skip++; break;
                case WARN:    warn++; break;
                default: break;
            }
        }
        tvDl5Counters.setText(getString(R.string.diag_dl5_counters_fmt, pass, fail, warn, skip));
    }

    private void copyDl5Report() {
        String report = DiLink5TestRunner.buildReport(this, dl5LastResults);
        AppLogger.i("DiagActivity", "DiLink 5 report:\n" + report);
        AppLogger.shareWithReport(this, report);
    }

    // ─── Cluster DL5 recon panel (v1.2.37) ──────────────────────────────────

    private void bindClusterDl5Panel() {
        tvDl5ClusterSubtitle      = panelClusterDl5.findViewById(R.id.tv_dl5_cluster_subtitle);
        tvDl5ClusterPill          = panelClusterDl5.findViewById(R.id.tv_dl5_cluster_pill);
        tvDl5ClusterCounters      = panelClusterDl5.findViewById(R.id.tv_dl5_cluster_counters);
        btnDl5ClusterRunAll       = panelClusterDl5.findViewById(R.id.btn_dl5_cluster_run_all);
        btnDl5ClusterCopyReport   = panelClusterDl5.findViewById(R.id.btn_dl5_cluster_copy_report);
        btnDl5ClusterSendTelegram = panelClusterDl5.findViewById(R.id.btn_dl5_cluster_send_telegram);
        btnDl5ClusterTestFission  = panelClusterDl5.findViewById(R.id.btn_dl5_cluster_test_fission);
        llDl5ClusterFissionRow    = panelClusterDl5.findViewById(R.id.ll_dl5_cluster_fission_row);
        llDl5ClusterTestList      = panelClusterDl5.findViewById(R.id.ll_dl5_cluster_test_list);

        boolean dl5 = Platform.get().isAutoDetectedDiLink5();
        int pillRes;
        if (dl5)                                  pillRes = R.string.diag_dl5_cluster_pill_dl5;
        else if (android.os.Build.VERSION.SDK_INT >= 29) pillRes = R.string.diag_dl5_cluster_pill_other;
        else                                      pillRes = R.string.diag_dl5_cluster_pill_unknown;
        tvDl5ClusterPill.setText(pillRes);

        // Fission live test row is DL5-only — HARD RULE: never offer the
        // destructive test on DL2/3/4 (compat-override semantics + display
        // topology differ).
        llDl5ClusterFissionRow.setVisibility(dl5 ? View.VISIBLE : View.GONE);

        btnDl5ClusterRunAll.setOnClickListener(v -> runClusterDl5AllTests());
        btnDl5ClusterCopyReport.setOnClickListener(v -> copyClusterDl5Report());
        btnDl5ClusterSendTelegram.setOnClickListener(v -> sendClusterDl5ToTelegram());
        btnDl5ClusterTestFission.setOnClickListener(v -> confirmAndRunClusterDl5FissionTests());
        btnDl5ClusterCopyReport.setEnabled(false);
        btnDl5ClusterSendTelegram.setEnabled(false);
    }

    private void prepareClusterDl5TestRows() {
        prepareClusterDl5TestRowsFor(Dl5ClusterReconRunner.catalog());
        dl5ClusterShowingFission = false;
    }

    /** v1.2.46 — lazy : inflate the DL5 Cluster recon rows on first panel show. */
    private void prepareClusterDl5TestRowsIfNeeded() {
        if (clusterDl5RowsPrepared) return;
        clusterDl5RowsPrepared = true;
        prepareClusterDl5TestRows();
    }

    /** v1.2.41 — shared row builder for either the R-recon or the F-fission catalog. */
    private void prepareClusterDl5TestRowsFor(List<DiLink5TestRunner.TestDef> defs) {
        dl5ClusterRowViews.clear();
        dl5ClusterLastResults.clear();
        llDl5ClusterTestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DiLink5TestRunner.TestDef def : defs) {
            View row = inflater.inflate(R.layout.item_beta_test, llDl5ClusterTestList, false);
            DiLink5TestRunner.TestResult r = new DiLink5TestRunner.TestResult(def);
            r.status = DiLink5TestRunner.Status.PENDING;
            bindDl5Row(row, r); // reuse existing row binder — same TestResult type
            llDl5ClusterTestList.addView(row);
            dl5ClusterRowViews.add(row);
            dl5ClusterLastResults.add(r);
        }
    }

    private void runClusterDl5AllTests() {
        // Re-prime the list with the R-recon catalog (in case the user just
        // ran a Fission test before).
        prepareClusterDl5TestRowsFor(Dl5ClusterReconRunner.catalog());
        dl5ClusterShowingFission = false;
        btnDl5ClusterRunAll.setEnabled(false);
        if (btnDl5ClusterTestFission != null) btnDl5ClusterTestFission.setEnabled(false);
        btnDl5ClusterCopyReport.setEnabled(false);
        btnDl5ClusterSendTelegram.setEnabled(false);
        Dl5ClusterReconRunner.runAll(this, new Dl5ClusterReconRunner.Listener() {
            @Override public void onSuiteStarted(List<DiLink5TestRunner.TestResult> results) {
                if (mDestroyed) return;
                dl5ClusterLastResults.clear();
                dl5ClusterLastResults.addAll(results);
                for (int i = 0; i < results.size() && i < dl5ClusterRowViews.size(); i++) {
                    bindDl5Row(dl5ClusterRowViews.get(i), results.get(i));
                }
                tvDl5ClusterCounters.setText(getString(R.string.diag_beta_counters_running));
            }
            @Override public void onTestUpdated(int index, DiLink5TestRunner.TestResult result) {
                if (mDestroyed) return;
                if (index < dl5ClusterRowViews.size()) bindDl5Row(dl5ClusterRowViews.get(index), result);
                updateClusterDl5Counters();
            }
            @Override public void onSuiteFinished(List<DiLink5TestRunner.TestResult> results) {
                if (mDestroyed) return;
                btnDl5ClusterRunAll.setEnabled(true);
                if (btnDl5ClusterTestFission != null) btnDl5ClusterTestFission.setEnabled(true);
                btnDl5ClusterCopyReport.setEnabled(true);
                btnDl5ClusterSendTelegram.setEnabled(true);
                updateClusterDl5Counters();
            }
        });
    }

    // ─── v1.2.41 — Fission live test battery (DL5 only) ──────────────────────

    private void confirmAndRunClusterDl5FissionTests() {
        // HARD RULE: DL5 only. The button is hidden on other platforms but
        // double-check here in case of layout overrides.
        if (!Platform.get().isAutoDetectedDiLink5()) {
            Toast.makeText(this, R.string.diag_dl5_cluster_test_fission_only_dl5,
                    Toast.LENGTH_LONG).show();
            return;
        }
        // v1.2.56 — let the user pick which app to target instead of the
        // hard-coded Yandex Maps fallback that skipped F03..F13 for everyone
        // who didn't have that exact package installed.
        pickFissionTargetThenConfirm();
    }

    /**
     * v1.2.56 — enumerate launchable apps, show a single-choice picker,
     * then chain into the confirm dialog with the chosen package.
     */
    private void pickFissionTargetThenConfirm() {
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        main.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        if (infos == null || infos.isEmpty()) {
            Toast.makeText(this, R.string.diag_dl5_cluster_test_fission_pick_empty,
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Dedupe by package + skip our own apk so the user never targets
        // DashCast itself for a destructive resize cycle.
        String selfPkg = getPackageName();
        java.util.Map<String, String> pkgToLabel = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo ri : infos) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(selfPkg)) continue;
            if (pkgToLabel.containsKey(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            pkgToLabel.put(pkg, label == null ? pkg : label.toString());
        }
        if (pkgToLabel.isEmpty()) {
            Toast.makeText(this, R.string.diag_dl5_cluster_test_fission_pick_empty,
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Sort alphabetically by label (case-insensitive) for findability.
        java.util.List<java.util.Map.Entry<String, String>> sorted =
                new java.util.ArrayList<>(pkgToLabel.entrySet());
        java.util.Collections.sort(sorted, (a, b) ->
                a.getValue().compareToIgnoreCase(b.getValue()));
        final String[] pkgs   = new String[sorted.size()];
        final String[] labels = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            pkgs[i]   = sorted.get(i).getKey();
            labels[i] = sorted.get(i).getValue() + "  —  " + sorted.get(i).getKey();
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.diag_dl5_cluster_test_fission_pick_title)
                .setItems(labels, (d, which) -> {
                    if (which < 0 || which >= pkgs.length) return;
                    confirmFissionRun(pkgs[which], sorted.get(which).getValue());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** v1.2.56 — second-step confirm dialog showing the chosen target. */
    private void confirmFissionRun(String targetPkg, String targetLabel) {
        String displayName = targetLabel + " (" + targetPkg + ")";
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.diag_dl5_cluster_test_fission_confirm_title)
                .setMessage(getString(R.string.diag_dl5_cluster_test_fission_confirm_msg,
                        displayName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.diag_dl5_cluster_test_fission_confirm_ok,
                        (d, w) -> runClusterDl5FissionTests(targetPkg, targetLabel))
                .show();
    }

    private void runClusterDl5FissionTests(String targetPkg, String targetLabel) {
        // Replace row list with the F-catalog and flip the renderer flag so
        // Copy / Telegram use renderFissionReport.
        prepareClusterDl5TestRowsFor(Dl5ClusterReconRunner.fissionCatalog());
        dl5ClusterShowingFission = true;
        btnDl5ClusterRunAll.setEnabled(false);
        btnDl5ClusterTestFission.setEnabled(false);
        btnDl5ClusterCopyReport.setEnabled(false);
        btnDl5ClusterSendTelegram.setEnabled(false);
        Dl5ClusterReconRunner.runFission(this, targetPkg, targetLabel, new Dl5ClusterReconRunner.Listener() {
            @Override public void onSuiteStarted(List<DiLink5TestRunner.TestResult> results) {
                if (mDestroyed) return;
                dl5ClusterLastResults.clear();
                dl5ClusterLastResults.addAll(results);
                for (int i = 0; i < results.size() && i < dl5ClusterRowViews.size(); i++) {
                    bindDl5Row(dl5ClusterRowViews.get(i), results.get(i));
                }
                tvDl5ClusterCounters.setText(getString(R.string.diag_beta_counters_running));
            }
            @Override public void onTestUpdated(int index, DiLink5TestRunner.TestResult result) {
                if (mDestroyed) return;
                if (index < dl5ClusterRowViews.size()) bindDl5Row(dl5ClusterRowViews.get(index), result);
                updateClusterDl5Counters();
            }
            @Override public void onSuiteFinished(List<DiLink5TestRunner.TestResult> results) {
                if (mDestroyed) return;
                btnDl5ClusterRunAll.setEnabled(true);
                btnDl5ClusterTestFission.setEnabled(true);
                btnDl5ClusterCopyReport.setEnabled(true);
                btnDl5ClusterSendTelegram.setEnabled(true);
                updateClusterDl5Counters();
            }
            // v1.2.48 — interactive Yes/No prompt for the fission runner.
            // Called on the UI thread; the runner thread blocks until the
            // callback is invoked (or its 180 s safety timeout elapses, in
            // which case the result is recorded as NO).
            @Override public void onPromptYesNo(String title, String message,
                                                java.util.function.Consumer<Boolean> callback) {
                if (mDestroyed) { callback.accept(false); return; }
                new androidx.appcompat.app.AlertDialog.Builder(DiagActivity.this)
                        .setTitle(title)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton(R.string.diag_dl5_fission_prompt_yes,
                                (d, w) -> callback.accept(true))
                        .setNegativeButton(R.string.diag_dl5_fission_prompt_no,
                                (d, w) -> callback.accept(false))
                        .show();
            }
        });
    }

    private void updateClusterDl5Counters() {
        int pass = 0, fail = 0, skip = 0, warn = 0;
        for (DiLink5TestRunner.TestResult r : dl5ClusterLastResults) {
            switch (r.status) {
                case PASS:    pass++; break;
                case FAIL:    fail++; break;
                case SKIPPED: skip++; break;
                case WARN:    warn++; break;
                default: break;
            }
        }
        tvDl5ClusterCounters.setText(getString(R.string.diag_dl5_counters_fmt, pass, fail, warn, skip));
    }

    private void copyClusterDl5Report() {
        if (dl5ClusterLastResults.isEmpty()) {
            Toast.makeText(this, R.string.diag_dl5_cluster_toast_no_results, Toast.LENGTH_SHORT).show();
            return;
        }
        String report = dl5ClusterShowingFission
                ? Dl5ClusterReconRunner.renderFissionReport(dl5ClusterLastResults)
                : Dl5ClusterReconRunner.renderReport(dl5ClusterLastResults);
        AppLogger.i("DiagActivity", "DL5 Cluster report:\n" + report);
        AppLogger.shareWithReport(this, report);
        Toast.makeText(this,
                getString(R.string.diag_dl5_cluster_toast_report_copied, dl5ClusterLastResults.size()),
                Toast.LENGTH_SHORT).show();
    }

    private void sendClusterDl5ToTelegram() {
        if (dl5ClusterLastResults.isEmpty()) {
            Toast.makeText(this, R.string.diag_dl5_cluster_toast_no_results, Toast.LENGTH_SHORT).show();
            return;
        }
        String report = dl5ClusterShowingFission
                ? Dl5ClusterReconRunner.renderFissionReport(dl5ClusterLastResults)
                : Dl5ClusterReconRunner.renderReport(dl5ClusterLastResults);
        AppLogger.i("DiagActivity", "DL5 Cluster report (Telegram):\n" + report);
        AppLogger.shareReportToTelegram(this, report);
    }

    // ─── DiLink 2 recon panel (build 185) ───────────────────────────────────

    private void bindDl2Panel() {
        tvDl2HeaderSubtitle = panelDl2.findViewById(R.id.tv_dl2_header_subtitle);
        tvDl2SignaturePill  = panelDl2.findViewById(R.id.tv_dl2_signature_pill);
        tvDl2Counters       = panelDl2.findViewById(R.id.tv_dl2_counters);
        btnDl2RunAll        = panelDl2.findViewById(R.id.btn_dl2_run_all);
        btnDl2CopyReport    = panelDl2.findViewById(R.id.btn_dl2_copy_report);
        llDl2TestList       = panelDl2.findViewById(R.id.ll_dl2_test_list);

        String product = android.os.Build.PRODUCT == null ? "?" : android.os.Build.PRODUCT;
        String brand   = android.os.Build.BRAND   == null ? "?" : android.os.Build.BRAND;
        tvDl2HeaderSubtitle.setText(getString(
                R.string.diag_dl2_header_subtitle_fmt, product, brand, android.os.Build.VERSION.SDK_INT));

        boolean dl2Sig = "alps".equalsIgnoreCase(brand)
                && product.toLowerCase().contains("k65v1");
        tvDl2SignaturePill.setText(dl2Sig
                ? R.string.diag_dl2_pill_detected
                : R.string.diag_dl2_pill_other);

        btnDl2RunAll.setOnClickListener(v -> runDl2AllTests());
        btnDl2CopyReport.setOnClickListener(v -> copyDl2Report());
        btnDl2CopyReport.setEnabled(false);
    }

    /** v1.2.46 — lazy : inflate the DL2 catalog rows on first panel show. */
    private void prepareDl2TestRowsIfNeeded() {
        if (dl2RowsPrepared) return;
        dl2RowsPrepared = true;
        prepareDl2TestRows();
    }

    private void prepareDl2TestRows() {
        dl2RowViews.clear();
        dl2LastResults.clear();
        llDl2TestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DiLink2TestRunner.TestDef def : DiLink2TestRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_beta_test, llDl2TestList, false);
            DiLink2TestRunner.TestResult r = new DiLink2TestRunner.TestResult(def);
            r.status = DiLink2TestRunner.Status.PENDING;
            bindDl2Row(row, r);
            llDl2TestList.addView(row);
            dl2RowViews.add(row);
            dl2LastResults.add(r);
        }
    }

    private void bindDl2Row(View row, DiLink2TestRunner.TestResult r) {
        TextView status = row.findViewById(R.id.tv_test_status);
        TextView id     = row.findViewById(R.id.tv_test_id);
        TextView title  = row.findViewById(R.id.tv_test_title);
        TextView desc   = row.findViewById(R.id.tv_test_description);
        TextView msg    = row.findViewById(R.id.tv_test_message);
        TextView elap   = row.findViewById(R.id.tv_test_elapsed);

        id.setText(r.def.id);
        title.setText(r.def.title);
        desc.setText(r.def.description);

        String glyph; int color;
        switch (r.status) {
            case PASS:    glyph = "\u2713"; color = 0xFF4CAF50; break;
            case FAIL:    glyph = "\u2717"; color = 0xFFE53935; break;
            case WARN:    glyph = "!";      color = 0xFFFFB300; break;
            case SKIPPED: glyph = "\u2298"; color = 0xFF9E9E9E; break;
            case RUNNING: glyph = "\u2026"; color = 0xFFFFB300; break;
            default:      glyph = "\u00b7"; color = 0xFF607D8B; break;
        }
        status.setText(glyph);
        status.setTextColor(color);

        elap.setText(r.elapsedMs > 0 ? (r.elapsedMs + " ms") : "");

        if (r.message != null && !r.message.isEmpty()) {
            msg.setVisibility(View.VISIBLE);
            msg.setText(r.message);
            int textColor;
            switch (r.status) {
                case FAIL: textColor = 0xFFE53935; break;
                case PASS: textColor = 0xFF4CAF50; break;
                case WARN: textColor = 0xFFFFB300; break;
                default:   textColor = 0xFF9E9E9E; break;
            }
            msg.setTextColor(textColor);
        } else {
            msg.setVisibility(View.GONE);
        }
    }

    private void runDl2AllTests() {
        btnDl2RunAll.setEnabled(false);
        btnDl2CopyReport.setEnabled(false);
        DiLink2TestRunner.runAll(this, new DiLink2TestRunner.Listener() {
            @Override public void onSuiteStarted(List<DiLink2TestRunner.TestResult> results) {
                if (mDestroyed) return;
                dl2LastResults.clear();
                dl2LastResults.addAll(results);
                for (int i = 0; i < results.size() && i < dl2RowViews.size(); i++) {
                    bindDl2Row(dl2RowViews.get(i), results.get(i));
                }
                tvDl2Counters.setText(getString(R.string.diag_beta_counters_running));
            }
            @Override public void onTestUpdated(int index, DiLink2TestRunner.TestResult result) {
                if (mDestroyed) return;
                if (index < dl2RowViews.size()) bindDl2Row(dl2RowViews.get(index), result);
                updateDl2Counters();
            }
            @Override public void onSuiteFinished(List<DiLink2TestRunner.TestResult> results) {
                if (mDestroyed) return;
                btnDl2RunAll.setEnabled(true);
                btnDl2CopyReport.setEnabled(true);
                updateDl2Counters();
            }
        });
    }

    private void updateDl2Counters() {
        int pass = 0, fail = 0, skip = 0, warn = 0;
        for (DiLink2TestRunner.TestResult r : dl2LastResults) {
            switch (r.status) {
                case PASS:    pass++; break;
                case FAIL:    fail++; break;
                case SKIPPED: skip++; break;
                case WARN:    warn++; break;
                default: break;
            }
        }
        tvDl2Counters.setText(getString(R.string.diag_dl2_counters_fmt, pass, fail, warn, skip));
    }

    private void copyDl2Report() {
        String report = DiLink2TestRunner.buildReport(this, dl2LastResults);
        AppLogger.i("DiagActivity", "DiLink 2 report:\n" + report);
        AppLogger.shareWithReport(this, report);
    }

    // ─── DiLink 4 diagnostic panel (v1.2.28) ────────────────────────────────

    private void bindDl4Panel() {
        tvDl4HeaderSubtitle = panelDl4.findViewById(R.id.tv_dl4_header_subtitle);
        tvDl4SignaturePill  = panelDl4.findViewById(R.id.tv_dl4_signature_pill);
        tvDl4Counters       = panelDl4.findViewById(R.id.tv_dl4_counters);
        btnDl4RunAll        = panelDl4.findViewById(R.id.btn_dl4_run_all);
        btnDl4CopyReport    = panelDl4.findViewById(R.id.btn_dl4_copy_report);
        llDl4TestList       = panelDl4.findViewById(R.id.ll_dl4_test_list);

        String product = android.os.Build.PRODUCT == null ? "?" : android.os.Build.PRODUCT;
        String brand   = android.os.Build.BRAND   == null ? "?" : android.os.Build.BRAND;
        tvDl4HeaderSubtitle.setText(getString(
                R.string.diag_dl4_header_subtitle_fmt, product, brand, android.os.Build.VERSION.SDK_INT));

        tvDl4SignaturePill.setText(Platform.get().isAutoDetectedDiLink4()
                ? R.string.diag_dl4_pill_detected
                : R.string.diag_dl4_pill_other);

        btnDl4RunAll.setOnClickListener(v -> runDl4AllTests());
        btnDl4CopyReport.setOnClickListener(v -> copyDl4Report());
        btnDl4CopyReport.setEnabled(false);
    }

    /** v1.2.46 — lazy : inflate the DL4 catalog rows on first panel show. */
    private void prepareDl4TestRowsIfNeeded() {
        if (dl4RowsPrepared) return;
        dl4RowsPrepared = true;
        prepareDl4TestRows();
    }

    private void prepareDl4TestRows() {
        dl4RowViews.clear();
        dl4LastResults.clear();
        llDl4TestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DiLink4TestRunner.TestDef def : DiLink4TestRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_beta_test, llDl4TestList, false);
            DiLink4TestRunner.TestResult r = new DiLink4TestRunner.TestResult(def);
            r.status = DiLink4TestRunner.Status.PENDING;
            bindDl4Row(row, r);
            llDl4TestList.addView(row);
            dl4RowViews.add(row);
            dl4LastResults.add(r);
        }
    }

    private void bindDl4Row(View row, DiLink4TestRunner.TestResult r) {
        TextView status = row.findViewById(R.id.tv_test_status);
        TextView id     = row.findViewById(R.id.tv_test_id);
        TextView title  = row.findViewById(R.id.tv_test_title);
        TextView desc   = row.findViewById(R.id.tv_test_description);
        TextView msg    = row.findViewById(R.id.tv_test_message);
        TextView elap   = row.findViewById(R.id.tv_test_elapsed);

        id.setText(r.def.id);
        title.setText(r.def.title);
        desc.setText(r.def.description);

        String glyph; int color;
        switch (r.status) {
            case PASS:    glyph = "\u2713"; color = 0xFF4CAF50; break;
            case FAIL:    glyph = "\u2717"; color = 0xFFE53935; break;
            case WARN:    glyph = "!";      color = 0xFFFFB300; break;
            case SKIPPED: glyph = "\u2298"; color = 0xFF9E9E9E; break;
            case RUNNING: glyph = "\u2026"; color = 0xFFFFB300; break;
            default:      glyph = "\u00b7"; color = 0xFF607D8B; break;
        }
        status.setText(glyph);
        status.setTextColor(color);

        elap.setText(r.elapsedMs > 0 ? (r.elapsedMs + " ms") : "");

        if (r.message != null && !r.message.isEmpty()) {
            msg.setVisibility(View.VISIBLE);
            msg.setText(r.message);
            int textColor;
            switch (r.status) {
                case FAIL: textColor = 0xFFE53935; break;
                case PASS: textColor = 0xFF4CAF50; break;
                case WARN: textColor = 0xFFFFB300; break;
                default:   textColor = 0xFF9E9E9E; break;
            }
            msg.setTextColor(textColor);
        } else {
            msg.setVisibility(View.GONE);
        }
    }

    private void runDl4AllTests() {
        btnDl4RunAll.setEnabled(false);
        btnDl4CopyReport.setEnabled(false);
        DiLink4TestRunner.runAll(this, new DiLink4TestRunner.Listener() {
            @Override public void onSuiteStarted(List<DiLink4TestRunner.TestResult> results) {
                if (mDestroyed) return;
                dl4LastResults.clear();
                dl4LastResults.addAll(results);
                for (int i = 0; i < results.size() && i < dl4RowViews.size(); i++) {
                    bindDl4Row(dl4RowViews.get(i), results.get(i));
                }
                tvDl4Counters.setText(getString(R.string.diag_beta_counters_running));
            }
            @Override public void onTestUpdated(int index, DiLink4TestRunner.TestResult result) {
                if (mDestroyed) return;
                if (index < dl4RowViews.size()) bindDl4Row(dl4RowViews.get(index), result);
                updateDl4Counters();
            }
            @Override public void onSuiteFinished(List<DiLink4TestRunner.TestResult> results) {
                if (mDestroyed) return;
                btnDl4RunAll.setEnabled(true);
                btnDl4CopyReport.setEnabled(true);
                updateDl4Counters();
            }
        });
    }

    private void updateDl4Counters() {
        int pass = 0, fail = 0, skip = 0, warn = 0;
        for (DiLink4TestRunner.TestResult r : dl4LastResults) {
            switch (r.status) {
                case PASS:    pass++; break;
                case FAIL:    fail++; break;
                case SKIPPED: skip++; break;
                case WARN:    warn++; break;
                default: break;
            }
        }
        tvDl4Counters.setText(getString(R.string.diag_dl4_counters_fmt, pass, fail, warn, skip));
    }

    private void copyDl4Report() {
        String report = DiLink4TestRunner.buildReport(this, dl4LastResults);
        AppLogger.i("DiagActivity", "DiLink 4 report:\n" + report);
        AppLogger.shareWithReport(this, report);
    }

    // ─── Mirror diag panel (build 193) ──────────────────────────────────────

    private TextView       tvMirrorHeaderSubtitle;
    private TextView       tvMirrorModePill;
    private TextView       tvMirrorCounters;
    private MaterialButton btnMirrorRunAll;
    private MaterialButton btnMirrorSendLog;
    private LinearLayout   llMirrorTestList;
    private final List<View> mirrorRowViews = new ArrayList<>();
    private final List<com.byd.dashcast.mirror.MirrorTestRunner.TestResult> mirrorLastResults = new ArrayList<>();

    private void bindMirrorPanel() {
        tvMirrorHeaderSubtitle = panelMirror.findViewById(R.id.tv_mirror_header_subtitle);
        tvMirrorModePill       = panelMirror.findViewById(R.id.tv_mirror_mode_pill);
        tvMirrorCounters       = panelMirror.findViewById(R.id.tv_mirror_counters);
        btnMirrorRunAll        = panelMirror.findViewById(R.id.btn_mirror_run_all);
        btnMirrorSendLog       = panelMirror.findViewById(R.id.btn_mirror_send_log);
        llMirrorTestList       = panelMirror.findViewById(R.id.ll_mirror_test_list);

        Platform p = Platform.get();
        String prod = p.rawProductName();
        if (prod == null || prod.isEmpty()) prod = "?";
        tvMirrorHeaderSubtitle.setText(getString(
                R.string.diag_mirror_header_subtitle_fmt, prod, p.androidApi()));

        boolean dl5 = p.isAutoDetectedDiLink5();
        tvMirrorModePill.setText(dl5
                ? R.string.diag_mirror_pill_dl5
                : R.string.diag_mirror_pill_other);

        btnMirrorRunAll.setOnClickListener(v -> runMirrorAllTests());
        btnMirrorSendLog.setOnClickListener(v -> sendMirrorLog());
        btnMirrorSendLog.setEnabled(false);
    }

    /** v1.2.46 — lazy : inflate the Mirror catalog rows on first panel show. */
    private void prepareMirrorTestRowsIfNeeded() {
        if (mirrorRowsPrepared) return;
        mirrorRowsPrepared = true;
        prepareMirrorTestRows();
    }

    private void prepareMirrorTestRows() {
        mirrorRowViews.clear();
        mirrorLastResults.clear();
        llMirrorTestList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (com.byd.dashcast.mirror.MirrorTestRunner.TestDef def
                : com.byd.dashcast.mirror.MirrorTestRunner.catalog()) {
            View row = inflater.inflate(R.layout.item_beta_test, llMirrorTestList, false);
            com.byd.dashcast.mirror.MirrorTestRunner.TestResult r =
                    new com.byd.dashcast.mirror.MirrorTestRunner.TestResult(def);
            r.status = com.byd.dashcast.mirror.MirrorTestRunner.Status.PENDING;
            bindMirrorRow(row, r);
            llMirrorTestList.addView(row);
            mirrorRowViews.add(row);
            mirrorLastResults.add(r);
        }
    }

    private void bindMirrorRow(View row, com.byd.dashcast.mirror.MirrorTestRunner.TestResult r) {
        TextView status = row.findViewById(R.id.tv_test_status);
        TextView id     = row.findViewById(R.id.tv_test_id);
        TextView title  = row.findViewById(R.id.tv_test_title);
        TextView desc   = row.findViewById(R.id.tv_test_description);
        TextView msg    = row.findViewById(R.id.tv_test_message);
        TextView elap   = row.findViewById(R.id.tv_test_elapsed);

        id.setText(r.def.id);
        title.setText(r.def.title);
        desc.setText(r.def.description);

        String glyph; int color;
        switch (r.status) {
            case PASS:    glyph = "\u2713"; color = 0xFF4CAF50; break;
            case FAIL:    glyph = "\u2717"; color = 0xFFE53935; break;
            case WARN:    glyph = "!";      color = 0xFFFFB300; break;
            case SKIPPED: glyph = "\u2298"; color = 0xFF9E9E9E; break;
            case RUNNING: glyph = "\u2026"; color = 0xFFFFB300; break;
            default:      glyph = "\u00b7"; color = 0xFF607D8B; break;
        }
        status.setText(glyph);
        status.setTextColor(color);

        elap.setText(r.elapsedMs > 0 ? (r.elapsedMs + " ms") : "");

        if (r.message != null && !r.message.isEmpty()) {
            msg.setVisibility(View.VISIBLE);
            msg.setText(r.message);
            int textColor;
            switch (r.status) {
                case FAIL: textColor = 0xFFE53935; break;
                case PASS: textColor = 0xFF4CAF50; break;
                case WARN: textColor = 0xFFFFB300; break;
                default:   textColor = 0xFF9E9E9E; break;
            }
            msg.setTextColor(textColor);
        } else {
            msg.setVisibility(View.GONE);
        }
    }

    private void runMirrorAllTests() {
        btnMirrorRunAll.setEnabled(false);
        btnMirrorSendLog.setEnabled(false);
        com.byd.dashcast.mirror.MirrorTestRunner.runAll(this,
                new com.byd.dashcast.mirror.MirrorTestRunner.Listener() {
            @Override public void onSuiteStarted(
                    List<com.byd.dashcast.mirror.MirrorTestRunner.TestResult> results) {
                if (mDestroyed) return;
                mirrorLastResults.clear();
                mirrorLastResults.addAll(results);
                for (int i = 0; i < results.size() && i < mirrorRowViews.size(); i++) {
                    bindMirrorRow(mirrorRowViews.get(i), results.get(i));
                }
                tvMirrorCounters.setText(getString(R.string.diag_beta_counters_running));
            }
            @Override public void onTestUpdated(int index,
                    com.byd.dashcast.mirror.MirrorTestRunner.TestResult result) {
                if (mDestroyed) return;
                if (index < mirrorRowViews.size()) bindMirrorRow(mirrorRowViews.get(index), result);
                updateMirrorCounters();
            }
            @Override public void onSuiteFinished(
                    List<com.byd.dashcast.mirror.MirrorTestRunner.TestResult> results) {
                if (mDestroyed) return;
                btnMirrorRunAll.setEnabled(true);
                btnMirrorSendLog.setEnabled(true);
                updateMirrorCounters();
            }
        });
    }

    private void updateMirrorCounters() {
        int pass = 0, fail = 0, skip = 0, warn = 0;
        for (com.byd.dashcast.mirror.MirrorTestRunner.TestResult r : mirrorLastResults) {
            switch (r.status) {
                case PASS:    pass++; break;
                case FAIL:    fail++; break;
                case SKIPPED: skip++; break;
                case WARN:    warn++; break;
                default: break;
            }
        }
        tvMirrorCounters.setText(getString(R.string.diag_dl5_counters_fmt, pass, fail, warn, skip));
    }

    private void sendMirrorLog() {
        String report = com.byd.dashcast.mirror.MirrorTestRunner.buildReport(this, mirrorLastResults);
        AppLogger.i("DiagActivity", "Mirror diag report:\n" + report);
        AppLogger.shareWithReport(this, report);
    }

    // ─── RE Sniffer panel (restored in build 181) ───────────────────────────
    // Captures continuous logcat + periodic dumpsys snapshots into a single
    // BYD_RE_Sniffer_*.txt file. Background processes use setsid so they
    // survive Activity destruction (rotation, app pause). State restored from
    // SharedPreferences on each onCreate.

    private static final String RE_SNIFFER_TAG    = ".re_sniffer_run";
    private static final String RE_SNIFFER_PIDS   = ".re_sniffer_pids";
    private static final String RE_SNIFFER_PREFIX = "BYD_RE_Sniffer_";
    private static final String PREF_SNIFFER      = "byd_diag_prefs";
    private static final String PREF_SNIFFER_PATH = "re_sniffer_file_path";

    private TextView       tvSnifferStatusPill;
    private TextView       tvSnifferStatus;
    private MaterialButton btnSnifferStart;
    private MaterialButton btnSnifferStop;
    private MaterialButton btnSnifferSnapshot;
    private MaterialButton btnSnifferExport;
    private MaterialButton btnSnifferCleanup;
    private volatile java.io.File   mSnifferFile;

    private void bindSnifferPanel() {
        tvSnifferStatusPill = panelSniffer.findViewById(R.id.tv_sniffer_status_pill);
        tvSnifferStatus     = panelSniffer.findViewById(R.id.tv_sniffer_status);
        btnSnifferStart     = panelSniffer.findViewById(R.id.btn_sniffer_start);
        btnSnifferStop      = panelSniffer.findViewById(R.id.btn_sniffer_stop);
        btnSnifferSnapshot  = panelSniffer.findViewById(R.id.btn_sniffer_snapshot);
        btnSnifferExport    = panelSniffer.findViewById(R.id.btn_sniffer_export);
        btnSnifferCleanup   = panelSniffer.findViewById(R.id.btn_sniffer_cleanup);

        btnSnifferStart.setOnClickListener(v -> startSniffer());
        btnSnifferStop.setOnClickListener(v -> stopSniffer());
        btnSnifferSnapshot.setOnClickListener(v -> snapshotSniffer());
        btnSnifferExport.setOnClickListener(v -> exportSniffer());
        btnSnifferCleanup.setOnClickListener(v -> cleanupSnifferFiles());
    }

    // ─── ADAS panel ─────────────────────────────────────────────────────────
    // Wraps clusterdebug.SecondActivity codes 12 (show) / 13 (hide) — confirmed
    // generic across DiLink platforms (DL3/Di4/DL5/DL6) in v1.6.1.4. Service-name
    // differs: Auto_container on DL3/Di4, auto_container on DL5+.

    private TextView tvAdasResult;
    private TextView tvAdasServicePill;

    private void bindAdasPanel() {
        tvAdasResult      = panelAdas.findViewById(R.id.tv_adas_result);
        tvAdasServicePill = panelAdas.findViewById(R.id.tv_adas_service_pill);
        MaterialButton btnShow = panelAdas.findViewById(R.id.btn_adas_show);
        MaterialButton btnHide = panelAdas.findViewById(R.id.btn_adas_hide);

        String svc = autoContainerSvcName();
        if (tvAdasServicePill != null) tvAdasServicePill.setText(svc);

        btnShow.setOnClickListener(v -> sendAdasCmd(12));
        btnHide.setOnClickListener(v -> sendAdasCmd(13));
    }

    private String autoContainerSvcName() {
        try {
            return com.byd.dashcast.platform.Platform.get().isDiLink5(this)
                    ? "auto_container" : "AutoContainer";
        } catch (Throwable t) {
            return "AutoContainer";
        }
    }

    private void sendAdasCmd(final int code) {
        final String svc = autoContainerSvcName();
        final String cmd = "service call " + svc + " 2 i32 1000 i32 " + code + " s16 \"\" 2>&1";
        if (tvAdasResult != null) tvAdasResult.setText(getString(R.string.diag_adas_sending_fmt, cmd));
        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(final String report) {
                safeRunOnUiThread(() -> {
                    if (tvAdasResult != null)
                        tvAdasResult.setText("$ " + cmd + "\n" + report);
                });
            }
            @Override public void onError(final String error) {
                safeRunOnUiThread(() -> {
                    if (tvAdasResult != null)
                        tvAdasResult.setText(getString(R.string.diag_adas_error_fmt, cmd, error));
                });
            }
        });
    }

    private void setSnifferUiActive(boolean active, String detail) {
        if (mDestroyed) return;
        tvSnifferStatusPill.setText(active ? R.string.diag_sniffer_pill_active : R.string.diag_sniffer_pill_inactive);
        tvSnifferStatusPill.setTextColor(active ? 0xFF69F0AE : 0xFFFF8A80);
        if (detail != null) tvSnifferStatus.setText(detail);
        btnSnifferStart.setEnabled(!active);
        btnSnifferStop.setEnabled(active);
        btnSnifferSnapshot.setEnabled(active);
        btnSnifferExport.setEnabled(mSnifferFile != null && mSnifferFile.exists() && mSnifferFile.length() > 0);
    }

    private java.io.File buildSnifferFile() {
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new java.io.File(dir, RE_SNIFFER_PREFIX + ts + ".txt");
    }

    /**
     * Checks SharedPreferences for a saved sniffer path and verifies the
     * .re_sniffer_run tag file still exists (meaning bg processes are still alive).
     */
    private void restoreSnifferState() {
        String saved = getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE)
                .getString(PREF_SNIFFER_PATH, null);
        if (saved == null) { setSnifferUiActive(false, null); return; }

        java.io.File f = new java.io.File(saved);
        if (!f.exists() || f.length() == 0) {
            getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                    .remove(PREF_SNIFFER_PATH).apply();
            setSnifferUiActive(false, null);
            return;
        }

        mSnifferFile = f;
        AdbLocalClient.executeShellWithResult(this,
                "[ -f /data/local/tmp/" + RE_SNIFFER_TAG + " ] && echo ACTIVE || echo STOPPED",
                new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                final boolean active = out.trim().equals("ACTIVE");
                safeRunOnUiThread(() -> {
                    if (active) {
                        setSnifferUiActive(true, getString(R.string.diag_sniffer_active_detail_fmt,
                                f.getName(), (int) (f.length() / 1024)));
                    } else {
                        getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                                .remove(PREF_SNIFFER_PATH).apply();
                        // File still exists on disk → keep export enabled
                        setSnifferUiActive(false, getString(R.string.diag_sniffer_previous_stopped_fmt,
                                f.getName(), (int) (f.length() / 1024)));
                    }
                });
            }
            @Override public void onError(String err) {
                safeRunOnUiThread(() -> setSnifferUiActive(false,
                        getString(R.string.diag_sniffer_previous_no_adb_fmt,
                                f.getName(), (int) (f.length() / 1024))));
            }
        });
    }

    private void startSniffer() {
        killSnifferProcesses();

        mSnifferFile = buildSnifferFile();
        getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                .putString(PREF_SNIFFER_PATH, mSnifferFile.getAbsolutePath()).apply();
        final String p  = mSnifferFile.getAbsolutePath();
        final String pf = "/data/local/tmp/" + RE_SNIFFER_PIDS;
        AppLogger.i("RESniffer", "Starting → " + p);

        setSnifferUiActive(false, getString(R.string.diag_sniffer_initializing));
        btnSnifferStart.setEnabled(false);

        String headerCmd =
            "logcat -c 2>/dev/null"
            + " ; touch /data/local/tmp/" + RE_SNIFFER_TAG
            + " ; echo === BYD RE SNIFFER === > " + p
            + " ; date >> " + p
            + " ; getprop ro.product.model >> " + p
            + " ; getprop ro.build.fingerprint >> " + p
            + " ; echo --- DISPLAYS INITIAL --- >> " + p
            + " ; dumpsys display 2>/dev/null >> " + p
            + " ; echo --- SURFACEFLINGER INITIAL --- >> " + p
            + " ; dumpsys SurfaceFlinger 2>/dev/null >> " + p
            + " ; echo --- PROCESSUS INITIAL --- >> " + p
            + " ; ps -A 2>/dev/null >> " + p
            + " ; echo === LIVE CAPTURE START === >> " + p;

        AdbLocalClient.executeShellWithResult(this, headerCmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                String snapLoop =
                    "while [ -f /data/local/tmp/" + RE_SNIFFER_TAG + " ]; do sleep 10;"
                    + " echo >> " + p + ";"
                    + " printf \"=== SNAP %s ===\\n\" $(date +%H:%M:%S) >> " + p + ";"
                    + " dumpsys display 2>/dev/null"
                    + "   | grep -E \"mDisplayId|mName|mState|fission|virtual|cluster|layerStack\""
                    + "   >> " + p + ";"
                    + " dumpsys SurfaceFlinger 2>/dev/null"
                    + "   | grep -iE \"display|fission|layer|cluster|mirror|virtual|qt\""
                    + "   | head -30 >> " + p + ";"
                    + " ps -A 2>/dev/null"
                    + "   | grep -iE \"byd|xdja|daemon|dilink|qt|cluster|app_process\""
                    + "   >> " + p + ";"
                    + " done";

                String bgCmd =
                    "echo > " + pf
                    + " ; setsid sh -c 'logcat -v threadtime >> " + p + " 2>&1'"
                    + "   & echo $! >> " + pf
                    + " ; setsid sh -c '" + snapLoop + "'"
                    + "   & echo $! >> " + pf
                    + " ; setsid sh -c 'logcat -b events -v time >> " + p + " 2>&1'"
                    + "   & echo $! >> " + pf;

                AdbLocalClient.executeShell(DiagActivity.this, bgCmd);

                safeRunOnUiThread(() -> {
                    setSnifferUiActive(true, getString(R.string.diag_sniffer_active_fmt,
                            mSnifferFile.getName()));
                    Toast.makeText(DiagActivity.this,
                            getString(R.string.diag_sniffer_toast_started_fmt, mSnifferFile.getName()),
                            Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onError(String err) {
                safeRunOnUiThread(() -> {
                    setSnifferUiActive(false, getString(R.string.diag_sniffer_init_fail_fmt, err));
                    AppLogger.e("RESniffer", "init failed: " + err);
                });
            }
        });
    }

    private void killSnifferProcesses() {
        String pidFile = "/data/local/tmp/" + RE_SNIFFER_PIDS;
        String killCmd =
            "rm -f /data/local/tmp/" + RE_SNIFFER_TAG
            + " ; if [ -f " + pidFile + " ]; then"
            + "   while IFS= read -r pid; do"
            + "     [ -n \"$pid\" ] && kill -9 \"$pid\" 2>/dev/null; done < " + pidFile + ";"
            + "   rm -f " + pidFile + ";"
            + " fi"
            + " ; pkill -f " + RE_SNIFFER_PREFIX + " 2>/dev/null; true";
        AdbLocalClient.executeShell(this, killCmd);
    }

    private void stopSniffer() {
        killSnifferProcesses();
        getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                .remove(PREF_SNIFFER_PATH).apply();
        final String fileName = mSnifferFile != null ? mSnifferFile.getName() : getString(R.string.diag_sniffer_none);
        if (mSnifferFile != null) {
            AdbLocalClient.executeShell(this,
                    "echo '[RE Sniffer] Stopped.' >> " + mSnifferFile.getAbsolutePath());
        }
        safeRunOnUiThread(() -> {
            setSnifferUiActive(false, getString(R.string.diag_sniffer_stopped_fmt, fileName));
            Toast.makeText(this, R.string.diag_sniffer_toast_stopped, Toast.LENGTH_SHORT).show();
        });
    }

    private void snapshotSniffer() {
        if (mSnifferFile == null) {
            Toast.makeText(this, R.string.diag_sniffer_toast_need_start, Toast.LENGTH_SHORT).show();
            return;
        }
        final String p = mSnifferFile.getAbsolutePath();
        String cmd =
            "echo '' >> " + p
            + " && echo '=== SNAPSHOT MANUEL '$(date +%H:%M:%S)' ===' >> " + p
            + " && echo '--- DISPLAYS ---' >> " + p
            + " && dumpsys display 2>/dev/null >> " + p
            + " && echo '--- WINDOWS ---' >> " + p
            + " && dumpsys window 2>/dev/null >> " + p
            + " && echo '--- SURFACEFLINGER ---' >> " + p
            + " && dumpsys SurfaceFlinger 2>/dev/null >> " + p
            + " && echo '--- PROCESSUS ---' >> " + p
            + " && ps -A >> " + p
            + " && echo '--- BROADCASTS ---' >> " + p
            + " && dumpsys activity broadcasts history 2>/dev/null >> " + p;
        AdbLocalClient.executeShell(this, cmd);
        Toast.makeText(this, R.string.diag_sniffer_toast_snapshot_added, Toast.LENGTH_SHORT).show();
    }

    private void exportSniffer() {
        if (mSnifferFile == null) {
            String saved = getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE)
                    .getString(PREF_SNIFFER_PATH, null);
            if (saved != null) mSnifferFile = new java.io.File(saved);
        }
        java.io.File logFile = mSnifferFile;
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            Toast.makeText(this, R.string.diag_sniffer_toast_no_file, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", logFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, logFile.getName());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.setClipData(android.content.ClipData.newRawUri("", uri));
            Intent chooser = Intent.createChooser(shareIntent, getString(R.string.diag_sniffer_chooser_title));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            AppLogger.e("RESniffer", "Export erreur", e);
            Toast.makeText(this, getString(R.string.diag_sniffer_toast_export_error_fmt, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Restored from pre-redesign DiagActivity (pre v0.9.88 wipe). Deletes all
     * DashCast-generated files in the app's external/internal storage
     * (byd_log_*.log, byd_report_*.txt, BYD_RE_Sniffer_*.txt, cluster_live.png)
     * via {@link AppLogger#cleanupFiles(Context)}. Refuses to run while a
     * capture is active to avoid deleting the file the sniffer is writing to.
     * Field report (23/05/2026): app footprint can grow > 500 MB on long-lived
     * installs (multiple sniffer captures accumulating).
     */
    private void cleanupSnifferFiles() {
        // Active capture would be writing to a file we're about to delete.
        // Snapshot button is enabled iff capture is active → cheap proxy probe.
        if (btnSnifferSnapshot != null && btnSnifferSnapshot.isEnabled()) {
            Toast.makeText(this,
                    R.string.diag_sniffer_cleanup_busy,
                    Toast.LENGTH_LONG).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.diag_sniffer_cleanup_dialog_title)
                .setMessage(R.string.diag_sniffer_cleanup_dialog_msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.diag_sniffer_cleanup_dialog_confirm, (d, w) -> doCleanupSnifferFiles())
                .show();
    }

    private void doCleanupSnifferFiles() {
        btnSnifferCleanup.setEnabled(false);
        new Thread(() -> {
            int deleted = AppLogger.cleanupFiles(DiagActivity.this);
            long usedBytes = 0;
            java.io.File extDir = getExternalFilesDir(null);
            if (extDir != null && extDir.exists()) {
                java.io.File[] files = extDir.listFiles();
                if (files != null) for (java.io.File f : files) usedBytes += f.length();
            }
            java.io.File extCache = getExternalCacheDir();
            if (extCache != null && extCache.exists()) {
                java.io.File[] files = extCache.listFiles();
                if (files != null) for (java.io.File f : files) usedBytes += f.length();
            }
            final int finalDeleted = deleted;
            final String sizeStr = usedBytes < 1024
                    ? usedBytes + " B"
                    : usedBytes < 1024L * 1024L
                            ? (usedBytes / 1024L) + " KB"
                            : String.format(java.util.Locale.US, "%.1f MB", usedBytes / 1048576.0);
            // Cleared file is no longer exportable.
            mSnifferFile = null;
            getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                    .remove(PREF_SNIFFER_PATH).apply();
            safeRunOnUiThread(() -> {
                btnSnifferCleanup.setEnabled(true);
                setSnifferUiActive(false,
                        getString(R.string.diag_sniffer_cleanup_status_fmt, finalDeleted, sizeStr));
                Toast.makeText(DiagActivity.this,
                        getString(R.string.diag_sniffer_cleanup_toast_fmt, finalDeleted, sizeStr),
                        Toast.LENGTH_LONG).show();
                AppLogger.i("RESniffer",
                        "cleanupSnifferFiles: " + finalDeleted + " deleted, remaining=" + sizeStr);
            });
        }, "sniffer-cleanup-thread").start();
    }

    private void safeRunOnUiThread(Runnable r) {
        if (mDestroyed) return;
        runOnUiThread(() -> { if (!mDestroyed) r.run(); });
    }


    // ════════════════════════════════════════════════════════════════════════
    // Cluster Move/Resize panel (v1.2.58) — piggyback on the xdja fission VD
    //
    // VirtualDisplay path abandoned (CAPTURE_VIDEO_OUTPUT signature gate +
    // FLAG_OWN_CONTENT_ONLY forced on uid-2000 VDs → toast "écran secondaire"
    // + Telenav/Waze rebound). New approach matches Dilink5 Dashboard /
    // WindowManagement : enumerate displays, find the trusted "fission_*"
    // display owned by com.xdja.containerservice (uid 1000), and drive the
    // target app via the daemon's typed verbs :
    //   1. launchAndForce(pkg, null, fissionId, w, h)  — am start + relocate
    //   2. moveAndResize(pkg, fissionId, l, t, r, b)   — interactive sliders
    // ════════════════════════════════════════════════════════════════════════

    private java.io.File mClusterPocReportFile;

    private void bindClusterPocPanel() {
        if (panelClusterPoc == null) return;
        TextView status   = panelClusterPoc.findViewById(R.id.tv_cluster_poc_status);
        TextView rectView = panelClusterPoc.findViewById(R.id.tv_cluster_poc_rect);
        android.widget.EditText etPkg = panelClusterPoc.findViewById(R.id.et_cluster_poc_pkg);
        View btnEnum    = panelClusterPoc.findViewById(R.id.btn_cluster_poc_enumerate);
        View btnRestart = panelClusterPoc.findViewById(R.id.btn_cluster_poc_restart_daemon);
        View btnShare   = panelClusterPoc.findViewById(R.id.btn_cluster_poc_share);
        View btnLaunch  = panelClusterPoc.findViewById(R.id.btn_cluster_poc_launch);
        View btnStop    = panelClusterPoc.findViewById(R.id.btn_cluster_poc_stop);
        View btnApply   = panelClusterPoc.findViewById(R.id.btn_cluster_poc_apply);        final com.google.android.material.button.MaterialButton btnAuto =
                panelClusterPoc.findViewById(R.id.btn_cluster_poc_auto_apply);
        // v1.2.58 Phase A step 1 — auto-recovery in-car validation buttons.
        View btnKillDaemon    = panelClusterPoc.findViewById(R.id.btn_cluster_poc_kill_daemon);
        View btnTestRecovery  = panelClusterPoc.findViewById(R.id.btn_cluster_poc_test_recovery);
        View btnTestStorm     = panelClusterPoc.findViewById(R.id.btn_cluster_poc_test_storm);
        // v1.2.62 Phase A step 2 — foreground watchdog status button.
        View btnWatchdogStatus = panelClusterPoc.findViewById(R.id.btn_cluster_poc_watchdog_status);
        // v1.2.63 Phase A step 3 — fast rebroadcast (PID-file + trigger-file) test.
        View btnTestRebroadcast = panelClusterPoc.findViewById(R.id.btn_cluster_poc_test_rebroadcast);
        final android.widget.SeekBar sbX = panelClusterPoc.findViewById(R.id.sb_cluster_poc_x);
        final android.widget.SeekBar sbY = panelClusterPoc.findViewById(R.id.sb_cluster_poc_y);
        final android.widget.SeekBar sbW = panelClusterPoc.findViewById(R.id.sb_cluster_poc_w);
        final android.widget.SeekBar sbH = panelClusterPoc.findViewById(R.id.sb_cluster_poc_h);

        android.widget.SeekBar.OnSeekBarChangeListener sl = new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                int x = sbX.getProgress(), y = sbY.getProgress();
                int w = Math.max(100, sbW.getProgress()), h = Math.max(100, sbH.getProgress());
                if (rectView != null) {
                    rectView.setText("rect = (" + x + ", " + y + ") " + w + "×" + h);
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                if (btnAuto != null && btnAuto.isChecked()) {
                    applyMoveResize(status, etPkg, sbX, sbY, sbW, sbH);
                }
            }
        };
        if (sbX != null) sbX.setOnSeekBarChangeListener(sl);
        if (sbY != null) sbY.setOnSeekBarChangeListener(sl);
        if (sbW != null) sbW.setOnSeekBarChangeListener(sl);
        if (sbH != null) sbH.setOnSeekBarChangeListener(sl);

        if (btnEnum    != null) btnEnum.setOnClickListener(v -> enumerateDisplaysForPoc(status));
        if (btnRestart != null) btnRestart.setOnClickListener(v -> restartProxyDaemon(status));
        if (btnShare   != null) btnShare.setOnClickListener(v -> shareClusterPocReport());
        if (btnLaunch  != null) btnLaunch.setOnClickListener(v -> launchOnFission(status, etPkg, sbW, sbH));
        if (btnStop    != null) btnStop.setOnClickListener(v -> stopTargetPkg(status, etPkg));
        if (btnApply   != null) btnApply.setOnClickListener(v -> applyMoveResize(status, etPkg, sbX, sbY, sbW, sbH));
        if (btnKillDaemon   != null) btnKillDaemon.setOnClickListener(v -> killProxyDaemonForTest(status));
        if (btnTestRecovery != null) btnTestRecovery.setOnClickListener(v -> testProxyAutoRecovery(status));
        if (btnTestStorm    != null) btnTestStorm.setOnClickListener(v -> testProxyAntiStorm(status));
        if (btnWatchdogStatus != null) btnWatchdogStatus.setOnClickListener(v -> showWatchdogStatus(status));
        if (btnTestRebroadcast != null) btnTestRebroadcast.setOnClickListener(v -> testProxyRebroadcast(status));
    }

    private void enumerateDisplaysForPoc(TextView status) {
        if (status == null) return;
        StringBuilder ui   = new StringBuilder();
        StringBuilder file = new StringBuilder();

        String header = "DashCast Cluster — display enumeration\n"
                + "timestamp=" + new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "\n"
                + "app="       + BuildConfig.APPLICATION_ID + " v" + BuildConfig.VERSION_NAME
                                + " build " + BuildConfig.VERSION_CODE + "\n"
                + "device="    + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                                + " (" + android.os.Build.DEVICE + ")\n"
                + "android="   + android.os.Build.VERSION.RELEASE + " (sdk "
                                + android.os.Build.VERSION.SDK_INT + ")\n";
        file.append(header).append('\n');

        ui.append("[enum] Displays Android visibles :\n\n");
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            ui.append("  DisplayManager indisponible.");
            file.append("ERROR: DisplayManager null\n");
            status.setText(ui.toString());
            writeClusterPocReport(file.toString());
            return;
        }
        Display[] displays = dm.getDisplays();
        int candidateId    = pickClusterDisplayId(displays);

        file.append("== DisplayManager.getDisplays() (").append(displays.length).append(") ==\n");
        for (Display d : displays) {
            Point sz = new Point();
            try { d.getRealSize(sz); } catch (Exception ignored) {}
            android.util.DisplayMetrics dmx = new android.util.DisplayMetrics();
            try { d.getRealMetrics(dmx); } catch (Exception ignored) {}
            int flags = -1, rot = -1;
            try { flags = d.getFlags();    } catch (Throwable ignored) {}
            try { rot   = d.getRotation(); } catch (Throwable ignored) {}
            String line = "  id=" + d.getDisplayId()
                    + " name=\"" + d.getName() + "\""
                    + " size=" + sz.x + "x" + sz.y
                    + " density=" + dmx.densityDpi + "dpi"
                    + " state=" + d.getState()
                    + " flags=0x" + Integer.toHexString(flags)
                    + " rot=" + rot
                    + (d.getDisplayId() == candidateId ? "   ◀ fission/cluster" : "");
            ui.append(line).append('\n');
            file.append(line).append('\n');
        }
        ui.append('\n');

        if (candidateId < 0) {
            ui.append("⚠ Aucun display « fission » / « cluster ». ");
            ui.append("Le display trusted xdja n'est pas présent — ");
            ui.append("vérifie que le containerservice tourne.");
        } else {
            ui.append("Display fission : id=").append(candidateId).append('\n');
            ui.append("→ Lance ta cible (Waze) avec « ▶ Lancer sur fission ».");
        }
        ui.append("\n\nClique « 📤 Partager rapport » pour exporter le détail.");

        status.setText(ui.toString());
        writeClusterPocReport(file.toString());
        AppLogger.i("ClusterPoc", "enumerate: " + displays.length + " displays, fission=" + candidateId);
    }

    /** Heuristic — match Dilink5 Dashboard / WindowManagement : look for any
     *  display whose name starts with "fission_" (the xdja containerservice
     *  trusted VD) or contains "cluster". Returns -1 if none found. */
    private int pickClusterDisplayId(Display[] displays) {
        for (Display d : displays) {
            String name = d.getName();
            if (name == null) continue;
            String lc = name.toLowerCase();
            if (lc.startsWith("fission_") || lc.contains("fission") || lc.contains("cluster")) {
                return d.getDisplayId();
            }
        }
        return -1;
    }

    private String currentPkg(android.widget.EditText etPkg) {
        if (etPkg == null) return "com.waze";
        CharSequence s = etPkg.getText();
        String p = s == null ? null : s.toString().trim();
        return (p == null || p.isEmpty()) ? "com.waze" : p;
    }

    private void launchOnFission(TextView status, android.widget.EditText etPkg,
                                  android.widget.SeekBar sbW, android.widget.SeekBar sbH) {
        if (status == null) return;
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) { status.setText("[launch] ❌ DisplayManager null."); return; }
        final int fissionId = pickClusterDisplayId(dm.getDisplays());
        if (fissionId < 0) {
            status.setText("[launch] ❌ Display fission introuvable. Clique « Lister » d'abord.");
            return;
        }
        final String pkg = currentPkg(etPkg);
        final int w = sbW == null ? 400 : Math.max(100, sbW.getProgress());
        final int h = sbH == null ? 400 : Math.max(100, sbH.getProgress());
        status.setText("[launch] ⏳ " + pkg + " → display=" + fissionId + " " + w + "×" + h + "…");

        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            log.append("== launchOnFission ").append(pkg)
               .append(" → display=").append(fissionId)
               .append(' ').append(w).append('x').append(h).append(" ==\n");
            try {
                if (!com.byd.dashcast.beta.BetaProxyClient.isConnected()) {
                    boolean ok = com.byd.dashcast.beta.BetaProxyClient.connect(this);
                    log.append("BetaProxy.connect() = ").append(ok).append('\n');
                    if (!ok) {
                        final String out = log + "\n❌ Daemon indisponible. Tente « 🔄 Restart daemon ».";
                        safeRunOnUiThread(() -> status.setText(out));
                        writeClusterPocReport(out);
                        return;
                    }
                }
                String forceLog = com.byd.dashcast.beta.BetaProxyClient.launchAndForce(
                        pkg, /*activityCls*/ null, fissionId, w, h);
                log.append(forceLog).append('\n');
                final String out = log + "\n✅ Séquence terminée. Regarde le cluster :\n"
                        + " • app visible en " + w + "×" + h + " → manipule les sliders puis « Appliquer rect »\n"
                        + " • app plein écran → resizeTask a échoué (BYD lock probable)\n"
                        + " • rien sur cluster → moveStackToDisplay a échoué (voir log)";
                writeClusterPocReport(out);
                safeRunOnUiThread(() -> status.setText(out));
            } catch (com.byd.dashcast.beta.BetaProxyClient.BetaProxyException bex) {
                log.append("❌ BetaProxyException : ").append(bex.getMessage()).append('\n');
                if (bex.getMessage() != null && bex.getMessage().contains("Unknown transaction")) {
                    log.append("\nDaemon obsolète (proto < 5). Hit « 🔄 Restart daemon » puis réessaie.");
                }
                final String out = log.toString();
                AppLogger.w("ClusterPoc", "launchOnFission failed", bex);
                writeClusterPocReport(out);
                safeRunOnUiThread(() -> status.setText(out));
            } catch (Throwable t) {
                log.append("❌ ").append(t.getClass().getSimpleName()).append(" : ").append(t.getMessage());
                final String out = log.toString();
                AppLogger.w("ClusterPoc", "launchOnFission failed", t);
                writeClusterPocReport(out);
                safeRunOnUiThread(() -> status.setText(out));
            }
        }, "cluster-launch").start();
    }

    private void applyMoveResize(TextView status, android.widget.EditText etPkg,
                                  android.widget.SeekBar sbX, android.widget.SeekBar sbY,
                                  android.widget.SeekBar sbW, android.widget.SeekBar sbH) {
        if (status == null) return;
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) { status.setText("[apply] ❌ DisplayManager null."); return; }
        final int fissionId = pickClusterDisplayId(dm.getDisplays());
        if (fissionId < 0) {
            status.setText("[apply] ❌ Display fission introuvable.");
            return;
        }
        final String pkg = currentPkg(etPkg);
        final int x = sbX.getProgress();
        final int y = sbY.getProgress();
        final int w = Math.max(100, sbW.getProgress());
        final int h = Math.max(100, sbH.getProgress());
        final int l = x, t = y, r = x + w, b = y + h;

        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            log.append("== applyMoveResize ").append(pkg)
               .append(" → display=").append(fissionId)
               .append(" rect=[").append(l).append(',').append(t).append(',')
               .append(r).append(',').append(b).append("] ==\n");
            try {
                if (!com.byd.dashcast.beta.BetaProxyClient.isConnected()) {
                    boolean ok = com.byd.dashcast.beta.BetaProxyClient.connect(this);
                    log.append("connect = ").append(ok).append('\n');
                    if (!ok) {
                        final String out = log + "❌ Daemon indisponible.";
                        safeRunOnUiThread(() -> status.setText(out));
                        return;
                    }
                }
                String moveLog = com.byd.dashcast.beta.BetaProxyClient.moveAndResize(
                        pkg, fissionId, l, t, r, b);
                log.append(moveLog);
                final String out = log.toString();
                writeClusterPocReport(out);
                safeRunOnUiThread(() -> status.setText(out));
            } catch (com.byd.dashcast.beta.BetaProxyClient.BetaProxyException bex) {
                log.append("❌ ").append(bex.getMessage()).append('\n');
                if (bex.getMessage() != null && bex.getMessage().contains("Unknown transaction")) {
                    log.append("\nDaemon obsolète (proto < 5 — pas de moveAndResize). "
                            + "Hit « 🔄 Restart daemon » puis réessaie.");
                }
                final String out = log.toString();
                AppLogger.w("ClusterPoc", "applyMoveResize failed", bex);
                safeRunOnUiThread(() -> status.setText(out));
            } catch (Throwable th) {
                log.append("❌ ").append(th.getClass().getSimpleName()).append(" : ").append(th.getMessage());
                final String out = log.toString();
                AppLogger.w("ClusterPoc", "applyMoveResize failed", th);
                safeRunOnUiThread(() -> status.setText(out));
            }
        }, "cluster-apply").start();
    }

    private void stopTargetPkg(TextView status, android.widget.EditText etPkg) {
        if (status == null) return;
        final String pkg = currentPkg(etPkg);
        status.setText("[stop] ⏳ am force-stop " + pkg + "…");
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            log.append("[stop] am force-stop ").append(pkg).append('\n');
            try {
                if (!com.byd.dashcast.beta.BetaProxyClient.isConnected()) {
                    com.byd.dashcast.beta.BetaProxyClient.connect(this);
                }
                String out = com.byd.dashcast.beta.BetaProxyClient.runShell(
                        "am force-stop " + pkg);
                if (out != null && !out.isEmpty()) log.append(out).append('\n');
                log.append("✓ force-stop OK");
                final String finalLog = log.toString();
                safeRunOnUiThread(() -> status.setText(finalLog));
            } catch (Throwable t) {
                log.append("❌ ").append(t.getClass().getSimpleName()).append(" : ").append(t.getMessage());
                final String finalLog = log.toString();
                safeRunOnUiThread(() -> status.setText(finalLog));
            }
        }, "cluster-stop").start();
    }

    private void writeClusterPocReport(String content) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            String name = "cluster_poc_" + new java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".txt";
            java.io.File f = new java.io.File(dir, name);
            try (java.io.FileWriter w = new java.io.FileWriter(f)) {
                w.write(content);
            }
            mClusterPocReportFile = f;
            AppLogger.i("ClusterPoc", "report written: " + f.getAbsolutePath()
                    + " (" + f.length() + " B)");
        } catch (Exception e) {
            AppLogger.w("ClusterPoc", "writeReport failed", e);
        }
    }

    private void shareClusterPocReport() {
        java.io.File f = mClusterPocReportFile;
        if (f == null || !f.exists() || f.length() == 0) {
            Toast.makeText(this, R.string.diag_cluster_poc_toast_no_report, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(android.content.ClipData.newRawUri("", uri));
            Intent chooser = Intent.createChooser(share,
                    getString(R.string.diag_cluster_poc_chooser_title));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            AppLogger.e("ClusterPoc", "share failed", e);
            Toast.makeText(this, "Share erreur: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restartProxyDaemon(TextView status) {
        if (status == null) return;
        status.setText("[restart] Kill + bootstrap daemon… (peut prendre 5–8 s)");
        new Thread(() -> {
            boolean ok = com.byd.dashcast.beta.BetaProxyClient.killAndRestartDaemon(this);
            String ver = com.byd.dashcast.beta.BetaProxyClient.getProtocolVersion();
            int uid = com.byd.dashcast.beta.BetaProxyClient.getCallerUid();
            int pid = com.byd.dashcast.beta.BetaProxyClient.getDaemonPid();
            int verInt = -1;
            try { verInt = ver == null ? -1 : Integer.parseInt(ver); } catch (Throwable ignored) {}
            final String msg = ok
                    ? ("[restart] ✅ Daemon up. pid=" + pid + " uid=" + uid + " proto=" + ver
                       + (verInt >= 5
                              ? "\n   moveAndResize dispo ✅"
                              : "\n   ⚠ proto < 5 — moveAndResize PAS dispo"))
                    : "[restart] ❌ Échec du bootstrap. Voir logcat.";
            safeRunOnUiThread(() -> status.setText(msg));
        }, "daemon-restart").start();
    }

    // ─── v1.2.58 — Phase A step 1 in-car validation (no ADB required) ────────
    //
    // Three test verbs validating the daemon auto-recovery wired into the
    // typed verbs of BetaProxyClient (callWithRetry + attemptReconnect +
    // 10 s cooldown anti-storm). Buttons live in the Cluster POC panel because
    // that's where the proxy daemon is already explicitly surfaced (Restart
    // button), keeping all daemon controls together.
    //
    // Kill is done via dadb (AdbLocalClient) — never via BetaProxyClient.runShell
    // — because that wrapped verb would auto-recover *during* the kill, racing
    // against the kill itself.

    /** SIGKILL the daemon and report new connection state. Subsequent normal
     *  diag actions (Lister, Lancer, Apply…) should auto-bootstrap silently. */
    private void killProxyDaemonForTest(TextView status) {
        if (status == null) return;
        final int oldPid = com.byd.dashcast.beta.BetaProxyClient.getDaemonPid();
        status.setText("[kill] envoi SIGKILL au daemon (pid=" + oldPid + ")…");
        AdbLocalClient.executeShellWithResult(this,
                "pgrep -f dashcast_proxy | xargs -r kill -9 ; "
                        + "sleep 0.3 ; pgrep -f dashcast_proxy || echo DEAD",
                new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String report) {
                        final String tail = report == null ? "" : report.trim();
                        // Give DeathRecipient a beat to fire and clear sBinder.
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                        final boolean still = com.byd.dashcast.beta.BetaProxyClient.isConnected();
                        safeRunOnUiThread(() -> status.setText(
                                "[kill] ✅ shell OK (" + tail + ")\n"
                                + "isConnected=" + still + " — relance n'importe quelle action "
                                + "pour déclencher l'auto-recovery."));
                    }
                    @Override public void onError(String error) {
                        safeRunOnUiThread(() -> status.setText("[kill] ❌ " + error));
                    }
                });
    }

    /**
     * Kill the daemon, then immediately fire a benign typed verb
     * ({@code getPidsByPackage}) which goes through the wrapped retry path.
     * Reports old PID, new PID, total latency and PROTOCOL_VERSION — the
     * golden-path validation for Phase A step 1.
     */
    private void testProxyAutoRecovery(TextView status) {
        if (status == null) return;
        final int oldPid = com.byd.dashcast.beta.BetaProxyClient.getDaemonPid();
        status.setText("[recovery] kill pid=" + oldPid + " → probe verb (peut prendre 5–8 s)…");
        AdbLocalClient.executeShellWithResult(this,
                "pgrep -f dashcast_proxy | xargs -r kill -9 ; "
                        + "sleep 0.3 ; pgrep -f dashcast_proxy || echo DEAD",
                new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String killReport) {
                        new Thread(() -> {
                            long t0 = System.currentTimeMillis();
                            String verbResult;
                            boolean ok = false;
                            try {
                                // Wrapped verb — exercises callWithRetry + attemptReconnect.
                                String pids = com.byd.dashcast.beta.BetaProxyClient
                                        .getPidsByPackage("com.byd.dashcast");
                                ok = true;
                                verbResult = "pids=\"" + pids + "\"";
                            } catch (Throwable t) {
                                verbResult = "❌ " + t.getClass().getSimpleName()
                                        + ": " + t.getMessage();
                            }
                            long elapsed = System.currentTimeMillis() - t0;
                            final int newPid = com.byd.dashcast.beta.BetaProxyClient.getDaemonPid();
                            final String ver = com.byd.dashcast.beta.BetaProxyClient.getProtocolVersion();
                            final boolean okFinal = ok;
                            final String verbResultFinal = verbResult;
                            safeRunOnUiThread(() -> status.setText(
                                    "[recovery] " + (okFinal ? "✅" : "❌")
                                    + " oldPid=" + oldPid + " newPid=" + newPid
                                    + " proto=" + ver + "\n"
                                    + "elapsed=" + elapsed + " ms\n"
                                    + verbResultFinal + "\n"
                                    + "kill: " + (killReport == null ? "" : killReport.trim())));
                        }, "diag-recovery-test").start();
                    }
                    @Override public void onError(String error) {
                        safeRunOnUiThread(() -> status.setText("[recovery] ❌ kill: " + error));
                    }
                });
    }

    /**
     * Anti-storm validation: kill the daemon, then in a tight loop perform
     * (kill → probe) ×3 with no inter-cycle delay. Expected outcome with the
     * 10 s cooldown gate: cycle 1 reconnects (~5–8 s), cycles 2 and 3 hit the
     * cooldown skip → their probe call throws "not connected" → caller-side
     * legacy fallback would normally take over. Demonstrates the gate is
     * preventing bootstrap cascades.
     */
    private void testProxyAntiStorm(TextView status) {
        if (status == null) return;
        status.setText("[storm] 3 cycles kill→probe enchaînés…");
        new Thread(() -> {
            StringBuilder out = new StringBuilder("[storm] résultats :\n");
            for (int i = 1; i <= 3; i++) {
                // Kill synchronously from this background thread.
                try {
                    java.util.concurrent.CountDownLatch killLatch = new java.util.concurrent.CountDownLatch(1);
                    AdbLocalClient.executeShellWithResult(this,
                            "pgrep -f dashcast_proxy | xargs -r kill -9",
                            new AdbLocalClient.Callback() {
                                @Override public void onSuccess(String r) { killLatch.countDown(); }
                                @Override public void onError(String e)   { killLatch.countDown(); }
                            });
                    killLatch.await(3, java.util.concurrent.TimeUnit.SECONDS);
                    // Tiny pause to let DeathRecipient fire.
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                long t0 = System.currentTimeMillis();
                String result;
                try {
                    com.byd.dashcast.beta.BetaProxyClient.getPidsByPackage("com.byd.dashcast");
                    result = "✅ probe OK (reconnect)";
                } catch (Throwable t) {
                    result = "⏸ probe blocked (" + t.getMessage() + ")";
                }
                long elapsed = System.currentTimeMillis() - t0;
                out.append("  cycle ").append(i).append(": ")
                   .append(result).append(" — ").append(elapsed).append(" ms\n");
            }
            out.append("Attendu : cycle 1 ✅ (5–8 s), cycles 2/3 ⏸ (cooldown 10 s).");
            final String finalOut = out.toString();
            safeRunOnUiThread(() -> status.setText(finalOut));
        }, "diag-storm-test").start();
    }

    /**
     * v1.2.62 — Phase A step 2 : show the foreground liveness watchdog state.
     * Reports install state, foreground activity count, daemon connection,
     * and age of the last « binder seen alive » observation.
     */
    private void showWatchdogStatus(TextView status) {
        if (status == null) return;
        boolean installed = com.byd.dashcast.beta.ProxyWatchdog.isInstalled();
        int fg            = com.byd.dashcast.beta.ProxyWatchdog.getForegroundCount();
        long ageMs        = com.byd.dashcast.beta.ProxyWatchdog.getMsSinceLastSeenAlive();
        boolean connected = com.byd.dashcast.beta.BetaProxyClient.isConnected();
        int pid           = com.byd.dashcast.beta.BetaProxyClient.getDaemonPid();
        boolean proxyOn   = com.byd.dashcast.beta.BetaConfig.isProxyDaemonEnabled(this);
        String age = ageMs < 0 ? "jamais observé" : (ageMs + " ms");
        status.setText("[watchdog]"
                + " installed=" + installed
                + " foreground=" + fg
                + "\n  proxyEnabled=" + proxyOn
                + " connected=" + connected
                + " pid=" + pid
                + "\n  lastSeenAlive=" + age
                + "\n  → Astuce: 💀 Kill, attendre 30 s, refaire "
                + "Watchdog status — le pid doit avoir changé tout seul.");
    }

    /**
     * v1.2.63 — Phase A step 3 : exercises the bootstrap script's PID-file
     * fast path. If the daemon is alive, the script must return
     * {@code REBROADCAST <pid>} in tens of milliseconds (vs. ~1 s for a full
     * {@code app_process} respawn). The daemon-side {@code FileObserver}
     * then re-emits {@link com.byd.dashcast.beta.proxy.ProxyDaemonMain#ACTION_PROXY_CONNECTED}.
     * Does NOT touch the binder the app currently holds — purely validates
     * the shell-level plumbing.
     */
    private void testProxyRebroadcast(TextView status) {
        if (status == null) return;
        final int pid = com.byd.dashcast.beta.BetaProxyClient.getDaemonPid();
        if (pid <= 0) {
            status.setText("[rebroadcast] ⚠ daemon non connecté — connecte d'abord (Recovery/Storm).");
            return;
        }
        status.setText("[rebroadcast] PID=" + pid + " → bootstrap fast-path probe…");
        // Inline reproduction of the BetaProxyClient fast-path branch — same
        // file names + identity check. Returns REBROADCAST <pid> when the
        // daemon is alive, OK <apk> otherwise. Run in a worker thread so the
        // shell roundtrip never blocks the UI.
        new Thread(() -> {
            final long t0 = System.currentTimeMillis();
            AdbLocalClient.executeShellWithResult(this,
                    "PIDF=/data/local/tmp/dashcast_proxy.pid; "
                            + "TRIG=/data/local/tmp/dashcast_proxy.trigger; "
                            + "P=$(cat \"$PIDF\" 2>/dev/null); "
                            + "if [ -z \"$P\" ]; then echo NO_PID_FILE; exit 0; fi; "
                            + "N=$(cat /proc/$P/comm 2>/dev/null); "
                            + "if [ \"$N\" != \"dashcast_proxy\" ]; then "
                            + "  echo \"STALE pid=$P comm=$N\"; exit 0; "
                            + "fi; "
                            + "echo trigger > \"$TRIG\" && echo \"REBROADCAST $P\" || echo \"TRIGGER_FAILED $P\"",
                    new AdbLocalClient.Callback() {
                        @Override public void onSuccess(String r) {
                            long elapsed = System.currentTimeMillis() - t0;
                            final String trimmed = r == null ? "" : r.trim();
                            final boolean fast = trimmed.startsWith("REBROADCAST");
                            safeRunOnUiThread(() -> status.setText(
                                    "[rebroadcast] " + (fast ? "✅" : "⚠")
                                            + " elapsed=" + elapsed + " ms\n"
                                            + "shell: " + trimmed + "\n"
                                            + "Attendu : « REBROADCAST " + pid + " » en < 200 ms.\n"
                                            + "→ Vérifie /data/local/tmp/dashcast_proxy.log "
                                            + "pour la ligne « rebroadcast trigger received »."));
                        }
                        @Override public void onError(String e) {
                            safeRunOnUiThread(() -> status.setText("[rebroadcast] ❌ shell: " + e));
                        }
                    });
        }, "diag-rebroadcast-test").start();
    }

    // ─── v1.2.42 — Export BYD APK panel ──────────────────────────────────────

    /** Cache subdirectory where APKs are copied before being shared. Auto-wiped. */
    private static final String EXPORT_APK_SUBDIR = "exported_apks";
    /** Auto-deletion delay after we hand the file over to Telegram. */
    private static final long EXPORT_APK_AUTO_DELETE_MS = 5L * 60L * 1000L;
    /** Package-name prefixes / substrings that qualify as « BYD » for export. */
    private static final String[] EXPORT_APK_NEEDLES = new String[] {
            "com.byd.", "com.xdja.", "com.dilink.",
            "cluster", "automotive", "fission", "projection", "dilink"
    };

    private TextView     tvExportApkCounters;
    private MaterialButton btnExportApkRefresh;
    private LinearLayout llExportApkList;
    private boolean      exportApkInitialised = false;
    private final Handler exportApkCleanupHandler = new Handler(Looper.getMainLooper());

    // v1.2.46 — lazy test-row preparation flags. Each panel's row catalog
    // (~10–42 inflations × ~6 findViewById per row) is now built only when the
    // panel is first shown, instead of all 6 catalogs at onCreate. Cuts the
    // DiagActivity cold-start from ~400–800 ms down to one catalog's worth.
    private boolean betaRowsPrepared       = false;
    private boolean dl5RowsPrepared        = false;
    private boolean dl2RowsPrepared        = false;
    private boolean dl4RowsPrepared        = false;
    private boolean mirrorRowsPrepared     = false;
    private boolean clusterDl5RowsPrepared = false;

    private static final class ExportApkEntry {
        final String pkg;
        final String versionName;
        final long   versionCode;
        final String sourceDir;
        final long   sizeBytes;
        ExportApkEntry(String pkg, String vn, long vc, String src, long size) {
            this.pkg = pkg; this.versionName = vn; this.versionCode = vc;
            this.sourceDir = src; this.sizeBytes = size;
        }
    }

    private void bindExportApkPanel() {
        tvExportApkCounters = panelExportApk.findViewById(R.id.tv_export_apk_counters);
        btnExportApkRefresh = panelExportApk.findViewById(R.id.btn_export_apk_refresh);
        llExportApkList     = panelExportApk.findViewById(R.id.ll_export_apk_list);
        btnExportApkRefresh.setOnClickListener(v -> {
            wipeExportApkCacheDir();
            refreshExportApkList();
        });
        // Eager wipe at activity creation so we never carry over a previously
        // exported APK between launches.
        wipeExportApkCacheDir();
    }

    private void refreshExportApkListIfNeeded() {
        if (exportApkInitialised) return;
        refreshExportApkList();
    }

    private void refreshExportApkList() {
        exportApkInitialised = true;
        llExportApkList.removeAllViews();
        tvExportApkCounters.setText(R.string.diag_export_apk_counters_scanning);

        new Thread(() -> {
            List<ExportApkEntry> entries = scanBydPackages();
            safeRunOnUiThread(() -> {
                llExportApkList.removeAllViews();
                LayoutInflater inflater = LayoutInflater.from(this);
                long totalBytes = 0;
                for (ExportApkEntry e : entries) {
                    totalBytes += Math.max(0, e.sizeBytes);
                    View row = inflater.inflate(R.layout.row_export_apk, llExportApkList, false);
                    TextView tvPkg     = row.findViewById(R.id.tv_export_apk_pkg);
                    TextView tvVersion = row.findViewById(R.id.tv_export_apk_version);
                    TextView tvSize    = row.findViewById(R.id.tv_export_apk_size);
                    TextView tvPath    = row.findViewById(R.id.tv_export_apk_path);
                    MaterialButton btn = row.findViewById(R.id.btn_export_apk_row);
                    tvPkg.setText(e.pkg);
                    tvVersion.setText(getString(R.string.diag_export_apk_version_fmt,
                            e.versionName, e.versionCode));
                    tvSize.setText(formatBytes(e.sizeBytes));
                    tvPath.setText(e.sourceDir != null ? e.sourceDir : "-");
                    btn.setOnClickListener(v -> onExportApkClicked(e, btn));
                    llExportApkList.addView(row);
                }
                tvExportApkCounters.setText(getString(
                        R.string.diag_export_apk_counters_fmt,
                        entries.size(), formatBytes(totalBytes)));
            });
        }, "export-apk-scan").start();
    }

    private List<ExportApkEntry> scanBydPackages() {
        List<ExportApkEntry> out = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            List<android.content.pm.PackageInfo> all = pm.getInstalledPackages(0);
            for (android.content.pm.PackageInfo pi : all) {
                if (pi == null || pi.packageName == null) continue;
                String low = pi.packageName.toLowerCase();
                boolean match = false;
                for (String n : EXPORT_APK_NEEDLES) {
                    if (low.contains(n)) { match = true; break; }
                }
                if (!match) continue;
                String vn = pi.versionName != null ? pi.versionName : "?";
                long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                String src = pi.applicationInfo != null ? pi.applicationInfo.sourceDir : null;
                long size = 0;
                if (src != null) {
                    try { size = new java.io.File(src).length(); } catch (Throwable ignore) {}
                }
                out.add(new ExportApkEntry(pi.packageName, vn, vc, src, size));
            }
            Collections.sort(out, new Comparator<ExportApkEntry>() {
                @Override public int compare(ExportApkEntry a, ExportApkEntry b) {
                    return a.pkg.compareTo(b.pkg);
                }
            });
        } catch (Throwable t) {
            AppLogger.w("DiagActivity", "scanBydPackages failed: " + t);
        }
        return out;
    }

    private void onExportApkClicked(ExportApkEntry e, MaterialButton btn) {
        if (e.sourceDir == null || e.sourceDir.isEmpty()) {
            Toast.makeText(this, R.string.diag_export_apk_err_no_source, Toast.LENGTH_LONG).show();
            return;
        }
        btn.setEnabled(false);
        btn.setText(R.string.diag_export_apk_btn_exporting);
        new Thread(() -> {
            java.io.File copied = null;
            String err = null;
            try {
                java.io.File srcFile = new java.io.File(e.sourceDir);
                if (!srcFile.exists() || !srcFile.canRead()) {
                    err = "APK unreadable: " + e.sourceDir;
                } else {
                    java.io.File outDir = exportApkCacheDir();
                    if (outDir == null) {
                        err = "No cache dir";
                    } else {
                        String safeName = e.pkg + "-v" + sanitiseVer(e.versionName)
                                + "-" + e.versionCode + ".apk";
                        copied = new java.io.File(outDir, safeName);
                        copyFile(srcFile, copied);
                    }
                }
            } catch (Throwable t) {
                err = t.getMessage();
                AppLogger.w("DiagActivity", "Export APK copy failed: " + t);
            }
            final java.io.File finalCopied = copied;
            final String finalErr = err;
            safeRunOnUiThread(() -> {
                btn.setEnabled(true);
                btn.setText(R.string.diag_export_apk_btn_export);
                if (finalErr != null || finalCopied == null) {
                    Toast.makeText(this,
                            getString(R.string.diag_export_apk_err_fmt, String.valueOf(finalErr)),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                shareApkToTelegram(finalCopied, e.pkg);
                scheduleExportApkDelete(finalCopied);
            });
        }, "export-apk-" + e.pkg).start();
    }

    private void shareApkToTelegram(java.io.File apk, String pkg) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apk);
            String[] tgPkgs = new String[] {
                    "org.telegram.messenger", "org.telegram.messenger.web",
                    "org.telegram.plus", "nekox.messenger", "org.thunderdog.challegram"
            };
            String hit = null;
            PackageManager pm = getPackageManager();
            for (String p : tgPkgs) {
                try { pm.getPackageInfo(p, 0); hit = p; break; }
                catch (PackageManager.NameNotFoundException ignore) {}
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/vnd.android.package-archive");
            send.putExtra(Intent.EXTRA_SUBJECT, "DashCast — APK export: " + pkg);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (hit != null) {
                send.setPackage(hit);
                try {
                    startActivity(send);
                    Toast.makeText(this,
                            getString(R.string.diag_export_apk_sent_fmt, pkg),
                            Toast.LENGTH_SHORT).show();
                    return;
                } catch (Throwable t) {
                    AppLogger.w("DiagActivity", "Direct Telegram intent failed, falling back: " + t);
                    send.setPackage(null);
                }
            }
            startActivity(Intent.createChooser(send,
                    getString(R.string.diag_export_apk_chooser_title, pkg)));
        } catch (Throwable t) {
            AppLogger.w("DiagActivity", "shareApkToTelegram failed: " + t);
            Toast.makeText(this,
                    getString(R.string.diag_export_apk_err_fmt, String.valueOf(t.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void scheduleExportApkDelete(java.io.File f) {
        // Auto-wipe the cached APK so the car never keeps the file long-term.
        // Delay leaves Telegram enough time to upload before we yank it.
        exportApkCleanupHandler.postDelayed(() -> {
            try {
                if (f.exists() && f.delete()) {
                    AppLogger.i("DiagActivity", "Export APK auto-deleted: " + f.getName());
                }
            } catch (Throwable ignore) {}
        }, EXPORT_APK_AUTO_DELETE_MS);
    }

    private java.io.File exportApkCacheDir() {
        java.io.File ext = getExternalFilesDir(null);
        java.io.File base = ext != null ? ext : getCacheDir();
        if (base == null) return null;
        java.io.File dir = new java.io.File(base, EXPORT_APK_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }

    private void wipeExportApkCacheDir() {
        try {
            java.io.File dir = exportApkCacheDir();
            if (dir == null || !dir.isDirectory()) return;
            java.io.File[] files = dir.listFiles();
            if (files == null) return;
            for (java.io.File f : files) {
                if (f.isFile()) { //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable ignore) {}
    }

    private static void copyFile(java.io.File src, java.io.File dst) throws java.io.IOException {
        try (java.io.FileInputStream in  = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String sanitiseVer(String v) {
        if (v == null) return "0";
        return v.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String formatBytes(long b) {
        if (b <= 0) return "-";
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(java.util.Locale.US, "%.1f MB", mb);
    }

    // ─── Voice PoC panel (v1.2.43-beta, ML-free chain validator) ───────────

    /** Permission request code for RECORD_AUDIO, scoped to the Voice tab. */
    private static final int RC_VOICE_RECORD_AUDIO = 0x7AC0;

    private TextView       tvVoiceStatePill;
    private TextView       tvVoiceSampleRate;
    private TextView       tvVoiceRunTime;
    private TextView       tvVoiceStats;
    private ProgressBar    pbVoiceRms;
    private ProgressBar    pbVoicePeak;
    private MaterialButton btnVoiceToggle;
    private boolean        voiceReceiverRegistered;
    private boolean        voicePanelBound;

    // v1.2.50-beta wake-word card
    private com.google.android.material.materialswitch.MaterialSwitch swVoiceWakeword;
    private ProgressBar    pbVoiceWwScore;
    private TextView       tvVoiceWwLast;
    private TextView       tvVoiceWwModel;
    private com.byd.dashcast.voice.wakeword.WakeWordEngine voiceWakeWordEngine;

    private final BroadcastReceiver voiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (VoiceService.ACTION_LEVEL.equals(action)) {
                int rms   = intent.getIntExtra(VoiceService.EXTRA_RMS, 0);
                int peak  = intent.getIntExtra(VoiceService.EXTRA_PEAK, 0);
                long clip = intent.getLongExtra(VoiceService.EXTRA_CLIP, 0L);
                long fr   = intent.getLongExtra(VoiceService.EXTRA_FRAMES, 0L);
                long ms   = intent.getLongExtra(VoiceService.EXTRA_RUN_MS, 0L);
                if (pbVoiceRms != null)   pbVoiceRms.setProgress(rms);
                if (pbVoicePeak != null)  pbVoicePeak.setProgress(peak);
                if (tvVoiceStats != null) tvVoiceStats.setText(getString(R.string.diag_voice_stats_fmt, rms, peak, clip, fr));
                if (tvVoiceRunTime != null) tvVoiceRunTime.setText(formatVoiceRunTime(ms));
            } else if (VoiceService.ACTION_STATE.equals(action)) {
                int state = intent.getIntExtra(VoiceService.EXTRA_STATE, 0);
                String reason = intent.getStringExtra(VoiceService.EXTRA_REASON);
                applyVoiceState(state, reason);
            } else if (com.byd.dashcast.voice.wakeword.WakeWordEngine.ACTION_WAKEWORD.equals(action)) {
                onWakeWordBroadcast(intent);
            }
        }
    };

    private void bindVoicePanel() {
        if (panelVoice == null) return;
        tvVoiceStatePill  = panelVoice.findViewById(R.id.tv_voice_state_pill);
        tvVoiceSampleRate = panelVoice.findViewById(R.id.tv_voice_sample_rate);
        tvVoiceRunTime    = panelVoice.findViewById(R.id.tv_voice_run_time);
        tvVoiceStats      = panelVoice.findViewById(R.id.tv_voice_stats);
        pbVoiceRms        = panelVoice.findViewById(R.id.pb_voice_rms);
        pbVoicePeak       = panelVoice.findViewById(R.id.pb_voice_peak);
        btnVoiceToggle    = panelVoice.findViewById(R.id.btn_voice_toggle);
        if (tvVoiceSampleRate != null) {
            tvVoiceSampleRate.setText(getString(R.string.diag_voice_sample_rate_fmt, VoiceService.SAMPLE_RATE_HZ));
        }
        if (btnVoiceToggle != null) btnVoiceToggle.setOnClickListener(v -> onVoiceToggleClicked());

        // v1.2.50 wake-word card.
        swVoiceWakeword  = panelVoice.findViewById(R.id.sw_voice_wakeword);
        pbVoiceWwScore   = panelVoice.findViewById(R.id.pb_voice_ww_score);
        tvVoiceWwLast    = panelVoice.findViewById(R.id.tv_voice_ww_last);
        tvVoiceWwModel   = panelVoice.findViewById(R.id.tv_voice_ww_model);
        if (swVoiceWakeword != null) {
            swVoiceWakeword.setChecked(voiceWakeWordEngine != null);
            swVoiceWakeword.setOnCheckedChangeListener((bv, checked) -> onWakeWordToggle(checked));
        }

        // Reflect current state in case the service is already running (e.g. orientation change).
        applyVoiceState(VoiceService.isRunning() ? VoiceService.STATE_STARTED : VoiceService.STATE_STOPPED, null);
        voicePanelBound = true;
    }

    /** Called when the user navigates to the Voice tab; lazy-registers the local broadcast receiver. */
    private void onVoicePanelEntered() {
        registerVoiceReceiverIfNeeded();
        applyVoiceState(VoiceService.isRunning() ? VoiceService.STATE_STARTED : VoiceService.STATE_STOPPED, null);
    }

    private void registerVoiceReceiverIfNeeded() {
        if (voiceReceiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(VoiceService.ACTION_LEVEL);
        f.addAction(VoiceService.ACTION_STATE);
        f.addAction(com.byd.dashcast.voice.wakeword.WakeWordEngine.ACTION_WAKEWORD);
        LocalBroadcastManager.getInstance(this).registerReceiver(voiceReceiver, f);
        voiceReceiverRegistered = true;
    }

    private void unregisterVoiceReceiver() {
        if (!voiceReceiverRegistered) return;
        LocalBroadcastManager.getInstance(this).unregisterReceiver(voiceReceiver);
        voiceReceiverRegistered = false;
    }

    private void onVoiceToggleClicked() {
        if (VoiceService.isRunning()) {
            VoiceService.stop(this);
            applyVoiceState(VoiceService.STATE_STOPPED, null);
            return;
        }
        // Need RECORD_AUDIO at runtime on API 23+ (always true on DL3/4/5).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, RC_VOICE_RECORD_AUDIO);
            return;
        }
        startVoiceService();
    }

    private void startVoiceService() {
        registerVoiceReceiverIfNeeded();
        VoiceService.start(this);
        // Service will broadcast STATE_STARTED → we update the UI from the receiver.
    }

    private void applyVoiceState(int state, String reason) {
        if (!voicePanelBound) return;
        switch (state) {
            case VoiceService.STATE_STARTED:
                if (tvVoiceStatePill != null) tvVoiceStatePill.setText(getString(R.string.diag_voice_state_running));
                if (btnVoiceToggle != null)   btnVoiceToggle.setText(getString(R.string.diag_voice_btn_stop));
                break;
            case VoiceService.STATE_ERROR:
                String msg = (reason == null || reason.isEmpty()) ? "?" : reason;
                if (tvVoiceStatePill != null) tvVoiceStatePill.setText(getString(R.string.diag_voice_state_error_fmt, msg));
                if (btnVoiceToggle != null)   btnVoiceToggle.setText(getString(R.string.diag_voice_btn_start));
                if (pbVoiceRms != null)       pbVoiceRms.setProgress(0);
                if (pbVoicePeak != null)      pbVoicePeak.setProgress(0);
                break;
            case VoiceService.STATE_STOPPED:
            default:
                if (tvVoiceStatePill != null) tvVoiceStatePill.setText(getString(R.string.diag_voice_state_idle));
                if (btnVoiceToggle != null)   btnVoiceToggle.setText(getString(R.string.diag_voice_btn_start));
                if (pbVoiceRms != null)       pbVoiceRms.setProgress(0);
                if (pbVoicePeak != null)      pbVoicePeak.setProgress(0);
                if (tvVoiceStats != null)     tvVoiceStats.setText(getString(R.string.diag_voice_stats_idle));
                if (tvVoiceRunTime != null)   tvVoiceRunTime.setText(getString(R.string.diag_voice_stats_idle));
                break;
        }
    }

    private static String formatVoiceRunTime(long ms) {
        long s  = ms / 1000L;
        long mm = s / 60L;
        long ss = s % 60L;
        return String.format(java.util.Locale.US, "%02d:%02d", mm, ss);
    }

    // ─── v1.2.50 wake-word ──────────────────────────────────────────────────────────────

    private void onWakeWordToggle(boolean enabled) {
        if (enabled) {
            if (voiceWakeWordEngine != null) return;
            // v1.2.55-beta — wake-word ("Hey Jarvis") was a silent no-op when
            // the user flipped this switch without first hitting "Démarrer"
            // on the voice panel: the engine ran, but no AudioRecord was
            // feeding it (VoiceService not started → no SampleConsumer
            // callbacks). Auto-start the capture pipeline so toggling the
            // switch is sufficient.
            if (!VoiceService.isRunning()) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    // Request RECORD_AUDIO; user will need to re-toggle after
                    // grant (onRequestPermissionsResult starts VoiceService
                    // only, not the engine).
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.RECORD_AUDIO}, RC_VOICE_RECORD_AUDIO);
                    if (swVoiceWakeword != null) swVoiceWakeword.setChecked(false);
                    return;
                }
                startVoiceService();
            }
            try {
                voiceWakeWordEngine = new com.byd.dashcast.voice.wakeword.WakeWordEngine(this);
                VoiceService.setSampleConsumer(voiceWakeWordEngine);
                voiceWakeWordEngine.start();
            } catch (Throwable t) {
                AppLogger.e("DiagVoice", "WakeWord init failed: " + t);
                voiceWakeWordEngine = null;
                VoiceService.setSampleConsumer(null);
                if (swVoiceWakeword != null) swVoiceWakeword.setChecked(false);
                if (tvVoiceWwModel != null) {
                    tvVoiceWwModel.setText(getString(R.string.diag_voice_wakeword_unavailable,
                            t.getClass().getSimpleName()));
                }
            }
        } else {
            VoiceService.setSampleConsumer(null);
            if (voiceWakeWordEngine != null) {
                voiceWakeWordEngine.stop();
                voiceWakeWordEngine = null;
            }
            if (pbVoiceWwScore != null) pbVoiceWwScore.setProgress(0);
        }
    }

    private void onWakeWordBroadcast(Intent intent) {
        if (!voicePanelBound) return;
        boolean unavailable = intent.getBooleanExtra(
                com.byd.dashcast.voice.wakeword.WakeWordEngine.EXTRA_WW_UNAVAILABLE, false);
        if (unavailable) {
            String reason = intent.getStringExtra(
                    com.byd.dashcast.voice.wakeword.WakeWordEngine.EXTRA_WW_REASON);
            if (tvVoiceWwModel != null) {
                tvVoiceWwModel.setText(getString(R.string.diag_voice_wakeword_unavailable,
                        reason == null ? "?" : reason));
            }
            if (swVoiceWakeword != null) swVoiceWakeword.setChecked(false);
            return;
        }
        float score = intent.getFloatExtra(
                com.byd.dashcast.voice.wakeword.WakeWordEngine.EXTRA_WW_SCORE, 0f);
        long lastMs = intent.getLongExtra(
                com.byd.dashcast.voice.wakeword.WakeWordEngine.EXTRA_WW_LAST_MS, 0L);
        if (pbVoiceWwScore != null) {
            int p = Math.max(0, Math.min(1000, (int) (score * 1000f)));
            pbVoiceWwScore.setProgress(p);
        }
        if (tvVoiceWwLast != null && lastMs > 0L) {
            // Convert elapsedRealtime delta into wall-clock HH:mm:ss.
            long wall = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - lastMs);
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
            String when = sdf.format(new java.util.Date(wall));
            tvVoiceWwLast.setText(getString(R.string.diag_voice_wakeword_last_at_fmt, when, score));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != RC_VOICE_RECORD_AUDIO) return;
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            startVoiceService();
        } else {
            Toast.makeText(getApplicationContext(),
                    R.string.diag_voice_perm_denied, Toast.LENGTH_LONG).show();
        }
    }
}
