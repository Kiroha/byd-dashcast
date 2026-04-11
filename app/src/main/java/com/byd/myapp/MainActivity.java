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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.byd.myapp.dashboard.ClusterInputForwarder;
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
    private ClusterInputForwarder   mClusterInputForwarder;

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
            AppLogger.log(TAG, "ClusterService déconnecté");
        }
    };
    private String mCurrentDashboardApp = null;

    // UI — barre statut
    private TextView tvDashboardStatus;
    private Button   btnRestoreByd;
    private Button   btnActivateCluster;
    private RecyclerView rvApps;
    private AppListAdapter mAdapter;

    // UI — panel contrôle cluster
    private LinearLayout panelClusterControl;
    private TextView     tvControlAppName;
    private FrameLayout  clusterTouchpad;

    private static final int REQ_COMMON_PERMS = 43;
    private static final String[] COMMON_PERMS = {
        "android.permission.BYDAUTO_SPEED_COMMON",
        "android.permission.BYDAUTO_ENERGY_COMMON",
        "android.permission.BYDAUTO_GEARBOX_COMMON",
        "android.permission.BYDAUTO_BODYWORK_COMMON"
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Demander les permissions COMMON au démarrage (obligatoire sur ROM BYD)
        // afin qu'elles soient déjà accordées quand BYDDashboardActivity / BYDLiveActivity démarrent.
        boolean allGranted = true;
        for (String perm : COMMON_PERMS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, COMMON_PERMS, REQ_COMMON_PERMS);
        }

        tvDashboardStatus  = (TextView)    findViewById(R.id.tv_dashboard_status);
        btnRestoreByd      = (Button)      findViewById(R.id.btn_restore_byd);
        btnActivateCluster = (Button)      findViewById(R.id.btn_activate_cluster);
        rvApps             = (RecyclerView) findViewById(R.id.rv_apps);

        // Bouton diagnostic ⚙
        Button btnDiag = (Button) findViewById(R.id.btn_diag);
        btnDiag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagActivity.class));
            }
        });

        // Bouton rapport système 📋
        Button btnSysInfo = (Button) findViewById(R.id.btn_sysinfo);
        btnSysInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SysInfoActivity.class));
            }
        });

        // Bouton Live BYD 📊
        Button btnLive = (Button) findViewById(R.id.btn_live);
        btnLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, BYDLiveActivity.class));
            }
        });

        // Bouton langue 🌐
        Button btnLanguage = (Button) findViewById(R.id.btn_language);
        btnLanguage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Réinitialise le flag setup pour repasser par WelcomeActivity
                LocaleHelper.markSetupDone(MainActivity.this);
                android.content.SharedPreferences prefs = getSharedPreferences(
                        LocaleHelper.PREF_FILE, MODE_PRIVATE);
                prefs.edit().remove(LocaleHelper.PREF_SETUP_DONE).apply();
                Intent intent = new Intent(MainActivity.this, WelcomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }
        });

        // Liste des apps
        mAdapter = new AppListAdapter(this);
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        rvApps.setAdapter(mAdapter);

        // Bouton "Restaurer BYD"
        btnRestoreByd.setEnabled(false);
        btnRestoreByd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restoreBydDashboard();
            }
        });

        // Bouton "Activer cluster" — lance les commandes Freedom pour libérer le display
        btnActivateCluster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activateCluster();
            }
        });

        // Démarrer le ClusterService maintenant (startForegroundService dans onStart)
        mDashboardLauncher     = new DashboardLauncher(this); // temporaire jusqu'au bind
        mClusterInputForwarder = new ClusterInputForwarder(this);

        // Panel contrôle cluster
        panelClusterControl = (LinearLayout) findViewById(R.id.panel_cluster_control);
        tvControlAppName    = (TextView)     findViewById(R.id.tv_control_app_name);
        clusterTouchpad     = (FrameLayout)  findViewById(R.id.cluster_touchpad);

        // Masquer le panel
        Button btnControlHide = (Button) findViewById(R.id.btn_control_hide);
        btnControlHide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panelClusterControl.setVisibility(View.GONE);
            }
        });

        // Touchpad : transférer tous les événements tactiles vers le cluster
        clusterTouchpad.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Masquer le hint au premier contact
                TextView hint = (TextView) v.findViewById(R.id.tv_touchpad_hint);
                if (hint != null) hint.setVisibility(View.GONE);

                mClusterInputForwarder.forwardTouch(
                        event.getX(), event.getY(),
                        v.getWidth(), v.getHeight(),
                        event.getAction());
                return true;
            }
        });

        // Boutons de navigation
        ((Button) findViewById(R.id.btn_cluster_back)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        mClusterInputForwarder.injectKey(KeyEvent.KEYCODE_BACK);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_home)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        mClusterInputForwarder.injectKey(KeyEvent.KEYCODE_HOME);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_up)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        mClusterInputForwarder.injectKey(KeyEvent.KEYCODE_DPAD_UP);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_down)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        mClusterInputForwarder.injectKey(KeyEvent.KEYCODE_DPAD_DOWN);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_vol_up)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        mClusterInputForwarder.injectKey(KeyEvent.KEYCODE_VOLUME_UP);
                    }
                });
        ((Button) findViewById(R.id.btn_cluster_vol_down)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        mClusterInputForwarder.injectKey(KeyEvent.KEYCODE_VOLUME_DOWN);
                    }
                });

        // Charger la liste des apps (async pour ne pas bloquer l'UI)
        new LoadAppsTask().execute();
    }

    @Override
    protected void onStart() {
        super.onStart();
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
        // Retirer le listener mais garder le service actif : la projection continue
        if (mServiceBound && mClusterService != null) {
            mClusterService.setListener(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        // Le service a déjà mis à jour son DashboardLauncher — on synchronise la référence locale
        if (mServiceBound && mClusterService != null) {
            mDashboardLauncher = mClusterService.getLauncher();
        }
        mClusterInputForwarder.setClusterDisplay(display); // null-safe dans setClusterDisplay
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateDashboardStatus(null);
                mAdapter.setDashboardAvailable(true);
                btnActivateCluster.setEnabled(false);
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
                tvDashboardStatus.setText("Dashboard : non connecté");
                btnRestoreByd.setEnabled(false);
                btnActivateCluster.setEnabled(true); // Permettre réactivation
                mAdapter.setDashboardAvailable(false);
                panelClusterControl.setVisibility(View.GONE);
            }
        });
    }

    // ---- AppListAdapter.OnSendToDashboardListener ----

    @Override
    public void onSendToDashboard(AppInfo app) {
        if (!mDashboardLauncher.isDashboardAvailable()) {
            Toast.makeText(this, getString(R.string.toast_dashboard_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }

        // DashboardLauncher utilise Context.startActivity() + réflexion setLaunchDisplayId(),
        // exécuté dans le processus de l'app (uid de l'app, pas uid=2000 shell).
        // L'ancien chemin AdbLocalClient.launchOnCluster() passait par "am start-activity"
        // (uid=2000) → SafeActivityOptions.checkPermissions le rejette sur les apps tierces.
        AppLogger.log(TAG, "Envoi cluster — " + app.packageName
                + " display=" + mDashboardLauncher.getDashboardDisplayId());
        boolean launched = mDashboardLauncher.launchOnDashboard(app.packageName);
        AppLogger.log(TAG, "launchOnDashboard " + app.packageName + " → " + (launched ? "OK" : "ÉCHEC"));
        if (launched) {
            mCurrentDashboardApp = app.appName;
            updateDashboardStatus(app.appName);
            tvControlAppName.setText(app.appName);
            View hint = clusterTouchpad.findViewById(R.id.tv_touchpad_hint);
            if (hint != null) hint.setVisibility(View.VISIBLE);
            panelClusterControl.setVisibility(View.VISIBLE);
        } else {
            Toast.makeText(this,
                    getString(R.string.toast_app_incompatible, app.appName),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onSendToMain(AppInfo app) {
        boolean launched = mDashboardLauncher.launchOnMainDisplay(app.packageName);
        AppLogger.log(TAG, "Envoi écran principal — " + app.packageName
                + " → " + (launched ? "OK" : "ÉCHEC"));
        if (launched) {
            // L'app quitte le cluster → restauration BYD déjà déclenchée dans launchOnMainDisplay
            mCurrentDashboardApp = null;
            updateDashboardStatus(null);
            panelClusterControl.setVisibility(View.GONE);
        } else {
            Toast.makeText(this,
                    getString(R.string.toast_main_error, app.appName),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---- Restaurer l'affichage BYD d'origine ----

    private void activateCluster() {
        if (!mServiceBound || mClusterService == null) {
            Toast.makeText(this, "Service non prêt — attendez quelques secondes", Toast.LENGTH_SHORT).show();
            return;
        }
        btnActivateCluster.setEnabled(false);
        tvDashboardStatus.setText("Activation cluster...");
        AppLogger.log(TAG, "Activation cluster — ClusterService.restartProjection()");
        mClusterService.restartProjection();

        // Réactiver le bouton si le display n'apparaît pas sous 9s
        tvDashboardStatus.postDelayed(new Runnable() {
            @Override public void run() {
                if (!mServiceBound || mClusterService == null
                        || mClusterService.getDisplayId() < 0) {
                    btnActivateCluster.setEnabled(true);
                    tvDashboardStatus.setText("Display non détecté — réessayez");
                }
            }
        }, 9000);
    }

    private void restoreBydDashboard() {
        final int displayId = mDashboardLauncher.getDashboardDisplayId();
        if (displayId < 0) {
            Toast.makeText(this, getString(R.string.toast_dashboard_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }
        btnRestoreByd.setEnabled(false);
        AdbLocalClient.restoreBydOnCluster(this, displayId, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mCurrentDashboardApp = null;
                        updateDashboardStatus(null);
                        panelClusterControl.setVisibility(View.GONE);
                        AppLogger.log(TAG, "BYD restauré ✓");
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
            btnRestoreByd.setEnabled(false);
        } else {
            tvDashboardStatus.setText("Dashboard : " + appName);
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

