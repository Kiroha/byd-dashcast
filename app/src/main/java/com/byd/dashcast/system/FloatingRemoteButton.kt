package com.byd.dashcast.system

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

import com.byd.dashcast.MainActivity
import com.byd.dashcast.R
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.report.BugWizardActivity
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger

import kotlin.math.abs

/**
 * FloatingRemoteButton — persistent overlay button visible over all screens.
 *
 * Displays a draggable 📺 badge. Only visible when an app is active on the cluster.
 * • Tap  → starts MainActivity with ACTION_SHOW_MIRROR via startActivity() (not a broadcast).
 * • Long press → closes this overlay service.
 *
 * Visibility is controlled externally via the static show() / hide() helpers
 * called by MainActivity whenever mCurrentDashboardApp changes.
 */
// badge is drag+tap; tap handled in onTouch ACTION_UP
@SuppressLint("ClickableViewAccessibility")
class FloatingRemoteButton : Service() {

    private val mDimHandler = Handler(Looper.getMainLooper())
    private val mDimRunnable = Runnable {
        mFloatView?.animate()?.alpha(0.35f)?.setDuration(300)?.start()
    }

    fun triggerDimTimer() {
        mFloatView?.alpha = 1.0f
        mDimHandler.removeCallbacks(mDimRunnable)
        mDimHandler.postDelayed(mDimRunnable, 3000)
    }

    // mWindowManager is lateinit rather than nullable on purpose: it is assigned before
    // mFloatView ever becomes non-null, so every read below is preceded by a mFloatView
    // null-check. An impossible-state read throws UninitializedPropertyAccessException,
    // which — like the NPE the Java would have thrown — is an Exception, so onDestroy's
    // catch(Exception) swallows it exactly as before.
    private lateinit var mWindowManager: WindowManager
    private var mFloatView: View? = null
    private var mGrantAttempted = false

    // F29 (perf audit #8): set in onDestroy() so a late ADB grant onSuccess (running
    // on the AdbLocalClient background executor) short-circuits instead of re-posting
    // createOverlay() and addView()-ing a TYPE_APPLICATION_OVERLAY window on a dead
    // Service. volatile: written on the main thread, read on the ADB executor thread.
    @Volatile private var mDestroyed = false

    // v1.2.74 — track FG status so we can toggle the notification along with the badge.
    private var mIsForeground = false

    // M19: cached once to avoid PendingIntent.getActivity() IPC on every show().
    private var mFgPendingIntent: PendingIntent? = null

    // 1.2.30 — tracked so onDestroy() can dismiss the overlay dialog and avoid a
    // leaked TYPE_APPLICATION_OVERLAY window when the service tears down.
    private var mQuickSwitchDialog: AlertDialog? = null

    // M2: reused snap-to-edge animator — avoids allocating ValueAnimator + DecelerateInterpolator
    // on every drag release.
    private var mSnapAnimator: ValueAnimator? = null

