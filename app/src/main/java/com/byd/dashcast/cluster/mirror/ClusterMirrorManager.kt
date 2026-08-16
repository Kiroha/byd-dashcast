package com.byd.dashcast.cluster.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.IBinder
import android.os.Parcel
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import com.byd.dashcast.cluster.display.ClusterDisplayInfo
import com.byd.dashcast.cluster.display.ClusterDisplayRegistry
import com.byd.dashcast.cluster.display.ClusterLayerStackPolicy
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.DaemonBinderResolver
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import com.byd.dashcast.util.AppLogger
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cluster mirror — SurfaceControl.createDisplay + setDisplayLayerStack.
 *
 * Mechanism (identical to WindowManagement v1.2):
 *   - SurfaceControl.createDisplay("mybyd_preview_mirror", false)
 *   - Transaction.setDisplayLayerStack(token, clusterLayerStack) → mirrors cluster content
 *   - Transaction.setDisplaySurface(token, ourSurface) → to our TextureView
 *   - Transaction.setDisplayProjection(token, 0, srcRect, destRect)
 *   → SurfaceFlinger composites the cluster into our surface. No VirtualDisplay needed.
 *
 * Requires: ACCESS_SURFACE_FLINGER (signature permission, granted with platform.keystore)
 *
 * ## Which daemon does this class talk to?
 * [SurfaceDaemon] — the uid-2000 daemon that **HOLDS** graphical state (surfaces, cluster slot
 * overlay windows, trusted VirtualDisplays). Its binder comes from
 * [com.byd.dashcast.proxy.DaemonBinderResolver.surfaceDaemonBinder] and **never** from
 * `ProxyClient.getProxyDaemonBinder()`, which is the *other* daemon — `ProxyDaemonMain`, the
 * stateless command executor that **DOES** things (shell + one-shot verbs). Pairing
 * [SurfaceDaemon.DESCRIPTOR] with the ProxyDaemon's binder makes the receiving
 * `enforceInterface` throw a SecurityException
 * that the code below used to swallow, so the transaction silently did nothing — that is exactly
 * how the residual-compositing teardown bug happened. [writeSurfaceDaemonToken] now refuses that
 * pairing. Triage rule: a failed command → ProxyDaemon; a black or frozen surface → SurfaceDaemon.
 */
@Suppress("DEPRECATION")
class ClusterMirrorManager {

    // ── SurfaceControl mirror token ───────────────────────────────────────────────
    private var mMirrorDisplayToken: IBinder? = null
    // Audit batch 2 — removed dead field mMirrorSurface (was assigned in
    // startMirror/startMirrorViaDaemon and nulled in stopPreview/destroyMirrorToken
    // but never read anywhere). The Surface lifecycle is owned by MainActivity
    // (TextureView's SurfaceTexture), we never needed our own reference.

    private var mMirrorActive = false
    // v1.2.55-beta — tracks which path established the active mirror. When the
    // daemon Binder becomes available AFTER a direct-path mirror was put in
    // place, MainActivity uses this flag to detect the stale state and
    // restart via the daemon (which is the only path that actually streams
    // frames on DL3/DL5 because the app process lacks ACCESS_SURFACE_FLINGER).
    private var mMirrorViaDaemon = false
    private var mClusterW = 1920
    private var mClusterH = 720   // Confirmed: fission_bg_xdjaVirtualSurface 1920×720 (dumpsys window 03/05/2026)

    // ── Projection parameters (set when setDisplayProjection is called) ───────
    // Stored with integer arithmetic to match the daemon's computation exactly.
    // Used by touch mapping so the offset/scale are always consistent with the
    // actual rendered projection, regardless of current view dimensions.
    private var mProjOffsetX = 0
    private var mProjOffsetY = 0
    private var mProjScale = 0f  // 0 means "not yet set"

    fun getClusterWidth(): Int = mClusterW
    fun getClusterHeight(): Int = mClusterH
    fun isMirrorActive(): Boolean = mMirrorActive

