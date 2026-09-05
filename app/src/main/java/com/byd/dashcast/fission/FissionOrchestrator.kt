package com.byd.dashcast.fission

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.R
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.cluster.display.ClusterManager
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.domain.cluster.ProjectionStateProvider
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.DaemonBinderResolver
import com.byd.dashcast.proxy.DaemonConfig
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.proxy.daemon.TaskLaunchRecovery
import com.byd.dashcast.util.AppLogger

import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encapsulates all background fission logic: daemon acquisition, slot lifecycle
 * (attach / reuse / resize / release), mirror start/stop, and layout switching.
 *
 * ### Architecture improvements over original FissionActivity
 *  - **No static coupling to ClusterService**: normal projection state is queried via
 *    [ProjectionStateProvider], which is trivially stub-able in tests.
 *  - **No static binder field on FissionLayoutEditorActivity**: callers receive the binder through
 *    [Callbacks.onDaemonBinderAcquired] and pass it via Intent extras (using existing
 *    `BinderParcelable`) when starting the editor.
 *  - **Single executor thread**: all background work is serialised on one daemon thread (same as
 *    original), preventing concurrent VD operations that confuse the XDJA fission daemon.
 *
 * ### Threading contract
 * All public methods are main-thread safe and dispatch heavy work to the internal executor.
 * [Callbacks] are always invoked on the main thread.
 *
 * Kotlin port note: every `!=`/`==` the Java used on OBJECT references is `!==`/`===` here.
 * Kotlin's `==` is equals(), and these comparisons are all identity checks on orchestrators and
 * binders — getting one wrong would let a stale instance pass for the live one.
 */
