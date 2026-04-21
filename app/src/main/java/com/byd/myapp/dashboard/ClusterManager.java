package com.byd.myapp.dashboard;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import com.byd.myapp.AdbLocalClient;
import com.byd.myapp.AppLogger;
import com.byd.myapp.MainActivity;
import android.view.Display;

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
 * COMMANDES CLUSTER (type=1000) — CONFIRMÉES EN VOITURE (13/04/2026 + 16/04/2026, BYD Seal EU) :
 *
 *   infoInt=30  → 切换到12.3寸屏 = PASSER EN MODE Seal EU (bonne résolution) :
 *                  DOIT être envoyé AVANT infoInt=16 sur Seal EU.
 *                  Corrige le bug fenêtre ADAS et l'étirement UI.
 *                  Séquence complète : sendInfo(30) → attente 1s → sendInfo(16) → attente 2s → startActivity.
 *
 *   infoInt=16  → 全屏投屏开启 = ACTIVER projection plein écran :
 *                  Qt entre en standby, display 1 reste enregistré dans IActivityManager.
 *                  C'EST LA BONNE COMMANDE pour lancer une app sur display 1.
 *                  Séquence : sendInfo(30) → sendInfo(16) → attente 2s → startActivity sur display 1.
 *
 *   infoInt=18  → 投屏关闭 = FERMER la projection :
 *                  C'EST LA BONNE COMMANDE de restauration (cmd=0 seul ne suffit PAS).
 *                  Séquence : finishIfActive() → sendInfo(18) → sendInfo(0).
 *
 *   infoInt= 0  → 主机恢复付表视频流 = rafraîchir le flux vidéo Qt.
 *                  À appeler APRÈS sendInfo(18) pour compléter la restauration.
 *
 *   infoInt= 1  → déconnecte Qt ENTiÈREMENT → MCU prend le contrôle (Simple mode)
 *                  display 1 DISPARAIT d'IActivityManager → NE PAS UTILISER pour lancer des apps
 *
 *   infoInt=12  → 显示Adas — SANS EFFET sur cluster 2D Seal EU (prévu pour cluster 3D)
 *   infoInt=13  → 关闭Adas — SANS EFFET sur cluster 2D Seal EU (prévu pour cluster 3D)
 */
public class ClusterManager {

    private static final String TAG = "ClusterManager";

    // Nom exact dans ServiceManager (case-sensitive, confirmé par `service list`)
    public static final String SERVICE_NAME = "AutoContainer";

    // Paramètres sendInfo(type, infoInt, infoStr)
    public static final int CLUSTER_TYPE      = 1000;
    public static final int CMD_PROJECTION_ON   = 16;  // 全屏投屏开启 — ACTIVER projection (CONFIRMÉ 13/04/2026)
    public static final int CMD_STOP_PROJECTION  = 18;  // 投屏关闭 — FERMER la projection (CONFIRMÉ 13/04/2026)
    public static final int CMD_RESTORE_NATIVE   = 0;   // 主机恢复付表视频流 — rafraîchir flux Qt (après cmd 18)
    // CMD=1 : déconnecte Qt complètement — NE JAMAIS UTILISER (détruit display 1)
    // Commandes taille d'écran cluster (DiLink 3.0/Di4.0) :
    public static final int CMD_SCREEN_SIZE_SEAL_EU  = 30; // 切换到12.3寸屏 — BYD Seal EU (CONFIRMÉ 16/04/2026)
    public static final int CMD_SCREEN_SIZE_88       = 29; // 切换到8.8寸屏  — Atto3/Dolphin etc.
    public static final int CMD_SCREEN_SIZE_1025     = 31; // 切换到10.25寸屏 — autres modèles
    // Timeout d'attente du VirtualDisplay après sendInfo(projection_on)
    // Réduit à 3s : le VirtualDisplay est présent au boot (AutoDisplayService), n'a pas besoin de 8s.
    private static final long VIRTUAL_DISPLAY_TIMEOUT_MS = 3000;
    // Timeout étendu quand Freedom n'est pas encore actif (démarrage + création display ~5s)
    private static final long FREEDOM_STARTUP_TIMEOUT_MS = 12000;
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
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Référence au DisplayListener en cours d'activateClusterDisplay(), afin que cancel()
    // puisse le désinscrire même si aucun display n'est jamais apparu.
    private DisplayManager.DisplayListener mActiveDisplayListener = null;
    private DisplayManager               mActiveDisplayManager   = null;

