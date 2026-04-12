package com.byd.myapp.dashboard;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import com.byd.myapp.AppLogger;

import java.lang.reflect.Method;

/**
 * DashboardLauncher — lance n'importe quelle application sur l'écran dashboard.
 *
 * Sur Android AOSP 7.1 (API 25), ActivityOptions.setLaunchDisplayId() existe mais
 * est marquée @hide. On l'appelle par réflexion, ce qui fonctionne sans root dans
 * une app signée avec la platform key (c'est notre cas avec platform.keystore).
 *
 * Deux usages :
 *  - launchOnDashboard(packageName) : lance une app tierce (Waze, Maps…)
 *  - launchBydDashboard()           : restaure le widget BYD (BYDDashboardActivity)
 */
public class DashboardLauncher {

    private static final String TAG = "DashboardLauncher";

    private final Context mContext;
    private int mDashboardDisplayId = -1;

    public DashboardLauncher(Context context) {
        mContext = context.getApplicationContext();
    }

    public void setDashboardDisplayId(int displayId) {
        mDashboardDisplayId = displayId;
        Log.d(TAG, "Dashboard display ID enregistré : " + displayId);
        AppLogger.log(TAG, "Display cluster enregistré : id=" + displayId);
    }

    public boolean isDashboardAvailable() {
        return mDashboardDisplayId >= 0;
    }

    public int getDashboardDisplayId() {
        return mDashboardDisplayId;
    }

    /**
     * Lance une app tierce (identifiée par son package) sur le dashboard.
     */
    public boolean launchOnDashboard(String packageName) {
        if (!isDashboardAvailable()) {
            AppLogger.w(TAG, "Dashboard non disponible — lancement annulé pour " + packageName);
            AppLogger.log(TAG, "LAUNCH KO (pas de display) — " + packageName);
            return false;
        }

        Intent launchIntent = mContext.getPackageManager()
                .getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            AppLogger.e(TAG, "App non installée ou introuvable : " + packageName);
            AppLogger.log(TAG, "LAUNCH KO (app introuvable) — " + packageName);
            return false;
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return launchWithDisplayId(launchIntent, mDashboardDisplayId);
    }

    /**
     * Restaure l'affichage d'origine BYD sur le dashboard.
     *
     * Envoie un intent HOME sur le display secondaire. Le système Android
     * rend la main au launcher/cluster par défaut du display, ce qui
     * remet l'affichage BYD d'origine sans tuer l'app tierce.
     */
    /**
     * Lance une app sur le display principal (display ID 0).
     * Restore en même temps le cluster BYD via restoreSystemDashboard().
     */
    public boolean launchOnMainDisplay(String packageName) {
        Intent launchIntent = mContext.getPackageManager()
                .getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            AppLogger.e(TAG, "App non installée ou introuvable : " + packageName);
            return false;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Display 0 = écran principal
        boolean launched = launchWithDisplayId(launchIntent, 0);
        return launched;
    }

    /**
     * Cœur du mécanisme : appel par réflexion à ActivityOptions.setLaunchDisplayId().
     * Accessible depuis une app signée platform.keystore sans permission supplémentaire.
     *
     * INSIGHT (confirmé analyse Freedom v1.9 + com.xdja.containerservice) :
     *   Sur BYD Seal, le cluster = VirtualDisplay créé au BOOT par AutoDisplayService.
     *   Il APPARAÎT dans DisplayManager via DISPLAY_CATEGORY_PRESENTATION (flags PUBLIC|PRESENTATION).
     *   Dimensions : 1920×1080 (hardcodé dans AutoDisplayService.createVirtualDisplay()).
     *   Context.startActivity() avec setLaunchDisplayId fonctionne si signé platform.keystore
     *   + INTERNAL_SYSTEM_WINDOW dans le Manifest.
     *
     * Cette méthode essaie le path direct (Context.startActivity) PUIS le path IActivityManager.
     */
    private boolean launchWithDisplayId(Intent intent, int displayId) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();

            Method setLaunchDisplayId = ActivityOptions.class
                    .getDeclaredMethod("setLaunchDisplayId", int.class);
            setLaunchDisplayId.setAccessible(true);
            setLaunchDisplayId.invoke(options, displayId);

