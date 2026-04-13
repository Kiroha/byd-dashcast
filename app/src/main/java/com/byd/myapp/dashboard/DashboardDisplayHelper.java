package com.byd.myapp.dashboard;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import com.byd.myapp.AppLogger;

/**
 * DashboardDisplayHelper — détecte le display secondaire (instrument cluster).
 *
 * COMPORTEMENT SUR BYD SEAL :
 *   Le cluster n'est PAS exposé comme un display Android visible natif.
 *   Il faut d'abord appeler ClusterManager.enterProjectionMode() (sendInfo 1000/16)
 *   pour que AutoDisplayService crée son VirtualDisplay, puis écouter son apparition.
 *
 *   Ce helper délègue aujourd'hui toute la logique à ClusterManager.activateClusterDisplay()
 *   qui gère : sendInfo + startService AutoDisplayService + polling VirtualDisplay + timeout.
 *
 *   Le DisplayListener reste enregistré pour détecter les déconnexions.
 */
public class DashboardDisplayHelper {

    private static final String TAG = "DashboardDisplayHelper";

    public interface Listener {
        void onDashboardDisplayConnected(Display display, int displayId);
        void onDashboardDisplayDisconnected();
    }

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final Listener mListener;
    private final ClusterManager mClusterManager;

    // ID du display cluster connu — -1 si non connecté
    private int mKnownClusterDisplayId = -1;

    private final DisplayManager.DisplayListener mDisconnectListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {
                    if (displayId != mKnownClusterDisplayId) return;
                    AppLogger.i(TAG, "Dashboard display supprimé : id=" + displayId);
                    mKnownClusterDisplayId = -1;
                    mListener.onDashboardDisplayDisconnected();
                }

                @Override
                public void onDisplayChanged(int displayId) {}
            };

    public DashboardDisplayHelper(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mListener = listener;
        mClusterManager = new ClusterManager(context);
    }

    /**
     * Déclenche la séquence d'activation du cluster :
     *   1. Vérifie DISPLAY_CATEGORY_PRESENTATION (VirtualDisplay créé au BOOT par AutoDisplayService)
     *   2. Si trouvé → sendInfo(1000, 16) pour mettre Qt en standby → callback immédiat
     *   3. Si non trouvé → sendInfo(16) + polling 3s → fallback displayId=1 sur timeout
     *
     * ARCHITECTURE (confirmé analyse Freedom v1.9) :
     *   AutoDisplayService crée le VirtualDisplay cluster au BOOT avec flags PUBLIC|PRESENTATION.
     *   Le display est visible via DISPLAY_CATEGORY_PRESENTATION dès le démarrage du système.
     *   sendInfo(1000, 16) ne crée PAS le display : il met Qt en standby (libère la surface).
     *
     * La callback onDashboardDisplayConnected / onDashboardDisplayDisconnected
     * sera appelée sur le main thread.
     */
    public void start() {
        mDisplayManager.registerDisplayListener(mDisconnectListener, null);

        mClusterManager.activateClusterDisplay(new ClusterManager.DisplayReadyCallback() {
            @Override
            public void onDisplayReady(Display display, int displayId) {
                // Guard : si stop() a déjà été appelé, ignorer le callback
                if (mKnownClusterDisplayId == -2) return;
                mKnownClusterDisplayId = displayId;
                Log.i(TAG, "Dashboard display prêt : id=" + displayId
                        + " name=" + (display != null ? display.getName() : "null"));
                mListener.onDashboardDisplayConnected(display, displayId);
            }

            @Override
            public void onDisplayTimeout() {
                // Guard : si stop() a déjà été appelé, ignorer le callback orphelin
                if (mKnownClusterDisplayId == -2) {
                    Log.d(TAG, "onDisplayTimeout ignoré — stop() déjà appelé");
                    return;
                }
                // VirtualDisplay non trouvé via DISPLAY_CATEGORY_PRESENTATION (cas exceptionnel).
                // Fallback : displayId=1 hardcodé (DiLink 3.0, Seal EU — IActivityManager path).
                AppLogger.w(TAG, "Dashboard display non détecté après timeout — "
                        + "fallback hardcodé displayId=1 (DiLink 3.0)");
                mKnownClusterDisplayId = 1;
                Display display1 = mDisplayManager.getDisplay(1); // peut retourner null sur certains ROMs
                if (display1 != null) {
                    Log.i(TAG, "getDisplay(1) != null — display cluster accessible");
                    mListener.onDashboardDisplayConnected(display1, 1);
                } else {
                    Log.i(TAG, "getDisplay(1) null — lancement via IActivityManager au displayId=1");
                    mListener.onDashboardDisplayConnected(null, 1);
                }
            }
        });
    }

    public void stop() {
        // Sentinelle : toute callback ClusterManager orpheline (handler postDelayed) sera ignorée
        mKnownClusterDisplayId = -2;

        // Annuler TOUS les callbacks Handler en attente (polls + timeout) dans ClusterManager.
        // SANS ça, le timeout de 8s se déclenche après stop() et appelle onDashboardDisplayConnected(null,1) → NPE.
        mClusterManager.cancel();

        mDisplayManager.unregisterDisplayListener(mDisconnectListener);

        // NE PAS appeler stopService(AutoDisplayService) :
        // Ce service système est démarré au BOOT et gère le VirtualDisplay cluster.
        // L'arrêter détruirait le VirtualDisplay PRESENTATION pour TOUTE la session Android.

        // Terminer BYDDashboardActivity AVANT sendInfo(18) :
        // Qt ne peut recapturer la surface du VirtualDisplay que si aucune Activity Android
        // ne la détient encore. finish() notifie le WindowManager de manière asynchrone ;
        // sendInfo(18) est envoyé en Binder — Qt réessaiera jusqu'à ce que la surface soit libre.
        BYDDashboardActivity.finishIfActive();

        // Fermer le mode projection via sendInfo(1000, 18) = 投屏关闭.
        // CORRECTION : on utilisait cmd=0 (rafraîchir flux) au lieu de cmd=18 (fermer projection)
        // → cmd=18 enumère correctement le mode projection et permet à Qt de reprendre le display.
        mClusterManager.stopProjection();
        // Rafraîchir le flux Qt après fermeture de la projection.
        mClusterManager.restoreNative();
        // Réinitialiser à -1 (état "déconnecté normal" après stop complet)
        mKnownClusterDisplayId = -1;
    }

    /** Re-passe le cluster en mode projection (sendInfo 1000/16 — Qt standby). */
    public void enterProjectionMode() {
        mClusterManager.enterProjectionMode();
        // NOTE : hideAdas() (cmd 13) n'a pas d'effet sur le cluster 2D Seal EU.
        // L'agrandissement de la fenêtre ADAS est causé par le mode FREEFORM du WM Android,
        // non par Qt. Le fix est dans l'utilisation de cmd=18 pour la restauration.
    }

    public int getKnownClusterDisplayId() {
        return mKnownClusterDisplayId;
    }
}
