package com.byd.dashcast.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Toast

import androidx.core.view.isVisible

import com.byd.dashcast.R
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.ime.ClusterImeWatcherService
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.util.AppLogger

/**
 * Owns the two onboarding banners:
 *  - IME / Accessibility service banner (DL5 only) — `card_ime_a11y_banner`
 *  - HUD notification-listener banner (all devices) — `card_hud_notif_banner`
 *
 * Call [refresh] from `onStart` / `onResume` to recompute visibility.
 *
 * Kotlin port note: every `$` in the accessibility shell script below is escaped as `\$`.
 * CURRENT, COMP and NEW are SHELL variables and all three are valid Kotlin identifiers, so an
 * unescaped port would interpolate them into nothing and silently ship a broken command. Same
 * for the `Settings$AccessibilitySettingsActivity` component name.
 */
class PermissionBannerCoordinator(
        private val mCardIme: View?,
        private val mCardHudNotif: View?,
        private val mHost: Host
) {

    interface Host {
        fun getContext(): Context
        // Named 'runnable' to agree with SplitController.Host: MainActivity implements BOTH
        // interfaces and satisfies this method by inheriting Activity.runOnUiThread, so the two
        // declarations must not disagree on the parameter name.
        fun runOnUiThread(runnable: Runnable)
        /** Returns false once the host Activity is finishing or destroyed. */
        fun isActivityAlive(): Boolean
        fun startActivity(intent: Intent)
    }

    init {
        try { setupIme() } catch (t: Throwable) { AppLogger.e(TAG, "setupIme failed", t) }
        try { setupHudNotif() } catch (t: Throwable) { AppLogger.e(TAG, "setupHudNotif failed", t) }
    }

    /** Recomputes both banners' visibility. Safe to call repeatedly from lifecycle methods. */
    fun refresh() {
        try { refreshIme() } catch (t: Throwable) { AppLogger.e(TAG, "refreshIme failed", t) }
        try { refreshHudNotif() } catch (t: Throwable) { AppLogger.e(TAG, "refreshHudNotif failed", t) }
    }

    // ── IME / A11Y banner ─────────────────────────────────────────────────────

    private fun setupIme() {
        val card = mCardIme ?: return
        val btnEnable: View? = card.findViewById(R.id.btn_ime_banner_enable)
        val btnLater: View? = card.findViewById(R.id.btn_ime_banner_later)
        val btnDismiss: View? = card.findViewById(R.id.btn_ime_banner_dismiss)

        btnEnable?.setOnClickListener { v -> enableImeA11yOneClick(v) }
        btnLater?.setOnClickListener { card.visibility = View.GONE }
        btnDismiss?.setOnClickListener {
            try { ClusterPrefs.setImeBannerDismissed(mHost.getContext()) } catch (ignored: Throwable) {}
            card.visibility = View.GONE
        }
        refreshIme()
    }

    private fun refreshIme() {
        val card = mCardIme ?: return
        var shouldShow = false
        try {
            if (Platform.get().isDiLink5(mHost.getContext())) {
                val dismissed = ClusterPrefs.isImeBannerDismissed(mHost.getContext())
                val enabled = ClusterImeWatcherService.isEnabled(mHost.getContext())
                shouldShow = !dismissed && !enabled
                if (enabled && card.isVisible) {
                    try {
                        Toast.makeText(mHost.getContext(), R.string.ime_banner_toast_enabled,
                                Toast.LENGTH_SHORT).show()
                    } catch (ignored: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "refreshIme inner failed", t)
        }
        card.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun enableImeA11yOneClick(btnEnable: View?) {
        btnEnable?.isEnabled = false
        try {
            val comp = "com.byd.dashcast/com.byd.dashcast.ime.ClusterImeWatcherService"
            // Every \$ below is a SHELL reference, not a Kotlin template.
            val cmd =
                    "COMP='" + comp + "'; " +
                    "CURRENT=\$(settings get secure enabled_accessibility_services 2>/dev/null); " +
                    "if [ \"\$CURRENT\" = \"null\" ] || [ -z \"\$CURRENT\" ]; then " +
                      "NEW=\"\$COMP\"; " +
                    "elif echo \"\$CURRENT\" | grep -q \"\$COMP\"; then " +
                      "NEW=\"\$CURRENT\"; " +
                    "else " +
                      "NEW=\"\$CURRENT:\$COMP\"; " +
                    "fi; " +
                    "settings put secure enabled_accessibility_services \"\$NEW\"; " +
                    "settings put secure accessibility_enabled 1; " +
                    "echo OUT=\$(settings get secure enabled_accessibility_services)"

            ShellGateway.execShellWithResult(mHost.getContext(), cmd, object : AdbLocalClient.Callback {
                // String? per the Batch 0 pin: AdbLocalClient.Callback is ported last and will
                // declare String?; a non-null override here would become an illegal narrowing.
                override fun onSuccess(out: String?) {
                    mHost.runOnUiThread {
                        if (!mHost.isActivityAlive()) return@runOnUiThread
                        val ok = ClusterImeWatcherService.isEnabled(mHost.getContext())
                        if (ok) {
                            AppLogger.i(TAG, "IME a11y enabled via shell ✓")
                            try {
                                Toast.makeText(mHost.getContext(),
                                        R.string.ime_banner_toast_enabled,
                                        Toast.LENGTH_SHORT).show()
                            } catch (ignored: Throwable) {}
                            mCardIme?.visibility = View.GONE
                            btnEnable?.isEnabled = true
                        } else {
                            AppLogger.w(TAG, "shell OK but a11y not enabled — fallback. report=" + out)
                            openA11ySettingsFallback()
                            btnEnable?.isEnabled = true
                        }
                    }
                }
                override fun onError(err: String?) {
                    mHost.runOnUiThread {
                        if (!mHost.isActivityAlive()) return@runOnUiThread
                        AppLogger.w(TAG, "one-click a11y shell failed: " + err + " — fallback")
                        openA11ySettingsFallback()
                        btnEnable?.isEnabled = true
                    }
                }
            })
        } catch (t: Throwable) {
            AppLogger.e(TAG, "enableImeA11yOneClick threw", t)
            openA11ySettingsFallback()
            btnEnable?.isEnabled = true
        }
    }

    private fun openA11ySettingsFallback() {
        try {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mHost.startActivity(i)
            return
        } catch (t: Throwable) {
            AppLogger.w(TAG, "ACTION_ACCESSIBILITY_SETTINGS unavailable: " + t.message)
        }
        try {
            val i = Intent()
            // \$ escape: this is a JVM nested-class binary name, not a Kotlin template.
            i.component = ComponentName("com.android.settings",
                    "com.android.settings.Settings\$AccessibilitySettingsActivity")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mHost.startActivity(i)
            return
        } catch (t: Throwable) {
            AppLogger.w(TAG, "direct AccessibilitySettingsActivity unavailable: " + t.message)
        }
        try {
            val i = Intent(Settings.ACTION_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mHost.startActivity(i)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "no Settings activity reachable on this ROM", t)
            try {
                Toast.makeText(mHost.getContext(),
                        R.string.ime_banner_toast_cannot_open_settings,
                        Toast.LENGTH_LONG).show()
            } catch (ignored: Throwable) {}
        }
    }

    // ── HUD notification-listener banner ──────────────────────────────────────

    private fun setupHudNotif() {
        val card = mCardHudNotif ?: return
        val btnEnable: View? = card.findViewById(R.id.btn_hud_banner_enable)
        val btnLater: View? = card.findViewById(R.id.btn_hud_banner_later)
        val btnDismiss: View? = card.findViewById(R.id.btn_hud_banner_dismiss)

        btnEnable?.setOnClickListener { v -> enableHudNotifOneClick(v) }
        btnLater?.setOnClickListener { card.visibility = View.GONE }
        btnDismiss?.setOnClickListener {
            try { ClusterPrefs.setHudBannerDismissed(mHost.getContext()) } catch (ignored: Throwable) {}
            card.visibility = View.GONE
        }
        refreshHudNotif()
    }

    private fun refreshHudNotif() {
        val card = mCardHudNotif ?: return
        var shouldShow = false
        try {
            val dismissed = ClusterPrefs.isHudBannerDismissed(mHost.getContext())
            if (!dismissed) {
                val listeners = Settings.Secure.getString(
                        mHost.getContext().contentResolver, "enabled_notification_listeners")
                val granted = listeners != null
                        && listeners.contains(mHost.getContext().packageName)
                shouldShow = !granted
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "refreshHudNotif inner failed", t)
        }
        card.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun enableHudNotifOneClick(btnEnable: View?) {
        btnEnable?.isEnabled = false
        try {
            val comp = "com.byd.dashcast/com.byd.dashcast.hud.MapNotificationListenerService"
            val cmd = "cmd notification allow_listener " + comp

            ShellGateway.execShellWithResult(mHost.getContext(), cmd, object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    mHost.runOnUiThread {
                        if (!mHost.isActivityAlive()) return@runOnUiThread
                        val listeners = Settings.Secure.getString(
                                mHost.getContext().contentResolver, "enabled_notification_listeners")
                        val ok = listeners != null
                                && listeners.contains(mHost.getContext().packageName)
                        if (ok) {
                            AppLogger.i(TAG, "Notification listener enabled via shell ✓")
                            try {
                                Toast.makeText(mHost.getContext(),
                                        R.string.hud_notif_banner_toast_enabled,
                                        Toast.LENGTH_SHORT).show()
                            } catch (ignored: Throwable) {}
                            mCardHudNotif?.visibility = View.GONE
                            btnEnable?.isEnabled = true
                        } else {
                            AppLogger.w(TAG, "shell OK but listener not enabled — fallback. report=" + out)
                            openNotifListenerSettingsFallback()
                            btnEnable?.isEnabled = true
                        }
                    }
                }
                override fun onError(err: String?) {
                    mHost.runOnUiThread {
                        if (!mHost.isActivityAlive()) return@runOnUiThread
                        AppLogger.w(TAG, "one-click notif listener shell failed: " + err + " — fallback")
                        openNotifListenerSettingsFallback()
                        btnEnable?.isEnabled = true
                    }
                }
            })
        } catch (t: Throwable) {
            AppLogger.e(TAG, "enableHudNotifOneClick threw", t)
            openNotifListenerSettingsFallback()
            btnEnable?.isEnabled = true
        }
    }

    private fun openNotifListenerSettingsFallback() {
        try {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mHost.startActivity(i)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "ACTION_NOTIFICATION_LISTENER_SETTINGS unavailable: " + t.message)
        }
    }

    companion object {
        private const val TAG = "PermBannerCoord"
    }
}
