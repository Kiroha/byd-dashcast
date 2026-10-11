package com.byd.dashcast.media

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.R
import java.io.Closeable

/** Bounded benign test, using the same ownership gate/writer as production; no nav-mode writes. */
class MediaBenchDialog internal constructor(
    private val activity: AppCompatActivity,
    private val ownership: MediaOutputOwnership,
    private val automaticEnabled: () -> Boolean,
) : Closeable {
    constructor(activity: AppCompatActivity) : this(activity, ClusterMediaCoordinator.ownership,
        { MediaPrefs.isEnabled(activity) })
    private val handler = Handler(Looper.getMainLooper())
    private val token = Any()
    private var acquired = false
    private var closed = false
    private var runGeneration = 0L
    private val deadline = Runnable { release() }
    private val status = TextView(activity).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val cleanupStatus = TextView(activity).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val parked = CheckBox(activity).apply {
        setText(R.string.media_bench_parked)
        minHeight = dp(64)
    }
    private val modeButtons = listOf(
        R.string.media_bench_mode_reference,
        R.string.media_bench_mode_short,
        R.string.media_bench_mode_minimal,
    ).map { label ->
        RadioButton(activity).apply {
            id = View.generateViewId()
            setText(label)
            minHeight = dp(48)
            layoutParams = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    private val modes = RadioGroup(activity).apply {
        orientation = RadioGroup.VERTICAL
        modeButtons.forEach { addView(it) }
        check(modeButtons.first().id)
    }
    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
        addView(TextView(activity).apply { setText(R.string.media_bench_warning) })
        addView(TextView(activity).apply { setText(R.string.media_bench_layout_help) })
        addView(modes)
        addView(parked)
        addView(status)
        addView(cleanupStatus)
    }
    private val dialog = AlertDialog.Builder(activity)
        .setTitle(R.string.media_bench_title)
        .setView(ScrollView(activity).apply { addView(body) })
        .setPositiveButton(R.string.media_bench_send, null)
        .setNegativeButton(android.R.string.cancel) { _, _ -> close() }
        .create()

    fun show() {
        dialog.setOnDismissListener { close() }
        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            send.minHeight = dp(48)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minHeight = dp(48)
            send.isEnabled = false
            parked.setOnCheckedChangeListener { _, checked -> send.isEnabled = checked && !acquired }
            send.setOnClickListener {
                if (!parked.isChecked || closed) return@setOnClickListener
                if (automaticEnabled() || !ownership.acquire(token)) {
                    status.setText(R.string.media_bench_busy)
                    return@setOnClickListener
                }
                acquired = true
                val generation = ++runGeneration
                // Capture the choice once. The OEM centers the combined icon/text group in
                // some themes; shorter text may move it right, but this is a comparison only.
                val (title, artist) = when (modes.checkedRadioButtonId) {
                    modeButtons[1].id -> "DashCast" to ""
                    modeButtons[2].id -> "DC" to ""
                    else -> "DashCast test" to "DashCast"
                }
                send.isEnabled = false
                parked.isEnabled = false
                modeButtons.forEach { it.isEnabled = false }
                status.setText(R.string.media_bench_waiting)
                cleanupStatus.text = ""
                // Independent wall-clock bound: a missing callback or blocked Binder must not
                // keep the bench owner alive. An already executing Binder transaction cannot be
                // cancelled, but release invalidates every queued/delayed suffix immediately.
                handler.postDelayed(deadline, 6000)
                ownership.publish(token,
                    ClusterMediaData(token, title, artist, true)) { result ->
                    handler.post {
                        if (closed || !acquired || runGeneration != generation) return@post
                        status.setText(if (result == MediaWriteResult.ACCEPTED)
                            R.string.media_bench_accepted else R.string.media_bench_unavailable)
                        if (result != MediaWriteResult.ACCEPTED) release()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun release() {
        if (!acquired) return
        handler.removeCallbacks(deadline)
        acquired = false
        val generation = runGeneration
        ownership.release(token) { result ->
            handler.post {
                if (!closed && runGeneration == generation) cleanupStatus.setText(when (result) {
                    MediaWriteResult.CLEARED -> R.string.media_bench_cleared
                    MediaWriteResult.NOTHING_TO_CLEAR -> R.string.media_bench_nothing
                    else -> R.string.media_bench_clear_failed
                })
                if (!closed && runGeneration == generation) {
                    parked.isEnabled = true
                    parked.isChecked = false // Every new run requires a fresh parked acknowledgement.
                    modeButtons.forEach { it.isEnabled = true }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        release()
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
