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

import java.lang.reflect.Method;

public class MirrorDaemon {

    private static final String TAG = "MirrorDaemon";

    public static void main(String[] args) {
        try {
            android.os.Process.class.getMethod("setArgV0", String.class).invoke(null, "com.byd.myapp.mirrordaemon");
        } catch (Exception ignored) {}
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
            filter.addAction("com.byd.myapp.MIRROR_DAEMON_PULL");
            filter.addAction("com.byd.myapp.MIRROR_DAEMON_STOP");
            filter.addAction("com.byd.myapp.MIRROR_DAEMON_LAUNCH");
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    Log.i(TAG, "Intent reçu : " + intent.getAction());
                    if ("com.byd.myapp.MIRROR_DAEMON_LAUNCH".equals(intent.getAction())) {
                        String pkg = intent.getStringExtra("pkg");
                        String cls = intent.getStringExtra("cls");
                        int displayId = intent.getIntExtra("displayId", 0);
                        int bl = intent.getIntExtra("bounds_l", -1);
                        int bt = intent.getIntExtra("bounds_t", -1);
                        int br = intent.getIntExtra("bounds_r", -1);
                        int bb = intent.getIntExtra("bounds_b", -1);
                        try {
                            Intent launchIntent = new Intent();
                            launchIntent.setComponent(new android.content.ComponentName(pkg, cls));
                            launchIntent.addFlags(0x10008000); // NEW_TASK | CLEAR_TASK
                            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                            opts.setLaunchDisplayId(displayId);
                            if (bl >= 0 && br > bl && bb > bt) {
                                opts.setLaunchBounds(new Rect(bl, bt, br, bb));
                                try {
                                    Method setWm = android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                                    setWm.invoke(opts, 5); // FREEFORM
                                } catch (Exception ignored) {}
                            }
                            c.startActivity(launchIntent, opts.toBundle());
                            Log.i(TAG, "Lancement réussi de " + pkg + "/" + cls + " sur display " + displayId + " par le MirrorDaemon !");
                        } catch (Exception e) {
                            Log.e(TAG, "Erreur lancement daemon de " + pkg, e);
                        }
                        return;
                    }
                    if ("com.byd.myapp.MIRROR_DAEMON_STOP".equals(intent.getAction())) {
                        DaemonMirrorLogic.stop();
                        return;
                    }
                    
                    if ("com.byd.myapp.MIRROR_DAEMON_PULL".equals(intent.getAction())) {
                        try {
                            android.net.Uri uri = android.net.Uri.parse("content://com.byd.myapp.mirrorprovider");
                            android.os.Bundle result = c.getContentResolver().call(uri, "getSurface", null, null);
                            if (result != null) {
                                android.os.IBinder binder = result.getBinder("surface_binder");
                                if (binder != null) {
                                    android.os.Parcel data = android.os.Parcel.obtain();
                                    android.os.Parcel reply = android.os.Parcel.obtain();
                                    binder.transact(1, data, reply, 0);
                                    reply.readException();
                                    Surface surface = null;
                                    int hasSurface = reply.readInt();
                                    if (hasSurface == 1) {
                                        surface = Surface.CREATOR.createFromParcel(reply);
                                    }
                                    int viewW = reply.readInt();
                                    int viewH = reply.readInt();
                                    int clusterW = reply.readInt();
                                    int clusterH = reply.readInt();
                                    int layerStack = reply.readInt();
                                    data.recycle();
                                    reply.recycle();
                                    
                                    if (surface != null && surface.isValid()) {
                                        Log.i(TAG, "Surface PULL valide depuis le Provider ! Démarrage du miroir... " + viewW + "x" + viewH);
                                        DaemonMirrorLogic.start(surface, viewW, viewH, clusterW, clusterH, layerStack);
                                    } else {
                                        Log.e(TAG, "Surface récupérée est invalide ou nulle !");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Erreur PULL ContentProvider", e);
                        }
                    }
                }
            }, filter);

            startTouchServer();

            Log.i(TAG, "Receiver enregistré. Daemon en attente de la Surface...");
            context.sendBroadcast(new Intent("com.byd.myapp.MIRROR_DAEMON_READY"));
            
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
                    byte[] buffer = new byte[16];
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
                    Class<?> imClass = Class.forName("android.hardware.input.InputManager");
                    Method getInstance = imClass.getDeclaredMethod("getInstance");
                    getInstance.setAccessible(true);
                    Object im = getInstance.invoke(null);
                    Method inject = imClass.getDeclaredMethod("injectInputEvent", android.view.InputEvent.class, int.class);
                    inject.setAccessible(true);
                    Method setDisplayId = null;
                    try { setDisplayId = android.view.MotionEvent.class.getMethod("setDisplayId", int.class); } catch (Exception ignored) {}
                    
                    java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder());
                    Log.i(TAG, "Touch UDP Server Binary démarré sur le port 5005");
                    while (true) {
                        try {
                            socket.receive(packet);
                            if (packet.getLength() == 16) {
                                byteBuffer.clear();
                                byteBuffer.put(buffer, 0, 16);
                                byteBuffer.position(0);
                                int action = byteBuffer.getInt();
                                float x = byteBuffer.getFloat();
                                float y = byteBuffer.getFloat();
                                int displayId = byteBuffer.getInt();
                                
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

}