    // L3: cached prefs reference — getSharedPreferences() is a HashMap lookup but
    // no need to repeat it on every long-press.
    private var mPrefs: SharedPreferences? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sInstance = this
        if (mFloatView != null) return START_STICKY
        startForegroundCompat()
        createOverlay()
        AppLogger.d(TAG, "FloatingRemoteButton started")
        return START_STICKY
    }

    override fun onDestroy() {
        mDestroyed = true
        mSnapAnimator?.let { it.cancel() }
        mSnapAnimator = null
        mDimHandler.removeCallbacksAndMessages(null)
        sInstance = null
        // 1.2.30 — dismiss the quick-switch dialog if still showing, otherwise
        // its window leaks (overlay token kept alive after the service dies).
        mQuickSwitchDialog?.let { dlg ->
            try { if (dlg.isShowing) dlg.dismiss() }
            catch (ignored: Throwable) {}
        }
        mQuickSwitchDialog = null
        if (mFloatView != null) {
            try { mWindowManager.removeView(mFloatView) } catch (ignored: Exception) {
                AppLogger.d(TAG, "removeView skipped (view already detached): " + ignored.message)
            }
            mFloatView = null
        }
        super.onDestroy()
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun createOverlay() {
        if (mDestroyed) return
        if (!Settings.canDrawOverlays(this)) {
            if (mGrantAttempted) {
                AppLogger.e(TAG, "SYSTEM_ALERT_WINDOW still denied after ADB attempt — badge not shown")
                return
            }
            mGrantAttempted = true
            AppLogger.w(TAG, "SYSTEM_ALERT_WINDOW not granted — attempting auto-grant via ADB…")
            AdbLocalClient.grantOverlayPermission(this, object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    if (mDestroyed) return
                    AppLogger.i(TAG, "SYSTEM_ALERT_WINDOW granted via ADB ✓")
                    mDimHandler.post {
                        if (!mDestroyed) createOverlay()
                    }
                }
                override fun onError(err: String?) {
                    AppLogger.e(TAG, "Auto-grant SYSTEM_ALERT_WINDOW failed: $err")
                }
            })
            return
        }
        mGrantAttempted = false
        mWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val badge = TextView(this)
        badge.text = "📺" // 📺
        badge.textSize = 22f
        badge.setBackgroundColor(Color.argb(220, 0, 105, 92)) // teal #00695C
        badge.setPadding(20, 12, 20, 12)
        badge.elevation = 8f
        badge.visibility = View.GONE // hidden until an app is on the cluster

        val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                2038,  // TYPE_APPLICATION_OVERLAY
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 12
        params.y = 220

        mPrefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        val snap = ValueAnimator.ofInt(0, 0)
        snap.duration = 250
        snap.interpolator = DECELERATE
        snap.addUpdateListener { animation ->
            params.x = animation.animatedValue as Int
            try {
                if (mFloatView != null) mWindowManager.updateViewLayout(mFloatView, params)
            } catch (ignored: Exception) {
                AppLogger.d(TAG, "updateViewLayout skipped: " + ignored.message)
            }
        }
        mSnapAnimator = snap

        badge.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var initTX = 0f
            private var initTY = 0f
            private var downTime = 0L

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                // Reset opacity and cancel pending dim
                badge.alpha = 1.0f
                mDimHandler.removeCallbacks(mDimRunnable)

                // Trigger dim
                if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                    mDimHandler.postDelayed(mDimRunnable, 3000)
                }

                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x
                        initY = params.y
                        initTX = e.rawX
                        initTY = e.rawY
                        downTime = System.currentTimeMillis()
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = initTX - e.rawX
                        val dy = e.rawY - initTY
                        params.x = initX + dx.toInt()
                        params.y = initY + dy.toInt()
                        mWindowManager.updateViewLayout(mFloatView, params)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val movX = abs(e.rawX - initTX)
                        val movY = abs(e.rawY - initTY)
                        val held = System.currentTimeMillis() - downTime
                        if (movX < 12 && movY < 12) {
                            if (held > 600) {
                                // Long press → show recent apps popup for quick switching
                                AppLogger.i(TAG, "Long-press → show quick-switch popup")
                                showQuickSwitchPopup()
                            } else {
                                // Tap → bring MainActivity to front + open mirror panel
                                val bringFront = Intent(
                                        this@FloatingRemoteButton, MainActivity::class.java)
                                bringFront.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                bringFront.action = ACTION_SHOW_MIRROR
                                startActivity(bringFront)
                                AppLogger.d(TAG, "Tap → ACTION_SHOW_MIRROR")
                            }
                        } else {
                            // Snap to edge logic
                            val screenWidth = resources.displayMetrics.widthPixels
                            val halfWidth = screenWidth / 2
                            // Gravity is END, so params.x is the margin from the RIGHT.
                            // If params.x > halfWidth, it's closer to the LEFT edge.
                            val targetX = if (params.x > halfWidth) (screenWidth - badge.width) else 0

                            val anim = mSnapAnimator!!
                            if (anim.isRunning) anim.cancel()
                            anim.setIntValues(params.x, targetX)
                            anim.start()
                        }
                        return true
                    }
                }
                return false
            }
        })

        mFloatView = badge
        try {
            mWindowManager.addView(mFloatView, params)
            // Apply the desired visibility that may have been requested before
            // the overlay was ready (race: show() called before createOverlay completed).
            if (sShouldBeVisible) {
                mFloatView?.visibility = View.VISIBLE
                triggerDimTimer()
                // Same v1.2.74 invariant show() upholds: a visible badge means a foreground
                // service. This path was flipping only the visibility, so on the first run after
                // install — overlay permission not yet granted when the badge was first asked for
                // — the badge came back on screen with no ongoing notification and the service
                // demoted to an ordinary started one, i.e. a prime reclaim target. Losing it takes
                // the badge with it, and the badge is the only route back to the mirror and to the
                // bug reporter. No-op on the common path: promoteForeground returns immediately
                // when already foreground, and swallows the A13 background-start refusal.
                promoteForeground()
                AppLogger.d(TAG, "Overlay created — applying deferred show()")
            } else {
                mDimHandler.postDelayed(mDimRunnable, 3000)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "addView overlay failed", e)
            mFloatView = null
        }
    }

    private fun showQuickSwitchPopup() {
        // The dialog always offers "Report a bug" as the first entry (so the bug
        // reporter is reachable over any app at the moment it breaks), followed by
        // the recent apps for quick-switching.
        val raw = mPrefs!!.getString(SettingsActivity.PREF_RECENT_APPS, "") ?: ""
        // dropLastWhile reproduces Java's String.split(regex), which discards trailing
        // empty fields. ClusterPrefs writes this value with a join so there is no trailing
        // separator today, but the parser must not depend on the writer staying that way.
        val entries: List<String> =
                if (raw.isEmpty()) emptyList() else raw.split(";;").dropLastWhile { it.isEmpty() }
        val n = entries.size
        val pkgs = arrayOfNulls<String>(n + 1)
        pkgs[0] = null // sentinel → bug reporter
        val names = Array(n + 1) { i ->
            if (i == 0) {
                getString(R.string.bug_report_menu)
            } else {
                val parts = entries[i - 1].split("|", limit = 2)
                pkgs[i] = parts[0]
                if (parts.size > 1) parts[1] else parts[0]
            }
        }
        val dlg = AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.quick_switch_title))
                .setItems(names) { _, which ->
                    if (which == 0) {
                        val bug = Intent(this@FloatingRemoteButton, BugWizardActivity::class.java)
                        bug.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(bug)
                        AppLogger.d(TAG, "Floating → bug reporter")
                    } else {
                        val intent = Intent(this@FloatingRemoteButton, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        intent.action = ACTION_QUICK_SWITCH
                        intent.putExtra(EXTRA_QUICK_SWITCH_PKG, pkgs[which])
                        startActivity(intent)
                        AppLogger.d(TAG, "Quick-switch → " + pkgs[which])
                    }
                }
                .create()
        dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        // 1.2.30 — keep a reference so onDestroy() can dismiss the overlay.
        mQuickSwitchDialog = dlg
        dlg.setOnDismissListener { d -> if (mQuickSwitchDialog === d) mQuickSwitchDialog = null }
        dlg.show()
    }

    // ── Foreground service ────────────────────────────────────────────────────

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
                CHANNEL, getString(R.string.notif_remote_channel_name),
                NotificationManager.IMPORTANCE_MIN))

        if (mFgPendingIntent == null) {
            val tapIntent = Intent(this, MainActivity::class.java)
            tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mFgPendingIntent = PendingIntent.getActivity(
                    this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE)
        }

        val notif = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("DashCast")
                .setContentText(getString(R.string.notif_remote_content))
                .setContentIntent(mFgPendingIntent)
                .setOngoing(true)
                .build()

        startForeground(NOTIF_ID, notif)
        mIsForeground = true
        // v1.2.74 — if the service is started before any projection is active,
        // the badge is hidden (sShouldBeVisible == false). In that case we must
        // not leave the notification dangling. Drop FG status immediately.
        if (!sShouldBeVisible) {
            demoteForeground()
        }
    }

    /** v1.2.74 — re-promote to foreground when the badge becomes visible again. */
    private fun promoteForeground() {
        if (mIsForeground) return
        try {
            if (mFgPendingIntent == null) {
                val tapIntent = Intent(this, MainActivity::class.java)
                tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                mFgPendingIntent = PendingIntent.getActivity(
                        this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE)
            }
            val notif = Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("DashCast")
                    .setContentText(getString(R.string.notif_remote_content))
                    .setContentIntent(mFgPendingIntent)
                    .setOngoing(true)
                    .build()
            startForeground(NOTIF_ID, notif)
            mIsForeground = true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "promoteForeground failed: " + t.message)
        }
    }

    /** v1.2.74 — drop FG status + remove notification when the badge is hidden. */
    private fun demoteForeground() {
        if (!mIsForeground) return
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            mIsForeground = false
        } catch (t: Throwable) {
            AppLogger.w(TAG, "demoteForeground failed: " + t.message)
        }
    }

    companion object {
        private const val TAG = "FloatingRemoteBtn"
        private const val CHANNEL = "floating_remote_btn"
        private const val NOTIF_ID = 9989

        /** Broadcast action sent to MainActivity to open the mirror panel. */
        const val ACTION_SHOW_MIRROR = "com.byd.dashcast.action.SHOW_MIRROR"

        /** Broadcast action sent to MainActivity to quick-switch to a specific app. */
        const val ACTION_QUICK_SWITCH = "com.byd.dashcast.action.QUICK_SWITCH"
        const val EXTRA_QUICK_SWITCH_PKG = "quick_switch_pkg"

        private val DECELERATE = DecelerateInterpolator()

        // ── Static helpers so MainActivity can show/hide without a Service reference ──
        @SuppressLint("StaticFieldLeak")
        @Volatile private var sInstance: FloatingRemoteButton? = null

        /**
         * Desired visibility state — survives even when sInstance or mFloatView is not yet
         * ready (service starting, overlay permission being granted via ADB).
         * When the overlay is finally created, it reads this flag to apply the correct state.
         */
        @Volatile private var sShouldBeVisible = false

        @JvmStatic
        fun show() {
            sShouldBeVisible = true
            val inst = sInstance
            if (inst?.mFloatView != null) {
                inst.mFloatView?.post {
                    val i = sInstance
                    if (i?.mFloatView != null && sShouldBeVisible) {
                        i.mFloatView?.visibility = View.VISIBLE
                        i.triggerDimTimer()
                        // v1.2.74 — re-promote to foreground so the notification
                        // reappears in sync with the badge visibility.
                        i.promoteForeground()
                    }
                }
            }
        }

        @JvmStatic
        fun hide() {
            sShouldBeVisible = false
            val inst = sInstance ?: return
            // v1.2.74 — drop the FG notification too: badge invisible should not
            // leave a "Miroir cluster actif" notification dangling.
            inst.demoteForeground()
            if (inst.mFloatView == null) return
            inst.mFloatView?.post {
                val i = sInstance
                if (i?.mFloatView != null) {
                    i.mFloatView?.visibility = View.GONE
                }
            }
        }
    }
}
