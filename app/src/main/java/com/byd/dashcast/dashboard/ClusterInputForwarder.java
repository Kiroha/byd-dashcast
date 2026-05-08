package com.byd.dashcast.dashboard;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.Display;
import com.byd.dashcast.AppLogger;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Injects touch and key events to the app running on the (non-touch) cluster display.
 *
 * How it works:
 *  - The user touches the "touchpad" area on the main 15.6" screen.
 *  - Coordinates are mapped to the cluster dimensions (e.g. 1920×1080).
 *  - A MotionEvent is injected via InputManager.injectInputEvent() (@hide API,
 *    accessed by reflection, requires android.permission.INJECT_EVENTS).
 *  - KeyEvents (Back/Home/Volume) are injected directly; they route to the
 *    globally focused window, including on the secondary display.
 *
 * Note: MotionEvent routing to a secondary display depends on ROM implementation.
 * On BYD DiLink 3.0 (Android 10), events injected with setDisplayId() reach
 * windows on that display if the InputDispatcher supports multi-display.
 * KeyEvents always work because they target the globally focused window.
 */
public class ClusterInputForwarder {

    private static final String TAG = "ClusterInputForwarder";
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;

    private int mClusterWidth      = 1920;
    private int mClusterHeight     = 720;  // Confirmed: cluster VirtualDisplay is 1920×720 (dumpsys window 03/05/2026)
    private int mClusterDisplayId  = 1;   // Cluster display ID (routing for API 29)

    /** MirrorDaemon Binder — if non-null, events are routed through uid=2000. */
    private IBinder mDaemonBinder = null;

    private Object mInputManager;
    private Method mInjectMethod;
    private Method mSetDisplayIdMethod = null; // cached to avoid reflection on every event
    private boolean mAvailable = false;

    public ClusterInputForwarder(Context context) {
        try {
            // InputManager.getInstance() is a @hide method since API 16
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = imClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            mInputManager = getInstance.invoke(null);

            // injectInputEvent(InputEvent, int) is @hide but accessible via reflection
            // Requires android.permission.INJECT_EVENTS (signature permission)
            mInjectMethod = imClass.getDeclaredMethod("injectInputEvent",
                    android.view.InputEvent.class, int.class);
            mInjectMethod.setAccessible(true);

            // Cache setDisplayId to avoid reflection on every touch event
            try {
                mSetDisplayIdMethod = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                mSetDisplayIdMethod.setAccessible(true);
            } catch (Exception ignored) {
                // @hide API not available on this ROM — injection without displayId
            }

            mAvailable = true;
            AppLogger.i(TAG, "InputManager injection: available");
        } catch (Exception e) {
            AppLogger.e(TAG, "Init failed (INJECT_EVENTS permission missing?)", e);
        }
    }

    /** Called when the cluster display is detected, to get its dimensions and ID. */
    public void setClusterDisplay(Display display) {
        if (display == null) return;
        android.graphics.Point size = new android.graphics.Point();
        display.getSize(size);
        mClusterWidth     = size.x;
        mClusterHeight    = size.y;
        mClusterDisplayId = display.getDisplayId();
        AppLogger.i(TAG, "Cluster dimensions: " + mClusterWidth + "x" + mClusterHeight
                + " displayId=" + mClusterDisplayId);
    }

    /** Updates the cluster display ID (used when Display is null but ID is known). */
    public void setClusterDisplayId(int displayId) {
        mClusterDisplayId = displayId;
    }

    /**
     * Passes the MirrorDaemon Binder to this forwarder.
     * When non-null, forwardTouch() and injectKey() are routed through the daemon (uid=2000)
     * which holds android.permission.INJECT_EVENTS.
     */
    public void setDaemonBinder(IBinder binder) {
        mDaemonBinder = binder;
        AppLogger.i(TAG, "Daemon Binder connected — touch/key injection via uid=2000");
    }

    /**
     * Forwards pre-mapped cluster coordinates directly, without any re-normalization.
     * Use this when the caller has already computed exact cluster-space coordinates
     * from the stored projection parameters (avoids double-normalization bugs).
     *
     * @param clusterX  Final X coordinate in cluster display space (0..clusterW-1)
     * @param clusterY  Final Y coordinate in cluster display space (0..clusterH-1)
     * @param action    MotionEvent.ACTION_DOWN / ACTION_MOVE / ACTION_UP
     */
    public void forwardTouchFinal(float clusterX, float clusterY, final int action) {
        injectTouchAt(clusterX, clusterY, action);
    }