class FissionOrchestrator(
    context: Context,
    private val mProjectionState: ProjectionStateProvider,
    private val mCallbacks: Callbacks
) {

    // ── Slot state (value type) ───────────────────────────────────────────────

    class SlotState internal constructor(
        @JvmField val pkg: String,
        @JvmField val label: String,
        @JvmField val displayId: Int,
        @JvmField val layerStack: Int,
        rect: Rect
    ) {
        @JvmField var rect: Rect = Rect(rect)
    }

    /** Immutable target consumed by Main's tactile Layout mirror. */
    class LayoutMirrorTarget internal constructor(slot: SlotState) {
        @JvmField val pkg: String = slot.pkg
        @JvmField val label: String = slot.label
        @JvmField val displayId: Int = slot.displayId
        @JvmField val layerStack: Int = slot.layerStack
        @JvmField val width: Int = slot.rect.width()
        @JvmField val height: Int = slot.rect.height()
    }

    // ── Callbacks (all on main thread) ────────────────────────────────────────

    interface Callbacks {
        /** Called whenever the slot map changes (add / remove / resize). */
        fun onSlotsChanged(slots: Collection<SlotState>)
        /** Called with the acquired daemon binder so the caller can pass it to sub-screens. */
        fun onDaemonBinderAcquired(binder: IBinder?)
        /** Status message for UI display; null = idle / clear. */
        fun onStatusMessage(message: String?)
        /** Called when an unrecoverable error occurs starting a slot. */
        fun onSlotError(pkg: String?, message: String?)
        /** Called when normal projection is active and must be stopped first. */
        fun onProjectionConflict(proceedCallback: Runnable?)
    }

    enum class AutoStartResult {
        DISABLED,
        ALREADY_STARTED,
        MISSING_LAYOUT,
        PROJECTION_CONFLICT,
        STARTED
    }

    /** Listener notified (on the main thread) when the headless orchestrator's slot set changes. */
    fun interface LayoutChangeListener { fun onLayoutPackagesChanged() }

    /** Outcome of [activateLayoutManually]; always delivered on the main thread. */
    fun interface ActivationCallback {
        /**
         * @param ok    `true` when every zone of the preset got a live display
         * @param error `null` when the activation ran to completion (check `ok` for partial
         *              success), otherwise one of the `ERR_*` codes above, or a raw exception
         *              message. Always English — see [ERR_CLUSTER_TIMEOUT].
         */
        fun onActivationResult(ok: Boolean, error: String?)
    }

    // ── State ────────────────────────────────────────────────────────────────

    private val mAppCtx: Context = context.applicationContext
    private val mMainHandler = Handler(Looper.getMainLooper())
    private val mExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "fission-exec")
        t.isDaemon = true
        t
    }

    private val mSlots = ConcurrentHashMap<String, SlotState>()

    @Volatile private var mDaemonBinder: IBinder? = null
    @Volatile private var mProjecting = false
    @Volatile private var mMirrorReady = false
    @Volatile private var mDestroyed = false
    // volatile: written on the fission-exec thread (initAsync / activateFavoriteLayout) and
    // on the main thread (switchToLayoutAsync), read on the main thread (getActiveLayout) —
    // without volatile a background write may not be visible to a later main-thread read
    // (stale layout-selector label/checkmark). Siblings above are already volatile.
    @Volatile private var mFirstDisplayId = -1
    @Volatile private var mActiveLayout: LayoutPreset? = null
    @Volatile private var mSelectedMirrorPackage: String? = null
    @Volatile private var mAutoStartAttempt = false
    @Volatile private var mActivationGuardToken = 0L
    @Volatile private var mClusterActivationManager: ClusterManager? = null

    /**
     * Daemon slot keys of the layout zones that have NO app bound ("free zones"), created by the
     * last manual activation. Deliberately kept out of [mSlots]: those keys are package names and
     * feed [getActiveLayoutPackages] and Main's mirror selector.
     */
    private val mFreeZoneKeys: MutableSet<String> = LinkedHashSet()

    private fun surfaceBinderForTactile(reason: String): IBinder? {
        var current = mDaemonBinder
        if (current != null) return current
        val fresh = DaemonBinderResolver.reacquireSurfaceBinder(reason) ?: return null
        var adopted = false
        synchronized(this) {
            if (mDaemonBinder == null) {
                mDaemonBinder = fresh
                adopted = true
            }
            current = mDaemonBinder
        }
        if (adopted) {
            val published = current
            post { mCallbacks.onDaemonBinderAcquired(published) }
        }
        return current
    }

    private fun recoverSurfaceBinderIfCurrent(failed: IBinder?, reason: String): IBinder? {
        synchronized(this) {
            if (mDaemonBinder !== failed) return mDaemonBinder
            mDaemonBinder = null
        }
        var fresh = DaemonBinderResolver.reacquireSurfaceBinder(reason)
        if (fresh === failed || (fresh != null && !fresh.isBinderAlive)) fresh = null
        var adopted = false
        synchronized(this) {
            if (mDaemonBinder == null) {
                mDaemonBinder = fresh
                adopted = true
            }
            fresh = mDaemonBinder
        }
        if (adopted) {
            val published = fresh
            post { mCallbacks.onDaemonBinderAcquired(published) }
        }
        return fresh
    }

    @Synchronized
    private fun selectedMirrorTarget(): LayoutMirrorTarget? {
        val ordered = orderedSlotPackages()
        mSelectedMirrorPackage = LayoutSlotSelection.resolve(mSelectedMirrorPackage, ordered)
        val slot = if (mSelectedMirrorPackage != null) mSlots[mSelectedMirrorPackage] else null
        return if (slot != null) LayoutMirrorTarget(slot) else null
    }

    private fun orderedSlotPackages(): List<String> {
        val ordered = ArrayList<String>()
        val layout = mActiveLayout
        if (layout != null) {
            for (slot in layout.slots) {
                val pkg = slot.packageName
                if (pkg != null && mSlots.containsKey(pkg) && !ordered.contains(pkg)) {
                    ordered.add(pkg)
                }
            }
        }
        val extras = ArrayList(mSlots.keys)
        Collections.sort(extras)
        for (pkg in extras) if (!ordered.contains(pkg)) ordered.add(pkg)
        return ordered
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called on Activity.onDestroy() — shuts down executor and releases slots if finishing. */
    fun destroy(isFinishing: Boolean) {
        mDestroyed = true
        abandonClusterActivation()
        mMainHandler.removeCallbacksAndMessages(null)
        if (isFinishing && mSlots.isNotEmpty()) {
            val binder = mDaemonBinder
            val pkgs = ArrayList(mSlots.keys)
            submitQuietly("destroy teardown") {
                val keepVds = ClusterPrefs.isFissionPrecreateSlots(mAppCtx)
                for (pkg in pkgs) {
                    // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
                    if (binder != null) FissionClient.moveToDisplay0(binder, pkg)
                    if (!keepVds) {
                        if (binder != null) {
                            try {
                                FissionClient.releaseSlot(binder, pkg)
                                FissionReleaseDebt.settled(pkg)
                            } catch (error: Throwable) {
                                FissionReleaseDebt.record(pkg)
                            }
                        } else {
                            FissionReleaseDebt.record(pkg)
                        }
                    }
                    ShellGateway.execShell(mAppCtx, "am force-stop $pkg")
                }
                if (binder != null) {
                    try { FissionClient.stopMirror(binder) } catch (ignored: Throwable) {}
                }
            }
        }
        mExec.shutdown()
    }

    /**
     * Shuts down the single-thread executor gracefully (an in-flight stopAll/teardown task still
     * runs to completion). Call after stopAll() on a throwaway headless orchestrator that is never
     * destroy()'d, so its "fission-exec" worker thread doesn't leak.
     */
    fun shutdown() {
        mExec.shutdown()
    }

    /** Probes the daemon and fires auto-layout / pre-create if configured. */
    fun initAsync(favoriteLayout: LayoutPreset?, autoLayout: Boolean, precreate: Boolean) {
        submitQuietly("initAsync") {
            tryGetBinder()
            if (favoriteLayout != null) {
                post { mCallbacks.onSlotsChanged(mSlots.values) }
                if (autoLayout) {
                    post { mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_autoactivate)) }
                    // submitOrFail, not mExec.execute: onDisplayReady runs on the main looper
                    // seconds later, and Restore-BYD / a new auto-start can shut this executor
                    // down in between (pre-existing crash on this path, same shape as the
                    // manual one).
                    ensureClusterProjectionThen(
                            Runnable { submitOrFail(Runnable { activateFavoriteLayout() }, null) })
                } else if (precreate) {
                    precreateSlots(favoriteLayout)
                }
            }
        }
    }

    /**
     * Drives Qt into projection mode before running `next`, if it isn't already.
     *
     * Auto-layout used to launch the bound apps without checking the cluster state: after a
     * "restore BYD" (sendInfo 18+0) Qt renders natively, the mirror layerStack targets a surface Qt
     * owns, and every launched app stays invisible. The full activation sequence (30→16→35 or warm
     * path) must complete first.
     */
    private fun ensureClusterProjectionThen(next: Runnable) {
        ensureClusterProjectionThen(next, null)
    }

    /**
     * Same as [ensureClusterProjectionThen] with an explicit failure hook.
     *
     * @param onFailure run when the cluster never reaches projection mode. `null` — the auto-start
     *                  path — keeps the previous behaviour byte for byte; the user-triggered path
     *                  passes one so the failure reaches the UI.
     */
    private fun ensureClusterProjectionThen(next: Runnable, onFailure: Runnable?) {
        if (ClusterManager.isQtInProjectionMode()) {
            next.run()
            return
        }
        AppLogger.i(TAG, "auto-layout: Qt in native mode — activating cluster projection first")
        post {
            mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_projection))
            abandonClusterActivation()
            val manager = ClusterManager(mAppCtx)
            mClusterActivationManager = manager
            manager.activateClusterDisplay(object : ClusterManager.DisplayReadyCallback {
                override fun onDisplayReady(display: Display?, displayId: Int) {
                    if (mClusterActivationManager !== manager || mDestroyed) return
                    AppLogger.i(TAG, "auto-layout: cluster projection ready (display=$displayId)")
                    next.run()
                }
                override fun onDisplayTimeout() {
                    if (mClusterActivationManager !== manager || mDestroyed) return
                    mClusterActivationManager = null
                    manager.abandon()
                    AppLogger.w(TAG, "auto-layout: cluster activation timed out — aborted")
                    post { mCallbacks.onStatusMessage(null) }
                    markAutoStartFailed("cluster activation timeout")
                    onFailure?.run()
                }
                // No-op (matches the Kotlin-interface default body): the auto-layout flow doesn't
                // act on a late-arriving display. Kept explicit so the intent stays readable.
                override fun onDisplayLateReady(display: Display?, displayId: Int) {}
            })
        }
    }

    private fun abandonClusterActivation() {
        val manager = mClusterActivationManager
        mClusterActivationManager = null
        manager?.abandon()
    }

    /**
     * Starts a slot for the given package in the given bounds. Checks for normal projection
     * conflict before proceeding.
     *
     * @param surfaceHolder the SurfaceHolder for the cluster preview (may be `null` when called
     *                      from layout pre-create; mirror start is skipped then)
     */
    fun startSlot(pkg: String, label: String, rect: Rect, surfaceHolder: SurfaceHolder?) {
        if (mProjectionState.isProjectionActive()) {
            post {
                mCallbacks.onProjectionConflict(Runnable {
                    mProjectionState.stopProjectionIfActive(null)
                    mMainHandler.postDelayed({ startSlot(pkg, label, rect, surfaceHolder) }, 400)
                })
            }
            return
        }
        post { mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_starting_fmt, label)) }
        submitQuietly("startSlot $pkg") {
            try {
                doStartSlot(pkg, label, rect, surfaceHolder)
            } catch (e: Exception) {
                AppLogger.e(TAG, "startSlot error pkg=$pkg", e)
                post {
                    mCallbacks.onSlotsChanged(mSlots.values)
                    mCallbacks.onSlotError(pkg, e.message)
                    mCallbacks.onStatusMessage(null)
                }
            }
        }
    }

    fun stopAll() {
        stopAll(null)
    }

    fun stopAll(onComplete: Runnable?) {
        stopAll(false, onComplete)
    }

    private fun stopAllAndPurge(onComplete: Runnable?) {
        stopAll(true, onComplete)
    }

    private fun stopAll(purgeDaemonSlots: Boolean, onComplete: Runnable?) {
        abandonClusterActivation()
        post { mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_stopping)) }
        val accepted = submitQuietly("stopAll") {
            // Free zones hold no app and are not in mSlots, so the teardown plan below never
            // sees them — release them first or a stop leaves an orphaned overlay on the
            // cluster. No-op unless a manual activation created some.
            releaseFreeZones()
            val packages = ArrayList(mSlots.keys)
            if (!purgeDaemonSlots && !mProjecting && packages.isEmpty()) {
                mMainHandler.post {
                    mCallbacks.onStatusMessage(null)
                    if (onComplete != null) onComplete.run()
                }
                return@submitQuietly
            }
            mProjecting = false
            mMirrorReady = false
            // When "Pre-create slots on startup" is on, keep VDs alive so they persist
            // for the next session — only kill apps and move them back to display 0.
            val keepVds = ClusterPrefs.isFissionPrecreateSlots(mAppCtx)
            val binder = mDaemonBinder
            val unreleased = FissionTeardownPlan.run(
                    packages, keepVds, object : FissionTeardownPlan.Operations {
                override fun moveToDisplay0(packageName: String): String? {
                    if (binder == null) throw IllegalStateException("mirror daemon unavailable")
                    val result = FissionClient.moveToDisplay0(binder, packageName)
                    if (result == null || (!result.startsWith("OK ") &&
                                    !result.startsWith("SKIP ") &&
                                    !result.startsWith("no task for "))) {
                        throw IllegalStateException(result ?: "empty move result")
                    }
                    AppLogger.i(TAG, "Layout teardown move verified packageName=$packageName → $result")
                    return result
                }

                override fun forceStopAndWait(packageName: String): Boolean = forceStopAndWaitForResult(packageName)

                override fun releaseSlot(packageName: String) {
                    if (binder == null) throw IllegalStateException("mirror daemon unavailable")
                    FissionClient.releaseSlot(binder, packageName)
                    FissionReleaseDebt.settled(packageName)
                }

                override fun onStepError(packageName: String, step: String, error: Throwable) {
                    AppLogger.e(TAG, "Layout teardown $step failed for $packageName: " + error.message)
                }
            })
            FissionReleaseDebt.recordAll(unreleased)
            mSlots.clear()
            mSelectedMirrorPackage = null
            if (purgeDaemonSlots && binder != null) {
                try {
                    FissionClient.deactivateLayout(binder)
                    FissionReleaseDebt.clearAll()
                } catch (error: Exception) {
                    AppLogger.e(TAG, "global slot purge failed: " + error.message)
                }
            }
            if (binder != null) {
                try { FissionClient.stopMirror(binder) } catch (ignored: Throwable) {}
            }
            mDaemonBinder = null
            mFirstDisplayId = -1
            mMainHandler.post {
                mCallbacks.onSlotsChanged(mSlots.values)
                mCallbacks.onDaemonBinderAcquired(null)
                mCallbacks.onStatusMessage(null)
                if (onComplete != null) onComplete.run()
            }
        }
        if (!accepted && onComplete != null) {
            // The executor was already shut down, so nothing above will ever run — but the caller
            // is waiting on this continuation (stopAutoOrchestrator hands it its completion).
            // Run it anyway, on the same looper the accepted path uses, so no caller hangs.
            mMainHandler.post(onComplete)
        }
    }

    /** Worker-thread only: waits for the shared removeTask + force-stop + PID verification path. */
    private fun forceStopAndWaitForResult(pkg: String): Boolean {
        val done = CountDownLatch(1)
        val killed = AtomicBoolean(false)
        AdbLocalClient.forceStopApp(mAppCtx, pkg, object : AdbLocalClient.Callback {
            override fun onSuccess(result: String?) {
                killed.set(true)
                AppLogger.i(TAG, "Layout teardown force-stop verified pkg=$pkg → $result")
                done.countDown()
            }

            override fun onError(error: String?) {
                AppLogger.w(TAG, "Layout teardown force-stop failed pkg=$pkg → $error")
                done.countDown()
            }
        })
        try {
            if (!done.await(20, TimeUnit.SECONDS)) {
                AppLogger.w(TAG, "Layout teardown force-stop timeout pkg=$pkg")
                return false
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return killed.get()
    }

    fun releaseSlotAsync(pkg: String) {
        submitQuietly("releaseSlotAsync $pkg") {
            // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
            val binder = mDaemonBinder
            if (binder != null) FissionClient.moveToDisplay0(binder, pkg)
            if (binder != null) {
                try {
                    FissionClient.releaseSlot(binder, pkg)
                    FissionReleaseDebt.settled(pkg)
                } catch (e: Exception) {
                    FissionReleaseDebt.record(pkg)
                    AppLogger.e(TAG, "releaseSlot error", e)
                }
            } else {
                FissionReleaseDebt.record(pkg)
            }
            ShellGateway.execShell(mAppCtx, "am force-stop $pkg")
            mSlots.remove(pkg)
            mSelectedMirrorPackage = LayoutSlotSelection.resolve(
                    mSelectedMirrorPackage, orderedSlotPackages())
            mProjecting = mSlots.isNotEmpty()
            post { mCallbacks.onSlotsChanged(mSlots.values) }
        }
    }

    fun resizeSlotAsync(pkg: String, rect: Rect) {
        val requestedRect = Rect(rect)
        submitQuietly("resizeSlotAsync $pkg") {
            var resized = false
            try {
                val binder = mDaemonBinder
                if (binder != null) {
                    resized = FissionClient.resizeSlot(binder, pkg,
                            requestedRect.left, requestedRect.top,
                            requestedRect.width(), requestedRect.height())
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "resizeSlot error", e)
            }
            val slot = mSlots[pkg]
            if (!applyAcceptedResize(slot, requestedRect, resized)) {
                AppLogger.w(TAG, "resizeSlot rejected pkg=$pkg")
            }
            post { mCallbacks.onSlotsChanged(mSlots.values) }
        }
    }

    fun switchToLayoutAsync(newLayout: LayoutPreset?) {
        submitQuietly("switchToLayoutAsync") {
            try {
                switchActiveLayout(newLayout, null)
            } catch (e: Exception) {
                AppLogger.e(TAG, "switchToLayout error", e)
                post { mCallbacks.onSlotError("layout", e.message) }
            } finally {
                post { mCallbacks.onSlotsChanged(mSlots.values) }
            }
        }
    }

    fun getSlots(): Collection<SlotState> = mSlots.values
    fun isProjecting(): Boolean = mProjecting
    fun getActiveLayout(): LayoutPreset? = mActiveLayout
    /** The SURFACE daemon's binder (see [FissionClient]), or `null` if not resolved. */
    fun getSurfaceDaemonBinder(): IBinder? = mDaemonBinder

    // ── Background logic ───────────────────────────────────────────────────────

    private fun tryGetBinder() {
        val b = FissionClient.getBinderFromServiceManager()
        if (b != null) {
            mDaemonBinder = b
            retryReleaseDebt(b)
            post { mCallbacks.onDaemonBinderAcquired(b) }
            AppLogger.d(TAG, "Daemon binder found in ServiceManager")
        }
    }

    private fun ensureDaemon(): Boolean {
        if (mProjectionState.isProjectionActive()) {
            post { mCallbacks.onProjectionConflict(null) }
            return false
        }
        var b = FissionClient.getBinderFromServiceManager()
        if (b != null && sDaemonFreshnessChecked) {
            mDaemonBinder = b
            if (!retryReleaseDebt(b)) return false
            val fb0 = b
            post { mCallbacks.onDaemonBinderAcquired(fb0) }
            return true
        }
        // A LIVE binder is not proof of a CURRENT daemon. The SurfaceDaemon is a separate
        // uid-2000 process that survives an APK reinstall, and its ServiceManager registration
        // survives with it — so after an app update the previous build's daemon answers every
        // transaction while looking perfectly healthy. That is how a capture comes back full of
        // plausible, useless log lines, and how a new per-package slot key meets an old
        // handleDeactivateLayout that still filters on the "layout_" prefix and releases nothing.
        //
        // The build comparison lives in SurfaceDaemonReusePolicy.shouldReuse, which is only
        // reached from AdbLocalClient.startMirrorDaemon — i.e. on the path this method used to
        // skip entirely whenever a binder existed. So fall through to it ONCE per process: it
        // reuses the daemon when the marker build matches (the binder survives, the poll below
        // finds it on the first 500 ms tick) and kills + respawns it when it does not.
        //
        // Fail-safe: if ADB-local is unreachable, startMirrorDaemon cannot kill anything, the
        // existing daemon stays registered and the poll finds it — we lose one tick, never the
        // daemon. The flag is set BEFORE the call so a failure cannot make this repeat forever.
        if (b != null) {
            AppLogger.i(TAG, "daemon binder present but not yet build-checked this process — " +
                    "validating against build " + BuildConfig.VERSION_CODE)
        }
        sDaemonFreshnessChecked = true
        post { mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_daemon)) }
        AdbLocalClient.startMirrorDaemon(mAppCtx)
        for (i in 0 until 16) {
            try { Thread.sleep(500) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
            b = FissionClient.getBinderFromServiceManager()
            if (b != null) {
                mDaemonBinder = b
                if (!retryReleaseDebt(b)) return false
                val fb = b
                post { mCallbacks.onDaemonBinderAcquired(fb) }
                AppLogger.d(TAG, "Daemon binder acquired after " + ((i + 1) * 500) + "ms")
                return true
            }
        }
        AppLogger.e(TAG, "Daemon binder NOT found after 8s")
        return false
    }

    private fun retryReleaseDebt(binder: IBinder): Boolean {
        val remaining = FissionReleaseDebt.retry { key -> FissionClient.releaseSlot(binder, key) }
        if (remaining.isNotEmpty()) {
            AppLogger.w(TAG, "slot release debt still pending: $remaining")
            return false
        }
        return true
    }

    @Throws(Exception::class)
    private fun doStartSlot(pkg: String, label: String, rect: Rect,
                            surfaceHolder: SurfaceHolder?) {
        val isFirst = mSlots.isEmpty()
        var slotAcquired = false

        try {
            if (!ensureDaemon()) throw RuntimeException(mAppCtx.getString(R.string.fo_err_daemon))

            // ATTACH_SLOT or REUSE if VD already alive in daemon
            var existingId = -1
            try { existingId = FissionClient.querySlot(mDaemonBinder!!, pkg) } catch (ignored: Exception) {}
            val displayId: Int
            if (existingId > 0) {
                post { mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_reuse_fmt, label)) }
                val resized = FissionClient.resizeSlot(mDaemonBinder!!, pkg,
                        rect.left, rect.top, rect.width(), rect.height())
                if (!resized) {
                    throw IllegalStateException("existing slot resize rejected for $pkg")
                }
                displayId = existingId
                slotAcquired = true
                AppLogger.i(TAG, "FISSION REUSE_SLOT pkg=$pkg displayId=$displayId")
            } else {
                post { mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_create_fmt, label)) }
                val newId = FissionClient.attachSlot(mDaemonBinder!!, pkg,
                        rect.left, rect.top, rect.width(), rect.height())
                if (newId < 0) throw RuntimeException(mAppCtx.getString(R.string.fo_err_attach_fmt, pkg))
                displayId = newId
                slotAcquired = true
                AppLogger.i(TAG, "FISSION ATTACH_SLOT pkg=" + pkg + " displayId=" + displayId +
                        " rect=" + rect.left + "," + rect.top + "+" + rect.width() + "x" + rect.height())
            }

            // LAUNCH_AND_FORCE via ProxyClient
            post {
                mCallbacks.onStatusMessage(
                        mAppCtx.getString(R.string.fo_status_launching_fmt, label, displayId))
            }
            if (!ProxyClient.isConnected()) {
                AppLogger.d(TAG, "ProxyClient not connected — attempting connect…")
                val connected = ProxyClient.connect(mAppCtx)
                if (!connected) {
                    throw RuntimeException(mAppCtx.getString(R.string.fo_err_proxy))
                }
            }
            val launchResult = ProxyClient.launchAndForce(pkg, null, displayId,
                    rect.width(), rect.height())
            AppLogger.i(TAG, "FISSION launchAndForce result:\n$launchResult")
            if (!TaskLaunchRecovery.isSuccessful(launchResult)) {
                // The verdict is load-bearing, the way the teardown path already makes moveToDisplay0
                // load-bearing above. It used to be logged and stepped over: the slot was then
                // registered as live, the layout was reported "activated", and — because activation
                // success is what marks a layout as the auto-start favourite — a layout whose app never
                // started could be saved as the one to bring up on every boot. The driver sees an empty
                // cluster and an interface telling them it worked.
                AppLogger.w(TAG, "FISSION launchAndForce failed/incomplete: $launchResult")
                throw IllegalStateException("launch failed for $pkg: $launchResult")
            }

            val layerStack = resolveLayerStack(displayId)

            // MIRROR_START on first slot
            if (isFirst && surfaceHolder != null && surfaceHolder.surface != null
                    && surfaceHolder.surface.isValid) {
                post { mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_mirror)) }
                mFirstDisplayId = displayId
                var svW = surfaceHolder.surfaceFrame.width()
                var svH = surfaceHolder.surfaceFrame.height()
                if (svW <= 0 || svH <= 0) { svW = CLUSTER_W; svH = CLUSTER_H }
                mMirrorReady = FissionClient.startMirror(mDaemonBinder!!,
                        layerStack, rect.width(), rect.height(),
                        displayId, svW, svH, surfaceHolder.surface)
                AppLogger.i(TAG, "FISSION MIRROR_START displayId=$displayId ok=$mMirrorReady")
            }

            mSlots[pkg] = SlotState(pkg, label, displayId, layerStack, rect)
            mSelectedMirrorPackage = LayoutSlotSelection.resolve(
                    mSelectedMirrorPackage, orderedSlotPackages())
            mProjecting = true

            post {
                mCallbacks.onSlotsChanged(mSlots.values)
                mCallbacks.onStatusMessage(null)
            }
        } catch (error: Exception) {
            if (slotAcquired) rollbackStartedSlot(pkg)
            throw error
        }
    }

    @Throws(Exception::class)
    private fun doSwitchToLayout(newLayout: LayoutPreset?, surfaceHolder: SurfaceHolder?) {
        val targetSlots = LinkedHashMap<String, LayoutPreset.SlotDef>()
        if (newLayout != null) {
            for (s in newLayout.slots) {
                val name = s.packageName
                if (name != null && name.isNotEmpty()) {
                    targetSlots.putIfAbsent(name, s)
                }
            }
        }

        FissionLayoutSwitchPlan.run(
                ArrayList(mSlots.keys), targetSlots.keys,
                object : FissionLayoutSwitchPlan.Operations {
            override fun start(packageName: String) {
                val slot = targetSlots[packageName]
                        ?: throw IllegalStateException("missing target slot for $packageName")
                doStartSlot(packageName, getAppLabel(packageName), slot.toRect(), surfaceHolder)
            }

            override fun rollback(packageName: String) {
                rollbackStartedSlot(packageName)
            }

            override fun stop(packageName: String) {
                // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
                val binder = mDaemonBinder
                if (binder != null) FissionClient.moveToDisplay0(binder, packageName)
                if (binder != null) {
                    try {
                        FissionClient.releaseSlot(binder, packageName)
                        FissionReleaseDebt.settled(packageName)
                    } catch (error: Exception) {
                        FissionReleaseDebt.record(packageName)
                    }
                }
                ShellGateway.execShell(mAppCtx, "am force-stop $packageName")
                mSlots.remove(packageName)
            }
        })
        mSelectedMirrorPackage = LayoutSlotSelection.resolve(
                mSelectedMirrorPackage, orderedSlotPackages())
        mProjecting = mSlots.isNotEmpty()
    }

    /** Worker-thread rollback for slots successfully acquired by the failed switch attempt. */
    private fun rollbackStartedSlot(pkg: String) {
        val binder = mDaemonBinder
        val unreleased = FissionTeardownPlan.run(
                listOf(pkg), false, object : FissionTeardownPlan.Operations {
            override fun moveToDisplay0(packageName: String): String? {
                if (binder == null) throw IllegalStateException("mirror daemon unavailable")
                return FissionClient.moveToDisplay0(binder, packageName)
            }

            override fun forceStopAndWait(packageName: String): Boolean =
                    forceStopAndWaitForResult(packageName)

            override fun releaseSlot(packageName: String) {
                if (binder == null) throw IllegalStateException("mirror daemon unavailable")
                FissionClient.releaseSlot(binder, packageName)
                FissionReleaseDebt.settled(packageName)
            }

            override fun onStepError(packageName: String, step: String, error: Throwable) {
                AppLogger.e(TAG, "Layout activation rollback $step failed for $packageName: " +
                        error.message)
            }
        })
        FissionReleaseDebt.recordAll(unreleased)
        mSlots.remove(pkg)
        mSelectedMirrorPackage = LayoutSlotSelection.resolve(
                mSelectedMirrorPackage, orderedSlotPackages())
        mProjecting = mSlots.isNotEmpty()
    }

    private fun activateFavoriteLayout() {
        val fav = LayoutPrefs.getAutoStartLayout(mAppCtx)
        if (fav == null) {
            post { mCallbacks.onStatusMessage(null) }
            markAutoStartFailed("saved favourite layout disappeared")
            return
        }
        try {
            switchActiveLayout(fav, null)
        } catch (e: Exception) {
            AppLogger.e(TAG, "activateFavoriteLayout failed", e)
            post {
                mCallbacks.onStatusMessage(
                        mAppCtx.getString(R.string.fo_status_autolayout_err_fmt, e.message))
            }
            markAutoStartFailed("layout activation failed: " + e.message)
        } finally {
            // The success funnel of the auto-start path: the ClusterManager sequence is done, so
            // a manual Activate is safe again. markAutoStartFailed covers the failure funnel; the
            // guard's own expiry covers anything that reaches neither.
            if (sActivationGate.release(mActivationGuardToken)) {
                mAutoStartAttempt = false
            }
        }
    }

    /** True while this orchestrator can still accept work on its executor. */
    private fun isUsable(): Boolean = !mDestroyed && !mExec.isShutdown

    /**
     * Submits to `mExec` tolerating the shutdown race described on [submitOrFail].
     *
     * Same hazard, different callers: these submissions have no [ActivationCallback] to report to,
     * so a rejection is logged and swallowed instead of being delivered. Without this, a
     * `RejectedExecutionException` raised on the `fission-exec` thread itself — that thread has no
     * `UncaughtExceptionHandler` — reaches Android's KillApplicationHandler and takes the whole
     * process down (AUD-002).
     *
     * @return `true` when the task was accepted by the executor.
     */
    private fun submitQuietly(what: String, task: Runnable): Boolean {
        return try {
            mExec.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            AppLogger.w(TAG, "$what skipped: the orchestrator was already stopped")
            false
        }
    }

    /**
     * Submits to the executor from a thread that is **not** the executor — currently the main
     * looper, via `ClusterManager.DisplayReadyCallback`.
     *
     * `mExec` is a single-thread executor with the default AbortPolicy, so `execute()` after
     * `shutdown()` throws [RejectedExecutionException]. `onDisplayReady` is invoked directly on
     * the main looper with no try/catch, so that throw is a FATAL EXCEPTION on the main thread. It
     * is reachable: Activate preset B, then Deactivate (or Delete) preset A during the multi-second
     * cluster sequence — `stopAutoOrchestrator` shuts this executor down, and the display then
     * arrives.
     */
    private fun submitOrFail(task: Runnable, callback: ActivationCallback?) {
        try {
            mExec.execute(task)
        } catch (e: RejectedExecutionException) {
            AppLogger.w(TAG, "activation abandoned: the orchestrator was stopped while the " +
                    "cluster was activating")
            deliver(callback, false, ERR_ABANDONED)
        }
    }

    /**
     * Instance half of [activateLayoutManually]. Nothing runs on the caller's thread.
     *
     * @param purgeStaleDaemonSlots true when this orchestrator was just created, i.e. nothing in
     *                              this process owns the slots the daemon may still hold from an
     *                              earlier run — they must be dropped or the new layout inherits
     *                              overlays nobody tracks.
     */
    private fun activatePresetAsync(preset: LayoutPreset, purgeStaleDaemonSlots: Boolean,
                                    callback: ActivationCallback?) {
        // submitOrFail even for the FIRST submit: isUsable() was checked on the caller's thread
        // and another thread can shut this executor down before we get here. A raw execute()
        // would then throw on the UI thread AND leave the activation gate latched, disabling
        // Activate for the rest of the process.
        submitOrFail(Runnable {
            tryGetBinder()
            post {
                mCallbacks.onSlotsChanged(mSlots.values)
                mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_autoactivate))
            }
            ensureClusterProjectionThen(
                    Runnable {
                        submitOrFail(
                                Runnable { doActivatePreset(preset, purgeStaleDaemonSlots, callback) },
                                callback)
                    },
                    Runnable { deliver(callback, false, ERR_CLUSTER_TIMEOUT) })
        }, callback)
    }

    /** Executor-thread only. Cluster projection is already up when this runs. */
    private fun doActivatePreset(preset: LayoutPreset, purgeStaleDaemonSlots: Boolean,
                                 callback: ActivationCallback?) {
        try {
            if (mProjectionState.isProjectionActive()) {
                // Classic projection owns the cluster; stacking Layout overlays on it is the
                // conflict the orchestrator has always refused (ensureDaemon would refuse too,
                // but would report it as a missing daemon).
                post { mCallbacks.onStatusMessage(null) }
                deliver(callback, false, ERR_PROJECTION_CONFLICT)
                return
            }
            if (!ensureDaemon()) {
                post { mCallbacks.onStatusMessage(null) }
                deliver(callback, false, ERR_NO_DAEMON)
                return
            }
            if (purgeStaleDaemonSlots) {
                try {
                    FissionClient.deactivateLayout(mDaemonBinder!!)
                    FissionReleaseDebt.clearAll()
                } catch (ignored: Exception) {}
            }
            // One ATTACH_SLOT per bound app — keyed BY PACKAGE in the daemon, so the slot can
            // afterwards be queried, resized and released. The batch ACTIVATE_LAYOUT this
            // replaces keyed slots "layout_<label>_<i>" and never put the package on the wire,
            // which made every slot it created unaddressable. doSwitchToLayout also launches
            // each app itself, so no separate launch pass can target a slot that never existed.
            switchActiveLayout(preset, null)
            // Commit free-zone replacement only after every bound slot has started. Removing the
            // old overlays first damages the active layout when a later app start rolls back.
            releaseFreeZones()
            attachFreeZones(preset)
            val allOk = publishDisplayIds(preset)
            post {
                mCallbacks.onSlotsChanged(mSlots.values)
                mCallbacks.onStatusMessage(null)
            }
            deliver(callback, allOk, null)
        } catch (e: Exception) {
            AppLogger.e(TAG, "manual layout activation failed", e)
            post { mCallbacks.onStatusMessage(null) }
            // NEVER deliver a bare getMessage(): it is null for DeadObjectException — the very
            // exception a killed daemon throws from binder.transact — and a null error is the
            // wire value BOTH consumers read as "the activation ran to completion". That would
            // save a failed preset as the favourite, skip the auto-start re-arm, and toast
            // "partially activated" over a cluster with nothing on it. Always carry the class
            // name, which is also what makes this line greppable across the report corpus.
            var reason = e.javaClass.simpleName
            if (e.message != null) reason = reason + ": " + e.message
            deliver(callback, false, reason)
        }
    }

    /** Commits the logical active layout only if its physical slot switch completes. */
    @Throws(Exception::class)
    private fun switchActiveLayout(target: LayoutPreset?, surfaceHolder: SurfaceHolder?) {
        val previous = mActiveLayout
        mActiveLayout = target
        try {
            doSwitchToLayout(target, surfaceHolder)
        } catch (error: Exception) {
            mActiveLayout = previous
            mSelectedMirrorPackage = LayoutSlotSelection.resolve(
                    mSelectedMirrorPackage, orderedSlotPackages())
            throw error
        }
    }

    /**
     * Creates the overlay + VD of the zones with no app bound, so a manually activated layout still
     * paints every zone it painted under the batch call.
     *
     * Keyed `"zone<i>_<label>"` and NOT by label alone: the daemon keys `sSlots` by whatever string
     * it receives and `handleAttachSlot` does `remove(key) + release()` first, so two zones sharing
     * a label would silently destroy each other's overlay. Duplicate labels need no typing —
     * `LayoutPreset.nextSlotLabel()` is `"Zone " + (size + 1)`, so drawing 1/2/3, deleting Zone 2
     * and drawing again yields a second "Zone 3". The index restores the uniqueness the replaced
     * batch path got from its `layout_<label>_<i>` keys.
     *
     * Free zones hold no app, so releasing and re-creating them on each activation is invisible.
     */
    private fun attachFreeZones(preset: LayoutPreset) {
        val binder = mDaemonBinder ?: return
        for (i in preset.slots.indices) {
            val s = preset.slots[i]
            val name = s.packageName
            if (name != null && name.isNotEmpty()) continue
            // Clear first: on a retry a stale id from a previous activation would otherwise
            // survive a failed attach and publishDisplayIds would report the layout as fully
            // up while that zone has no overlay at all.
            s.displayId = -1
            val key = "zone" + i + "_" + s.label
            try {
                val id = FissionClient.attachSlot(binder, key, s.x, s.y, s.w, s.h)
                if (id > 0) {
                    s.displayId = id
                    mFreeZoneKeys.add(key)
                    AppLogger.i(TAG, "FISSION FREE_ZONE slot=$key displayId=$id")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "free zone attach failed for $key: " + e.message)
            }
        }
    }

    /** Releases the free-zone slots of the previous activation (no app to move or kill). */
    private fun releaseFreeZones() {
        if (mFreeZoneKeys.isEmpty()) return
        for (key in ArrayList(mFreeZoneKeys)) {
            val binder = mDaemonBinder
            if (binder != null) {
                try {
                    FissionClient.releaseSlot(binder, key)
                    FissionReleaseDebt.settled(key)
                    mFreeZoneKeys.remove(key)
                } catch (error: Exception) {
                    FissionReleaseDebt.record(key)
                    AppLogger.e(TAG, "free-zone release failed for $key: " + error.message)
                }
            } else {
                FissionReleaseDebt.record(key)
            }
        }
    }

    /**
     * Copies the live display ids back into the preset (the Layout Manager renders them) and
     * reports whether every zone came up.
     */
    private fun publishDisplayIds(preset: LayoutPreset): Boolean {
        var allOk = preset.slots.isNotEmpty()
        for (s in preset.slots) {
            val name = s.packageName
            if (name != null && name.isNotEmpty()) {
                val state = mSlots[name]
                s.displayId = state?.displayId ?: -1
            }
            if (s.displayId <= 0) allOk = false
        }
        return allOk
    }

    /** Delivers an activation outcome on the main thread (never suppressed — the UI waits). */
    private fun deliver(callback: ActivationCallback?, ok: Boolean, error: String?) {
        if (callback == null) return
        mMainHandler.post { callback.onActivationResult(ok, error) }
    }

    private fun markAutoStartFailed(reason: String) {
        if (!mAutoStartAttempt) return
        val ownedCompletion = sActivationGate.release(mActivationGuardToken)
        if (!ownedCompletion) {
            AppLogger.w(TAG, "stale auto-start failure ignored: $reason")
            return
        }
        synchronized(FissionOrchestrator::class.java) {
            if (sAutoStartOrchestrator === this) sAutoStartOrchestrator = null
            sAutoStartFired = false
            // The failure funnel of the auto-start path. Release the activation guard here too:
            // this method is reached from onDisplayTimeout, i.e. BEFORE activateFavoriteLayout
            // ever runs, so its finally would never fire and the guard would sit held until the
            // 60 s expiry stole it — leaving Activate answering "busy" on a car that just failed
            // to project, which is exactly when a user tries it by hand.
        }
        AppLogger.w(TAG, "auto-start re-armed after failure: $reason")
        // mFreeZoneKeys counts too. Free zones are deliberately kept OUT of mSlots and never set
        // mProjecting, so a layout made only of unbound zones satisfies neither condition below
        // — and this method then drops the orchestrator and shuts its executor down, orphaning
        // every free-zone overlay on the cluster with nothing left that could release it.
        if (mSlots.isNotEmpty() || mProjecting || mFreeZoneKeys.isNotEmpty()) stopAll()
        shutdown()
        notifyLayoutChanged()
    }

    private fun precreateSlots(layout: LayoutPreset) {
        post { mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_precreate)) }
        if (!ensureDaemon()) { post { mCallbacks.onStatusMessage(null) }; return }
        for (s in layout.slots) {
            val name = s.packageName
            val key = if (name != null && name.isNotEmpty()) name else s.label
            try {
                val id = FissionClient.attachSlot(mDaemonBinder!!, key, s.x, s.y, s.w, s.h)
                if (id > 0) AppLogger.i(TAG, "FISSION PRECREATE slot=$key displayId=$id")
            } catch (e: Exception) {
                AppLogger.w(TAG, "precreateSlots failed for $key: " + e.message)
            }
        }
        post { mCallbacks.onStatusMessage(null) }
    }

    @Suppress("DEPRECATION")
    private fun getAppLabel(pkg: String): String {
        return try {
            val pm = mAppCtx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }

    private fun resolveLayerStack(displayId: Int): Int {
        try {
            val displayManager = mAppCtx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager?
            val display = displayManager?.getDisplay(displayId) ?: return displayId
            val method = Display::class.java.getDeclaredMethod("getLayerStack")
            method.isAccessible = true
            val value = method.invoke(display)
            if (value is Int && value >= 0) return value
        } catch (error: Throwable) {
            AppLogger.w(TAG, "slot layerStack lookup failed for display $displayId: " +
                    error.message)
        }
        return displayId
    }

    private fun post(r: Runnable) {
        if (mDestroyed) return
        mMainHandler.post { if (!mDestroyed) r.run() }
    }

    companion object {

        private const val TAG = "FissionOrchestrator"
        private const val CLUSTER_W = 1920
        private const val CLUSTER_H = 720

        // ── App-launch auto-start ─────────────────────────────────────────────

        /** One-shot per process: avoids re-activating the layout on every MainActivity return. */
        @Volatile private var sAutoStartFired = false

        /** Keeps the headless orchestrator reachable while its executor works.
         *
         *  The Context it holds is `context.applicationContext` (see mAppCtx), i.e. the
         *  process singleton — never an Activity — so this reference cannot outlive
         *  anything it should not. The field IS the headless layout owner and has to be
         *  process-scoped; that is the whole design. */
        @SuppressLint("StaticFieldLeak")
        @Volatile private var sAutoStartOrchestrator: FissionOrchestrator? = null

        @Volatile private var sLayoutChangeListener: LayoutChangeListener? = null

        /** Registers a UI listener for headless layout slot changes (MainActivity onStart). */
        @JvmStatic
        fun setLayoutChangeListener(l: LayoutChangeListener?) { sLayoutChangeListener = l }

        /** Fires the registered layout-change listener on the main thread (if any). */
        private fun notifyLayoutChanged() {
            val l = sLayoutChangeListener
            if (l != null) Handler(Looper.getMainLooper()).post { l.onLayoutPackagesChanged() }
        }

        /**
         * Snapshot of the package names currently projected by the headless auto-start
         * orchestrator (the layout-launch path). Empty when no layout is active.
         */
        @JvmStatic
        fun getActiveLayoutPackages(): Set<String> {
            val o = sAutoStartOrchestrator ?: return emptySet()
            val pkgs = HashSet<String>()
            for (s in o.getSlots()) {
                if (s.pkg.isNotEmpty()) pkgs.add(s.pkg)
            }
            return pkgs
        }

        /** True when `pkg` is currently projected by the headless layout orchestrator. */
        @JvmStatic
        fun isLayoutPackage(pkg: String?): Boolean =
                pkg != null && getActiveLayoutPackages().contains(pkg)

        /** Returns the slot currently selected for Main's tactile mirror. */
        @JvmStatic
        fun getSelectedLayoutMirrorTarget(): LayoutMirrorTarget? =
                sAutoStartOrchestrator?.selectedMirrorTarget()

        /** Selects a running Layout app as the tactile mirror target. */
        @JvmStatic
        fun selectLayoutMirrorPackage(pkg: String?): LayoutMirrorTarget? {
            val o = sAutoStartOrchestrator
            if (o == null || pkg == null || !o.mSlots.containsKey(pkg)) return null
            o.mSelectedMirrorPackage = pkg
            notifyLayoutChanged()
            return o.selectedMirrorTarget()
        }

        /** Selects the previous/next running slot in the saved Layout's zone order. */
        @JvmStatic
        fun stepLayoutMirrorSelection(delta: Int): LayoutMirrorTarget? {
            val o = sAutoStartOrchestrator ?: return null
            val ordered = o.orderedSlotPackages()
            o.mSelectedMirrorPackage = LayoutSlotSelection.step(
                    o.mSelectedMirrorPackage, ordered, delta)
            notifyLayoutChanged()
            return o.selectedMirrorTarget()
        }

        /** Starts a mirror of the selected slot and routes daemon input to that slot display. */
        @JvmStatic
        fun startSelectedLayoutMirror(
                surface: Surface?, viewWidth: Int, viewHeight: Int): LayoutMirrorTarget? {
            val o = sAutoStartOrchestrator
            if (o == null || surface == null || !surface.isValid) {
                return null
            }
            val binder = o.surfaceBinderForTactile("LayoutMirrorStart") ?: return null
            val target = o.selectedMirrorTarget() ?: return null
            try {
                val focus = FissionClient.focusSlot(binder, target.pkg)
                if (focus == null || !focus.startsWith("OK ")) {
                    AppLogger.w(TAG, "Layout tactile focus best-effort for " + target.pkg +
                            ": " + focus)
                }
            } catch (dead: DeadObjectException) {
                o.recoverSurfaceBinderIfCurrent(binder, "LayoutFocus")
                AppLogger.w(TAG, "Layout tactile focus lost surface daemon for " + target.pkg)
                return null
            } catch (focusError: Exception) {
                AppLogger.w(TAG, "Layout tactile focus unavailable for " + target.pkg +
                        ": " + focusError.message)
            }
            try {
                val ok = FissionClient.startMirror(binder,
                        target.layerStack, target.width, target.height,
                        target.displayId, viewWidth, viewHeight, surface)
                if (!ok) return null
                o.mMirrorReady = true
                o.mFirstDisplayId = target.displayId
                AppLogger.i(TAG, "Layout tactile mirror selected pkg=" + target.pkg +
                        " displayId=" + target.displayId + " layerStack=" + target.layerStack)
                return target
            } catch (dead: DeadObjectException) {
                o.recoverSurfaceBinderIfCurrent(binder, "LayoutMirrorStart")
                AppLogger.w(TAG, "Layout tactile mirror lost surface daemon for " + target.pkg)
                return null
            } catch (error: Exception) {
                AppLogger.e(TAG, "startSelectedLayoutMirror failed for " + target.pkg, error)
                return null
            }
        }

        @JvmStatic
        fun stopSelectedLayoutMirror() {
            val o = sAutoStartOrchestrator ?: return
            val binder = o.mDaemonBinder
            val accepted = FissionClient.stopMirror(binder)
            val ownerGone = binder == null || !binder.isBinderAlive
            if (!accepted && binder != null && !binder.isBinderAlive) {
                // The old process already destroyed its mirror/input state. Reacquire for subsequent
                // tactile operations, but do not pretend the dead binder accepted this command.
                o.recoverSurfaceBinderIfCurrent(binder, "LayoutMirrorStop")
            }
            if (accepted || ownerGone) {
                o.mMirrorReady = false
                o.mFirstDisplayId = -1
            } else {
                AppLogger.e(TAG, "Layout tactile mirror STOP was not accepted; retaining local state")
            }
        }

        @JvmStatic
        fun injectSelectedLayoutMotion(event: MotionEvent?): Boolean {
            val o = sAutoStartOrchestrator
            if (o == null || event == null) return false
            val binder = o.surfaceBinderForTactile("LayoutMotion") ?: return false
            return try {
                FissionClient.injectMotion(binder, event)
                true
            } catch (dead: DeadObjectException) {
                o.recoverSurfaceBinderIfCurrent(binder, "LayoutMotion")
                AppLogger.w(TAG, "Layout tactile input lost surface daemon")
                false
            } catch (error: Exception) {
                AppLogger.e(TAG, "injectSelectedLayoutMotion failed", error)
                false
            }
        }

        /**
         * Kills a single layout slot: moves the app back to display 0, releases its VD and
         * force-stops it (via [releaseSlotAsync]). No-op when no layout is active.
         */
        @JvmStatic
        fun killLayoutSlot(pkg: String?) {
            val o = sAutoStartOrchestrator
            if (o != null && pkg != null) o.releaseSlotAsync(pkg)
        }

        /**
         * True only when ClusterService has an app actively projected (mProjectionActive == true).
         * Unlike sIsRunning, this returns false as soon as stopProjectionNoAdb() is called — even
         * if the service is still bound by MainActivity.
         */
        private fun isClassicProjectionActive(): Boolean {
            val cs = ClusterService.getInstance()
            return cs != null && cs.isProjectionActive()
        }

        /**
         * Launch-time entry point: when "auto favourite layout" is enabled, activates the cluster
         * projection and the favourite layout (which then launches the bound apps) as soon as
         * DashCast starts — without requiring the user to open the Fission screen.
         *
         * No-op when the option is off, Layouts mode is disabled, no favourite layout exists,
         * classic projection is already running, or it already fired this process.
         */
        @JvmStatic
        fun isAutoStartRequested(context: Context): Boolean {
            val appCtx = context.applicationContext
            return LayoutAutoStartPolicy.isRequested(
                    DaemonConfig.isFissionModeEnabled(appCtx),
                    ClusterPrefs.isFissionAutoLayout(appCtx))
        }

        @JvmStatic
        @Synchronized
        fun maybeAutoStartOnAppLaunch(context: Context): AutoStartResult {
            if (sAutoStartFired) return AutoStartResult.ALREADY_STARTED
            val appCtx = context.applicationContext
            if (!isAutoStartRequested(appCtx)) return AutoStartResult.DISABLED
            val fav = LayoutPrefs.getAutoStartLayout(appCtx)
            if (fav == null) {
                AppLogger.w(TAG, "auto-start requested but no unambiguous saved layout has bound apps")
                return AutoStartResult.MISSING_LAYOUT
            }
            if (isClassicProjectionActive()) {
                AppLogger.d(TAG, "auto-start skipped: classic projection already active")
                return AutoStartResult.PROJECTION_CONFLICT
            }
            // Take the SAME guard activateLayoutManually takes, for the same reason it exists: two
            // concurrent activations build two ClusterManagers, and the second one's cancel() cannot
            // unregister the first's DisplayListener — the listener leak + double-launch fixed in
            // 1.2.29. Only the manual path ever took it, so the two paths did not exclude each other:
            // auto-start runs for several seconds at launch (30 → 3s → 16 → 3s → 35), and a user who
            // opens Layout Manager and taps Activate inside that window walked straight past a guard
            // that was never armed.
            //
            // Released in activateFavoriteLayout and in markAutoStartFailed — the success and failure
            // funnels of this path — and, if both are somehow missed, stolen by the existing
            // ACTIVATION_GUARD_MAX_MS expiry. A lost guard here cannot disable anything permanently.
            val nowMs = SystemClock.elapsedRealtime()
            val activation = sActivationGate.tryAcquire(nowMs)
            if (activation == null) {
                AppLogger.w(TAG, "auto-start skipped: an activation is already in flight (held " +
                        sActivationGate.heldMs(nowMs) + "ms)")
                return AutoStartResult.PROJECTION_CONFLICT
            }
            if (activation.reclaimed) {
                AppLogger.w(TAG, "auto-start reclaimed an expired activation guard")
            }
            sAutoStartFired = true
            AppLogger.i(TAG, "auto-start on app launch: projection + layout « " + fav.name + " »")

            val orch = FissionOrchestrator(appCtx,
                    headlessProjectionState(), headlessCallbacks("auto-start"))
            orch.mAutoStartAttempt = true
            orch.mActivationGuardToken = activation.token
            replaceHeadlessAfterStop(orch, fav)
            return AutoStartResult.STARTED
        }

        /**
         * Projection-state provider shared by every headless (no-Activity) orchestrator.
         * Extracted so a new entry point cannot silently diverge from the auto-start path.
         */
        private fun headlessProjectionState(): ProjectionStateProvider {
            return object : ProjectionStateProvider {
                override fun isProjectionActive(): Boolean = isClassicProjectionActive()
                override fun stopProjectionIfActive(onStopped: Runnable?) {
                    val cs = ClusterService.getInstance()
                    cs?.stopProjectionNoAdb()
                    if (onStopped != null) Handler(Looper.getMainLooper()).post(onStopped)
                }
            }
        }

        /** Log-only callbacks for a headless orchestrator; `logPrefix` names the entry point. */
        private fun headlessCallbacks(logPrefix: String): Callbacks {
            return object : Callbacks {
                override fun onSlotsChanged(slots: Collection<SlotState>) { notifyLayoutChanged() }
                override fun onDaemonBinderAcquired(binder: IBinder?) {}
                override fun onStatusMessage(message: String?) {
                    if (message != null) AppLogger.d(TAG, "$logPrefix: $message")
                }
                override fun onSlotError(pkg: String?, message: String?) {
                    AppLogger.w(TAG, "$logPrefix slot error $pkg: $message")
                }
                override fun onProjectionConflict(proceedCallback: Runnable?) {
                    AppLogger.w(TAG, "$logPrefix: projection conflict — aborting (no UI to ask)")
                }
            }
        }

        // ── Manual (user-triggered) layout activation ─────────────────────────

        /**
         * Stable, **English** failure codes handed to [ActivationCallback].
         *
         * Deliberately not user text: this value is what `AppLogger.e` writes into the journal that
         * ships inside every bug report, and triage greps the whole corpus for it. A localised
         * message here would make a Turkish or Russian capture unsearchable. The UI maps these to
         * an `R.string` for display — see `LayoutManagerActivity.activateLayout`.
         */
        const val ERR_CLUSTER_TIMEOUT = "cluster activation timeout"
        /** @see ERR_CLUSTER_TIMEOUT */
        const val ERR_PROJECTION_CONFLICT = "classic projection already active"
        /** @see ERR_CLUSTER_TIMEOUT */
        const val ERR_NO_DAEMON = "surface daemon unavailable"
        /** @see ERR_CLUSTER_TIMEOUT */
        const val ERR_BUSY = "activation already in flight"
        /** @see ERR_CLUSTER_TIMEOUT */
        const val ERR_ABANDONED = "layout stopped during cluster activation"

        /**
         * The generation-bound gate records when its current owner was acquired.
         *
         * The guard is cleared from the activation callback, and that callback is only reached
         * through [deliver]. `ClusterManager` posts no deadline of its own on the warm and DiLink 5
         * paths: if the underlying `sendInfo` callback never arrives — a half-open ADB-TCP socket is
         * the documented D50F_LC condition — NEITHER `onDisplayReady` nor `onDisplayTimeout` fires,
         * nothing calls back, and the guard would stay taken for the life of the process, silently
         * killing the Activate button. So the guard also expires: an attempt older than this bound
         * is assumed lost and stolen. Generous on purpose — a real sequence is ~6.5 s
         * (30 → 3 s → 16 → 3 s → 35) and the DiLink 4 daemon display probe is bounded at 13 s.
         */
        private const val ACTIVATION_GUARD_MAX_MS = 60_000L

        private val sActivationGate = ActivationAttemptGate(ACTIVATION_GUARD_MAX_MS)

        /**
         * Set once [ensureDaemon] has let `AdbLocalClient.startMirrorDaemon` compare the running
         * daemon's build marker against this APK's. Process-scoped on purpose: a daemon outlives an
         * app update, so the check is needed once per app process, not once per layout.
         */
        @Volatile private var sDaemonFreshnessChecked = false

        /**
         * Activates an explicit layout on user request (Layout Manager "Activate").
         *
         * Runs the **same** sequence as the auto-start path, which the manual button never did:
         * cluster projection first via [ensureClusterProjectionThen] (without it the slots are
         * created while Qt still scans out the OEM's native view and nothing is visible), then
         * [ensureDaemon] — which *starts* the SurfaceDaemon and polls for it instead of merely
         * testing the binder and giving up — then one `ATTACH_SLOT` per zone, so every slot stays
         * addressable by QUERY / RESIZE / RELEASE.
         *
         * Reuses the process-wide headless orchestrator when one exists, so an already-running
         * layout is *switched* (zones absent from the new preset released, shared ones kept alive)
         * instead of being torn down and rebuilt.
         *
         * Re-entrant taps are rejected with [ERR_BUSY]: the cluster sequence takes seconds
         * (30 → 3 s → 16 → 3 s → 35) with only a short toast for feedback, so a second tap is
         * likely, and it would build a second `ClusterManager` whose `cancel()` cannot unregister
         * the first instance's DisplayListener — the exact listener leak + double-launch fixed in
         * 1.2.29.
         */
        @JvmStatic
        @Synchronized
        fun activateLayoutManually(context: Context, preset: LayoutPreset,
                                   callback: ActivationCallback?) {
            val appCtx = context.applicationContext
            val nowMs = SystemClock.elapsedRealtime()
            val activation = sActivationGate.tryAcquire(nowMs)
            if (activation == null) {
                AppLogger.w(TAG, "activateLayoutManually: " + ERR_BUSY + " (held " +
                        sActivationGate.heldMs(nowMs) + "ms) — ignoring tap")
                if (callback != null) {
                    Handler(Looper.getMainLooper())
                            .post { callback.onActivationResult(false, ERR_BUSY) }
                }
                return
            }
            if (activation.reclaimed) {
                AppLogger.w(TAG, "activateLayoutManually: reclaimed expired activation guard")
            }
            val activationToken = activation.token
            var orch = sAutoStartOrchestrator
            // A shut-down executor would make activatePresetAsync throw RejectedExecutionException
            // on the UI thread; replace such an orchestrator instead of reusing it.
            val fresh = (orch == null || !orch.isUsable())
            if (fresh) {
                orch = FissionOrchestrator(appCtx,
                        headlessProjectionState(), headlessCallbacks("activate-layout"))
                sAutoStartOrchestrator = orch
            }
            // This IS the layout start, so the launch-time auto-start has nothing left to do:
            // without the latch, returning to MainActivity would fire maybeAutoStartOnAppLaunch,
            // orphan this orchestrator and rebuild every slot. Restored on failure so a manual
            // attempt that fails does not disable the automatic one.
            val previouslyFired = sAutoStartFired
            sAutoStartFired = true
            orch!!.activatePresetAsync(preset, fresh) { ok, error ->
                val ownedCompletion = sActivationGate.release(activationToken)
                // Under the SAME monitor markAutoStartFailed() uses. This is a read-modify-write on
                // a static that the fission-exec thread also writes, and this lambda runs on the
                // main thread: unsynchronised, a markAutoStartFailed() landing between the read and
                // the write is clobbered, which pins sAutoStartFired true and permanently disables
                // the auto-start this line exists to preserve.
                //
                // Only a hard failure re-arms, and only if nothing else already re-armed it. A
                // PARTIAL activation must NOT re-arm: it still owns the cluster, and the next
                // onResume would tear it down and rebuild it.
                synchronized(FissionOrchestrator::class.java) {
                    if (ownedCompletion && error != null && sAutoStartFired) {
                        sAutoStartFired = previouslyFired
                    }
                }
                if (callback != null) callback.onActivationResult(ok, error)
            }
        }

        /**
         * Manually launches the apps configured in the favourite layout. Same flow as
         * [maybeAutoStartOnAppLaunch] but user-triggered — skips the `isFissionAutoLayout` guard so
         * it works when the auto-launch option is OFF.
         */
        @JvmStatic
        fun launchFavoriteLayoutApps(context: Context) {
            val appCtx = context.applicationContext
            if (!DaemonConfig.isFissionModeEnabled(appCtx)) return
            val fav = LayoutPrefs.getAutoStartLayout(appCtx) ?: return
            if (isClassicProjectionActive()) {
                AppLogger.d(TAG, "launchFavoriteLayoutApps skipped: classic projection active")
                return
            }
            val activation = sActivationGate.tryAcquire(SystemClock.elapsedRealtime())
            if (activation == null) {
                AppLogger.w(TAG, "launchFavoriteLayoutApps skipped: activation already in flight")
                return
            }
            AppLogger.i(TAG, "manual launch layout apps: « " + fav.name + " »")

            val orch = FissionOrchestrator(appCtx,
                    headlessProjectionState(), headlessCallbacks("launch-layout"))
            orch.mAutoStartAttempt = true
            orch.mActivationGuardToken = activation.token
            replaceHeadlessAfterStop(orch, fav)
        }

        /** Publishes and starts `next` only after the current slot owner has fully stopped. */
        private fun replaceHeadlessAfterStop(next: FissionOrchestrator, layout: LayoutPreset) {
            val previous = sAutoStartOrchestrator
            if (previous == null) {
                sAutoStartOrchestrator = next
                next.initAsync(layout, true, false)
                return
            }
            previous.stopAll {
                previous.shutdown()
                if (sAutoStartOrchestrator !== previous) {
                    next.shutdown()
                    sActivationGate.release(next.mActivationGuardToken)
                    AppLogger.i(TAG, "headless replacement cancelled while prior teardown completed")
                    return@stopAll
                }
                sAutoStartOrchestrator = next
                next.initAsync(layout, true, false)
            }
        }

        /**
         * Stops the headless orchestrator and invokes `onComplete` on the main thread only after
         * every Layout package has completed move → verified force-stop → optional slot release.
         */
        @JvmStatic
        fun stopAutoOrchestrator(onComplete: Runnable?) {
            stopAutoOrchestrator(false, null, onComplete)
        }

        /** Stops all tracked slots, then globally purges daemon slots before activation can resume. */
        @JvmStatic
        fun stopAutoOrchestratorAndPurge(context: Context, onComplete: Runnable?) {
            stopAutoOrchestrator(true, context.applicationContext, onComplete)
        }

        private fun stopAutoOrchestrator(purgeDaemonSlots: Boolean, context: Context?,
                                         onComplete: Runnable?) {
            val o = sAutoStartOrchestrator
            sAutoStartOrchestrator = null
            val teardownToken = sActivationGate.forceAcquire(SystemClock.elapsedRealtime()).token
            val complete = Runnable {
                sActivationGate.release(teardownToken)
                notifyLayoutChanged()
                if (onComplete != null) onComplete.run()
            }
            if (o != null) {
                AppLogger.i(TAG, "stopping headless auto-start orchestrator")
                if (purgeDaemonSlots) o.stopAllAndPurge(complete)
                else o.stopAll(complete)
                // stopAll() submitted its teardown to mExec but never shut it down; this
                // throwaway orchestrator is dropped here (never destroy()'d), so shut the
                // executor down gracefully or its worker thread leaks per headless stop.
                o.shutdown()
            } else if (purgeDaemonSlots) {
                val purge = Thread({
                    val binder = FissionClient.getBinderFromServiceManager()
                    if (binder != null) {
                        try {
                            FissionClient.deactivateLayout(binder)
                            FissionReleaseDebt.clearAll()
                        } catch (error: Exception) {
                            AppLogger.e(TAG, "global slot purge failed: " + error.message)
                        }
                    }
                    Handler(Looper.getMainLooper()).post(complete)
                }, "fission-global-purge")
                purge.isDaemon = true
                purge.start()
            } else {
                Handler(Looper.getMainLooper()).post(complete)
            }
        }

        @JvmStatic
        internal fun applyAcceptedResize(slot: SlotState?, requestedRect: Rect?,
                                         accepted: Boolean): Boolean {
            if (!accepted || slot == null || requestedRect == null) return false
            slot.rect = Rect(requestedRect)
            return true
        }
    }
}
