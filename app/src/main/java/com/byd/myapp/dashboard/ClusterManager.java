package com.byd.myapp.dashboard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import com.byd.myapp.AppLogger;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * ClusterManager — contrôle direct du cluster BYD Seal via le service Binder "AutoContainer".
 *
 * ARCHITECTURE (DiLink 3.0 / XDJA) :
 *   • Le service "AutoContainer" (android.os.IAutoContainer) est enregistré dans ServiceManager.
 *   • AutoContainerManager (getSystemService("auto_container")) vérifie la whitelist
 *     /system/etc/container_comm_cfg.json → uniquement "com.xdja.clusterdemo" autorisé.
 *   • MAIS : l'appel Binder direct bypasse ce check Java (confirmé TEST 8 — retour 00000000).
 *   • On accède au Binder via ServiceManager.getService("AutoContainer") par réflexion.
 *
 * AIDL IAutoContainer (transactions) :
 *   #1 sendJson(int type, String json)
 *   #2 sendInfo(int type, int infoInt, String infoStr)  ← utilisé ici
 *   #3 sendInfo2(int type, byte[] data)
 *   #4 registerCallback(IAutoContainerCallback cb)
 *
 * COMMANDES CLUSTER (type=1000) :
 *   infoInt=16  → mode projection plein écran : Qt entre en standby et laisse display 1 enregistré
 *                  dans IActivityManager. Header/footer Qt restent visibles SAUF si
 *                  setLaunchWindowingMode(FULLSCREEN=1) est utilisé → app couvre tout.
 *                  C'EST LA BONNE COMMANDE pour lancer une app sur display 1.
 *   infoInt= 1  → déconnecte Qt ENTIÈREMENT → MCU prend le contrôle (Simple mode)
 *                  display 1 DISPARAIT d'IActivityManager → NE PAS UTILISER pour lancer des apps
 *   infoInt= 0  → restaurer rendu BYD natif
 */
public class ClusterManager {

    private static final String TAG = "ClusterManager";

    // Nom exact dans ServiceManager (case-sensitive, confirmé par `service list`)
    public static final String SERVICE_NAME = "AutoContainer";
    // Token AIDL (interface descriptor)
    private static final String INTERFACE_TOKEN = "android.os.IAutoContainer";

    // Numéros de transaction AIDL
    private static final int TX_SEND_INFO = 2;

    // Paramètres sendInfo(type, infoInt, infoStr)
    public static final int CLUSTER_TYPE      = 1000;
    public static final int CMD_PROJECTION_ON  = 16;   // Qt standby + display 1 reste dans IActivityManager
    // CMD=1 (Qt déconnecté complètement / Simple mode) NON EXPOSé : destroy display 1 — ne jamais utiliser
    public static final int CMD_STOP_PROJECTION = 18;  // 投屏关闭 — FERMER la projection (correct pour restauration)
    public static final int CMD_RESTORE_NATIVE = 0;    // 主机恢复仪表视频流 — rafraîchir le flux Qt (après cmd 18)
    // Commandes ADAS (confirmées com.byd.clusterdebug SecondActivity)
    // ATTENTION : ces commandes n'ont pas d'effet visible sur le cluster 2D Seal EU.
    // Elles semblent cibler un modèle de cluster 3D différent.
    public static final int CMD_ADAS_SHOW = 12;   // 显示Adas — sans effet sur Seal EU 2D
    public static final int CMD_ADAS_HIDE = 13;   // 关闭Adas — sans effet sur Seal EU 2D

    // Timeout d'attente du VirtualDisplay après sendInfo(projection_on)
    // Réduit à 3s : le VirtualDisplay est présent au boot (AutoDisplayService), n'a pas besoin de 8s.
    private static final long VIRTUAL_DISPLAY_TIMEOUT_MS = 3000;
    // Polling interval pour détecter le virtual display
    private static final long POLL_INTERVAL_MS = 500;

    // ─────────────────────────────────────────────────────────────────────────

