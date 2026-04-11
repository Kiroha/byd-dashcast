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
import android.util.Log;
import android.view.Display;

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
    }

    // ── Binder ──────────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public ClusterService getService() { return ClusterService.this; }
    }

    private final IBinder mBinder = new LocalBinder();

    // ── État ────────────────────────────────────────────────────────────────
    private DashboardDisplayHelper mDisplayHelper;
    private DashboardLauncher      mLauncher;
    private Listener               mListener;
    private boolean                mProjectionActive = false;

    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        mDisplayHelper = new DashboardDisplayHelper(this, this);
        mLauncher      = new DashboardLauncher(this);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Cluster : initialisation…"));
        AppLogger.log(TAG, "ClusterService créé — démarrage projection");
        mDisplayHelper.start();
        mProjectionActive = true;
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
        // Garder le listener null pour éviter les leaks si MainActivity est détruite
        mListener = null;
        return true; // onRebind() sera appelé quand MainActivity se rebind
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mListener = null;
        if (mProjectionActive) {
            mDisplayHelper.stop();
        }
        AppLogger.log(TAG, "ClusterService détruit");
    }

    // ── API publique (appelée depuis MainActivity via le binder) ────────────

    public void setListener(Listener listener) {
        mListener = listener;
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

    public int getDisplayId() {
        return mDisplayHelper.getKnownClusterDisplayId();
    }

    /** Relance la séquence d'activation (bouton ACTIVER dans MainActivity). */
    public void restartProjection() {
        AppLogger.log(TAG, "restartProjection demandé");
        mLauncher.setDashboardDisplayId(-1);
        mDisplayHelper.stop();
        mDisplayHelper.start();
        mProjectionActive = true;
        updateNotification("Cluster : activation…");
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

    // ── DashboardDisplayHelper.Listener ─────────────────────────────────────

    @Override
    public void onDashboardDisplayConnected(Display display, int displayId) {
        AppLogger.log(TAG, "Display cluster connecté : id=" + displayId);
        mLauncher.setDashboardDisplayId(displayId);
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
