package com.byd.myapp.dashboard;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

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
            Log.w(TAG, "Dashboard non disponible — lancement annulé pour " + packageName);
            AppLogger.log(TAG, "LAUNCH KO (pas de display) — " + packageName);
            return false;
        }

        Intent launchIntent = mContext.getPackageManager()
                .getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            Log.e(TAG, "App non installée ou introuvable : " + packageName);
            AppLogger.log(TAG, "LAUNCH KO (app introuvable) — " + packageName);
            return false;
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);

        return launchWithDisplayId(launchIntent, mDashboardDisplayId);
    }

    /**
     * Restaure l'affichage d'origine BYD sur le dashboard.
     *
     * Envoie un intent HOME sur le display secondaire. Le système Android
     * rend la main au launcher/cluster par défaut du display, ce qui
     * remet l'affichage BYD d'origine sans tuer l'app tierce.
     */
    public boolean restoreSystemDashboard() {
        if (!isDashboardAvailable()) {
            Log.w(TAG, "Dashboard non disponible — restauration annulée");
            return false;
        }

        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return launchWithDisplayId(homeIntent, mDashboardDisplayId);
    }

    /**
     * Lance une app sur le display principal (display ID 0).
     * Restore en même temps le cluster BYD via restoreSystemDashboard().
     */
    public boolean launchOnMainDisplay(String packageName) {
        Intent launchIntent = mContext.getPackageManager()
                .getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            Log.e(TAG, "App non installée ou introuvable : " + packageName);
            return false;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Display 0 = écran principal
        boolean launched = launchWithDisplayId(launchIntent, 0);
        if (launched && isDashboardAvailable()) {
            // Restaurer le cluster BYD maintenant que l'app quitte le display secondaire
            restoreSystemDashboard();
        }
        return launched;
    }

    /**
     * Cœur du mécanisme : appel par réflexion à ActivityOptions.setLaunchDisplayId().
     * Accessible depuis une app signée platform.keystore sans permission supplémentaire.
     */
    private boolean launchWithDisplayId(Intent intent, int displayId) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();

            Method setLaunchDisplayId = ActivityOptions.class
                    .getDeclaredMethod("setLaunchDisplayId", int.class);
            setLaunchDisplayId.setAccessible(true);
            setLaunchDisplayId.invoke(options, displayId);

            mContext.startActivity(intent, options.toBundle());
            Log.i(TAG, "Lancé sur display " + displayId + " : " + intent);
            AppLogger.log(TAG, "LAUNCH OK display=" + displayId + " pkg=" + intent.getPackage());
            return true;

        } catch (NoSuchMethodException e) {
            Log.e(TAG, "setLaunchDisplayId introuvable dans ce ROM — fallback display principal", e);
            AppLogger.log(TAG, "LAUNCH FALLBACK — setLaunchDisplayId absent");
            mContext.startActivity(intent);
            return false;

        } catch (Exception e) {
            Log.e(TAG, "Erreur lors du lancement sur display " + displayId, e);
            AppLogger.log(TAG, "LAUNCH EXCEPTION display=" + displayId + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }
}
