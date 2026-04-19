package com.byd.myapp.dashboard;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import com.byd.myapp.AdbLocalClient;
import com.byd.myapp.AppLogger;

/**
 * DashboardDisplayHelper — détecte le display secondaire (instrument cluster).
 *
 * COMPORTEMENT SUR BYD SEAL :
 *   Le cluster n'est PAS exposé comme un display Android visible natif.
 *   Il faut d'abord appeler ClusterManager.activateClusterDisplay() (sendInfo 1000/30+16)
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
        mKnownClusterDisplayId = -1; // réinitialiser avant chaque activation
        mDisplayManager.registerDisplayListener(mDisconnectListener, null);

        mClusterManager.activateClusterDisplay(new ClusterManager.DisplayReadyCallback() {
            @Override
            public void onDisplayReady(Display display, int displayId) {
                // Guard : si stop() a déjà été appelé, ignorer le callback
                if (mKnownClusterDisplayId == -2) return;
                mKnownClusterDisplayId = displayId;
                AppLogger.i(TAG, "Dashboard display prêt : id=" + displayId
                        + " name=" + (display != null ? display.getName() : "null"));
                mListener.onDashboardDisplayConnected(display, displayId);
            }

            @Override
            public void onDisplayTimeout() {
                // Guard : si stop() a déjà été appelé, ignorer le callback orphelin
                if (mKnownClusterDisplayId == -2) {
                    AppLogger.d(TAG, "onDisplayTimeout ignoré — stop() déjà appelé");
                    return;
                }
                // VirtualDisplay non trouvé via DISPLAY_CATEGORY_PRESENTATION (cas exceptionnel).
                // Fallback : displayId=1 hardcodé (DiLink 3.0, Seal EU — IActivityManager path).
                AppLogger.w(TAG, "Dashboard display non détecté après timeout — "
                        + "fallback hardcodé displayId=1 (DiLink 3.0)");
                mKnownClusterDisplayId = 1;
                Display display1 = mDisplayManager.getDisplay(1); // peut retourner null sur certains ROMs
                if (display1 != null) {
                    AppLogger.i(TAG, "getDisplay(1) != null — display cluster accessible");
                    mListener.onDashboardDisplayConnected(display1, 1);
                } else {
                    AppLogger.i(TAG, "getDisplay(1) null — lancement via IActivityManager au displayId=1");
                    mListener.onDashboardDisplayConnected(null, 1);
                }
            }
        });
    }

    public void stop() {
        // Sentinelle : toute callback ClusterManager orpheline (handler postDelayed) sera ignorée
        mKnownClusterDisplayId = -2;

        // Annuler TOUS les callbacks Handler en attente (polls + timeout) dans ClusterManager.
        // SANS ça, le timeout de 3s se déclenche après stop() et appelle onDashboardDisplayConnected(null,1) → NPE.
        mClusterManager.cancel();

        mDisplayManager.unregisterDisplayListener(mDisconnectListener);

        // NE PAS appeler stopService(AutoDisplayService) :
        // Ce service système est démarré au BOOT et gère le VirtualDisplay cluster.
        // L'arrêter détruirait le VirtualDisplay PRESENTATION pour TOUTE la session Android.

        // Fermer le mode projection via sendInfo(1000, 18) = 投屏关闭 + sendInfo(1000, 0) = rafraîchir Qt.
        // Envoyé via ADB relay (uid=2000) car com.byd.myapp est bloqué par SecurityException Binder.
        // Chaîné : cmd=18 d'abord, puis cmd=0 dans le callback pour garantir l'ordre d'éxécution.
        AdbLocalClient.sendInfo(mContext, ClusterManager.CLUSTER_TYPE, ClusterManager.CMD_STOP_PROJECTION, "",
            new AdbLocalClient.Callback() {
                @Override public void onSuccess(String out) {
                    AppLogger.i(TAG, "stopProjection ADB(cmd=18): " + out);
                    AdbLocalClient.sendInfo(mContext, ClusterManager.CLUSTER_TYPE, ClusterManager.CMD_RESTORE_NATIVE, "",
                        new AdbLocalClient.Callback() {
                            @Override public void onSuccess(String o) { AppLogger.i(TAG, "restoreNative ADB(cmd=0): " + o); }
                            @Override public void onError(String e) { AppLogger.e(TAG, "restoreNative ADB ERREUR: " + e); }
                        });
                }
                @Override public void onError(String err) {
                    AppLogger.e(TAG, "stopProjection ADB ERREUR: " + err);
                }
            });        // Réinitialiser à -1 se fait dans start() — PAS ici.
        // Garder la sentinel -2 jusqu'au prochain start() pour bloquer les callbacks
        // ADB orphelins (thread background peut poster sur mHandler après cancel()).
    }

    /**
     * Variante de stop() sans envoi des commandes ADB de restauration.
     * À utiliser quand la restauration a déjà été faite en amont (ex: restoreBydDashboard)
     * pour éviter le double envoi de sendInfo(18+0).
     */
    public void stopWithoutAdb() {
        mKnownClusterDisplayId = -2;
        mClusterManager.cancel();
        mDisplayManager.unregisterDisplayListener(mDisconnectListener);
        AppLogger.i(TAG, "stopWithoutAdb — ADB déjà envoyé en amont");
    }

    public int getKnownClusterDisplayId() {
        return mKnownClusterDisplayId;
    }
}
