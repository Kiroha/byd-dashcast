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

    public ClusterInputForwarder(Context context) {
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
    public void forwardTouch(float padX, float padY, float padW, float padH, final int action) {
        if (!mAvailable) return;

        // Mapping proportionnel : coordonnées pad → coordonnées cluster
        final float clusterX = (padX / padW) * mClusterWidth;
        final float clusterY = (padY / padH) * mClusterHeight;

        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, clusterX, clusterY, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        // API 29 : router l'event vers le display cluster.
        // setDisplayId() est @hide → accessible via réflexion avec platform.keystore.
        try {
            java.lang.reflect.Method setDisplayId =
                    MotionEvent.class.getMethod("setDisplayId", int.class);
            setDisplayId.invoke(event, mClusterDisplayId);
        } catch (Exception ignored) {
            // ROM trop ancienne ou méthode absente — l'event ira au display principal
        }
        try {
            mUdpExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String msg = action + "," + clusterX + "," + clusterY + "," + mClusterDisplayId;
                        byte[] buf = msg.getBytes();
                        if (mUdpSocket != null) {
                            mUdpSocket.send(new DatagramPacket(buf, buf.length, InetAddress.getByName("127.0.0.1"), 5005));
                        }
                    } catch (Exception x) {
                        AppLogger.e(TAG, "Touch UDP echoué", x);
                    }
                }
            });
        } catch (Exception e) {
            AppLogger.e(TAG, "Touch inject échoué", e);
        } finally {
            event.recycle();
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
