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
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.daemon.MirrorDaemon
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
                throw RuntimeException("SurfaceControl.createDisplay → null")
            }

            // 3. Projection: preserve aspect ratio (letterbox)
            // Use integer arithmetic to match MirrorDaemon.setupMirror() exactly.
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
            AppLogger.e(TAG, "SurfaceControl mirror FAILED (ACCESS_SURFACE_FLINGER?) — use startMirrorViaDaemon()", e)
            destroyMirrorToken()
            false
        }
    }

    /**
     * Mirror via the MirrorDaemon (uid=2000) which holds ACCESS_SURFACE_FLINGER.
     * The daemon receives the Surface via Binder and configures SurfaceControl (static methods).
     * SYNCHRONOUS call: the daemon replies 1 (success) or 0 (failure) → mMirrorActive reflects
     * reality, which allows the screencap fallback if the daemon fails.
     */
    fun startMirrorViaDaemon(ctx: Context?, daemonBinder: IBinder?, clusterDisplay: Display?,
                             targetSurface: Surface?, viewW: Int, viewH: Int): Boolean {
        if (mMirrorActive) return true
        if (daemonBinder == null || targetSurface == null || !targetSurface.isValid) return false
        if (clusterDisplay == null) {
            AppLogger.w(TAG, "startMirrorViaDaemon: clusterDisplay null — mirror deferred until display is ready")
            return false
        }

        // Cluster dimensions — only accept a strictly-positive size (see startMirror).
        val sz = Point(1920, 720)
        clusterDisplay.getRealSize(sz)
        if (sz.x > 0 && sz.y > 0) {
            mClusterW = sz.x
            mClusterH = sz.y
        } else {
            AppLogger.w(TAG, "startMirrorViaDaemon: getRealSize returned ${sz.x}×${sz.y} — keeping ${mClusterW}×${mClusterH}")
        }

        // Pre-compute projection params (identical formula to MirrorDaemon.setupMirror).
        // Stored here so touch mapping uses exact same offsets as the daemon's projection.
        run {
            val scale = Math.min(viewW.toFloat() / mClusterW, viewH.toFloat() / mClusterH)
            val drawW = (mClusterW * scale).toInt()
            val drawH = (mClusterH * scale).toInt()
            mProjOffsetX = (viewW - drawW) / 2
            mProjOffsetY = (viewH - drawH) / 2
            mProjScale = scale
        }

        var clusterDisplayId = clusterDisplay.displayId
        var layerStack: Int
        try {
            ensureMirrorMethodsCached()
            layerStack = sCachedGetLayerStack!!.invoke(clusterDisplay) as Int
        } catch (e: Exception) {
            layerStack = clusterDisplayId
            AppLogger.w(TAG, "getLayerStack failed → fallback layerStack=$layerStack")
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
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR)
            data.writeInt(layerStack)
            data.writeInt(mClusterW)
            data.writeInt(mClusterH)
            data.writeInt(clusterDisplayId)
            data.writeInt(viewW)
            data.writeInt(viewH)
            data.writeParcelable(targetSurface, 0)
            // Synchronous call (not FLAG_ONEWAY) → daemon reply in 'reply' parcel
            daemonBinder.transact(MirrorDaemon.TRANSACT_MIRROR_START, data, reply, 0)
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
            com.byd.dashcast.proxy.ProxyClient.invalidateBinder("MirrorStart")
            AppLogger.e(TAG, "startMirrorViaDaemon DeadObjectException — binder invalidated", doe)
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
     * Requests the daemon to stop the SurfaceControl mirror.
     */
    fun stopMirrorViaDaemon(daemonBinder: IBinder?) {
        if (daemonBinder == null) return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR)
            // v0.9.77: SYNCHRONOUS stop (no FLAG_ONEWAY) so callers can immediately
            // restart the mirror at a different size (e.g. fullscreen toggle) without
            // a race where the daemon's stop is still queued behind the new start.
            daemonBinder.transact(MirrorDaemon.TRANSACT_MIRROR_STOP, data, reply, 0)
            try { reply.readException() } catch (ignored: Throwable) { /* daemon may not write reply */ }
        } catch (doe: android.os.DeadObjectException) {
            // v1.3.3 — see comment in startMirrorViaDaemon.
            com.byd.dashcast.proxy.ProxyClient.invalidateBinder("MirrorStop")
            AppLogger.w(TAG, "stopMirrorViaDaemon DeadObjectException — binder invalidated")
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
            val daemon = com.byd.dashcast.proxy.ProxyClient.getDaemonBinder()
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
         */
        private fun applyDl5LayerStackOverride(ctx: Context?, detectedLayerStack: Int): Int {
            if (ctx == null) return detectedLayerStack
            val dl5: Boolean
            try {
                dl5 = Platform.get().isDiLink5(ctx)
            } catch (t: Throwable) {
                return detectedLayerStack
            }
            if (!dl5) return detectedLayerStack
            if (detectedLayerStack == 3 || detectedLayerStack == 4) {
                AppLogger.i(TAG, "DL5 override: layerStack $detectedLayerStack → 2 (mirror composed fission output)")
                return 2
            }
            return detectedLayerStack
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
            if (!dl5) return detectedDisplayId
            if (detectedDisplayId == 3 || detectedDisplayId == 4) {
                AppLogger.i(TAG, "DL5 override: displayId $detectedDisplayId → 2 (touch injection on composed cluster face)")
                return 2
            }
            return detectedDisplayId
        }
    }
}
