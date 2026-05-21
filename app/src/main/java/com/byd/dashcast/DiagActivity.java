package com.byd.dashcast;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.byd.dashcast.beta.BetaConfig;
import com.byd.dashcast.beta.BetaTestRunner;
import com.byd.dashcast.beta.BetaTestRunner.TestDef;
import com.byd.dashcast.beta.BetaTestRunner.TestResult;
import com.byd.dashcast.beta.BetaTestRunner.Status;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
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

    private TabLayout    tabs;
    private View         panelBeta;
    private View         panelComingSoon;

    // Beta panel views
    private TextView       tvBetaStatusA;
    private TextView       tvBetaStatusB;
    private TextView       tvBetaCounters;
    private MaterialButton btnRunAll;
    private MaterialButton btnCopyReport;
    private LinearLayout   llTestList;

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
        prepareTestRows();
        updateStatusPills();
        // Default to Beta Engine tab for the 1.1.0-beta release; users can switch.
        tabs.selectTab(tabs.getTabAt(TAB_BETA_ENGINE));
        showPanelForTab(TAB_BETA_ENGINE);
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
        panelComingSoon = findViewById(R.id.panel_coming_soon);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab)   { showPanelForTab(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void showPanelForTab(int position) {
        boolean isBeta = position == TAB_BETA_ENGINE;
        panelBeta.setVisibility(isBeta ? View.VISIBLE : View.GONE);
        panelComingSoon.setVisibility(isBeta ? View.GONE : View.VISIBLE);
        if (!isBeta) {
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
}
