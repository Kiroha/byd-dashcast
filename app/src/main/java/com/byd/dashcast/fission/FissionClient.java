package com.byd.dashcast.fission;

import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.proxy.daemon.MirrorDaemon;

public class FissionClient {

    private static final String TAG = "FissionClient";

    private static final String DAEMON_SERVICE = "byd_mirror_daemon";

    // ── ServiceManager helper ─────────────────────────────────────────────────

    public static IBinder getBinderFromServiceManager() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method get = sm.getDeclaredMethod("getService", String.class);
            get.setAccessible(true);
            return (IBinder) get.invoke(null, DAEMON_SERVICE);
        } catch (Exception e) {
            AppLogger.w(TAG, "getBinderFromServiceManager: " + e.getMessage());
            return null;
        }
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
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            data.writeString(pkg);
            data.writeInt(x); data.writeInt(y);
            data.writeInt(w); data.writeInt(h);
            binder.transact(MirrorDaemon.TRANSACT_ATTACH_SLOT, data, reply, 0);
            reply.readException();
            if (reply.readInt() != 1) return -1;
            reply.readParcelable(Surface.class.getClassLoader()); // daemon-owned surface, advance parcel
            int displayId = reply.readInt();
            AppLogger.d(TAG, "ATTACH_SLOT pkg=" + pkg + " → displayId=" + displayId);
            return displayId;
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Move {@code pkg}'s task back to display 0 before teardown so the app relaunches cleanly. */
    public static void moveToDisplay0(IBinder binder, String pkg) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            data.writeString(pkg);
            binder.transact(MirrorDaemon.TRANSACT_MOVE_TO_DISPLAY0, data, reply, 0);
            reply.readException();
            AppLogger.d(TAG, "MOVE_TO_DISPLAY0 pkg=" + pkg + " result=" + reply.readString());
        } catch (Throwable e) {
            AppLogger.w(TAG, "MOVE_TO_DISPLAY0 pkg=" + pkg + " error: " + e.getMessage());
        } finally { data.recycle(); reply.recycle(); }
    }

    public static void releaseSlot(IBinder binder, String pkg) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            data.writeString(pkg);
            binder.transact(MirrorDaemon.TRANSACT_RELEASE_SLOT, data, reply, 0);
            reply.readException();
            AppLogger.d(TAG, "RELEASE_SLOT pkg=" + pkg + " ok=" + reply.readInt());
        } finally { data.recycle(); reply.recycle(); }
    }

    public static void resizeSlot(IBinder binder, String pkg,
                                  int x, int y, int w, int h) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            data.writeString(pkg);
            data.writeInt(x); data.writeInt(y);
            data.writeInt(w); data.writeInt(h);
            binder.transact(MirrorDaemon.TRANSACT_RESIZE_SLOT, data, reply, 0);
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
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            data.writeInt(preset.slots.size());
            for (LayoutPreset.SlotDef s : preset.slots) {
                data.writeString(s.label);
                data.writeInt(s.x); data.writeInt(s.y);
                data.writeInt(s.w); data.writeInt(s.h);
            }
            binder.transact(MirrorDaemon.TRANSACT_ACTIVATE_LAYOUT, data, reply, 0);
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
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            binder.transact(MirrorDaemon.TRANSACT_DEACTIVATE_LAYOUT, data, reply, 0);
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
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            data.writeInt(layerStack);
            data.writeInt(contentW);
            data.writeInt(contentH);
            data.writeInt(clusterDisplayId);
            data.writeInt(svW);
            data.writeInt(svH);
            data.writeParcelable(surface, 0);
            binder.transact(MirrorDaemon.TRANSACT_MIRROR_START, data, reply, 0);
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
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            data.writeString(pkg);
            binder.transact(MirrorDaemon.TRANSACT_QUERY_SLOT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally { data.recycle(); reply.recycle(); }
    }

    public static void stopMirror(IBinder binder) {
        if (binder == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            binder.transact(MirrorDaemon.TRANSACT_MIRROR_STOP, data, null, 0);
            AppLogger.d(TAG, "MIRROR_STOP sent");
        } catch (Exception e) {
            AppLogger.w(TAG, "MIRROR_STOP error: " + e.getMessage());
        } finally { data.recycle(); }
    }
}
