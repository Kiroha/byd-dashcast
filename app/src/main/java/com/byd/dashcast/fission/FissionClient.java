package com.byd.dashcast.fission;

import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;
import android.view.MotionEvent;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.proxy.DaemonBinderResolver;
import com.byd.dashcast.proxy.MirrorResourceOwner;
import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.proxy.daemon.SurfaceDaemon;

/**
 * Wire layer of the Layout / Fission subsystem: raw {@code Parcel} transactions on the
 * <b>SURFACE</b> daemon ({@link com.byd.dashcast.proxy.daemon.SurfaceDaemon}, ServiceManager name
 * {@code byd_mirror_daemon}) — slot attach/resize/release, layout activation, mirror start/stop and
 * touch injection. Despite the name, "Fission" here is only the feature; the counterpart process is
 * the surface daemon.
 *
 * <p><b>Boundary rule.</b> DashCast runs two uid-2000 daemons and every method below writes
 * {@link com.byd.dashcast.proxy.daemon.SurfaceDaemon#DESCRIPTOR} onto the caller-supplied
 * {@code binder}. That binder MUST come from {@link #getBinderFromServiceManager()} (or the
 * equivalent {@link com.byd.dashcast.proxy.DaemonBinderResolver#surfaceDaemonBinder()}) and NEVER
 * from {@code ProxyClient.getProxyDaemonBinder()} — the proxy daemon is the other process, its
 * {@code enforceInterface} rejects this token, and the transaction then silently does nothing.
 * See the boundary documented on {@link com.byd.dashcast.proxy.daemon.SurfaceDaemon} and
 * {@link com.byd.dashcast.proxy.daemon.ProxyDaemonMain}.
 */
public class FissionClient {

    private static final String TAG = "FissionClient";

    // ── ServiceManager helper ─────────────────────────────────────────────────

    /**
     * The surface daemon's binder, or {@code null} if it is not registered (daemon not up).
     *
     * <p>Thin alias for {@link com.byd.dashcast.proxy.DaemonBinderResolver#surfaceDaemonBinder()},
     * kept because the whole Layout subsystem calls it by this name; it performs the identical
     * {@code ServiceManager.getService("byd_mirror_daemon")} lookup and returns the identical
     * binder. Delegating rather than duplicating the reflection keeps the service name in exactly
     * one place. Safe from any thread (a local ServiceManager lookup, no IPC to the daemon).
     */
    public static IBinder getBinderFromServiceManager() {
        return DaemonBinderResolver.surfaceDaemonBinder();
    }

    // ── Slot management ───────────────────────────────────────────────────────

    /**
     * Creates an overlay + TRUSTED VirtualDisplay for {@code pkg} at the given
     * cluster rect. Returns the new VD displayId, or -1 on failure.
     */
    public static int attachSlot(IBinder binder, String pkg,
                                 int x, int y, int w, int h) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            data.writeString(pkg);
            data.writeInt(x); data.writeInt(y);
            data.writeInt(w); data.writeInt(h);
            binder.transact(SurfaceDaemon.TRANSACT_ATTACH_SLOT, data, reply, 0);
            reply.readException();
            if (reply.readInt() != 1) return -1;
            Surface surface = reply.readParcelable(Surface.class.getClassLoader());
            // Wire-compatible legacy field: the daemon owns the SurfaceView/VD. This client-side
            // Parcel wrapper is unused and must release its native reference immediately.
            if (surface != null) surface.release();
            int displayId = reply.readInt();
            AppLogger.d(TAG, "ATTACH_SLOT pkg=" + pkg + " → displayId=" + displayId);
            return displayId;
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Move {@code pkg}'s task back to display 0 before teardown so the app relaunches cleanly. */
    public static String moveToDisplay0(IBinder binder, String pkg) {
        boolean guardianCancelled = ProxyClient.cancelFissionWatchdog(pkg);
        AppLogger.d(TAG, "MOVE_TO_DISPLAY0 watchdog cancelled=" + guardianCancelled
            + " pkg=" + pkg);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            data.writeString(pkg);
            binder.transact(SurfaceDaemon.TRANSACT_MOVE_TO_DISPLAY0, data, reply, 0);
            reply.readException();
            String result = reply.readString();
            AppLogger.d(TAG, "MOVE_TO_DISPLAY0 pkg=" + pkg + " result=" + result);
            return result;
        } catch (Throwable e) {
            AppLogger.w(TAG, "MOVE_TO_DISPLAY0 pkg=" + pkg + " error: " + e.getMessage());
            return "ERR transact: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally { data.recycle(); reply.recycle(); }
    }

