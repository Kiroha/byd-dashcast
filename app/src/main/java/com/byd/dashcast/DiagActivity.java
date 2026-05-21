package com.byd.dashcast;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
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
    private static final int TAB_STRESS      = 4;
    private static final int TAB_BETA_ENGINE = 5;
    private static final int TAB_DILINK5     = 6;

    private TabLayout    tabs;
    private View         panelBeta;
    private View         panelDl5;
    private View         panelComingSoon;

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
        prepareTestRows();
        prepareDl5TestRows();
        updateStatusPills();
        // Default tab: DiLink 5 when auto-detected as DL5, Beta Engine otherwise.
        int defaultTab = Platform.get().isAutoDetectedDiLink5() ? TAB_DILINK5 : TAB_BETA_ENGINE;
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
        panelComingSoon = findViewById(R.id.panel_coming_soon);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab)   { showPanelForTab(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void showPanelForTab(int position) {
        boolean isBeta = position == TAB_BETA_ENGINE;
        boolean isDl5  = position == TAB_DILINK5;
        panelBeta.setVisibility(isBeta ? View.VISIBLE : View.GONE);
        panelDl5.setVisibility(isDl5 ? View.VISIBLE : View.GONE);
        panelComingSoon.setVisibility((isBeta || isDl5) ? View.GONE : View.VISIBLE);
        if (!isBeta && !isDl5) {
            TextView title = panelComingSoon.findViewById(R.id.tv_coming_soon_title);
            int titleRes;
            switch (position) {
                case TAB_CLUSTER:   titleRes = R.string.diag_tab_cluster;   break;
                case TAB_DISPLAY:   titleRes = R.string.diag_tab_display;   break;
                case TAB_ADB_LOCAL: titleRes = R.string.diag_tab_adb_local; break;
                case TAB_SYSTEM:    titleRes = R.string.diag_tab_system;    break;
                case TAB_STRESS:    titleRes = R.string.diag_tab_stress;    break;
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
}