    /** Notifié quand le VirtualDisplay cluster devient disponible (ou timeout). */
    public interface DisplayReadyCallback {
        void onDisplayReady(Display display, int displayId);
        void onDisplayTimeout();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final Context mContext;
    private IBinder mBinder;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Référence au DisplayListener en cours d'activateClusterDisplay(), afin que cancel()
    // puisse le désinscrire même si aucun display n'est jamais apparu.
    private DisplayManager.DisplayListener mActiveDisplayListener = null;
    private DisplayManager               mActiveDisplayManager   = null;

    public ClusterManager(Context context) {
        mContext = context.getApplicationContext();
    }

    // ── Binder ──────────────────────────────────────────────────────────────

    /**
     * Obtient le Binder AutoContainer via réflexion ServiceManager.
     * @return true si le service est disponible.
     */
    public boolean connect() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getDeclaredMethod("getService", String.class);
            mBinder = (IBinder) getService.invoke(null, SERVICE_NAME);
            if (mBinder == null) {
                AppLogger.w(TAG, "ServiceManager.getService(\"" + SERVICE_NAME + "\") = null");
                return false;
            }
            AppLogger.i(TAG, "AutoContainer Binder obtenu : " + mBinder);
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "connect() échec", e);
            return false;
        }
    }

    /**
     * Envoi d'une commande sendInfo (transaction #2) au service AutoContainer.
     *
     * @param type     toujours 1000 pour le cluster
     * @param infoInt  commande : CMD_PROJECTION_ON(16), CMD_RESTORE_NATIVE(0)
     * @param infoStr  payload string (généralement "")
     * @return true si l'appel Binder a réussi (pas d'exception, pas de RemoteException)
     */
    public boolean sendInfo(int type, int infoInt, String infoStr) {
        if (mBinder == null && !connect()) {
            AppLogger.e(TAG, "sendInfo : Binder non disponible");
            return false;
        }
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN);
            data.writeInt(type);
            data.writeInt(infoInt);
            data.writeString(infoStr != null ? infoStr : "");
            mBinder.transact(TX_SEND_INFO, data, reply, 0);
            // Lecture de l'exception distante (lance une exception Java si le Binder a retourné une erreur)
            reply.readException();
            AppLogger.i(TAG, "sendInfo(" + type + ", " + infoInt + ", \"" + infoStr + "\") OK");
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "sendInfo(" + type + ", " + infoInt + ") échec", e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ── Commandes haut niveau ────────────────────────────────────────────────

    /** Met le cluster en mode projection plein écran. */
    public boolean enterProjectionMode() {
        return sendInfo(CLUSTER_TYPE, CMD_PROJECTION_ON, "");
    }

    /** Ferme proprement le mode projection (投屏关闭) — Qt reprend le contrôle du display. */
    public boolean stopProjection() {
        return sendInfo(CLUSTER_TYPE, CMD_STOP_PROJECTION, "");
    }

    /** Rafraîchit le flux vidéo Qt (主机恢复仪表视频流) — à appeler après stopProjection(). */
    public boolean restoreNative() {
        return sendInfo(CLUSTER_TYPE, CMD_RESTORE_NATIVE, "");
    }

    /** Masque l'overlay ADAS Qt — sans effet visible sur cluster 2D Seal EU. */
    public boolean hideAdas() {
        return sendInfo(CLUSTER_TYPE, CMD_ADAS_HIDE, "");
    }

    /** Restaure l'overlay ADAS Qt — sans effet visible sur cluster 2D Seal EU. */
    public boolean showAdas() {
        return sendInfo(CLUSTER_TYPE, CMD_ADAS_SHOW, "");
    }

    // ── Activation + attente du VirtualDisplay ───────────────────────────────

    /**
     * Séquence complète :
     *   1. Vérifie d'abord DISPLAY_CATEGORY_PRESENTATION (VirtualDisplay présent au boot)
     *   2. Si trouvé → sendInfo(16) pour mettre Qt en standby → callback immédiat
     *   3. Si non trouvé → sendInfo(16) → écoute DisplayManager + polling court (3s)
     *   4. Timeout → onDisplayTimeout() → DashboardDisplayHelper fait le fallback displayId=1
     *
     * ARCHITECTURE CONFIRMÉE (analyse Freedom v1.9 + com.xdja.containerservice) :
     *   AutoDisplayService crée le VirtualDisplay cluster au BOOT :
     *     createVirtualDisplay("fission_testVirtualSurface", 1920, 1080, 320, qtSurface, 11)
     *     flags 11 = PUBLIC | PRESENTATION | OWN_CONTENT_ONLY
     *   → Le display est visible via DISPLAY_CATEGORY_PRESENTATION AVANT tout appel sendInfo.
     *   → sendInfo(1000, 16) ne crée PAS le display : il met seulement Qt en standby
     *     (libère la surface pour notre rendu Android).
     *   → sendInfo(1000, 0) seul suffit à restaurer le cluster sur Seal EU
     *     (Freedom confirme : pas besoin de relancer com.byd.automap, non installé).
     *
     * CONFIRMATION TEST 10 (11/04/2026) :
     *   cmd=1 = MAUVAISE COMMANDE : Qt se déconnecte entièrement → MCU reprend le contrôle
     *   (Simple mode visible) → display 1 DISPARAIT d'IActivityManager → lancement impossible.
     *   cmd=16 = BONNE COMMANDE : Qt entre en standby, display 1 reste enregistré.
     *
     * La callback est appelée sur le main thread.
     */
    public void activateClusterDisplay(final DisplayReadyCallback callback) {
        final DisplayManager dm = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);

        // 1. Vérifier d'abord si le VirtualDisplay cluster est déjà présent (DISPLAY_CATEGORY_PRESENTATION)
        //    AutoDisplayService le crée au BOOT → disponible immédiatement sans attente.
        Display found = findClusterDisplay(dm);
        if (found != null) {
            Log.i(TAG, "VirtualDisplay cluster présent au boot : id=" + found.getDisplayId()
                    + " name=" + found.getName());
            // NE PAS appeler enterProjectionMode() ici.
            // La séquence correcte (confirmée TEST 10) est :
            //   état BYD natif  →  sendInfo(16)  →  2 s d'attente  →  lancement app.
            // Si sendInfo(16) est envoyé une 2ème fois depuis launchOnDashboard alors
            // que le cluster est DÉJÀ en mode projection (appel ici + appel launchOnDashboard),
            // Qt toggle brevité active/standby et le timing 1,5 s devient insuffisant.
            // launchOnDashboard() se charge d'appeler enterProjectionMode() juste avant le lancement.
            callback.onDisplayReady(found, found.getDisplayId());
            return;
        }

        // Display non trouvé immédiatement — sendInfo(16) d'abord, puis écoute
        AppLogger.w(TAG, "VirtualDisplay non trouvé immédiatement — sendInfo(16) + polling");
        boolean ok = enterProjectionMode();
        AppLogger.i(TAG, "enterProjectionMode (cmd=16) : " + (ok ? "OK" : "ÉCHEC"));

        // Écouter les ajouts de display + timeout
        final long[] pollCount = {0};
        final DisplayManager.DisplayListener[] listenerHolder = new DisplayManager.DisplayListener[1];

        listenerHolder[0] = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {
                Display d = dm.getDisplay(displayId);
                Log.i(TAG, "onDisplayAdded id=" + displayId + " display=" + d);
                if (isClusterDisplay(dm, d)) {
                    mHandler.removeCallbacksAndMessages(null);
                    dm.unregisterDisplayListener(listenerHolder[0]);
                    mActiveDisplayListener = null;
                    mActiveDisplayManager  = null;
                    Log.i(TAG, "VirtualDisplay cluster détecté : id=" + displayId);
                    callback.onDisplayReady(d, displayId);
                }
            }
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {}
        };
        mActiveDisplayManager  = dm;
        mActiveDisplayListener = listenerHolder[0];
        dm.registerDisplayListener(listenerHolder[0], mHandler);

        // Polling supplémentaire : parfois onDisplayAdded n'est pas déclenché pour les VirtualDisplays
        // créés dans un autre processus (binder cross-process)
        scheduleDisplayPoll(dm, listenerHolder, callback, pollCount, 0);

        // Timeout global
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                dm.unregisterDisplayListener(listenerHolder[0]);
                mActiveDisplayListener = null;
                mActiveDisplayManager  = null;
                mHandler.removeCallbacksAndMessages(null);
                AppLogger.w(TAG, "Timeout : VirtualDisplay cluster non détecté après "
                        + VIRTUAL_DISPLAY_TIMEOUT_MS + "ms");
                callback.onDisplayTimeout();
            }
        }, VIRTUAL_DISPLAY_TIMEOUT_MS);
    }

    private void scheduleDisplayPoll(
            final DisplayManager dm,
            final DisplayManager.DisplayListener[] listenerHolder,
            final DisplayReadyCallback callback,
            final long[] pollCount,
            long delayMs) {
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                pollCount[0]++;
                if (pollCount[0] * POLL_INTERVAL_MS >= VIRTUAL_DISPLAY_TIMEOUT_MS) return;

                Display found = findClusterDisplay(dm);
                if (found != null) {
                    mHandler.removeCallbacksAndMessages(null);
                    dm.unregisterDisplayListener(listenerHolder[0]);
                    mActiveDisplayListener = null;
                    mActiveDisplayManager  = null;
                    Log.i(TAG, "VirtualDisplay trouvé par polling : id=" + found.getDisplayId());
                    callback.onDisplayReady(found, found.getDisplayId());
                } else {
                    scheduleDisplayPoll(dm, listenerHolder, callback, pollCount, POLL_INTERVAL_MS);
                }
            }
        }, delayMs == 0 ? POLL_INTERVAL_MS : delayMs);
    }

    // ── Détection du display cluster ─────────────────────────────────────────

    /**
     * Cherche un display qui ressemble au VirtualDisplay cluster parmi TOUS les displays.
     * On cherche soit PRESENTATION, soit VIRTUAL non-default, car le VirtualDisplay
     * de AutoDisplayService peut avoir n'importe quelle catégorie selon la version ROM.
     */
    private Display findClusterDisplay(DisplayManager dm) {
        // Stratégie 1 : displays de catégorie PRESENTATION
        Display[] presentations = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (presentations != null) {
            for (Display d : presentations) {
                if (d.getDisplayId() != 0) {
                    Log.d(TAG, "Candidat PRESENTATION : id=" + d.getDisplayId() + " name=" + d.getName());
                    return d;
                }
            }
        }
        // Stratégie 2 : n'importe quel display non-default (id != 0)
        Display[] all = dm.getDisplays();
        if (all != null) {
            for (Display d : all) {
                if (d.getDisplayId() != 0) {
                    Log.d(TAG, "Candidat non-default : id=" + d.getDisplayId() + " name=" + d.getName());
                    return d;
                }
            }
        }
        return null;
    }

    private boolean isClusterDisplay(DisplayManager dm, Display d) {
        // Un display est considéré cluster si ce n'est pas le display principal (id=0)
        return d != null && d.getDisplayId() != 0;
    }

    // ── Annulation ──────────────────────────────────────────────────────────

    /**
     * Annule toutes les opérations en cours : polls Handler, timeout, et DisplayListener.
     * DOIT être appelé par DashboardDisplayHelper.stop().
     */
    public void cancel() {
        mHandler.removeCallbacksAndMessages(null);
        if (mActiveDisplayManager != null && mActiveDisplayListener != null) {
            mActiveDisplayManager.unregisterDisplayListener(mActiveDisplayListener);
            mActiveDisplayListener = null;
            mActiveDisplayManager  = null;
        }
        Log.d(TAG, "cancel() — Handler et DisplayListener annulés");
    }
}