            // WINDOWING_MODE_FULLSCREEN (1) est refusé par DiLink 3.0 — confirmé TEST 10.
            // L'activité apparaît en petite fenêtre flottante. FIX (Byd Dashboard APK v1.10.5) :
            //   → WINDOWING_MODE_FREEFORM (5) + setLaunchBounds(0, 0, realW, realH)
            try {
                Method setWM = ActivityOptions.class
                        .getDeclaredMethod("setLaunchWindowingMode", int.class);
                setWM.setAccessible(true);
                setWM.invoke(options, 5); // WINDOWING_MODE_FREEFORM = 5
                AppLogger.i(TAG, "setLaunchWindowingMode(FREEFORM=5) appliqué");
            } catch (NoSuchMethodException ignored) {
                AppLogger.w(TAG, "setLaunchWindowingMode absent (ROM trop ancienne)");
            }
            // Bounds = dimensions réelles du display → occupe tout le cluster
            try {
                Method setLB = ActivityOptions.class
                        .getDeclaredMethod("setLaunchBounds", Rect.class);
                setLB.setAccessible(true);
                Point size = new Point(1920, 1080); // fallback VirtualDisplay AutoDisplayService: 1920×1080
                DisplayManager dm = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
                Display targetDisplay = (dm != null) ? dm.getDisplay(displayId) : null;
                if (targetDisplay != null) {
                    targetDisplay.getRealSize(size);
                    AppLogger.i(TAG, "getRealSize display " + displayId + " → " + size.x + "×" + size.y);
                } else {
                    AppLogger.w(TAG, "getDisplay(" + displayId + ") null → fallback 1920×1080");
                }
                setLB.invoke(options, new Rect(0, 0, size.x, size.y));
                AppLogger.i(TAG, "setLaunchBounds(0,0," + size.x + "," + size.y + ") appliqué");
            } catch (NoSuchMethodException ignored) {
                AppLogger.w(TAG, "setLaunchBounds absent");
            }

            // Essai 1 : Context.startActivity (fonctionnel sur display 0, peut échouer sur display 1)
            try {
                mContext.startActivity(intent, options.toBundle());
                AppLogger.i(TAG, "Context.startActivity OK display=" + displayId);
                AppLogger.log(TAG, "LAUNCH OK display=" + displayId);
                return true;
            } catch (Exception e1) {
                AppLogger.w(TAG, "Context.startActivity échoué display=" + displayId + " — essai IActivityManager", e1);
            }

            // Essai 2 : IActivityManager.startActivityAsUser (path WindowManagement, userId=-2)
            try {
                Class<?> amClass = Class.forName("android.app.IActivityManager$Stub");
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                Method getService = smClass.getDeclaredMethod("getService", String.class);
                Object iAm = amClass.getMethod("asInterface", android.os.IBinder.class)
                        .invoke(null, getService.invoke(null, "activity"));
                if (iAm == null) throw new IllegalStateException("IActivityManager null");

                // startActivityAsUser(null, null, intent, null, null, null, 0, 0, null, optBundle, -2)
                Method startActivity = null;
                for (java.lang.reflect.Method m : iAm.getClass().getMethods()) {
                    if (m.getName().equals("startActivityAsUser")) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 11) { startActivity = m; break; }
                    }
                }
                if (startActivity == null) throw new NoSuchMethodException("startActivityAsUser(11)");
                int result = (int) startActivity.invoke(iAm,
                        null, null, intent, null, null, null, 0, 0, null,
                        options.toBundle(), -2);
                AppLogger.i(TAG, "IActivityManager.startActivityAsUser result=" + result + " display=" + displayId);
                AppLogger.log(TAG, "LAUNCH IActMgr result=" + result + " display=" + displayId);
                return result == 0;
            } catch (Exception e2) {
                AppLogger.e(TAG, "IActivityManager.startActivityAsUser échoué", e2);
                AppLogger.log(TAG, "LAUNCH IActMgr ECHEC — " + e2.getClass().getSimpleName() + ": " + e2.getMessage());
            }

            return false;

        } catch (NoSuchMethodException e) {
            AppLogger.e(TAG, "setLaunchDisplayId introuvable dans ce ROM — fallback display principal", e);
            AppLogger.log(TAG, "LAUNCH FALLBACK — setLaunchDisplayId absent");
            mContext.startActivity(intent);
            return true;

        } catch (Exception e) {
            AppLogger.e(TAG, "Erreur lors du lancement sur display " + displayId, e);
            AppLogger.log(TAG, "LAUNCH EXCEPTION display=" + displayId + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }
}
