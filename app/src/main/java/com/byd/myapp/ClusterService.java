package com.byd.myapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.view.Display;

import com.byd.myapp.dashboard.ClusterInputForwarder;
import com.byd.myapp.dashboard.ClusterMirrorManager;
import com.byd.myapp.dashboard.DashboardDisplayHelper;
import com.byd.myapp.dashboard.DashboardLauncher;

/**
 * ClusterService — Foreground Service qui maintient la projection sur le cluster
 * indépendamment du cycle de vie de MainActivity.
 *
 * L'utilisateur peut mettre l'app en background (revenir sur l'écran principal BYD,
 * utiliser d'autres apps) sans que la projection cluster soit interrompue.
 *
 * Lifecycle :
 *   - Démarré par MainActivity.onCreate() via startForegroundService()
 *   - MainActivity se bind/unbind en onStart()/onStop() pour accéder aux données
 *   - Le service continue de tourner tant que stopSelf() n'a pas été appelé
 *   - stopProjection() est appelé explicitement (bouton Restaurer BYD ou destruction de l'app)
 *
 * Communication avec MainActivity :
 *   - LocalBinder.getService() retourne l'instance du service
 *   - MainActivity implémente ClusterService.Listener pour les callbacks display
 */
public class ClusterService extends Service implements DashboardDisplayHelper.Listener {

    private static final String TAG = "ClusterService";
    private static final String CHANNEL_ID = "cluster_projection";
    private static final int NOTIF_ID = 1;

    // ── Listener pour MainActivity ──────────────────────────────────────────
    public interface Listener {
        void onClusterDisplayConnected(Display display, int displayId);
        void onClusterDisplayDisconnected();
        /** État de Freedom vérifié au démarrage du service (appelé sur le main thread). */
        void onFreedomStatus(AdbLocalClient.FreedomStatus status);
    }

