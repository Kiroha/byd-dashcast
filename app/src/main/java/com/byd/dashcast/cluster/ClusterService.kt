package com.byd.dashcast.cluster

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display

import androidx.core.app.NotificationCompat

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.MainActivity
import com.byd.dashcast.R
import com.byd.dashcast.cluster.display.ClusterDisplayRegistry
import com.byd.dashcast.cluster.display.ClusterGeometryPolicy
import com.byd.dashcast.cluster.display.ClusterManager
import com.byd.dashcast.cluster.display.DashboardDisplayHelper
import com.byd.dashcast.cluster.display.DashboardLauncher
import com.byd.dashcast.cluster.dpi.ClusterDpiManager
import com.byd.dashcast.cluster.mirror.ClusterInputForwarder
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.domain.cluster.ProjectionStateProvider
import com.byd.dashcast.fission.FissionOrchestrator
import com.byd.dashcast.ime.KeyboardBridgeActivity
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.infrastructure.task.AdbLocalTaskFinder
import com.byd.dashcast.infrastructure.task.AmTaskFinder
import com.byd.dashcast.infrastructure.task.ChainedTaskFinder
import com.byd.dashcast.infrastructure.task.ProxyTaskFinder
import com.byd.dashcast.infrastructure.task.TaskFinder
import com.byd.dashcast.infrastructure.task.TaskLocation
import com.byd.dashcast.infrastructure.task.TypedProxyTaskFinder
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.DaemonBinderResolver
import com.byd.dashcast.proxy.DaemonConfig
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.concurrent.LifecycleGate

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ClusterService — Foreground Service that maintains projection on the cluster
 * independently of the MainActivity lifecycle.
 *
 * ### Architecture changes (refactor v1.5)
 *  - [findRunningTaskId] delegates to [ChainedTaskFinder] (AM → ProxyDaemon → AdbLocal),
 *    removing 140 lines of inline strategy code.
 *  - Implements [ProjectionStateProvider] so FissionActivity / FissionOrchestrator no longer
 *    depend on the static field `sIsRunning` or a direct class reference.
 *
 * Zero regression: all runtime logic (IATM reflection, shell commands, timing, DPI settle,
 * DL5 guards, wm overscan, cleanFissionStacks, enforceTaskOnDisplay) is preserved verbatim.
 *
 * ### Both daemons run through here
 * Besides the ProxyDaemon commands above, this service drives the **SURFACE** daemon
 * ([com.byd.dashcast.proxy.daemon.SurfaceDaemon]): it owns the [ClusterMirrorManager] and the
 * [ClusterInputForwarder], and [stopProjectionNoAdb] / [onDestroy] are the two paths that tear
 * the mirror down. Their binder comes from [DaemonBinderResolver.surfaceDaemonBinder] — never
 * from `ProxyClient.getProxyDaemonBinder()`; see the boundary rule on `SurfaceDaemon`.
 */
@Suppress("DEPRECATION")
class ClusterService : Service(), DashboardDisplayHelper.Listener, ProjectionStateProvider {

    /**
     * Whether IActivityTaskManager.moveTaskToDisplay is usable on this ROM.
     * Returns false only once the reflection probe has positively found it stripped
     * (DiLink3.0); null/unknown and TRUE both report true.
     *
     * Callers use this to decide whether the app already on the cluster must be stopped
     * before launching a new one: when the move is stripped, the fallback launch path
     * cannot reparent the previous app, so a second app lands in split-screen on the
     * main display (INC-20260621-130238).
     */
    fun isMoveTaskToDisplaySupported(): Boolean {
        // Resolved on demand, by the reader that actually needs the answer. Previously the flag was
        // only ever latched as a SIDE EFFECT of a move attempt, and the corpus shows 82% of real
        // sessions got it from the deferred re-anchor alone — the very call that now goes through
        // the daemon instead. A fire-and-forget prime at startup would have left a silent window
        // (and a race); a cheap interface lookup here has neither.
        var known = sMoveTaskToDisplayAvailable
        if (known == null) known = resolveMoveTaskToDisplaySupport()
        return known != false
    }

    // ── Listener for MainActivity ───────────────────────────────────────────
    interface Listener {
        fun onClusterDisplayConnected(display: Display?, displayId: Int)
        fun onClusterDisplayDisconnected()
    }

