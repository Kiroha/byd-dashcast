package com.byd.myapp;

import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.IBinder;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.byd.myapp.dashboard.DashboardLauncher;
import com.byd.myapp.model.AppInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * MainActivity — 15-inch main screen.
 *
 * Displays the list of installed apps. The user selects an app and
 * clicks "→ Dashboard" to send it to the small screen behind the steering wheel.
 * The "Restore BYD" button brings back the speed/battery/gear widget.
 */
public class MainActivity extends AppCompatActivity
        implements ClusterService.Listener,
                   AppListAdapter.OnSendToDashboardListener {

    private static final String TAG = "BYDApp";

    // Cluster service
    private ClusterService          mClusterService;
    private boolean                 mServiceBound    = false;
    private boolean                 mBindRequested   = false; // true as soon as a bindService is in progress
    private DashboardLauncher       mDashboardLauncher; // local reference updated after bind

    // savedItem: package of the last app sent to the cluster (removed, see history)
    private static final String PREFS_NAME         = "byd_app_prefs";
    /** Package of the app sent to the main display — persisted to survive Activity recreation */
    private static final String PREF_MAIN_PKG      = "main_display_pkg";
    /** sendInfo code for cluster screen size: 29=8.8", 30=12.3" (default Seal EU), 31=10.25" */
    private static final String PREF_CLUSTER_TYPE  = "cluster_screen_size_cmd";
    private static final int    CLUSTER_TYPE_DEFAULT = 30;
    // App waiting to be sent during cluster auto-activation
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
            mBindRequested  = false; // allow a new bindService if needed
            mClusterService = null;
            // Invalidate the displayId so isDashboardAvailable() returns false.
            // Without this, onSendToDashboard() would think the cluster is available and would call
            // mClusterService.launchOnDashboard() → NullPointerException.
            if (mDashboardLauncher != null) mDashboardLauncher.setDashboardDisplayId(-1);
            mCurrentDashboardApp = null;
                mCurrentDashboardPkg = null;
                btnActivateCluster.setEnabled(true);
            mMainDisplayPkg      = null;
            clearSplitState();
            if (mAdapter != null) mAdapter.setCurrentPackage(null);
            if (mAdapter != null) mAdapter.setMainPackage(null);
            AppLogger.log(TAG, "ClusterService disconnected");
        }
    };
    private String mCurrentDashboardApp = null;  // readable name (displayed in the status bar)
    private String mCurrentDashboardPkg = null;   // package name (for am force-stop)
    private String mSecondDashboardApp  = null;   // readable name of the secondary slot (split)
    private String mSecondDashboardPkg  = null;   // package name of the secondary slot (split)
    private int    mCurrentSplitSlot    = 0;      // 0=full screen, 1=left, 2=right
    private String mMainDisplayPkg      = null;   // package sent to the main display (button "→ Cluster")

    // UI — barre statut
    private TextView tvDashboardStatus;
    private TextView     tvAppListTitle;
    private Button   btnActivateCluster;
    private Button   btnRestoreCluster;
    private Button   btnOriginCluster;
    private Button   btnOverflow;
    private Button   btnSplitLayout;
    private RecyclerView rvApps;
    private AppListAdapter mAdapter;

    // UI — cluster control panel
    private LinearLayout panelClusterControl;
    private TextView     tvControlAppName;
    private android.widget.FrameLayout frameMirror;
    private TextureView clusterMirror;
    private TextView     tvMirrorPlaceholder;
    private ImageView    clusterMirrorScreenshot;
    // Surface created from the TextureView's SurfaceTexture.
    // SF is the PRODUCER of this surface (setDisplaySurface) → TextureView renders.
    private Surface      mMirrorSurface;

    // Screenshot mirror loop (fallback when SurfaceControl.createDisplay() fails)
    private final Handler  mScreenshotHandler  = new Handler(Looper.getMainLooper());

    // MirrorDaemon — Binder received via broadcast ACTION_DAEMON_READY
    private IBinder mDaemonBinder = null;
    private final BroadcastReceiver mDaemonReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            IBinder binder = extras.getBinder("daemon_binder");
            if (binder == null) return;
            mDaemonBinder = binder;
            AppLogger.i(TAG, "Daemon Binder received OK");
            // Forward to the forwarder for touch/key injection via uid=2000
            if (mServiceBound && mClusterService != null) {
                mClusterService.getInputForwarder().setDaemonBinder(mDaemonBinder);
            }
            // Start the mirror if the surface is available
            if (mMirrorSurface != null && mMirrorSurface.isValid()
                    && panelClusterControl != null
                    && panelClusterControl.getVisibility() == View.VISIBLE) {
                attemptStartMirrorWithCurrentHolder();
            }
        }
    };
    // volatile: read from the ADB background thread (captureClusterDisplay callback),
    // written from the main thread (stopScreenshotLoop). Without volatile, the ADB thread
    // could see the old non-null value after stopScreenshotLoop() has set it to null.
    private volatile Runnable mScreenshotRunnable = null;
    private static final int SCREENSHOT_INTERVAL_MS = 800;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");

        // Unlock hidden Android APIs (SurfaceControl, etc.)
        // Must be called before any call to ClusterMirrorManager.startMirror(this, ).
        // Same mechanism as WindowManagement v1.2 (VMRuntime.setHiddenApiExemptions).
        com.byd.myapp.dashboard.ClusterMirrorManager.unlockHiddenApis();
        // Floating LOG button — debug only (absent in release)
        if (BuildConfig.DEBUG) {
            startService(new Intent(this, FloatingLogButton.class));
        }

        // Receiver to retrieve the MirrorDaemon Binder (uid=2000)
        registerReceiver(mDaemonReadyReceiver,
                new IntentFilter(com.byd.myapp.daemon.MirrorDaemon.ACTION_DAEMON_READY));
        
        // Floating "GPS" button to quickly reopen Waze streaming
        startService(new Intent(this, FloatingRemoteButton.class));

        tvDashboardStatus   = (TextView) findViewById(R.id.tv_dashboard_status);
        btnActivateCluster  = (Button)   findViewById(R.id.btn_activate_cluster);
        btnRestoreCluster   = (Button)   findViewById(R.id.btn_restore_cluster);
        btnOriginCluster    = (Button)   findViewById(R.id.btn_origin_cluster);
        btnOverflow         = (Button)   findViewById(R.id.btn_overflow);
        rvApps             = (RecyclerView) findViewById(R.id.rv_apps);

        // App list
        mAdapter = new AppListAdapter(this);
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        rvApps.setAdapter(mAdapter);

        // Button "Activate cluster" — always triggers activateCluster()
        btnActivateCluster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { activateCluster(); }
        });

        // Button "Restore cluster" — always triggers restoreBydDashboard()
        btnRestoreCluster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { restoreBydDashboard(); }
        });

        // Button "Original cluster" — restores the configured screen size and restores Qt
        btnOriginCluster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { originCluster(); }
        });

        // Button ⋮ overflow — dev tools + manual activation
        btnOverflow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflowMenu(v);
            }
        });

        // Start ClusterService now (startForegroundService in onStart)
        mDashboardLauncher = new DashboardLauncher(this); // temporary until bind

        // Cluster control panel
        panelClusterControl = (LinearLayout) findViewById(R.id.panel_cluster_control);
        tvControlAppName    = (TextView)     findViewById(R.id.tv_control_app_name);
        tvAppListTitle      = (TextView)     findViewById(R.id.tv_app_list_title);
        frameMirror         = (android.widget.FrameLayout) findViewById(R.id.frame_cluster_mirror);
        clusterMirror       = (TextureView) findViewById(R.id.cluster_mirror);
        tvMirrorPlaceholder = (TextView)     findViewById(R.id.tv_mirror_placeholder);
        clusterMirrorScreenshot = (ImageView) findViewById(R.id.cluster_mirror_screenshot);

        // Restore mMainDisplayPkg (lost if Activity is destroyed and recreated)
        mMainDisplayPkg = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_MAIN_PKG, null);
        if (mMainDisplayPkg != null) {
            mAdapter.setMainPackage(mMainDisplayPkg);
        }

        // TextureView optimizations
        clusterMirror.setOpaque(true);  // No alpha blending overhead
        clusterMirror.setLayerType(View.LAYER_TYPE_HARDWARE, null); // Force hardware layer

        // TextureView.SurfaceTextureListener: starts/stops the mirror when the SurfaceTexture is available.
        // Surface(SurfaceTexture) → SF is the PRODUCER, TextureView renders each frame produced by SF.
        clusterMirror.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                // Pre-size the buffer to the view dimensions to limit memory footprint and let SF scale it
                st.setDefaultBufferSize(w, h);
                mMirrorSurface = new Surface(st);
                attemptStartMirrorWithCurrentHolder();
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(w, h);
                mMirrorSurface = new Surface(st);
                attemptStartMirrorWithCurrentHolder();
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                stopClusterMirror();
                if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) { /* frame received */ }
        });
        // If the SurfaceTexture is already available (Activity recreated)
        if (clusterMirror.isAvailable()) {
            mMirrorSurface = new Surface(clusterMirror.getSurfaceTexture());
        }

        // Hide → return to list
        Button btnControlHide = (Button) findViewById(R.id.btn_control_hide);
        btnControlHide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppList();
            }
        });

        // Temporary floating keyboard
        Button btnClusterKeyboard = (Button) findViewById(R.id.btn_cluster_keyboard);
        btnClusterKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyboardDialog();
            }
        });

        // Split button — cluster layout (full screen / left 50% / right 50%)
        btnSplitLayout = (Button) findViewById(R.id.btn_cluster_split);
        btnSplitLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showSplitMenu(v); }
        });

        // Cluster mirror: touch → map coordinates → inject on display 1
        clusterMirror.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                forwardTouchFromMirror(v, event);
                return true;
            }
        });
        // Same listener for the screenshot ImageView (same coordinate space)
        clusterMirrorScreenshot.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                forwardTouchFromMirror(v, event);
                return true;
            }
        });

        // Async loading of the app list (async to avoid blocking the UI)
        loadAppsAsync();
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStart");
        // Retrieve the daemon Binder from ServiceManager if not yet available.
        // ACTION_REQUEST_BINDER no longer works: the daemon no longer has a registerReceiver
        // (forbidden since systemMain() — AMS rejects IApplicationThread).
        if (mDaemonBinder == null) {
            tryGetDaemonBinderFromServiceManager();
        }
        if (mServiceBound && mClusterService != null) {
            // Activity back in the foreground: re-attach the listener
            // (onStop had set it to null to avoid leaks during background)
            mClusterService.setListener(this);
            // If an app was active and the panel visible, restart the mirror.
            if (mCurrentDashboardApp != null
                    && panelClusterControl != null
                    && panelClusterControl.getVisibility() == View.VISIBLE) {
                attemptStartMirrorWithCurrentHolder();
            }
        } else if (!mBindRequested) {
            // Check if the service is already running (e.g. Activity re-opened)
            if (ClusterService.sIsRunning) {
                mBindRequested = true;
                tvDashboardStatus.setText(getString(R.string.status_starting_cluster));
                Intent svcIntent = new Intent(this, ClusterService.class);
                bindService(svcIntent, mServiceConn, BIND_AUTO_CREATE);
            } else {
                // Feature requested: DO NOT start the service automatically.
                // The cluster will only be activated when the user clicks 'Activate Cluster'.
                AppLogger.d(TAG, "Not starting ClusterService automatically. Waiting for user action.");
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStop");
        // Remove the listener but keep the service active: projection continues.
        // Stop the mirror: the HandlerThread must not capture frames in the background.
        // The mirror restarts automatically via the savedItem mechanism in
        // onClusterDisplayConnected() when the Activity returns to the foreground.
        stopClusterMirror();
        if (mServiceBound && mClusterService != null) {
            mClusterService.setListener(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLogger.lifecycle(getClass().getSimpleName(), "onDestroy");
        unregisterReceiver(mDaemonReadyReceiver);
        if (mServiceBound) {
            unbindService(mServiceConn);
            mServiceBound  = false;
            mBindRequested = false;
        }
    }

    // ---- ClusterService.Listener ----

    @Override
    public void onClusterDisplayConnected(Display display, int displayId) {
        AppLogger.log(TAG, "Dashboard connected — displayId=" + displayId
                + " name=" + (display != null ? display.getName() : "IActivityManager/fallback"));
        if (mServiceBound && mClusterService != null) {
            mDashboardLauncher = mClusterService.getLauncher();
        }
        // setClusterDisplay is now handled in ClusterService.onDashboardDisplayConnected
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateDashboardStatus(null);
                btnActivateCluster.setEnabled(true);

                // If the panel is visible (app already active), start/reconfigure the mirror
                if (panelClusterControl.getVisibility() == View.VISIBLE) {
                    attemptStartMirrorWithCurrentHolder();
                }

                // Restore mMainDisplayPkg if Activity was recreated (it is null after onCreate
                // only if getSharedPreferences returned no value, which should
                // not happen here, but we re-check for the onClusterDisplayDisconnected case)
                if (mMainDisplayPkg == null) {
                    mMainDisplayPkg = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_MAIN_PKG, null);
                    if (mMainDisplayPkg != null) {
                        mAdapter.setMainPackage(mMainDisplayPkg);
                    }
                }

                // Launch the pending app (tapped during cluster activation)
                if (mPendingLaunchPackage != null) {
                    final String pkg = mPendingLaunchPackage;
                    mPendingLaunchPackage = null;
                    String resolvedName;
                    try {
                        resolvedName = getPackageManager()
                                .getApplicationLabel(
                                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
                    } catch (Exception ignored) {
                        resolvedName = pkg;
                    }
                    final String appDisplayName = resolvedName;
                    mClusterService.launchOnDashboard(pkg, new ClusterService.LaunchCallback() {
                        @Override public void onResult(boolean launched) {
                            if (launched) {
                                mCurrentDashboardApp = appDisplayName;
                                mCurrentDashboardPkg = pkg;
                                mAdapter.setCurrentPackage(pkg);
                                updateDashboardStatus(appDisplayName);
                                updateControlLabel();
                            }
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onClusterDisplayDisconnected() {
        AppLogger.log(TAG, "Dashboard disconnected");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCurrentDashboardApp = null;
                mCurrentDashboardPkg = null;
                btnActivateCluster.setEnabled(true);
                mMainDisplayPkg = null;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_MAIN_PKG).apply();
                clearSplitState();
                mAdapter.setCurrentPackage(null);
                mAdapter.setMainPackage(null);
                tvDashboardStatus.setText(getString(R.string.status_disconnected));
                showAppList();
            }
        });
    }

    

    // ---- AppListAdapter.OnSendToDashboardListener ----

    @Override
    public void onSendToDashboard(AppInfo app) {
        // Java displayId may not be resolved even when the cluster is active
        // (internal state unreliable on DiLink 3.0). We no longer block here:
        // ClusterService.launchOnDashboard() tries direct Binder then ADB relay
        // with displayId=1 hardcoded (Seal EU) as fallback → always functional.
        if (mClusterService == null) {
            AppLogger.e(TAG, "ClusterService null — send cancelled for " + app.packageName);
            Toast.makeText(this, getString(R.string.toast_cluster_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }

        AppLogger.log(TAG, "Envoi cluster — " + app.packageName
                + " display=" + mDashboardLauncher.getDashboardDisplayId());
        final String appName = app.appName;
        final String pkgName = app.packageName;

        // If this app was on the main display, clear that state immediately
        if (pkgName != null && pkgName.equals(mMainDisplayPkg)) {
            mMainDisplayPkg = null;
            mAdapter.setMainPackage(null);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_MAIN_PKG).apply();
        }

        // ── Split mode: an app already occupies a slot → the new app goes into the other one ──
        if (mCurrentSplitSlot != 0 && mCurrentDashboardPkg != null) {
            // Same app as the main or secondary slot already present: ignore
            if (pkgName.equals(mCurrentDashboardPkg) || pkgName.equals(mSecondDashboardPkg)) {
                AppLogger.w(TAG, "split: duplicate ignored pkg=" + pkgName
                        + " (main=" + mCurrentDashboardPkg + " second=" + mSecondDashboardPkg + ")");
                Toast.makeText(this, getString(R.string.toast_app_already_cluster), Toast.LENGTH_SHORT).show();
                return;
            }
            int[] dims = getClusterDimensions();
            final int W = dims[0], H = dims[1];
            // Complementary slot (1=left → right; 2=right → left)
            final int newLeft  = (mCurrentSplitSlot == 1) ? W / 2 : 0;
            final int newRight = (mCurrentSplitSlot == 1) ? W     : W / 2;
            AppLogger.log(TAG, "split — slot courant=" + mCurrentSplitSlot
                    + " → complementary slot bounds=[" + newLeft + ",0," + newRight + "," + H + "]"
                    + " pkg=" + pkgName);
            // Force-stop the old secondary slot if already occupied
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

        // ── Normal behavior — full screen launch ────────────────────────────────
        mClusterService.launchOnDashboard(pkgName, new ClusterService.LaunchCallback() {
            @Override public void onResult(boolean launched) {
                AppLogger.log(TAG, "launchOnDashboard " + pkgName + " → " + (launched ? "OK" : "FAILED"));
                if (launched) {
                    mCurrentDashboardApp = appName;
                    mCurrentDashboardPkg = pkgName;
                    mAdapter.setCurrentPackage(pkgName);
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
        // Clean up cluster state before launch: launchOnMainDisplay may return
        // false even if the app launches correctly (startActivity fallback without reflection).
        mCurrentDashboardApp = null;
                mCurrentDashboardPkg = null;
                btnActivateCluster.setEnabled(true);
        // Force-stop the secondary slot in split mode (prevents it from staying on display 1)
        if (mSecondDashboardPkg != null) {
            AdbLocalClient.forceStopApp(this, mSecondDashboardPkg, null);
        }
        clearSplitState();
        // Record that the app is on the main display → shows button "→ Cluster" in the list
        mMainDisplayPkg = app.packageName;
        mAdapter.setCurrentPackage(null);
        mAdapter.setMainPackage(app.packageName);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(PREF_MAIN_PKG, app.packageName).apply();
        updateDashboardStatus(null);
                btnActivateCluster.setEnabled(true);
        showAppList();
        mDashboardLauncher.launchOnMainDisplay(app.packageName);
        AppLogger.log(TAG, "Send to main display — " + app.packageName);
    }

    @Override
    public void onKillApp(final AppInfo app) {
        // 1. If the app is still on the cluster (mCurrentDashboardPkg matches),
        //    restore first to free the Qt surface.
        boolean isOnCluster = mCurrentDashboardPkg != null
                && app.packageName != null
                && app.packageName.equals(mCurrentDashboardPkg);
        if (isOnCluster && mServiceBound && mClusterService != null) {
            mClusterService.stopProjectionNoAdb(); // Ne pas envoyer le restore cluster auto
        }

        // 2. am force-stop via ADB
        AdbLocalClient.forceStopApp(this, app.packageName, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mCurrentDashboardApp = null;
                mCurrentDashboardPkg = null;
                btnActivateCluster.setEnabled(true);
                        // Force-stop le slot secondaire en mode split
                        if (mSecondDashboardPkg != null) {
                            AdbLocalClient.forceStopApp(MainActivity.this, mSecondDashboardPkg, null);
                        }
                        clearSplitState();
                        // If the killed app was on the main display, clear that state
                        if (app.packageName != null && app.packageName.equals(mMainDisplayPkg)) {
                            mMainDisplayPkg = null;
                            mAdapter.setMainPackage(null);
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit().remove(PREF_MAIN_PKG).apply();
                        }
                        mAdapter.setCurrentPackage(null);
                        updateDashboardStatus(null);
                btnActivateCluster.setEnabled(true);
                        showAppList();
                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_app_stopped, app.appName),
                                Toast.LENGTH_SHORT).show();
                        AppLogger.log(TAG, "forceStop " + app.packageName + " OK");
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_kill_failed, error), Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "forceStop FAILED: " + error);
                    }
                });
            }
        });
    }

    // ---- Miroir cluster ----

    /** Returns the ClusterInputForwarder from the service if bound, otherwise returns null. */
    private com.byd.myapp.dashboard.ClusterInputForwarder getInputForwarder() {
        if (mServiceBound && mClusterService != null) {
            return mClusterService.getInputForwarder();
        }
        return null;
    }

    /**
     * Starts the preview VirtualDisplay if the Surface is ready.
    /**
     * Attempts to retrieve the daemon Binder from ServiceManager (via reflection).
     * Called in onStart() if mDaemonBinder == null (daemon already running, app returned to foreground).
     * Thread-safe: must be called from the main thread.
     */
    private void tryGetDaemonBinderFromServiceManager() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Class<?> smClass = Class.forName("android.os.ServiceManager");
                    java.lang.reflect.Method getService = smClass.getDeclaredMethod(
                            "getService", String.class);
                    getService.setAccessible(true);
                    IBinder binder = (IBinder) getService.invoke(null, "byd_mirror_daemon");
                    if (binder != null) {
                        AppLogger.i(TAG, "DaemonBinder retrieved from ServiceManager ✓");
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                mDaemonBinder = binder;
                                if (mServiceBound && mClusterService != null) {
                                    mClusterService.getInputForwarder().setDaemonBinder(binder);
                                }
                        // Restart the mirror if panel is visible
                        if (mCurrentDashboardApp != null
                                        && panelClusterControl != null
                                        && panelClusterControl.getVisibility() == View.VISIBLE) {
                                    attemptStartMirrorWithCurrentHolder();
                                }
                            }
                        });
                    } else {
                        AppLogger.d(TAG, "DaemonBinder not found in ServiceManager (daemon not yet started?)");
                    }
                } catch (Exception e) {
                    AppLogger.w(TAG, "tryGetDaemonBinderFromServiceManager: " + e.getMessage());
                }
            }
        }, "sm-daemon-lookup").start();
    }

    /**
     * v2.30: uses DisplayManager.createVirtualDisplay() like WindowManagement/byd_dashboard.
     * No need for clusterDisplay — the VirtualDisplay is independent of the cluster display.
     * After creation, also launches the current app on the preview display.
     */
    private void attemptStartMirrorWithCurrentHolder() {
        if (!mServiceBound || mClusterService == null) {
            AppLogger.d(TAG, "attemptStartMirror : service non disponible");
            return;
        }
        if (mMirrorSurface == null || !mMirrorSurface.isValid()) {
            AppLogger.d(TAG, "attemptStartMirror : surface invalide");
            return;
        }

        // If mirror already active (SurfaceControl or VirtualDisplay), do not recreate
        if (mClusterService.getMirrorManager().isMirrorActive()) {
            AppLogger.d(TAG, "attemptStartMirror: mirror already active");
            clusterMirror.setVisibility(View.VISIBLE);
            stopScreenshotLoop();
            return;
        }

        int viewW = clusterMirror.getWidth();
        int viewH = clusterMirror.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            AppLogger.d(TAG, "attemptStartMirror: view not yet measured "
                    + viewW + "×" + viewH);
            return;
        }

        // clusterDisplay passed to get dimensions — can be null (→ 1920×720 by default)
        Display clusterDisplay = null;
        int displayId = mClusterService.getDisplayId();
        if (displayId >= 0) {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm != null) clusterDisplay = dm.getDisplay(displayId);
        }

        AppLogger.d(TAG, "attemptStartMirror → view=" + viewW + "×" + viewH
                + " (clusterDisplay=" + (clusterDisplay != null ? displayId : "null") + ")");

        // Preferred path: mirror via daemon uid=2000 (ACCESS_SURFACE_FLINGER guaranteed)
        boolean mirrorOk = false;
        if (mDaemonBinder != null) {
            mirrorOk = mClusterService.getMirrorManager().startMirrorViaDaemon(
                    mDaemonBinder, clusterDisplay, mMirrorSurface, viewW, viewH);
        }
        // Fallback: direct SurfaceControl uid=10100 (fails if ACCESS_SURFACE_FLINGER missing)
        if (!mirrorOk) {
            mirrorOk = mClusterService.getMirrorManager().startMirror(this,
                    clusterDisplay, mMirrorSurface, viewW, viewH);
        }

        if (mirrorOk) {
            // Miroir actif → afficher le SurfaceView, stopper les screenshots
            clusterMirror.setVisibility(View.VISIBLE);
            clusterMirrorScreenshot.setVisibility(View.GONE);
            tvMirrorPlaceholder.setVisibility(View.GONE);
            stopScreenshotLoop();
        } else {
            // No mirror available → periodic screencap via ADB shell
            clusterMirror.setVisibility(View.GONE);
            tvMirrorPlaceholder.setVisibility(View.GONE);
            startScreenshotLoop(displayId);
        }
    }

    /**
     * Hides the app list and displays the cluster mirror in full space.
     * Called from startClusterMirror().
     */
    private void showMirrorView() {
        tvAppListTitle.setVisibility(View.GONE);
        rvApps.setVisibility(View.GONE);
        frameMirror.setVisibility(View.VISIBLE);
        panelClusterControl.setVisibility(View.VISIBLE);
    }

    /**
     * Hides the mirror and restores the app list.
     * Called from showAppList().
     */
    private void showAppList() {
        stopClusterMirror();
        frameMirror.setVisibility(View.GONE);
        panelClusterControl.setVisibility(View.GONE);
        tvAppListTitle.setVisibility(View.VISIBLE);
        rvApps.setVisibility(View.VISIBLE);
    }

    /**
     * Signals to MainActivity that an app was launched on the cluster → show the mirror.
     * Called from onSendToDashboard after a successful launch.
     */
    private void startClusterMirror() {
        AppLogger.d(TAG, "startClusterMirror app=" + mCurrentDashboardApp);
        showMirrorView();
        attemptStartMirrorWithCurrentHolder();
    }

    /** Stops the SurfaceControl mirror and hides the panel. */
    private void stopClusterMirror() {
        if (mServiceBound && mClusterService != null) {
            boolean wasActive = mClusterService.getMirrorManager().isMirrorActive();
            // Stop the daemon mirror if active
            if (mDaemonBinder != null) {
                mClusterService.getMirrorManager().stopMirrorViaDaemon(mDaemonBinder);
            }
            // Local cleanup (direct SurfaceControl token, residual VirtualDisplay)
            mClusterService.getMirrorManager().stopMirror(this);
            if (wasActive) AppLogger.d(TAG, "stopClusterMirror OK");
        }
        stopScreenshotLoop();
    }

    /**
     * Starts the periodic capture loop via screencap (mirror fallback).
     * ADB shell = uid=2000 → always has SurfaceFlinger access regardless of our permissions.
     */
    private void startScreenshotLoop(final int displayId) {
        stopScreenshotLoop();
        clusterMirrorScreenshot.setVisibility(View.VISIBLE);
        // WeakReference: if the Activity is stopped/destroyed while an ADB capture is
        // in flight, the callback will not update a view belonging to a dead Activity.
        final java.lang.ref.WeakReference<MainActivity> weakSelf =
                new java.lang.ref.WeakReference<>(this);
        mScreenshotRunnable = new Runnable() {
            @Override public void run() {
                AdbLocalClient.captureClusterDisplay(MainActivity.this, displayId,
                        new AdbLocalClient.BitmapCallback() {
                    @Override public void onBitmap(final Bitmap bm) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                MainActivity self = weakSelf.get();
                                if (self != null && !self.isFinishing()
                                        && self.clusterMirrorScreenshot != null) {
                                    self.clusterMirrorScreenshot.setImageBitmap(bm);
                                }
                            }
                        });
                        if (mScreenshotRunnable != null) {
                            mScreenshotHandler.postDelayed(mScreenshotRunnable,
                                    SCREENSHOT_INTERVAL_MS);
                        }
                    }
                    @Override public void onError(String error) {
                        AppLogger.w(TAG, "screenshotLoop erreur: " + error);
                        if (mScreenshotRunnable != null) {
                            mScreenshotHandler.postDelayed(mScreenshotRunnable,
                                    SCREENSHOT_INTERVAL_MS * 2L);
                        }
                    }
                });
            }
        };
        mScreenshotHandler.post(mScreenshotRunnable);
        AppLogger.i(TAG, "Screenshot mirror loop started (displayId=" + displayId + ")");
    }

    /** Stops the capture loop and hides the ImageView. */
    private void stopScreenshotLoop() {
        if (mScreenshotRunnable != null) {
            mScreenshotHandler.removeCallbacks(mScreenshotRunnable);
            mScreenshotRunnable = null;
            AppLogger.d(TAG, "Screenshot mirror loop stopped");
        }
        if (clusterMirrorScreenshot != null) {
            clusterMirrorScreenshot.setVisibility(View.GONE);
        }
    }

    /**
     * Maps touch coordinates from the mirror TextureView to the cluster display.
     * The SurfaceControl projection preserves the ratio (letterboxing), so we recalculate
     * the offset the same way setDisplayProjection did.
     */
    private void forwardTouchFromMirror(View mirrorView, MotionEvent event) {
        com.byd.myapp.dashboard.ClusterInputForwarder forwarder = getInputForwarder();
        if (forwarder == null) return;

        com.byd.myapp.dashboard.ClusterMirrorManager mirror =
                mServiceBound && mClusterService != null
                        ? mClusterService.getMirrorManager() : null;
        if (mirror == null) return;

        // Use the projection params stored when setDisplayProjection was called.
        // This guarantees the touch offset/scale matches the actual rendered projection,
        // even if the view was resized since mirror start (avoids touch offset bugs).
        float scale   = mirror.getProjScale();
        if (scale <= 0f) return;  // Mirror not yet fully initialized

        float offsetX = mirror.getProjOffsetX();
        float offsetY = mirror.getProjOffsetY();
        int   clusterW = mirror.getClusterWidth();
        int   clusterH = mirror.getClusterHeight();
        if (clusterW <= 0 || clusterH <= 0) return;

        float clusterX = (event.getX() - offsetX) / scale;
        float clusterY = (event.getY() - offsetY) / scale;
        clusterX = Math.max(0, Math.min(clusterX, clusterW - 1));
        clusterY = Math.max(0, Math.min(clusterY, clusterH - 1));

        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            AppLogger.d(TAG, "touch → view(" + (int)event.getX() + "," + (int)event.getY()
                    + ") off=(" + (int)offsetX + "," + (int)offsetY + ")"
                    + " scale=" + String.format("%.3f", scale)
                    + " cluster=(" + (int)clusterX + "," + (int)clusterY
                    + ")/" + clusterW + "×" + clusterH);
        }

        forwarder.forwardTouchFinal(clusterX, clusterY, event.getAction());
    }

    // ---- Restaurer l'affichage BYD d'origine ----

    private void activateCluster() {
        btnActivateCluster.setEnabled(false);
        tvDashboardStatus.setText(getString(R.string.status_activating_cluster));
        AppLogger.log(TAG, "activateCluster() — serviceBound=" + mServiceBound
                + " bindRequested=" + mBindRequested
                + " displayId=" + (mClusterService != null ? mClusterService.getDisplayId() : "N/A"));

        if (!mServiceBound || mClusterService == null) {
            // Service stopped or not started yet.
            // Start it: ClusterService.onCreate() → mDisplayHelper.start() → sendInfo(30+16).
            // onClusterDisplayConnected() will fire and enable the button.
            if (!mBindRequested) {
                mBindRequested = true;
                Intent svcIntent = new Intent(this, ClusterService.class);
                startForegroundService(svcIntent);
                bindService(svcIntent, mServiceConn, BIND_AUTO_CREATE);
            }
            tvDashboardStatus.setText(getString(R.string.status_starting_cluster));
            // Button is re-enabled natively by onClusterDisplayConnected or onClusterDisplayDisconnected callbacks.
        } else {
            // Service already up → manually restart projection natively without ADB
            AppLogger.log(TAG, "Calling native restartProjection via ClusterService");
            mClusterService.restartProjection();
            // onClusterDisplayConnected / onClusterDisplayDisconnected will re-enable the button
        }
    }

    /** Returns the sendInfo code for the screen size chosen in settings. */
    private int getClusterTypeCmd() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_CLUSTER_TYPE, CLUSTER_TYPE_DEFAULT);
    }

    /** ⋮ menu — developer tools accessible without cluttering the toolbar. */
    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, getString(R.string.menu_settings));
        popup.getMenu().add(0, 2, 0, getString(R.string.menu_diagnostic));
        popup.getMenu().add(0, 3, 0, getString(R.string.menu_system_report));
        popup.getMenu().add(0, 5, 0, getString(R.string.menu_language));
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

    /** Dialog for selecting the cluster type (screen size). */
    private void showClusterTypeSettings() {
        final int[] cmds    = { 29, 30, 31 };
        final String[] labels = {
            getString(R.string.cluster_label_88),
            getString(R.string.cluster_label_123),
            getString(R.string.cluster_label_1025)
        };
        int current = getClusterTypeCmd();
        int checked = 1; // default 12.3"
        for (int i = 0; i < cmds.length; i++) {
            if (cmds[i] == current) { checked = i; break; }
        }
        final int[] selected = { checked };
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_cluster_type_title))
            .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    selected[0] = which;
                }
            })
            .setPositiveButton(getString(R.string.btn_ok), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    int cmd = cmds[selected[0]];
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putInt(PREF_CLUSTER_TYPE, cmd).apply();
                    Toast.makeText(MainActivity.this,
                            getString(R.string.toast_cluster_type, labels[selected[0]]), Toast.LENGTH_SHORT).show();
                    AppLogger.log(TAG, "Cluster type → sendInfo cmd=" + cmd);
                }
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    }

    private void restoreBydDashboard() {
        btnRestoreCluster.setEnabled(false);
        tvDashboardStatus.setText(getString(R.string.status_restoring_cluster));
        AppLogger.log(TAG, "restoreBydDashboard() via ADB (TEST 10)");
        // Split mode: force-stop the second app before sendInfo(18)
        // (prevents it from relocating to the main display)
        if (mSecondDashboardPkg != null) {
            AdbLocalClient.forceStopApp(this, mSecondDashboardPkg, null);
        }

        AdbLocalClient.restoreBydOnCluster(this, mCurrentDashboardPkg, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // Sync ClusterService: invalidate mDashboardDisplayId.
                        // Without this, isDashboardAvailable() would remain true and the next tap
                        // would try launchOnDashboard() on a VirtualDisplay whose Qt
                        // has taken back the surface.
                        // stopProjectionNoAdb() because restoreBydOnCluster() has already sent
                        // sendInfo(18+0) — we avoid double sending ADB commands.
                        if (mServiceBound && mClusterService != null) {
                            mClusterService.stopProjectionNoAdb();
                        }
                        mCurrentDashboardApp = null;
                mCurrentDashboardPkg = null;
                btnActivateCluster.setEnabled(true);
                        clearSplitState();
                        mAdapter.setCurrentPackage(null);
                        updateDashboardStatus(null);
                btnActivateCluster.setEnabled(true);
                        showAppList();
                        btnRestoreCluster.setEnabled(true);
                        AppLogger.log(TAG, "BYD restored via ADB ✓");
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btnRestoreCluster.setEnabled(true);
                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_restore_failed, error), Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "Restore FAILED: " + error);
                    }
                });
            }
        });
    }

    private void updateDashboardStatus(String appName) {
        tvDashboardStatus.setTextColor(Color.WHITE);
        if (appName == null) {
            tvDashboardStatus.setText(getString(R.string.status_dashboard_byd));
        } else {
            tvDashboardStatus.setText(getString(R.string.status_dashboard_app, appName));
        }
        btnRestoreCluster.setEnabled(true);
    }

    /** Original cluster — sendInfo(screenSize) + sendInfo(18) + sendInfo(0). */
    private void originCluster() {
        btnOriginCluster.setEnabled(false);
        tvDashboardStatus.setText(getString(R.string.status_restoring_origin));
        AppLogger.log(TAG, "originCluster() cmd=" + getClusterTypeCmd());
        // Split mode: force-stop the second app before restoration
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
                btnActivateCluster.setEnabled(true);
                        clearSplitState();
                        mAdapter.setCurrentPackage(null);
                        updateDashboardStatus(null);
                btnActivateCluster.setEnabled(true);
                        showAppList();
                        btnOriginCluster.setEnabled(true);
                        AppLogger.log(TAG, "Original cluster restored ✓");
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btnOriginCluster.setEnabled(true);
                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_origin_failed, error), Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "originCluster FAILED: " + error);
                    }
                });
            }
        });
    }

    // ---- Split layout -------------------------------------------------------

    /** Displays the cluster layout menu (full screen / left 50% / right 50%). */
    private void showSplitMenu(View anchor) {
        if (!mServiceBound || mClusterService == null || mCurrentDashboardPkg == null) {
            AppLogger.w(TAG, "showSplitMenu ignored — serviceBound=" + mServiceBound
                    + " clusterService=" + (mClusterService != null)
                    + " currentPkg=" + mCurrentDashboardPkg);
            Toast.makeText(this, getString(R.string.toast_no_app_cluster), Toast.LENGTH_SHORT).show();
            return;
        }
        AppLogger.d(TAG, "showSplitMenu — app=" + mCurrentDashboardPkg
                + " slot=" + mCurrentSplitSlot
                + " second=" + mSecondDashboardPkg);
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, getString(R.string.split_full_screen));
        popup.getMenu().add(0, 2, 0, getString(R.string.split_left));
        popup.getMenu().add(0, 3, 0, getString(R.string.split_right));
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
     * Resizes the main app in the chosen slot via "am task resize".
     * slot 0 = full screen, 1 = left (0..W/2), 2 = right (W/2..W).
     */
    private void applySplitSlot(final int slot, final int l, final int t, final int r, final int b) {
        AppLogger.i(TAG, "applySplitSlot slot=" + slot
                + " bounds=[" + l + "," + t + "," + r + "," + b + "]"
                + " pkg=" + mCurrentDashboardPkg
                + " second=" + mSecondDashboardPkg);
        // Back to full screen: force-stop the second app if present
        if (slot == 0 && mSecondDashboardPkg != null) {
            AppLogger.i(TAG, "split → full screen: force-stop second=" + mSecondDashboardPkg);
            AdbLocalClient.forceStopApp(this, mSecondDashboardPkg, null);
            mSecondDashboardApp = null;
            mSecondDashboardPkg = null;
        }
        mCurrentSplitSlot = slot;
        // am task resize is blocked on DiLink 3.0 ("resizeTask not allowed" for StackId != FREEFORM).
        // Alternative: force-stop the app then relaunch it with the desired bounds.
        final String splitPkg = mCurrentDashboardPkg;
        final String splitApp = mCurrentDashboardApp;
        final int    splitL = l, splitT = t, splitR = r, splitB = b;
        AdbLocalClient.forceStopApp(this, splitPkg, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String ignored) {
                mClusterService.launchOnDashboardWithBounds(splitPkg, splitL, splitT, splitR, splitB,
                        new ClusterService.LaunchCallback() {
                    @Override public void onResult(boolean launched) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (launched) {
                                    mCurrentDashboardApp = splitApp;
                                    mCurrentDashboardPkg = splitPkg;
                                    AppLogger.i(TAG, "split slot " + slot + " OK ["
                                            + splitL + "," + splitT + "," + splitR + "," + splitB + "]");
                                    updateControlLabel();
                                } else {
                                    AppLogger.e(TAG, "split relaunch FAILED slot=" + slot);
                                    Toast.makeText(MainActivity.this,
                                            getString(R.string.toast_app_incompatible, splitApp),
                                            Toast.LENGTH_SHORT).show();
                                    mCurrentSplitSlot = 0;
                                }
                            }
                        });
                    }
                });
            }
            @Override public void onError(String error) {
                // force-stop failed: attempt relaunch anyway
                mClusterService.launchOnDashboardWithBounds(splitPkg, splitL, splitT, splitR, splitB,
                        new ClusterService.LaunchCallback() {
                    @Override public void onResult(boolean launched) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (launched) {
                                    mCurrentDashboardApp = splitApp;
                                    mCurrentDashboardPkg = splitPkg;
                                    updateControlLabel();
                                } else {
                                    mCurrentSplitSlot = 0;
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    /** Resets the split state (slot + second app). */
    private void clearSplitState() {
        if (mCurrentSplitSlot != 0 || mSecondDashboardPkg != null) {
            AppLogger.d(TAG, "clearSplitState — slot=" + mCurrentSplitSlot
                    + " second=" + mSecondDashboardPkg);
        }
        mSecondDashboardApp = null;
        mSecondDashboardPkg = null;
        mCurrentSplitSlot   = 0;
    }

    /**
     * Returns [width, height] of the cluster display in pixels.
     * Reads from the SurfaceControl mirror if available, otherwise fallback 1920×720.
     */
    private int[] getClusterDimensions() {
        if (mServiceBound && mClusterService != null) {
            int w = mClusterService.getMirrorManager().getClusterWidth();
            int h = mClusterService.getMirrorManager().getClusterHeight();
            if (w > 0 && h > 0) {
                AppLogger.d(TAG, "getClusterDimensions → mirror " + w + "×" + h);
                return new int[]{w, h};
            }
        }
        AppLogger.w(TAG, "getClusterDimensions → fallback 1920×720 (mirror not available)");
        return new int[]{1920, 720};
    }

    /** Updates the app label in the cluster panel (supports "App A  |  App B" in split mode). */
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

    // ---- Async loading of the app list ----

    /**
     * Loads the list of installed apps in a background thread, then publishes
     * the result on the main thread via Handler.
     *
     * HISTORY: before v2.07 this code used AsyncTask (API deprecated in API 30).
     * The hardware target being Android 10 (API 29 — BYD DiLink 3.0), AsyncTask
     * still worked, but its usage generates a warning and creates a strong implicit reference
     * on MainActivity (risk of leak if the list takes time to load).
     *
     * ── ROLLBACK to AsyncTask (if needed) ────────────────────────────────────────
     * 1. Replace this call in onCreate():
     *        loadAppsAsync();
     *    par :
     *        new LoadAppsTask().execute();
     *
     * 2. Delete this method (loadAppsAsync) and replace it with the inner class:
     *
     *    private class LoadAppsTask extends android.os.AsyncTask<Void, Void, List<AppInfo>> {
     *        @Override
     *        protected List<AppInfo> doInBackground(Void... voids) {
     *            PackageManager pm = getPackageManager();
     *            Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
     *            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
     *            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(launcherIntent, 0);
     *            List<AppInfo> apps = new ArrayList<>();
     *            for (ResolveInfo ri : resolveInfos) {
     *                String pkg = ri.activityInfo.packageName;
     *                if (pkg.equals(getPackageName())) continue;
     *                apps.add(new AppInfo(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)));
     *            }
     *            Collections.sort(apps, new Comparator<AppInfo>() {
     *                @Override public int compare(AppInfo a, AppInfo b) {
     *                    return a.appName.compareToIgnoreCase(b.appName);
     *                }
     *            });
     *            return apps;
     *        }
     *        @Override
     *        protected void onPostExecute(List<AppInfo> apps) {
     *            mAdapter.setApps(apps);
     *        }
     *    }
     *
     * 3. Re-add the import:  import android.os.AsyncTask;
     * ────────────────────────────────────────────────────────────────────────────
     */
    private void loadAppsAsync() {
        java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor();
        loader.execute(new Runnable() {
            @Override public void run() {
                PackageManager pm = getPackageManager();
                Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
                launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

                List<ResolveInfo> resolveInfos = pm.queryIntentActivities(launcherIntent, 0);
                List<AppInfo> apps = new ArrayList<>();
                final String ownPackage = getPackageName();

                for (ResolveInfo ri : resolveInfos) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(ownPackage)) continue;
                    String name = ri.loadLabel(pm).toString();
                    apps.add(new AppInfo(pkg, name, ri.loadIcon(pm)));
                }

                Collections.sort(apps, new Comparator<AppInfo>() {
                    @Override
                    public int compare(AppInfo a, AppInfo b) {
                        return a.appName.compareToIgnoreCase(b.appName);
                    }
                });

                final List<AppInfo> result = apps;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { mAdapter.setApps(result); }
                });
            }
        });
        loader.shutdown(); // thread ends as soon as the above task finishes
    }

    private void showKeyboardDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(getString(R.string.dialog_keyboard_hint));
        input.setSingleLine(true);

        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_keyboard_title))
            .setMessage(getString(R.string.dialog_keyboard_message))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_send), new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface dialog, int whichButton) {
                    final String text = input.getText().toString();
                    if (!text.isEmpty()) {
                        // On Android, "input text" takes space as %s
                        String escapedText = text.replace(" ", "%s").replace("\"", "\\\"");
                        AdbLocalClient.executeShell(MainActivity.this, "input text \"" + escapedText + "\"");
                    }
                }
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    }

}

