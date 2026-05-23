package com.byd.dashcast;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.byd.dashcast.beta.BetaConfig;
import com.byd.dashcast.beta.BetaTestRunner;
import com.byd.dashcast.beta.BetaTestRunner.TestDef;
import com.byd.dashcast.beta.BetaTestRunner.TestResult;
import com.byd.dashcast.beta.BetaTestRunner.Status;
import com.byd.dashcast.dilink5.DiLink5TestRunner;
import com.byd.dashcast.dilink2.DiLink2TestRunner;
import com.byd.dashcast.platform.Platform;

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
    private static final int TAB_MIRROR      = 8;
    private static final int TAB_SNIFFER     = 9;

    private TabLayout    tabs;
    private View         panelBeta;
    private View         panelDl5;
    private View         panelDl2;
    private View         panelMirror;
    private View         panelSniffer;
    private View         panelAdas;
    private View         panelComingSoon;
    private static final int TAB_COUNT = 10; // cluster,display,adb_local,system,adas,beta,dl5,dl2,mirror,sniffer

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

    // DiLink 2 panel views (build 185 — recon-only)
    private TextView       tvDl2HeaderSubtitle;
    private TextView       tvDl2SignaturePill;
    private TextView       tvDl2Counters;
    private MaterialButton btnDl2RunAll;
    private MaterialButton btnDl2CopyReport;
    private LinearLayout   llDl2TestList;
    private final List<View> dl2RowViews = new ArrayList<>();
    private final List<DiLink2TestRunner.TestResult> dl2LastResults = new ArrayList<>();

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
        bindMirrorPanel();
        bindSnifferPanel();
        bindAdasPanel();
        prepareTestRows();
        prepareDl5TestRows();
        prepareDl2TestRows();
        prepareMirrorTestRows();
        updateStatusPills();
        restoreSnifferState();
        // Default tab: DiLink 2 when auto-detected as DL2 (build 192), DiLink 5 when DL5,
        // Beta Engine otherwise. DL2 takes priority because its diag surface is the only
        // useful one on that platform (cluster RE workflow).
        int defaultTab;
        if (Platform.get().isAutoDetectedDiLink2())      defaultTab = TAB_DILINK2;
        else if (Platform.get().isAutoDetectedDiLink5()) defaultTab = TAB_DILINK5;
        else                                             defaultTab = TAB_BETA_ENGINE;
        tabs.selectTab(tabs.getTabAt(defaultTab));
        showPanelForTab(defaultTab);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
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
    }

    // ─── Tabs ───────────────────────────────────────────────────────────────

    private void bindTabs() {
        tabs            = findViewById(R.id.tabs_diag);
        panelBeta       = findViewById(R.id.panel_beta_engine);
        panelDl5        = findViewById(R.id.panel_dilink5);
        panelDl2        = findViewById(R.id.panel_dilink2);
        panelMirror     = findViewById(R.id.panel_mirror);
        panelSniffer    = findViewById(R.id.panel_sniffer);
        panelAdas       = findViewById(R.id.panel_adas);
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
        boolean isBeta    = position == TAB_BETA_ENGINE;
        boolean isDl5     = position == TAB_DILINK5;
        boolean isDl2     = position == TAB_DILINK2;
        boolean isMirror  = position == TAB_MIRROR;
        boolean isSniffer = position == TAB_SNIFFER;
        boolean isAdas    = position == TAB_ADAS;
        panelBeta.setVisibility(isBeta ? View.VISIBLE : View.GONE);
        panelDl5.setVisibility(isDl5 ? View.VISIBLE : View.GONE);
        panelDl2.setVisibility(isDl2 ? View.VISIBLE : View.GONE);
        panelMirror.setVisibility(isMirror ? View.VISIBLE : View.GONE);
        panelSniffer.setVisibility(isSniffer ? View.VISIBLE : View.GONE);
        panelAdas.setVisibility(isAdas ? View.VISIBLE : View.GONE);
        panelComingSoon.setVisibility((isBeta || isDl5 || isDl2 || isMirror || isSniffer || isAdas) ? View.GONE : View.VISIBLE);
        if (!isBeta && !isDl5 && !isDl2 && !isMirror && !isSniffer && !isAdas) {
            TextView title = panelComingSoon.findViewById(R.id.tv_coming_soon_title);
            int titleRes;
            switch (position) {
                case TAB_CLUSTER:   titleRes = R.string.diag_tab_cluster;   break;
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

    private void populateLaunchableApps() {
        dl5Apps.clear();
        PackageManager pm = getPackageManager();
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
            if (pkg == null || pkg.equals(getPackageName())) continue;
            CharSequence label = ri.loadLabel(pm);
            String lbl = (label != null && label.length() > 0) ? label.toString() : pkg;
            dl5Apps.add(new LaunchableApp(lbl, pkg));
        }
        Collections.sort(dl5Apps, new Comparator<LaunchableApp>() {
            @Override public int compare(LaunchableApp a, LaunchableApp b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
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
    private java.io.File   mSnifferFile;

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
        if (tvAdasResult != null) tvAdasResult.setText("$ " + cmd + "\n…envoi en cours…");
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
                        tvAdasResult.setText("$ " + cmd + "\nERREUR: " + error);
                });
            }
        });
    }

    private void setSnifferUiActive(boolean active, String detail) {
        if (mDestroyed) return;
        tvSnifferStatusPill.setText(active ? "ACTIF" : "INACTIF");
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
                        setSnifferUiActive(true, "ACTIF → " + f.getName() + "\n("
                                + (f.length() / 1024) + " KB capturés)");
                    } else {
                        getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE).edit()
                                .remove(PREF_SNIFFER_PATH).apply();
                        // File still exists on disk → keep export enabled
                        setSnifferUiActive(false, "Capture précédente arrêtée → " + f.getName()
                                + "\n(" + (f.length() / 1024) + " KB) — exportable.");
                    }
                });
            }
            @Override public void onError(String err) {
                safeRunOnUiThread(() -> setSnifferUiActive(false,
                        "Capture précédente : " + f.getName()
                        + " (" + (f.length() / 1024) + " KB, ADB local indisponible — exportable)"));
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

        setSnifferUiActive(false, "Initialisation…");
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
                    setSnifferUiActive(true, "ACTIF → " + mSnifferFile.getName()
                            + "\nCapture en cours (logcat + snapshots auto / 10 s)");
                    Toast.makeText(DiagActivity.this,
                            "Sniffer démarré → " + mSnifferFile.getName(),
                            Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onError(String err) {
                safeRunOnUiThread(() -> {
                    setSnifferUiActive(false, "❌ Échec init : " + err);
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
        final String fileName = mSnifferFile != null ? mSnifferFile.getName() : "(aucun)";
        if (mSnifferFile != null) {
            AdbLocalClient.executeShell(this,
                    "echo '[RE Sniffer] Stopped.' >> " + mSnifferFile.getAbsolutePath());
        }
        safeRunOnUiThread(() -> {
            setSnifferUiActive(false, "Arrêté → " + fileName + "\nPrêt pour export.");
            Toast.makeText(this, "Sniffer arrêté.", Toast.LENGTH_SHORT).show();
        });
    }

    private void snapshotSniffer() {
        if (mSnifferFile == null) {
            Toast.makeText(this, "Démarrer le sniffer d'abord.", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, "Snapshot ajouté.", Toast.LENGTH_SHORT).show();
    }

    private void exportSniffer() {
        if (mSnifferFile == null) {
            String saved = getSharedPreferences(PREF_SNIFFER, MODE_PRIVATE)
                    .getString(PREF_SNIFFER_PATH, null);
            if (saved != null) mSnifferFile = new java.io.File(saved);
        }
        java.io.File logFile = mSnifferFile;
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            Toast.makeText(this, "Aucun fichier sniffer à exporter.", Toast.LENGTH_SHORT).show();
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
            Intent chooser = Intent.createChooser(shareIntent, "Exporter Sniffer RE");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            AppLogger.e("RESniffer", "Export erreur", e);
            Toast.makeText(this, "Export erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                    "Arrête le sniffer avant de nettoyer (la capture écrit dans un fichier).",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Nettoyer les fichiers DashCast")
                .setMessage("Supprime tous les logs et captures (byd_log_*, byd_report_*, "
                        + "BYD_RE_Sniffer_*, cluster_live.png) du stockage de l'application.\n\n"
                        + "Les clés ADB et préférences sont préservées.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Nettoyer", (d, w) -> doCleanupSnifferFiles())
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
                        "Nettoyage : " + finalDeleted + " fichier(s) supprimé(s) · "
                                + "restant : " + sizeStr);
                Toast.makeText(DiagActivity.this,
                        finalDeleted + " fichier(s) supprimé(s) — restant : " + sizeStr,
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
}
