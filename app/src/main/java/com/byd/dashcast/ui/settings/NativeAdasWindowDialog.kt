package com.byd.dashcast.ui.settings

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.R
import com.byd.dashcast.cluster.NativeAdasWindowControl
import com.byd.dashcast.cluster.ProjectionAdasCoordinator
import com.byd.dashcast.data.prefs.ClusterPrefs
import java.io.Closeable

/** Manual requests, deliberately independent of projection's existing ADAS geometry fix. */
internal class NativeAdasWindowDialog(
    private val activity: AppCompatActivity,
    private val control: NativeAdasWindowControl = NativeAdasWindowControl.get(activity),
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private var request: NativeAdasWindowControl.Request? = null
    private var closed = false
    private var pending = false
    private var preferencePending = false
    private var changingPreference = false
    private var generation = 0
    private val status = TextView(activity).apply {
        setText(R.string.native_adas_state_unknown)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val parked = CheckBox(activity).apply {
        setText(R.string.media_bench_parked)
        minHeight = dp(64)
    }
    private val hide = Button(activity).apply {
        setText(R.string.native_adas_hide)
        minHeight = dp(64)
        isEnabled = false
    }
    private val restore = Button(activity).apply {
        setText(R.string.native_adas_restore)
        minHeight = dp(64)
    }
    private val automatic = CheckBox(activity).apply {
        setText(R.string.native_adas_projection_option)
        minHeight = dp(64)
        isChecked = ClusterPrefs.isProjectionAdasEnabled(activity)
    }
    private val fullRestore = Button(activity).apply {
        setText(R.string.native_adas_projection_restore)
        minHeight = dp(64)
    }
    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
        addView(TextView(activity).apply { setText(R.string.native_adas_warning) })
        addView(parked)
        addView(automatic)
        addView(TextView(activity).apply { setText(R.string.native_adas_projection_help) })
        addView(fullRestore)
        addView(hide)
        addView(restore)
        addView(status)
    }
    private val dialog = AlertDialog.Builder(activity)
        .setTitle(R.string.settings_native_adas_panel)
        .setView(ScrollView(activity).apply { addView(body) })
        .setNegativeButton(R.string.resize_btn_close, null)
        .create()
    private val deadline = Runnable {
        request?.cancel()
        generation++
        pending = false
        status.setText(R.string.native_adas_send_failed)
        updateButtons()
    }

    fun show() {
        dialog.setOnDismissListener { close() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minHeight = dp(48) }
        parked.setOnCheckedChangeListener { _, _ -> updateButtons() }
        hide.setOnClickListener { if (parked.isChecked) send(NativeAdasWindowControl.Action.HIDE) }
        restore.setOnClickListener { send(NativeAdasWindowControl.Action.RESTORE) }
        automatic.setOnCheckedChangeListener { _, enabled ->
            if (changingPreference || preferencePending) return@setOnCheckedChangeListener
            if (enabled && !parked.isChecked) {
                changingPreference = true
                automatic.isChecked = ClusterPrefs.isProjectionAdasEnabled(activity)
                changingPreference = false
                return@setOnCheckedChangeListener
            }
            preferencePending = true
            updateButtons()
            kotlin.concurrent.thread(name = "adas-preference") {
                val saved = ClusterPrefs.setProjectionAdasEnabled(activity.applicationContext, enabled)
                handler.post {
                    preferencePending = false
                    if (saved) ProjectionAdasCoordinator.preferenceChanged(activity)
                    if (!closed) {
                        changingPreference = true
                        automatic.isChecked = ClusterPrefs.isProjectionAdasEnabled(activity)
                        changingPreference = false
                        status.setText(if (saved) R.string.native_adas_projection_help else R.string.native_adas_preference_failed)
                        parked.isChecked = false
                        updateButtons()
                    }
                }
            }
        }
        fullRestore.setOnClickListener {
            request?.cancel()
            pending = true
            parked.isChecked = false
            updateButtons()
            status.setText(R.string.native_adas_pending)
            ProjectionAdasCoordinator.restore(activity) { ok ->
                pending = false
                if (!closed) {
                    updateButtons()
                    status.setText(if (ok) R.string.native_adas_restore_sent else R.string.native_adas_send_failed)
                }
            }
        }
        updateButtons()
        dialog.show()
    }

    private fun send(action: NativeAdasWindowControl.Action) {
        if (closed || pending) return
        pending = true
        val current = ++generation
        parked.isChecked = false
        status.setText(R.string.native_adas_pending)
        updateButtons()
        handler.postDelayed(deadline, 15_000)
        request = control.request(action) { result -> handler.post {
            if (closed || !pending || generation != current) return@post
            handler.removeCallbacks(deadline)
            pending = false
            status.setText(when (result) {
                NativeAdasWindowControl.Result.HIDE_SENT -> R.string.native_adas_hide_sent
                NativeAdasWindowControl.Result.RESTORE_SENT -> R.string.native_adas_restore_sent
                NativeAdasWindowControl.Result.UNAVAILABLE -> R.string.native_adas_unavailable
                NativeAdasWindowControl.Result.ORIGINAL_UNKNOWN -> R.string.native_adas_original_unknown
                NativeAdasWindowControl.Result.RECOVERY_MISSING -> R.string.native_adas_recovery_missing
                NativeAdasWindowControl.Result.RECOVERY_FAILED -> R.string.native_adas_recovery_failed
                NativeAdasWindowControl.Result.BUSY -> R.string.native_adas_unavailable
                else -> R.string.native_adas_send_failed
            })
            parked.isChecked = false
            updateButtons()
        } }
    }

    private fun updateButtons() {
        hide.isEnabled = !pending && !preferencePending && parked.isChecked
        restore.isEnabled = !pending && !preferencePending
        parked.isEnabled = !pending
        automatic.isEnabled = !pending && !preferencePending && (automatic.isChecked || parked.isChecked)
        fullRestore.isEnabled = !pending && !preferencePending
    }

    override fun close() {
        if (closed) return
        closed = true
        request?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (dialog.isShowing) dialog.dismiss()
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
