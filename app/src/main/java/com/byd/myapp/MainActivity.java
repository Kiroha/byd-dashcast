package com.byd.myapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.byd.myapp.dashboard.DashboardLauncher;
import com.byd.myapp.model.AppInfo;

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
    private static final String PREFS_NAME         = "byd_app_prefs";
    private static final String PREF_LAST_APP      = "last_app_package";
    /** Code sendInfo pour la taille d'écran du cluster : 29=8.8", 30=12.3" (défaut Seal EU), 31=10.25" */
    private static final String PREF_CLUSTER_TYPE  = "cluster_screen_size_cmd";
    private static final int    CLUSTER_TYPE_DEFAULT = 30;
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
            mCurrentDashboardPkg = null;
            clearSplitState();
            if (mAdapter != null) mAdapter.setCurrentPackage(null);
            AppLogger.log(TAG, "ClusterService déconnecté");
        }
    };
    private String mCurrentDashboardApp = null;  // nom lisible (affiché dans la status bar)
    private String mCurrentDashboardPkg = null;   // package name (pour am force-stop)
    private String mSecondDashboardApp  = null;   // nom lisible du slot secondaire (split)
    private String mSecondDashboardPkg  = null;   // package name du slot secondaire (split)
    private int    mCurrentSplitSlot    = 0;      // 0=plein écran, 1=gauche, 2=droite

    // UI — barre statut
    private TextView tvDashboardStatus;
    private Button   btnActivateCluster;
    private Button   btnRestoreCluster;
    private Button   btnOriginCluster;
    private Button   btnOverflow;
    private Button   btnSplitLayout;
    private RecyclerView rvApps;
    private AppListAdapter mAdapter;

    // UI — panel contrôle cluster
    private LinearLayout panelClusterControl;
    private TextView     tvControlAppName;
    private SurfaceView  clusterMirror;
    private SurfaceHolder mMirrorHolder;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");
        // Bouton flottant LOG — debug uniquement (absent en release)
        if (BuildConfig.DEBUG) {
            startService(new Intent(this, FloatingLogButton.class));
        }

        tvDashboardStatus   = (TextView) findViewById(R.id.tv_dashboard_status);
        btnActivateCluster  = (Button)   findViewById(R.id.btn_activate_cluster);
        btnRestoreCluster   = (Button)   findViewById(R.id.btn_restore_cluster);
        btnOriginCluster    = (Button)   findViewById(R.id.btn_origin_cluster);
        btnOverflow         = (Button)   findViewById(R.id.btn_overflow);
        rvApps             = (RecyclerView) findViewById(R.id.rv_apps);

        // Liste des apps
        mAdapter = new AppListAdapter(this);
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        rvApps.setAdapter(mAdapter);

        // Bouton « Activer cluster » — toujours déclenche activateCluster()
        btnActivateCluster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { activateCluster(); }
        });

        // Bouton « Restaurer cluster » — toujours déclenche restoreBydDashboard()
        btnRestoreCluster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { restoreBydDashboard(); }
        });

        // Bouton « Cluster d'origine » — remet la taille d'écran configurée et restaure Qt
        btnOriginCluster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { originCluster(); }
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
        clusterMirror       = (SurfaceView)  findViewById(R.id.cluster_mirror);

        // SurfaceHolder.Callback : démarre/arrête le miroir SurfaceControl quand la Surface est disponible.
        mMirrorHolder = clusterMirror.getHolder();
        mMirrorHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // La surface est prête — démarrer le miroir si le display cluster est connu
                attemptStartMirrorWithCurrentHolder();
            }
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // Dimensions changées : reconfigurer la projection
                attemptStartMirrorWithCurrentHolder();
            }
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopClusterMirror();
            }
        });

        // Masquer le panel
        Button btnControlHide = (Button) findViewById(R.id.btn_control_hide);
        btnControlHide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panelClusterControl.setVisibility(View.GONE);
                stopClusterMirror();
            }
        });

        // Bouton Split — mise en page du cluster (plein écran / gauche 50% / droite 50%)
        btnSplitLayout = (Button) findViewById(R.id.btn_cluster_split);
        btnSplitLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showSplitMenu(v); }
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
            // Si une app était active et le panel visible, relancer le miroir.
            if (mCurrentDashboardApp != null
                    && panelClusterControl != null
                    && panelClusterControl.getVisibility() == View.VISIBLE) {
                attemptStartMirrorWithCurrentHolder();
            }
        } else if (!mBindRequested) {
            // Premier démarrage ou après onDestroy : lancer + binder le service
            mBindRequested = true;
            tvDashboardStatus.setText("Démarrage cluster…");
            // Freedom est démarré automatiquement par ClusterManager.activateClusterDisplay()
            // si le VirtualDisplay n'est pas encore présent — pas besoin de le lancer ici
            // (évite un double force-stop/restart Freedom pendant l'initialisation du service).
            Intent svcIntent = new Intent(this, ClusterService.class);
            startForegroundService(svcIntent);
            bindService(svcIntent, mServiceConn, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStop");
        // Retirer le listener mais garder le service actif : la projection continue.
        // Arrêter le miroir : le HandlerThread ne doit pas capturer des frames en background.
        // Le miroir redémarre automatiquement via le mécanisme savedItem dans
        // onClusterDisplayConnected() quand l'Activity revient au premier plan.
        stopClusterMirror();
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

                // Si le panel est visible (app déjà active), démarrer/reconfigurer le miroir
                if (panelClusterControl.getVisibility() == View.VISIBLE) {
                    attemptStartMirrorWithCurrentHolder();
                }

                // Auto-envoi : app en attente (tap pendant l'activation) ou savedItem
                String toSend = mPendingLaunchPackage;
                if (toSend == null) {
                    toSend = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_LAST_APP, null);
                }
                mPendingLaunchPackage = null;

                if (toSend != null) {
                    final String pkg = toSend;
                    // Résoudre le nom lisible pour la status bar
                    String resolvedName;
                    try {
                        resolvedName = getPackageManager()
                                .getApplicationLabel(
                                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
                    } catch (Exception ignored) {
                        resolvedName = pkg; // fallback au package si l'app est désinstallée
                    }
                    final String appDisplayName = resolvedName;
                    AppLogger.log(TAG, "savedItem : relance auto → " + pkg);
                    mClusterService.launchOnDashboard(pkg, new ClusterService.LaunchCallback() {
                        @Override public void onResult(boolean launched) {
                            if (launched) {
                                mCurrentDashboardApp = appDisplayName;
                                mCurrentDashboardPkg = pkg;
                                mAdapter.setCurrentPackage(pkg);
                                updateDashboardStatus(appDisplayName);
                                updateControlLabel();
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
                mCurrentDashboardPkg = null;
                clearSplitState();
                mAdapter.setCurrentPackage(null);
                tvDashboardStatus.setText(getString(R.string.status_disconnected));
                panelClusterControl.setVisibility(View.GONE);
            }
        });
    }

    // ---- AppListAdapter.OnSendToDashboardListener ----

    @Override
    public void onSendToDashboard(AppInfo app) {
        // Le displayId Java peut ne pas être résolu même quand le cluster est actif
        // (état interne non fiable sur DiLink 3.0). On ne bloque plus ici :
        // ClusterService.launchOnDashboard() essaie le Binder direct puis l'ADB relay
        // avec displayId=1 hardcodé (Seal EU) en fallback → toujours fonctionnel.
        if (mClusterService == null) {
            AppLogger.e(TAG, "ClusterService null — envoi annulé pour " + app.packageName);
            Toast.makeText(this, "Service cluster non disponible", Toast.LENGTH_SHORT).show();
            return;
        }

        AppLogger.log(TAG, "Envoi cluster — " + app.packageName
                + " display=" + mDashboardLauncher.getDashboardDisplayId());
        final String appName = app.appName;
        final String pkgName = app.packageName;

        // ── Mode split : une app occupe déjà un slot → la nouvelle app va dans l'autre ──
        if (mCurrentSplitSlot != 0 && mCurrentDashboardPkg != null) {
            // Même app que le slot principal ou secondaire déjà présent : ignorer
            if (pkgName.equals(mCurrentDashboardPkg) || pkgName.equals(mSecondDashboardPkg)) {
                Toast.makeText(this, "App déjà sur le cluster", Toast.LENGTH_SHORT).show();
                return;
            }
            int[] dims = getClusterDimensions();
            final int W = dims[0], H = dims[1];
            // Slot complémentaire (1=gauche → droite ; 2=droite → gauche)
            final int newLeft  = (mCurrentSplitSlot == 1) ? W / 2 : 0;
            final int newRight = (mCurrentSplitSlot == 1) ? W     : W / 2;
            AppLogger.log(TAG, "split — slot " + (mCurrentSplitSlot == 1 ? "droit" : "gauche")
                    + " bounds=[" + newLeft + ",0," + newRight + "," + H + "] → " + pkgName);
            // Force-stop l'ancien slot secondaire si déjà occupé
            if (mSecondDashboardPkg != null) {
                AdbLocalClient.forceStopApp(this, mSecondDashboardPkg, null);
            }
            mClusterService.launchOnDashboardWithBounds(pkgName, newLeft, 0, newRight, H,
                    new ClusterService.LaunchCallback() {
                @Override public void onResult(boolean launched) {
                    if (launched) {
                        mSecondDashboardApp = appName;
                        mSecondDashboardPkg = pkgName;
                        updateControlLabel();
                    } else {
                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_app_incompatible, appName),
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
            return;
        }

        // ── Comportement normal — lancement plein écran ──────────────────────────────
        mClusterService.launchOnDashboard(pkgName, new ClusterService.LaunchCallback() {
            @Override public void onResult(boolean launched) {
                AppLogger.log(TAG, "launchOnDashboard " + pkgName + " → " + (launched ? "OK" : "ÉCHEC"));
                if (launched) {
                    mCurrentDashboardApp = appName;
                    mCurrentDashboardPkg = pkgName;
                    mAdapter.setCurrentPackage(pkgName);
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putString(PREF_LAST_APP, pkgName).apply();
                    updateDashboardStatus(appName);
                    updateControlLabel();
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
        mCurrentDashboardPkg = null;
        // Force-stop le slot secondaire en mode split (évite qu'il reste sur display 1)
        if (mSecondDashboardPkg != null) {
            AdbLocalClient.forceStopApp(this, mSecondDashboardPkg, null);
        }
        clearSplitState();
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
                        mCurrentDashboardPkg = null;
                        // Force-stop le slot secondaire en mode split
                        if (mSecondDashboardPkg != null) {
                            AdbLocalClient.forceStopApp(MainActivity.this, mSecondDashboardPkg, null);
                        }
                        clearSplitState();
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
        return null;
    }

    /**
     * Démarre le miroir SurfaceControl si la Surface est prête ET le display cluster connu.
     * Peut être appelé depuis surfaceCreated, surfaceChanged, ou onClusterDisplayConnected.
     */
    private void attemptStartMirrorWithCurrentHolder() {
        if (!mServiceBound || mClusterService == null) return;
        if (mMirrorHolder == null || !mMirrorHolder.getSurface().isValid()) return;
        int displayId = mClusterService.getDisplayId();
        if (displayId < 0) return;

        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null) return;
        Display clusterDisplay = dm.getDisplay(displayId);
        if (clusterDisplay == null) return;

        int viewW = clusterMirror.getWidth();
        int viewH = clusterMirror.getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        mClusterService.getMirrorManager().startMirror(
                clusterDisplay, mMirrorHolder.getSurface(), viewW, viewH);
    }

    /**
     * Signale à MainActivity qu'une app a été lancée sur le cluster → afficher le panel + démarrer miroir.
     * Appelé depuis onSendToDashboard après un lancement réussi.
     */
    private void startClusterMirror() {
        panelClusterControl.setVisibility(View.VISIBLE);
        // La Surface peut ne pas encore être créée (premier affichage du panel).
        // Dans ce cas, surfaceCreated() appellera attemptStartMirrorWithCurrentHolder().
        attemptStartMirrorWithCurrentHolder();
    }

    /** Arrête le miroir SurfaceControl et masque le panel. */
    private void stopClusterMirror() {
        if (mServiceBound && mClusterService != null) {
            mClusterService.getMirrorManager().stopMirror();
        }
    }

    /**
     * Mappe les coordonnées touch depuis la SurfaceView miroir vers le display cluster.
     * La projection SurfaceControl respecte le ratio (letterboxing), donc on recalcule
     * l'offset de la même façon que setDisplayProjection l'a fait.
     */
    private void forwardTouchFromMirror(View mirrorView, MotionEvent event) {
        com.byd.myapp.dashboard.ClusterInputForwarder forwarder = getInputForwarder();
        if (forwarder == null) return;

        com.byd.myapp.dashboard.ClusterMirrorManager mirror =
                mServiceBound && mClusterService != null
                        ? mClusterService.getMirrorManager() : null;
        if (mirror == null) return;

        int clusterW = mirror.getClusterWidth();
        int clusterH = mirror.getClusterHeight();
        int viewW    = mirrorView.getWidth();
        int viewH    = mirrorView.getHeight();
        if (viewW <= 0 || viewH <= 0 || clusterW <= 0 || clusterH <= 0) return;

        // Même calcul que ClusterMirrorManager.startMirror (ratio préservé)
        float scale   = Math.min((float) viewW / clusterW, (float) viewH / clusterH);
        float drawW   = clusterW * scale;
        float drawH   = clusterH * scale;
        float offsetX = (viewW - drawW) / 2f;
        float offsetY = (viewH - drawH) / 2f;

        float clusterX = (event.getX() - offsetX) / scale;
        float clusterY = (event.getY() - offsetY) / scale;
        clusterX = Math.max(0, Math.min(clusterX, clusterW - 1));
        clusterY = Math.max(0, Math.min(clusterY, clusterH - 1));

        forwarder.forwardTouch(clusterX, clusterY, clusterW, clusterH, event.getAction());
    }

    // ---- Restaurer l'affichage BYD d'origine ----

    private void activateCluster() {
        btnActivateCluster.setEnabled(false);
        tvDashboardStatus.setText("Activation cluster…");
        AppLogger.log(TAG, "activateCluster()");

        if (!mServiceBound || mClusterService == null) {
            // Service arrêté (ex: après stopProjection via kill app).
            // Le redémarrer : ClusterService.onCreate() → mDisplayHelper.start() → sendInfo(30+16).
            // onClusterDisplayConnected() se déclenchera → mPendingLaunchPackage consommé.
            if (!mBindRequested) {
                mBindRequested = true;
                Intent svcIntent = new Intent(this, ClusterService.class);
                startForegroundService(svcIntent);
                bindService(svcIntent, mServiceConn, BIND_AUTO_CREATE);
            }
            tvDashboardStatus.setText("Démarrage cluster…");
            btnActivateCluster.setEnabled(true);
            return;
        }

        // Service déjà bindé → envoyer les commandes ADB directement (ré-activation manuelle)
        AdbLocalClient.activateClusterDisplay(this, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvDashboardStatus.setText("Cluster activé ✓");
                        btnActivateCluster.setEnabled(true);
                        AppLogger.log(TAG, "activateCluster OK — " + report);
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvDashboardStatus.setText(getString(R.string.status_disconnected));
                        btnActivateCluster.setEnabled(true);
                        Toast.makeText(MainActivity.this,
                                "Activation échouée : " + error, Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "activateCluster ÉCHEC — " + error);
                    }
                });
            }
        });
    }

    /** Retourne le code sendInfo pour la taille d'écran choisie dans les paramètres. */
    private int getClusterTypeCmd() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_CLUSTER_TYPE, CLUSTER_TYPE_DEFAULT);
    }

    /** Menu ⋮ — outils développeur accessibles sans encombrer la barre. */
    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "⚙️ Paramètres");
        popup.getMenu().add(0, 2, 0, "🔧 Diagnostic");
        popup.getMenu().add(0, 3, 0, "📋 Rapport système");
        popup.getMenu().add(0, 5, 0, "🌐 Langue");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: showClusterTypeSettings(); return true;
                    case 2: startActivity(new Intent(MainActivity.this, DiagActivity.class)); return true;
                    case 3: startActivity(new Intent(MainActivity.this, SysInfoActivity.class)); return true;
                    case 5:
                        SharedPreferences p = getSharedPreferences(
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

    /** Dialog de sélection du type de cluster (taille d'écran). */
    private void showClusterTypeSettings() {
        final int[] cmds    = { 29, 30, 31 };
        final String[] labels = {
            "8.8 pouces  (cmd=29)",
            "12.3 pouces (cmd=30) — Seal EU",
            "10.25 pouces (cmd=31)"
        };
        int current = getClusterTypeCmd();
        int checked = 1; // défaut 12.3"
        for (int i = 0; i < cmds.length; i++) {
            if (cmds[i] == current) { checked = i; break; }
        }
        final int[] selected = { checked };
        new AlertDialog.Builder(this)
            .setTitle("Type de cluster")
            .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    selected[0] = which;
                }
            })
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    int cmd = cmds[selected[0]];
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putInt(PREF_CLUSTER_TYPE, cmd).apply();
                    Toast.makeText(MainActivity.this,
                            "Cluster : " + labels[selected[0]], Toast.LENGTH_SHORT).show();
                    AppLogger.log(TAG, "Cluster type → sendInfo cmd=" + cmd);
                }
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void restoreBydDashboard() {
        btnRestoreCluster.setEnabled(false);
        tvDashboardStatus.setText("Restauration cluster…");
        AppLogger.log(TAG, "restoreBydDashboard() via ADB (TEST 10)");
        // Mode split : force-stop la seconde app avant sendInfo(18)
        // (évite qu'elle se relocalise sur le display principal)
        if (mSecondDashboardPkg != null) {
            AdbLocalClient.forceStopApp(this, mSecondDashboardPkg, null);
        }

        AdbLocalClient.restoreBydOnCluster(this, mCurrentDashboardPkg, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // Synchroniser ClusterService : invalider mDashboardDisplayId.
                        // Sans ça, isDashboardAvailable() resterait true et le prochain tap
                        // d'app tenterait launchOnDashboard() sur un VirtualDisplay dont Qt
                        // a repris la surface.
                        // stopProjectionNoAdb() car restoreBydOnCluster() a déjà envoyé
                        // sendInfo(18+0) — on évite le double envoi de commandes ADB.
                        if (mServiceBound && mClusterService != null) {
                            mClusterService.stopProjectionNoAdb();
                        }
                        mCurrentDashboardApp = null;
                        mCurrentDashboardPkg = null;
                        clearSplitState();
                        mAdapter.setCurrentPackage(null);
                        updateDashboardStatus(null);
                        panelClusterControl.setVisibility(View.GONE);
                        stopClusterMirror();
                        btnRestoreCluster.setEnabled(true);
                        AppLogger.log(TAG, "BYD restauré via ADB ✓");
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btnRestoreCluster.setEnabled(true);
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
        } else {
            tvDashboardStatus.setText("Dashboard : " + appName);
        }
        btnRestoreCluster.setEnabled(true);
    }

    /** Cluster d'origine — sendInfo(screenSize) + sendInfo(18) + sendInfo(0). */
    private void originCluster() {
        btnOriginCluster.setEnabled(false);
        tvDashboardStatus.setText("Cluster d'origine…");
        AppLogger.log(TAG, "originCluster() cmd=" + getClusterTypeCmd());
        // Mode split : force-stop la seconde app avant la restauration
        if (mSecondDashboardPkg != null) {
            AdbLocalClient.forceStopApp(this, mSecondDashboardPkg, null);
        }

        AdbLocalClient.restoreOriginCluster(this, getClusterTypeCmd(), mCurrentDashboardPkg, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mServiceBound && mClusterService != null) {
                            mClusterService.stopProjectionNoAdb();
                        }
                        mCurrentDashboardApp = null;
                        mCurrentDashboardPkg = null;
                        clearSplitState();
                        mAdapter.setCurrentPackage(null);
                        updateDashboardStatus(null);
                        panelClusterControl.setVisibility(View.GONE);
                        stopClusterMirror();
                        btnOriginCluster.setEnabled(true);
                        AppLogger.log(TAG, "Cluster d'origine restauré ✓");
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btnOriginCluster.setEnabled(true);
                        Toast.makeText(MainActivity.this,
                                "Cluster d'origine échoué : " + error, Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "originCluster ÉCHEC : " + error);
                    }
                });
            }
        });
    }

    // ---- Split layout -------------------------------------------------------

    /** Affiche le menu de mise en page du cluster (plein écran / gauche 50% / droite 50%). */
    private void showSplitMenu(View anchor) {
        if (!mServiceBound || mClusterService == null || mCurrentDashboardPkg == null) {
            Toast.makeText(this, "Aucune app sur le cluster", Toast.LENGTH_SHORT).show();
            return;
        }
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "⬜ Plein écran");
        popup.getMenu().add(0, 2, 0, "⬜⬛ Gauche (50%)");
        popup.getMenu().add(0, 3, 0, "⬛⬜ Droite (50%)");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int[] dims = getClusterDimensions();
                int W = dims[0], H = dims[1];
                switch (item.getItemId()) {
                    case 1: applySplitSlot(0, 0, 0, W, H);     break;
                    case 2: applySplitSlot(1, 0, 0, W / 2, H); break;
                    case 3: applySplitSlot(2, W / 2, 0, W, H); break;
                }
                return true;
            }
        });
        popup.show();
    }

    /**
     * Redimensionne l'app principale dans le slot choisi via "am task resize".
     * slot 0 = plein écran, 1 = gauche (0..W/2), 2 = droite (W/2..W).
     */
    private void applySplitSlot(final int slot, final int l, final int t, final int r, final int b) {
        // Retour en plein écran : force-stop la seconde app si présente
        if (slot == 0 && mSecondDashboardPkg != null) {
            AdbLocalClient.forceStopApp(this, mSecondDashboardPkg, null);
            mSecondDashboardApp = null;
            mSecondDashboardPkg = null;
        }
        mCurrentSplitSlot = slot;
        mClusterService.resizeTaskOnDashboard(mCurrentDashboardPkg, l, t, r, b,
                new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        AppLogger.i(TAG, "split slot " + slot + " OK ["
                                + l + "," + t + "," + r + "," + b + "]");
                        updateControlLabel();
                    }
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        AppLogger.e(TAG, "split resize ÉCHEC: " + error);
                        Toast.makeText(MainActivity.this,
                                "Redimensionnement échoué : " + error, Toast.LENGTH_SHORT).show();
                        mCurrentSplitSlot = 0; // revert
                    }
                });
            }
        });
    }

    /** Réinitialise l'état split (slot + second app). */
    private void clearSplitState() {
        mSecondDashboardApp = null;
        mSecondDashboardPkg = null;
        mCurrentSplitSlot   = 0;
    }

    /**
     * Retourne [largeur, hauteur] du display cluster en pixels.
     * Lit depuis le miroir SurfaceControl si disponible, sinon fallback 1920×720.
     */
    private int[] getClusterDimensions() {
        if (mServiceBound && mClusterService != null) {
            int w = mClusterService.getMirrorManager().getClusterWidth();
            int h = mClusterService.getMirrorManager().getClusterHeight();
            if (w > 0 && h > 0) return new int[]{w, h};
        }
        return new int[]{1920, 720}; // fallback DiLink 3.0 Seal EU
    }

    /** Met à jour le label d'app dans le panel cluster (supporte "App A  |  App B" en split). */
    private void updateControlLabel() {
        if (tvControlAppName == null) return;
        if (mCurrentDashboardApp == null) {
            tvControlAppName.setText("");
        } else if (mSecondDashboardApp != null) {
            tvControlAppName.setText(mCurrentDashboardApp + "  |  " + mSecondDashboardApp);
        } else {
            tvControlAppName.setText(mCurrentDashboardApp);
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