    // ── Binder ──────────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public ClusterService getService() { return ClusterService.this; }
    }

    private final IBinder mBinder = new LocalBinder();

    // ── État ────────────────────────────────────────────────────────────────
    private DashboardDisplayHelper mDisplayHelper;
    private DashboardLauncher      mLauncher;
    private ClusterMirrorManager   mMirrorManager;
    private ClusterInputForwarder  mInputForwarder;
    private Listener               mListener;
    private boolean                mProjectionActive = false;
    // Dernier état Freedom connu — mis en cache pour replay dans setListener()
    private AdbLocalClient.FreedomStatus mFreedomStatus = null;
    // Handler réutilisable sur le main thread (remplace les new Handler() éphémères).
    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        mDisplayHelper  = new DashboardDisplayHelper(this, this);
        mLauncher       = new DashboardLauncher(this);
        mMirrorManager  = new ClusterMirrorManager();
        mInputForwarder = new ClusterInputForwarder(this);
        
        // Prédémarrer le MirrorDaemon (app_process via ADB) pour le Real-Time Cluster Mirror + Touch
        // Executé ici au lieu de MainActivity pour éviter de le relancer à chaque rotation d'écran.
        AdbLocalClient.startMirrorDaemon(this);
        
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Cluster : initialisation…"));
        AppLogger.log(TAG, "ClusterService créé — vérification état Freedom");
        mProjectionActive = true;
        // Diagnostic signatures + permissions — debug uniquement (ouvre une connexion ADB).
        if (BuildConfig.DEBUG) {
            AdbLocalClient.dumpSignatureAndPermissions(this);
        }
        checkAndStartWithFreedom();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY : le système recrée le service s'il est tué par manque de mémoire
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Garder le listener null pour éviter les leaks si MainActivity est détruite.
        // return false : chaque nouveau bindService() passe par onBind() normalement.
        mListener = null;
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mListener = null;
        // Annuler tous les Runnable en attente sur mMainHandler AVANT release() :
        // le launchOnDashboard (postDelayed 2s) pourrait poster une callback
        // sur un service détruit (NPE / leak de thread ADB).
        mMainHandler.removeCallbacksAndMessages(null);
        // Arrêter le miroir SurfaceControl si actif.
        mMirrorManager.stopMirror(this);
        if (mProjectionActive) {
            mDisplayHelper.stop();
        }
        AppLogger.log(TAG, "ClusterService détruit");
    }

    // ── API publique (appelée depuis MainActivity via le binder) ────────────

    /**
     * Vérifie l'état de Freedom via ADB, puis :
     *   ACTIVE      → mDisplayHelper.start() directement
     *   INACTIVE    → startFreedom() (force-stop + navigationType=1 + am start) → délai 2s → start()
     *   NOT_INSTALLED → mDisplayHelper.start() quand même (let activateClusterDisplay gérer le fallback)
     */
    private void checkAndStartWithFreedom() {
        AppLogger.i(TAG, "Freedom : vérification état avant activation cluster");
        AdbLocalClient.checkFreedomState(this, new AdbLocalClient.FreedomStateCallback() {
            @Override public void onResult(final AdbLocalClient.FreedomStatus status) {
                mMainHandler.post(new Runnable() {
                    @Override public void run() {
                        mFreedomStatus = status;
                        AppLogger.i(TAG, "Freedom state: " + status);
                        if (mListener != null) mListener.onFreedomStatus(status);
                        if (status == AdbLocalClient.FreedomStatus.INACTIVE) {
                            // Freedom installé mais inactif → démarrer en mode 全屏導航 d'abord
                            AppLogger.i(TAG, "Freedom INACTIVE → startFreedom() avant activation cluster");
                            AdbLocalClient.startFreedom(ClusterService.this, true, new AdbLocalClient.Callback() {
                                @Override public void onSuccess(String r) {
                                    AppLogger.i(TAG, "startFreedom pré-check OK → " + r.trim().replace("\n", " "));
                                    // Délai 2s : laisser Freedom créer le VirtualDisplay fission
                                    mMainHandler.postDelayed(new Runnable() {
                                        @Override public void run() { mDisplayHelper.start(true); }
                                    }, 2000);
                                }
                                @Override public void onError(String err) {
                                    AppLogger.w(TAG, "startFreedom pré-check ERREUR (on continue) : " + err);
                                    mDisplayHelper.start();
                                }
                            });
                        } else {
                            // ACTIVE (fast path) ou NOT_INSTALLED (fallback dans activateClusterDisplay)
                            mDisplayHelper.start();
                        }
                    }
                });
            }
        });
    }

    public void setListener(Listener listener) {
        mListener = listener;
        // Rejouer l'état Freedom en cache si disponible (check lancé avant le bind)
        if (mFreedomStatus != null && mListener != null) {
            mListener.onFreedomStatus(mFreedomStatus);
        }
        // Si le display est déjà connu, notifier immédiatement (reconnexion de l'Activity)
        int knownId = mDisplayHelper.getKnownClusterDisplayId();
        if (knownId > 0 && mListener != null) {
            Display d = null;
            try {
                android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                    getSystemService(DISPLAY_SERVICE);
                if (dm != null) d = dm.getDisplay(knownId);
            } catch (Exception ignored) {}
            mListener.onClusterDisplayConnected(d, knownId);
        }
    }

    public DashboardLauncher getLauncher() {
        return mLauncher;
    }

    public ClusterMirrorManager getMirrorManager() {
        return mMirrorManager;
    }

    public ClusterInputForwarder getInputForwarder() {
        return mInputForwarder;
    }

    public interface LaunchCallback {
        void onResult(boolean success);
    }

    /**
     * Lance une app sur le cluster.
     * Séquence d'activation :
     *   1. sendInfo(1000, 30) — Seal EU screen size (CONFIRMÉ 16/04/2026)
     *   2. sendInfo(1000, 16) — Qt standby
     * Ces deux commandes sont déjà envoyées par activateClusterDisplay() au démarrage du service.
     * Ce méthode ajoute le délai post-activation puis lance l'app.
     * La callback est appelée sur le main thread.
     */
    public void launchOnDashboard(final String packageName, final LaunchCallback callback) {
        // sendInfo(16) déjà envoyé par activateClusterDisplay() — ne pas rappeler ici
        // (risque de toggle Qt si cmd=16 n'est pas idempotent).
        // Pour le chemin direct (tap app sans passage par activateCluster),
        // activateClusterDisplay() a été appelé lors du démarrage du service → Qt déjà en standby.
        AppLogger.log(TAG, "launchOnDashboard — délai 2s → " + packageName);
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                // v1.73+ : lancement direct via ADB shell (uid=2000) qui possède
                // INTERNAL_SYSTEM_WINDOW sur cette ROM. ADB shell lance notre trampoline
                // exporté sur display 1 avec --es target_package=<tier>, le trampoline
                // démarre ensuite le tiers depuis son propre contexte (display 1).
                //
                // On NE PASSE PLUS par mLauncher.launchOnDashboard() (Context.startActivity)
                // qui échoue toujours faute de INTERNAL_SYSTEM_WINDOW (mismatch keystore
                // confirmé par dump v1.72 : notre sig=b4addb29 ≠ ROM sig=22216e4d).
                final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                AppLogger.i(TAG, "Lancement direct DEPUIS l'appli (uid=10xxx) via IActivityManager sur display=" + displayId + " -> " + packageName);
                try {
                    android.content.Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        AppLogger.e(TAG, "Aucun intent de lancement trouvé pour " + packageName);
                        return;
                    }
                    launchIntent.addFlags(0x10008000); // NEW_TASK | CLEAR_TASK
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(displayId);
                    
                    try {
                        Class<?> amClass = Class.forName("android.app.ActivityManager");
                        java.lang.reflect.Method getServiceMethod = amClass.getMethod("getService");
                        Object iActivityManager = getServiceMethod.invoke(null);
                        
                        Class<?> iAmClass = Class.forName("android.app.IActivityManager");
                        Class<?> iAppThreadClass = Class.forName("android.app.IApplicationThread");
                        Class<?> profilerInfoClass = Class.forName("android.app.ProfilerInfo");
                        java.lang.reflect.Method startActivityAsUserMethod = iAmClass.getMethod("startActivityAsUser", iAppThreadClass, String.class, android.content.Intent.class, String.class, android.os.IBinder.class, String.class, int.class, int.class, profilerInfoClass, android.os.Bundle.class, int.class);
                        
                        AppLogger.i(TAG, "Calling IActivityManager.startActivityAsUser avec callerPackage=" + getPackageName());
                        startActivityAsUserMethod.invoke(iActivityManager, null, getPackageName(), launchIntent, null, null, null, 0, 0, null, opts.toBundle(), -2); // UserHandle.USER_CURRENT = -2
                    } catch (Exception ex) {
                        AppLogger.e(TAG, "Erreur appel IActivityManager depuis MyBYDApp, fallback context", ex);
                        startActivity(launchIntent, opts.toBundle());
                    }
                    
                    AppLogger.i(TAG, "Appel IActivityManager réussi depuis MyBYDApp !");
                    if (callback != null) {
                        mMainHandler.post(new Runnable() {
                            @Override public void run() { callback.onResult(true); }
                        });
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "Erreur globale de lancement de " + packageName, e);
                }
            }
        }, 2000);
    }

    /**
     * Lance une app sur le cluster avec des bounds FREEFORM explicites (mode split).
     * Le display étant déjà actif, le délai est réduit à 500 ms.
     */
    public void launchOnDashboardWithBounds(final String packageName,
            final int left, final int top, final int right, final int bottom,
            final LaunchCallback callback) {
        AppLogger.log(TAG, "launchOnDashboardWithBounds 500ms → " + packageName
                + " bounds=[" + left + "," + top + "," + right + "," + bottom + "]");
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                AdbLocalClient.launchDirectWithBounds(ClusterService.this, packageName,
                        displayId, left, top, right, bottom,
                        new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String report) {
                        AppLogger.i(TAG, "ADB trampoline bounds OK: "
                                + report.trim().replace("\n", " "));
                        if (callback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override public void run() { callback.onResult(true); }
                            });
                        }
                    }
                    @Override public void onError(String error) {
                        AppLogger.e(TAG, "ADB trampoline bounds ÉCHEC — "
                                + error.replace("\n", " | "));
                        if (callback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override public void run() { callback.onResult(false); }
                            });
                        }
                    }
                });
            }
        }, 500);
    }

    public int getDisplayId() {
        return mDisplayHelper.getKnownClusterDisplayId();
    }

    /**
     * Lance une app sur un displayId spécifique (ex: VirtualDisplay preview de ClusterMirrorManager).
     * Délai court (500ms) car le display est déjà prêt (pas d'activation Freedom nécessaire).
     * Utilise la même réflexion IActivityManager que launchOnDashboard().
     */
    public void launchOnSpecificDisplay(final String packageName, final int displayId,
            final LaunchCallback callback) {
        AppLogger.i(TAG, "launchOnSpecificDisplay → " + packageName + " sur display=" + displayId);
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    android.content.Intent launchIntent =
                            getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        AppLogger.e(TAG, "Aucun intent pour " + packageName);
                        if (callback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override public void run() { callback.onResult(false); }
                            });
                        }
                        return;
                    }
                    launchIntent.addFlags(0x10008000); // NEW_TASK | CLEAR_TASK
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(displayId);

                    try {
                        Class<?> amClass = Class.forName("android.app.ActivityManager");
                        java.lang.reflect.Method getServiceMethod = amClass.getMethod("getService");
                        Object iam = getServiceMethod.invoke(null);
                        Class<?> iAmClass = Class.forName("android.app.IActivityManager");
                        Class<?> iAppThreadClass = Class.forName("android.app.IApplicationThread");
                        Class<?> profilerInfoClass = Class.forName("android.app.ProfilerInfo");
                        java.lang.reflect.Method startMethod = iAmClass.getMethod(
                                "startActivityAsUser",
                                iAppThreadClass, String.class, android.content.Intent.class,
                                String.class, android.os.IBinder.class, String.class,
                                int.class, int.class, profilerInfoClass,
                                android.os.Bundle.class, int.class);
                        startMethod.invoke(iam, null, getPackageName(), launchIntent,
                                null, null, null, 0, 0, null, opts.toBundle(), -2);
                    } catch (Exception ex) {
                        AppLogger.w(TAG, "launchOnSpecificDisplay fallback context: " + ex.getMessage());
                        startActivity(launchIntent, opts.toBundle());
                    }

                    AppLogger.i(TAG, "launchOnSpecificDisplay réussi → " + packageName
                            + " sur display=" + displayId);
                    if (callback != null) {
                        mMainHandler.post(new Runnable() {
                            @Override public void run() { callback.onResult(true); }
                        });
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "launchOnSpecificDisplay ERREUR", e);
                    if (callback != null) {
                        mMainHandler.post(new Runnable() {
                            @Override public void run() { callback.onResult(false); }
                        });
                    }
                }
            }
        }, 500);
    }

    /** Arrête proprement la projection (sendInfo(0) + stopService AutoDisplayService). */
    public void stopProjection() {
        AppLogger.log(TAG, "stopProjection demandé");
        mProjectionActive = false;
        mDisplayHelper.stop();
        mLauncher.setDashboardDisplayId(-1);
        updateNotification("Cluster : arrêté");
        stopSelf();
    }

    /**
     * Synchronise l'état du service SANS renvoyer les commandes ADB de restauration.
     * À utiliser quand la restauration ADB a déjà été faite en amont (ex: restoreBydDashboard).
     * Évite le double envoi de sendInfo(18+0).
     */
    public void stopProjectionNoAdb() {
        AppLogger.log(TAG, "stopProjectionNoAdb demandé (ADB déjà envoyé)");
        mProjectionActive = false;
        mDisplayHelper.stopWithoutAdb();
        mLauncher.setDashboardDisplayId(-1);
        updateNotification("Cluster : arrêté");
        stopSelf();
    }

    // ── DashboardDisplayHelper.Listener ─────────────────────────────────────

    @Override
    public void onDashboardDisplayConnected(Display display, int displayId) {
        AppLogger.log(TAG, "Display cluster connecté : id=" + displayId);
        mLauncher.setDashboardDisplayId(displayId);
        // Mettre à jour le forwarder avec les dimensions et l'ID réels du display
        mInputForwarder.setClusterDisplay(display);
        mInputForwarder.setClusterDisplayId(displayId);
        updateNotification("Cluster actif — display " + displayId);
        if (mListener != null) {
            mListener.onClusterDisplayConnected(display, displayId);
        }
    }

    @Override
    public void onDashboardDisplayDisconnected() {
        AppLogger.log(TAG, "Display cluster déconnecté");
        mLauncher.setDashboardDisplayId(-1);
        updateNotification("Cluster : déconnecté");
        if (mListener != null) {
            mListener.onClusterDisplayDisconnected();
        }
    }

    // ── Notification (obligatoire pour Foreground Service) ──────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Projection cluster",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Maintient l'affichage sur le cluster BYD");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BYD App")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
