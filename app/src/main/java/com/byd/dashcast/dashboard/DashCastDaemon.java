package com.byd.dashcast.dashboard;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.graphics.PixelFormat;
import android.os.Looper;
import java.lang.reflect.Method;

/**
 * Daemon exécuté via app_process (shell, UID 2000).
 * Bénéficie de CAPTURE_VIDEO_OUTPUT accordée à shell.
 *
 * FIX packageName: ActivityThread.systemMain() crée un contexte
 * dont getPackageName() ne correspond pas à l'UID 2000.
 * DisplayManagerService.validatePackageName(uid=2000, pkg) vérifie
 * que le package appartient bien à l'UID appelant.
 * Sur Android 10, "com.android.shell" est le package de l'UID 2000.
 * Solution : ContextWrapper qui override getPackageName().
 *
 * Tentatives par ordre :
 *   1. "com.android.shell"   → package standard shell UID 2000
 *   2. null                  → skip validation sur certains ROM (permissif)
 *   3. "android"             → system UID (peut marcher si ROM permissive)
 */
public class DashCastDaemon {

    private static final String[] PACKAGE_CANDIDATES = {
        "com.android.shell",
        "android",
    };

    public static void main(String[] args) {
        System.out.println("[DashCastDaemon] Daemon started with shell privileges!");

        try {
            Looper.prepareMainLooper();
            System.out.println("[DashCastDaemon] Main Looper prepared.");

            Context systemContext = getSystemContext();
            System.out.println("[DashCastDaemon] Got SystemContext: " + systemContext);
            if (systemContext == null) {
                System.err.println("[DashCastDaemon] Failed to get SystemContext. Exiting.");
                System.exit(1);
                return;
            }

            ImageReader reader = null;
            VirtualDisplay vd = null;
            try {
                @SuppressWarnings("WrongConstant")
                ImageReader r = ImageReader.newInstance(1920, 720, PixelFormat.RGBA_8888, 2);
                reader = r;
                System.out.println("[DashCastDaemon] Fake Surface created.");

                vd = tryCreateVirtualDisplay(systemContext, reader);
                if (vd != null) {
                    System.out.println("[DashCastDaemon] SUCCESS! VirtualDisplay created! ID: "
                        + vd.getDisplay().getDisplayId());
                } else {
                    System.err.println("[DashCastDaemon] All packageName attempts failed.");
                }
            } finally {
                if (vd != null) vd.release();
                if (reader != null) reader.close();
            }

            System.out.println("[DashCastDaemon] Test completed. Exiting.");
            System.exit(0);

        } catch (Exception e) {
            System.err.println("[DashCastDaemon] FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Essaie plusieurs package names jusqu'à ce que createVirtualDisplay réussisse.
     * Le serveur DisplayManagerService vérifie que le package appartient à l'UID appelant.
     * UID shell = 2000 → "com.android.shell" sur Android 10.
     */
    private static VirtualDisplay tryCreateVirtualDisplay(Context base, ImageReader reader) {
        // Tentative 1..N avec les packages candidats
        for (String pkg : PACKAGE_CANDIDATES) {
            System.out.println("[DashCastDaemon] Trying packageName=\"" + pkg + "\"...");
            Context ctx = wrapPackage(base, pkg);
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) continue;
            try {
                VirtualDisplay vd = dm.createVirtualDisplay(
                    "remote_dashboard", 1920, 720, 320, reader.getSurface(), 320);
                System.out.println("[DashCastDaemon] packageName=\"" + pkg + "\" → SUCCESS");
                return vd;
            } catch (SecurityException se) {
                System.err.println("[DashCastDaemon] packageName=\"" + pkg + "\" → "
                    + se.getMessage());
            } catch (Exception e) {
                System.err.println("[DashCastDaemon] packageName=\"" + pkg + "\" → "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // Tentative finale via IDisplayManager reflection (null packageName)
        System.out.println("[DashCastDaemon] Trying IDisplayManager reflection (null pkg)...");
        try {
            VirtualDisplay vd = tryViaIDisplayManager(base, reader);
            if (vd != null) {
                System.out.println("[DashCastDaemon] IDisplayManager reflection → SUCCESS");
                return vd;
            }
        } catch (Exception e) {
            System.err.println("[DashCastDaemon] IDisplayManager reflection → "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Accès direct à IDisplayManager (Binder) pour contourner
     * la vérification du packageName en passant null.
     */
    private static VirtualDisplay tryViaIDisplayManager(Context ctx, ImageReader reader)
            throws Exception {
        // Récupérer le singleton DisplayManagerGlobal et son champ mDm (IDisplayManager)
        Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Method getInstance = globalClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object global = getInstance.invoke(null);

        // Appeler createVirtualDisplay via DisplayManagerGlobal (méthode interne)
        // Signature API 29 : createVirtualDisplay(Context, MediaProjection, String name,
        //   int w, int h, int dpi, Surface, int flags, Callback, Handler, String uniqueId)
        Method m = null;
        for (Method method : globalClass.getDeclaredMethods()) {
            if (method.getName().equals("createVirtualDisplay")) {
                m = method;
                break;
            }
        }
        if (m == null) throw new NoSuchMethodException("createVirtualDisplay not found");
        m.setAccessible(true);
        // Args: context, projection=null, name, w, h, dpi, surface, flags, callback=null, handler=null, uniqueId=null
        int result = (int) m.invoke(global, ctx, null,
            "remote_dashboard", 1920, 720, 320,
            reader.getSurface(), 320, null, null, null);
        System.out.println("[DashCastDaemon] IDisplayManager.createVirtualDisplay → displayId=" + result);
        if (result < 0) return null;
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return null;
        for (android.view.Display d : dm.getDisplays()) {
            if (d.getDisplayId() == result) {
                System.out.println("[DashCastDaemon] Display found: " + d);
                // Reconstruire un VirtualDisplay wrapper — pas possible sans callback object
                // On log juste le displayId pour prouver que ça marche
                return null; // display créé mais on ne peut pas wrapper sans callback
            }
        }
        return null;
    }

    private static Context wrapPackage(Context base, final String pkg) {
        return new ContextWrapper(base) {
            @Override public String getPackageName() { return pkg; }
        };
    }

    private static Context getSystemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method systemMainMethod = activityThreadClass.getDeclaredMethod("systemMain");
        systemMainMethod.setAccessible(true);
        Object activityThread = systemMainMethod.invoke(null);
        Method getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContextMethod.setAccessible(true);
        return (Context) getSystemContextMethod.invoke(activityThread);
    }
}
