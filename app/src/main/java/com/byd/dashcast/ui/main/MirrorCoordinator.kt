package com.byd.dashcast.ui.main

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

import androidx.core.view.isVisible

import com.byd.dashcast.R
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.cluster.display.ClusterDisplayRegistry
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager
import com.byd.dashcast.fission.FissionOrchestrator
import com.byd.dashcast.ime.ClusterImeWatcherService
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.util.AppLogger

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the TextureView / SurfaceTexture / Surface lifecycle for the cluster preview mirror.
 *
 * All methods must be called on the main thread.
 */
class MirrorCoordinator(
    private val mTextureView: TextureView,
    private val mFrameMirror: FrameLayout,
    private val mPlaceholder: TextView?,
    private val mHost: Host
) {

    interface Host {
        fun getContext(): Context
        fun getClusterServiceIfBound(): ClusterService?

        /**
         * The **SURFACE** daemon's binder ([com.byd.dashcast.proxy.daemon.SurfaceDaemon],
         * ServiceManager name `byd_mirror_daemon`), or `null` if not resolved yet.
         * Never `ProxyClient.getProxyDaemonBinder()` — that is the other daemon and the
         * mirror transactions below would be rejected silently.
         */
        fun getSurfaceDaemonBinder(): IBinder?
        fun onPreviewClicked()
    }

    private var mMirrorSurface: Surface? = null
    private var mDestroyed = false
    private var mLayoutMirrorActive = false
    private var mLayoutMirrorPackage: String? = null
    private var mLayoutProjection: MirrorProjection? = null

    // Reusable arrays for touch forwarding — avoids per-event allocation at 60-120 Hz.
    // Cap at 16 pointers (= Android InputDispatcher limit, matches ClusterInputForwarder.MAX_POINTERS).
    private val mFwdPointerIds = IntArray(MAX_FWD_POINTERS)
    private val mFwdClusterXs = FloatArray(MAX_FWD_POINTERS)
    private val mFwdClusterYs = FloatArray(MAX_FWD_POINTERS)
    private val mLayoutPointerProperties =
        Array(MAX_FWD_POINTERS) { MotionEvent.PointerProperties() }
    private val mLayoutPointerCoords =
        Array(MAX_FWD_POINTERS) { MotionEvent.PointerCoords() }

    // ONE Runnable for the lifetime of this coordinator, cancelled by every stop/destroy path.
    // It must never be allocated at the postDelayed() call site: removeCallbacks matches by
    // identity, so a fresh instance per touch would leave an uncancellable probe behind that
    // fires after the mirror is gone. MirrorImeCallbackLifecycleTest guards this structurally.
    private val mPostTouchImeCheck: Runnable = Runnable {
        if (mDestroyed || mFrameMirror.visibility != View.VISIBLE) return@Runnable
        try {
            ClusterImeWatcherService.checkAndLaunchBridgeIfNeeded(mHost.getContext())
        } catch (t: Throwable) {
            AppLogger.e(TAG, "auto-keyboard post-touch check failed", t)
        }
    }

    init {
        setup()
    }

    private fun setup() {
        mTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(w, h)
                mMirrorSurface = Surface(st)
                AppLogger.i(TAG, "SurfaceTexture available " + w + "x" + h)
                attemptStart()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(w, h)
                mMirrorSurface?.let { it.release() }
                mMirrorSurface = null
                mMirrorSurface = Surface(st)
                AppLogger.d(TAG, "SurfaceTexture size changed " + w + "x" + h)
                // Stop the active mirror (if any) so attemptStart() below is not
                // short-circuited by isMirrorActive(). Without this the mirror keeps
                // projecting at the stale size (e.g. 349px on DL5 cold start) because
                // the weighted-card layout emits a wrong first pass before settling.
                // Bypass stopMirror() to avoid flashing the placeholder during resize.
                stopMirrorForRestart()
                attemptStart()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stopMirror()
                mMirrorSurface?.let { it.release() }
                mMirrorSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) { /* no-op */ }
        }

        // If the SurfaceTexture is already available (Activity recreated), init immediately.
        if (mTextureView.isAvailable) {
            val st = mTextureView.surfaceTexture
            if (st != null) {
                val w = mTextureView.width
                val h = mTextureView.height
                if (w > 0 && h > 0) st.setDefaultBufferSize(w, h)
                mMirrorSurface = Surface(st)
            }
        }

        mFrameMirror.setOnClickListener { mHost.onPreviewClicked() }

        // Touch → map coordinates → inject on cluster display
        mTextureView.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                // v0.9.79 — prevent NestedScrollView (or any ancestor) from stealing the
                // gesture once the finger moves past touchSlop, otherwise vertical drags
                // and pinch gestures get cancelled mid-flight.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                forwardTouchFromMirror(event)
                return true
            }
        })
    }

    /** Called when a daemon Binder arrives after Activity start. */
    fun onDaemonBinderAvailable(daemonBinder: IBinder?) {
        if (mDestroyed) return
        val svc = mHost.getClusterServiceIfBound()
        if (svc != null) {
            val mm = svc.getMirrorManager()
            if (mm.isMirrorActive() && !mm.isMirrorViaDaemon()) {
                AppLogger.i(TAG, "Daemon arrived after direct-path mirror — restarting via daemon")
                stopMirror()
            }
        }
        val surface = mMirrorSurface
        if (surface != null && surface.isValid
                && mFrameMirror.isVisible) {
            attemptStart()
        }
    }

    /** Rebinds the existing TextureView when the selected headless Layout slot changes. */
    fun onLayoutTargetChanged() {
        if (mDestroyed) return
        val target = FissionOrchestrator.getSelectedLayoutMirrorTarget()
        val nextPackage = target?.pkg
        if (mLayoutMirrorActive && nextPackage == mLayoutMirrorPackage) {
            return
        }
        if (!mLayoutMirrorActive && nextPackage == null) return
        stopMirrorForRestart()
        val surface = mMirrorSurface
        if (surface != null && surface.isValid
                && mFrameMirror.isVisible) {
            attemptStart()
        }
    }

    /** Attempts to start the mirror using whichever path is available. */
    fun attemptStart() {
        if (mDestroyed) return
        val surface = mMirrorSurface
        if (surface == null || !surface.isValid) return

        val viewW = mTextureView.width
        val viewH = mTextureView.height
        if (viewW <= 0 || viewH <= 0) {
            AppLogger.d(TAG, "attemptStart: view not yet measured " + viewW + "x" + viewH)
            return
        }

        val layoutTarget = FissionOrchestrator.getSelectedLayoutMirrorTarget()
        if (layoutTarget != null) {
            if (mLayoutMirrorActive && layoutTarget.pkg == mLayoutMirrorPackage) {
                mTextureView.visibility = View.VISIBLE
                setPlaceholderVisible(false)
                return
            }
            stopNormalMirror()
            val started = FissionOrchestrator.startSelectedLayoutMirror(surface, viewW, viewH)
            val projection = if (started != null)
                MirrorProjection.create(started.width, started.height, viewW, viewH) else null
            if (started != null && projection != null) {
                mLayoutMirrorActive = true
                mLayoutMirrorPackage = started.pkg
                mLayoutProjection = projection
                mTextureView.visibility = View.VISIBLE
                setPlaceholderVisible(false)
                AppLogger.i(TAG, "Layout mirror active pkg=" + started.pkg
                        + " displayId=" + started.displayId
                        + " content=" + started.width + "x" + started.height)
            } else {
                clearLayoutMirrorState()
                mTextureView.visibility = View.GONE
                mPlaceholder?.setText(R.string.mirror_unavailable)
                setPlaceholderVisible(true)
            }
            return
        }

        if (mLayoutMirrorActive) {
            FissionOrchestrator.stopSelectedLayoutMirror()
            clearLayoutMirrorState()
        }

        val svc = mHost.getClusterServiceIfBound() ?: return

        val mm = svc.getMirrorManager()
        if (mm.isMirrorActive()) {
            AppLogger.d(TAG, "attemptStart: mirror already active")
            mTextureView.visibility = View.VISIBLE
            setPlaceholderVisible(false)
            return
        }

        val ctx = mHost.getContext()
        var clusterDisplay: Display? = null
        val displayId = svc.getDisplayId()
        if (displayId >= 0) {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager?
            if (dm != null) clusterDisplay = dm.getDisplay(displayId)
        }

        // "Do we have a cluster to mirror?" must be answered from the ID, not from the Display
        // object: on DiLink 4.0 the display exists and the daemon can drive it, but the OEM
        // DisplayManagerService whitelist means dm.getDisplay() above always returns null for
        // our uid. ClusterDisplayRegistry is only ever populated by the DL4 activation path, so
        // on DL3/DL5 this is exactly "clusterDisplay != null" as before.
        val haveClusterDisplay = clusterDisplay != null
                || (displayId > 0 && ClusterDisplayRegistry.forDisplayId(displayId) != null)

        var mirrorOk = false
        val daemonBinder = mHost.getSurfaceDaemonBinder()
        if (daemonBinder != null) {
            mirrorOk = mm.startMirrorViaDaemon(
                    ctx, daemonBinder, clusterDisplay, displayId, surface, viewW, viewH)
        }
        // Only attempt the unprivileged in-app SurfaceControl path when a cluster display
        // ACTUALLY EXISTS. With none, startMirror() cannot possibly succeed — yet it used to be
        // called anyway (startMirrorViaDaemon returns false both for "daemon failed" and for
        // "no display yet"), inventing a layerStack from a null Display
        // ("getLayerStack failed -> fallback layerStack=2") and then dying on
        // SurfaceControl.createDisplay -> null with an [ERROR] naming ACCESS_SURFACE_FLINGER —
        // three times in 0.9 s in INC-20260727-203241. An unprivileged app can NEVER hold that
        // permission (that is the whole reason the uid-2000 SurfaceDaemon exists), so the line reads like a
        // security regression and has repeatedly sent triage down a phantom path. When there is
        // no display the WARN already emitted by startMirrorViaDaemon says everything, and the
        // user-visible outcome is unchanged: mirrorOk stays false → placeholder.
        if (!mirrorOk && haveClusterDisplay) {
            mirrorOk = mm.startMirror(ctx, clusterDisplay, surface, viewW, viewH)
        } else if (!mirrorOk) {
            // Keep one quiet breadcrumb: with no daemon binder AND no display, nothing else
            // would have logged the skip at all.
            AppLogger.d(TAG, "attemptStart: no cluster display (id=" + displayId
                    + ") — in-app SurfaceControl fallback skipped")
        }

        if (mirrorOk) {
            mTextureView.visibility = View.VISIBLE
            setPlaceholderVisible(false)
        } else {
            mTextureView.visibility = View.GONE
            mPlaceholder?.setText(R.string.mirror_unavailable)
            setPlaceholderVisible(true)
        }
    }

    fun stopMirror() {
        cancelPostTouchImeCheck()
        if (mLayoutMirrorActive) {
            FissionOrchestrator.stopSelectedLayoutMirror()
            clearLayoutMirrorState()
        }
        stopNormalMirror()
        setPlaceholderVisible(true)
    }

    private fun stopMirrorForRestart() {
        cancelPostTouchImeCheck()
        if (mLayoutMirrorActive) {
            FissionOrchestrator.stopSelectedLayoutMirror()
            clearLayoutMirrorState()
        }
        stopNormalMirror()
    }

    private fun stopNormalMirror() {
        val svc = mHost.getClusterServiceIfBound() ?: return
        val mirror = svc.getMirrorManager()
        if (mirror.isMirrorViaDaemon()) mirror.stopMirrorViaDaemon(mHost.getSurfaceDaemonBinder())
        mirror.stopMirror()
    }

    private fun clearLayoutMirrorState() {
        mLayoutMirrorActive = false
        mLayoutMirrorPackage = null
        mLayoutProjection = null
    }

    fun showPreview() {
        if (mDestroyed) return
        mFrameMirror.visibility = View.VISIBLE
        attemptStart()
    }

    fun hidePreview() {
        stopMirror()
        mFrameMirror.visibility = View.GONE
    }

    /** Recreates the Surface from the current SurfaceTexture and restarts the mirror. */
    fun recreateSurfaceAndRestart() {
        if (mDestroyed) return
        try {
            val st = mTextureView.surfaceTexture
            val w = mTextureView.width
            val h = mTextureView.height
            if (st == null || w <= 0 || h <= 0) {
                AppLogger.w(TAG, "recreateSurface: not ready (w=" + w + " h=" + h + ")")
                return
            }
            st.setDefaultBufferSize(w, h)
            mMirrorSurface?.let { it.release() }
            mMirrorSurface = null
            mMirrorSurface = Surface(st)
            AppLogger.i(TAG, "recreateSurface: new surface " + w + "x" + h)
            attemptStart()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "recreateSurface failed: " + t.message)
        }
    }

    fun getMirrorSurface(): Surface? = mMirrorSurface

    fun isPreviewVisible(): Boolean = mFrameMirror.isVisible

    /**
     * Maps touch coordinates from the mirror TextureView to the cluster display and injects them.
     * The SurfaceControl projection preserves the ratio (letterboxing), so we recalculate
     * the offset the same way setDisplayProjection did.
     */
    private fun forwardTouchFromMirror(event: MotionEvent) {
        if (mLayoutMirrorActive && mLayoutProjection != null) {
            forwardTouchToLayout(event)
            return
        }
        val svc = mHost.getClusterServiceIfBound() ?: return
        val forwarder = svc.getInputForwarder() ?: return

        val mirror = svc.getMirrorManager() ?: return

        // Use the projection params stored when setDisplayProjection was called.
        // This guarantees the touch offset/scale matches the actual rendered projection,
        // even if the view was resized since mirror start (avoids touch offset bugs).
        val scale = mirror.getProjScale()
        // Reject non-finite scale too: a degenerate projection (e.g. a transient
        // 0-size cluster display) can leave scale = Infinity/NaN, which "scale <= 0f"
        // does not catch — it would map every touch to the clamp boundary.
        if (!scale.isFinite() || scale <= 0f) return  // Mirror not yet fully initialized

        val offsetX = mirror.getProjOffsetX()
        val offsetY = mirror.getProjOffsetY()
        val clusterW = mirror.getClusterWidth()
        val clusterH = mirror.getClusterHeight()
        if (clusterW <= 0 || clusterH <= 0) return

        val pointerCount = min(event.pointerCount, MAX_FWD_POINTERS)
        if (pointerCount <= 0) return

        for (i in 0 until pointerCount) {
            mFwdPointerIds[i] = event.getPointerId(i)
            val cx = (event.getX(i) - offsetX) / scale
            val cy = (event.getY(i) - offsetY) / scale
            mFwdClusterXs[i] = max(0f, min(cx, (clusterW - 1).toFloat()))
            mFwdClusterYs[i] = max(0f, min(cy, (clusterH - 1).toFloat()))
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN
                || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val ai = event.actionIndex
            if (ai >= 0 && ai < pointerCount) {
                AppLogger.d(TAG, "touch → ptrs=" + pointerCount
                        + " action=" + event.actionMasked
                        + " idx=" + ai
                        + " view(" + event.getX(ai).toInt() + "," + event.getY(ai).toInt() + ")"
                        + " off=(" + offsetX.toInt() + "," + offsetY.toInt() + ")"
                        + " scale=" + String.format(Locale.US, "%.3f", scale)
                        + " cluster=(" + mFwdClusterXs[ai].toInt() + "," + mFwdClusterYs[ai].toInt()
                        + ")/" + clusterW + "×" + clusterH)
            }
        }

        forwarder.forwardTouchFinalMulti(
                mFwdPointerIds,
                mFwdClusterXs,
                mFwdClusterYs,
                event.actionMasked,
                event.actionIndex,
                pointerCount
        )

        // v1.3.3 — DL5 only: after the finger is lifted from the cluster mirror,
        // wait 350 ms (enough for the cluster app to move input focus) then check
        // whether a focused editable node is visible on the cluster. If so,
        // auto-launch the keyboard bridge. This is additive to the event-driven
        // path in ClusterImeWatcherService (TYPE_VIEW_FOCUSED), which can miss
        // events when the ROM returns displayId=-1 on secondary-display a11y events.
        // Guard: only on DL5, only on ACTION_UP (lift), only when mirror is active.
        val actionMasked = event.actionMasked
        if ((actionMasked == MotionEvent.ACTION_UP
                || actionMasked == MotionEvent.ACTION_POINTER_UP)
                && ClusterService.sIsRunning) {
            try {
                if (Platform.get().isDiLink5(mHost.getContext())) {
                    mTextureView.removeCallbacks(mPostTouchImeCheck)
                    mTextureView.postDelayed(mPostTouchImeCheck, 350)
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "auto-keyboard DL5 guard check failed", t)
            }
        }
    }

    private fun cancelPostTouchImeCheck() {
        mTextureView.removeCallbacks(mPostTouchImeCheck)
    }

    private fun forwardTouchToLayout(event: MotionEvent) {
        val projection = mLayoutProjection ?: return
        val pointerCount = min(event.pointerCount, MAX_FWD_POINTERS)
        if (pointerCount <= 0) return

        for (i in 0 until pointerCount) {
            event.getPointerProperties(i, mLayoutPointerProperties[i])
            event.getPointerCoords(i, mLayoutPointerCoords[i])
            mLayoutPointerCoords[i].x = projection.mapX(event.getX(i))
            mLayoutPointerCoords[i].y = projection.mapY(event.getY(i))
        }

        var mapped: MotionEvent? = null
        try {
            mapped = MotionEvent.obtain(event.downTime, event.eventTime,
                    event.action, pointerCount,
                    mLayoutPointerProperties, mLayoutPointerCoords,
                    event.metaState, event.buttonState,
                    event.xPrecision, event.yPrecision,
                    event.deviceId, event.edgeFlags,
                    InputDevice.SOURCE_TOUCHSCREEN, event.flags)
            FissionOrchestrator.injectSelectedLayoutMotion(mapped)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                AppLogger.d(TAG, "Layout touch pkg=" + mLayoutMirrorPackage
                        + " view=(" + event.x.toInt() + "," + event.y.toInt() + ")"
                        + " slot=(" + mLayoutPointerCoords[0].x.toInt() + ","
                        + mLayoutPointerCoords[0].y.toInt() + ")")
            }
        } catch (error: Exception) {
            AppLogger.e(TAG, "Layout touch injection failed", error)
        } finally {
            mapped?.recycle()
        }
    }

    fun destroy() {
        mDestroyed = true
        cancelPostTouchImeCheck()
        stopMirror()
        mTextureView.setOnTouchListener(null)
        mTextureView.surfaceTextureListener = null
        mFrameMirror.setOnClickListener(null)
        mMirrorSurface?.let { it.release() }
        mMirrorSurface = null
    }

    private fun setPlaceholderVisible(visible: Boolean) {
        mPlaceholder?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    companion object {
        private const val TAG = "MirrorCoordinator"

        // Cap at 16 pointers (= Android InputDispatcher limit).
        private const val MAX_FWD_POINTERS = 16
    }
}
