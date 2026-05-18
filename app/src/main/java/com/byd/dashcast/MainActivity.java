package com.byd.dashcast;

import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import com.byd.dashcast.model.AppShortcut;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import com.byd.dashcast.dashboard.DashboardLauncher;
import com.byd.dashcast.model.AppInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import androidx.recyclerview.widget.GridLayoutManager;

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

    // --- Resize Zone ---
    private android.widget.SeekBar sbResizeW;
    private android.widget.SeekBar sbResizeH;
    private android.widget.TextView tvResizeW;
    private android.widget.TextView tvResizeH;
    private android.widget.Button btnResizeApply;
    private android.widget.Button btnToggleResize;
     


    // Cluster service
    private ClusterService          mClusterService;
    private boolean                 mServiceBound    = false;
    private boolean                 mBindRequested   = false; // true as soon as a bindService is in progress
    private DashboardLauncher       mDashboardLauncher; // local reference updated after bind

    private static final String PREFS_NAME         = SettingsActivity.PREFS_NAME;
    /** Package of the app sent to the main display — persisted to survive Activity recreation */
    private static final String PREF_MAIN_PKG      = "main_display_pkg";
    /** Package/name of the app currently active on the cluster — persisted to survive Activity recreation */
    private static final String PREF_CLUSTER_PKG   = "cluster_active_pkg";
    private static final String PREF_CLUSTER_NAME  = "cluster_active_name";
    /** sendInfo code for cluster screen size: 29=8.8", 30=12.3" (default Seal EU), 31=10.25" */
    private static final String PREF_CLUSTER_TYPE = SettingsActivity.PREF_CLUSTER_TYPE;
    
    private static final String PREF_AUTO_LAUNCH_PKG = "auto_launch_pkg";
    private String mPendingAutoLaunchPkg = null;
    private AppInfo mPendingAppAfterActivation = null;
    private static final int    CLUSTER_TYPE_DEFAULT = 30;
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
            if (mDashboardLauncher != null) mDashboardLauncher.setDashboardDisplayId(-1);
            trackUsageStop(mCurrentDashboardPkg);
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

    private static final String PREF_FIRST_LAUNCH_TIP   = "first_launch_tip_shown";
    /** Last app voluntarily launched on the cluster — never cleared on disconnect, for reconnect reminder. */
    private static final String PREF_LAST_CLUSTER_PKG  = "last_cluster_pkg";
    private static final String PREF_LAST_CLUSTER_NAME = "last_cluster_name";
    /** Timeout before re-enabling the Activate button if the cluster never connects. */
    private static final long   ACTIVATE_TIMEOUT_MS    = 30_000;
    private Runnable            mActivateTimeoutRunnable = null;
    /** True if the current activation was triggered by the user (not Activity restore). */
    private boolean             mWasManualActivation   = false;

    // Status dot colors
    private static final int DOT_COLOR_OFF     = 0xFF888888;
    private static final int DOT_COLOR_PENDING = 0xFFFFC107;
    private static final int DOT_COLOR_ACTIVE  = 0xFF4CAF50;

    // UI — barre statut
    private View     mStatusDot;
    private TextView tvDashboardStatus;
    private View     llAppListSection;  // wrapper for title header + search bar
    private Button   btnActivateCluster;
    private Button   btnRestoreCluster;
    private Button   btnOverflow;
    private Button   btnShowMirror;
    private Button   btnSplitLayout;
    private Button   btnRelaunch;
    private Button   btnViewToggle;
    private RecyclerView rvApps;
    private AppListAdapter mAdapter;
    private android.widget.EditText etSearch;

    // UI — category filter buttons
    private View llCategoryFilters;
    private Button btnFilterAll, btnFilterNav, btnFilterMedia;

    // UI — profile spinner
    private android.widget.Spinner spinnerProfile;

    // Usage tracking
    private long mClusterAppStartTime = 0;

    // Session-scoped set of all packages that were launched on the cluster (display != 0).
    // Used to move them all back to Display 0 when the user stops the projection,
    // so Android doesn't re-launch them on the (still-alive) VirtualDisplay later.
    // Persisted to SharedPreferences so it survives a process kill (car shutdown).
    private final java.util.Set<String> mSessionClusterPackages = new java.util.LinkedHashSet<>();
    private static final String PREF_SESSION_CLUSTER_PKGS = "session_cluster_pkgs";

    // UI — cluster control panel
    private LinearLayout panelClusterControl;
    private LinearLayout panelResize;
    private LinearLayout panelControlsContent;
    private Button       btnPanelToggle;
    private TextView     tvControlAppName;
    private InsetOverlayView mInsetOverlay;
    private android.widget.FrameLayout frameMirror;
    private TextureView clusterMirror;
    private TextView     tvMirrorPlaceholder;
    private ImageView    clusterMirrorScreenshot;
    // Surface created from the TextureView's SurfaceTexture.
    // SF is the PRODUCER of this surface (setDisplaySurface) → TextureView renders.
    private Surface      mMirrorSurface;

    // Screenshot mirror loop (fallback when SurfaceControl.createDisplay() fails)
    private final Handler  mScreenshotHandler  = new Handler(Looper.getMainLooper());

    // Receiver for FloatingRemoteButton tap → open mirror panel from overlay
    private final BroadcastReceiver mShowMirrorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mCurrentDashboardApp == null) return; // no app on cluster
            showMirrorView();
            attemptStartMirrorWithCurrentHolder();
            AppLogger.d(TAG, "mShowMirrorReceiver → showMirrorView");
        }
    };

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

        // Safety-net: if projection auto-start is disabled, move any leftover
        // cluster apps back to Display 0 (covers case where BootReceiver couldn't run).
        SharedPreferences bootPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!bootPrefs.getBoolean("boot_auto_start_enabled", false)) {
            cleanupDisplayAffinityAtBoot(this);
        } else {
            // Auto-start enabled: clear the persisted set (projection is active,
            // apps will be managed normally).
            bootPrefs.edit().remove(PREF_SESSION_CLUSTER_PKGS).apply();
        }

        // Unlock hidden Android APIs (SurfaceControl, etc.)
        // Must be called before any call to ClusterMirrorManager.startMirror(this, ).
        // Same mechanism as WindowManagement v1.2 (VMRuntime.setHiddenApiExemptions).
        com.byd.dashcast.dashboard.ClusterMirrorManager.unlockHiddenApis();

        // Receiver to retrieve the MirrorDaemon Binder (uid=2000)
        registerReceiver(mDaemonReadyReceiver,
                new IntentFilter(com.byd.dashcast.daemon.MirrorDaemon.ACTION_DAEMON_READY));

        // Floating 📺 mirror button — started once, visibility controlled by show()/hide()
        startService(new Intent(this, FloatingRemoteButton.class));

        // Handle a tap on the floating button when the Activity is already alive
        // (Activity exists in back stack → onNewIntent fires instead of onCreate)
        handleShowMirrorIntent(getIntent());

        mStatusDot          = (View)     findViewById(R.id.view_status_dot);
        tvDashboardStatus   = (TextView) findViewById(R.id.tv_dashboard_status);
        btnActivateCluster  = (Button)   findViewById(R.id.btn_activate_cluster);
        btnRestoreCluster   = (Button)   findViewById(R.id.btn_restore_cluster);
        btnOverflow         = (Button)   findViewById(R.id.btn_overflow);
        btnShowMirror       = (Button)   findViewById(R.id.btn_show_mirror);
        llAppListSection    = (View)     findViewById(R.id.ll_app_list_section);
        rvApps             = (RecyclerView) findViewById(R.id.rv_apps);
        etSearch           = (android.widget.EditText) findViewById(R.id.et_search_apps);
        btnViewToggle      = (Button)   findViewById(R.id.btn_view_toggle);

        // App list
        mAdapter = new AppListAdapter(this);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isGrid = prefs.getBoolean("grid_mode", false);
        mAdapter.setGridMode(isGrid);
        updateViewToggleButton();
        
        if (isGrid) {
            rvApps.setLayoutManager(new GridLayoutManager(this, 5));
        } else {
            rvApps.setLayoutManager(new LinearLayoutManager(this));
        }
        
        rvApps.setAdapter(mAdapter);

        // Search bar
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAdapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Category filter buttons
        llCategoryFilters = findViewById(R.id.ll_category_filters);
        btnFilterAll   = (Button) findViewById(R.id.btn_filter_all);
        btnFilterNav   = (Button) findViewById(R.id.btn_filter_nav);
        btnFilterMedia = (Button) findViewById(R.id.btn_filter_media);
        boolean showFilters = prefs.getBoolean("show_category_filters", false);
        llCategoryFilters.setVisibility(showFilters ? View.VISIBLE : View.GONE);
        View.OnClickListener filterClick = new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cat = 0;
                if (v == btnFilterNav) cat = AppInfo.CATEGORY_NAVIGATION;
                else if (v == btnFilterMedia) cat = AppInfo.CATEGORY_MEDIA;
                mAdapter.filterByCategory(cat);
                updateCategoryFilterButtons(cat);
            }
        };
        btnFilterAll.setOnClickListener(filterClick);
        btnFilterNav.setOnClickListener(filterClick);
        btnFilterMedia.setOnClickListener(filterClick);

        // Profile spinner
        spinnerProfile = (android.widget.Spinner) findViewById(R.id.spinner_profile);
        setupProfileSpinner();

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

        // Button &#9654; View toggle (list ↔ grid) in the title header
        btnViewToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleViewMode(); }
        });

        // Button ⋮ overflow — dev tools + manual activation
        btnOverflow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflowMenu(v);
            }
        });

        // Button 📺 Mirror — reopen the mirror+tactile panel for the app running on the cluster
        btnShowMirror.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMirrorView();
                attemptStartMirrorWithCurrentHolder();
                AppLogger.d(TAG, "btn_show_mirror → showMirrorView for " + mCurrentDashboardApp);
            }
        });

        // Start ClusterService now (startForegroundService in onStart)
        mDashboardLauncher = new DashboardLauncher(this); // temporary until bind

        // Cluster control panel
        panelClusterControl   = (LinearLayout) findViewById(R.id.panel_cluster_control);
        panelControlsContent  = (LinearLayout) findViewById(R.id.panel_controls_content);
        tvControlAppName      = (TextView)     findViewById(R.id.tv_control_app_name);
        // llAppListSection replaces tvAppListTitle (see field declaration)
        btnPanelToggle        = (Button)       findViewById(R.id.btn_panel_toggle);

        // Panel collapse toggle
        btnPanelToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean visible = panelControlsContent.getVisibility() == View.VISIBLE;
                panelControlsContent.setVisibility(visible ? View.GONE : View.VISIBLE);
                btnPanelToggle.setText(visible ? "\u25b2" : "\u25bc");
            }
        });
        
        // --- Resize Zone ---
        btnToggleResize = (Button) findViewById(R.id.btn_toggle_resize);
        panelResize = (LinearLayout) findViewById(R.id.panel_resize);
        sbResizeW = (SeekBar) findViewById(R.id.sb_resize_w);
        sbResizeH = (SeekBar) findViewById(R.id.sb_resize_h);
        tvResizeW = (TextView) findViewById(R.id.tv_resize_w_val);
        tvResizeH = (TextView) findViewById(R.id.tv_resize_h_val);
        btnResizeApply = (Button) findViewById(R.id.btn_resize_apply);
        if (btnToggleResize != null) {
            btnToggleResize.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (panelResize != null) {
                        if (panelResize.getVisibility() == View.VISIBLE) {
                            panelResize.setVisibility(View.GONE);
                            if (mInsetOverlay != null) mInsetOverlay.setOverlayVisible(false);
                            btnToggleResize.setText(getString(R.string.btn_adjust));
                        } else {
                            panelResize.setVisibility(View.VISIBLE);
                            if (mInsetOverlay != null) {
                                refreshInsetOverlay();
                                mInsetOverlay.setOverlayVisible(true);
                            }
                            btnToggleResize.setText("\u25b2 " + getString(R.string.btn_adjust));
                        }
                    }
                }
            });
        }
        
        
        sbResizeW.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int value, boolean b) {
                tvResizeW.setText(String.valueOf(value));
                if (mInsetOverlay != null) mInsetOverlay.setInsets(value, sbResizeH.getProgress());
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        sbResizeH.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int value, boolean b) {
                tvResizeH.setText(String.valueOf(value));
                if (mInsetOverlay != null) mInsetOverlay.setInsets(sbResizeW.getProgress(), value);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        
        btnResizeApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrentDashboardPkg == null) return;
                int w = sbResizeW.getProgress();
                int h = sbResizeH.getProgress();
                SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                ed.putInt("inset_h_" + mCurrentDashboardPkg, w);
                ed.putInt("inset_v_" + mCurrentDashboardPkg, h);
                ed.apply();
                
                AppLogger.i(TAG, "Applied custom resize " + w + "/" + h + " for " + mCurrentDashboardPkg);
                
                if (mServiceBound && mClusterService != null) {
                    // findRunningTaskId() calls getRunningTasks() — must run off the main thread.
                    final String pkg = mCurrentDashboardPkg;
                    final ClusterService svc = mClusterService;
                    AdbLocalClient.executeShell(MainActivity.this, "wm overscan " + w + "," + h + "," + w + "," + h + " -d 1");
                    new Thread(new Runnable() {
                        @Override public void run() {
                            int taskId = svc.findRunningTaskId(pkg);
                            svc.resizeActiveTask(taskId, pkg);
                        }
                    }, "resize-task-thread").start();
                }
            }
        });

        frameMirror         = (android.widget.FrameLayout) findViewById(R.id.frame_cluster_mirror);
        clusterMirror       = (TextureView) findViewById(R.id.cluster_mirror);
        tvMirrorPlaceholder = (TextView)     findViewById(R.id.tv_mirror_placeholder);
        clusterMirrorScreenshot = (ImageView) findViewById(R.id.cluster_mirror_screenshot);
        mInsetOverlay       = (InsetOverlayView) findViewById(R.id.inset_overlay);

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
                // Release the old Surface before creating a new one to avoid a native
                // resource leak (Surface wraps an ANativeWindow whose refcount must reach 0).
                if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
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

        // Split button — cluster layout (full screen / left 50% / right 50%)
        btnSplitLayout = (Button) findViewById(R.id.btn_cluster_split);
        btnSplitLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showSplitMenu(v); }
        });

        // Relaunch button — force-stops current cluster app then relaunches it
        btnRelaunch = (Button) findViewById(R.id.btn_relaunch);
        btnRelaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { relaunchCurrentApp(); }
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

        // OTA update check — only on fresh launch, not on rotation
        if (savedInstanceState == null) {
            UpdateChecker.checkUpdate(this, makeOtaProgressListener(false));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShowMirrorIntent(intent);
    }

    private void handleShowMirrorIntent(Intent intent) {
        if (intent == null) return;
        if (FloatingRemoteButton.ACTION_SHOW_MIRROR.equals(intent.getAction())
                && mCurrentDashboardApp != null) {
            showMirrorView();
            attemptStartMirrorWithCurrentHolder();
            AppLogger.d(TAG, "handleShowMirrorIntent → showMirrorView for " + mCurrentDashboardApp);
        } else if (FloatingRemoteButton.ACTION_QUICK_SWITCH.equals(intent.getAction())) {
            String pkg = intent.getStringExtra(FloatingRemoteButton.EXTRA_QUICK_SWITCH_PKG);
            if (pkg != null) {
                AppLogger.i(TAG, "Quick-switch intent → " + pkg);
                // Simpler: use ClusterService directly to move/launch by package name
                quickSwitchToApp(pkg);
            }
        }
    }

    private void quickSwitchToApp(String pkgName) {
        if (mClusterService == null) return;
        if (pkgName.equals(mCurrentDashboardPkg)) {
            startClusterMirror();
            return;
        }
        trackUsageStop(mCurrentDashboardPkg);
        int displayId = mClusterService.getDisplayId();
        if (displayId < 0) displayId = 1;
        mClusterService.moveTaskToDisplay(pkgName, displayId, new ClusterService.LaunchCallback() {
            @Override public void onResult(boolean launched) {
                if (launched) {
                    mCurrentDashboardPkg = pkgName;
                    mSessionClusterPackages.add(pkgName);
                    persistSessionClusterPackages();
                    // Resolve app name
                    String name = pkgName;
                    try {
                        android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkgName, 0);
                        CharSequence label = getPackageManager().getApplicationLabel(ai);
                        if (label != null) name = label.toString();
                    } catch (Exception ignored) {}
                    mCurrentDashboardApp = name;
                    addToRecentApps(pkgName, name);
                    trackUsageStart();
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_CLUSTER_PKG, pkgName)
                            .putString(PREF_CLUSTER_NAME, name)
                            .putString(PREF_LAST_CLUSTER_PKG, pkgName)
                            .putString(PREF_LAST_CLUSTER_NAME, name).apply();
                    mAdapter.setCurrentPackage(pkgName);
                    updateDashboardStatus(mCurrentDashboardApp);
                    updateControlLabel();
                    startClusterMirror();
                    autoApplyInsetsIfNeeded(pkgName);
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStart");
        // Refresh category filter visibility (may have been toggled in Settings)
        boolean showFilters = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("show_category_filters", false);
        if (llCategoryFilters != null) {
            llCategoryFilters.setVisibility(showFilters ? View.VISIBLE : View.GONE);
        }
        registerReceiver(mShowMirrorReceiver,
                new IntentFilter(FloatingRemoteButton.ACTION_SHOW_MIRROR));
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
        try { unregisterReceiver(mShowMirrorReceiver); } catch (IllegalArgumentException ignored) {}
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
        try { unregisterReceiver(mShowMirrorReceiver); } catch (IllegalArgumentException ignored) {}
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
                cancelActivateTimeout();
                final boolean wasManual = mWasManualActivation;
                mWasManualActivation = false;
                updateDashboardStatus(null);
                btnActivateCluster.setEnabled(true);

                // Restore active cluster app if Activity was recreated (in-memory state lost).
                // mCurrentDashboardPkg is only null here if the Activity instance was killed
                // while in background (Home pressed) and a new instance was recreated.
                if (mCurrentDashboardPkg == null) {
                    SharedPreferences _p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    String _pkg  = _p.getString(PREF_CLUSTER_PKG, null);
                    String _name = _p.getString(PREF_CLUSTER_NAME, null);
                    if (_pkg != null) {
                        mCurrentDashboardPkg = _pkg;
                        mCurrentDashboardApp = _name;
                        mAdapter.setCurrentPackage(_pkg);
                        updateDashboardStatus(_name);
                        updateControlLabel();
                        showMirrorView(); // makes panelClusterControl visible
                        AppLogger.i(TAG, "cluster active app restored: " + _pkg);
                    }
                }

                // If the panel is visible (app already active), start/reconfigure the mirror
                if (panelClusterControl.getVisibility() == View.VISIBLE) {
                    attemptStartMirrorWithCurrentHolder();
                }

                // Restore mMainDisplayPkg if Activity was recreated
                if (mMainDisplayPkg == null) {
                    mMainDisplayPkg = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_MAIN_PKG, null);
                    if (mMainDisplayPkg != null) mAdapter.setMainPackage(mMainDisplayPkg);
                }
                
                // Pending app from "activate cluster" dialog
                if (mPendingAppAfterActivation != null) {
                    final AppInfo pending = mPendingAppAfterActivation;
                    mPendingAppAfterActivation = null;
                    AppLogger.i(TAG, "Auto-sending pending app after activation: " + pending.packageName);
                    onSendToDashboard(pending);
                }

                // Auto-Launch process
                if (mPendingAutoLaunchPkg != null) {
                    String targetPkg = mPendingAutoLaunchPkg;
                    mPendingAutoLaunchPkg = null; // Clear immediately
                    AppLogger.i(TAG, "Executing pending auto-launch for " + targetPkg);
                    // Find it and launch it
                    for (AppInfo a : mAdapter.getApps()) {
                        if (a.packageName.equals(targetPkg)) {
                            onSendToDashboard(a);
                            break;
                        }
                    }
                }

                // Reconnect reminder: if cluster was manually re-activated and
                // there was a last known app, offer to relaunch it.
                if (wasManual && mCurrentDashboardPkg == null) {
                    final SharedPreferences _pp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    final String lastPkg  = _pp.getString(PREF_LAST_CLUSTER_PKG, null);
                    final String lastName = _pp.getString(PREF_LAST_CLUSTER_NAME, null);
                    if (lastPkg != null && lastName != null) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle(getString(R.string.dialog_reconnect_title))
                            .setMessage(getString(R.string.dialog_reconnect_msg, lastName))
                            .setPositiveButton(getString(R.string.dialog_reconnect_yes), new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    for (AppInfo a : mAdapter.getApps()) {
                                        if (a.packageName.equals(lastPkg)) {
                                            onSendToDashboard(a);
                                            break;
                                        }
                                    }
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    }
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
                cancelActivateTimeout();
                mWasManualActivation = false;
                mCurrentDashboardApp = null;
                mCurrentDashboardPkg = null;
                btnActivateCluster.setEnabled(true);
                mMainDisplayPkg = null;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .remove(PREF_MAIN_PKG)
                        .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
                clearSplitState();
                mAdapter.setCurrentPackage(null);
                mAdapter.setMainPackage(null);
                tvDashboardStatus.setText(getString(R.string.status_disconnected));
                setStatusDot(DOT_COLOR_OFF);
                showAppList();
            }
        });
    }

    // ---- AppListAdapter.OnSendToDashboardListener ----

    @Override
    public void onSetAutoLaunch(AppInfo app, boolean enable) {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (enable) {
            p.edit().putString(PREF_AUTO_LAUNCH_PKG, app.packageName).apply();
            // Clear other auto launches in memory
            for (AppInfo a : mAdapter.getApps()) {
                a.isAutoLaunch = a.packageName.equals(app.packageName);
            }
        } else {
            p.edit().remove(PREF_AUTO_LAUNCH_PKG).apply();
            app.isAutoLaunch = false;
        }
        // Use post to avoid IllegalStateException (cannot call notify during bind)
        rvApps.post(new Runnable() {
            @Override
            public void run() {
                mAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onToggleFavorite(AppInfo app) {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> favs = new HashSet<>(p.getStringSet("favorites", new HashSet<>()));
        if (favs.contains(app.packageName)) {
            favs.remove(app.packageName);
            app.isFavorite = false;
        } else {
            favs.add(app.packageName);
            app.isFavorite = true;
        }
        p.edit().putStringSet("favorites", favs).apply();
        loadAppsAsync(); // Reload and re-sort
    }

    @Override
    public void onSendToDashboard(AppInfo app) {
        incrementLaunchCount(app.packageName);
        // Java displayId may not be resolved even when the cluster is active
        // (internal state unreliable on DiLink 3.0). We no longer block here:
        // ClusterService.launchOnDashboard() tries direct Binder then ADB relay
        // with displayId=1 hardcoded (Seal EU) as fallback → always functional.
        if (mClusterService == null) {
            AppLogger.e(TAG, "ClusterService null — prompt user to activate for " + app.packageName);
            showActivateClusterDialog(app);
            return;
        }

        AppLogger.log(TAG, "Envoi cluster — " + app.packageName
                + " display=" + mDashboardLauncher.getDashboardDisplayId());
        final String appName = app.appName;
        final String pkgName = app.packageName;

        // Guard: if this app is already on the cluster, just show the mirror — do NOT
        // call moveTaskToDisplay() again (it would perturb setTaskWindowingMode/resizeTask
        // and disrupt the cluster window, causing the mirror to flash/close).
        if (pkgName != null && pkgName.equals(mCurrentDashboardPkg)) {
            AppLogger.d(TAG, "onSendToDashboard: already on cluster — show mirror only");
            startClusterMirror();
            return;
        }

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
                        mSessionClusterPackages.add(pkgName);
                        persistSessionClusterPackages();
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

        // ── Normal behavior — move (or launch if not running) ──────────────────
        // moveTaskToDisplay() moves the existing task without killing it.
        // Falls back to launchOnDashboard() if no running task is found.
        int clusterDisplayId = mClusterService.getDisplayId();
        if (clusterDisplayId < 0) clusterDisplayId = 1; // Seal EU hardcoded fallback
        final int targetDisplayId = clusterDisplayId;
        mClusterService.moveTaskToDisplay(pkgName, targetDisplayId, new ClusterService.LaunchCallback() {
            @Override public void onResult(boolean launched) {
                AppLogger.log(TAG, "moveTaskToDisplay " + pkgName + " → display=" + targetDisplayId
                        + " " + (launched ? "OK" : "FAILED"));
                if (launched) {
                    // Track usage: stop timer for previous app, start for new one
                    trackUsageStop(mCurrentDashboardPkg);
                    mCurrentDashboardApp = appName;
                    mCurrentDashboardPkg = pkgName;
                    mSessionClusterPackages.add(pkgName);
                    persistSessionClusterPackages();
                    addToRecentApps(pkgName, appName);
                    trackUsageStart();
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_CLUSTER_PKG, pkgName)
                            .putString(PREF_CLUSTER_NAME, appName)
                            .putString(PREF_LAST_CLUSTER_PKG, pkgName)
                            .putString(PREF_LAST_CLUSTER_NAME, appName).apply();
                    mAdapter.setCurrentPackage(pkgName);
                    updateDashboardStatus(appName);
                    updateControlLabel();
                    startClusterMirror();
                    autoApplyInsetsIfNeeded(pkgName);
                } else {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.toast_app_incompatible, appName),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void incrementLaunchCount(String pkgName) {
        if (pkgName == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String key = "launch_count_" + pkgName;
        int count = prefs.getInt(key, 0);
        prefs.edit().putInt(key, count + 1).apply();
    }

    @Override
    public void onSendToMain(AppInfo app) {
        incrementLaunchCount(app.packageName);
        // Track usage: stop timer for the app leaving the cluster
        trackUsageStop(mCurrentDashboardPkg);
        // Clean up cluster state before move
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
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
        // Move the running task to display 0 without relaunching.
        // Falls back to launchOnMainDisplay() if no task is found.
        if (mServiceBound && mClusterService != null) {
            mClusterService.moveTaskToDisplay(app.packageName, 0, null);
        } else {
            mDashboardLauncher.launchOnMainDisplay(app.packageName);
        }
        AppLogger.log(TAG, "Send to main display — " + app.packageName);
    }

    @Override
    public void onKillApp(final AppInfo app) {
        // Confirm before killing — accidental taps are easy on a car touchscreen
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_kill_title))
            .setMessage(getString(R.string.confirm_kill_msg, app.appName))
            .setPositiveButton(getString(R.string.confirm_kill_ok), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { doKillApp(app); }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Performs the actual force-stop after the user confirmed. */
    private void doKillApp(final AppInfo app) {
        // 1. If the app is still on the cluster (mCurrentDashboardPkg matches),
        //    we do NOT stop projection or restore anything. We just kill it in memory.
        final boolean isOnCluster = mCurrentDashboardPkg != null
                && app.packageName != null
                && app.packageName.equals(mCurrentDashboardPkg);

        // 2. Move the app back to Display 0 before killing — safety net so that
        //    if force-stop fails silently, Android won't re-launch it on Display 1.
        if (mSessionClusterPackages.contains(app.packageName)
                && mServiceBound && mClusterService != null) {
            mClusterService.moveTaskToDisplay(app.packageName, 0, null);
        }
        mSessionClusterPackages.remove(app.packageName);
        persistSessionClusterPackages();

        // 3. am force-stop via ADB
        AdbLocalClient.forceStopApp(this, app.packageName, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        AppLogger.i(TAG, "forceStop " + app.packageName + " OK");
                        if (isOnCluster) {
                            mCurrentDashboardApp = null;
                            mCurrentDashboardPkg = null;
                            // Clear persisted state so the killed app is not ghost-restored
                            // if the Activity is destroyed and recreated later.
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                    .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
                            mAdapter.setCurrentPackage(null);
                            updateDashboardStatus(null);
                            // The virtual display remains alive and black, waiting for another app.
                        }
                        if (app.packageName != null && app.packageName.equals(mSecondDashboardPkg)) {
                            mSecondDashboardPkg = null;
                            clearSplitState();
                        }
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
    private com.byd.dashcast.dashboard.ClusterInputForwarder getInputForwarder() {
        if (mServiceBound && mClusterService != null) {
            return mClusterService.getInputForwarder();
        }
        return null;
    }

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

    // ---- External process-death detection -----------------------------------
    //
    // Removed in v0.1.43-alpha. The /proc-based watchdog and the OnUidImportanceListener
    // before it both produced false positives on this platform:
    //   - /proc is mounted with hidepid on Android 10, so a non-system app (uid=10xxx)
    //     cannot see PIDs of third-party packages → findPid() always returned -1 → watchdog
    //     incorrectly declared the app dead and cleared the cluster state.
    //   - OnUidImportanceListener fired immediately after launch because apps on a secondary
    //     VirtualDisplay (the cluster) are considered background by the importance model.
    //
    // We now trust our own state (mCurrentDashboardPkg / mMainDisplayPkg). State is only
    // cleared by explicit user actions (← Main, ✕ kill, restore, send-to-cluster of another
    // app). If the app dies externally (OOM kill, etc.) the user can simply tap restore to
    // reset everything — same UX as for the mirror, which is also preserved on transient
    // failures.

    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------

    /**
     * Hides the app list and displays the cluster mirror in full space.
     * Called from startClusterMirror().
     */
    private void showMirrorView() {
        llAppListSection.setVisibility(View.GONE);
        rvApps.setVisibility(View.GONE);
        frameMirror.setVisibility(View.VISIBLE);
        panelClusterControl.setVisibility(View.VISIBLE);
        // Always restore the controls panel when mirror is shown
        if (panelControlsContent != null) {
            panelControlsContent.setVisibility(View.VISIBLE);
            if (btnPanelToggle != null) btnPanelToggle.setText("\u25bc");
        }
        // Also hide overlay when switching app (resize not open by default)
        if (mInsetOverlay != null) mInsetOverlay.setOverlayVisible(false);
        if (btnToggleResize != null) btnToggleResize.setText(getString(R.string.btn_adjust));
        if (panelResize != null) panelResize.setVisibility(View.GONE);
        
        // Init Resize SeekBar based on current app or global prefs
        if (mCurrentDashboardPkg != null) {
            SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int defH = p.getInt(SettingsActivity.PREF_INSET_H, SettingsActivity.DEFAULT_INSET_H);
            int defV = p.getInt(SettingsActivity.PREF_INSET_V, SettingsActivity.DEFAULT_INSET_V);
            int curW = p.getInt("inset_h_" + mCurrentDashboardPkg, defH);
            int curH = p.getInt("inset_v_" + mCurrentDashboardPkg, defV);
            if (sbResizeW != null) {
                sbResizeW.setProgress(curW);
                tvResizeW.setText(String.valueOf(curW));
            }
            if (sbResizeH != null) {
                sbResizeH.setProgress(curH);
                tvResizeH.setText(String.valueOf(curH));
            }
        }
    }


    /**
     * If per-app insets have been saved for {@code pkg}, automatically applies them
     * (wm overscan + resizeActiveTask) 500 ms after a successful launch so the user
     * doesn't have to press Apply every time.
     */
    private void autoApplyInsetsIfNeeded(final String pkg) {
        if (pkg == null) return;
        final SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final int defH = p.getInt(SettingsActivity.PREF_INSET_H, SettingsActivity.DEFAULT_INSET_H);
        final int defV = p.getInt(SettingsActivity.PREF_INSET_V, SettingsActivity.DEFAULT_INSET_V);
        final int savedW = p.getInt("inset_h_" + pkg, defH);
        final int savedH = p.getInt("inset_v_" + pkg, defV);
        // Only apply if there are per-app custom insets (different from global defaults)
        if (savedW == defH && savedH == defV) return;
        AppLogger.d(TAG, "autoApplyInsets pkg=" + pkg + " w=" + savedW + " h=" + savedH);
        // Small delay: give the app time to render on the cluster before resizing
        mScreenshotHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!pkg.equals(mCurrentDashboardPkg)) return; // app changed in the meantime
                AdbLocalClient.executeShell(MainActivity.this,
                        "wm overscan " + savedW + "," + savedH + "," + savedW + "," + savedH + " -d 1");
                if (mServiceBound && mClusterService != null) {
                    final ClusterService svc = mClusterService;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            int taskId = svc.findRunningTaskId(pkg);
                            svc.resizeActiveTask(taskId, pkg);
                        }
                    }, "auto-resize-thread").start();
                }
            }
        }, 500);
    }

    /**
     * Hides the mirror and restores the app list.
     * Called from showAppList().
     */
    private void showAppList() {
        stopClusterMirror();
        frameMirror.setVisibility(View.GONE);
        panelClusterControl.setVisibility(View.GONE);
        llAppListSection.setAlpha(0f);
        llAppListSection.setVisibility(View.VISIBLE);
        llAppListSection.animate().alpha(1f).setDuration(150).start();
        rvApps.setAlpha(0f);
        rvApps.setVisibility(View.VISIBLE);
        rvApps.animate().alpha(1f).setDuration(150).start();
    }

    /**
     * Signals to MainActivity that an app was launched on the cluster → show the mirror.
     * Called from onSendToDashboard after a successful launch.
     */
    private void startClusterMirror() {
        AppLogger.d(TAG, "startClusterMirror app=" + mCurrentDashboardApp);
        showMirrorView();
        frameMirror.setAlpha(0f);
        frameMirror.animate().alpha(1f).setDuration(150).start();
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
            mClusterService.getMirrorManager().stopMirror();
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
        com.byd.dashcast.dashboard.ClusterInputForwarder forwarder = getInputForwarder();
        if (forwarder == null) return;

        com.byd.dashcast.dashboard.ClusterMirrorManager mirror =
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

        int pointerCount = event.getPointerCount();
        if (pointerCount <= 0) return;

        int[] pointerIds = new int[pointerCount];
        float[] clusterXs = new float[pointerCount];
        float[] clusterYs = new float[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            pointerIds[i] = event.getPointerId(i);
            float cx = (event.getX(i) - offsetX) / scale;
            float cy = (event.getY(i) - offsetY) / scale;
            clusterXs[i] = Math.max(0, Math.min(cx, clusterW - 1));
            clusterYs[i] = Math.max(0, Math.min(cy, clusterH - 1));
        }

        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                || event.getActionMasked() == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            int ai = event.getActionIndex();
            if (ai >= 0 && ai < pointerCount) {
                AppLogger.d(TAG, "touch → ptrs=" + pointerCount
                        + " action=" + event.getActionMasked()
                        + " idx=" + ai
                        + " view(" + (int)event.getX(ai) + "," + (int)event.getY(ai) + ")"
                        + " off=(" + (int)offsetX + "," + (int)offsetY + ")"
                        + " scale=" + String.format(java.util.Locale.US, "%.3f", scale)
                        + " cluster=(" + (int)clusterXs[ai] + "," + (int)clusterYs[ai]
                        + ")/" + clusterW + "×" + clusterH);
            }
        }

        forwarder.forwardTouchFinalMulti(
                pointerIds,
                clusterXs,
                clusterYs,
                event.getActionMasked(),
                event.getActionIndex(),
                pointerCount
        );
    }

    // ---- Restaurer l'affichage BYD d'origine ----

    private void showActivateClusterDialog(final AppInfo pendingApp) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.toast_cluster_unavailable))
                .setMessage(getString(R.string.dialog_cluster_not_active))
                .setPositiveButton(getString(R.string.dialog_activate_now), (dialog, which) -> {
                    activateCluster();
                    // After activation, auto-send the app once connected
                    mPendingAppAfterActivation = pendingApp;
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void activateCluster() {
        btnActivateCluster.setEnabled(false);
        tvDashboardStatus.setText(getString(R.string.status_activating_cluster));
        setStatusDot(DOT_COLOR_PENDING);
        mWasManualActivation = true;
        startActivateTimeout();
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
                setStatusDot(DOT_COLOR_PENDING);
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
    // ── OTA progress dialog ───────────────────────────────────────────────────

    /**
     * Returns a ProgressListener that shows a centered AlertDialog with a ProgressBar
     * during download, then switches to indeterminate while installing.
     *
     * @param notifyIfUpToDate if true, shows a toast when no update is found
     *                         (use true for manual checks, false for auto-check at launch)
     */
    private UpdateChecker.ProgressListener makeOtaProgressListener(boolean notifyIfUpToDate) {
        final AlertDialog[] dlgHolder  = {null};
        final ProgressBar[] pbHolder   = {null};
        final TextView[]    pctHolder  = {null};

        return new UpdateChecker.ProgressListener() {
            @Override
            public void onUpdateFound(final String version, final String changelog, final String downloadUrl) {
                if (isFinishing() || isDestroyed()) return;

                LinearLayout layout = new LinearLayout(MainActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (getResources().getDisplayMetrics().density * 20);
                layout.setPadding(pad, pad, pad, pad / 2);

                TextView tvVersion = new TextView(MainActivity.this);
                tvVersion.setText(getString(R.string.ota_version_label, version));
                tvVersion.setTextSize(16);
                tvVersion.setPadding(pad, 0, pad, pad / 2);
                tvVersion.setTextColor(getColor(R.color.text_accent));
                layout.addView(tvVersion);

                ScrollView sv = new ScrollView(MainActivity.this);
                TextView tvChangelog = new TextView(MainActivity.this);
                tvChangelog.setText(renderMarkdown(changelog));
                tvChangelog.setTextSize(13);
                tvChangelog.setPadding(pad, 0, pad, pad);
                tvChangelog.setTextColor(getColor(R.color.text_primary));
                sv.addView(tvChangelog);
                
                LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (getResources().getDisplayMetrics().density * 250) // max height
                );
                layout.addView(sv, svParams);

                // Progress bar container (initially hidden)
                final LinearLayout progressLayout = new LinearLayout(MainActivity.this);
                progressLayout.setOrientation(LinearLayout.VERTICAL);
                progressLayout.setPadding(pad, pad, pad, 0);
                progressLayout.setVisibility(View.GONE);

                ProgressBar pb = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(0);
                progressLayout.addView(pb);
                pbHolder[0] = pb;

                TextView tvPct = new TextView(MainActivity.this);
                tvPct.setText(getString(R.string.ota_progress_percent, 0));
                tvPct.setGravity(android.view.Gravity.CENTER);
                tvPct.setTextSize(12);
                tvPct.setTextColor(0xFF888888);
                progressLayout.addView(tvPct);
                pctHolder[0] = tvPct;

                layout.addView(progressLayout);

                dlgHolder[0] = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.ota_dialog_title))
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton(getString(R.string.ota_btn_update_now), null)
                        .setNegativeButton(getString(R.string.ota_btn_later), (dialog, which) -> dialog.dismiss())
                        .create();
                
                dlgHolder[0].setOnShowListener(dialog -> {
                    Button posButton = dlgHolder[0].getButton(AlertDialog.BUTTON_POSITIVE);
                    posButton.setOnClickListener(v -> {
                        posButton.setEnabled(false);
                        dlgHolder[0].getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        sv.setVisibility(View.GONE);
                        tvVersion.setText(getString(R.string.ota_downloading));
                        progressLayout.setVisibility(View.VISIBLE);
                        // Trigger download
                        UpdateChecker.startDownload(MainActivity.this, downloadUrl, this);
                    });
                });
                dlgHolder[0].show();
            }

            @Override
            public void onDownloadProgress(int percent) {
                if (pbHolder[0] == null) return;
                if (percent < 0) {
                    // Content-Length unknown → indeterminate
                    pbHolder[0].setIndeterminate(true);
                    if (pctHolder[0] != null) pctHolder[0].setText(getString(R.string.ota_progress_unknown));
                } else {
                    pbHolder[0].setIndeterminate(false);
                    pbHolder[0].setProgress(percent);
                    if (pctHolder[0] != null) pctHolder[0].setText(getString(R.string.ota_progress_percent, percent));
                }
            }

            @Override
            public void onInstalling() {
                // Dismiss the dialog — PackageInstaller takes over from here.
                // InstallResultReceiver handles success (app restarts) and failure (Toast).
                if (dlgHolder[0] != null) {
                    dlgHolder[0].dismiss();
                    dlgHolder[0] = null;
                }
            }

            @Override
            public void onUpToDate() {
                if (notifyIfUpToDate) {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.ota_up_to_date), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                if (dlgHolder[0] != null) {
                    dlgHolder[0].dismiss();
                    dlgHolder[0] = null;
                }
                AppLogger.e("OTA", "error: " + message);
            }
        };
    }

    // ── Overflow menu ─────────────────────────────────────────────────────────

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        // Group 0: user actions (Settings, Language, Updates, View toggle)
        popup.getMenu().add(0, 1, 0, getString(R.string.menu_settings));
        popup.getMenu().add(0, 5, 1, getString(R.string.menu_language));
        popup.getMenu().add(0, 6, 2, getString(R.string.menu_check_updates));
        popup.getMenu().add(0, 7, 3, mAdapter.isGridMode() ? getString(R.string.menu_view_list) : getString(R.string.menu_view_grid));
        popup.getMenu().add(0, 8, 4, getString(R.string.btn_origin_cluster));
        popup.getMenu().add(0, 9, 5, getString(R.string.menu_usage_stats));
        // Group 1: dev tools (with divider)
        popup.getMenu().add(1, 2, 6, getString(R.string.menu_diagnostic));
        popup.getMenu().add(1, 3, 7, getString(R.string.menu_system_report));
        popup.getMenu().add(1, 4, 8, getString(R.string.menu_log));
        // Enable visual divider between groups (API 28+, safe on our API 29 target)
        try {
            popup.getMenu().getClass()
                    .getDeclaredMethod("setGroupDividerEnabled", boolean.class)
                    .invoke(popup.getMenu(), true);
        } catch (Exception ignored) {
            AppLogger.d(TAG, "setGroupDividerEnabled unavailable: " + ignored.getMessage());
        }
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: startActivity(new Intent(MainActivity.this, SettingsActivity.class)); return true;
                    case 7:
                        toggleViewMode();
                        Toast.makeText(MainActivity.this, mAdapter.isGridMode() ? getString(R.string.toast_grid_mode_enabled) : getString(R.string.toast_list_mode_enabled), Toast.LENGTH_SHORT).show();
                        return true;
                    case 8: originCluster(); return true;
                    case 2: startActivity(new Intent(MainActivity.this, DiagActivity.class)); return true;
                    case 3: startActivity(new Intent(MainActivity.this, SysInfoActivity.class)); return true;
                    case 4: startActivity(new Intent(MainActivity.this, LogActivity.class)); return true;
                    case 5:
                        SharedPreferences p = getSharedPreferences(
                                LocaleHelper.PREF_FILE, MODE_PRIVATE);
                        p.edit().remove(LocaleHelper.PREF_SETUP_DONE).apply();
                        Intent intent = new Intent(MainActivity.this, WelcomeActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        return true;
                    case 6:
                        UpdateChecker.checkUpdate(MainActivity.this,
                                makeOtaProgressListener(true));
                        return true;
                    case 9:
                        showUsageStatsDialog();
                        return true;
                }
                return false;
            }
        });
        popup.show();
    }

    private void restoreBydDashboard() {
        btnRestoreCluster.setEnabled(false);
        tvDashboardStatus.setText(getString(R.string.status_restoring_cluster));
        setStatusDot(DOT_COLOR_PENDING);
        trackUsageStop(mCurrentDashboardPkg);

        // Move ALL apps that were launched on the cluster during this session back to Display 0.
        // This prevents Android from re-launching them on the (still-alive) VirtualDisplay
        // when the user opens the app from the BYD launcher after stopping the projection.
        moveSessionAppsToMainDisplay();

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
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
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

    /**
     * Moves every app that was launched on the cluster during this session back to Display 0.
     * Uses moveTaskToDisplay(pkg, 0) via ClusterService so Android remembers Display 0
     * as the last display for each app. Clears the session set afterwards.
     */
    private void moveSessionAppsToMainDisplay() {
        if (mSessionClusterPackages.isEmpty()) return;
        if (!mServiceBound || mClusterService == null) {
            // Keep the persisted set intact so boot/onCreate cleanup can still recover.
            AppLogger.w(TAG, "moveSessionAppsToMainDisplay: service not bound, preserving set for later cleanup");
            return;
        }
        AppLogger.i(TAG, "moveSessionAppsToMainDisplay: " + mSessionClusterPackages.size()
                + " apps → " + mSessionClusterPackages);
        for (String pkg : mSessionClusterPackages) {
            mClusterService.moveTaskToDisplay(pkg, 0, null);
        }
        mSessionClusterPackages.clear();
        persistSessionClusterPackages();
    }

    /** Persists the session cluster packages set to SharedPreferences. */
    private void persistSessionClusterPackages() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putStringSet(PREF_SESSION_CLUSTER_PKGS, new java.util.HashSet<>(mSessionClusterPackages))
                .apply();
    }

    /**
     * Boot/onCreate safety net: moves all previously-tracked cluster apps to Display 0
     * using IActivityTaskManager reflection (no ClusterService needed).
     * Only runs if boot_auto_start_enabled is false.
     */
    static void cleanupDisplayAffinityAtBoot(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        java.util.Set<String> pkgs = prefs.getStringSet(PREF_SESSION_CLUSTER_PKGS, null);
        if (pkgs == null || pkgs.isEmpty()) {
            AppLogger.d("DisplayCleanup", "No session cluster packages to clean up");
            return;
        }
        java.util.Set<String> remaining = new java.util.HashSet<>(pkgs);
        AppLogger.i("DisplayCleanup", "Cleaning up " + pkgs.size() + " apps → Display 0: " + pkgs);
        for (String pkg : pkgs) {
            if (moveTaskToDisplayZero(pkg)) {
                remaining.remove(pkg);
            }
        }
        // Keep only failed packages for a later retry (next boot/app launch).
        if (remaining.isEmpty()) {
            prefs.edit().remove(PREF_SESSION_CLUSTER_PKGS).apply();
        } else {
            prefs.edit().putStringSet(PREF_SESSION_CLUSTER_PKGS, remaining).apply();
            AppLogger.w("DisplayCleanup", "Cleanup partially failed, keeping pending set: " + remaining);
        }
    }

    /** Moves a single package's task to Display 0 via IActivityTaskManager reflection. */
    private static boolean moveTaskToDisplayZero(String packageName) {
        try {
            // Find the task ID
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object iatm = atmClass.getMethod("getService").invoke(null);
            // Use IActivityTaskManager.getTasks(100) — hidden but available with platform signing
            @SuppressWarnings("unchecked")
            java.util.List<?> tasks = (java.util.List<?>) iatm.getClass()
                    .getMethod("getTasks", int.class).invoke(iatm, 100);
            if (tasks == null) return false;
            for (Object taskInfo : tasks) {
                // RecentTaskInfo or RunningTaskInfo — both extend TaskInfo with baseActivity
                android.content.ComponentName base = (android.content.ComponentName)
                        taskInfo.getClass().getField("baseActivity").get(taskInfo);
                if (base != null && packageName.equals(base.getPackageName())) {
                    int taskId = taskInfo.getClass().getField("taskId").getInt(taskInfo);
                    iatm.getClass().getMethod("moveTaskToDisplay", int.class, int.class)
                            .invoke(iatm, taskId, 0);
                    AppLogger.i("DisplayCleanup", "Moved " + packageName
                            + " (taskId=" + taskId + ") → Display 0");
                    return true;
                }
            }
            AppLogger.w("DisplayCleanup", "No running task found for " + packageName);
            return false;
        } catch (Exception e) {
            AppLogger.w("DisplayCleanup", "Could not move " + packageName + " to Display 0: " + e.getMessage());
            return false;
        }
    }

    private void updateDashboardStatus(String appName) {
        tvDashboardStatus.setTextColor(Color.WHITE);
        if (appName == null) {
            tvDashboardStatus.setText(getString(R.string.status_dashboard_byd));
            // No app on cluster — hide the mirror shortcut and the floating button
            btnShowMirror.setVisibility(View.GONE);
            FloatingRemoteButton.hide();
        } else {
            tvDashboardStatus.setText(getString(R.string.status_dashboard_app, appName));
            // App active on cluster — show the mirror shortcut and the floating button
            btnShowMirror.setVisibility(View.VISIBLE);
            FloatingRemoteButton.show();
        }
        setStatusDot(DOT_COLOR_ACTIVE);
        btnRestoreCluster.setEnabled(true);
    }

    /** Sets the status dot to a given ARGB color using an oval GradientDrawable. */
    private void setStatusDot(int color) {
        if (mStatusDot == null) return;
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(color);
        mStatusDot.setBackground(shape);
    }

    /** Toggles list ↔ grid mode, updates the toggle button icon and adapter layout. */
    private void toggleViewMode() {
        SharedPreferences p2 = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean nv = !mAdapter.isGridMode();
        p2.edit().putBoolean("grid_mode", nv).apply();
        mAdapter.setGridMode(nv);
        if (nv) {
            rvApps.setLayoutManager(new GridLayoutManager(this, 5));
        } else {
            rvApps.setLayoutManager(new LinearLayoutManager(this));
        }
        rvApps.setAdapter(mAdapter);
        updateViewToggleButton();
    }

    /** Syncs the view-toggle button icon to the current mode. */
    private void updateViewToggleButton() {
        if (btnViewToggle == null) return;
        btnViewToggle.setText(mAdapter.isGridMode() ? "\u2630" : "\u229e");
    }

    /** Updates the split button text/tint to reflect the current slot. */
    private void updateSplitButton() {
        if (btnSplitLayout == null) return;
        if (mCurrentSplitSlot != 0) {
            btnSplitLayout.setText(getString(R.string.split_btn_exit));
            btnSplitLayout.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.split_active)));
        } else {
            btnSplitLayout.setText(getString(R.string.btn_cluster_split));
            btnSplitLayout.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.split_inactive)));
        }
    }

    /** Refreshes the InsetOverlayView projection params from the current mirror state. */
    private void refreshInsetOverlay() {
        if (mInsetOverlay == null || !mServiceBound || mClusterService == null) return;
        com.byd.dashcast.dashboard.ClusterMirrorManager mirror = mClusterService.getMirrorManager();
        mInsetOverlay.setProjection(mirror.getProjScale(), mirror.getProjOffsetX(), mirror.getProjOffsetY());
        mInsetOverlay.setInsets(sbResizeW.getProgress(), sbResizeH.getProgress());
    }

    // ── Activate timeout ──────────────────────────────────────────────────────

    /** Posts a 30-second timeout that re-enables the Activate button if the cluster never connects. */
    private void startActivateTimeout() {
        cancelActivateTimeout();
        mActivateTimeoutRunnable = new Runnable() {
            @Override public void run() {
                mActivateTimeoutRunnable = null;
                mWasManualActivation = false;
                btnActivateCluster.setEnabled(true);
                setStatusDot(DOT_COLOR_OFF);
                tvDashboardStatus.setText(getString(R.string.status_disconnected));
                Toast.makeText(MainActivity.this,
                        getString(R.string.toast_activate_timeout), Toast.LENGTH_LONG).show();
                AppLogger.w(TAG, "Activate cluster timeout (30s)");
            }
        };
        mScreenshotHandler.postDelayed(mActivateTimeoutRunnable, ACTIVATE_TIMEOUT_MS);
    }

    /** Cancels the pending activate timeout if any. */
    private void cancelActivateTimeout() {
        if (mActivateTimeoutRunnable != null) {
            mScreenshotHandler.removeCallbacks(mActivateTimeoutRunnable);
            mActivateTimeoutRunnable = null;
        }
    }

    // ── Relaunch current cluster app ─────────────────────────────────────────

    /** Force-stops then relaunches the app currently active on the cluster. */
    private void relaunchCurrentApp() {
        if (mCurrentDashboardPkg == null) return;
        final String pkg  = mCurrentDashboardPkg;
        final String name = mCurrentDashboardApp;
        AppLogger.i(TAG, "relaunchCurrentApp → " + pkg);
        AdbLocalClient.forceStopApp(this, pkg, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String ignored) {
                // Find AppInfo and relaunch through normal flow
                for (AppInfo a : mAdapter.getApps()) {
                    if (pkg.equals(a.packageName)) {
                        mCurrentDashboardPkg = null; // clear so onSendToDashboard doesn't bail early
                        mCurrentDashboardApp = null;
                        runOnUiThread(new Runnable() {
                            @Override public void run() { onSendToDashboard(a); }
                        });
                        return;
                    }
                }
                AppLogger.w(TAG, "relaunchCurrentApp: pkg not found in list — " + pkg);
            }
            @Override public void onError(String error) {
                AppLogger.w(TAG, "relaunchCurrentApp: forceStop error: " + error);
                // Try relaunch anyway
                for (AppInfo a : mAdapter.getApps()) {
                    if (pkg.equals(a.packageName)) {
                        mCurrentDashboardPkg = null;
                        mCurrentDashboardApp = null;
                        runOnUiThread(new Runnable() {
                            @Override public void run() { onSendToDashboard(a); }
                        });
                        return;
                    }
                }
            }
        });
    }

    // ── Markdown renderer for OTA changelog ──────────────────────────────────

    /**
     * Converts a simple GitHub Markdown string to a styled SpannableStringBuilder.
     * Handles: ## heading, ### heading, - bullet, * bullet, **bold**.
     */
    private static CharSequence renderMarkdown(String raw) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = raw.split("\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            boolean bold = false;
            float relSize = 0f;
            if (line.startsWith("## ")) {
                line = line.substring(3);
                bold = true; relSize = 1.15f;
            } else if (line.startsWith("### ")) {
                line = line.substring(4);
                bold = true;
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                line = "\u2022 " + line.substring(2);
            }
            int lineStart = sb.length();
            appendWithInlineBold(sb, line);
            int lineEnd = sb.length();
            if (bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD),
                        lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (relSize > 0f) {
                sb.setSpan(new RelativeSizeSpan(relSize),
                        lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (li < lines.length - 1) sb.append('\n');
        }
        return sb;
    }

    /** Appends {@code text} to {@code sb}, converting **bold** markers to bold spans. */
    private static void appendWithInlineBold(SpannableStringBuilder sb, String text) {
        int i = 0;
        while (i < text.length()) {
            int boldStart = text.indexOf("**", i);
            if (boldStart < 0) { sb.append(text.substring(i)); break; }
            sb.append(text.substring(i, boldStart));
            int boldEnd = text.indexOf("**", boldStart + 2);
            if (boldEnd < 0) { sb.append(text.substring(boldStart)); break; }
            int spanStart = sb.length();
            sb.append(text.substring(boldStart + 2, boldEnd));
            sb.setSpan(new StyleSpan(Typeface.BOLD),
                    spanStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = boldEnd + 2;
        }
    }

    /** Original cluster — sendInfo(screenSize) + sendInfo(18) + sendInfo(0). */
    private void originCluster() {
        tvDashboardStatus.setText(getString(R.string.status_restoring_origin));
        setStatusDot(DOT_COLOR_PENDING);
        moveSessionAppsToMainDisplay();
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
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
                        clearSplitState();
                        mAdapter.setCurrentPackage(null);
                        updateDashboardStatus(null);
                        btnActivateCluster.setEnabled(true);
                        showAppList();
                        AppLogger.log(TAG, "Original cluster restored ✓");
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
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
                                    updateSplitButton();
                                } else {
                                    AppLogger.e(TAG, "split relaunch FAILED slot=" + slot);
                                    Toast.makeText(MainActivity.this,
                                            getString(R.string.toast_app_incompatible, splitApp),
                                            Toast.LENGTH_SHORT).show();
                                    mCurrentSplitSlot = 0;
                                    updateSplitButton();
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
        runOnUiThread(new Runnable() { @Override public void run() { updateSplitButton(); } });
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
                    AppInfo appInfo = new AppInfo(pkg, name, ri.loadIcon(pm));
                    
                    // Fetch shortcuts (API 25+)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                        try {
                            android.content.pm.LauncherApps launcherApps = (android.content.pm.LauncherApps) getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE);
                            if (launcherApps != null && launcherApps.hasShortcutHostPermission()) {
                                android.content.pm.LauncherApps.ShortcutQuery query = new android.content.pm.LauncherApps.ShortcutQuery();
                                query.setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                                query.setPackage(pkg);
                                
                                java.util.List<android.content.pm.ShortcutInfo> shortcuts = launcherApps.getShortcuts(query, android.os.Process.myUserHandle());
                                if (shortcuts != null) {
                                    for (android.content.pm.ShortcutInfo shortcut : shortcuts) {
                                        android.graphics.drawable.Drawable shortcutIcon = launcherApps.getShortcutIconDrawable(shortcut, getResources().getDisplayMetrics().densityDpi);
                                        appInfo.shortcuts.add(new AppShortcut(shortcut.getId(), shortcut.getShortLabel().toString(), shortcutIcon, null));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Ignored: app has no shortcuts or no permission
                        }
                    }
                    
                    apps.add(appInfo);
                }

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                Set<String> favs = prefs.getStringSet("favorites", new HashSet<>());
                String autoPkg = prefs.getString(PREF_AUTO_LAUNCH_PKG, null);

                for (AppInfo info : apps) {
                    if (favs.contains(info.packageName)) {
                        info.isFavorite = true;
                    }
                    if (autoPkg != null && autoPkg.equals(info.packageName)) {
                        info.isAutoLaunch = true;
                    }
                    info.launchCount = prefs.getInt("launch_count_" + info.packageName, 0);
                }

                Collections.sort(apps, new Comparator<AppInfo>() {
                    @Override
                    public int compare(AppInfo a, AppInfo b) {
                        // 1. Group automatically by Category (Navigation -> Media -> Others)
                        if (a.category != b.category) {
                            return Integer.compare(a.category, b.category);
                        }
                        // 2. Inside the category, push Favorites to the top
                        if (a.isFavorite && !b.isFavorite) return -1;
                        if (!a.isFavorite && b.isFavorite) return 1;
                        // 3. Then sort by usage frequency
                        if (a.launchCount != b.launchCount) {
                            return Integer.compare(b.launchCount, a.launchCount); // descending
                        }
                        // 4. Alphabetical fallback
                        return a.appName.compareToIgnoreCase(b.appName);
                    }
                });

                final List<AppInfo> result = apps;
                runOnUiThread(() -> {
                    mAdapter.setApps(result);
                    // One-shot tip: show once, on first ever launch
                    SharedPreferences _p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    if (!_p.getBoolean(PREF_FIRST_LAUNCH_TIP, false)) {
                        _p.edit().putBoolean(PREF_FIRST_LAUNCH_TIP, true).apply();
                        mScreenshotHandler.postDelayed(() ->
                                Toast.makeText(MainActivity.this,
                                        getString(R.string.tooltip_tap_send),
                                        Toast.LENGTH_LONG).show(),
                                1200);
                    }
                });
            }
        });
        loader.shutdown(); // thread ends as soon as the above task finishes
    }

    // ── Category filter helpers ──────────────────────────────────────────────

    private void updateCategoryFilterButtons(int activeCategory) {
        int activeTint   = android.graphics.Color.parseColor("#1976D2");
        int inactiveTint = android.graphics.Color.parseColor("#607D8B");
        btnFilterAll.getBackground().setTint(activeCategory == 0 ? activeTint : inactiveTint);
        btnFilterNav.getBackground().setTint(activeCategory == AppInfo.CATEGORY_NAVIGATION ? activeTint : inactiveTint);
        btnFilterMedia.getBackground().setTint(activeCategory == AppInfo.CATEGORY_MEDIA ? activeTint : inactiveTint);
    }

    // ── Profile spinner ─────────────────────────────────────────────────────

    private void setupProfileSpinner() {
        final String[] profileLabels = {
            getString(R.string.profile_none),
            getString(R.string.profile_driving),
            getString(R.string.profile_parking)
        };
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, profileLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProfile.setAdapter(adapter);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedProfile = prefs.getInt("profile_mode", 0);
        spinnerProfile.setSelection(savedProfile, false);

        spinnerProfile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putInt("profile_mode", position).apply();
                AppLogger.i(TAG, "Profile selected: " + profileLabels[position]);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    // ── Quick-switch history ────────────────────────────────────────────────

    private static final String PREF_RECENT_APPS = "recent_cluster_apps";
    private static final int MAX_RECENT_APPS = 3;

    private void addToRecentApps(String pkgName, String appName) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String raw = prefs.getString(PREF_RECENT_APPS, "");
        // Format: "pkg|name;;pkg|name;;pkg|name"
        java.util.LinkedList<String> entries = new java.util.LinkedList<>();
        if (!raw.isEmpty()) {
            for (String e : raw.split(";;")) entries.add(e);
        }
        String newEntry = pkgName + "|" + appName;
        entries.remove(newEntry); // avoid duplicates
        entries.addFirst(newEntry);
        while (entries.size() > MAX_RECENT_APPS) entries.removeLast();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(";;");
            sb.append(entries.get(i));
        }
        prefs.edit().putString(PREF_RECENT_APPS, sb.toString()).apply();
    }

    // ── Usage stats ─────────────────────────────────────────────────────────

    private void trackUsageStart() {
        mClusterAppStartTime = System.currentTimeMillis();
    }

    private void trackUsageStop(String pkgName) {
        if (mClusterAppStartTime <= 0 || pkgName == null) return;
        long elapsed = System.currentTimeMillis() - mClusterAppStartTime;
        mClusterAppStartTime = 0;
        if (elapsed < 1000) return; // ignore sub-second
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long prev = prefs.getLong("usage_ms_" + pkgName, 0);
        prefs.edit().putLong("usage_ms_" + pkgName, prev + elapsed).apply();
    }

    private void showUsageStatsDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        java.util.Map<String, ?> all = prefs.getAll();
        java.util.List<String[]> stats = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getKey().startsWith("usage_ms_") && entry.getValue() instanceof Long) {
                String pkg = entry.getKey().substring("usage_ms_".length());
                long ms = (Long) entry.getValue();
                // Resolve app name
                String name = pkg;
                try {
                    android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                    CharSequence label = getPackageManager().getApplicationLabel(ai);
                    if (label != null) name = label.toString();
                } catch (Exception ignored) {}
                stats.add(new String[] { name, formatDuration(ms) });
            }
        }
        if (stats.isEmpty()) {
            Toast.makeText(this, getString(R.string.usage_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        // Sort by name
        java.util.Collections.sort(stats, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        StringBuilder sb = new StringBuilder();
        for (String[] s : stats) {
            sb.append(s[0]).append(" — ").append(s[1]).append("\n");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.usage_title))
                .setMessage(sb.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(getString(R.string.usage_reset), (d, w) -> {
                    SharedPreferences.Editor editor = prefs.edit();
                    for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                        if (entry.getKey().startsWith("usage_ms_")) editor.remove(entry.getKey());
                    }
                    editor.apply();
                    Toast.makeText(this, "✓", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }

}


