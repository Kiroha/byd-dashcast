package com.byd.dashcast.fission

import android.os.IBinder
import android.os.Parcel
import android.view.MotionEvent
import android.view.Surface

import com.byd.dashcast.proxy.DaemonBinderResolver
import com.byd.dashcast.proxy.FissionResourceOwner
import com.byd.dashcast.proxy.MirrorResourceOwner
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import com.byd.dashcast.util.AppLogger

/**
 * Wire layer of the Layout / Fission subsystem: raw `Parcel` transactions on the **SURFACE**
 * daemon ([com.byd.dashcast.proxy.daemon.SurfaceDaemon], ServiceManager name
 * `byd_mirror_daemon`) — slot attach/resize/release, layout activation, mirror start/stop and
 * touch injection. Despite the name, "Fission" here is only the feature; the counterpart process
 * is the surface daemon.
 *
 * **Boundary rule.** DashCast runs two uid-2000 daemons and every method below writes
 * [SurfaceDaemon.DESCRIPTOR] onto the caller-supplied `binder`. That binder MUST come from
 * [getBinderFromServiceManager] (or the equivalent
 * [com.byd.dashcast.proxy.DaemonBinderResolver.surfaceDaemonBinder]) and NEVER from
 * `ProxyClient.getProxyDaemonBinder()` — the proxy daemon is the other process, its
 * `enforceInterface` rejects this token, and the transaction then silently does nothing.
 *
 * Kotlin port note: the Parcel WRITE ORDER in every method below is a wire contract with the
 * daemon. FissionClientOwnerWireTest decodes the parcel and asserts the exact field sequence for
 * attachSlot and resizeSlot, so a reordering fails locally rather than in a car.
 */
object FissionClient {

    private const val TAG = "FissionClient"

    // ── ServiceManager helper ─────────────────────────────────────────────────

    /**
     * The surface daemon's binder, or `null` if it is not registered (daemon not up).
     *
     * Thin alias for [com.byd.dashcast.proxy.DaemonBinderResolver.surfaceDaemonBinder], kept
     * because the whole Layout subsystem calls it by this name; it performs the identical
     * `ServiceManager.getService("byd_mirror_daemon")` lookup and returns the identical binder.
     * Delegating rather than duplicating the reflection keeps the service name in exactly one
     * place. Safe from any thread (a local ServiceManager lookup, no IPC to the daemon).
     */
    @JvmStatic
    fun getBinderFromServiceManager(): IBinder? = DaemonBinderResolver.surfaceDaemonBinder()

    // ── Slot management ───────────────────────────────────────────────────────

