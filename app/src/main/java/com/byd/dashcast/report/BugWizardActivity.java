package com.byd.dashcast.report;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
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
import java.util.ArrayList;

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
    private static final String PREFS_BUG    = "dashcast_bug_report";
    private static final String PREF_TG_HANDLE = "tg_handle";

    // Category indices — must match bug_categories string-array order.
    private static final int CAT_MIRROR  = 0;
    private static final int CAT_APP     = 1;
    private static final int CAT_SOUND   = 2;
    private static final int CAT_CONNECT = 3;
    private static final int CAT_FREEZE  = 4;
    private static final int CAT_SIMPLE  = 5;
    private static final int CAT_OTHER   = 6;

    private static final String[] CAT_EMOJIS = {"📺", "📱", "🔊", "🔗", "❄️", "🖥️", "❓"};

    private static final int[] ISSUE_ARRAYS = {
        R.array.bug_issues_mirror,
        R.array.bug_issues_app,
        R.array.bug_issues_sound,
        R.array.bug_issues_connect,
        R.array.bug_issues_freeze,
        R.array.bug_issues_simple,
        R.array.bug_issues_other,
    };

    // Views
    private ViewFlipper    mFlipper;
    private TextView       mTvTitle;
    private TextView       mTvStatus;
    private TextView       mTvTgBanner;
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
    private String  mTgHandle = "";

    // Step 2 (issue) — selection + optional free-text details, sent via an explicit button.
    private String  mSelectedIssue = null;
    private EditText mDetailsField  = null;
    private MaterialButton mBtnSend  = null;
    private TextView mTvSelected     = null;
    private final ArrayList<MaterialButton> mIssueButtons = new ArrayList<>();

    // Cluster app detection
    private String  mDetectedPkg   = null;
    private String  mDetectedLabel = null;
    private boolean mDetectionDone = false;
    private boolean mAppPagePending = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_bug_wizard);

        mFlipper    = findViewById(R.id.bug_wizard_flipper);
        mTvTitle    = findViewById(R.id.tv_wizard_title);
        mTvStatus   = findViewById(R.id.tv_wizard_status);
        mTvTgBanner = findViewById(R.id.tv_tg_handle_banner);
        mBtnBack    = findViewById(R.id.btn_wizard_back);
        mGridCat    = findViewById(R.id.grid_wizard_categories);
        mLlApps     = findViewById(R.id.ll_wizard_apps);
        mLlIssues   = findViewById(R.id.ll_wizard_issues);
        mDots       = new TextView[]{
            findViewById(R.id.dot_wizard_1),
            findViewById(R.id.dot_wizard_2),
            findViewById(R.id.dot_wizard_3)
        };

        findViewById(R.id.btn_wizard_cancel).setOnClickListener(v -> finish());
        mBtnBack.setOnClickListener(v -> goBack());
        mTvTgBanner.setOnClickListener(v -> showTgHandleDialog());

        mTgHandle = loadTgHandle();
        if (mTgHandle.isEmpty()) {
            // First use: block on dialog before showing wizard
            showTgHandleDialogThen(() -> {
                buildCategoryPage();
                showStep(0);
                detectClusterApp();
            });
        } else {
            buildCategoryPage();
            showStep(0);
            detectClusterApp();
        }
        updateTgBanner();
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
        mIssueButtons.clear();
        mSelectedIssue = null;

        // Issue chips: a tap now just selects (highlights) the issue; the report is sent
        // by the explicit "Send" button below, so the optional free-text can be filled first.
        String[] issues = getResources().getStringArray(ISSUE_ARRAYS[mCategory]);
        for (String issue : issues) {
            MaterialButton btn = makeOutlinedButton(issue);
            btn.setCheckable(true);
            btn.setOnClickListener(v -> onIssuePicked(issue, btn));
            mLlIssues.addView(btn);
            mIssueButtons.add(btn);
        }

        // ── Optional free-text details (the user can add anything in their own words) ──
        TextView lbl = new TextView(this);
        lbl.setText(R.string.bug_wizard_details_label);
        lbl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lblLp.setMargins(0, dp(18), 0, dp(4));
        lbl.setLayoutParams(lblLp);
        mLlIssues.addView(lbl);

        mDetailsField = new EditText(this);
        mDetailsField.setHint(R.string.bug_wizard_details_hint);
        mDetailsField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mDetailsField.setMinLines(2);
        mDetailsField.setMaxLines(5);
        mDetailsField.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mDetailsField.setLayoutParams(etLp);
        mLlIssues.addView(mDetailsField);

        // ── Selected indicator + Send button ──
        mTvSelected = new TextView(this);
        mTvSelected.setText(R.string.bug_wizard_pick_issue);
        mTvSelected.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        selLp.setMargins(0, dp(12), 0, dp(4));
        mTvSelected.setLayoutParams(selLp);
        mLlIssues.addView(mTvSelected);

        mBtnSend = new MaterialButton(this); // filled style (default)
        mBtnSend.setText(R.string.bug_wizard_send);
        mBtnSend.setMinimumHeight(dp(64));
        mBtnSend.setEnabled(false);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sendLp.setMargins(0, dp(8), 0, dp(8));
        mBtnSend.setLayoutParams(sendLp);
        mBtnSend.setOnClickListener(v -> submitReport());
        mLlIssues.addView(mBtnSend);
    }

    /** Marks the picked issue chip (single-choice) and enables the Send button. */
    private void onIssuePicked(String issue, MaterialButton picked) {
        if (mSending) return;
        mSelectedIssue = issue;
        for (MaterialButton b : mIssueButtons) {
            b.setChecked(b == picked);
        }
        mTvSelected.setText(getString(R.string.bug_wizard_selected_fmt, issue));
        mBtnSend.setEnabled(true);
    }

    private void submitReport() {
        if (mSending || mSelectedIssue == null) return;
        mSending = true;
        mBtnSend.setEnabled(false);
        mBtnBack.setEnabled(false);
        mTvStatus.setVisibility(View.VISIBLE);
        mTvStatus.setText(R.string.bug_status_capturing);

        String details = (mDetailsField != null) ? mDetailsField.getText().toString().trim() : "";
        String[] cats = getResources().getStringArray(R.array.bug_categories);
        String caption = "Category: " + cats[mCategory]
                + "\nApp: " + (mAppPkg.isEmpty() ? mAppLabel
                               : mAppLabel + " (" + mAppPkg + ")")
                + "\nIssue: " + mSelectedIssue
                + (details.isEmpty() ? "" : "\nDetails: " + details)
                + "\nDevice: " + BugReportCapture.deviceLine()
                + "\nVersion: " + BugReportCapture.versionLine()
                + (mTgHandle.isEmpty() ? "" : "\nTelegram: " + mTgHandle);

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
                if (mBtnSend != null) mBtnSend.setEnabled(true);
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

    // ── Telegram handle ───────────────────────────────────────────────────────

    private String loadTgHandle() {
        return getSharedPreferences(PREFS_BUG, MODE_PRIVATE)
                .getString(PREF_TG_HANDLE, "");
    }

    private void saveTgHandle(String handle) {
        getSharedPreferences(PREFS_BUG, MODE_PRIVATE)
                .edit().putString(PREF_TG_HANDLE, handle).apply();
    }

    private void updateTgBanner() {
        if (mTgHandle.isEmpty()) {
            mTvTgBanner.setText(R.string.bug_tg_banner_unset);
            mTvTgBanner.setTextColor(
                    getResources().getColor(android.R.color.holo_orange_dark, getTheme()));
        } else {
            mTvTgBanner.setText(getString(R.string.bug_tg_banner_set, mTgHandle));
            mTvTgBanner.setTextColor(
                    getResources().getColor(R.color.md_on_surface_variant, getTheme()));
        }
    }

    /** Shows the dialog with the full explanation message (first use). Calls {@code then} on confirm or skip. */
    private void showTgHandleDialogThen(Runnable then) {
        showTgHandleDialogInternal(then);
    }

    /** Shows the dialog for subsequent edits (no mandatory callback). */
    private void showTgHandleDialog() {
        showTgHandleDialogInternal(null);
    }

    private void showTgHandleDialogInternal(Runnable onDismiss) {
        EditText et = new EditText(this);
        et.setHint(R.string.bug_tg_hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setSingleLine(true);
        if (!mTgHandle.isEmpty()) et.setText(mTgHandle);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        container.setPadding(pad, dp(8), pad, dp(4));
        container.addView(et);

        new AlertDialog.Builder(this)
                .setTitle(R.string.bug_tg_title)
                .setMessage(R.string.bug_tg_message)
                .setView(container)
                .setPositiveButton(R.string.bug_tg_confirm, (d, w) -> {
                    String raw = et.getText().toString().trim();
                    if (!raw.isEmpty() && !raw.startsWith("@")) raw = "@" + raw;
                    mTgHandle = raw;
                    saveTgHandle(mTgHandle);
                    updateTgBanner();
                    if (onDismiss != null) onDismiss.run();
                })
                .setNegativeButton(R.string.bug_tg_skip, (d, w) -> {
                    if (onDismiss != null) onDismiss.run();
                })
                .setCancelable(false)
                .show();
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