    /** True iff the active mirror was established through the system-uid daemon path. */
    fun isMirrorViaDaemon(): Boolean = mMirrorActive && mMirrorViaDaemon

    /** Returns the horizontal letterbox offset (pixels) used in the last setDisplayProjection call. */
    fun getProjOffsetX(): Int = mProjOffsetX

    /** Returns the vertical letterbox offset (pixels) used in the last setDisplayProjection call. */
    fun getProjOffsetY(): Int = mProjOffsetY

    /** Returns the scale factor used in the last setDisplayProjection call. 0 if not yet set. */
    fun getProjScale(): Float = mProjScale

    // ── SURFACECONTROL MIRROR ─────────────────────────────────────────────────

    /**
     * Mirrors the cluster content into the provided Surface via SurfaceControl.
     *
     * Equivalent to what WindowManagement does via its daemon (uid=2000):
     *   SurfaceControl.createDisplay + setDisplayLayerStack(clusterLayerStack) + setDisplaySurface
     *
     * Requires ACCESS_SURFACE_FLINGER (signature permission).
     * Returns false on failure → caller falls back to screencap.
     *
     * Attempted at most once per process when it fails for a permanent reason — see
     * [sInAppMirrorDenied]. A unit that genuinely holds the permission keeps using this path
     * exactly as before.
     *
     * @param targetSurface  Surface of our local TextureView (in-app)
     * @param viewW / viewH  View dimensions (for projection mapping)
     */
    @SuppressLint("NewApi")
    fun startMirror(ctx: Context?, clusterDisplay: Display?, targetSurface: Surface?,
                    viewW: Int, viewH: Int): Boolean {
        if (mMirrorActive) {
            AppLogger.d(TAG, "Mirror already active")
            return true
        }
        stopPreview()

        // Placed AFTER stopPreview() so the teardown side effect callers rely on is unchanged.
        if (sInAppMirrorDenied) return false

        if (targetSurface == null || !targetSurface.isValid) {
            AppLogger.e(TAG, "startMirror: targetSurface is invalid")
            return false
        }

        // Cluster dimensions — only accept a strictly-positive size. A transient
        // 0×0 from getRealSize() during display teardown would otherwise make the
        // scale computation below divide by zero (viewW/0f → +Infinity), yielding
        // an empty destRect and a non-finite mProjScale that corrupts touch mapping.
        if (clusterDisplay != null) {
            val sz = Point(1920, 720)
            clusterDisplay.getRealSize(sz)
            if (sz.x > 0 && sz.y > 0) {
                mClusterW = sz.x; mClusterH = sz.y
            } else {
                AppLogger.w(TAG, "startMirror: getRealSize returned ${sz.x}×${sz.y} — keeping ${mClusterW}×${mClusterH}")
            }
        }

        // ── SurfaceControl mirror attempt ────────────────────────────────────
        var transaction: SurfaceControl.Transaction? = null
        return try {
            ensureMirrorMethodsCached()

            // 1. Cluster layer stack (@hide API)
            var layerStack = 0
            try {
                layerStack = sCachedGetLayerStack!!.invoke(clusterDisplay) as Int
                AppLogger.d(TAG, "Cluster layerStack=$layerStack")
            } catch (e: Exception) {
                // On some ROMs layerStack == displayId
                layerStack = clusterDisplay?.displayId ?: 2
                AppLogger.w(TAG, "getLayerStack failed → fallback layerStack=$layerStack")
            }
            layerStack = applyDl5LayerStackOverride(ctx, layerStack)

            // 2. Create a display token for our mirror
            mMirrorDisplayToken = sCachedCreateDisplay!!.invoke(null,
                    "mybyd_preview_mirror", false) as IBinder?
            if (mMirrorDisplayToken == null) {
                // SurfaceFlinger returns a null token (no exception) when the caller lacks
                // ACCESS_SURFACE_FLINGER — this is THE observed signature of the denial.
                throw InAppMirrorUnavailable("SurfaceControl.createDisplay → null")
            }

            // 3. Projection: preserve aspect ratio (letterbox)
            // Use integer arithmetic to match SurfaceDaemon.setupMirror() exactly.
            val scale = Math.min(viewW.toFloat() / mClusterW, viewH.toFloat() / mClusterH)
            val drawW = (mClusterW * scale).toInt()
            val drawH = (mClusterH * scale).toInt()
            val offsetX = (viewW - drawW) / 2
            val offsetY = (viewH - drawH) / 2
            val srcRect = Rect(0, 0, mClusterW, mClusterH)
            val destRect = Rect(offsetX, offsetY, offsetX + drawW, offsetY + drawH)

            // Store projection params for touch coordinate mapping
            mProjOffsetX = offsetX
            mProjOffsetY = offsetY
            mProjScale = scale

            // 4. SurfaceControl Transaction (@hide methods — cached)
            val tx = SurfaceControl.Transaction()
            transaction = tx

            sCachedSetDisplayLayerStack!!.invoke(tx, mMirrorDisplayToken, layerStack)
            sCachedSetDisplaySurface!!.invoke(tx, mMirrorDisplayToken, targetSurface)
            sCachedSetDisplayProjection!!.invoke(tx, mMirrorDisplayToken, 0, srcRect, destRect)
            tx.apply()

            mMirrorActive = true
            mMirrorViaDaemon = false
            AppLogger.i(TAG, "SurfaceControl mirror ✓ layerStack=$layerStack"
                    + " src=$mClusterW×$mClusterH"
                    + " dest=$drawW×$drawH offset=($offsetX,$offsetY)")
            true
        } catch (e: Exception) {
            if (isPermanentInAppMirrorDenial(e)) {
                // Expected on every unit seen so far (see sInAppMirrorDenied): WARN once, then
                // never attempt again for the life of this process.
                sInAppMirrorDenied = true
                AppLogger.w(TAG, "in-app SurfaceControl mirror unavailable to this process"
                        + " (ACCESS_SURFACE_FLINGER is a signature permission an unprivileged app"
                        + " cannot hold) — expected; the uid-2000 surface daemon does the"
                        + " mirroring. Not retried again in this process. Cause: ${e.message}")
            } else {
                AppLogger.e(TAG, "SurfaceControl mirror FAILED — use startMirrorViaDaemon()", e)
            }
            destroyMirrorToken()
            false
        } finally {
            // close() releases only the local native transaction builder, not the applied state.
            try { transaction?.close() } catch (ignored: Throwable) { }
        }
    }

