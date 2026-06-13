package com.byd.dashcast.report;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import com.byd.dashcast.R;
import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;

import com.google.android.material.button.MaterialButton;

import java.io.File;

/**
 * Keyboard-free, 3-step bug reporter designed for in-car use.
 *
 * <p>Step 1 — Category (6 large tiles, 2-column grid, tap only)
 * <p>Step 2 — App (auto-detected from cluster display + "None" + "Other")
 * <p>Step 3 — Issue (5 context-sensitive chips from string-arrays)
 *
 * <p>On finish, captures a bounded log snapshot and uploads it straight to
 * the DashCast support Telegram channel. No text keyboard is ever shown.
 */
public class BugWizardActivity extends Activity {

    private static final String TAG = "BugWizardActivity";

    // Category indices — must match bug_categories string-array order.
    private static final int CAT_MIRROR  = 0;
    private static final int CAT_APP     = 1;
    private static final int CAT_SOUND   = 2;
    private static final int CAT_CONNECT = 3;
    private static final int CAT_FREEZE  = 4;
    private static final int CAT_OTHER   = 5;

    private static final String[] CAT_EMOJIS = {"📺", "📱", "🔊", "🔗", "❄️", "❓"};

    private static final int[] ISSUE_ARRAYS = {
        R.array.bug_issues_mirror,
        R.array.bug_issues_app,
        R.array.bug_issues_sound,
        R.array.bug_issues_connect,
        R.array.bug_issues_freeze,
        R.array.bug_issues_other,
    };

    // Views
    private ViewFlipper    mFlipper;
    private TextView       mTvTitle;
    private TextView       mTvStatus;
    private MaterialButton mBtnBack;
    private TextView[]     mDots;
    private GridLayout     mGridCat;
    private LinearLayout   mLlApps;
    private LinearLayout   mLlIssues;

    // State
    private int     mCategory = -1;
    private String  mAppPkg   = "";
    private String  mAppLabel = "";
    private boolean mSending  = false;

