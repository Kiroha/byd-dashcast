package com.byd.myapp.dashboard;

import android.content.Context;
import android.os.SystemClock;
import android.view.Display;
import com.byd.myapp.AppLogger;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.lang.reflect.Method;

/**
 * Injecte des événements tactiles et claviers à destination de l'app lancée
 * sur le display cluster (non tactile).
 *
 * Principe :
 *  - L'utilisateur touche le "touchpad" sur l'écran principal.
 *  - Les coordonnées sont mappées aux dimensions du cluster (ex. 480×240).
 *  - Un MotionEvent est injecté via InputManager.injectInputEvent() (API cachée,
 *    accessible par réflexion, requiert android.permission.INJECT_EVENTS).
 *  - Les KeyEvents Back/Home/Volume sont injectés directement ; ils se routent
 *    vers la fenêtre focalisée, y compris sur le display secondaire.
 *
 * Note : le routage des MotionEvents vers un display secondaire dépend de
 * l'implémentation ROM. Sur Android 7.x BYD, les events injectés aux
 * coordonnées du cluster peuvent atteindre les fenêtres de ce display si
 * l'InputDispatcher BYD supporte le multi-display. Les KeyEvents fonctionnent
 * dans tous les cas car ils ciblent la fenêtre focalisée globalement.
 */
public class ClusterInputForwarder {

    private static final String TAG = "ClusterInputForwarder";
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    private ExecutorService mUdpExecutor = Executors.newSingleThreadExecutor();
    private DatagramSocket mUdpSocket;

    private int mClusterWidth      = 1920;
    private int mClusterHeight     = 1080;
    private int mClusterDisplayId  = 1;   // ID du display cluster (routage API 29)

    private Object mInputManager;
    private Method mInjectMethod;
    private boolean mAvailable = false;

    // Object buffer and pre-resolved address for high-performance UDP transmission to avoid GC churn
    private InetAddress mLoopback;
    private final ByteBuffer mTouchBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    private final byte[] mTouchBytes = new byte[16];

    public ClusterInputForwarder(Context context) {
        try {
            mLoopback = InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {}
        try {
            // InputManager.getInstance() est une méthode cachée depuis API 16
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = imClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            mInputManager = getInstance.invoke(null);

            // injectInputEvent(InputEvent, int) est cachée mais accessible via réflexion
            // Requiert android.permission.INJECT_EVENTS (signature permission)
            mInjectMethod = imClass.getDeclaredMethod("injectInputEvent",
                    android.view.InputEvent.class, int.class);
            mInjectMethod.setAccessible(true);
            mUdpSocket = new DatagramSocket();
            // Pas de bind sur 5005 ici, on est client

            mAvailable = true;
            AppLogger.i(TAG, "InputManager injection: disponible");
        } catch (Exception e) {
            AppLogger.e(TAG, "Init échouée (permission INJECT_EVENTS absente ?)", e);
        }
    }

    /** Appelé quand le display cluster est détecté, pour connaître ses dimensions et son ID. */
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

    /** Met à jour l'ID du display cluster (utilisé quand Display est null mais l'ID est connu). */
    public void setClusterDisplayId(int displayId) {
        mClusterDisplayId = displayId;
    }

    /**
     * Transfère un événement tactile depuis le touchpad vers le cluster.
     *
     * @param padX   X de l'événement dans la vue touchpad
     * @param padY   Y de l'événement dans la vue touchpad
     * @param padW   Largeur de la vue touchpad
     * @param padH   Hauteur de la vue touchpad
     * @param action MotionEvent.ACTION_DOWN / ACTION_MOVE / ACTION_UP
     */
    /**
     * Transfère un événement tactile vers le cluster via InputManager.injectInputEvent
     * avec setDisplayId — identique à ce que fait WindowManagement.
     *
     * @param padX / padY  Coordonnées déjà mappées en espace cluster (pas en espace vue)
     * @param padW / padH  Dimensions référence (= mClusterWidth/Height si déjà mappées)
     * @param action       MotionEvent.ACTION_DOWN / ACTION_MOVE / ACTION_UP
     */
    public void forwardTouch(float padX, float padY, float padW, float padH, final int action) {
        if (!mAvailable) return;

        // Mapping proportionnel (no-op si padW==mClusterWidth, déjà mappé par l'appelant)
        final float clusterX = (padX / padW) * mClusterWidth;
        final float clusterY = (padY / padH) * mClusterHeight;
        final int displayId  = mClusterDisplayId;

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
            // setDisplayId est une API @hide — appel par reflection (unlockHiddenApis() déjà fait)
            try {
                Method setDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                setDisplayId.setAccessible(true);
                setDisplayId.invoke(ev, displayId);
            } catch (Exception ignored) {
                // Sur certaines ROM la méthode n'existe pas — injection sans display ID
            }
            mInjectMethod.invoke(mInputManager, ev, INJECT_INPUT_EVENT_MODE_ASYNC);
            ev.recycle();
        } catch (Exception e) {
            AppLogger.e(TAG, "forwardTouch inject échoué x=" + (int)clusterX
                    + " y=" + (int)clusterY + " disp=" + displayId, e);
        }
    }

    /**
     * Injecte une paire DOWN+UP pour un keyCode Android.
     * Ex : KeyEvent.KEYCODE_BACK, KEYCODE_HOME, KEYCODE_VOLUME_UP, KEYCODE_DPAD_UP…
     * Les KeyEvents se routent vers la fenêtre focalisée (y compris sur le cluster).
     */
    public void injectKey(int keyCode) {
        if (!mAvailable) return;
        long now = SystemClock.uptimeMillis();
        try {
            KeyEvent down = new KeyEvent(now, now,     KeyEvent.ACTION_DOWN, keyCode, 0);
            KeyEvent up   = new KeyEvent(now, now + 1, KeyEvent.ACTION_UP,   keyCode, 0);
            mInjectMethod.invoke(mInputManager, down, INJECT_INPUT_EVENT_MODE_ASYNC);
            mInjectMethod.invoke(mInputManager, up,   INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Exception e) {
            AppLogger.e(TAG, "Key inject échoué", e);
        }
    }

    public boolean isAvailable() {
        return mAvailable;
    }

    public int getClusterWidth()  { return mClusterWidth; }
    public int getClusterHeight() { return mClusterHeight; }
}
