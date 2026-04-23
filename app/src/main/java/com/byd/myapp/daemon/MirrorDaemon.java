package com.byd.myapp.daemon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;

import java.lang.reflect.Method;

public class MirrorDaemon {

    private static final String TAG = "MirrorDaemon";

    public static void main(String[] args) {
        Log.i(TAG, "Démarrage du daemon MirrorDaemon avec uid=" + android.os.Process.myUid());

        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }

            // Récupération d'un System Context
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object thread = atClass.getMethod("systemMain").invoke(null);
            Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            if (context == null) {
                Log.e(TAG, "Context null !");
                return;
            }
            Log.i(TAG, "Context système récupéré OK.");

            // Déverrouillage des APIs cachées
            try {
                Method getDeclaredMethod = Class.class.getDeclaredMethod(
                        "getDeclaredMethod", String.class, Class[].class);
                Method forNameMethod = Class.class.getDeclaredMethod("forName", String.class);
                Class<?> vmRuntimeClass = (Class<?>) forNameMethod.invoke(null, "dalvik.system.VMRuntime");
                Method getRuntimeMethod = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null);
                Object vmRuntime = getRuntimeMethod.invoke(null);
                Method setExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass,
                        "setHiddenApiExemptions", new Class[]{String[].class});
                setExemptions.invoke(vmRuntime, new Object[]{
                        new String[]{"Landroid/", "Lcom/android/", "Ljava/lang/"}
                });
                Log.i(TAG, "APIs cachées déverrouillées dans le Daemon.");
            } catch (Exception e) {
                Log.e(TAG, "Erreur déverrouillage API", e);
            }

            // On enregistre un un receiver pour récupérer la Surface
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.byd.myapp.MIRROR_DAEMON_SURFACE");
            filter.addAction("com.byd.myapp.MIRROR_DAEMON_STOP");
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    Log.i(TAG, "Intent reçu : " + intent.getAction());
                    if ("com.byd.myapp.MIRROR_DAEMON_STOP".equals(intent.getAction())) {
                        stopMirrorNatively();
                        return;
                    }
                    
                    Surface surface = null;
                    if (intent.getExtras() != null) {
                        android.os.IBinder binder = intent.getExtras().getBinder("surface_binder");
                        if (binder != null) {
                            try {
                                android.os.Parcel data = android.os.Parcel.obtain();
                                android.os.Parcel reply = android.os.Parcel.obtain();
                                binder.transact(1, data, reply, 0);
                                reply.readException();
                                surface = Surface.CREATOR.createFromParcel(reply);
                                data.recycle();
                                reply.recycle();
                            } catch (Exception e) {
                                Log.e(TAG, "Erreur transaction Binder (Surface)", e);
                            }
                        }
                    }

                    if (surface != null) {
                        Log.i(TAG, "Surface reçue via Binder IPC valide ! Démarrage du miroir...");
                        int viewW = intent.getIntExtra("viewW", 1920);
                        int viewH = intent.getIntExtra("viewH", 720);
                        int clusterW = intent.getIntExtra("clusterW", 1920);
                        int clusterH = intent.getIntExtra("clusterH", 720);
                        startMirrorNatively(surface, viewW, viewH, clusterW, clusterH);
                    } else {
                        Log.e(TAG, "Surface null ou non fournie par le Binder.");
                    }
                }
            }, filter);

            startTouchServer();

            Log.i(TAG, "Receiver enregistré. Daemon en attente de la Surface...");
            Looper.loop();

        } catch (Exception e) {
            Log.e(TAG, "Crash du Daemon MirrorDaemon", e);
        }
    }

    private static void startTouchServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.DatagramSocket socket = new java.net.DatagramSocket(5005);
                    byte[] buffer = new byte[64];
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
                    Class<?> imClass = Class.forName("android.hardware.input.InputManager");
                    Method getInstance = imClass.getDeclaredMethod("getInstance");
                    getInstance.setAccessible(true);
                    Object im = getInstance.invoke(null);
                    Method inject = imClass.getDeclaredMethod("injectInputEvent", android.view.InputEvent.class, int.class);
                    inject.setAccessible(true);
                    Method setDisplayId = null;
                    try { setDisplayId = android.view.MotionEvent.class.getMethod("setDisplayId", int.class); } catch (Exception ignored) {}
                    Log.i(TAG, "Touch UDP Server démarré sur le port 5005");
                    while (true) {
                        try {
                            socket.receive(packet);
                            String data = new String(packet.getData(), 0, packet.getLength());
                            String[] parts = data.split(",");
                            if (parts.length >= 4) {
                                int action = Integer.parseInt(parts[0]);
                                float x = Float.parseFloat(parts[1]);
                                float y = Float.parseFloat(parts[2]);
                                int displayId = Integer.parseInt(parts[3]);
                                long now = android.os.SystemClock.uptimeMillis();
                                android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now, action, x, y, 0);
                                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                                if (setDisplayId != null && displayId > 0) {
                                    setDisplayId.invoke(event, displayId);
                                }
                                inject.invoke(im, event, 0);
                                event.recycle();
                            }
                        } catch (Exception packetEx) {
                            Log.e(TAG, "TouchServer: Erreur packet ignorée", packetEx);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "TouchServer erreur", e);
                }
            }
        }).start();
    }

    private static VirtualDisplay mVirtualDisplay = null;

    private static void stopMirrorNatively() {
        if (mVirtualDisplay != null) {
            try {
                mVirtualDisplay.release();
                Log.i(TAG, "Miroir VirtualDisplay detruit par le Daemon.");
            } catch (Exception e) {
                Log.e(TAG, "Erreur destruction miroir", e);
            }
            mVirtualDisplay = null;
        }
    }

    private static void startMirrorNatively(Surface targetSurface, int viewW, int viewH, int mClusterW, int mClusterH) {
        try {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }

            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object thread = atClass.getMethod("systemMain").invoke(null);
            Context systemContext = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);

            DisplayManager dm = (DisplayManager) systemContext.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                Log.e(TAG, "DisplayManager non trouvé.");
                return;
            }

            // Flags: 1=PUBLIC, 2=PRESENTATION, 8=AUTO_MIRROR => 11
            int flags = 11;
            
            // Nom hardcodé qui bypass les protections BYD
            String displayName = "fission_bg_xdjaVirtualSurface";
            
            mVirtualDisplay = dm.createVirtualDisplay(displayName, mClusterW, mClusterH, 320, targetSurface, flags);
            
            if (mVirtualDisplay == null) {
                Log.e(TAG, "Echec creation VirtualDisplay par Daemon.");
                return;
            }

            Log.i(TAG, "Miroir Daemon (DisplayManager) démarré avec succès sous le nom: " + displayName);
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors du démarrage du miroir Daemon natif", e);
        }
    }
}