    // Cluster app detection
    private String  mDetectedPkg   = null;
    private String  mDetectedLabel = null;
    private boolean mDetectionDone = false;
    private boolean mAppPagePending = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_bug_wizard);

        mFlipper  = findViewById(R.id.bug_wizard_flipper);
        mTvTitle  = findViewById(R.id.tv_wizard_title);
        mTvStatus = findViewById(R.id.tv_wizard_status);
        mBtnBack  = findViewById(R.id.btn_wizard_back);
        mGridCat  = findViewById(R.id.grid_wizard_categories);
        mLlApps   = findViewById(R.id.ll_wizard_apps);
        mLlIssues = findViewById(R.id.ll_wizard_issues);
        mDots     = new TextView[]{
            findViewById(R.id.dot_wizard_1),
            findViewById(R.id.dot_wizard_2),
            findViewById(R.id.dot_wizard_3)
        };

        findViewById(R.id.btn_wizard_cancel).setOnClickListener(v -> finish());
        mBtnBack.setOnClickListener(v -> goBack());

        buildCategoryPage();
        showStep(0);
        detectClusterApp();
    }

    // ── Step 0: category ─────────────────────────────────────────────────────

    private void buildCategoryPage() {
        String[] cats = getResources().getStringArray(R.array.bug_categories);
        mGridCat.removeAllViews();
        for (int i = 0; i < cats.length; i++) {
            final int idx = i;
            MaterialButton btn = makeOutlinedButton(CAT_EMOJIS[i] + "  " + cats[i]);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.columnSpec = GridLayout.spec(i % 2, 1f);
            lp.rowSpec    = GridLayout.spec(GridLayout.UNDEFINED);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            lp.width = 0;
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> selectCategory(idx));
            mGridCat.addView(btn);
        }
    }

    private void selectCategory(int cat) {
        mCategory = cat;
        buildAppPage();
        showStep(1);
    }

    // ── Step 1: app ──────────────────────────────────────────────────────────

    private void buildAppPage() {
        mLlApps.removeAllViews();
        if (mDetectionDone) {
            populateAppButtons();
        } else {
            TextView tv = new TextView(this);
            tv.setText(R.string.bug_wizard_detecting);
            tv.setTextColor(getResources().getColor(R.color.md_on_surface_variant, getTheme()));
            tv.setPadding(0, dp(16), 0, dp(16));
            mLlApps.addView(tv);
            mAppPagePending = true;
        }
    }

    private void detectClusterApp() {
        // Parse foreground activity on displayId=1 (cluster display).
        String cmd = "dumpsys activity activities"
                + " | grep -E 'displayId=1' -A 10"
                + " | grep 'realActivity'"
                + " | head -1";
        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                String pkg   = parseRealActivity(out.trim());
                String label = pkg != null ? labelFor(pkg) : null;
                runOnUiThread(() -> onDetectionResult(pkg, label));
            }
            @Override public void onError(String err) {
                runOnUiThread(() -> onDetectionResult(null, null));
            }
        });
    }

    private void onDetectionResult(String pkg, String label) {
        mDetectedPkg   = pkg   != null ? pkg   : "";
        mDetectedLabel = label != null ? label : "";
        mDetectionDone = true;
        if (mAppPagePending) {
            mAppPagePending = false;
            mLlApps.removeAllViews();
            populateAppButtons();
        }
    }

    private void populateAppButtons() {
        // 1) Detected cluster app (highlighted with primary-color stroke).
        if (!mDetectedPkg.isEmpty()) {
            String lbl = mDetectedLabel.isEmpty() ? mDetectedPkg : mDetectedLabel;
            MaterialButton btn = makeOutlinedButton("🎯  " + lbl);
            btn.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(R.color.md_primary, getTheme())));
            btn.setStrokeWidth(dp(2));
            btn.setOnClickListener(v -> selectApp(mDetectedPkg, lbl));
            mLlApps.addView(btn);
        }

        // 2) "No specific app" (system / general issue).
        String noApp = getString(R.string.bug_wizard_no_app);
        MaterialButton btnNone = makeOutlinedButton("—  " + noApp);
        btnNone.setOnClickListener(v -> selectApp("", noApp));
        mLlApps.addView(btnNone);

        // 3) "Other / Unknown".
        String otherApp = getString(R.string.bug_wizard_other_app);
        MaterialButton btnOther = makeOutlinedButton("❓  " + otherApp);
        btnOther.setOnClickListener(v -> selectApp("other", otherApp));
        mLlApps.addView(btnOther);
    }

    private void selectApp(String pkg, String label) {
        mAppPkg   = pkg;
        mAppLabel = label;
        buildIssuePage();
        showStep(2);
    }

    // ── Step 2: issue ─────────────────────────────────────────────────────────

    private void buildIssuePage() {
        mLlIssues.removeAllViews();
        String[] issues = getResources().getStringArray(ISSUE_ARRAYS[mCategory]);
        for (String issue : issues) {
            MaterialButton btn = makeOutlinedButton(issue);
            btn.setOnClickListener(v -> selectIssue(issue));
            mLlIssues.addView(btn);
        }
    }

    private void selectIssue(String issue) {
        if (mSending) return;
        mSending = true;
        mBtnBack.setEnabled(false);
        mTvStatus.setVisibility(View.VISIBLE);
        mTvStatus.setText(R.string.bug_status_capturing);

        String[] cats = getResources().getStringArray(R.array.bug_categories);
        String caption = "Category: " + cats[mCategory]
                + "\nApp: " + (mAppPkg.isEmpty() ? mAppLabel
                               : mAppLabel + " (" + mAppPkg + ")")
                + "\nIssue: " + issue
                + "\nDevice: " + BugReportCapture.deviceLine()
                + "\nVersion: " + BugReportCapture.versionLine();

        BugReportCapture.capture(this, caption, new BugReportCapture.Callback() {
            @Override public void onReady(File file) {
                if (TelegramBugReporter.isConfigured()) {
                    mTvStatus.setText(R.string.bug_status_sending);
                    TelegramBugReporter.send(BugWizardActivity.this, file, caption,
                            new TelegramBugReporter.Callback() {
                                @Override public void onSent() {
                                    Toast.makeText(BugWizardActivity.this,
                                            R.string.bug_sent_ok, Toast.LENGTH_LONG).show();
                                    finish();
                                }
                                @Override public void onFailed(String msg) {
                                    AppLogger.w(TAG, "bot upload failed: " + msg);
                                    shareFallback(file);
                                }
                            });
                } else {
                    shareFallback(file);
                }
            }
            @Override public void onError(String msg, File partial) {
                mSending = false;
                mBtnBack.setEnabled(true);
                mTvStatus.setText(getString(R.string.bug_status_error_fmt, msg));
                if (partial != null) shareFallback(partial);
            }
        });
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void showStep(int step) {
        mFlipper.setDisplayedChild(step);
        int[] titleIds = {
            R.string.bug_wizard_step_category,
            R.string.bug_wizard_step_app,
            R.string.bug_wizard_step_issue
        };
        mTvTitle.setText(titleIds[step]);
        mBtnBack.setVisibility(step > 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < mDots.length; i++) {
            mDots[i].setAlpha(i == step ? 1f : 0.3f);
        }
    }

    private void goBack() {
        if (mSending) return;
        int cur = mFlipper.getDisplayedChild();
        if (cur > 0) showStep(cur - 1);
        else finish();
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MaterialButton makeOutlinedButton(String text) {
        MaterialButton btn = new MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(text);
        btn.setMinimumHeight(dp(72));
        btn.setPadding(dp(16), dp(10), dp(16), dp(10));
        btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Parses "realActivity=com.waze/.FreeMapAppActivity" → "com.waze". */
    private static String parseRealActivity(String line) {
        int eq = line.indexOf("realActivity=");
        if (eq < 0) return null;
        String s = line.substring(eq + "realActivity=".length()).trim();
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : (s.isEmpty() ? null : s);
    }

    private String labelFor(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void shareFallback(File file) {
        try {
            AppLogger.shareFile(this, file,
                    getString(R.string.bug_share_subject),
                    getString(R.string.bug_share_chooser));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.bug_status_error_fmt, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