    /**
     * Creates an overlay + TRUSTED VirtualDisplay for `pkg` at the given cluster rect.
     * Returns the new VD displayId, or -1 on failure.
     */
    @JvmStatic
    @Suppress("DEPRECATION")
    @Throws(Exception::class)
    fun attachSlot(binder: IBinder, pkg: String?, x: Int, y: Int, w: Int, h: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            data.writeString(pkg)
            data.writeInt(x); data.writeInt(y)
            data.writeInt(w); data.writeInt(h)
            // Additive tail field: old daemons ignore it; current daemons tie the slot lifetime
            // to this app process so a crash cannot orphan an overlay and VirtualDisplay.
            data.writeStrongBinder(FissionResourceOwner.token())
            binder.transact(SurfaceDaemon.TRANSACT_ATTACH_SLOT, data, reply, 0)
            reply.readException()
            if (reply.readInt() != 1) return -1
            val surface: Surface? = reply.readParcelable(Surface::class.java.classLoader)
            // Wire-compatible legacy field: the daemon owns the SurfaceView/VD. This client-side
            // Parcel wrapper is unused and must release its native reference immediately.
            surface?.release()
            val displayId = reply.readInt()
            AppLogger.d(TAG, "ATTACH_SLOT pkg=" + pkg + " → displayId=" + displayId)
            return displayId
        } finally { data.recycle(); reply.recycle() }
    }

    /** Move `pkg`'s task back to display 0 before teardown so the app relaunches cleanly. */
    @JvmStatic
    fun moveToDisplay0(binder: IBinder, pkg: String?): String? {
        val guardianCancelled = ProxyClient.cancelFissionWatchdog(pkg)
        AppLogger.d(TAG, "MOVE_TO_DISPLAY0 watchdog cancelled=" + guardianCancelled
                + " pkg=" + pkg)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            data.writeString(pkg)
            binder.transact(SurfaceDaemon.TRANSACT_MOVE_TO_DISPLAY0, data, reply, 0)
            reply.readException()
            val result = reply.readString()
            AppLogger.d(TAG, "MOVE_TO_DISPLAY0 pkg=" + pkg + " result=" + result)
            return result
        } catch (e: Throwable) {
            AppLogger.w(TAG, "MOVE_TO_DISPLAY0 pkg=" + pkg + " error: " + e.message)
            return "ERR transact: " + e.javaClass.simpleName + ": " + e.message
        } finally { data.recycle(); reply.recycle() }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun releaseSlot(binder: IBinder, pkg: String?) {
        val guardianCancelled = ProxyClient.cancelFissionWatchdog(pkg)
        AppLogger.d(TAG, "RELEASE_SLOT watchdog cancelled=" + guardianCancelled + " pkg=" + pkg)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            data.writeString(pkg)
            binder.transact(SurfaceDaemon.TRANSACT_RELEASE_SLOT, data, reply, 0)
            reply.readException()
            AppLogger.d(TAG, "RELEASE_SLOT pkg=" + pkg + " ok=" + reply.readInt())
        } finally { data.recycle(); reply.recycle() }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun resizeSlot(binder: IBinder, pkg: String?, x: Int, y: Int, w: Int, h: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            data.writeString(pkg)
            data.writeInt(x); data.writeInt(y)
            data.writeInt(w); data.writeInt(h)
            if (!binder.transact(SurfaceDaemon.TRANSACT_RESIZE_SLOT, data, reply, 0)) {
                throw IllegalStateException("RESIZE_SLOT transaction not handled")
            }
            reply.readException()
            val resized = reply.readInt() != 0
            AppLogger.d(TAG, "RESIZE_SLOT pkg=" + pkg + " ok=" + resized)
            return resized
        } finally { data.recycle(); reply.recycle() }
    }

    // ── Layout management ─────────────────────────────────────────────────────

    // NOTE — there is deliberately no batch "activateLayout" call here any more.
    // TRANSACT_ACTIVATE_LAYOUT keyed its slots "layout_<label>_<i>" and never put the package
    // name on the wire, so nothing it created could be found again by QUERY_SLOT, RELEASE_SLOT
    // or RESIZE_SLOT — all three look slots up by package. A layout is now activated with one
    // [attachSlot] per zone (see FissionOrchestrator#activateLayoutManually).
    //
    // Addressability is the WHOLE reason, and the field record is not an argument either way:
    // the batch path has been observed working (18 "OK surface valid pkg=layout_Zone_*" lines
    // across INC-20260614-131051 / -131118, both DiLink 3) and failing (INC-20260615-160735,
    // INC-20260622-080346, both DiLink 5.0) — exactly like the per-slot path. The platform is
    // the differentiator, not the verb. Do not cite "the batch path never worked".
    //
    // The daemon still answers the batch verb; no client sends it.

    /**
     * Releases every slot the surface daemon holds and cancels the layout watchdogs.
     * Used by the Layout Manager's "free mode".
     */
    @JvmStatic
    @Throws(Exception::class)
    fun deactivateLayout(binder: IBinder) {
        val guardiansCancelled = ProxyClient.cancelAllFissionWatchdogs()
        AppLogger.d(TAG, "DEACTIVATE_LAYOUT watchdogs cancelled=" + guardiansCancelled)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            binder.transact(SurfaceDaemon.TRANSACT_DEACTIVATE_LAYOUT, data, reply, 0)
            reply.readException()
            AppLogger.d(TAG, "DEACTIVATE_LAYOUT ok")
        } finally { data.recycle(); reply.recycle() }
    }

    // ── Mirror ────────────────────────────────────────────────────────────────

    /**
     * Starts the SurfaceControl mirror of the cluster display into `surface`.
     * layerStack == displayId on API 29. svW/svH are the SurfaceView dimensions.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun startMirror(binder: IBinder,
                    layerStack: Int, contentW: Int, contentH: Int,
                    clusterDisplayId: Int, svW: Int, svH: Int,
                    surface: Surface?): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            data.writeInt(layerStack)
            data.writeInt(contentW)
            data.writeInt(contentH)
            data.writeInt(clusterDisplayId)
            data.writeInt(svW)
            data.writeInt(svH)
            data.writeParcelable(surface, 0)
            data.writeStrongBinder(MirrorResourceOwner.token())
            binder.transact(SurfaceDaemon.TRANSACT_MIRROR_START, data, reply, 0)
            reply.readException()
            val ok = reply.readInt() == 1
            AppLogger.d(TAG, "MIRROR_START ok=" + ok)
            return ok
        } finally { data.recycle(); reply.recycle() }
    }

    /**
     * Queries the displayId of an existing slot for `pkg`.
     * Returns the live displayId if the daemon still holds the slot, -1 otherwise.
     * Fast O(1) call — no shell, no polling.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun querySlot(binder: IBinder, pkg: String?): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            data.writeString(pkg)
            binder.transact(SurfaceDaemon.TRANSACT_QUERY_SLOT, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally { data.recycle(); reply.recycle() }
    }

    @JvmStatic
    fun stopMirror(binder: IBinder?): Boolean {
        if (binder == null || !binder.isBinderAlive) return false
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            val accepted = binder.transact(SurfaceDaemon.TRANSACT_MIRROR_STOP, data, null, 0)
            if (!accepted) {
                AppLogger.w(TAG, "MIRROR_STOP rejected by surface daemon")
                return false
            }
            AppLogger.d(TAG, "MIRROR_STOP sent")
            return true
        } catch (e: Exception) {
            AppLogger.w(TAG, "MIRROR_STOP error: " + e.message)
            return false
        } finally { data.recycle() }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun injectMotion(binder: IBinder?, event: MotionEvent?) {
        if (binder == null || event == null) return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            data.writeParcelable(event, 0)
            binder.transact(SurfaceDaemon.TRANSACT_INJECT_MOTION, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun focusSlot(binder: IBinder, pkg: String?): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            data.writeString(pkg)
            binder.transact(SurfaceDaemon.TRANSACT_FOCUS_SLOT, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
