package com.byd.dashcast.report;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.byd.dashcast.R;
import com.byd.dashcast.util.AppLogger;

import com.google.android.material.button.MaterialButton;

import java.io.File;

/**
 * One-screen bug reporter. The user fills Title / Steps / Result; Device and
 * Version are filled automatically. On send, a bounded diagnostic snapshot is
 * captured (logcat ring buffer + dumpsys + journal) and the report is uploaded
 * straight to the DashCast Telegram support channel via the bot — or, when no
 * bot token is baked in, handed to the system share sheet.
 *
 * <p>Launched from the floating button (over any app) and the main-screen nav rail.
 */
public class BugReportActivity extends Activity {

    private static final String TAG = "BugReportActivity";

    private EditText       etTitle, etSteps, etResult;
    private TextView       tvMeta, tvStatus;
    private MaterialButton btnSend, btnCancel;
    private boolean        mSending;

    @SuppressLint("SetTextI18n") // device/version line is a locale-neutral identifier
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_bug_report);

        etTitle   = findViewById(R.id.et_bug_title);
        etSteps   = findViewById(R.id.et_bug_steps);
        etResult  = findViewById(R.id.et_bug_result);
        tvMeta    = findViewById(R.id.tv_bug_meta);
        tvStatus  = findViewById(R.id.tv_bug_status);
        btnSend   = findViewById(R.id.btn_bug_send);
        btnCancel = findViewById(R.id.btn_bug_cancel);

        // Device/version are locale-neutral technical identifiers.
        tvMeta.setText("📱 " + BugReportCapture.deviceLine()
                + "\n🔖 " + BugReportCapture.versionLine());

        btnCancel.setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> onSend());
    }

    private void onSend() {
        if (mSending) return;
        String title = etTitle.getText().toString().trim();
        if (TextUtils.isEmpty(title)) {
            etTitle.setError(getString(R.string.bug_title_required));
            etTitle.requestFocus();
            return;
        }

        mSending = true;
        btnSend.setEnabled(false);
        btnCancel.setEnabled(false);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.bug_status_capturing);

        final String caption = buildCaption(title);

        BugReportCapture.capture(this, caption, new BugReportCapture.Callback() {
            @Override public void onReady(File file) {
                if (TelegramBugReporter.isConfigured()) {
                    tvStatus.setText(R.string.bug_status_sending);
                    TelegramBugReporter.send(BugReportActivity.this, file, caption,
                            new TelegramBugReporter.Callback() {
                                @Override public void onSent() {
                                    Toast.makeText(BugReportActivity.this,
                                            R.string.bug_sent_ok, Toast.LENGTH_LONG).show();
                                    finish();
                                }
                                @Override public void onFailed(String message) {
                                    // Bot upload failed (no network to Telegram, etc.)
                                    // — fall back to the share sheet so nothing is lost.
                                    AppLogger.w(TAG, "bot send failed, share fallback: " + message);
                                    shareFallback(file);
                                }
                            });
                } else {
                    shareFallback(file);
                }
            }
            @Override public void onError(String message, File partial) {
                mSending = false;
                btnSend.setEnabled(true);
                btnCancel.setEnabled(true);
                tvStatus.setText(getString(R.string.bug_status_error_fmt, message));
                if (partial != null) shareFallback(partial);
            }
        });
    }

    private void shareFallback(File file) {
        try {
            AppLogger.shareFile(this, file, getString(R.string.bug_share_subject),
                    getString(R.string.bug_share_chooser));
        } catch (Exception e) {
            AppLogger.e(TAG, "share fallback failed", e);
            Toast.makeText(this, getString(R.string.bug_status_error_fmt, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /** Builds the Telegram caption / file header in the channel's expected format. */
    private String buildCaption(String title) {
        return "Title: " + title
                + "\nDevice: " + BugReportCapture.deviceLine()
                + "\nVersion: " + BugReportCapture.versionLine()
                + "\nSteps: " + textOr(etSteps, "-")
                + "\nResult: " + textOr(etResult, "-");
    }

    private static String textOr(EditText et, String fallback) {
        String s = et.getText().toString().trim();
        return s.isEmpty() ? fallback : s;
    }
}