    public static void releaseSlot(IBinder binder, String pkg) throws Exception {
        boolean guardianCancelled = ProxyClient.cancelFissionWatchdog(pkg);
        AppLogger.d(TAG, "RELEASE_SLOT watchdog cancelled=" + guardianCancelled
            + " pkg=" + pkg);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            data.writeString(pkg);
            binder.transact(SurfaceDaemon.TRANSACT_RELEASE_SLOT, data, reply, 0);
            reply.readException();
            AppLogger.d(TAG, "RELEASE_SLOT pkg=" + pkg + " ok=" + reply.readInt());
        } finally { data.recycle(); reply.recycle(); }
    }

    public static void resizeSlot(IBinder binder, String pkg,
                                  int x, int y, int w, int h) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            data.writeString(pkg);
            data.writeInt(x); data.writeInt(y);
            data.writeInt(w); data.writeInt(h);
            binder.transact(SurfaceDaemon.TRANSACT_RESIZE_SLOT, data, reply, 0);
            reply.readException();
            AppLogger.d(TAG, "RESIZE_SLOT pkg=" + pkg + " ok=" + reply.readInt());
        } finally { data.recycle(); reply.recycle(); }
    }

    // ── Layout management ─────────────────────────────────────────────────────

    /**
     * Pre-creates all overlay+VD slots for a layout in one batch.
     * Fills {@code preset.slots[i].displayId} with the returned VD ids.
     * Returns true if all slots succeeded.
     */
    public static boolean activateLayout(IBinder binder, LayoutPreset preset) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            data.writeInt(preset.slots.size());
            for (LayoutPreset.SlotDef s : preset.slots) {
                data.writeString(s.label);
                data.writeInt(s.x); data.writeInt(s.y);
                data.writeInt(s.w); data.writeInt(s.h);
            }
            binder.transact(SurfaceDaemon.TRANSACT_ACTIVATE_LAYOUT, data, reply, 0);
            reply.readException();
            int n = reply.readInt();
            boolean allOk = (n > 0);
            for (int i = 0; i < n; i++) {
                int did = (reply.dataAvail() > 0) ? reply.readInt() : -1;
                if (i < preset.slots.size()) {
                    preset.slots.get(i).displayId = did;
                    if (did < 0) allOk = false;
                }
            }
            AppLogger.d(TAG, "ACTIVATE_LAYOUT n=" + n + " allOk=" + allOk);
            return allOk;
        } finally { data.recycle(); reply.recycle(); }
    }

    public static void deactivateLayout(IBinder binder) throws Exception {
        boolean guardiansCancelled = ProxyClient.cancelAllFissionWatchdogs();
        AppLogger.d(TAG, "DEACTIVATE_LAYOUT watchdogs cancelled=" + guardiansCancelled);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            binder.transact(SurfaceDaemon.TRANSACT_DEACTIVATE_LAYOUT, data, reply, 0);
            reply.readException();
            AppLogger.d(TAG, "DEACTIVATE_LAYOUT ok");
        } finally { data.recycle(); reply.recycle(); }
    }

    // ── Mirror ────────────────────────────────────────────────────────────────

    /**
     * Starts the SurfaceControl mirror of the cluster display into {@code surface}.
     * layerStack == displayId on API 29. svW/svH are the SurfaceView dimensions.
     */
    public static boolean startMirror(IBinder binder,
                                      int layerStack, int contentW, int contentH,
                                      int clusterDisplayId, int svW, int svH,
                                      Surface surface) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            data.writeInt(layerStack);
            data.writeInt(contentW);
            data.writeInt(contentH);
            data.writeInt(clusterDisplayId);
            data.writeInt(svW);
            data.writeInt(svH);
            data.writeParcelable(surface, 0);
            data.writeStrongBinder(MirrorResourceOwner.token());
            binder.transact(SurfaceDaemon.TRANSACT_MIRROR_START, data, reply, 0);
            reply.readException();
            boolean ok = (reply.readInt() == 1);
            AppLogger.d(TAG, "MIRROR_START ok=" + ok);
            return ok;
        } finally { data.recycle(); reply.recycle(); }
    }

    /**
     * Queries the displayId of an existing slot for {@code pkg}.
     * Returns the live displayId if the daemon still holds the slot, -1 otherwise.
     * Fast O(1) call — no shell, no polling.
     */
    public static int querySlot(IBinder binder, String pkg) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            data.writeString(pkg);
            binder.transact(SurfaceDaemon.TRANSACT_QUERY_SLOT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally { data.recycle(); reply.recycle(); }
    }

    public static void stopMirror(IBinder binder) {
        if (binder == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            binder.transact(SurfaceDaemon.TRANSACT_MIRROR_STOP, data, null, 0);
            AppLogger.d(TAG, "MIRROR_STOP sent");
        } catch (Exception e) {
            AppLogger.w(TAG, "MIRROR_STOP error: " + e.getMessage());
        } finally { data.recycle(); }
    }

    public static void injectMotion(IBinder binder, MotionEvent event) throws Exception {
        if (binder == null || event == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            data.writeParcelable(event, 0);
            binder.transact(SurfaceDaemon.TRANSACT_INJECT_MOTION,
                    data, null, IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    public static String focusSlot(IBinder binder, String pkg) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR);
            data.writeString(pkg);
            binder.transact(SurfaceDaemon.TRANSACT_FOCUS_SLOT, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
