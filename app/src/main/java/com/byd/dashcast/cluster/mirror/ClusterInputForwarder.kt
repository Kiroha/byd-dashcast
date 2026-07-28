package com.byd.dashcast.cluster.mirror

import android.content.Context
import android.graphics.Point
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import com.byd.dashcast.util.AppLogger

import java.lang.reflect.Method

/**
 * Injects touch and key events to the app running on the (non-touch) cluster display.
 *
 *  - The user touches the "touchpad" area on the main 15.6" screen.
 *  - Coordinates are mapped to the cluster dimensions (e.g. 1920×1080).
 *  - A MotionEvent is injected via InputManager.injectInputEvent() (@hide API,
 *    accessed by reflection, requires android.permission.INJECT_EVENTS).
 *  - KeyEvents (Back/Home/Volume) are injected directly; they route to the
 *    globally focused window, including on the secondary display.
 *
 * On BYD DiLink 3.0 (Android 10), events injected with setDisplayId() reach windows on
 * that display if the InputDispatcher supports multi-display.
 *
 * The preferred path is [SurfaceDaemon] — the uid-2000 daemon that HOLDS the cluster surfaces and
 * also owns input injection into them. Its binder arrives through [setDaemonBinder] and is
 * resolved by [com.byd.dashcast.proxy.DaemonBinderResolver.surfaceDaemonBinder], never from
 * `ProxyClient.getProxyDaemonBinder()` (that is the *other*, command-executing daemon). The in-app
 * reflective path below is only a fallback for when no daemon binder is available.
 */
@Suppress("DEPRECATION")
class ClusterInputForwarder(context: Context) {

    // 1.2.29 — volatile: setClusterDisplay()/setDaemonBinder() run on the main thread, but
    // injectTouchAt*/injectKey() can be invoked from the touch dispatcher thread on DL5
    // (multi-display routing) at 60-120 events/s. Without volatile, a stale read (esp.
    // mDaemonBinder == null after a fresh set) silently drops events to the local path.
    @Volatile private var mClusterWidth = 1920
    @Volatile private var mClusterHeight = 720 // cluster VirtualDisplay is 1920×720 (dumpsys window 03/05/2026)
    @Volatile private var mClusterDisplayId = 1 // Cluster display ID (routing for API 29)

    /** SurfaceDaemon Binder — if non-null, events are routed through uid=2000. */
    @Volatile private var mDaemonBinder: IBinder? = null

    private var mInputManager: Any? = null
    private var mInjectMethod: Method? = null
    private var mSetDisplayIdMethod: Method? = null // cached to avoid reflection on every MotionEvent
    private var mSetDisplayIdKeyMethod: Method? = null // v1.2.11 — KeyEvent.setDisplayId (route keys to cluster)
    private var mAvailable = false
    @Volatile private var mTouchDownTime = 0L

    // v1.6.147 — one-shot marker so the unprivileged direct path reports itself exactly once
    // per process instead of staying silent. INC-20260724-102136 was hard to diagnose precisely
    // because every touch took this path and logged nothing at all.
    @Volatile private var mDirectInjectLogged = false

    /**
     * Latched when the framework REFUSES a direct injection with a SecurityException, i.e.
     * INJECT_EVENTS (a signature permission an unprivileged app can never obtain) is denied to
     * this process. From then on the direct path is skipped silently instead of throwing — and
     * logging an ERROR — on every single touch event.
     *
     * CANNOT MIS-LATCH: only a SecurityException sets it, and that verdict is permanent for the
     * life of the process. A plain `false` return from injectInputEvent, a recycled MotionEvent,
     * or "no focused window on the cluster yet" are all transient and deliberately leave the path
     * enabled — they keep today's per-event ERROR. The preferred daemon path is checked before
     * this flag is even read, so a daemon binder arriving later is unaffected.
     */
    @Volatile private var mDirectInjectDenied = false

