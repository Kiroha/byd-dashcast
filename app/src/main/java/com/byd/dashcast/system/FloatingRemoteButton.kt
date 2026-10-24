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
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.fission.FissionOrchestrator
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
        mFloatView?.animate()?.alpha(0.78f)?.setDuration(300)?.start()
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
        if (intent == null) {
            sShouldBeVisible = ClusterService.getInstance()?.activeProjectionApps()?.isNotEmpty() == true ||
                FissionOrchestrator.getActiveLayoutPackages().isNotEmpty()
        }
        if (mFloatView != null) {
            if (sShouldBeVisible) { mFloatView?.visibility = View.VISIBLE; promoteForeground() }
            return START_STICKY
        }
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

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val shape = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(240, 42, 49, 64), Color.argb(245, 20, 25, 35))).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.argb(100, 152, 180, 211))
        }
        val badge = FrameLayout(this).apply {
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(75, 80, 220, 200)), shape, null)
            elevation = dp(8).toFloat()
            contentDescription = getString(R.string.floating_mirror_open)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_floating_mirror)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        badge.addView(icon, FrameLayout.LayoutParams(dp(30), dp(38), Gravity.CENTER))
        badge.setOnClickListener { openMirror() }
        badge.setOnLongClickListener { showQuickSwitchPopup(); true }
        val params = WindowManager.LayoutParams(
                dp(56), dp(72), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.LEFT
        val saved = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val size = screenSize()
        params.x = saved.getInt("floating_mirror_x", size.x - dp(68))
        params.y = saved.getInt("floating_mirror_y", dp(180))
        clampPosition(params)

        mPrefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        val snap = ValueAnimator.ofInt(0, 0)
        snap.duration = 250
        snap.interpolator = DECELERATE
        snap.addUpdateListener { animation ->
            params.x = animation.animatedValue as Int
            clampPosition(params)
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
            private var dragging = false
            private val slop = ViewConfiguration.get(this@FloatingRemoteButton).scaledTouchSlop

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                badge.animate().cancel()
                badge.alpha = 1f
                mDimHandler.removeCallbacks(mDimRunnable)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        mSnapAnimator?.cancel()
                        initX = params.x; initY = params.y
                        initTX = e.rawX; initTY = e.rawY
                        downTime = e.eventTime
                        dragging = false
                        badge.isPressed = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - initTX
                        val dy = e.rawY - initTY
                        if (abs(dx) > slop || abs(dy) > slop) dragging = true
                        if (dragging) {
                            badge.isPressed = false
                            params.x = initX + dx.toInt(); params.y = initY + dy.toInt()
                            clampPosition(params)
                            updatePosition(params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        badge.isPressed = false
                        if (!dragging) {
                            if (e.eventTime - downTime >= ViewConfiguration.getLongPressTimeout()) badge.performLongClick()
                            else badge.performClick()
                        } else {
                            val maxX = (screenSize().x - params.width).coerceAtLeast(0)
                            val targetX = if (params.x + params.width / 2 > screenSize().x / 2) maxX else 0
                            mPrefs?.edit()?.putInt("floating_mirror_x", targetX)?.putInt("floating_mirror_y", params.y)?.apply()
                            mSnapAnimator?.setIntValues(params.x, targetX)
                            mSnapAnimator?.start()
                        }
                        triggerDimTimer()
                    }
                    MotionEvent.ACTION_CANCEL -> { badge.isPressed = false; triggerDimTimer() }
                }
                return true
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

    private fun screenSize(): Point = Point().also { mWindowManager.defaultDisplay.getSize(it) }

    private fun clampPosition(params: WindowManager.LayoutParams) {
        val size = screenSize()
        params.x = params.x.coerceIn(0, (size.x - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (size.y - params.height).coerceAtLeast(0))
    }

    private fun updatePosition(params: WindowManager.LayoutParams) {
        val view = mFloatView ?: return
        if (mDestroyed || !view.isAttachedToWindow) return
        try { mWindowManager.updateViewLayout(view, params) }
        catch (error: Exception) { AppLogger.w(TAG, "overlay position unavailable: ${error.message}") }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mSnapAnimator?.cancel()
        val params = mFloatView?.layoutParams as? WindowManager.LayoutParams ?: return
        clampPosition(params)
        updatePosition(params)
    }

    private fun mirrorIntent() = Intent(this, MainActivity::class.java).apply {
        action = ACTION_SHOW_MIRROR
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private fun openMirror() {
        try { startActivity(mirrorIntent()) }
        catch (error: Exception) { AppLogger.e(TAG, "opening tactile mirror failed", error) }
    }

    private fun showQuickSwitchPopup() {
        if (mQuickSwitchDialog?.isShowing == true || mDestroyed) return
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
            val tapIntent = mirrorIntent()
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
                val tapIntent = mirrorIntent()
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
        @JvmOverloads
        fun show(context: Context? = null) {
            sShouldBeVisible = true
            if (context != null && (sInstance == null || sInstance?.mFloatView == null)) {
                try { context.applicationContext.startForegroundService(Intent(context.applicationContext, FloatingRemoteButton::class.java)) }
                catch (error: Exception) { AppLogger.w(TAG, "floating mirror service unavailable: ${error.message}") }
            }
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
            inst.mDimHandler.post {
                if (sShouldBeVisible) return@post
                inst.demoteForeground()
                val i = sInstance
                if (i?.mFloatView != null) {
                    i.mFloatView?.visibility = View.GONE
                }
            }
        }
    }
}
