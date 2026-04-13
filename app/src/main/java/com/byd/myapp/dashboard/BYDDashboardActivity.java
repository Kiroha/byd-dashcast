package com.byd.myapp.dashboard;

import android.graphics.Point;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.Display;

import com.byd.myapp.R;

/**
 * BYDDashboardActivity — surface de projection sur le cluster (display 1).
 *
 * Lancée via setLaunchDisplayId() sur le display secondaire. Elle occupe la totalité
 * de la surface du cluster et sert de fond neutre quand aucune app tierce n'est projetée.
 *
 * "Restaurer BYD" depuis MainActivity = relancer cette Activity sur le même display
 * avec FLAG_ACTIVITY_SINGLE_TOP → elle revient au premier plan, repoussant l'app tierce.
 */
public class BYDDashboardActivity extends AppCompatActivity {

    // Référence statique pour permettre à DashboardDisplayHelper.stop() de terminer
    // cette Activity AVANT d'appeler restoreNative() — Qt ne peut recapturer la surface
    // que si aucune Activity Android ne la détient.
    private static java.lang.ref.WeakReference<BYDDashboardActivity> sInstance;

    /** Appelé par DashboardDisplayHelper.stop() juste avant sendInfo(0). */
    public static void finishIfActive() {
        java.lang.ref.WeakReference<BYDDashboardActivity> ref = sInstance;
        if (ref != null) {
            BYDDashboardActivity act = ref.get();
            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                act.finish();
            }
            sInstance = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = new java.lang.ref.WeakReference<>(this);

        // Supprimer les animations et décorations FREEFORM AVANT setContentView().
        // FLAG_LAYOUT_IN_SCREEN : empêche le WM de repositionner la fenêtre hors des bornes display.
        // FLAG_LAYOUT_NO_LIMITS : désactive les contraintes de taille imposées par le WM en FREEFORM.
        // FLAG_FULLSCREEN       : supprime la barre de caption FREEFORM (déco resize-handle).
        // Ces 3 flags combinés stoppent l'animation "grow-from-zero" du mode FREEFORM.
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Figer la taille de la fenêtre AVANT setContentView().
        // En mode FREEFORM (windowingMode=5), le layout match_parent + l'absence de bornes
        // fixes causent un agrandissement progressif du rectangle visible sur le cluster.
        // getRealSize() depuis l'Activity retourne les dimensions du display sur lequel
        // elle s'exécute (display 1 = cluster), pas du display principal.
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Point size = new Point(1920, 1080); // fallback VirtualDisplay AutoDisplayService (1920×1080)
            d.getRealSize(size);
            getWindow().setLayout(size.x, size.y);
        } catch (Exception ignored) {
            getWindow().setLayout(1920, 1080);
        }

        setContentView(R.layout.activity_dashboard);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Nettoyer la référence statique si c'est bien notre instance
        java.lang.ref.WeakReference<BYDDashboardActivity> ref = sInstance;
        if (ref != null && ref.get() == this) {
            sInstance = null;
        }
    }
}
