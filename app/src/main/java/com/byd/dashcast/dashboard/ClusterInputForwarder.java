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
    private long mTouchDownTime = 0L;

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
     * Forwards already-mapped cluster coordinates for N pointers (multi-touch).
     * This enables pinch gestures (ACTION_POINTER_DOWN/MOVE/POINTER_UP).
     */
    public void forwardTouchFinalMulti(int[] pointerIds,
                                       float[] clusterXs,
                                       float[] clusterYs,
                                       int actionMasked,
                                       int actionIndex,
                                       int pointerCount) {
        if (pointerIds == null || clusterXs == null || clusterYs == null) return;
        if (pointerCount <= 0) return;
        if (pointerCount > pointerIds.length
                || pointerCount > clusterXs.length
                || pointerCount > clusterYs.length) {
            return;
        }
        injectTouchAtMulti(pointerIds, clusterXs, clusterYs, actionMasked, actionIndex, pointerCount);
    }

    /** Internal: build and inject a multi-pointer MotionEvent at cluster coordinates. */
    private void injectTouchAtMulti(final int[] pointerIds,
                                    final float[] clusterXs,
                                    final float[] clusterYs,
                                    final int actionMasked,
                                    final int actionIndex,
                                    final int pointerCount) {
        if (actionMasked == MotionEvent.ACTION_DOWN || mTouchDownTime == 0L) {
            mTouchDownTime = SystemClock.uptimeMillis();
        }
        long now = SystemClock.uptimeMillis();
        int safeActionIndex = Math.max(0, Math.min(actionIndex, pointerCount - 1));
        int action;
        if (actionMasked == MotionEvent.ACTION_POINTER_DOWN
                || actionMasked == MotionEvent.ACTION_POINTER_UP) {
            action = actionMasked | (safeActionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        } else {
            action = actionMasked;
        }

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = pointerIds[i];
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;

            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = clusterXs[i];
            coords[i].y = clusterYs[i];
            coords[i].pressure = 1.0f;
            coords[i].size = 1.0f;
        }

        // Preferred path: daemon uid=2000 (INJECT_EVENTS guaranteed)
        if (mDaemonBinder != null) {
            try {
                MotionEvent ev = MotionEvent.obtain(
                        mTouchDownTime, now, action, pointerCount, props, coords,
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
            MotionEvent ev = MotionEvent.obtain(
                    mTouchDownTime, now, action, pointerCount, props, coords,
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
            AppLogger.e(TAG, "injectTouchAtMulti failed action=" + actionMasked
                    + " ptrs=" + pointerCount + " disp=" + mClusterDisplayId, e);
        }

        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            mTouchDownTime = 0L;
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
