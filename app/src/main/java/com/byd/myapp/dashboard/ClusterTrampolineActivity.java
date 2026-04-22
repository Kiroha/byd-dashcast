package com.byd.myapp.dashboard;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;

import com.byd.myapp.AppLogger;

/**
 * ClusterTrampolineActivity — activity vide lancée sur display 1 pour servir de
 * "source activity" lors du lancement d'apps tierces sur le cluster.
 *
 * Pourquoi : sur Seal EU (DiLink 3.0, Android 10), pousser une app tierce
 * directement avec ActivityOptions.setLaunchDisplayId(1) échoue avec
 * SecurityException dans SafeActivityOptions.checkPermissions() — même quand
 * l'APK est signé platform.keystore (INTERNAL_SYSTEM_WINDOW et MANAGE_ACTIVITY_STACKS
 * sont déclarés mais la ROM ne les considère pas suffisants pour cibler un display
 * non-default appartenant à un autre process — confirmé en voiture v1.69 et v1.70).
 *
 * AOSP API 29 — ActivityStackSupervisor.isCallerAllowedToLaunchOnDisplay() :
 *   final int targetUid = aInfo.applicationInfo.uid;
 *   if (targetUid == callingUid) return true;   // ← ON PASSE ICI
 *
 * Donc lancer NOTRE PROPRE activity (uid identique) avec setLaunchDisplayId(1) est
 * autorisé. Une fois cette activity sur display 1, on appelle
 * `Activity.startActivity(intent_tiers)` SANS setLaunchDisplayId : ActivityStarter
 * place la nouvelle task sur le display de la source (display 1) — aucun check de
 * SafeActivityOptions n'est déclenché car launchDisplayId == INVALID_DISPLAY.
 *
 * C'est exactement le pattern utilisé par BYDDashboard officiel.
 *
 * Lifecycle : finish() est appelée dès le démarrage du tiers. La task de l'app
 * tierce reste sur display 1 même après destruction du trampoline.
 */
public class ClusterTrampolineActivity extends Activity {

    private static final String TAG = "ClusterTrampoline";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";

    /** Construit l'Intent de lancement du trampoline pour un tiers donné. */
    public static Intent buildLaunchIntent(Context ctx, String targetPackage) {
        Intent i = new Intent(ctx, ClusterTrampolineActivity.class);
        i.putExtra(EXTRA_TARGET_PACKAGE, targetPackage);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");

        String pkg = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_TARGET_PACKAGE)
                : null;

        AppLogger.i(TAG, "Trampoline lancé sur display="
                + getWindowManager().getDefaultDisplay().getDisplayId()
                + " — cible: " + pkg);

        if (pkg == null || pkg.isEmpty()) {
            AppLogger.w(TAG, "Pas de cible — finish");
            finish();
            return;
        }

        Intent launch = resolveLaunchIntent(pkg);
        if (launch == null) {
            AppLogger.e(TAG, "Aucune Activity trouvée pour " + pkg);
            finish();
            return;
        }

        // CRUCIAL : pas de setLaunchDisplayId ici. La nouvelle task héritera du
        // display de la source (nous → display 1).
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        // Bounds optionnelles passées via extras entiers (DiLink 3.0 n'accepte pas --bounds)
        int bl = getIntent().getIntExtra("bounds_l", -1);
        int bt = getIntent().getIntExtra("bounds_t", -1);
        int br = getIntent().getIntExtra("bounds_r", -1);
        int bb = getIntent().getIntExtra("bounds_b", -1);
        boolean hasBounds = bl >= 0 && br > bl && bb > bt;

        try {
            if (hasBounds) {
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchBounds(new Rect(bl, bt, br, bb));
                startActivity(launch, opts.toBundle());
                AppLogger.i(TAG, "startActivity(" + pkg + ") bounds=["
                        + bl + "," + bt + "," + br + "," + bb + "] OK depuis trampoline");
            } else {
                startActivity(launch);
                AppLogger.i(TAG, "startActivity(" + pkg + ") OK depuis trampoline");
            }
        } catch (Exception e) {
            if (hasBounds) {
                // Fallback sans bounds si setLaunchBounds n'est pas supporté sur ce ROM
                AppLogger.w(TAG, "startActivity avec bounds échoué (" + e.getMessage()
                        + "), fallback sans bounds");
                try {
                    startActivity(launch);
                    AppLogger.i(TAG, "startActivity(" + pkg + ") fallback sans bounds OK");
                } catch (Exception e2) {
                    AppLogger.e(TAG, "startActivity fallback aussi échoué pour " + pkg, e2);
                }
            } else {
                AppLogger.e(TAG, "startActivity échoué pour " + pkg, e);
            }
        }
        finish();
    }

    private Intent resolveLaunchIntent(String packageName) {
        PackageManager pm = getPackageManager();
        Intent li = pm.getLaunchIntentForPackage(packageName);
        if (li != null) return li;

        try {
            PackageInfo pi = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null && pi.activities.length > 0) {
                ActivityInfo ai = pi.activities[0];
                Intent i = new Intent();
                i.setComponent(new ComponentName(packageName, ai.name));
                return i;
            }
        } catch (PackageManager.NameNotFoundException ignored) {}
        return null;
    }
}