    /** Internal: build and inject a MotionEvent at the given cluster coordinates. */
    private void injectTouchAt(final float clusterX, final float clusterY, final int action) {
        // Preferred path: daemon uid=2000 (INJECT_EVENTS guaranteed)
        if (mDaemonBinder != null) {
            try {
                long now = android.os.SystemClock.uptimeMillis();
                MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
                props[0] = new MotionEvent.PointerProperties();
                props[0].id = 0;
                props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;

                MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
                coords[0] = new MotionEvent.PointerCoords();
                coords[0].x = clusterX;
                coords[0].y = clusterY;
                coords[0].pressure = 1.0f;
                coords[0].size = 1.0f;

                MotionEvent ev = MotionEvent.obtain(
                        now, now, action, 1, props, coords,
                        0, 0, 1.0f, 1.0f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken(com.byd.dashcast.daemon.MirrorDaemon.DESCRIPTOR);
                data.writeParcelable(ev, 0);
                mDaemonBinder.transact(com.byd.dashcast.daemon.MirrorDaemon.TRANSACT_INJECT_MOTION,
                        data, null, android.os.IBinder.FLAG_ONEWAY);
                data.recycle();
                ev.recycle();
            } catch (Exception e) {
                AppLogger.e(TAG, "injectTouchAt via daemon failed", e);
            }
            return;
        }

        if (!mAvailable) return;

        try {
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
            props[0] = new MotionEvent.PointerProperties();
            props[0].id = 0;
            props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;

            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
            coords[0] = new MotionEvent.PointerCoords();
            coords[0].x = clusterX;
            coords[0].y = clusterY;
            coords[0].pressure = 1.0f;
            coords[0].size = 1.0f;

            long now = SystemClock.uptimeMillis();
            MotionEvent ev = MotionEvent.obtain(
                    now, now, action, 1, props, coords,
                    0, 0, 1.0f, 1.0f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            // setDisplayId is a @hide API — using the Method cached in the constructor
            if (mSetDisplayIdMethod != null) {
                try {
                    mSetDisplayIdMethod.invoke(ev, mClusterDisplayId);
                } catch (Exception e) {
                    AppLogger.d(TAG, "setDisplayId via reflection failed: " + e.getMessage());
                }
            }
            mInjectMethod.invoke(mInputManager, ev, INJECT_INPUT_EVENT_MODE_ASYNC);
            ev.recycle();
        } catch (Exception e) {
            AppLogger.e(TAG, "injectTouchAt inject failed x=" + (int)clusterX
                    + " y=" + (int)clusterY + " disp=" + mClusterDisplayId, e);
        }
    }

    /**
     * Injects a DOWN+UP pair for an Android keyCode.
     * E.g.: KeyEvent.KEYCODE_BACK, KEYCODE_HOME, KEYCODE_VOLUME_UP, KEYCODE_DPAD_UP…
     * KeyEvents route to the focused window (including on the cluster display).
     */
    public void injectKey(int keyCode) {
        // Preferred path: daemon uid=2000
        if (mDaemonBinder != null) {
            try {
                long now = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now,     KeyEvent.ACTION_DOWN, keyCode, 0);
                KeyEvent up   = new KeyEvent(now, now + 1, KeyEvent.ACTION_UP,   keyCode, 0);
                for (KeyEvent kev : new KeyEvent[]{down, up}) {
                    Parcel data = Parcel.obtain();
                    data.writeInterfaceToken(com.byd.dashcast.daemon.MirrorDaemon.DESCRIPTOR);
                    data.writeParcelable(kev, 0);
                    mDaemonBinder.transact(com.byd.dashcast.daemon.MirrorDaemon.TRANSACT_INJECT_KEY,
                            data, null, android.os.IBinder.FLAG_ONEWAY);
                    data.recycle();
                }
            } catch (Exception e) {
                AppLogger.e(TAG, "injectKey via daemon failed", e);
            }
            return;
        }
        if (!mAvailable) return;
        long now = SystemClock.uptimeMillis();
        try {
            KeyEvent down = new KeyEvent(now, now,     KeyEvent.ACTION_DOWN, keyCode, 0);
            KeyEvent up   = new KeyEvent(now, now + 1, KeyEvent.ACTION_UP,   keyCode, 0);
            mInjectMethod.invoke(mInputManager, down, INJECT_INPUT_EVENT_MODE_ASYNC);
            mInjectMethod.invoke(mInputManager, up,   INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Exception e) {
            AppLogger.e(TAG, "Key inject failed", e);
        }
    }

    public boolean isAvailable() {
        return mAvailable;
    }

    public int getClusterWidth()  { return mClusterWidth; }
    public int getClusterHeight() { return mClusterHeight; }
}