    // ── Binder ──────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): ClusterService = this@ClusterService
    }

    private val mBinder: IBinder = LocalBinder()

    // ── State ───────────────────────────────────────────────────────────────
    // mDisplayHelper / mLauncher / mTaskFinder are assigned once in initializeAfterOwnershipClaim()
    // and read only afterwards; the Java version dereferenced them unconditionally, so `lateinit`
    // reproduces its contract. mMirrorManager / mInputForwarder are NULLABLE on purpose: the Java
    // code null-checked exactly those two (onDashboardDisplayDisconnected, enforceTaskOnDisplay,
    // clusterWidthOr/HeightOr, moveTaskToDisplayInternal, verifyClusterDisplayState) because
    // sInstance publishes `this` before they are assigned.
    private lateinit var mDisplayHelper: DashboardDisplayHelper
    private lateinit var mLauncher: DashboardLauncher
    private var mMirrorManager: ClusterMirrorManager? = null
    private var mInputForwarder: ClusterInputForwarder? = null
    private var mListener: Listener? = null

    @Volatile private var mProjectionActive = false
    @Volatile private var mDestroyed = false
    private val mOperationGate = LifecycleGate()
    private var mPendingDashboardCallback: LaunchCallback? = null
    private var mPendingLaunchRunnable: Runnable? = null

    private val mMainHandler = Handler(Looper.getMainLooper())
    private var mNotifPi: PendingIntent? = null

    // ── Strategy objects (injected in onCreate) ─────────────────────────────
    private lateinit var mTaskFinder: TaskFinder

    /** Package to auto-launch once the cluster display connects (armed in onStartCommand at boot). */
    @Volatile private var mBootAutoLaunchPkg: String? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        claimTaskMoveOwnership()
        var initialized = false
        try {
            initializeAfterOwnershipClaim()
            initialized = true
        } finally {
            if (!initialized) {
                mProjectionActive = false
                if (sInstance === this) sInstance = null
                releaseTaskMoveOwnership()
            }
        }
    }

    private fun initializeAfterOwnershipClaim() {
        sInstance = this

        mDisplayHelper = DashboardDisplayHelper(this, this)
        mLauncher = DashboardLauncher(this)
        mMirrorManager = ClusterMirrorManager()
        mInputForwarder = ClusterInputForwarder(this)

        // v1.6.147 — PULL the daemon Binder instead of waiting to be pushed one.
        // When the SurfaceDaemon survives an ACC off/on cycle it is REUSED, so it is already
        // registered in ServiceManager before MainActivity binds this service. The push path in
        // MainActivity resolves the binder first, finds mClusterService still null and drops it,
        // and no ACTION_DAEMON_READY broadcast follows a reused (never re-spawned) daemon — so
        // setDaemonBinder was never called and every touch fell back to the unprivileged direct
        // path, silently reaching nothing (INC-20260724-102136). Pulling here removes the
        // Activity-ordering dependency entirely; it is a cheap local ServiceManager lookup.
        val reusedDaemon = DaemonBinderResolver.surfaceDaemonBinder()
        if (reusedDaemon != null) {
            mInputForwarder?.setDaemonBinder(reusedDaemon)
        }

        // Strategy wiring: app AM → typed daemon ATM → daemon dumpsys → AdbLocal dumpsys.
        mTaskFinder = ChainedTaskFinder(
            AmTaskFinder(this),
            TypedProxyTaskFinder(),
            ProxyTaskFinder(),
            AdbLocalTaskFinder(this)
        )

        AdbLocalClient.startMirrorDaemon(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_cluster_initializing)))
        AppLogger.log(TAG, "ClusterService created — starting native projection")
        mProjectionActive = true

        if (AdbLocalClient.isDiLink5Safe(this)) {
            val check = "v=\$(settings get global force_resizable_activities); " +
                "if [ \"\$v\" = \"1\" ]; then " +
                "settings put global force_resizable_activities 0 2>&1; " +
                "echo RESET; else echo OK=\$v; fi"
            ShellGateway.execShellWithResult(this, check, object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    AppLogger.i(TAG, "DL5 force_resizable_activities cleanup → " + out?.trim())
                }

                override fun onError(err: String?) {
                    AppLogger.e(TAG, "DL5 force_resizable_activities cleanup ERROR: $err")
                }
            })
            KeyboardBridgeActivity.ensureClusterImeEnabled(this)
        }
        if (BuildConfig.DEBUG) {
            AdbLocalClient.dumpSignatureAndPermissions(this)
        }

        // Load any persisted single-OS verdict BEFORE startNativeProjection() so its guard fires
        // immediately on a car already known to have no projectable cluster. Also read the prop
        // via the shell (uid 2000 CAN read ro.build.system.fission_single_os even when the app's
        // own in-process SystemProperties.get returns "" — SELinux prop context, INC-20260715-140107),
        // so a first-seen single-OS DL3 gets flagged for next time without waiting on a doomed cycle.
        Platform.primeClusterSingleOs(this)
        if (Platform.get().isDiLink3(this)) {
            AdbLocalClient.executeShellWithResult(
                this,
                "getprop ro.build.system.fission_single_os",
                object : AdbLocalClient.Callback {
                    override fun onSuccess(out: String?) {
                        if (out != null && "1" == out.trim()) {
                            Platform.noteClusterSingleOsDetected(this@ClusterService)
                            AppLogger.i(TAG, "shell getprop: DL3 single-OS cluster (fission_single_os=1) — projection unsupported")
                            // If this resolved only AFTER the first activation already timed
                            // out, correct the notification now (it would otherwise read the
                            // generic "disconnected" until the next boot).
                            //
                            // Guarded: this is a shell round-trip, so it can land after the
                            // user has already stopped projection — and updateNotification
                            // would then re-post NOTIF_ID for a session that is over, putting
                            // an ongoing notification back on screen with nothing behind it.
                            // mProjectionActive covers the common stopped-but-still-bound
                            // case that mDestroyed misses; it is cleared in
                            // stopProjectionNoAdb() before the cancel/stopSelf.
                            mMainHandler.post {
                                if (mDestroyed || !mProjectionActive) return@post
                                updateNotification(
                                    getString(R.string.dl3_singleos_cluster_unsupported_title)
                                )
                            }
                        }
                    }

                    override fun onError(err: String?) { /* stays unflagged; projection proceeds (fail-open) */ }
                }
            )
        }
        startNativeProjection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_RESTART_PROJECTION == intent.action) {
            restartProjection()
        }
        // Headless boot auto-launch: BootReceiver starts us with EXTRA_BOOT_AUTOLAUNCH so the
        // configured app is launched onto the cluster at startup WITHOUT the user opening DashCast
        // (INC-20260716-091016). Arm it here (intent is delivered to onStartCommand); the actual
        // launch happens in onDashboardDisplayConnected once the cluster display is up.
        if (intent != null && intent.getBooleanExtra(EXTRA_BOOT_AUTOLAUNCH, false)) {
            // Mirror MainActivity's guard: when a Layout auto-start is configured, IT owns startup
            // launching (activates projection + launches every bound app). A headless single-app
            // launch here would create a classic projection first, and the layout's ensureDaemon()
            // would then abort with "Daemon unavailable". So skip the single-app path for layout users.
            if (isLayoutAutoStartRequested()) {
                AppLogger.i(TAG, "boot auto-launch: layout auto-start owns startup — headless single-app launch skipped")
            } else {
                val pkg = ClusterPrefs.getAutoLaunchPkg(this)
                if (pkg != null && pkg.isNotEmpty()) {
                    mBootAutoLaunchPkg = pkg
                    AppLogger.i(TAG, "boot auto-launch armed (headless) → $pkg")
                } else {
                    AppLogger.i(TAG, "boot auto-launch requested but no app configured")
                }
            }
        }
        return START_STICKY
    }

    /** A requested Layout auto-start owns startup, so the single-app path must stand down. */
    private fun isLayoutAutoStartRequested(): Boolean {
        return try {
            FissionOrchestrator.isAutoStartRequested(this)
        } catch (t: Throwable) {
            false // fail-open to the single-app path (never block a boot launch on a probe error)
        }
    }

    override fun onBind(intent: Intent?): IBinder = mBinder

    override fun onUnbind(intent: Intent?): Boolean {
        mListener = null
        return false
    }

    override fun onDestroy() {
        mOperationGate.invalidate()
        mDestroyed = true
        try {
            super.onDestroy()
            if (sInstance === this) sInstance = null
            mListener = null
            // NOTE: the rolling screenshots are deliberately NOT wiped here — a tester often stops a
            // broken projection and THEN files the report, so the shots must survive the stop. They
            // self-clean via the recorder's max-age prune (runs on the keeper heartbeat even when idle)
            // and after a send. See ClusterShotRecorder.
            val pending = mPendingDashboardCallback
            if (pending != null) {
                mPendingDashboardCallback = null
                pending.onResult(false)
            }
            mMainHandler.removeCallbacksAndMessages(null)
            try {
                ClusterDpiManager.restore(this, mDisplayHelper.getKnownClusterDisplayId())
            } catch (t: Throwable) {
                AppLogger.w(TAG, "DPI restore (onDestroy) failed: " + t.message)
            }
            mMirrorManager!!.release()
            if (mProjectionActive) {
                mDisplayHelper.stop()
            }
            // Same reason as stopProjectionNoAdb: the registry is process-wide and outlives this
            // service, so a geometry left behind here would be picked up by the next mirror attempt
            // even though no projection is running. No-op on DL3/DL5.
            ClusterDisplayRegistry.clear()
            AppLogger.log(TAG, "ClusterService destroyed")
        } finally {
            releaseTaskMoveOwnership()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProjectionStateProvider
    // ─────────────────────────────────────────────────────────────────────────

    override fun isProjectionActive(): Boolean = mProjectionActive

    fun interface StoppedTaskMove {
        @Throws(Exception::class)
        fun run(): Boolean
    }

    override fun stopProjectionIfActive(onStopped: Runnable?) {
        if (mProjectionActive) {
            stopProjectionNoAdb()
        }
        if (onStopped != null) {
            mMainHandler.post(onStopped)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API (called from MainActivity via the binder)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * DL3 with `ro.build.system.fission_single_os==1`: the instrument cluster is rendered
     * natively (Qt/fission) and there is NO projectable Android display — AutoContainer has no
     * native backend ("no AutoContainerNative"), so the activation sequence just churns for 12 s
     * per cycle forever (this is what made "auto-start" appear broken; INC-20260701-083007).
     * Skip the activation entirely on these cars. STRICT + safe: a normal 1-for-2 DL3
     * (`==0`) and DL5/DL5.1 (`isDiLink3()==false`) are NOT affected; fail-open if the
     * prop can't be read.
     */
    private fun isDl3SingleOsFission(): Boolean =
        Platform.get().isDiLink3(this) && Platform.isClusterSingleOs()

    /**
     * DiLink 4.0 where the cluster display is invisible to BOTH the app process and the
     * uid-2000 daemon. The OEM patched `DisplayManagerService.getDisplayIdsInternal` with
     * an app whitelist DashCast is not on, which is why the app process alone proves nothing —
     * so this is only true once [ClusterManager] recorded that a SUCCESSFUL daemon
     * `dumpsys display` also listed no cluster display. A shell that merely failed to
     * answer leaves this false and the generic "disconnected" message stands.
     *
     * STRICT: gated on `Platform.isDiLink4`, and `isDiLink3()` is defined as
     * "not DL2/DL4/DL5" — DL3 and DL5 can never reach this branch.
     */
    private fun isDl4ProjectionBlocked(): Boolean =
        Platform.get().isDiLink4(this) && ClusterManager.isDl4ProjectionUnavailable()

    private fun startNativeProjection() {
        if (isDl3SingleOsFission()) {
            AppLogger.w(TAG, "DL3 single-OS fission (fission_single_os=1) — no projectable cluster display; skipping AutoContainer activation")
            updateNotification(getString(R.string.dl3_singleos_cluster_unsupported_title))
            return
        }
        AppLogger.i(TAG, "Starting cluster projection (native)...")
        mDisplayHelper.start()
    }

    /**
     * Set when the cluster display disconnects with no listener attached, so the edge can be
     * replayed on re-attach. Volatile: written from the display callback thread, read in
     * setListener on the main thread.
     */
    @Volatile private var mMissedDisconnect = false

    fun setListener(listener: Listener?) {
        mListener = listener
        val knownId = mDisplayHelper.getKnownClusterDisplayId()
        // Replay a disconnect that happened while nobody was listening.
        //
        // MainActivity detaches in onStop, which is the app's NORMAL state during projection --
        // the user sends an app to the cluster and then leaves DashCast. If the cluster display
        // goes away in that window (ACC off/on, OEM teardown, activation timeout) the callback at
        // onDashboardDisplayDisconnected was simply dropped: nothing latched it, and this method
        // only ever replayed the CONNECTED edge. The Activity came back still showing the app as
        // live on a display that no longer exists -- green state, "<app> -> Cluster", mirror
        // button, and a mirror restart aimed at a dead display. Worse, ClusterPrefs still recorded
        // that package as the cluster app, and that persisted value is what the next successful
        // connect restores, so the lie outlived the process.
        //
        // Nothing automatic corrected it. The five other places that clear the persisted package
        // are all user-initiated (send-to-main, kill, clear, restore-BYD, origin-cluster), and
        // onStart's re-sync is one-sided by construction: `if (curDispId > 0)`, no else.
        if (listener != null && mMissedDisconnect && knownId <= 0) {
            mMissedDisconnect = false
            AppLogger.i(TAG, "setListener: replaying cluster disconnect missed while detached")
            listener.onClusterDisplayDisconnected()
            return
        }
        if (knownId > 0 && mListener != null) {
            // A disconnect followed by a reconnect while detached nets out to "still up" -- do not
            // deliver a spurious off-state on top of the connected replay below.
            mMissedDisconnect = false
            var d: Display? = null
            try {
                val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager
                if (dm != null) d = dm.getDisplay(knownId)
            } catch (e: Exception) {
                AppLogger.w(TAG, "getDisplay($knownId) failed: " + e.message)
            }
            if (d != null) {
                mListener?.onClusterDisplayConnected(d, knownId)
            } else if (ClusterDisplayRegistry.forDisplayId(knownId) != null) {
                // DiLink 4.0: dm.getDisplay(knownId) is blocked by the same OEM whitelist that
                // hides the id list, so this branch used to DROP the callback and MainActivity
                // never learned the cluster was up after an Activity re-create (rotation, back
                // from another app) — the mirror and the green state stayed off for the rest of
                // the session. The id IS valid (the daemon resolved it) and Listener already
                // declares Display as nullable, so deliver it. Registry is null on DL3/DL5.
                AppLogger.i(TAG, "setListener: no Display object for id=$knownId" +
                    " (daemon-resolved cluster) — notifying with null Display")
                mListener?.onClusterDisplayConnected(null, knownId)
            }
        }
    }

    val launcher: DashboardLauncher get() = mLauncher
    val mirrorManager: ClusterMirrorManager? get() = mMirrorManager
    val inputForwarder: ClusterInputForwarder? get() = mInputForwarder
    val displayId: Int get() = mDisplayHelper.getKnownClusterDisplayId()

    fun interface LaunchCallback {
        fun onResult(success: Boolean)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task finding (now via ChainedTaskFinder)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Finds the taskId of the running task for [packageName].
     * Must be called from a background thread.
     *
     * Delegates to [ChainedTaskFinder]: AM → typed daemon ATM → daemon dumpsys → AdbLocal.
     * Returns -1 if no task is found.
     */
    fun findRunningTaskId(packageName: String?): Int {
        return try {
            val id = mTaskFinder.findTaskId(packageName!!)
            if (id == TaskFinder.NOT_FOUND) -1 else id
        } catch (e: TaskFinder.TaskFinderException) {
            AppLogger.w(TAG, "findRunningTaskId failed for " + packageName + ": " + e.message)
            -1
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task reparenting
    // ─────────────────────────────────────────────────────────────────────────

    fun moveTaskToDisplay(packageName: String?, targetDisplayId: Int, callback: LaunchCallback?) {
        moveTaskToDisplayInternal(packageName, targetDisplayId, callback)
    }

    /**
     * Record, permanently, that this car has the small 1280x480 cluster panel — the one that drops
     * into its degraded "simple mode" if a larger shape preset is ever sent to it.
     *
     * **Second chance, not the primary reading.** This runs after activation, so on a car
     * where the ADAS fix has just forced the 12.3" shape it sees 1920x720 and correctly declines to
     * latch — which is why v1.8.27, where this was the ONLY latch site, failed to fire on exactly
     * the cars it protects. `ClusterManager.activateClusterDisplay` now latches from the
     * display it already holds BEFORE sending anything; this site still earns its place for the
     * DiLink 4 path, where the app cannot enumerate the display at all and the geometry only
     * arrives later via the uid-2000 daemon's `dumpsys display` parse.
     *
     * Latching rather than re-reading is the whole point: the damage is self-concealing, because
     * a panel already pushed to 1920x720 reports 1920x720 from then on. One sighting is enough and
     * it has to outlive the process.
     */
    private fun latchPanelGeometry(width: Int, height: Int) {
        if (!ClusterGeometryPolicy.isSmallPanelGeometry(width, height)) return
        if (ClusterPrefs.isSmallClusterPanelLatched(this)) return
        ClusterPrefs.latchSmallClusterPanel(this)
        AppLogger.i(TAG, "small cluster panel observed (" + width + "x" + height + ") — latched; " +
            "shape presets 30/31 will be refused on this car from now on")
    }

    /**
     * Deferred post-launch re-anchor: 2.5 s after a launch, make sure the task really is on the
     * cluster, because AOSP sometimes places it on display 0 anyway.
     *
     * Looks before it acts, and moves through the uid-2000 daemon rather than in-process
     * reflection. The old implementation called `IActivityTaskManager.moveTaskToDisplay`
     * from the app process; that method does not exist on DiLink 3 (the OEM stripped it), so it
     * threw `NoSuchMethodException`, logged a WARN claiming a launcher fallback that the
     * `enforceOnly` branch never actually ran, and gave up — the re-anchor was a silent
     * no-op on the platform that needs it most. The daemon has a working path
     * (`moveAndResize` → `setDisplayToSingleTaskInstance` + stack move), proven on
     * DL3 in every `launchAndForce` transcript.
     *
     * The probe is what keeps this cheap: when the daemon's own post-launch watchdog has
     * already done the job — the normal case — the task is found on the right display and nothing
     * is sent at all.
     */
    fun enforceTaskOnDisplay(packageName: String?, targetDisplayId: Int) {
        if (packageName == null || packageName.isEmpty() || targetDisplayId <= 0) return
        if (PKG_FORCE_FRESH_LAUNCH == packageName) {
            AppLogger.d(TAG, "enforceTaskOnDisplay: skip force-fresh-launch pkg $packageName")
            return
        }
        val operation = mOperationGate.capture()
        sMoveTaskExecutor.execute {
            if (!operation.isValid) return@execute
            val location: TaskLocation
            try {
                location = ProxyClient.findTaskLocationForPackage(packageName)
            } catch (t: Throwable) {
                // A failed probe says nothing about where the task is; acting on that would be the
                // mistake TaskLocation exists to prevent.
                AppLogger.d(TAG, "enforceTaskOnDisplay: probe failed for " + packageName +
                    " (" + t.javaClass.simpleName + ") — leaving it alone")
                return@execute
            }
            if (!operation.isValid) return@execute
            when (location.matchDisplay(targetDisplayId)) {
                TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY -> {
                    AppLogger.d(TAG, "enforceTaskOnDisplay: " + packageName +
                        " already on display " + targetDisplayId + " — nothing to do")
                    return@execute
                }
                TaskLocation.DisplayMatch.ABSENT -> {
                    AppLogger.d(TAG, "enforceTaskOnDisplay: no task yet for $packageName")
                    return@execute
                }
                TaskLocation.DisplayMatch.UNKNOWN -> {
                    AppLogger.d(TAG, "enforceTaskOnDisplay: location unknown for " + packageName +
                        " — leaving it alone")
                    return@execute
                }
                else -> {
                    // ON_OTHER_DISPLAY — the case this method exists for.
                }
            }
            var w = mInputForwarder?.getClusterWidth() ?: 0
            var h = mInputForwarder?.getClusterHeight() ?: 0
            if (w <= 0) w = 1920
            if (h <= 0) h = 720
            AppLogger.i(TAG, "enforceTaskOnDisplay: " + packageName + " is on display " +
                location.displayId + ", re-anchoring to " + targetDisplayId + " via daemon")
            try {
                val log = ProxyClient.moveAndResize(packageName, targetDisplayId, 0, 0, w, h)
                AppLogger.i(TAG, "enforceTaskOnDisplay result:\n" +
                    (if (log == null || log.isEmpty()) "(empty)" else log))
            } catch (t: Throwable) {
                AppLogger.w(TAG, "enforceTaskOnDisplay: daemon move failed for " + packageName +
                    ": " + t.javaClass.simpleName + ": " + t.message)
            }
        }
    }

    fun interface TaskLocationCallback {
        fun onResult(location: TaskLocation)
    }

    /**
     * Async, side-effect-free task-location query. This deliberately uses the typed daemon ATM
     * verb instead of the legacy finder chain: transport/reflection failure must remain UNKNOWN,
     * never collapse to ABSENT and trigger a destructive navigation relaunch.
     */
    fun findPackageLocation(packageName: String?, callback: TaskLocationCallback?) {
        if (callback == null) return
        val operation = mOperationGate.capture()
        sMoveTaskExecutor.execute {
            if (!operation.isValid) return@execute
            var location: TaskLocation
            try {
                location = ProxyClient.findTaskLocationForPackage(packageName)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "findPackageLocation unknown for " + packageName + ": " + t.message)
                location = TaskLocation.unknown()
            }
            if (!operation.isValid) return@execute
            val result = location
            mMainHandler.post {
                if (!operation.isValid) return@post
                callback.onResult(result)
            }
        }
    }

    private fun moveTaskToDisplayInternal(packageName: String?, targetDisplayId: Int,
                                          callback: LaunchCallback?) {
        val operation = mOperationGate.capture()
        if (PKG_FORCE_FRESH_LAUNCH == packageName && targetDisplayId > 0) {
            AppLogger.i(TAG, "moveTaskToDisplay: force fresh launch for $packageName")
            fallbackLaunch(packageName, targetDisplayId, callback, operation)
            return
        }

        sMoveTaskExecutor.execute {
            if (!operation.isValid) return@execute
            try {
                val taskId = findRunningTaskId(packageName)
                if (!operation.isValid) return@execute
                if (taskId == -1) {
                    AppLogger.w(TAG, "moveTaskToDisplay: no task for $packageName → fallback")
                    fallbackLaunch(packageName, targetDisplayId, callback, operation)
                    return@execute
                }

                if (sMoveTaskToDisplayAvailable == false) {
                    AppLogger.d(TAG, "moveTaskToDisplay: method unavailable on ROM → fallback launch")
                    if (!operation.isValid) return@execute
                    fallbackLaunch(packageName, targetDisplayId, callback, operation)
                    return@execute
                }

                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val iatm = atmClass.getMethod("getService").invoke(null)
                val iAtmClass: Class<*> = iatm.javaClass
                if (!operation.isValid) return@execute
                iAtmClass.getMethod("moveTaskToDisplay",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(iatm, taskId, targetDisplayId)
                AppLogger.i(TAG, "moveTaskToDisplay taskId=" + taskId +
                    " → display=" + targetDisplayId + " OK")

                if (targetDisplayId > 0) {
                    try {
                        Thread.sleep(300)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                    if (!operation.isValid) return@execute
                    // WINDOWING_MODE_FREEFORM = 5
                    try {
                        iAtmClass.getMethod("setTaskWindowingMode",
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType)
                            .invoke(iatm, taskId, 5, true)
                        AppLogger.i(TAG, "setTaskWindowingMode(FREEFORM) OK")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "setTaskWindowingMode: " + e.message)
                    }
                    // Fill the panel post-move (v1.8.2 — no more inset margins; a per-app
                    // hand-drawn rectangle is re-applied afterwards by InsetAutoApplicator).
                    try {
                        if (!operation.isValid) return@execute
                        var cw = mInputForwarder?.getClusterWidth() ?: 1920
                        var ch = mInputForwarder?.getClusterHeight() ?: 720
                        if (cw <= 0) cw = 1920
                        if (ch <= 0) ch = 720
                        val bounds = Rect(0, 0, cw, ch)
                        iAtmClass.getMethod("resizeTask",
                            Int::class.javaPrimitiveType, Rect::class.java,
                            Int::class.javaPrimitiveType)
                            .invoke(iatm, taskId, bounds, 1 /* RESIZE_MODE_FORCED */)
                        AppLogger.i(TAG, "resizeTask $bounds OK")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "resizeTask: " + e.message)
                    }
                }

                mMainHandler.post {
                    if (!operation.isValid) return@post
                    callback?.onResult(true)
                }
            } catch (e: Exception) {
                val stripped = (e is NoSuchMethodException) || (e.cause is NoSuchMethodException)
                if (stripped && sMoveTaskToDisplayAvailable == null) {
                    sMoveTaskToDisplayAvailable = false
                    AppLogger.w(TAG, "moveTaskToDisplay stripped on ROM — using launcher fallback")
                } else if (!stripped) {
                    AppLogger.e(TAG, "moveTaskToDisplay error", e)
                }
                if (!operation.isValid) return@execute
                fallbackLaunch(packageName, targetDisplayId, callback, operation)
            }
        }
    }

    private fun fallbackLaunch(packageName: String?, targetDisplayId: Int,
                               callback: LaunchCallback?, operation: LifecycleGate.Token) {
        mMainHandler.post {
            if (!operation.isValid) return@post
            if (targetDisplayId > 0) {
                launchOnDashboard(packageName, callback)
            } else {
                val ok = mLauncher.launchOnMainDisplay(packageName!!)
                callback?.onResult(ok)
            }
        }
    }

    /**
     * Resolves this package's flags and the current HOME handler, then asks
     * [ProjectionSafetyPolicy]. Fails OPEN: if PackageManager cannot answer, the launch
     * proceeds exactly as before — a lookup failure is not evidence that a package is dangerous,
     * and this guard must never be the reason a working car stops projecting.
     */
    private fun isProjectionAllowed(packageName: String?): Boolean {
        var isHome = false
        try {
            val pm = packageManager
            val home = pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            isHome = home != null && packageName == home.activityInfo.packageName
        } catch (t: Throwable) {
            AppLogger.d(TAG, "projection safety check inconclusive for " + packageName +
                " (" + t.javaClass.simpleName + ") — allowing")
            return true
        }
        val v = ProjectionSafetyPolicy.verdict(packageName, isHome)
        if (v == ProjectionSafetyPolicy.Verdict.ALLOWED) return true
        AppLogger.w(TAG, "refusing to project " + packageName + " — " +
            ProjectionSafetyPolicy.reason(v))
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch on cluster
    // ─────────────────────────────────────────────────────────────────────────

    fun launchOnDashboard(packageName: String?, callback: LaunchCallback?) {
        val operation = mOperationGate.capture()
        if (!operation.isValid) return
        // Backstop for the paths the app picker does not gate: auto-launch, saved layouts, the
        // headless boot launch, and a target persisted from before this build. Hiding it in the
        // list is the primary defence; refusing here is what makes it unreachable.
        if (!isProjectionAllowed(packageName)) {
            callback?.onResult(false)
            return
        }
        AppLogger.log(TAG, "launchOnDashboard — 2s delay → $packageName")
        val inFlight = mPendingLaunchRunnable
        if (inFlight != null) {
            mMainHandler.removeCallbacks(inFlight)
            mPendingLaunchRunnable = null
            val prev = mPendingDashboardCallback
            if (prev != null) {
                mPendingDashboardCallback = null
                prev.onResult(false)
            }
        }
        mPendingDashboardCallback = callback
        val launchRunnable = Runnable {
            // Cancellation bookkeeping stays on the MAIN thread: these two fields are
            // read/written from launchOnDashboard() and onDestroy() on the main looper,
            // so they must be cleared here (still on main) before the executor hop.
            mPendingLaunchRunnable = null
            mPendingDashboardCallback = null
            if (operation.isValid) {
                // Hop the whole cascade off the main thread. cleanFissionStacks() is a
                // synchronous binder/proxy transact, applyForLaunch() does a shell round-trip
                // and startActivityViaIAM() bootstraps the uid-2000 proxy daemon — none of that
                // may run on the UI thread (ANR when the daemon is cold). Reusing the existing
                // single-thread sMoveTaskExecutor keeps this launch serialized behind any
                // in-flight moveTaskToDisplay task-move, so no new races are introduced.
                sMoveTaskExecutor.execute {
                    if (!operation.isValid) return@execute
                    val displayId = mDisplayHelper.getKnownClusterDisplayId()
                    AppLogger.i(TAG, "Launching on display=$displayId → $packageName")
                    if (displayId <= 0) {
                        AppLogger.w(TAG, "launchOnDashboard: cluster display not ready (id=" +
                            displayId + ") — aborting launch for " + packageName)
                        postLaunchResult(callback, false, operation)
                        return@execute
                    }
                    if (!operation.isValid) return@execute
                    try {
                        val cleanLog = ProxyClient.cleanFissionStacks(displayId)
                        AppLogger.d(TAG, "cleanFissionStacks($displayId)\n$cleanLog")
                    } catch (ce: Throwable) {
                        AppLogger.w(TAG, "cleanFissionStacks failed: " + ce.message)
                    }
                    if (!operation.isValid) return@execute
                    val needsDpiSettle = ClusterDpiManager.applyForLaunch(this, packageName, displayId)
                    val doLaunch = Runnable doLaunch@{
                        if (!operation.isValid) return@doLaunch
                        try {
                            val launchIntent = packageManager.getLaunchIntentForPackage(packageName!!)
                            if (launchIntent == null) {
                                AppLogger.e(TAG, "No launch intent for $packageName")
                                postLaunchResult(callback, false, operation)
                                return@doLaunch
                            }
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            val opts = ActivityOptions.makeBasic()
                            opts.setLaunchDisplayId(displayId)
                            if (displayId > 0) {
                                applyClusterFreeformBounds(opts, displayId, packageName, operation)
                            }
                            if (!operation.isValid) return@doLaunch
                            // ── DAEMON-PRIMARY launch (matches DaemonConfig: the uid-2000 proxy daemon
                            // is the DEFAULT privileged path in ALL modes; the app-side path is a fallback
                            // gated by use_legacy_path). Layout mode already launches via the daemon —
                            // this unifies normal mode. On unprivileged DL5 the app-side launch is
                            // cross-user DENIED anyway, so the daemon is essential there; on DL3 the daemon
                            // `am start --display` is the same cascade fission/layout uses successfully.
                            // The app-side path stays as the fallback (use_legacy_path ON, daemon down, or
                            // the daemon launch failed).
                            val clW = clusterWidthOr(1920)
                            val clH = clusterHeightOr(720)
                            val legacyPath = DaemonConfig.isLegacyPathEnabled(this)
                            var daemonTried = false
                            if (!legacyPath && ProxyClient.isConnected()) {
                                daemonTried = true
                                if (daemonLaunchSync(packageName, displayId, clW, clH, operation)) {
                                    AppLogger.i(TAG, "launchOnDashboard OK (daemon) → $packageName")
                                    postLaunchResult(callback, true, operation)
                                    return@doLaunch
                                }
                                AppLogger.w(TAG, "daemon launch failed — falling back to app-side for $packageName")
                            }

                            // App-side fallback. startActivityViaIAM RETURNS false when IAM reflection
                            // failed but its internal startActivity(opts) fallback DID launch the app —
                            // the normal DL3 path, a SUCCESS that must be reported so the app is tracked
                            // (green bar / resize / stop all depend on it). It only THROWS when every
                            // app-side attempt failed. 1.6.106 conflated the two (INC-20260705-195632
                            // Telenav / 195856 Waze — app shown but never tracked).
                            var iamOk: Boolean
                            var iamThrew = false
                            if (!operation.isValid) return@doLaunch
                            try {
                                iamOk = startActivityViaIAM(launchIntent, opts, displayId)
                            } catch (iamErr: Throwable) {
                                AppLogger.w(TAG, "startActivityViaIAM threw (" +
                                    iamErr.javaClass.simpleName + ": " + iamErr.message + ")")
                                iamOk = false
                                iamThrew = true
                            }
                            if (!operation.isValid) return@doLaunch
                            // DL4 joins DL5 here for the same structural reason: on DL4 the app process
                            // is not allowed to see the cluster display at all (OEM
                            // DisplayManagerService whitelist), so an app-side setLaunchDisplayId is
                            // at best unverifiable — the daemon is the only path with a proven view of
                            // that display. Whether DL4 ALSO blocks cross-display launches for our uid
                            // is unproven; routing through the daemon removes the question either way.
                            // isDiLink4() is false on DL3 and on DL5, so neither changes.
                            val daemonOnlyLaunch = AdbLocalClient.isDiLink5Safe(this) ||
                                Platform.get().isDiLink4(this)
                            if (!iamOk && !daemonTried && daemonOnlyLaunch) {
                                // App-side DENIED / unverifiable and the daemon was not tried yet
                                // (use_legacy_path ON, or daemon was down at decision time) — the daemon
                                // HOLDS the cross-user permission, so it is the only path that can land it.
                                AppLogger.w(TAG, "app-side launch failed — routing via proxy daemon launchAndForce")
                                val ok = daemonLaunchSync(packageName, displayId, clW, clH, operation)
                                AppLogger.i(TAG, "launchOnDashboard → daemon force path (ok=$ok) → $packageName")
                                postLaunchResult(callback, ok, operation)
                            } else if (iamThrew) {
                                // Neither DL5 nor DL4 (i.e. DL3) and EVERY app-side attempt threw —
                                // a genuine failure.
                                AppLogger.e(TAG, "launchOnDashboard failed (app-side) → $packageName")
                                postLaunchResult(callback, false, operation)
                            } else {
                                // iamOk==true, OR (non-DL5 && returned false = startActivity fallback
                                // launched it — the normal DL3 path). Report success so it is tracked.
                                AppLogger.i(TAG, "launchOnDashboard OK → $packageName")
                                postLaunchResult(callback, true, operation)
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "launchOnDashboard error for $packageName", e)
                            postLaunchResult(callback, false, operation)
                        }
                    }
                    // DPI settle delay is now applied on THIS background thread (ClusterDpiManager
                    // documents that the caller must delay SETTLE_MS off the main thread) instead of
                    // a main-handler postDelayed, so the whole cascade stays on the executor.
                    if (needsDpiSettle) {
                        try {
                            Thread.sleep(ClusterDpiManager.SETTLE_MS)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                    if (!operation.isValid) return@execute
                    doLaunch.run()
                }
            }
        }
        mPendingLaunchRunnable = launchRunnable
        mMainHandler.postDelayed(launchRunnable, 2000)
    }

    fun launchOnDashboardWithBounds(packageName: String?,
                                    left: Int, top: Int, right: Int, bottom: Int,
                                    callback: LaunchCallback?) {
        val operation = mOperationGate.capture()
        if (!operation.isValid) return
        AppLogger.log(TAG, "launchOnDashboardWithBounds 500ms → " + packageName +
            " [" + left + "," + top + "," + right + "," + bottom + "]")
        // Keep the 500ms schedule on the main handler (preserves timing + cancel semantics),
        // then hop the whole binder/proxy/shell cascade onto the shared single-thread move-task
        // executor so the main thread never blocks and launches stay serialized behind any
        // in-flight task-move.
        mMainHandler.postDelayed({
            if (operation.isValid) {
                sMoveTaskExecutor.execute {
                    if (!operation.isValid) return@execute
                    val displayId = mDisplayHelper.getKnownClusterDisplayId()
                    if (displayId <= 0) {
                        AppLogger.w(TAG, "launchOnDashboardWithBounds: cluster display not ready (id=" +
                            displayId + ") — aborting launch for " + packageName)
                        postLaunchResult(callback, false, operation)
                        return@execute
                    }
                    if (!operation.isValid) return@execute
                    if (displayId > 0) {
                        try {
                            ProxyClient.cleanFissionStacks(displayId)
                        } catch (ce: Throwable) {
                            AppLogger.w(TAG, "cleanFissionStacks(WithBounds) failed: " + ce.message)
                        }
                    }
                    if (!operation.isValid) return@execute
                    val needsDpiSettle = ClusterDpiManager.applyForLaunch(this, packageName, displayId)
                    val doLaunchWithBounds = Runnable doLaunch@{
                        if (!operation.isValid) return@doLaunch
                        try {
                            val launchIntent = packageManager.getLaunchIntentForPackage(packageName!!)
                            if (launchIntent == null) {
                                AppLogger.e(TAG, "launchOnDashboardWithBounds: no intent for $packageName")
                                postLaunchResult(callback, false, operation)
                                return@doLaunch
                            }
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            val opts = ActivityOptions.makeBasic()
                            opts.setLaunchDisplayId(displayId)
                            try {
                                val setWM = ActivityOptions::class.java
                                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                                setWM.isAccessible = true
                                setWM.invoke(opts, 5)
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "setLaunchWindowingMode: " + e.message)
                            }
                            try {
                                val setLB = ActivityOptions::class.java
                                    .getDeclaredMethod("setLaunchBounds", Rect::class.java)
                                setLB.isAccessible = true
                                setLB.invoke(opts, Rect(left, top, right, bottom))
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "setLaunchBounds: " + e.message)
                            }
                            // Same chain as launchOnDashboard: IAM first, then the proxy daemon's
                            // launchAndForce cascade as the DL5 fallback (shell `am start --display`
                            // provably lands the app on display 0 on some DX_BYD_AUTO ROMs).
                            if (!operation.isValid) return@doLaunch
                            val iamOkWB = startActivityViaIAM(launchIntent, opts, displayId)
                            if (!operation.isValid) return@doLaunch
                            if (!iamOkWB && AdbLocalClient.isDiLink5Safe(this)) {
                                AppLogger.w(TAG, "DL5: IAM fell back to startActivity (WithBounds) — routing via proxy daemon launchAndForce")
                                val wbW = right - left
                                val wbH = bottom - top
                                // Use the synchronous, verdict-checked daemon launch — same path
                                // launchOnDashboard uses. The old launchViaDaemonForce was
                                // fire-and-forget, so WithBounds reported unconditional success even
                                // when the DL5 daemon launch actually failed, mistracking the app.
                                val okWB = daemonLaunchSync(packageName, displayId,
                                    if (wbW > 0) wbW else clusterWidthOr(1920),
                                    if (wbH > 0) wbH else clusterHeightOr(720), operation)
                                AppLogger.i(TAG, "launchOnDashboardWithBounds → daemon force path (ok=" +
                                    okWB + ") → " + packageName)
                                postLaunchResult(callback, okWB, operation)
                            } else {
                                AppLogger.i(TAG, "launchOnDashboardWithBounds OK display=$displayId")
                                postLaunchResult(callback, true, operation)
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "launchOnDashboardWithBounds error", e)
                            postLaunchResult(callback, false, operation)
                        }
                    }
                    // DPI settle delay applied on this background thread (see launchOnDashboard).
                    if (needsDpiSettle) {
                        try {
                            Thread.sleep(ClusterDpiManager.SETTLE_MS)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                    if (!operation.isValid) return@execute
                    doLaunchWithBounds.run()
                }
            }
        }, 500)
    }

    /**
     * Marshals a launch callback result back onto the main looper. The launch cascades now
     * run on `sMoveTaskExecutor`; per the hardening contract only `onResult` is
     * allowed back on the main thread. Follows the same drop-on-destroy contract already used
     * by [fallbackLaunch] and moveTaskToDisplayInternal so we never call back into a
     * torn-down service.
     */
    private fun postLaunchResult(callback: LaunchCallback?, success: Boolean,
                                 operation: LifecycleGate.Token) {
        if (callback == null) return
        mMainHandler.post {
            if (!operation.isValid) return@post
            callback.onResult(success)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyClusterFreeformBounds(opts: ActivityOptions, displayId: Int,
                                           packageName: String?, operation: LifecycleGate.Token) {
        if (!operation.isValid) return
        try {
            val setWM = ActivityOptions::class.java
                .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            setWM.isAccessible = true
            setWM.invoke(opts, 5)
        } catch (e: Exception) {
            AppLogger.w(TAG, "setLaunchWindowingMode: " + e.message)
        }
        val sz = Point(1920, 720)
        try {
            val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager
            val d = dm?.getDisplay(displayId)
            if (d != null) {
                d.getRealSize(sz)
            } else {
                // DL4: no Display object obtainable (OEM whitelist). Use the geometry the
                // uid-2000 daemon read from `dumpsys display` rather than the 1920x720 literal,
                // so a DL4 variant with a different panel still gets correct launch bounds.
                // Null on DL3/DL5 → the literal above stands, exactly as before.
                val info = ClusterDisplayRegistry.forDisplayId(displayId)
                if (info != null && info.width > 0 && info.height > 0) {
                    sz.set(info.width, info.height)
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "getRealSize: " + e.message)
        }
        // Normalize: cluster is always landscape; getRealSize() can report portrait dimensions
        // after a portrait app (e.g. TikTok) triggered a VirtualDisplay rotation on DL3.
        if (sz.x > 0 && sz.y > 0 && sz.y > sz.x) {
            val tmp = sz.x
            sz.x = sz.y
            sz.y = tmp
            AppLogger.w(TAG, "applyClusterFreeformBounds: portrait VD detected — swapped to " +
                sz.x + "x" + sz.y)
        }
        // v1.8.2 — always launch onto the FULL panel. This used to inset the launch bounds by
        // the per-app/global margins AND then apply the same margins again as a display overscan;
        // the two stacked, so an 80/50 setting removed 160/100 of usable area on every side pair
        // (measured 1600x520 of a 1920x720 panel — INC-20260725-211405). Shrinking is now the sole
        // job of the per-app hand-drawn rectangle, re-applied after launch by InsetAutoApplicator.
        val bounds = Rect(0, 0, sz.x, sz.y)
        try {
            val setLB = ActivityOptions::class.java
                .getDeclaredMethod("setLaunchBounds", Rect::class.java)
            setLB.isAccessible = true
            setLB.invoke(opts, bounds)
            AppLogger.i(TAG, "cluster FREEFORM bounds=$bounds display=$displayId")
        } catch (e: Exception) {
            AppLogger.w(TAG, "setLaunchBounds: " + e.message)
        }
    }

    // Returns true if IAM reflection succeeded, false if fell back to startActivity().
    // Callers on DL5 can use the return value to decide whether to retry via shell.
    private fun startActivityViaIAM(intent: Intent, opts: ActivityOptions, displayId: Int): Boolean {
        try {
            val amClass = Class.forName("android.app.ActivityManager")
            val iam = amClass.getMethod("getService").invoke(null)
            val iAmClass = Class.forName("android.app.IActivityManager")
            val iAppThreadClass = Class.forName("android.app.IApplicationThread")
            val profilerInfoClass = Class.forName("android.app.ProfilerInfo")
            iAmClass.getMethod("startActivityAsUser",
                iAppThreadClass, String::class.java, Intent::class.java,
                String::class.java, IBinder::class.java, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, profilerInfoClass,
                Bundle::class.java, Int::class.javaPrimitiveType)
                .invoke(iam, null, packageName, intent,
                    null, null, null, 0, 0, null, opts.toBundle(), -2)
            return true
        } catch (ex: Exception) {
            AppLogger.w(TAG, "startActivityViaIAM → fallback startActivity: " + ex.message)
            try {
                startActivity(intent, opts.toBundle())
            } catch (e2: Exception) {
                // DL3 cold-launch: launching a not-yet-running app onto the fission
                // VirtualDisplay with FREEFORM + launch bounds NPEs in the WM
                // (ActivityStack.getBounds() on a stack that doesn't exist yet —
                // INC-20260625-123938). Retry targeting only the display (no FREEFORM,
                // no bounds) so the task is created on the cluster; the inset bounds are
                // re-applied afterwards by the auto-resize path. DL5 never reaches this
                // catch — there startActivity(opts) succeeds (it lands on the wrong
                // display) and launchViaDaemonForce takes over, so DL5 is unchanged.
                AppLogger.w(TAG, "fallback startActivity(opts) failed (" + e2.message +
                    ") — retrying plain launch on display " + displayId)
                val plain = ActivityOptions.makeBasic()
                if (displayId > 0) plain.setLaunchDisplayId(displayId)
                startActivity(intent, plain.toBundle())
            }
            return false
        }
    }

    private fun clusterWidthOr(fallback: Int): Int {
        val w = mInputForwarder?.getClusterWidth() ?: 0
        return if (w > 0) w else fallback
    }

    private fun clusterHeightOr(fallback: Int): Int {
        val h = mInputForwarder?.getClusterHeight() ?: 0
        return if (h > 0) h else fallback
    }

    // DL5 fallback when IAM fails and a shell `am start --display N` provably lands the
    // app on display 0 (DX_BYD_AUTO HDMI cluster — the live `displayId=1 realActivity`
    // query stays empty). Routes through the proxy daemon's launchAndForce cascade, which
    // follows the launch with the privileged moveRootTaskToDisplay + an async watchdog that
    // re-anchors the task on the cluster display — the same path fission uses. Runs off the
    // main thread because launchAndForce blocks for several seconds.
    @Suppress("unused") // dead since daemonLaunchSync replaced it; kept as the documented DL5 fallback
    private fun launchViaDaemonForce(packageName: String?, displayId: Int,
                                     width: Int, height: Int) {
        val operation = mOperationGate.capture()
        sMoveTaskExecutor.execute {
            if (!operation.isValid) return@execute
            try {
                if (!ProxyClient.isConnected()) ProxyClient.connect(this)
                if (!operation.isValid) return@execute
                val log = ProxyClient.launchAndForce(packageName, null, displayId, width, height)
                // Log the FULL cascade result, not just the first line — we need to see
                // whether moveRootTaskToDisplay / setTaskWindowingModeFreeform / resizeTask
                // actually succeeded on this ROM (INC-20260621-201303: app reaches display 1
                // but ends up mode=fullscreen and the user still sees nothing).
                AppLogger.i(TAG, "DL5 daemon launchAndForce result:\n" +
                    (if (log != null && log.isNotEmpty()) log else "(empty)"))
            } catch (t: Throwable) {
                AppLogger.e(TAG, "DL5 daemon launchAndForce failed: " + t.message)
            }
            // AAOS-only experiment: on Android Automotive head units the normal launch lands the
            // app on the logical cluster display (visible in our preview) but never on the
            // physical cluster panel. Probe the AAOS `start-fixed-activity` mechanism — see the
            // method for details. No-op on DL3/DL5 fission ROMs.
            if (!operation.isValid) return@execute
            tryClusterFixedActivityExperiment(displayId, packageName, operation)
            // Post-launch verification: ~1.5 s after the cascade, dump what is actually on
            // the cluster display (package / visible / windowing mode / bounds) so the next
            // bug report shows whether the app stayed put and in which mode it rendered.
            // Runs on the diagnostic executor so its 1.5 s sleep never holds the move-task
            // worker (frees it immediately for a queued eviction / task-move).
            // AUD-PERF-P3 — opt-in (Diagnostics screen), OFF by default. This dump shells
            // `dumpsys SurfaceFlinger`, taking SF's global lock 1.5 s into the projected app's
            // cold start. NOTE: tryClusterFixedActivityExperiment above is deliberately NOT
            // gated by this — despite the name it invokes `cmd car_service start-fixed-activity`,
            // which has a real effect on AAOS units, so gating it could regress DX_BYD_AUTO.
            if (ClusterPrefs.isLaunchDiagnosticsEnabled(this)) {
                sDiagExecutor.execute { verifyClusterDisplayState(displayId, packageName, operation) }
            }
        }
    }

    /**
     * Synchronous daemon launch (uid 2000): runs the `launchAndForce` cascade
     * (am start --display + moveRootTaskToDisplay + resize) and RETURNS whether the app
     * was launched, so the caller can register it and decide a fallback. Used as the
     * daemon-PRIMARY path in [launchOnDashboard] (DaemonConfig design). MUST run
     * off the main thread — `launchAndForce` blocks for several seconds; callers
     * already run on `sMoveTaskExecutor`. The diagnostic post-launch verify is
     * scheduled on `sDiagExecutor` so it never holds the move-task worker.
     *
     * Success = the daemon did not report its own "FAIL: no task discovered" verdict AND
     * the am transcript shows an accepted start. The daemon's verdict wins, because it is the
     * only signal that actually proves a task exists; the transcript alone can look like a
     * success on a launch that system_server threw away.
     */
    private fun daemonLaunchSync(packageName: String?, displayId: Int,
                                 width: Int, height: Int,
                                 operation: LifecycleGate.Token): Boolean {
        if (!operation.isValid) return false
        var ok: Boolean
        try {
            if (!ProxyClient.isConnected()) ProxyClient.connect(this)
            if (!operation.isValid) return false
            val log = ProxyClient.launchAndForce(packageName, null, displayId, width, height)
            if (!operation.isValid) return false
            val low = log?.lowercase(Locale.ROOT) ?: ""
            // The daemon polls for the task after `am start` and appends its OWN verdict:
            // "FAIL: no task discovered for <pkg>" when nothing came up. That verdict is
            // authoritative and MUST win — the am transcript above it can read like a success
            // ("Starting: Intent {…}") while system_server threw and no activity ever started
            // (DiLink 3.0 FREEFORM stack creation NPE, INC-20260714-215700). Trusting the
            // transcript alone reported ok=true on a launch that showed nothing on the
            // cluster, and suppressed the app-side fallback.
            val daemonSaysFail = low.contains("fail: no task discovered")
            // The daemon's poll-based verdict is authoritative: if it did NOT append
            // "FAIL: no task discovered", a task WAS discovered on the cluster display. Do NOT
            // also require an am-transcript keyword ("status: ok" / "starting: intent") —
            // launchAndForce does not emit a positive verdict string when a task is found, so
            // ANDing that in vetoed task-PROVEN launches: it dropped the app's tracking (green
            // bar / resize / stop) and forced the app-side fallback (cross-user DENIED on DL5).
            // Only guard against EMPTY output (a transport error before the daemon ran), which
            // cannot count as success. Do NOT infer failure from "exception" either — the
            // cascade's optional-method reflection probes (setDisplayToSingleTaskInstance /
            // setTaskWindowingModeFreeform) log a BENIGN NoSuchMethodException even on success.
            ok = low.isNotEmpty() && !daemonSaysFail
            AppLogger.i(TAG, "daemon launchAndForce result (ok=" + ok + "):\n" +
                (if (log != null && log.isNotEmpty()) log else "(empty)"))
        } catch (t: Throwable) {
            AppLogger.e(TAG, "daemon launchAndForce failed: " + t.message)
            ok = false
        }
        // AAOS-only experiment + post-launch verify — diagnostic, off the critical path.
        if (!operation.isValid) return false
        tryClusterFixedActivityExperiment(displayId, packageName, operation)
        // AUD-PERF-P3 — opt-in (Diagnostics screen), OFF by default. This dump shells
        // `dumpsys SurfaceFlinger`, taking SF's global lock 1.5 s into the projected app's
        // cold start. NOTE: tryClusterFixedActivityExperiment above is deliberately NOT
        // gated by this — despite the name it invokes `cmd car_service start-fixed-activity`,
        // which has a real effect on AAOS units, so gating it could regress DX_BYD_AUTO.
        if (ClusterPrefs.isLaunchDiagnosticsEnabled(this)) {
            sDiagExecutor.execute { verifyClusterDisplayState(displayId, packageName, operation) }
        }
        return ok
    }

    /**
     * Diagnostic only (no behaviour change): after a DL5 daemon launch, query the
     * cluster display's top tasks and log them to the journal. Helps determine whether
     * the launched app stays on the display and in which windowing mode / bounds.
     */
    private fun verifyClusterDisplayState(displayId: Int, packageName: String?,
                                          operation: LifecycleGate.Token) {
        if (!operation.isValid) return
        try {
            Thread.sleep(1500)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        if (!operation.isValid) return
        // v1.6.108 — decisive pump-vs-placement capture (INC-20260705-175936). The app is known
        // to reach the cluster display at the WM level; the open question is whether the OEM
        // fission container actually forwards a FOREIGN window on the composed cluster output
        // (layerStack 2 / fission_bg_XDJAScreenProjection) to the physical panel. Capture both
        // the WM task state AND the SurfaceFlinger layers on the cluster surface (is the app's
        // layer present + non-empty visibleRegion on layerStack 2?). Combined with the app-side
        // mirror state below — the mirror reads layerStack 2, i.e. EXACTLY what the panel is fed —
        // this disambiguates "container pump ignores foreign content" (preview shows the app but
        // the panel is blank) from "window never composited to layerStack 2".
        val cmd = "echo '--- WM tasks on display " + displayId + " ---';" +
            " dumpsys activity activities 2>/dev/null" +
            "   | grep -A 25 'Display #" + displayId + "'" +
            "   | grep -E 'Stack #|Task\\{|mResumed|visible=' | head -20;" +
            " echo '--- SurfaceFlinger cluster layers (" + packageName + ") ---';" +
            " dumpsys SurfaceFlinger 2>/dev/null" +
            "   | grep -iE 'fission_bg_XDJAScreenProjection|" + packageName + "|layerStack|visibleRegion'" +
            "   | head -40"
        ShellGateway.execShellWithResult(this, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                if (!operation.isValid) return
                val mirrorActive = mMirrorManager?.isMirrorActive() ?: false
                AppLogger.i(TAG, "DL5 post-launch display " + displayId + " state (" +
                    packageName + ") — in-app mirror active=" + mirrorActive +
                    " [mirror reads layerStack 2 = exact panel content]:\n" +
                    (if (out == null || out.trim().isEmpty())
                        "(nothing on display $displayId)" else out.trim()))
            }

            override fun onError(err: String?) {
                if (!operation.isValid) return
                AppLogger.w(TAG, "DL5 post-launch verify failed: $err")
            }
        })
    }

    /**
     * EXPERIMENT — Android Automotive (AAOS) clusters only. On these head units (e.g.
     * DX_BYD_AUTO / Bosch, INC-20260624-221542) the instrument cluster panel is driven by the
     * AAOS cluster-rendering pipeline (InstrumentClusterService + IAutomotiveDisplayProxyService),
     * NOT by a raw scan-out of the logical display. So a normal `am start --display N` puts
     * the app on the logical cluster display (it shows in our SurfaceControl preview) but never on
     * the physical cluster, where only the OEM system nav is presented.
     *
     * `cmd car_service start-fixed-activity <displayId> <pkg> <activity>` is the AAOS way to
     * pin an activity on a display in "fixed" mode. This probes whether it (a) is permitted on this
     * production/user build and (b) actually reaches the physical cluster. The full result is logged
     * for the bug report. Gated to `FEATURE_AUTOMOTIVE` so DL3/DL5 fission ROMs — where the
     * normal launch already works — are left completely untouched.
     */
    private fun tryClusterFixedActivityExperiment(displayId: Int, packageName: String?,
                                                  operation: LifecycleGate.Token) {
        if (!operation.isValid) return
        if (displayId <= 0 || packageName == null || packageName.isEmpty()) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return // not AAOS — do not disturb working fission ROMs
        }
        // Resolve the package's launcher activity, normalise a relative ".Name" to a full
        // component, then ask car_service to pin it on the cluster display in fixed mode.
        val cmd =
            "PKG=" + packageName + " ; DID=" + displayId + " ; " +
            "COMP=\$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER \"\$PKG\" 2>/dev/null | tail -1) ; " +
            "ACT=\${COMP#*/} ; case \"\$ACT\" in .*) ACT=\"\$PKG\$ACT\" ;; esac ; " +
            "echo \"[exp] resolved=\$COMP -> start-fixed-activity \$DID \$PKG \$ACT\" ; " +
            "cmd car_service start-fixed-activity \"\$DID\" \"\$PKG\" \"\$ACT\" 2>&1"
        if (!operation.isValid) return
        ShellGateway.execShellWithResult(this, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                if (!operation.isValid) return
                AppLogger.i(TAG, "AAOS start-fixed-activity experiment (" + packageName +
                    " → display " + displayId + "):\n" +
                    (if (out == null || out.trim().isEmpty()) "(no output)" else out.trim()))
            }

            override fun onError(err: String?) {
                if (!operation.isValid) return
                AppLogger.w(TAG, "AAOS start-fixed-activity experiment error: $err")
            }
        })
    }

    // DL5 shell fallback used when IAM reflection fails and startActivity() ignores
    // setLaunchDisplayId on a real HDMI secondary display (e.g. DX_BYD_AUTO).
    // Intentionally omits --windowingMode 5: some ROMs silently reject --display N
    // when FREEFORM mode is requested on a display that does not support it, causing
    // the app to land on display=0 instead.
    @Suppress("unused") // dead; kept as the documented DL5 shell fallback
    private fun startActivityViaShellSimple(packageName: String?, displayId: Int,
                                            launchIntent: Intent?) {
        val cn: ComponentName? = launchIntent?.component
        if (cn == null) {
            AppLogger.e(TAG, "startActivityViaShellSimple: no component for $packageName")
            return
        }
        val component = cn.packageName + "/" + cn.className
        val cmd = "am force-stop " + packageName + " 2>&1; " +
            "am start --display " + displayId +
            " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER" +
            " -n " + component + " 2>&1"
        AppLogger.i(TAG, "DL5 shell fallback (no windowingMode): $cmd")
        ShellGateway.execShellWithResult(this, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                AppLogger.i(TAG, "DL5 shell fallback → " + (out?.trim() ?: ""))
            }

            override fun onError(err: String?) {
                AppLogger.e(TAG, "DL5 shell fallback ERROR: $err")
            }
        })
    }

    @Suppress("unused") // dead; kept as the documented DL5 shell fallback
    private fun startActivityViaShell(packageName: String?, displayId: Int,
                                      launchIntent: Intent?) {
        val cn: ComponentName? = launchIntent?.component
        if (cn == null) {
            AppLogger.e(TAG, "startActivityViaShell: cannot resolve component for $packageName")
            return
        }
        val component = cn.packageName + "/" + cn.className
        val cmd = "am force-stop " + packageName + " 2>&1; " +
            "am start --display " + displayId +
            " --windowingMode 5" +
            " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER" +
            " -n " + component +
            " --activity-clear-task 2>&1"
        AppLogger.i(TAG, "DL5 launch via shell: $cmd")
        ShellGateway.execShellWithResult(this, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                AppLogger.i(TAG, "DL5 am start → " + (out?.trim() ?: ""))
            }

            override fun onError(err: String?) {
                AppLogger.e(TAG, "DL5 am start ERROR: $err")
            }
        })
    }

    fun restartProjection() {
        AppLogger.log(TAG, "restartProjection requested natively")
        mProjectionActive = true
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_cluster_initializing)))
        startNativeProjection()
    }

    fun stopProjectionNoAdb() {
        AppLogger.log(TAG, "stopProjectionNoAdb requested")
        // v1.8.2 — was hardcoded "-d 1". The fission VirtualDisplay is display 1 on most DiLink 3
        // units but not all (INC-20260721 saw it come up as display 3), and resetting the wrong
        // display both misses the cluster and touches a display we do not own. Never display 0:
        // the id is the one the helper resolved, and ShellGateway blocks -d 0 outright anyway.
        val clusterId = mDisplayHelper.getKnownClusterDisplayId()
        if (clusterId > 0 && !AdbLocalClient.isDiLink5Safe(this)) {
            ShellGateway.execShell(this, "wm overscan reset -d $clusterId")
        }
        try {
            ClusterDpiManager.restore(this, mDisplayHelper.getKnownClusterDisplayId())
        } catch (t: Throwable) {
            AppLogger.w(TAG, "DPI restore (stopProjectionNoAdb) failed: " + t.message)
        }
        mProjectionActive = false
        mDisplayHelper.stopWithoutAdb()
        // The projection session is over: drop the daemon-resolved geometry so nothing downstream
        // can start a mirror / inject touch on a display that is no longer projected. This used to
        // be cleared ONLY from onDashboardDisplayDisconnected, which on DL4 essentially never
        // fires (its producers need the app process to observe the display — the very thing the
        // OEM whitelist forbids). No-op on DL3/DL5, where the registry is never populated.
        ClusterDisplayRegistry.clear()
        // Tear down the mirror NOW (local token + daemon SurfaceControl via stopPreview) so
        // SurfaceFlinger stops compositing the cluster immediately on a real stop, instead of
        // waiting for the async stopSelf()→onDestroy()→release(). Background keepalive is not
        // affected: stopProjectionNoAdb() is only called on an explicit restore/stop.
        mMirrorManager!!.stopMirror()
        mLauncher.setDashboardDisplayId(-1)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "stopForeground failed: " + t.message)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIF_ID)
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DashboardDisplayHelper.Listener
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDashboardDisplayConnected(display: Display?, displayId: Int) {
        AppLogger.log(TAG, "Cluster display connected: id=$displayId")
        mLauncher.setDashboardDisplayId(displayId)
        mInputForwarder?.setClusterDisplay(display)
        mInputForwarder?.setClusterDisplayId(displayId)
        // DiLink 4.0 has no Display object to hand around (the OEM DisplayManagerService
        // whitelist hides the id from our uid, so dm.getDisplay() returns null): the call above
        // no-ops and the forwarder would keep its 1920x720 compile-time defaults. Feed it the
        // geometry the uid-2000 daemon read out of `dumpsys display` instead. Double-gated —
        // display==null never happens on DL3/DL5, and only the DL4 activation path writes
        // ClusterDisplayRegistry, so both platforms skip this entirely.
        if (display == null) {
            val info = ClusterDisplayRegistry.forDisplayId(displayId)
            if (info != null) {
                mInputForwarder?.setClusterGeometry(displayId, info.width, info.height)
                latchPanelGeometry(info.width, info.height)
            }
        } else {
            val size = Point()
            try {
                display.getRealSize(size)
                latchPanelGeometry(size.x, size.y)
            } catch (ignored: Throwable) {
                // Unreadable geometry latches nothing — the policy stays on the configured type.
            }
        }
        updateNotification(getString(R.string.notif_cluster_active, displayId))
        // v1.8.2 — the global overscan is gone: the cluster is always driven full-screen and
        // only a per-app hand-drawn rectangle (ClusterResizeActivity) may shrink it afterwards.
        // We RESET here rather than simply not applying, because `wm overscan` is display state
        // that survives in WindowManager: a unit upgrading from a build that set 80,50 would
        // otherwise keep the old inset with no UI left to clear it (INC-20260725-211405, where
        // the 80/50 default combined with the launch bounds to eat 40% of the panel).
        if (displayId > 0 && !AdbLocalClient.isDiLink5Safe(this)) {
            ShellGateway.execShell(this, "wm overscan reset -d $displayId")
            AppLogger.i(TAG, "wm overscan reset on display $displayId (full-screen cluster)")
        }
        mListener?.onClusterDisplayConnected(display, displayId)

        // Headless boot auto-launch (armed in onStartCommand): the cluster is now up, so launch the
        // configured app onto it — no MainActivity needed. Record it as the cluster app on success
        // so a later MainActivity open adopts it (via getLastClusterPkg / sBootLaunchedPkg) instead
        // of relaunching (which would force-stop the running nav). Fires once.
        val armed = mBootAutoLaunchPkg
        if (displayId > 0 && armed != null) {
            mBootAutoLaunchPkg = null
            AppLogger.i(TAG, "boot auto-launch → $armed on display $displayId")
            launchOnDashboard(armed, object : LaunchCallback {
                override fun onResult(success: Boolean) {
                    AppLogger.i(TAG, "boot auto-launch result=$success → $armed")
                    // Mark it so a later MainActivity open adopts it (shows the mirror) instead of
                    // relaunching — see MainActivity.onSendToDashboard's sBootLaunchedPkg guard.
                    if (success) sBootLaunchedPkg = armed
                }
            })
        }
    }

    override fun onDashboardDisplayDisconnected() {
        AppLogger.log(TAG, "Cluster display disconnected")
        mLauncher.setDashboardDisplayId(-1)
        // Projection ended — the boot-launched app is no longer on the cluster, so drop the latch
        // (defensive: the consume-once in MainActivity is the primary clear).
        sBootLaunchedPkg = null
        // Nothing downstream may keep using a dead display's geometry. No-op on DL3/DL5.
        ClusterDisplayRegistry.clear()

        // The mirror was compositing onto the display that just went away, and nothing here used to
        // tell it so. stopProjectionNoAdb tears it down; this path did not, so mMirrorActive stayed
        // true against a dead surface — and because every start is guarded by that flag, the mirror
        // could never be started again for the rest of the process's life. On a head unit that runs
        // for weeks, "never again" means what it says: the preview stays black until the app is
        // force-stopped, and nothing on screen explains why.
        try {
            mMirrorManager?.stopMirror()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "mirror teardown on disconnect failed: " + t.message)
        }
        // On a DL3 single-OS car the "disconnect" is really "activation timed out because no
        // VirtualDisplay can exist" — tell the user projection is unavailable rather than the
        // generic "disconnected" (which reads like a transient glitch they should retry).
        // Same treatment on a DL4 whose firmware hides the display from BOTH the app process and
        // the uid-2000 daemon: there is nothing the user can retry.
        val titleRes = when {
            isDl3SingleOsFission() -> R.string.dl3_singleos_cluster_unsupported_title
            isDl4ProjectionBlocked() -> R.string.dl4_cluster_unsupported_title
            else -> R.string.notif_cluster_disconnected
        }
        updateNotification(getString(titleRes))
        val listener = mListener
        if (listener != null) {
            listener.onClusterDisplayDisconnected()
        } else {
            // Nobody is listening (MainActivity is stopped). Latch it so setListener can replay
            // the edge on re-attach; otherwise the UI and the persisted cluster package stay
            // wrong for the rest of the session and beyond. See setListener for the full note.
            mMissedDisconnect = true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_cluster_channel_name),
            NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notif_cluster_channel_desc)
        channel.setShowBadge(false)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        if (mNotifPi == null) {
            val tapIntent = Intent(this, MainActivity::class.java)
            tapIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            mNotifPi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(mNotifPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "ClusterService"

        // v1.3.7-beta — probe result for IActivityTaskManager.moveTaskToDisplay availability.
        //   null = unknown (try), TRUE = available, FALSE = stripped (skip reflection)
        @Volatile private var sMoveTaskToDisplayAvailable: Boolean? = null

        private const val CHANNEL_ID = "cluster_projection"
        private const val NOTIF_ID = 1

        @Volatile
        @JvmField
        var sIsRunning = false

        private val sTaskMoveOwnershipLock = Any()

        @JvmStatic
        fun isRunning(): Boolean = sIsRunning

        @Volatile private var sInstance: ClusterService? = null

        @JvmStatic
        fun getInstance(): ClusterService? = sInstance

        // ── Background executor for task-move operations ────────────────────
        private val sMoveTaskExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            val t = Thread(r, "move-task-thread")
            t.isDaemon = true
            t
        }

        // ── Separate executor for diagnostic-only post-launch verification ──
        // verifyClusterDisplayState sleeps ~1.5s before a dumpsys; it must NOT run on
        // sMoveTaskExecutor (the single move-task worker) or it serializes ahead of a
        // pending moveTaskToDisplay/eviction, delaying app return-to-display-0. It has
        // no bearing on the launch outcome, so it runs off the critical path here.
        private val sDiagExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            val t = Thread(r, "cluster-diag-thread")
            t.isDaemon = true
            t
        }

        private const val PKG_FORCE_FRESH_LAUNCH = "com.telenav.app.arp"

        /** Set by BootReceiver when it starts us at boot: also auto-launch the configured app. */
        const val EXTRA_BOOT_AUTOLAUNCH = "boot_autolaunch"

        /** Re-arms a stopped-but-still-bound service before restarting native projection. */
        const val ACTION_RESTART_PROJECTION =
            "com.byd.dashcast.action.RESTART_CLUSTER_PROJECTION"

        /**
         * The app this service auto-launched at boot (headless), so MainActivity doesn't relaunch it
         * and force-stop a running nav. Null until a boot auto-launch succeeds.
         */
        @Volatile
        @JvmField
        var sBootLaunchedPkg: String? = null

        /** Runs one cleanup move atomically with respect to service ownership acquisition. */
        @JvmStatic
        @Throws(Exception::class)
        fun runTaskMoveWhileStopped(move: StoppedTaskMove): Boolean {
            synchronized(sTaskMoveOwnershipLock) {
                if (sIsRunning) return false
                return move.run()
            }
        }

        internal fun claimTaskMoveOwnership() {
            synchronized(sTaskMoveOwnershipLock) {
                sIsRunning = true
            }
        }

        internal fun releaseTaskMoveOwnership() {
            synchronized(sTaskMoveOwnershipLock) {
                sIsRunning = false
            }
        }

        // Preserved static helpers — still used by callers that have direct access to dump strings.
        @Suppress("unused")
        internal fun parseTaskIdFromDumpsysRecents(dump: String?, packageName: String?): Int =
            ProxyTaskFinder.parseFromRecents(dump, packageName)

        @Suppress("unused")
        internal fun parseTaskIdFromDumpsysActivities(dump: String?, packageName: String?): Int =
            ProxyTaskFinder.parseFromActivities(dump, packageName)

        /**
         * Resolve once, at service start, whether this ROM still has
         * `IActivityTaskManager.moveTaskToDisplay`.
         *
         * Used to be a side effect: the deferred re-anchor was, on DiLink 3, the call that happened
         * to reach the reflection first and latch the flag to FALSE. Routing that re-anchor through
         * the daemon removed the side effect, and with it the only primer on a path where every
         * launch goes through `fallbackLaunch` (a cold app has no task, so the reflection is never
         * reached). The flag then stayed `null`, [isMoveTaskToDisplaySupported] kept answering
         * "supported", and `MainActivity`'s DiLink-3 workaround — force-stop the previous cluster
         * app before launching the next — never armed. The second app would land split-screen on the
         * main display: exactly the `ActivityStack.getBounds` NPE of INC-20260621-130238 that the
         * workaround exists to prevent. Caught by adversarial review, not by a test.
         *
         * A lookup, never an invoke — it cannot move or disturb anything.
         */
        private fun resolveMoveTaskToDisplaySupport(): Boolean? {
            val known = sMoveTaskToDisplayAvailable
            if (known != null) return known
            try {
                // The INTERFACE, not a live binder. An earlier version resolved
                // ActivityTaskManager.getService() and inspected the returned Stub$Proxy, which asks
                // a question about a class through an object: getService() can return null before
                // activity_task is registered (NPE, answered "inconclusive" for a reason unrelated
                // to the method), can block on ServiceManager, and forced the whole probe onto a
                // worker thread — which is the only reason there was ever a race to reason about.
                // The AIDL method is a property of the interface; verified against this ROM's own
                // decompiled framework.jar, where IActivityTaskManager declares no
                // moveTaskToDisplay at all.
                Class.forName("android.app.IActivityTaskManager")
                    .getMethod("moveTaskToDisplay",
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                sMoveTaskToDisplayAvailable = true
                return true
            } catch (nsme: NoSuchMethodException) {
                // Means "unreachable from this process" — stripped by the OEM, OR hidden-API filtered,
                // which surfaces identically because Android's enforcement makes a blocked member
                // invisible to reflection (see the 1.8.24 hidden-API work: the bypass ships in the
                // DAEMON process, not this one). Both are the same verdict for every consumer of this
                // flag: the in-process fallback cannot reparent a task either way. On DiLink 3 the
                // daemon dump settles which it is — genuine absence.
                sMoveTaskToDisplayAvailable = false
                AppLogger.i(TAG, "moveTaskToDisplay unreachable on this ROM — launcher fallback will " +
                    "be used, and the stop-previous-app guard is armed")
                return false
            } catch (t: Throwable) {
                // ClassNotFoundException on a ROM without the interface at all, or a LinkageError.
                // Genuinely inconclusive: leave the flag null so the NEXT reader probes again rather
                // than latching a guess for the life of the process.
                AppLogger.d(TAG, "moveTaskToDisplay probe inconclusive (" +
                    t.javaClass.simpleName + ": " + t.message + ") — will retry")
                return null
            }
        }
    }
}
