package com.byd.myapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.util.Log;
import android.content.pm.ResolveInfo;

import com.byd.myapp.AppLogger;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.byd.myapp.dashboard.DashboardLauncher;
import com.byd.myapp.model.AppInfo;
import com.byd.myapp.FloatingLogButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * MainActivity — écran principal 15 pouces.
 *
 * Affiche la liste des apps installées. L'utilisateur choisit une app et
 * clique "→ Dashboard" pour l'envoyer sur le petit écran derrière le volant.
 * Le bouton "Restaurer BYD" ramène le widget vitesse/batterie/rapport.
 */
public class MainActivity extends AppCompatActivity
        implements ClusterService.Listener,
                   AppListAdapter.OnSendToDashboardListener {

    private static final String TAG = "BYDApp";

    // Service cluster
    private ClusterService          mClusterService;
    private boolean                 mServiceBound    = false;
    private boolean                 mBindRequested   = false; // vrai dès qu'un bindService est en cours
    private DashboardLauncher       mDashboardLauncher; // référence locale mise à jour après bind

    // savedItem : package de la dernière app envoyée sur le cluster
    private static final String PREFS_NAME      = "byd_app_prefs";
    private static final String PREF_LAST_APP   = "last_app_package";
    // App en attente d'envoi pendant l'auto-activation du cluster
    private String mPendingLaunchPackage = null;

    private final ServiceConnection mServiceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mClusterService = ((ClusterService.LocalBinder) binder).getService();
            mServiceBound   = true;
            mDashboardLauncher = mClusterService.getLauncher();
            mClusterService.setListener(MainActivity.this);
            AppLogger.log(TAG, "Bind ClusterService OK — displayId=" + mClusterService.getDisplayId());
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBound   = false;
            mBindRequested  = false; // autoriser un nouveau bindService si nécessaire
            mClusterService = null;
            // Invalider le displayId pour qu'isDashboardAvailable() retourne false.
            // Sans ça, onSendToDashboard() croirait le cluster disponible et appellerait
            // mClusterService.launchOnDashboard() → NullPointerException.
            if (mDashboardLauncher != null) mDashboardLauncher.setDashboardDisplayId(-1);
            mCurrentDashboardApp = null;
            if (mAdapter != null) mAdapter.setCurrentPackage(null);
            AppLogger.log(TAG, "ClusterService déconnecté");
        }
    };
    private String mCurrentDashboardApp = null;

    // UI — barre statut
    private TextView tvDashboardStatus;
    private Button   btnRestoreByd;
    private Button   btnOverflow;
    private RecyclerView rvApps;
    private AppListAdapter mAdapter;

    // UI — panel contrôle cluster
    private LinearLayout panelClusterControl;
    private TextView     tvControlAppName;
    private ImageView    clusterMirror;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");
        // Démarrer le bouton flottant dès que l'app est lancée
        startService(new Intent(this, FloatingLogButton.class));

        tvDashboardStatus  = (TextView)    findViewById(R.id.tv_dashboard_status);
        btnRestoreByd      = (Button)      findViewById(R.id.btn_restore_byd);
        btnOverflow        = (Button)      findViewById(R.id.btn_overflow);
        rvApps             = (RecyclerView) findViewById(R.id.rv_apps);

        // Liste des apps
        mAdapter = new AppListAdapter(this);
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        rvApps.setAdapter(mAdapter);

        // Bouton principal : "Activer cluster" quand déconnecté, "Restaurer BYD" quand actif
        btnRestoreByd.setEnabled(true);
        btnRestoreByd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrentDashboardApp == null) {
                    // Aucune app projetée → activer
                    activateCluster();
                } else {
                    // App projetée → restaurer BYD
                    restoreBydDashboard();
                }
            }
        });

        // Bouton ⋮ overflow — outils dev + activation manuelle
        btnOverflow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflowMenu(v);
            }
        });

        // Démarrer le ClusterService maintenant (startForegroundService dans onStart)
        mDashboardLauncher = new DashboardLauncher(this); // temporaire jusqu'au bind

        // Panel contrôle cluster
        panelClusterControl = (LinearLayout) findViewById(R.id.panel_cluster_control);
        tvControlAppName    = (TextView)     findViewById(R.id.tv_control_app_name);
        clusterMirror       = (ImageView)    findViewById(R.id.cluster_mirror);

        // Masquer le panel
        Button btnControlHide = (Button) findViewById(R.id.btn_control_hide);
        btnControlHide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panelClusterControl.setVisibility(View.GONE);
                stopClusterMirror();
            }
        });

        // Miroir cluster : touch → mapper les coordonnées → injecter sur display 1
        clusterMirror.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                forwardTouchFromMirror(v, event);
                return true;
            }
        });

        // Boutons de navigation
        ((Button) findViewById(R.id.btn_cluster_back)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        com.byd.myapp.dashboard.ClusterInputForwarder f = getInputForwarder();
                        if (f != null) f.injectKey(KeyEvent.KEYCODE_BACK);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_home)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        com.byd.myapp.dashboard.ClusterInputForwarder f = getInputForwarder();
                        if (f != null) f.injectKey(KeyEvent.KEYCODE_HOME);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_up)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        com.byd.myapp.dashboard.ClusterInputForwarder f = getInputForwarder();
                        if (f != null) f.injectKey(KeyEvent.KEYCODE_DPAD_UP);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_down)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        com.byd.myapp.dashboard.ClusterInputForwarder f = getInputForwarder();
                        if (f != null) f.injectKey(KeyEvent.KEYCODE_DPAD_DOWN);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_vol_up)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        com.byd.myapp.dashboard.ClusterInputForwarder f = getInputForwarder();
                        if (f != null) f.injectKey(KeyEvent.KEYCODE_VOLUME_UP);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_vol_down)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        com.byd.myapp.dashboard.ClusterInputForwarder f = getInputForwarder();
                        if (f != null) f.injectKey(KeyEvent.KEYCODE_VOLUME_DOWN);
                    }
                });

        // Charger la liste des apps (async pour ne pas bloquer l'UI)
        new LoadAppsTask().execute();
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStart");
        if (mServiceBound && mClusterService != null) {
            // Activity revenue au premier plan : ré-attacher le listener
            // (onStop l'avait mis à null pour éviter les leaks pendant le background)
            mClusterService.setListener(this);
        } else if (!mBindRequested) {
            // Premier démarrage ou après onDestroy : lancer + binder le service
            mBindRequested = true;
            Intent svcIntent = new Intent(this, ClusterService.class);
            startForegroundService(svcIntent);
            bindService(svcIntent, mServiceConn, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStop");
        // Retirer le listener mais garder le service actif : la projection continue
        if (mServiceBound && mClusterService != null) {
            mClusterService.setListener(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLogger.lifecycle(getClass().getSimpleName(), "onDestroy");
        if (mServiceBound) {
            unbindService(mServiceConn);
            mServiceBound  = false;
            mBindRequested = false;
        }
    }

    // ---- ClusterService.Listener ----

    @Override
    public void onClusterDisplayConnected(Display display, int displayId) {
        Log.i(TAG, "Dashboard display connecté : id=" + displayId);
        AppLogger.log(TAG, "Dashboard connecté — displayId=" + displayId
                + " nom=" + (display != null ? display.getName() : "IActivityManager/fallback"));
        if (mServiceBound && mClusterService != null) {
            mDashboardLauncher = mClusterService.getLauncher();
        }
        // setClusterDisplay est maintenant géré dans ClusterService.onDashboardDisplayConnected
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateDashboardStatus(null);

                // Auto-envoi : app en attente (tap pendant l'activation) ou savedItem
                String toSend = mPendingLaunchPackage;
                if (toSend == null) {
                    toSend = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_LAST_APP, null);
                }
                mPendingLaunchPackage = null;

                if (toSend != null) {
                    final String pkg = toSend;
                    AppLogger.log(TAG, "savedItem : relance auto → " + pkg);
                    mClusterService.launchOnDashboard(pkg, new ClusterService.LaunchCallback() {
                        @Override public void onResult(boolean launched) {
                            if (launched) {
                                mCurrentDashboardApp = pkg;
                                mAdapter.setCurrentPackage(pkg);
                                updateDashboardStatus(pkg);
                                AppLogger.log(TAG, "savedItem relancé ✓ → " + pkg);
                            }
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onClusterDisplayDisconnected() {
        AppLogger.log(TAG, "Dashboard déconnecté");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCurrentDashboardApp = null;
                mAdapter.setCurrentPackage(null);
                tvDashboardStatus.setText(getString(R.string.status_disconnected));
                btnRestoreByd.setText("Activer cluster");
                btnRestoreByd.setEnabled(true);
                panelClusterControl.setVisibility(View.GONE);
            }
        });
    }

    // ---- AppListAdapter.OnSendToDashboardListener ----

    @Override
    public void onSendToDashboard(AppInfo app) {
        if (!mDashboardLauncher.isDashboardAvailable()) {
            // Cluster pas encore prêt : auto-activation + mise en attente de l'app
            AppLogger.log(TAG, "Cluster non prêt — auto-activation pour " + app.packageName);
            mPendingLaunchPackage = app.packageName;
            activateCluster();
            Toast.makeText(this, "Activation du cluster…", Toast.LENGTH_SHORT).show();
            return;
        }

        AppLogger.log(TAG, "Envoi cluster — " + app.packageName
                + " display=" + mDashboardLauncher.getDashboardDisplayId());
        final String appName = app.appName;
        final String pkgName = app.packageName;
        // Guard : mClusterService peut être null si onServiceDisconnected() s'est déclenché
        // entre le isDashboardAvailable() ci-dessus et cet appel.
        if (mClusterService == null) {
            AppLogger.e(TAG, "ClusterService null — envoi annulé pour " + pkgName);
            return;
        }
        // Séquence : sendInfo(16) → délai 1,5 s → lancement (même logique que TEST 10)
        mClusterService.launchOnDashboard(pkgName, new ClusterService.LaunchCallback() {
            @Override public void onResult(boolean launched) {
                AppLogger.log(TAG, "launchOnDashboard " + pkgName + " → " + (launched ? "OK" : "ÉCHEC"));
                if (launched) {
                    mCurrentDashboardApp = appName;
                    mAdapter.setCurrentPackage(pkgName);
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putString(PREF_LAST_APP, pkgName).apply();
                    updateDashboardStatus(appName);
                    tvControlAppName.setText(appName);
                    panelClusterControl.setVisibility(View.VISIBLE);
                    startClusterMirror();
                } else {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.toast_app_incompatible, appName),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    public void onSendToMain(AppInfo app) {
        // Nettoyer l'état cluster avant le lancement : launchOnMainDisplay peut retourner
        // false même si l'app part bien (fallback startActivity sans reflection).
        mCurrentDashboardApp = null;
        mAdapter.setCurrentPackage(null);
        updateDashboardStatus(null);
        panelClusterControl.setVisibility(View.GONE);
        stopClusterMirror();
        // Effacer PREF_LAST_APP : sinon onKillApp croirait que l'app est encore sur
        // le cluster et appellerait stopProjection() inutilement, détruisant le service.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_LAST_APP).apply();
        mDashboardLauncher.launchOnMainDisplay(app.packageName);
        AppLogger.log(TAG, "Envoi écran principal — " + app.packageName);
    }

    @Override
    public void onKillApp(final AppInfo app) {
        // 1. Si l'app est effectivement encore sur le cluster (mCurrentDashboardApp != null
        //    indique que notre code la considère toujours présente sur display 1),
        //    restaurer d'abord pour libérer la surface Qt.
        //    NE PAS vérifier PREF_LAST_APP : il reste set même après "<- Principal",
        //    ce qui causerait un stopProjection() inutile + déstruction du service.
        boolean isOnCluster = mCurrentDashboardApp != null
                && app.packageName != null
                && app.packageName.equals(
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_LAST_APP, null));
        if (isOnCluster && mServiceBound && mClusterService != null) {
            mClusterService.stopProjection();
        }

        // 2. am force-stop via ADB
        AdbLocalClient.forceStopApp(this, app.packageName, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (app.packageName != null && app.packageName.equals(
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .getString(PREF_LAST_APP, null))) {
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit().remove(PREF_LAST_APP).apply();
                        }
                        mCurrentDashboardApp = null;
                        mAdapter.setCurrentPackage(null);
                        updateDashboardStatus(null);
                        panelClusterControl.setVisibility(View.GONE);
                        stopClusterMirror();
                        Toast.makeText(MainActivity.this,
                                app.appName + " arrêté", Toast.LENGTH_SHORT).show();
                        AppLogger.log(TAG, "forceStop " + app.packageName + " OK");
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this,
                                "Kill échoué : " + error, Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "forceStop ÉCHEC : " + error);
                    }
                });
            }
        });
    }

    // ---- Miroir cluster ----

    /** Retourne le ClusterInputForwarder du service si bindé, sinon crée un temporaire. */
    private com.byd.myapp.dashboard.ClusterInputForwarder getInputForwarder() {
        if (mServiceBound && mClusterService != null) {
            return mClusterService.getInputForwarder();
        }
        // Pas de fallback local : le service est toujours démarré avant tout usage
        return null;
    }

    /**
     * Mappe les coordonnées touch depuis l'ImageView miroir vers le display cluster.
     * Tient compte du scaleType=fitCenter : l'image est centrée, des bandes peuvent être présentes.
     */
    private void forwardTouchFromMirror(View mirrorView, MotionEvent event) {
        com.byd.myapp.dashboard.ClusterInputForwarder forwarder = getInputForwarder();
        if (forwarder == null) return;
        int clusterW = forwarder.getClusterWidth();
        int clusterH = forwarder.getClusterHeight();
        int viewW    = mirrorView.getWidth();
        int viewH    = mirrorView.getHeight();
        if (viewW <= 0 || viewH <= 0 || clusterW <= 0 || clusterH <= 0) return;

        // Calculer le rectangle réel de l'image dans la vue (fitCenter)
        float scale   = Math.min((float) viewW / clusterW, (float) viewH / clusterH);
        float drawW   = clusterW * scale;
        float drawH   = clusterH * scale;
        float offsetX = (viewW - drawW) / 2f;
        float offsetY = (viewH - drawH) / 2f;

        float clusterX = (event.getX() - offsetX) / scale;
        float clusterY = (event.getY() - offsetY) / scale;
        // Clips aux bornes du display
        clusterX = Math.max(0, Math.min(clusterX, clusterW - 1));
        clusterY = Math.max(0, Math.min(clusterY, clusterH - 1));

        // Réutiliser forwardTouch avec des coordonnées déjà converties (pad = cluster size)
        forwarder.forwardTouch(clusterX, clusterY, clusterW, clusterH, event.getAction());
    }

    /** Démarre la boucle de capture miroir du cluster. */
    private void startClusterMirror() {
        if (!mServiceBound || mClusterService == null) return;
        int displayId = mClusterService.getDisplayId();
        if (displayId < 0) return;
        mClusterService.getMirrorManager().start(displayId,
                new com.byd.myapp.dashboard.ClusterMirrorManager.FrameCallback() {
            @Override
            public void onFrame(android.graphics.Bitmap bitmap, int w, int h) {
                if (clusterMirror != null) {
                    clusterMirror.setImageBitmap(bitmap);
                }
            }
            @Override
            public void onError(String reason) {
                AppLogger.log(TAG, "Miroir erreur : " + reason);
            }
        });
    }

    /** Arrête la boucle de capture miroir. */
    private void stopClusterMirror() {
        if (mServiceBound && mClusterService != null) {
            mClusterService.getMirrorManager().stop();
        }
        if (clusterMirror != null) clusterMirror.setImageBitmap(null);
    }

    // ---- Restaurer l'affichage BYD d'origine ----

    private void activateCluster() {
        if (!mServiceBound || mClusterService == null) {
            // Service arrêté (stopProjection() l'a tué) → le redémarrer.
            // ClusterService.onCreate() appelle mDisplayHelper.start() automatiquement,
            // qui envoie sendInfo(16) + attend le VirtualDisplay → onClusterDisplayConnected.
            if (!mBindRequested) {
                mBindRequested = true;
                Intent svcIntent = new Intent(this, ClusterService.class);
                startForegroundService(svcIntent);
                bindService(svcIntent, mServiceConn, BIND_AUTO_CREATE);
            }
            tvDashboardStatus.setText("Activation cluster…");
            AppLogger.log(TAG, "Activation cluster — redémarrage ClusterService");
            // Timeout fallback : si le display ne répond pas sous 9s
            tvDashboardStatus.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!mServiceBound || mClusterService == null
                            || mClusterService.getDisplayId() < 0) {
                        mPendingLaunchPackage = null;
                        tvDashboardStatus.setText(getString(R.string.status_disconnected));
                    }
                }
            }, 9000);
            return;
        }
        tvDashboardStatus.setText("Activation cluster…");
        AppLogger.log(TAG, "Activation cluster — ClusterService.restartProjection()");
        mClusterService.restartProjection();

        // Afficher un message d'échec si le display ne répond pas sous 9s
        tvDashboardStatus.postDelayed(new Runnable() {
            @Override public void run() {
                if (!mServiceBound || mClusterService == null
                        || mClusterService.getDisplayId() < 0) {
                    mPendingLaunchPackage = null; // annuler l'envoi en attente
                    tvDashboardStatus.setText(getString(R.string.status_disconnected));
                }
            }
        }, 9000);
    }

    /** Menu ⋮ — outils développeur accessibles sans encombrer la barre. */
    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "🖥 Activer cluster");
        popup.getMenu().add(0, 2, 0, "⚙ Diagnostic");
        popup.getMenu().add(0, 3, 0, "📋 Rapport système");
        popup.getMenu().add(0, 4, 0, "📊 Données live BYD");
        popup.getMenu().add(0, 5, 0, "🌐 Langue");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: activateCluster(); return true;
                    case 2: startActivity(new Intent(MainActivity.this, DiagActivity.class)); return true;
                    case 3: startActivity(new Intent(MainActivity.this, SysInfoActivity.class)); return true;
                    case 4: startActivity(new Intent(MainActivity.this, BYDLiveActivity.class)); return true;
                    case 5:
                        android.content.SharedPreferences p = getSharedPreferences(
                                LocaleHelper.PREF_FILE, MODE_PRIVATE);
                        p.edit().remove(LocaleHelper.PREF_SETUP_DONE).apply();
                        Intent intent = new Intent(MainActivity.this, WelcomeActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        return true;
                }
                return false;
            }
        });
        popup.show();
    }

    private void restoreBydDashboard() {
        btnRestoreByd.setEnabled(false);
        if (mServiceBound && mClusterService != null) {
            mClusterService.stopProjection();
            mCurrentDashboardApp = null;
            mAdapter.setCurrentPackage(null);
            panelClusterControl.setVisibility(View.GONE);
            stopClusterMirror();
            updateDashboardStatus(null);
            btnRestoreByd.setEnabled(true);
            AppLogger.log(TAG, "BYD restauré via stopProjection() ✓");
            return;
        }

        // Path 2 : service non bindé → fallback ADB (sendInfo(0) via shell)
        final int displayId = mDashboardLauncher != null ? mDashboardLauncher.getDashboardDisplayId() : -1;
        if (displayId < 0) {
            Toast.makeText(this, getString(R.string.toast_dashboard_unavailable), Toast.LENGTH_SHORT).show();
            btnRestoreByd.setEnabled(true);
            return;
        }
        AdbLocalClient.restoreBydOnCluster(this, displayId, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mCurrentDashboardApp = null;
                        mAdapter.setCurrentPackage(null);
                        updateDashboardStatus(null);
                        panelClusterControl.setVisibility(View.GONE);
                        stopClusterMirror();
                        AppLogger.log(TAG, "BYD restauré via ADB ✓");
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btnRestoreByd.setEnabled(true);
                        Toast.makeText(MainActivity.this,
                                "Restauration échouée: " + error, Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "Restauration ÉCHEC: " + error);
                    }
                });
            }
        });
    }

    private void updateDashboardStatus(String appName) {
        if (appName == null) {
            tvDashboardStatus.setText("Dashboard : affichage BYD");
            btnRestoreByd.setText("Activer cluster");
            // Toujours activé : re-déclenche activateCluster() si besoin
            btnRestoreByd.setEnabled(true);
        } else {
            tvDashboardStatus.setText("Dashboard : " + appName);
            btnRestoreByd.setText(getString(R.string.btn_restore_byd));
            btnRestoreByd.setEnabled(true);
        }
    }

    // ---- Chargement async de la liste des apps ----

    private class LoadAppsTask extends AsyncTask<Void, Void, List<AppInfo>> {
        @Override
        protected List<AppInfo> doInBackground(Void... voids) {
            PackageManager pm = getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(launcherIntent, 0);
            List<AppInfo> apps = new ArrayList<>();

            for (ResolveInfo ri : resolveInfos) {
                String pkg = ri.activityInfo.packageName;
                // Exclure notre propre app
                if (pkg.equals(getPackageName())) continue;

                String name = ri.loadLabel(pm).toString();
                apps.add(new AppInfo(pkg, name, ri.loadIcon(pm)));
            }

            // Trier par nom
            Collections.sort(apps, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo a, AppInfo b) {
                    return a.appName.compareToIgnoreCase(b.appName);
                }
            });

            return apps;
        }

        @Override
        protected void onPostExecute(List<AppInfo> apps) {
            mAdapter.setApps(apps);
        }
    }

}