    public ClusterManager(Context context) {
        mContext = context.getApplicationContext();
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
    public void activateClusterDisplay(final boolean freedomJustStarted,
            final DisplayReadyCallback callback) {
        final DisplayManager dm = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);

        // 1. Vérifier d'abord si le VirtualDisplay cluster est déjà présent (DISPLAY_CATEGORY_PRESENTATION)
        //    AutoDisplayService le crée au BOOT → disponible immédiatement sans attente.
        Display found = findClusterDisplay(dm);
        if (found != null) {
            AppLogger.i(TAG, "VirtualDisplay cluster présent au boot : id=" + found.getDisplayId()
                    + " name=" + found.getName());
            // Séquence Seal EU (CONFIRMÉE 16/04/2026) :
            //   1. sendInfo(1000, 30) — passer cluster en mode Seal EU (12.3") → résolution correcte
            //   2. sendInfo(1000, 16) — Qt standby → on peut afficher notre app
            // sendInfo via ADB relay (uid=2000) — le Binder direct échoue :
            //   com.byd.myapp absent de container_comm_cfg.json → SecurityException.
            final Display displayFound = found;
            AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_SCREEN_SIZE_SEAL_EU, "",
                new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String out) {
                        AppLogger.i(TAG, "activateCluster ADB(cmd=30, Seal EU screen) : " + out);
                        // Attendre 1s que le cluster adopte la nouvelle résolution
                        mHandler.postDelayed(new Runnable() {
                            @Override public void run() {
                                AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
                                    new AdbLocalClient.Callback() {
                                        @Override public void onSuccess(String out2) {
                                            AppLogger.i(TAG, "activateCluster ADB(cmd=16) : " + out2);
                                            mHandler.post(new Runnable() {
                                                @Override public void run() {
                                                    callback.onDisplayReady(displayFound, displayFound.getDisplayId());
                                                }
                                            });
                                        }
                                        @Override public void onError(String err) {
                                            AppLogger.e(TAG, "activateCluster ADB(cmd=16) ERREUR: " + err);
                                            mHandler.post(new Runnable() {
                                                @Override public void run() {
                                                    callback.onDisplayReady(displayFound, displayFound.getDisplayId());
                                                }
                                            });
                                        }
                                    });
                            }
                        }, 1000);
                    }
                    @Override public void onError(String err) {
                        AppLogger.e(TAG, "activateCluster ADB(cmd=30) ERREUR: " + err);
                        // Même en cas d'erreur cmd=30, on tente cmd=16
                        AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
                            new AdbLocalClient.Callback() {
                                @Override public void onSuccess(String out2) {
                                    AppLogger.i(TAG, "activateCluster ADB(cmd=16) fallback : " + out2);
                                    mHandler.post(new Runnable() {
                                        @Override public void run() {
                                            callback.onDisplayReady(displayFound, displayFound.getDisplayId());
                                        }
                                    });
                                }
                                @Override public void onError(String err2) {
                                    AppLogger.e(TAG, "activateCluster ADB(cmd=16) fallback ERREUR: " + err2);
                                    mHandler.post(new Runnable() {
                                        @Override public void run() {
                                            callback.onDisplayReady(displayFound, displayFound.getDisplayId());
                                        }
                                    });
                                }
                            });
                    }
                });
            return;
        }

        // Display non trouvé immédiatement — démarrer Freedom si absent, puis sendInfo(30+16)
        AppLogger.w(TAG, "VirtualDisplay non trouvé — démarrage Freedom + sendInfo(30+16) ADB + polling");

        // Timeout étendu : Freedom peut prendre ~5s à créer le display au premier démarrage.
        final long timeoutMs = FREEDOM_STARTUP_TIMEOUT_MS;

        // 1. Démarrer Freedom (com.xdja.clusterdemo) si absent → crée le VirtualDisplay cluster.
        //    Si freedomJustStarted=true : ClusterService l'a déjà lancé, on ne refait pas un
        //    force-stop/restart (il serait en cours de démarrage → race condition).
        if (freedomJustStarted) {
            AppLogger.i(TAG, "activateClusterDisplay : Freedom déjà lancé par ClusterService — skip startFreedom()");
            // Envoyer quand même sendInfo(30+16) pour libérer la surface Qt
            mHandler.postDelayed(new Runnable() {
                @Override public void run() { sendActivationSequence(); }
            }, 2000);
        } else {
            AdbLocalClient.startFreedom(mContext, new AdbLocalClient.Callback() {
                @Override public void onSuccess(String result) {
                    AppLogger.i(TAG, "startFreedom : " + result.trim().replace("\n", " "));

                    // Ramener notre app au premier plan 1s après le démarrage de Freedom
                    mHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                Intent front = new Intent(mContext, MainActivity.class);
                                front.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                             | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                                mContext.startActivity(front);
                                AppLogger.i(TAG, "startFreedom : retour au premier plan");
                            } catch (Exception e) {
                                AppLogger.w(TAG, "startFreedom : impossible de revenir au 1er plan : " + e.getMessage());
                            }
                        }
                    }, 1000);

                    // Après 3s : envoyer sendInfo(30+16) pour activer le cluster
                    mHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            sendActivationSequence();
                        }
                    }, 3000);
                }
                @Override public void onError(String err) {
                    AppLogger.w(TAG, "startFreedom ERREUR (on continue quand même) : " + err);
                    sendActivationSequence();
                }
            });
        }

        // Écouter les ajouts de display + timeout
        final long[] pollCount = {0};
        final DisplayManager.DisplayListener[] listenerHolder = new DisplayManager.DisplayListener[1];

        listenerHolder[0] = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {
                Display d = dm.getDisplay(displayId);
                AppLogger.i(TAG, "onDisplayAdded id=" + displayId + " display=" + d);
                if (isClusterDisplay(d)) {
                    mHandler.removeCallbacksAndMessages(null);
                    dm.unregisterDisplayListener(listenerHolder[0]);
                    mActiveDisplayListener = null;
                    mActiveDisplayManager  = null;
                    AppLogger.i(TAG, "VirtualDisplay cluster détecté : id=" + displayId);
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

        // Timeout global — étendu (FREEDOM_STARTUP_TIMEOUT_MS) car Freedom doit démarrer
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                dm.unregisterDisplayListener(listenerHolder[0]);
                mActiveDisplayListener = null;
                mActiveDisplayManager  = null;
                mHandler.removeCallbacksAndMessages(null);
                AppLogger.w(TAG, "Timeout : VirtualDisplay cluster non détecté après "
                        + timeoutMs + "ms");
                callback.onDisplayTimeout();
            }
        }, timeoutMs);
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
                if (pollCount[0] * POLL_INTERVAL_MS >= FREEDOM_STARTUP_TIMEOUT_MS) return;

                Display found = findClusterDisplay(dm);
                if (found != null) {
                    mHandler.removeCallbacksAndMessages(null);
                    dm.unregisterDisplayListener(listenerHolder[0]);
                    mActiveDisplayListener = null;
                    mActiveDisplayManager  = null;
                    AppLogger.i(TAG, "VirtualDisplay trouvé par polling : id=" + found.getDisplayId());
                    callback.onDisplayReady(found, found.getDisplayId());
                } else {
                    scheduleDisplayPoll(dm, listenerHolder, callback, pollCount, POLL_INTERVAL_MS);
                }
            }
        }, delayMs == 0 ? POLL_INTERVAL_MS : delayMs);
    }

    // ── Séquence d'activation sendInfo(30 → 16) ──────────────────────────────

    /**
     * Envoie sendInfo(30) puis sendInfo(16) via ADB relay.
     * Utilisé à la fois par le fast path (Freedom déjà actif → depuis ClusterService)
     * et par le slow path (Freedom vient d'être lancé → depuis activateClusterDisplay).
     * Le callback DisplayReadyCallback n'est PAS appelé ici : c'est le DisplayListener /
     * polling qui le déclenche quand le VirtualDisplay apparaît.
     */
    private void sendActivationSequence() {
        AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_SCREEN_SIZE_SEAL_EU, "",
            new AdbLocalClient.Callback() {
                @Override public void onSuccess(String out) {
                    AppLogger.i(TAG, "slow path ADB(cmd=30) : " + out);
                    mHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
                                new AdbLocalClient.Callback() {
                                    @Override public void onSuccess(String out2) { AppLogger.i(TAG, "slow path ADB(cmd=16) : " + out2); }
                                    @Override public void onError(String err)    { AppLogger.e(TAG, "slow path ADB(cmd=16) ERREUR: " + err); }
                                });
                        }
                    }, 1000);
                }
                @Override public void onError(String err) {
                    AppLogger.e(TAG, "slow path ADB(cmd=30) ERREUR: " + err);
                    AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
                        new AdbLocalClient.Callback() {
                            @Override public void onSuccess(String out2) { AppLogger.i(TAG, "slow path ADB(cmd=16) fallback : " + out2); }
                            @Override public void onError(String err2)   { AppLogger.e(TAG, "slow path ADB(cmd=16) fallback ERREUR: " + err2); }
                        });
                }
            });
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
                    AppLogger.d(TAG, "Candidat PRESENTATION : id=" + d.getDisplayId() + " name=" + d.getName());
                    return d;
                }
            }
        }
        // Stratégie 2 : n'importe quel display non-default (id != 0)
        Display[] all = dm.getDisplays();
        if (all != null) {
            for (Display d : all) {
                if (d.getDisplayId() != 0) {
                    AppLogger.d(TAG, "Candidat non-default : id=" + d.getDisplayId() + " name=" + d.getName());
                    return d;
                }
            }
        }
        return null;
    }

    private boolean isClusterDisplay(Display d) {
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
        AppLogger.d(TAG, "cancel() — Handler et DisplayListener annulés");
    }
}