    // 1.2.31 — pre-allocated pointer arrays reused across touch events. MotionEvent.obtain(...)
    // only reads the first `pointerCount` entries (AOSP copies the data → safe to reuse). Access
    // serialised via mTouchInjectLock (cluster touch driven by the UI thread AND Beta probes).
    private val mTouchInjectLock = Any()
    private val mProps = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val mCoords = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }

    init {
        try {
            // InputManager.getInstance() is a @hide method since API 16.
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
            mInputManager = getInstance.invoke(null)

            // injectInputEvent(InputEvent, int) is @hide but accessible via reflection.
            // Requires android.permission.INJECT_EVENTS (signature permission).
            mInjectMethod = imClass.getDeclaredMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            // Cache setDisplayId to avoid reflection on every touch event.
            try {
                mSetDisplayIdMethod = MotionEvent::class.java
                    .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            } catch (ignored: Exception) {
                // @hide API not available on this ROM — injection without displayId. v1.6.147:
                // no longer swallowed silently: without it, directly-injected events carry
                // displayId=0 and land on the head unit instead of the cluster.
                AppLogger.w(TAG, "MotionEvent.setDisplayId unavailable — "
                        + "direct-path injection would target display 0, not the cluster")
            }
            // v1.2.11 — same for KeyEvent so injected keys reach the cluster display.
            try {
                mSetDisplayIdKeyMethod = KeyEvent::class.java
                    .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            } catch (ignored: Exception) {
                // not available on this ROM
            }

            mAvailable = true
            AppLogger.i(TAG, "InputManager injection: available")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Init failed (INJECT_EVENTS permission missing?)", e)
        }
    }

    /** Called when the cluster display is detected, to get its dimensions and ID. */
    fun setClusterDisplay(display: Display?) {
        if (display == null) return
        val size = Point()
        display.getSize(size)
        mClusterWidth = size.x
        mClusterHeight = size.y
        mClusterDisplayId = display.displayId
        AppLogger.i(TAG, "Cluster dimensions: ${mClusterWidth}x$mClusterHeight displayId=$mClusterDisplayId")
    }

    /** Updates the cluster display ID (used when Display is null but ID is known). */
    fun setClusterDisplayId(displayId: Int) {
        mClusterDisplayId = displayId
    }

    /**
     * Injects the cluster geometry when NO [Display] object can be obtained.
     *
     * DiLink 4.0 only: the OEM patched DisplayManagerService with an app whitelist, so
     * `dm.getDisplay(1)` returns null for our uid even though the display exists and is ON.
     * [setClusterDisplay] then no-ops on the null and the forwarder silently keeps its
     * 1920x720 compile-time defaults — i.e. every touch would be scaled against a guess that
     * merely happens to be right on the units seen so far. The uid-2000 daemon reads the real
     * geometry out of `dumpsys display`; this is how it gets in.
     *
     * Never called on DL3/DL5 (the Display object is always available there).
     */
    fun setClusterGeometry(displayId: Int, width: Int, height: Int) {
        if (width > 0) mClusterWidth = width
        if (height > 0) mClusterHeight = height
        mClusterDisplayId = displayId
        AppLogger.i(TAG, "Cluster geometry injected (no Display object): "
                + "${mClusterWidth}x$mClusterHeight displayId=$mClusterDisplayId")
    }

    /**
     * Passes the SurfaceDaemon Binder to this forwarder. When non-null, touch/key injection is
     * routed through the daemon (uid=2000) which holds android.permission.INJECT_EVENTS.
     */
    fun setDaemonBinder(binder: IBinder?) {
        mDaemonBinder = binder
        // v1.6.147 — the old message claimed "connected" even when handed null, which would have
        // masked the very failure of INC-20260724-102136. Report what actually happened.
        if (binder != null) {
            AppLogger.i(TAG, "Daemon Binder connected — touch/key injection via uid=2000")
        } else {
            AppLogger.w(TAG, "Daemon Binder cleared — touch/key injection falls back to the "
                    + "unprivileged direct path (events will not reach the cluster)")
        }
    }

    /**
     * Forwards already-mapped cluster coordinates for N pointers (multi-touch).
     * This enables pinch gestures (ACTION_POINTER_DOWN/MOVE/POINTER_UP).
     */
    fun forwardTouchFinalMulti(
        pointerIds: IntArray?,
        clusterXs: FloatArray?,
        clusterYs: FloatArray?,
        actionMasked: Int,
        actionIndex: Int,
        pointerCount: Int
    ) {
        if (pointerIds == null || clusterXs == null || clusterYs == null) return
        if (pointerCount <= 0) return
        if (pointerCount > pointerIds.size || pointerCount > clusterXs.size || pointerCount > clusterYs.size) {
            return
        }
        injectTouchAtMulti(pointerIds, clusterXs, clusterYs, actionMasked, actionIndex, pointerCount)
    }

    /** Internal: build and inject a multi-pointer MotionEvent at cluster coordinates. */
    private fun injectTouchAtMulti(
        pointerIds: IntArray,
        clusterXs: FloatArray,
        clusterYs: FloatArray,
        actionMasked: Int,
        actionIndex: Int,
        pointerCount: Int
    ) {
        // 1.2.31 — reuse pre-allocated mProps / mCoords. Cap at MAX_POINTERS so a malformed
        // caller can never overrun the scratch arrays.
        val n = Math.min(pointerCount, MAX_POINTERS)
        synchronized(mTouchInjectLock) {
            if (actionMasked == MotionEvent.ACTION_DOWN || mTouchDownTime == 0L) {
                mTouchDownTime = SystemClock.uptimeMillis()
            }
            val now = SystemClock.uptimeMillis()
            val safeActionIndex = Math.max(0, Math.min(actionIndex, n - 1))
            val action = if (actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
                actionMasked == MotionEvent.ACTION_POINTER_UP
            ) {
                actionMasked or (safeActionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            } else {
                actionMasked
            }

            for (i in 0 until n) {
                val p = mProps[i]
                p.clear()
                p.id = pointerIds[i]
                p.toolType = MotionEvent.TOOL_TYPE_FINGER

                val c = mCoords[i]
                c.clear()
                c.x = clusterXs[i]
                c.y = clusterYs[i]
                c.pressure = 1.0f
                c.size = 1.0f
            }

            try {
                // Preferred path: daemon uid=2000 (INJECT_EVENTS guaranteed).
                val binder = mDaemonBinder
                if (binder != null) {
                    var ev: MotionEvent? = null
                    var data: Parcel? = null
                    try {
                        ev = MotionEvent.obtain(
                            mTouchDownTime, now, action, n, mProps, mCoords,
                            0, 0, 1.0f, 1.0f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                        )
                        data = Parcel.obtain()
                        data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
                        data.writeParcelable(ev, 0)
                        binder.transact(
                            SurfaceDaemon.TRANSACT_INJECT_MOTION, data, null, IBinder.FLAG_ONEWAY
                        )
                    } catch (doe: android.os.DeadObjectException) {
                        // v1.3.3 — silent binder death seen on user devices; invalidate so
                        // ProxyKeeperService can recover.
                        ProxyClient.invalidateBinder("InjectMotion")
                        AppLogger.w(TAG, "injectTouchAt: daemon binder dead — invalidated")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "injectTouchAt via daemon failed", e)
                    } finally {
                        data?.recycle()
                        ev?.recycle()
                    }
                    return
                }

                if (!mAvailable || mDirectInjectDenied) return

                var ev: MotionEvent? = null
                try {
                    ev = MotionEvent.obtain(
                        mTouchDownTime, now, action, n, mProps, mCoords,
                        0, 0, 1.0f, 1.0f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                    )
                    // setDisplayId is a @hide API — using the Method cached in the constructor.
                    if (mSetDisplayIdMethod != null) {
                        try {
                            mSetDisplayIdMethod!!.invoke(ev, mClusterDisplayId)
                        } catch (e: Exception) {
                            AppLogger.d(TAG, "setDisplayId via reflection failed: " + e.message)
                        }
                    }
                    val injected = mInjectMethod?.invoke(mInputManager, ev, INJECT_INPUT_EVENT_MODE_ASYNC)
                    // v1.6.147 — the return value used to be discarded, so a direct path that
                    // never reaches the cluster looked identical to a working one. Report the
                    // outcome once per process (see mDirectInjectLogged).
                    if (!mDirectInjectLogged) {
                        mDirectInjectLogged = true
                        AppLogger.w(TAG, "direct-path injection (no daemon Binder): ret=$injected "
                                + "displayId=$mClusterDisplayId setDisplayId="
                                + "${mSetDisplayIdMethod != null} — cluster touch likely inert")
                    }
                } catch (e: Exception) {
                    if (!latchDirectInjectDenied("injectTouchAtMulti", e)) {
                        AppLogger.e(TAG, "injectTouchAtMulti failed action=$actionMasked ptrs=$n disp=$mClusterDisplayId", e)
                    }
                } finally {
                    ev?.recycle()
                }
            } finally {
                // Audit batch 1 — applies to both daemon and direct paths so mTouchDownTime
                // never carries over between gestures.
                if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                    mTouchDownTime = 0L
                }
            }
        }
    }

    /**
     * Injects a DOWN+UP pair for an Android keyCode (e.g. KEYCODE_BACK, KEYCODE_HOME).
     * KeyEvents route to the focused window (including on the cluster display).
     */
    fun injectKey(keyCode: Int) {
        // Preferred path: daemon uid=2000
        val binder = mDaemonBinder
        if (binder != null) {
            try {
                val now = SystemClock.uptimeMillis()
                val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
                val up = KeyEvent(now, now + 1, KeyEvent.ACTION_UP, keyCode, 0)
                val dataDown = Parcel.obtain()
                try {
                    dataDown.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
                    dataDown.writeParcelable(down, 0)
                    binder.transact(SurfaceDaemon.TRANSACT_INJECT_KEY, dataDown, null, IBinder.FLAG_ONEWAY)
                } finally {
                    dataDown.recycle()
                }
                val dataUp = Parcel.obtain()
                try {
                    dataUp.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
                    dataUp.writeParcelable(up, 0)
                    binder.transact(SurfaceDaemon.TRANSACT_INJECT_KEY, dataUp, null, IBinder.FLAG_ONEWAY)
                } finally {
                    dataUp.recycle()
                }
            } catch (doe: android.os.DeadObjectException) {
                ProxyClient.invalidateBinder("InjectKey")
                AppLogger.w(TAG, "injectKey: daemon binder dead — invalidated")
            } catch (e: Exception) {
                AppLogger.e(TAG, "injectKey via daemon failed", e)
            }
            return
        }
        if (!mAvailable || mDirectInjectDenied) return
        val now = SystemClock.uptimeMillis()
        try {
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now + 1, KeyEvent.ACTION_UP, keyCode, 0)
            // 1.2.30 — route to the cluster display so the cluster-side focused window gets the key.
            if (mSetDisplayIdKeyMethod != null) {
                try {
                    mSetDisplayIdKeyMethod!!.invoke(down, mClusterDisplayId)
                    mSetDisplayIdKeyMethod!!.invoke(up, mClusterDisplayId)
                } catch (ignored: Exception) {
                    // inject anyway
                }
            }
            mInjectMethod?.invoke(mInputManager, down, INJECT_INPUT_EVENT_MODE_ASYNC)
            mInjectMethod?.invoke(mInputManager, up, INJECT_INPUT_EVENT_MODE_ASYNC)
        } catch (e: Exception) {
            if (!latchDirectInjectDenied("injectKey", e)) {
                AppLogger.e(TAG, "Key inject failed", e)
            }
        }
    }

    /**
     * Injects a fully-formed KeyEvent (with meta state, scan code, source, etc.).
     * Used by KeyboardBridgeActivity to forward typed characters.
     *
     * @since v1.2.8
     */
    fun injectKeyEvent(kev: KeyEvent?) {
        if (kev == null) return
        // Preferred path: daemon uid=2000
        val binder = mDaemonBinder
        if (binder != null) {
            try {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
                    data.writeParcelable(kev, 0)
                    binder.transact(SurfaceDaemon.TRANSACT_INJECT_KEY, data, null, IBinder.FLAG_ONEWAY)
                } finally {
                    data.recycle()
                }
            } catch (doe: android.os.DeadObjectException) {
                ProxyClient.invalidateBinder("InjectKeyEvent")
                AppLogger.w(TAG, "injectKeyEvent: daemon binder dead — invalidated")
            } catch (e: Exception) {
                AppLogger.e(TAG, "injectKeyEvent via daemon failed", e)
            }
            return
        }
        if (!mAvailable || mDirectInjectDenied) return
        try {
            // v1.2.11 — route to the cluster display so the cluster-side focused window gets the key.
            if (mSetDisplayIdKeyMethod != null) {
                try {
                    mSetDisplayIdKeyMethod!!.invoke(kev, mClusterDisplayId)
                } catch (ignored: Exception) {
                    // inject anyway
                }
            }
            mInjectMethod?.invoke(mInputManager, kev, INJECT_INPUT_EVENT_MODE_ASYNC)
        } catch (e: Exception) {
            if (!latchDirectInjectDenied("injectKeyEvent", e)) {
                AppLogger.e(TAG, "injectKeyEvent direct failed", e)
            }
        }
    }

    /**
     * If [e] is the framework refusing the injection (INJECT_EVENTS denied), latches
     * [mDirectInjectDenied], logs exactly ONE line at WARN — an unprivileged app can never hold
     * that signature permission, so this is an expected state, not the security regression the
     * old per-event ERROR looked like — and returns true so the caller skips its ERROR.
     *
     * Returns false for every other failure, leaving today's behaviour untouched.
     */
    private fun latchDirectInjectDenied(where: String, e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        var denied = false
        while (cause != null && depth++ < 5) {
            if (cause is SecurityException) { denied = true; break }
            cause = cause.cause
        }
        if (!denied) return false
        if (!mDirectInjectDenied) {
            mDirectInjectDenied = true
            AppLogger.w(TAG, "$where: INJECT_EVENTS denied to the app process — expected without "
                    + "the platform signature. Direct-path injection disabled for this process; "
                    + "cluster touch/keys need the uid=2000 surface daemon. (${cause?.message})")
        }
        return true
    }

    fun isAvailable(): Boolean = mAvailable

    fun getClusterWidth(): Int = mClusterWidth
    fun getClusterHeight(): Int = mClusterHeight

    companion object {
        private const val TAG = "ClusterInputForwarder"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        // = Android InputDispatcher max pointers.
        private const val MAX_POINTERS = 16
    }
}
