package com.byd.dashcast.report

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

import com.byd.dashcast.R
import com.byd.dashcast.util.AppLogger

import com.google.android.material.button.MaterialButton

import java.io.File

/**
 * One-screen bug reporter. The user fills Title / Steps / Result; Device and
 * Version are filled automatically. On send, a bounded diagnostic snapshot is
 * captured (logcat ring buffer + dumpsys + journal) and the report is uploaded
 * straight to the DashCast Telegram support channel via the bot — or, when no
 * bot token is baked in, handed to the system share sheet.
 *
 * Launched from the floating button (over any app) and the main-screen nav rail.
 */
class BugReportActivity : Activity() {

    private lateinit var etTitle: EditText
    private lateinit var etSteps: EditText
    private lateinit var etResult: EditText
    private lateinit var tvMeta: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnSend: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private var mSending = false

    @SuppressLint("SetTextI18n") // device/version line is a locale-neutral identifier
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_bug_report)

        etTitle = findViewById(R.id.et_bug_title)
        etSteps = findViewById(R.id.et_bug_steps)
        etResult = findViewById(R.id.et_bug_result)
        tvMeta = findViewById(R.id.tv_bug_meta)
        tvStatus = findViewById(R.id.tv_bug_status)
        btnSend = findViewById(R.id.btn_bug_send)
        btnCancel = findViewById(R.id.btn_bug_cancel)

        // Device/version are locale-neutral technical identifiers.
        tvMeta.text = "📱 " + BugReportCapture.deviceLine() +
                "\n🔖 " + BugReportCapture.versionLine()

        btnCancel.setOnClickListener { finish() }
        btnSend.setOnClickListener { onSend() }
    }

    private fun onSend() {
        if (mSending) return
        val title = etTitle.text.toString().trim()
        if (TextUtils.isEmpty(title)) {
            etTitle.error = getString(R.string.bug_title_required)
            etTitle.requestFocus()
            return
        }

        mSending = true
        btnSend.isEnabled = false
        btnCancel.isEnabled = false
        tvStatus.visibility = View.VISIBLE
        tvStatus.setText(R.string.bug_status_capturing)

        val caption = buildCaption(title)

        BugReportCapture.capture(this, caption, object : BugReportCapture.Callback {
            override fun onReady(file: File) {
                if (TelegramBugReporter.isConfigured()) {
                    tvStatus.setText(R.string.bug_status_sending)
                    TelegramBugReporter.send(this@BugReportActivity, file, caption,
                        object : TelegramBugReporter.Callback {
                            override fun onSent() {
                                Toast.makeText(this@BugReportActivity,
                                    R.string.bug_sent_ok, Toast.LENGTH_LONG).show()
                                finish()
                            }
                            override fun onFailed(message: String) {
                                // Bot upload failed (no network to Telegram, etc.)
                                // — fall back to the share sheet so nothing is lost.
                                AppLogger.w(TAG, "bot send failed, share fallback: $message")
                                shareFallback(file)
                            }
                        })
                } else {
                    shareFallback(file)
                }
            }

            override fun onError(message: String, partial: File?) {
                mSending = false
                btnSend.isEnabled = true
                btnCancel.isEnabled = true
                tvStatus.text = getString(R.string.bug_status_error_fmt, message)
                if (partial != null) shareFallback(partial)
            }
        })
    }

    private fun shareFallback(file: File) {
        try {
            AppLogger.shareFile(this, file, getString(R.string.bug_share_subject),
                getString(R.string.bug_share_chooser))
        } catch (e: Exception) {
            AppLogger.e(TAG, "share fallback failed", e)
            Toast.makeText(this, getString(R.string.bug_status_error_fmt, e.message.orEmpty()),
                Toast.LENGTH_LONG).show()
        }
        finish()
    }

    /** Builds the Telegram caption / file header in the channel's expected format. */
    private fun buildCaption(title: String): String {
        return "Title: " + title +
                "\nDevice: " + BugReportCapture.deviceLine() +
                "\nVersion: " + BugReportCapture.versionLine() +
                "\nSteps: " + textOr(etSteps, "-") +
                "\nResult: " + textOr(etResult, "-")
    }

    companion object {
        private const val TAG = "BugReportActivity"

        private fun textOr(et: EditText, fallback: String): String {
            val s = et.text.toString().trim()
            return if (s.isEmpty()) fallback else s
        }
    }
}