    /**
     * Mirror via the SurfaceDaemon (uid=2000) which holds ACCESS_SURFACE_FLINGER.
     * The daemon receives the Surface via Binder and configures SurfaceControl (static methods).
     * SYNCHRONOUS call: the daemon replies 1 (success) or 0 (failure) → mMirrorActive reflects
     * reality, which allows the screencap fallback if the daemon fails.
     */
    fun startMirrorViaDaemon(ctx: Context?, daemonBinder: IBinder?, clusterDisplay: Display?,
                             requestedDisplayId: Int, targetSurface: Surface?,
                             viewW: Int, viewH: Int): Boolean {
        if (mMirrorActive) return true
        if (daemonBinder == null || targetSurface == null || !targetSurface.isValid) return false

        // DiLink 4.0: the cluster display exists (1920x720, layerStack 1, state ON in every
        // uid-2000 dump) but our uid can never obtain a Display object for it — the OEM
        // DisplayManagerService whitelist withholds the id list, so dm.getDisplay(1) is null.
        // The DAEMON does not need the object: TRANSACT_MIRROR_START takes plain ints
        // (layerStack, w, h, displayId), which ClusterManager already resolved for us. On
        // DL3/DL5 nothing ever populates ClusterDisplayRegistry, so this stays null and the
        // original "defer until display is ready" behaviour is preserved bit-for-bit.
        //
        // forDisplayId, NOT get(): the registry is a process-wide singleton, so a bare get()
        // hands back the geometry of a projection that may already have been stopped (the caller
        // then passes displayId=-1, meaning "no cluster"). That started a daemon mirror of a dead
        // layerStack with touch injection armed on it, and the UI showed an "active" mirror of a
        // stopped projection. Every other consumer of the registry already cross-checks the id —
        // this was the only one that did not.
        val injected: ClusterDisplayInfo? = if (clusterDisplay == null && requestedDisplayId > 0)
                ClusterDisplayRegistry.forDisplayId(requestedDisplayId) else null
        if (clusterDisplay == null && injected == null) {
            AppLogger.w(TAG, "startMirrorViaDaemon: clusterDisplay null — mirror deferred until display is ready")
            return false
        }

        // Cluster dimensions — only accept a strictly-positive size (see startMirror).
        if (clusterDisplay != null) {
            val sz = Point(1920, 720)
            clusterDisplay.getRealSize(sz)
            if (sz.x > 0 && sz.y > 0) {
                mClusterW = sz.x
                mClusterH = sz.y
            } else {
                AppLogger.w(TAG, "startMirrorViaDaemon: getRealSize returned ${sz.x}×${sz.y} — keeping ${mClusterW}×${mClusterH}")
            }
        } else if (injected != null && injected.width > 0 && injected.height > 0) {
            mClusterW = injected.width
            mClusterH = injected.height
        }

        // Pre-compute projection params (identical formula to SurfaceDaemon.setupMirror).
        // Stored here so touch mapping uses exact same offsets as the daemon's projection.
        run {
            val scale = Math.min(viewW.toFloat() / mClusterW, viewH.toFloat() / mClusterH)
            val drawW = (mClusterW * scale).toInt()
            val drawH = (mClusterH * scale).toInt()
            mProjOffsetX = (viewW - drawW) / 2
            mProjOffsetY = (viewH - drawH) / 2
            mProjScale = scale
        }

        var clusterDisplayId = clusterDisplay?.displayId ?: injected!!.id
        var layerStack: Int
        if (clusterDisplay == null) {
            // Daemon-resolved: the layerStack comes straight from `dumpsys display`
            // ("layerStack 1" for fission_bg_xdjaVirtualSurface on DL4), so no reflection and
            // no invented value. injected is non-null here — the guard above returned otherwise.
            layerStack = injected!!.layerStack
        } else {
            try {
                ensureMirrorMethodsCached()
                layerStack = sCachedGetLayerStack!!.invoke(clusterDisplay) as Int
            } catch (e: Exception) {
                layerStack = clusterDisplayId
                AppLogger.w(TAG, "getLayerStack failed → fallback layerStack=$layerStack")
            }
        }
        layerStack = applyDl5LayerStackOverride(ctx, layerStack)
        // v1.2.7 — On DL5 the daemon must inject touch on displayId=2 (composed fission output),
        // not on the shadow render displayId=3 whose framebufferSpace is 1×1 — see field test
        // 22/05/2026 (preview OK after layerStack 3→2 override but tactile not working until
        // we mirror that override on the daemon's setDisplayId target).
        clusterDisplayId = applyDl5DisplayIdOverride(ctx, clusterDisplayId)

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            if (!writeSurfaceDaemonToken(daemonBinder, data)) return false
            data.writeInt(layerStack)
            data.writeInt(mClusterW)
            data.writeInt(mClusterH)
            data.writeInt(clusterDisplayId)
            data.writeInt(viewW)
            data.writeInt(viewH)
            data.writeParcelable(targetSurface, 0)
            data.writeStrongBinder(com.byd.dashcast.proxy.MirrorResourceOwner.token())
            // Synchronous call (not FLAG_ONEWAY) → daemon reply in 'reply' parcel
            daemonBinder.transact(SurfaceDaemon.TRANSACT_MIRROR_START, data, reply, 0)
            reply.readException()
            val daemonOk = reply.readInt() == 1
            if (daemonOk) {
                mMirrorActive = true
                mMirrorViaDaemon = true
                AppLogger.i(TAG, "startMirrorViaDaemon ✓ layerStack=$layerStack"
                        + " $mClusterW×$mClusterH displayId=$clusterDisplayId")
            } else {
                AppLogger.e(TAG, "startMirrorViaDaemon: daemon reported failure"
                        + " (check logcat for MirrorDaemon details)")
            }
            return daemonOk
        } catch (doe: android.os.DeadObjectException) {
            // v1.3.3 — silent binder death: kernel did not notify sDeath, so
            // the cached binder is stuck "alive". Eagerly invalidate so the
            // keeper re-bootstraps within HEARTBEAT_MS instead of leaving every
            // call broken until the user quits the app.
            // AUD-009 — the transaction above is the SURFACE daemon's (TRANSACT_MIRROR_START,
            // SurfaceDaemon.DESCRIPTOR), so invalidating the PROXY daemon repaired a process that
            // was not down and did nothing for the one that was.
            //
            // This class holds no cache of its own — the binder arrives as a parameter — so it
            // cannot forget the dead reference on the caller's behalf. What it can do is warm a
            // fresh ServiceManager lookup for whoever asks next, and stop disturbing the other
            // daemon. The caller's stale copy is dealt with where it lives.
            com.byd.dashcast.proxy.DaemonBinderResolver.reacquireSurfaceBinder("MirrorStart")
            AppLogger.e(TAG, "startMirrorViaDaemon: surface binder dead", doe)
            return false
        } catch (e: Exception) {
            AppLogger.e(TAG, "startMirrorViaDaemon failed", e)
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Requests the surface daemon to stop the SurfaceControl mirror.
     *
     * [daemonBinder] MUST be the surface daemon's binder — see [writeSurfaceDaemonToken].
     */
    fun stopMirrorViaDaemon(daemonBinder: IBinder?) {
        if (daemonBinder == null) return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            // Skips the transact (not the state reset below) when the binder is provably the
            // OTHER daemon's — exactly the outcome the transact itself used to produce, minus the
            // pointless round-trip and the swallowed SecurityException.
            if (writeSurfaceDaemonToken(daemonBinder, data)) {
                // v0.9.77: SYNCHRONOUS stop (no FLAG_ONEWAY) so callers can immediately
                // restart the mirror at a different size (e.g. fullscreen toggle) without
                // a race where the daemon's stop is still queued behind the new start.
                daemonBinder.transact(SurfaceDaemon.TRANSACT_MIRROR_STOP, data, reply, 0)
                try { reply.readException() } catch (ignored: Throwable) { /* daemon may not write reply */ }
            }
        } catch (doe: android.os.DeadObjectException) {
            // v1.3.3 — see comment in startMirrorViaDaemon.
            // AUD-009 — same as the start path. Harmless here in one sense, since a stop whose
            // daemon is already dead has nothing left to stop, but it still reconnected the wrong
            // daemon on every teardown after a surface death.
            com.byd.dashcast.proxy.DaemonBinderResolver.reacquireSurfaceBinder("MirrorStop")
            AppLogger.w(TAG, "stopMirrorViaDaemon: surface binder dead — nothing left to stop")
        } catch (e: Exception) {
            AppLogger.w(TAG, "stopMirrorViaDaemon transact failed: ${e.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
        mMirrorActive = false
        mMirrorViaDaemon = false
        AppLogger.i(TAG, "stopMirrorViaDaemon done (sync)")
    }

    // ──────────────────────────────────────────────────────────────────────────

    private fun destroyMirrorToken() {
        val token = mMirrorDisplayToken
        if (token != null) {
            try {
                ensureMirrorMethodsCached()
                sCachedDestroyDisplay!!.invoke(null, token)
            } catch (e: Exception) {
                AppLogger.w(TAG, "destroyDisplay via reflection failed: ${e.message}")
            }
            mMirrorDisplayToken = null
        }
    }

    private fun stopPreview() {
        // If we were mirroring via the daemon, tell it to drop its OWN SurfaceControl token too.
        // destroyMirrorToken() below only releases the LOCAL token; without this the daemon keeps
        // compositing the cluster after a stop → residual SurfaceFlinger/CPU load (sluggish tablet
        // after projection ends). Read the flag before stopMirrorViaDaemon() clears it.
        if (mMirrorViaDaemon) {
            // Must be the SURFACE daemon's binder. Until 1.8.x this read
            // ProxyClient.getDaemonBinder() (renamed getProxyDaemonBinder in 1.8.x) — the PROXY
            // daemon — so the transact carried SurfaceDaemon.DESCRIPTOR to a binder that enforces
            // the proxy DESCRIPTOR: rejected,
            // the SecurityException swallowed by readException(), and the surface daemon never
            // told to release its cluster SurfaceControl token. Masked on the UI teardown path
            // (MirrorCoordinator.stopNormalMirror stops correctly first and clears the flag) but
            // live on the service-driven paths ClusterService.stopProjectionNoAdb() and
            // ClusterService.onDestroy() → residual compositing after a stop.
            val daemon = DaemonBinderResolver.surfaceDaemonBinder()
            if (daemon != null) {
                stopMirrorViaDaemon(daemon) // synchronous TRANSACT_MIRROR_STOP; clears the flags
            }
        }
        mMirrorActive = false
        mMirrorViaDaemon = false
        mProjScale = 0f  // Reset: signals "not yet set" to touch mapping
        destroyMirrorToken()
    }

    /**
     * Stops the local preview (called from MainActivity.onStop).
     */
    fun stopMirror() {
        stopPreview()
        AppLogger.i(TAG, "ClusterMirrorManager preview stopped")
    }

    /**
     * Releases the preview.
     * Must only be called from ClusterService.onDestroy().
     */
    fun release() {
        stopPreview()
        AppLogger.i(TAG, "ClusterMirrorManager released")
    }

    companion object {
        private const val TAG = "ClusterMirrorManager"

        /** Guard to prevent repeated costly reflection calls if unlockHiddenApis is called more than once. */
        private val sHiddenApisUnlocked = AtomicBoolean(false)

        /**
         * Marks a failure of the in-app SurfaceControl path that can never resolve while this
         * process lives (missing hidden API, or SurfaceFlinger refusing to create the display).
         */
        private class InAppMirrorUnavailable(message: String) : RuntimeException(message)

        /**
         * Latched once the in-app SurfaceControl path has failed for a permanent reason; further
         * [startMirror] calls then return false immediately and log nothing.
         *
         * Field evidence over the bug-report corpus: 235 × "SurfaceControl mirror FAILED" and 0
         * successes (54 of them on DiLink 3.0). The path is deliberately KEPT — no dump ever
         * printed a `granted=` line for ACCESS_SURFACE_FLINGER, so "it never works" is an
         * inference, and a unit that genuinely holds the permission must keep working unchanged.
         * What is removed is only the repeated noise and the wasted work after the first failure.
         *
         * CANNOT MIS-LATCH: set only from [isPermanentInAppMirrorDenial], i.e. `createDisplay`
         * returning null (SurfaceFlinger's refusal signature) or an explicit SecurityException.
         * Every transient condition avoids it — "no cluster display yet" never reaches the catch
         * (MirrorCoordinator/ClusterResizeActivity only call startMirror when a display exists,
         * and a null/invalid target Surface returns before the try block), a plain transaction
         * failure keeps today's ERROR + retry behaviour, and hidden-API reflection failures are
         * excluded too because they depend on [unlockHiddenApis] having run first.
         *
         * Process-wide (companion) on purpose: ClusterService and ClusterResizeActivity each own
         * their own ClusterMirrorManager, and the permission is a property of the process.
         */
        @Volatile private var sInAppMirrorDenied = false

        /**
         * True if [e] means SurfaceFlinger refused this process — the only verdict allowed to
         * latch [sInAppMirrorDenied].
         *
         * Deliberately narrow: `createDisplay → null` (the observed refusal signature, wrapped in
         * [InAppMirrorUnavailable]) and an explicit SecurityException. Reflection failures from
         * [ensureMirrorMethodsCached] are NOT included even though they look permanent — hidden-API
         * access depends on [unlockHiddenApis] having run first, so latching on them could disable
         * the path over an ordering artefact rather than a real denial.
         */
        private fun isPermanentInAppMirrorDenial(e: Throwable): Boolean {
            var cause: Throwable? = e
            var depth = 0
            while (cause != null && depth++ < 5) {
                if (cause is InAppMirrorUnavailable) return true
                if (cause is SecurityException) return true
                cause = cause.cause
            }
            return false
        }

        /**
         * Writes [SurfaceDaemon.DESCRIPTOR] into [data] — the ONLY place this class pairs that
         * token with a binder.
         *
         * There are two uid-2000 daemons with two different binders and two different DESCRIPTORs
         * (see the class doc). Sending this token to the proxy daemon's binder makes its
         * `enforceInterface` throw a SecurityException that `reply.readException()` swallows, so
         * the call silently does nothing — that is how the teardown bug survived unnoticed. The
         * check below is deliberately NEGATIVE: it refuses only a binder that is provably the
         * *other* daemon's, so it can never reject a legitimate surface-daemon binder (the two
         * daemons are distinct processes; their binders can never be the same object).
         *
         * Obtain the right binder from [DaemonBinderResolver.surfaceDaemonBinder].
         *
         * @return true if the token was written and the caller may transact.
         */
        private fun writeSurfaceDaemonToken(daemonBinder: IBinder, data: Parcel): Boolean {
            if (daemonBinder === ProxyClient.getProxyDaemonBinder()) {
                AppLogger.w(TAG, "refusing to send the surface daemon's interface token to the"
                        + " PROXY daemon's binder — it would be rejected silently."
                        + " Use DaemonBinderResolver.surfaceDaemonBinder().")
                return false
            }
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            return true
        }

        // ── Cached reflection Methods — resolved once, reused on every mirror start ──────────────
        @Volatile private var sMirrorMethodsCached = false
        private var sCachedGetLayerStack: Method? = null
        private var sCachedCreateDisplay: Method? = null
        private var sCachedSetDisplaySurface: Method? = null
        private var sCachedSetDisplayLayerStack: Method? = null
        private var sCachedSetDisplayProjection: Method? = null
        private var sCachedDestroyDisplay: Method? = null

        @Synchronized
        @Throws(Exception::class)
        private fun ensureMirrorMethodsCached() {
            if (sMirrorMethodsCached) return
            sCachedGetLayerStack = Display::class.java.getDeclaredMethod("getLayerStack")
                    .apply { isAccessible = true }
            val scClass = Class.forName("android.view.SurfaceControl")
            sCachedCreateDisplay = scClass.getDeclaredMethod("createDisplay",
                    String::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            sCachedDestroyDisplay = scClass.getDeclaredMethod("destroyDisplay", IBinder::class.java)
                    .apply { isAccessible = true }
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            sCachedSetDisplaySurface = txClass.getDeclaredMethod("setDisplaySurface",
                    IBinder::class.java, Surface::class.java)
                    .apply { isAccessible = true }
            sCachedSetDisplayLayerStack = txClass.getDeclaredMethod("setDisplayLayerStack",
                    IBinder::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            sCachedSetDisplayProjection = txClass.getDeclaredMethod("setDisplayProjection",
                    IBinder::class.java, Int::class.javaPrimitiveType, Rect::class.java, Rect::class.java)
                    .apply { isAccessible = true }
            sMirrorMethodsCached = true
        }

        /**
         * Unlocks hidden APIs (SurfaceControl, Display.getLayerStack, etc.).
         * Guarded by an AtomicBoolean so repeated calls (e.g. Activity re-create) are no-ops.
         */
        @JvmStatic
        fun unlockHiddenApis() {
            if (!sHiddenApisUnlocked.compareAndSet(false, true)) {
                AppLogger.d(TAG, "unlockHiddenApis: already unlocked, skipping")
                return
            }
            try {
                val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                        "getDeclaredMethod", String::class.java, arrayOf<Class<*>>().javaClass)
                val forNameMethod = Class::class.java.getDeclaredMethod("forName", String::class.java)
                val vmRuntimeClass = forNameMethod.invoke(null, "dalvik.system.VMRuntime") as Class<*>
                val getRuntimeMethod = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
                val vmRuntime = getRuntimeMethod.invoke(null)
                val setExemptions = getDeclaredMethod.invoke(vmRuntimeClass,
                        "setHiddenApiExemptions", arrayOf<Class<*>>(arrayOf<String>().javaClass)) as Method
                setExemptions.invoke(vmRuntime,
                        arrayOf("Landroid/", "Lcom/android/", "Ljava/lang/"))
                AppLogger.i(TAG, "unlockHiddenApis OK — SurfaceControl accessible")
            } catch (e: Exception) {
                AppLogger.w(TAG, "unlockHiddenApis ERROR: ${e.message}")
            }
        }

        /**
         * DL5 cluster architecture quirk: apps render on layerStack 3/4
         * (shared_fission_bg_XDJAScreenProjection_0/_1) which expose a shadow
         * framebuffer of 1×1 px — copying that layerStack into our preview yields
         * a black image. The actually-composited 1920×720 content displayed on
         * the physical cluster lives on layerStack=2 (fission_bg_XDJAScreenProjection).
         * Override 3/4 → 2 only on effective DL5. DL3 path untouched.
         *
         * The numeric rule lives in [ClusterLayerStackPolicy] so the mirror and the screenshot
         * recorder cannot drift apart again — they already had, and the recorder's missing override
         * is why every DiLink 5 cluster screenshot was an all-black frame. Behaviour here is
         * unchanged: same DL5 gate, same fail-open on a detection throw, same 3/4 → 2 mapping,
         * same log line.
         */
        private fun applyDl5LayerStackOverride(ctx: Context?, detectedLayerStack: Int): Int {
            if (ctx == null) return detectedLayerStack
            val dl5: Boolean
            try {
                dl5 = Platform.get().isDiLink5(ctx)
            } catch (t: Throwable) {
                return detectedLayerStack
            }
            val effective = ClusterLayerStackPolicy.composedOrSelf(dl5, detectedLayerStack)
            if (effective != detectedLayerStack) {
                AppLogger.i(TAG, "DL5 override: layerStack $detectedLayerStack → $effective (mirror composed fission output)")
            }
            return effective
        }

        /**
         * v1.2.7 — Symmetric to [applyDl5LayerStackOverride]: on DL5, when the detected
         * displayId (used by the daemon's MotionEvent.setDisplayId target for touch injection) is
         * the shadow render display 3 or 4 (framebufferSpace 1×1), rewrite it to 2 — the WMS
         * displayId backing the composed 1920×720 cluster output where the user actually sees
         * the apps. On DL3 and on any other displayId value, returns the input unchanged.
         */
        private fun applyDl5DisplayIdOverride(ctx: Context?, detectedDisplayId: Int): Int {
            if (ctx == null) return detectedDisplayId
            val dl5: Boolean
            try {
                dl5 = Platform.get().isDiLink5(ctx)
            } catch (t: Throwable) {
                return detectedDisplayId
            }
            val effective = ClusterLayerStackPolicy.composedOrSelf(dl5, detectedDisplayId)
            if (effective != detectedDisplayId) {
                AppLogger.i(TAG, "DL5 override: displayId $detectedDisplayId → $effective (touch injection on composed cluster face)")
            }
            return effective
        }
    }
}
