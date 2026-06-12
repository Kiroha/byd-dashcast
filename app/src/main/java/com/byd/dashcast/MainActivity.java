package com.byd.dashcast;

import com.byd.dashcast.proxy.ShellGateway;
import com.byd.dashcast.app.AppStartupTasks;
import com.byd.dashcast.system.FloatingRemoteButton;
import com.byd.dashcast.ui.AppListAdapter;
import com.byd.dashcast.update.OtaProgressUi;
import com.byd.dashcast.update.UpdateChecker;
import com.byd.dashcast.ui.nav.NavRailLayouts;
import com.byd.dashcast.ui.nav.NavRailSetup;
import com.byd.dashcast.app.BootDisplayCleanup;
import com.byd.dashcast.proxy.DaemonBinderResolver;
import com.byd.dashcast.ui.InsetOverlayView;
import com.byd.dashcast.util.LocaleHelper;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.cluster.ClusterService;
import com.byd.dashcast.cluster.ClusterSessionTracker;
import com.byd.dashcast.ime.KeyboardBridgeActivity;
import com.byd.dashcast.voice.VoiceCommandReceiver;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.ui.main.ActivateTimeoutManager;
import com.byd.dashcast.ui.main.InsetAutoApplicator;
import com.byd.dashcast.ui.main.AppActionSheet;
import com.byd.dashcast.ui.main.AppListCoordinator;
import com.byd.dashcast.ui.main.DisplayStatePollCoordinator;
import com.byd.dashcast.ui.main.OverflowMenuHelper;
import com.byd.dashcast.ui.main.ClusterControlCoordinator;
import com.byd.dashcast.ui.main.FissionCoordinator;
import com.byd.dashcast.ui.main.NavigationCoordinator;
import com.byd.dashcast.ui.main.MirrorCoordinator;
import com.byd.dashcast.ui.main.FullscreenMirrorCoordinator;
import com.byd.dashcast.ui.main.SplitController;
import com.byd.dashcast.ui.main.PermissionBannerCoordinator;
import com.byd.dashcast.ui.main.UsageTracker;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
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
import android.view.MotionEvent;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
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

import com.byd.dashcast.cluster.display.DashboardLauncher;
import com.byd.dashcast.data.apps.AppRepository;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.model.AppInfo;
import com.byd.dashcast.voice.VoiceCommandRouter;

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
@android.annotation.SuppressLint({"ClickableViewAccessibility","SetTextI18n"}) // cluster touches forwarded to display 1; debug labels intentional
@SuppressWarnings("deprecation")
public class MainActivity extends AppCompatActivity
        implements ClusterService.Listener,
                   AppListAdapter.OnSendToDashboardListener,
                   AppListCoordinator.Host,
                   ClusterControlCoordinator.Host,
                   NavigationCoordinator.Host,
                   MirrorCoordinator.Host,
                   FullscreenMirrorCoordinator.Host,
                   SplitController.Host,
                   PermissionBannerCoordinator.Host,
                   UsageTracker.Host,
                   FissionCoordinator.Host,
                   AppActionSheet.Host,
                   OverflowMenuHelper.Host,
                   DisplayStatePollCoordinator.Host,
                   ActivateTimeoutManager.Host,
                   InsetAutoApplicator.Host {

    private static final String TAG = "BYDApp";
    // Orphan sniffer kill must only run once per process lifetime (first cold start).
    // Subsequent MainActivity instances created by in-app navigation (e.g. DiagActivity
    // tapping the Apps nav button) must not clobber a legitimately running sniffer.
    private static boolean sOrphanSnifferKillDone = false;

    // --- Resize Zone ---
     


    // Cluster service
    private ClusterService          mClusterService;
    private boolean                 mServiceBound    = false;
    private boolean                 mBindRequested   = false; // true as soon as a bindService is in progress
    private DashboardLauncher       mDashboardLauncher; // local reference updated after bind

    private static final String PREFS_NAME         = ClusterPrefs.PREFS_NAME;
    /** Single source of truth for app loading, caching, and favorite/auto-launch mutations. */
    private final AppRepository mAppRepo = new AppRepository();
    private String mPendingAutoLaunchPkg = null;
    private AppInfo mPendingAppAfterActivation = null;
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
            if (mUsageTracker != null) mUsageTracker.trackStop(mCurrentDashboardPkg);
            mCurrentDashboardApp = null;
            mCurrentDashboardPkg = null;
            mMainDisplayPkg      = null;
            if (mSplitController != null) mSplitController.clearSplitState();
            if (mAppListCoordinator != null) mAppListCoordinator.setCurrentPackage(null);
            if (mAppListCoordinator != null) mAppListCoordinator.setMainPackage(null);
            AppLogger.log(TAG, "ClusterService disconnected");
        }
    };
    private String mCurrentDashboardApp = null;  // readable name (displayed in the status bar)
    private String mCurrentDashboardPkg = null;   // package name (for am force-stop)
    private String mMainDisplayPkg      = null;   // package sent to the main display (button "→ Cluster")

    /** True if the current activation was triggered by the user (not Activity restore). */
    private boolean             mWasManualActivation   = false;
    private ActivateTimeoutManager mTimeoutManager;
    private InsetAutoApplicator    mInsetApplicator;

    // UI — barre statut
    private View     llAppListSection;  // wrapper for title header + search bar
                                          // The 8 setEnabled() callsites below are wrapped by setActivateBtnEnabled(),
                                          // a null-safe helper, so the call graph stays unchanged.
    private Button   btnRestoreCluster;
    private android.widget.ImageView ivNavLogo; // v0.9.81: long-press = overflow menu
    private Button   btnShowMirror;

    // v0.9.74 — Favorites horizontal strip

    // v0.9.74 — Pseudo-fullscreen mirror state (managed by FullscreenMirrorCoordinator)
    private com.google.android.material.floatingactionbutton.FloatingActionButton btnExitFullscreen;

    // v0.9.79 — root overlay for reparented control panel (managed by FullscreenMirrorCoordinator)
    private android.widget.FrameLayout vRootOverlay;

    // UI — category filter buttons
    private View llCategoryFilters;

    private UsageTracker                 mUsageTracker;

    private ClusterSessionTracker mSessionTracker;
    // UI — cluster control panel
    private InsetOverlayView mInsetOverlay;
    private android.widget.FrameLayout frameMirror;
    private TextureView clusterMirror;
    // ── Coordinators / Controllers ────────────────────────────────────────────
    private AppListCoordinator          mAppListCoordinator;
    private ClusterControlCoordinator   mClusterControlCoordinator;
    private NavigationCoordinator       mNavCoordinator;
    private MirrorCoordinator           mMirrorCoordinator;
    private FullscreenMirrorCoordinator  mFullscreenCoordinator;
    private SplitController              mSplitController;
    private PermissionBannerCoordinator  mPermissionBannerCoordinator;
    private FissionCoordinator           mFissionCoordinator;
    private DisplayStatePollCoordinator  mStatePollCoordinator;

    // Grace period check for state poll
    private long         mLastLaunchTime = 0;

    private final Handler  mScreenshotHandler  = new Handler(Looper.getMainLooper());

    // MirrorDaemon — Binder received via broadcast ACTION_DAEMON_READY
    private IBinder mDaemonBinder = null;
    private final BroadcastReceiver mDaemonReadyReceiver = DaemonBinderResolver.createActionReceiver(
            binder -> {
                mDaemonBinder = binder;
                final ClusterService svc = mServiceBound ? mClusterService : null;
                if (svc != null) svc.getInputForwarder().setDaemonBinder(binder);
                if (mMirrorCoordinator != null) mMirrorCoordinator.onDaemonBinderAvailable(binder);
            });

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
        // Auto-launch (cf. ClusterPrefs.getAutoLaunchPkg / onSetAutoLaunch / onClusterDisplayConnected).
        // The pending package is consumed at most once per Activity instance, in the cluster-connect
        // callback. Setting it here ensures that if the user previously marked an app as auto-launch,
        // it is sent to the cluster as soon as the dashboard display becomes available.
        mPendingAutoLaunchPkg = ClusterPrefs.getAutoLaunchPkg(this);
        if (!ClusterPrefs.isBootAutoStartEnabled(this)) {
            // Defensive: cleanup uses IActivityTaskManager binder reflection per package.
            // With a non-trivial persisted set, calling on the main thread during onCreate
            // could approach the ANR threshold. Off-load to a named daemon thread; the work
            // is purely a safety-net (BootReceiver already runs it asynchronously at boot).
            final Context appCtx = getApplicationContext();
            Thread cleanupThread = new Thread(new Runnable() {
                @Override public void run() { BootDisplayCleanup.cleanup(appCtx); }
            }, "boot-cleanup-fallback");
            cleanupThread.setDaemon(true);
            cleanupThread.start();
        } else {
            // Auto-start enabled: clear the persisted set (projection is active,
            // apps will be managed normally).
            ClusterPrefs.clearSessionClusterPkgs(this);
        }

        // Unlock hidden Android APIs (SurfaceControl, etc.)
        // Must be called before any call to ClusterMirrorManager.startMirror(this, ).
        // Same mechanism as WindowManagement v1.2 (VMRuntime.setHiddenApiExemptions).
        com.byd.dashcast.cluster.mirror.ClusterMirrorManager.unlockHiddenApis();

        // v1.2.55-beta — log pruning + orphan-sniffer cleanup, off-loaded to a daemon thread.
        // Gate evaluated here (main thread) so the "run once per process" invariant holds
        // across Activity re-creates.
        final boolean killOrphanSniffer = !sOrphanSnifferKillDone;
        if (killOrphanSniffer) sOrphanSnifferKillDone = true;
        AppStartupTasks.run(getApplicationContext(), killOrphanSniffer);

        // Auto favourite layout: activate cluster projection + favourite layout at app
        // launch (one-shot per process; no-op unless enabled in Settings).
        com.byd.dashcast.fission.FissionOrchestrator.maybeAutoStartOnAppLaunch(this);

        // Receiver to retrieve the MirrorDaemon Binder (uid=2000)
        registerReceiver(mDaemonReadyReceiver,
                new IntentFilter(com.byd.dashcast.proxy.daemon.MirrorDaemon.ACTION_DAEMON_READY));

        // Floating 📺 mirror button — started once, visibility controlled by show()/hide()
        startService(new Intent(this, FloatingRemoteButton.class));

        // Handle a tap on the floating button when the Activity is already alive
        // (Activity exists in back stack → onNewIntent fires instead of onCreate)
        handleShowMirrorIntent(getIntent());

        btnRestoreCluster   = (Button)   findViewById(R.id.btn_restore_cluster);
        ivNavLogo           = (android.widget.ImageView) findViewById(R.id.iv_nav_logo);
        btnShowMirror       = (Button)   findViewById(R.id.btn_show_mirror);
        llAppListSection    =            findViewById(R.id.ll_app_list_section);

        // v0.9.74 — Favorites strip + fullscreen overlay refs.
        btnExitFullscreen  = findViewById(R.id.btn_exit_fullscreen);
        vRootOverlay        = findViewById(R.id.root_overlay);
        // Category filter container visibility (coordinator owns the buttons' click listeners)
        llCategoryFilters = findViewById(R.id.ll_category_filters);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // v0.9.71 — category chips visible by default (mockup fidelity).
        boolean showFilters = prefs.getBoolean(SettingsActivity.PREF_SHOW_CATEGORY_FILTERS, true);
        llCategoryFilters.setVisibility(showFilters ? View.VISIBLE : View.GONE);

        // v1.2.76 — "Activate cluster" button removed from layout: opening the app
        // auto-starts projection (via onStart → activateCluster()) and tapping any
        // app also triggers activateCluster() if the service is not yet up.
        // The legacy click handler is therefore no longer wired.

        // Button "Restore cluster" — default: restore origin cluster with the size
        // selected in Settings (sendInfo 29/30/31 + 18 + 0). When the user enables
        // PREF_QUICK_STOP in Settings, fall back to a quick stop (sendInfo 18 + 0
        // only, without changing the cluster size).
        btnRestoreCluster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean quick = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(SettingsActivity.PREF_QUICK_STOP, false);
                if (quick) {
                    restoreBydDashboard();
                } else {
                    originCluster();
                }
            }
        });

        // View toggle is wired by AppListCoordinator.

        // Button 📺 Mirror — v0.9.74: open the pseudo-fullscreen tactile mirror.
        btnShowMirror.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMirrorView();
                attemptStartMirrorWithCurrentHolder();
                enterFullscreenMirror();
                AppLogger.d(TAG, "btn_show_mirror → enterFullscreenMirror for " + mCurrentDashboardApp);
            }
        });

        // Static nav rail entries (Settings/Diag/SysInfo/Log/Help).
        // Hotspot is wired by NavigationCoordinator; Layouts by NavRailLayouts.
        NavRailSetup.wire(this);
        NavRailLayouts.apply(this, R.id.nav_layouts, false);

        // ── v0.9.7 — Capture button removed in v1.2.76 (was a coming-soon placeholder).

        // Start ClusterService now (startForegroundService in onStart)
        mDashboardLauncher = new DashboardLauncher(this); // temporary until bind

        // Cluster control panel
        // llAppListSection replaces tvAppListTitle (see field declaration)

        // Panel collapse toggle is wired by ClusterControlCoordinator (see setupCoordinators()).
        
        // --- Resize Zone ---
        // v1.2.59-beta — DL5 ROM-level guard.
        // The DL5 fission test report (byd_report_20260528_081206.log F10/F11/F12)
        // proved cluster task resize is a silent no-op on BYD DiLink 5.0
        // (cmd activity set-task-windowing-mode stripped, cmd activity task resize
        // returns exit=0 with no visible effect). Hide the entire resize affordance
        // on devices where the probe confirmed the verb is unreachable, instead of
        // exposing a broken UI. DL2/DL3/DL4 keep the existing UI unchanged.
        // The probe is primed at app startup (DashCastApp.onCreate → Platform.
        // primeClusterResizeProbe) so this read is non-blocking. See
        // doc_api/DL5_CLUSTER_RESIZE_LIMITATION.md for the rationale.
        frameMirror         = (android.widget.FrameLayout) findViewById(R.id.frame_cluster_mirror);
        clusterMirror       = (TextureView) findViewById(R.id.cluster_mirror);
                mInsetOverlay       = (InsetOverlayView) findViewById(R.id.inset_overlay);

        // Read persisted mMainDisplayPkg early so it is available for display-connected
        // callbacks, but push the coordinator update to after setupCoordinators() below.
        mMainDisplayPkg = ClusterPrefs.getMainPkg(this);

        // TextureView optimizations
        clusterMirror.setOpaque(true);  // No alpha blending overhead
        clusterMirror.setLayerType(View.LAYER_TYPE_HARDWARE, null); // Force hardware layer

        // Ensure that once the TextureView is measured (size > 0), we auto-trigger the mirror
        // to avoid black screens due to measuring race conditions under cold starts.
        clusterMirror.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int w = right - left;
                int h = bottom - top;
                if (w > 0 && h > 0) {
                    AppLogger.d(TAG, "clusterMirror layed out: " + w + "x" + h + " -> invoking attemptStartMirror");
                    attemptStartMirrorWithCurrentHolder();
                }
            }
        });

        // SurfaceTextureListener is installed by MirrorCoordinator (see setupCoordinators()).

        // Hide → return to list
        Button btnControlHide = (Button) findViewById(R.id.btn_control_hide);
        btnControlHide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppList();
            }
        });


        // v1.2.8 — Keyboard bridge: relays IME from head unit to cluster window.
        // DL5 limitation: the cluster Presentation display lives on a 1×1 shadow
        // framebuffer, so the system IME has nowhere to render there. We launch a
        // tiny relay Activity on display 0; characters typed locally are forwarded
        // as KeyEvents to the focused cluster window via TRANSACT_INJECT_KEY.
        // v1.2.9 — hidden on DL3 where the system IME renders natively on the cluster.
        Button btnKeyboardBridge = (Button) findViewById(R.id.btn_keyboard_bridge);
        if (btnKeyboardBridge != null) {
            boolean isDl5 = false;
            try {
                isDl5 = com.byd.dashcast.platform.Platform.get().isDiLink5(this);
            } catch (Throwable t) {
                AppLogger.e("MainActivity", "isDiLink5 check failed (keyboard btn)", t);
            }
            btnKeyboardBridge.setVisibility(isDl5 ? View.VISIBLE : View.GONE);
            btnKeyboardBridge.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new android.content.Intent(
                                MainActivity.this, KeyboardBridgeActivity.class));
                    } catch (Exception e) {
                        AppLogger.e("MainActivity", "KeyboardBridge launch failed", e);
                    }
                }
            });
        }

        // Banners and fission wired in setupCoordinators() below

        mSessionTracker = new ClusterSessionTracker(this);

        // Wire coordinator layer (status dot, mirror lifecycle, fullscreen state machine).
        // Touch forwarding is wired internally by MirrorCoordinator.
        setupCoordinators();

        // Restore main-display pkg into the adapter now that mAppListCoordinator exists.
        // (Cannot be done before setupCoordinators — mAppListCoordinator was null.)
        if (mMainDisplayPkg != null) {
            mAppListCoordinator.setMainPackage(mMainDisplayPkg);
        }

        // Async loading of the app list (async to avoid blocking the UI)
        loadAppsAsync();

        // OTA update check — only on fresh launch, not on rotation
        if (savedInstanceState == null) {
            UpdateChecker.checkUpdate(this, OtaProgressUi.makeListener(this, false));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShowMirrorIntent(intent);
    }

    // ─── v1.4.0 Voice command receiver ─────────────────────────────────────────

    private final VoiceCommandReceiver mVoiceCommandReceiver = new VoiceCommandReceiver(
            new VoiceCommandReceiver.Host() {
                @Override public boolean isActivityAlive() { return !isFinishing() && !isDestroyed(); }
                @Override public void activateCluster()     { MainActivity.this.activateCluster(); }
                @Override public void restoreBydDashboard() { MainActivity.this.restoreBydDashboard(); }
                @Override public void startActivity(Intent i) { MainActivity.this.startActivity(i); }
                @Override public void quickSwitchToApp(String pkg) { MainActivity.this.quickSwitchToApp(pkg); }
            });

    @Override
    protected void onResume() {
        super.onResume();
        // Hotspot navrail entry depends on the "use_own_sim" pref which can
        // change in SettingsActivity ; re-evaluate on every resume.
        if (mNavCoordinator != null) mNavCoordinator.refreshHotspot();
        // v1.2.45 — Compact apps panel pref is also live-applied so the user
        // sees the new column layout as soon as they leave Settings.
        applyCompactAppsPanelMode();
        // Fission button visibility follows its toggle in SettingsActivity.
        if (mFissionCoordinator != null) mFissionCoordinator.refresh();
    }

    /**
     * v1.2.45 — Apply the "compact apps panel" preference. When ON, the left
     * apps column is narrowed (weight 0.6) and switched to a 2-column grid so
     * the cluster preview pane on the right gets significantly more room. The
     * category filter chips are also force-hidden in compact mode because the
     * row is too wide for a 2-icon column. When OFF, the historical layout is
     * restored (weight 1.4, 5-column grid, chips visibility honours its own
     * PREF_SHOW_CATEGORY_FILTERS pref). Safe to call multiple times — purely
     * idempotent layout adjustments, no allocation of new fields.
     */
    private void applyCompactAppsPanelMode() {
        if (llAppListSection == null) return;

        // v1.3.2 — Left apps column is now always a fixed 160dp width (= 2 × 80dp
        // favorite tile) with span=2. This guarantees that the search bar,
        // favorites strip and grid columns all line up vertically, and that the
        // right-pane live preview gets every remaining pixel of horizontal space.
        android.view.ViewGroup.LayoutParams lp = llAppListSection.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
            int targetW = (int) (160 * getResources().getDisplayMetrics().density);
            if (llp.width != targetW || llp.weight != 0f) {
                llp.width = targetW;
                llp.weight = 0f;
                llAppListSection.setLayoutParams(llp);
            }
        }

        // Always 2-column grid to match the favorites strip (2 × 80dp tiles).
        if (mAppListCoordinator != null) mAppListCoordinator.ensureGridSpanCount(2);

        // Category-filter chips: too wide for a 160dp column, force-hidden.
        View chips = findViewById(R.id.ll_category_filters);
        if (chips != null) {
            chips.setVisibility(View.GONE);
        }
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
        mUsageTracker.trackStop(mCurrentDashboardPkg);
        int displayId = mClusterService.getDisplayId();
        if (displayId < 0) displayId = 1;
        mClusterService.moveTaskToDisplay(pkgName, displayId, new ClusterService.LaunchCallback() {
            @Override public void onResult(boolean launched) {
                if (launched) {
                    mLastLaunchTime = System.currentTimeMillis(); // set grace period on quick-switch launch
                    mCurrentDashboardPkg = pkgName;
                    mSessionTracker.add(pkgName);
                    // Resolve app name
                    String name = pkgName;
                    try {
                        android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkgName, 0);
                        CharSequence label = getPackageManager().getApplicationLabel(ai);
                        if (label != null) name = label.toString();
                    } catch (Exception ignored) {}
                    mCurrentDashboardApp = name;
                    ClusterPrefs.addRecentApp(MainActivity.this, pkgName, name);
                    mUsageTracker.trackStart();
                    ClusterPrefs.setClusterPkg(MainActivity.this, pkgName);
                    ClusterPrefs.setClusterName(MainActivity.this, name);
                    ClusterPrefs.setLastCluster(MainActivity.this, pkgName, name);
                    mAppListCoordinator.setCurrentPackage(pkgName);
                    updateDashboardStatus(mCurrentDashboardApp);
                    updateControlLabel();
                    startClusterMirror();
                    mInsetApplicator.apply(pkgName);
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStart");
        if (mPermissionBannerCoordinator != null) mPermissionBannerCoordinator.refresh();
        // Refresh category filter visibility (may have been toggled in Settings)
        boolean showFilters = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_SHOW_CATEGORY_FILTERS, true);
        if (llCategoryFilters != null) {
            llCategoryFilters.setVisibility(showFilters ? View.VISIBLE : View.GONE);
        }
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
            // v1.2.82 — re-sync the status UI if the cluster display is currently
            // up (e.g. user returns from SysInfo after using the projection replay
            // button while MainActivity was in background → no onClusterDisplayConnected
            // was delivered because the listener was null). Without this, the top-right
            // status stayed in "OFF" state even though projection is active.
            try {
                int curDispId = mClusterService.getDisplayId();
                if (curDispId > 0) {
                    updateDashboardStatus(mCurrentDashboardApp);
                }
            } catch (Throwable t) {
                AppLogger.w(TAG, "onStart: status re-sync failed: " + t.getMessage());
            }
            // If an app was active and the mirror is shown, restart it.
            // v1.2.85 — was: check panelClusterControl visibility (now
            // fullscreen-only). frameMirror is the actual TextureView host.
            if (mCurrentDashboardApp != null
                    && frameMirror != null
                    && frameMirror.getVisibility() == View.VISIBLE) {
                attemptStartMirrorWithCurrentHolder();
            }
            // Ensure btn_show_mirror and floating button visibility are correct
            // even when the Activity was merely stopped (not destroyed) — the
            // setListener callback only restores from prefs if mCurrentDashboardPkg
            // was null (Activity recreated).  This handles the "hide mirror then
            // leave and come back" scenario where btnShowMirror is already VISIBLE
            // in memory but FloatingRemoteButton may have lost state.
            if (mCurrentDashboardApp != null) {
                btnShowMirror.setVisibility(View.VISIBLE);
                FloatingRemoteButton.show();
            }
        } else if (!mBindRequested) {
            // Check if the service is already running (e.g. Activity re-opened)
            if (ClusterService.sIsRunning) {
                mBindRequested = true;
                if (mNavCoordinator != null) mNavCoordinator.setStatusPending();
                Intent svcIntent = new Intent(this, ClusterService.class);
                bindService(svcIntent, mServiceConn, BIND_AUTO_CREATE);
            }
            // v1.2.38: do NOT auto-activate projection on launch anymore. User
            // feedback: opening DashCast just to browse the app grid should not
            // wake the cluster surface. Auto-activation is preserved on the app
            // launch path — see onSendToDashboard() which already calls
            // activateCluster() (slow path) when mClusterService==null or
            // getDisplayId()<=0, and the fast path replays the tapped app
            // through mPendingAppAfterActivation once the service is up.
        }
        if (mStatePollCoordinator != null) mStatePollCoordinator.start();
        // Register voice-command receiver here (onStart) so it is only active while
        // the Activity is visible. Moved from onCreate/onDestroy to avoid firing
        // cluster state mutations while the Activity is in the background.
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .registerReceiver(mVoiceCommandReceiver,
                        new IntentFilter(VoiceCommandRouter.ACTION_VOICE_COMMAND));
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStop");
        if (mStatePollCoordinator != null) mStatePollCoordinator.stop();
        // Keep the daemon mirror alive when a nav app is actively streaming on the
        // cluster display: MainActivity goes to background when the nav app takes
        // the foreground on display 0, but the mirror must survive (field bug:
        // mirror was killed ~10 s after Maps launched on cluster).
        // When no cluster app is active the mirror is stopped as before.
        // onDestroy() always stops the mirror before unbinding so a recreated
        // Activity instance starts with a clean mMirrorActive=false state.
        boolean clusterAppActive = mCurrentDashboardApp != null
                && mServiceBound && mClusterService != null
                && mClusterService.getDisplayId() > 0;
        if (!clusterAppActive) {
            stopClusterMirror();
        }
        if (mServiceBound && mClusterService != null) {
            mClusterService.setListener(null);
        }
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                    .unregisterReceiver(mVoiceCommandReceiver);
        } catch (Throwable ignore) {}
    }

    @Override
    protected void onDestroy() {
        if (mClusterService != null) {
            try { mClusterService.setListener(null); } catch (Throwable ignore) {}
        }
        super.onDestroy();
        AppLogger.lifecycle(getClass().getSimpleName(), "onDestroy");
        // Cancel all pending runnables (state-poll + any anonymous lambdas posted via
        // postDelayed that individual removeCallbacks() calls may have missed).
        mScreenshotHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(mDaemonReadyReceiver);
        if (mServiceBound) {
            // Stop mirror before unbinding so a recreated Activity starts with
            // mMirrorActive=false (covers the onStop kept-alive path).
            stopClusterMirror();
            unbindService(mServiceConn);
            mServiceBound  = false;
            mBindRequested = false;
        }
        // Surface lifecycle is managed by MirrorCoordinator.
        if (mMirrorCoordinator != null) mMirrorCoordinator.destroy();
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
                if (isFinishing() || isDestroyed()) return;
                mTimeoutManager.cancel();
                final boolean wasManual = mWasManualActivation;
                mWasManualActivation = false;
                updateDashboardStatus(null);

                // Restore active cluster app if Activity was recreated (in-memory state lost).
                // mCurrentDashboardPkg is only null here if the Activity instance was killed
                // while in background (Home pressed) and a new instance was recreated.
                if (mCurrentDashboardPkg == null) {
                    String _pkg  = ClusterPrefs.getClusterPkg(MainActivity.this);
                    String _name = ClusterPrefs.getClusterName(MainActivity.this);
                    if (_pkg != null) {
                        mCurrentDashboardPkg = _pkg;
                        mCurrentDashboardApp = _name;
                        // NB: ne PAS armer mLastLaunchTime ici. Ce chemin est une
                        // restauration depuis les prefs après recreate d'Activity ;
                        // le « launch » réel est potentiellement très ancien et le
                        // process peut avoir été tué pendant que DashCast était en
                        // background. La grace period n'a de sens qu'autour d'un
                        // am start fraîchement dispatché (cf. les 3 sites de
                        // launch dans onSendToDashboard / quickSwitchToApp).
                        mAppListCoordinator.setCurrentPackage(_pkg);
                        updateDashboardStatus(_name);
                        updateControlLabel();
                        showMirrorView(); // makes panelClusterControl visible
                        AppLogger.i(TAG, "cluster active app restored: " + _pkg);
                    }
                }

                // If the mirror is shown (app already active), start/reconfigure it.
                // v1.2.85 — was panelClusterControl (now fullscreen-only).
                if (frameMirror != null && frameMirror.getVisibility() == View.VISIBLE) {
                    attemptStartMirrorWithCurrentHolder();
                }

                // Restore mMainDisplayPkg if Activity was recreated
                if (mMainDisplayPkg == null) {
                    mMainDisplayPkg = ClusterPrefs.getMainPkg(MainActivity.this);
                    if (mMainDisplayPkg != null) mAppListCoordinator.setMainPackage(mMainDisplayPkg);
                }
                
                // Pending app from "activate cluster" dialog
                if (mPendingAppAfterActivation != null) {
                    final AppInfo pending = mPendingAppAfterActivation;
                    mPendingAppAfterActivation = null;
                    AppLogger.i(TAG, "Auto-sending pending app after activation: " + pending.packageName);
                    // v1.3.8-beta — if this auto-send is for a restored cluster
                    // app and our cached pkg matches it, the "already on cluster
                    // — show mirror only" shortcut in onSendToDashboard would
                    // skip the actual launch. If Android killed the process
                    // while DashCast was in background, that path would just
                    // mirror the empty cluster display (black screen for the
                    // user) and only state-poll would clear state ~8 s later.
                    // Pre-check pidof and force a fresh launch if dead, so
                    // onSendToDashboard takes the normal launch path.
                    if (pending.packageName != null
                            && pending.packageName.equals(mCurrentDashboardPkg)) {
                        final String checkPkg = pending.packageName;
                        ShellGateway.execShellWithResult(MainActivity.this,
                                "pidof " + checkPkg,
                                new AdbLocalClient.Callback() {
                                    @Override public void onSuccess(String output) {
                                        final boolean alive = output != null && !output.trim().isEmpty();
                                        runOnUiThread(new Runnable() {
                                            @Override public void run() {
                                                if (isFinishing() || isDestroyed()) return;
                                                if (!alive) {
                                                    AppLogger.w(TAG, "auto-restore: " + checkPkg
                                                            + " process is dead → clearing cluster state for fresh launch");
                                                    clearClusterState();
                                                }
                                                onSendToDashboard(pending);
                                            }
                                        });
                                    }
                                    @Override public void onError(String error) {
                                        // Daemon down or pidof failed — proceed with shortcut path.
                                        AppLogger.w(TAG, "auto-restore: pidof failed (" + error
                                                + ") — proceeding with mirror shortcut");
                                        runOnUiThread(new Runnable() {
                                            @Override public void run() {
                                                if (isFinishing() || isDestroyed()) return;
                                                onSendToDashboard(pending);
                                            }
                                        });
                                    }
                                });
                    } else {
                        onSendToDashboard(pending);
                    }
                }

                // Auto-Launch process
                if (mPendingAutoLaunchPkg != null) {
                    String targetPkg = mPendingAutoLaunchPkg;
                    mPendingAutoLaunchPkg = null; // Clear immediately
                    AppLogger.i(TAG, "Executing pending auto-launch for " + targetPkg);
                    // Find it and launch it
                    for (AppInfo a : mAppListCoordinator.getApps()) {
                        if (a.packageName.equals(targetPkg)) {
                            onSendToDashboard(a);
                            break;
                        }
                    }
                }

                // Reconnect reminder: if cluster was manually re-activated and
                // there was a last known app, offer to relaunch it.
                // Guarded by user preference (can be disabled in Settings).
                boolean reconnectEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(SettingsActivity.PREF_RECONNECT_POPUP, false);
                if (reconnectEnabled && wasManual && mCurrentDashboardPkg == null) {
                    final String lastPkg  = ClusterPrefs.getLastClusterPkg(MainActivity.this);
                    final String lastName = ClusterPrefs.getLastClusterName(MainActivity.this);
                    if (lastPkg != null && lastName != null) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle(getString(R.string.dialog_reconnect_title))
                            .setMessage(getString(R.string.dialog_reconnect_msg, lastName))
                            .setPositiveButton(getString(R.string.dialog_reconnect_yes), new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    for (AppInfo a : mAppListCoordinator.getApps()) {
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
                mTimeoutManager.cancel();
                mWasManualActivation = false;
                mCurrentDashboardApp = null;
                mCurrentDashboardPkg = null;
                mMainDisplayPkg = null;
                ClusterPrefs.setMainPkg(MainActivity.this, null);
                ClusterPrefs.setClusterPkg(MainActivity.this, null);
                ClusterPrefs.setClusterName(MainActivity.this, null);
                if (mSplitController != null) mSplitController.clearSplitState();
                if (mAppListCoordinator != null) mAppListCoordinator.setCurrentPackage(null);
                if (mAppListCoordinator != null) mAppListCoordinator.setMainPackage(null);
                // v0.9.73 — unified OFF state ("Projection inactive") with grey dot.
                setDashboardOffState();
                showAppList();
            }
        });
    }

    // ---- AppListAdapter.OnSendToDashboardListener ----

    @Override
    public void onSetAutoLaunch(AppInfo app, boolean enable) {
        mAppRepo.setAutoLaunch(this, app.packageName, enable);
        // Post to avoid IllegalStateException (cannot call notify during bind)
        if (mAppListCoordinator != null) {
            final AppListCoordinator coord = mAppListCoordinator;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(coord::notifyAppsChanged);
        }
    }

    @Override
    public void onToggleFavorite(AppInfo app) {
        // setFavorite patches the in-memory cache and re-sorts — no PackageManager re-query.
        mAppRepo.setFavorite(this, app.packageName, !app.isFavorite);
        mAppListCoordinator.deliver(mAppRepo.getApps(), false);
    }

    // v0.9.72 — long-press opens a bottom sheet with the per-app actions.
    @Override
    public void onShowActions(AppInfo app) {
        AppActionSheet.show(app, this);
    }

    @Override
    public void onSendToDashboard(AppInfo app) {
        ClusterPrefs.incrementLaunchCount(this, app.packageName);
        // Java displayId may not be resolved even when the cluster is active
        // (internal state unreliable on DiLink 3.0). We no longer block here:
        // ClusterService.launchOnDashboard() tries direct Binder then ADB relay
        // with displayId=1 hardcoded (Seal EU) as fallback → always functional.
        if (mClusterService == null) {
            // v1.2.76 — no more confirmation popup: tapping an app implies wanting to
            // project it. Auto-trigger activateCluster() and replay the app once the
            // service is up (handled by mPendingAppAfterActivation in the connect
            // callback). Saves one tap on the "cold start → launch" flow.
            AppLogger.i(TAG, "ClusterService null — auto-activating for " + app.packageName);
            mPendingAppAfterActivation = app;
            activateCluster();
            return;
        }

        // v1.2.83 — same auto-activation if the service is bound but projection
        // was stopped (stopProjectionNoAdb left displayId=-1). Without this, the
        // following moveTaskToDisplay would issue an IAM call with
        // launchDisplayId=-2 → SecurityException, and the user would have to go
        // through SysInfo > slow-path replay to recover. Treat it as a fresh
        // activation request and replay the tapped app once the cluster is back.
        if (mClusterService.getDisplayId() <= 0) {
            AppLogger.i(TAG, "ClusterService bound but displayId<=0 — auto-activating for "
                    + app.packageName);
            mPendingAppAfterActivation = app;
            activateCluster();
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
            mAppListCoordinator.setMainPackage(null);
            ClusterPrefs.setMainPkg(this, null);
        }

        // ── Split mode: an app already occupies a slot → the new app goes into the other one ──
        if (mSplitController.isInSplitMode() && mCurrentDashboardPkg != null) {
            // Same app as the main or secondary slot already present: ignore
            if (pkgName.equals(mCurrentDashboardPkg) || pkgName.equals(mSplitController.getSecondDashboardPkg())) {
                AppLogger.w(TAG, "split: duplicate ignored pkg=" + pkgName
                        + " (main=" + mCurrentDashboardPkg + " second=" + mSplitController.getSecondDashboardPkg() + ")");
                Toast.makeText(getApplicationContext(), getString(R.string.toast_app_already_cluster), Toast.LENGTH_SHORT).show();
                return;
            }
            int[] dims = mSplitController.getClusterDimensions();
            final int W = dims[0], H = dims[1];
            // Complementary slot (1=left → right; 2=right → left)
            final int newLeft  = (mSplitController.getCurrentSplitSlot() == 1) ? W / 2 : 0;
            final int newRight = (mSplitController.getCurrentSplitSlot() == 1) ? W     : W / 2;
            AppLogger.log(TAG, "split — slot courant=" + mSplitController.getCurrentSplitSlot()
                    + " → complementary slot bounds=[" + newLeft + ",0," + newRight + "," + H + "]"
                    + " pkg=" + pkgName);
            // Force-stop the old secondary slot if already occupied
            if (mSplitController.getSecondDashboardPkg() != null) {
                AdbLocalClient.forceStopApp(this, mSplitController.getSecondDashboardPkg(), null);
            }
            mClusterService.launchOnDashboardWithBounds(pkgName, newLeft, 0, newRight, H,
                    new ClusterService.LaunchCallback() {
                @Override public void onResult(boolean launched) {
                    if (launched) {
                        mLastLaunchTime = System.currentTimeMillis(); // set grace period on split launch
                        mSplitController.setSecondDashboardApp(appName);
                        mSplitController.setSecondDashboardPkg(pkgName);
                        mSessionTracker.add(pkgName);
                        updateControlLabel();
                    } else {
                        Toast.makeText(getApplicationContext(),
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
                    mLastLaunchTime = System.currentTimeMillis(); // set grace period on normal launch
                    // Track usage: stop timer for previous app, start for new one
                    mUsageTracker.trackStop(mCurrentDashboardPkg);
                    mCurrentDashboardApp = appName;
                    mCurrentDashboardPkg = pkgName;
                    mSessionTracker.add(pkgName);
                    ClusterPrefs.addRecentApp(MainActivity.this, pkgName, appName);
                    mUsageTracker.trackStart();
                    ClusterPrefs.setClusterPkg(MainActivity.this, pkgName);
                    ClusterPrefs.setClusterName(MainActivity.this, appName);
                    ClusterPrefs.setLastCluster(MainActivity.this, pkgName, appName);
                    mAppListCoordinator.setCurrentPackage(pkgName);
                    updateDashboardStatus(appName);
                    updateControlLabel();
                    startClusterMirror();
                    // v1.2.55-beta — Waze first-tap fix: when moveTaskToDisplay
                    // falls back to launchOnDashboard (no running task), some
                    // apps (singleInstance / taskAffinity) get placed on
                    // display 0 by AOSP despite ActivityOptions.launchDisplayId.
                    // Schedule a deferred enforcement that issues a direct
                    // IATM.moveTaskToDisplay once the process has spawned.
                    // No-op if the task already landed on the cluster.
                    final ClusterService svc = mClusterService;
                    if (svc != null) {
                        mScreenshotHandler.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (isFinishing() || isDestroyed()) return;
                                if (!pkgName.equals(mCurrentDashboardPkg)) return;
                                svc.enforceTaskOnDisplay(pkgName, targetDisplayId);
                            }
                        }, 2500L);
                    }
                    mInsetApplicator.apply(pkgName);
                } else {
                    Toast.makeText(getApplicationContext(),
                            getString(R.string.toast_app_incompatible, appName),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    public void onSendToMain(AppInfo app) {
        ClusterPrefs.incrementLaunchCount(this, app.packageName);
        // Track usage: stop timer for the app leaving the cluster
        mUsageTracker.trackStop(mCurrentDashboardPkg);
        // Clean up cluster state before move
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        ClusterPrefs.setClusterPkg(this, null);
        ClusterPrefs.setClusterName(this, null);
        // Force-stop the secondary slot in split mode (prevents it from staying on display 1)
        if (mSplitController != null && mSplitController.getSecondDashboardPkg() != null) {
            AdbLocalClient.forceStopApp(this, mSplitController.getSecondDashboardPkg(), null);
        }
        if (mSplitController != null) mSplitController.clearSplitState();
        // Record that the app is on the main display → shows button "→ Cluster" in the list
        mMainDisplayPkg = app.packageName;
        mAppListCoordinator.setCurrentPackage(null);
        mAppListCoordinator.setMainPackage(app.packageName);
        ClusterPrefs.setMainPkg(this, app.packageName);
        updateDashboardStatus(null);
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

        // Eagerly clear tracked state BEFORE async move/kill so the display-state
        // poll does not see a stale mCurrentDashboardPkg on display 0 during the
        // brief window between moveTaskToDisplay and forceStopApp.
        if (isOnCluster) {
            mCurrentDashboardApp = null;
            mCurrentDashboardPkg = null;
            ClusterPrefs.setClusterPkg(MainActivity.this, null);
            ClusterPrefs.setClusterName(MainActivity.this, null);
            mAppListCoordinator.setCurrentPackage(null);
            updateDashboardStatus(null);
        }

        // 2. Move the app back to Display 0 before killing — safety net so that
        //    if force-stop fails silently, Android won't re-launch it on Display 1.
        //    v1.2.9 fix (Bug 1) : SÉRIALISER move → forceStop via LaunchCallback.
        //    Avant : move() async + forceStop() async sur thread différent →
        //    le dumpsys interne à forceStopApp pouvait tomber pendant le déplacement,
        //    voir un TaskId fantôme ou plus aucun match → am task remove
        //    s'exécutait sur rien et la TaskRecord restait orpheline dans Recents.
        final AdbLocalClient.Callback killCallback = new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // Audit B19: forceStop callback may arrive after Activity destroy.
                        if (isFinishing() || isDestroyed()) return;
                        AppLogger.i(TAG, "forceStop " + app.packageName + " OK");
                        // Cluster state already cleared eagerly above (before async ops).
                        if (app.packageName != null && app.packageName.equals(mSplitController.getSecondDashboardPkg())) {
                            mSplitController.setSecondDashboardPkg(null);
                            mSplitController.clearSplitState();
                        }
                        showAppList();
                        Toast.makeText(getApplicationContext(),
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
                        // Audit B19: forceStop error callback may arrive after Activity destroy.
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(getApplicationContext(),
                                getString(R.string.toast_kill_failed, error), Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "forceStop FAILED: " + error);
                    }
                });
            }
        };

        if (mSessionTracker.contains(app.packageName)
                && mServiceBound && mClusterService != null) {
            mClusterService.moveTaskToDisplay(app.packageName, 0,
                    new com.byd.dashcast.cluster.ClusterService.LaunchCallback() {
                @Override public void onResult(boolean ok) {
                    AppLogger.i(TAG, "doKillApp: move→display0 " + (ok ? "OK" : "KO")
                            + " for " + app.packageName + " — now force-stop");
                    mSessionTracker.remove(app.packageName);
                    AdbLocalClient.forceStopApp(MainActivity.this, app.packageName, killCallback);
                }
            });
        } else {
            mSessionTracker.remove(app.packageName);
            AdbLocalClient.forceStopApp(this, app.packageName, killCallback);
        }
    }

    // ---- Miroir cluster ----

    private void tryGetDaemonBinderFromServiceManager() {
        DaemonBinderResolver.fetch(binder -> {
            // Guard: lookup is async and may resolve after onDestroy() (user backed out
            // during daemon startup). Touching mClusterService after destroy NPEs the forwarder.
            if (isFinishing() || isDestroyed()) return;
            mDaemonBinder = binder;
            // Capture once — onServiceDisconnected() (main thread) can null mClusterService
            // concurrently with this callback.
            final ClusterService svc = mServiceBound ? mClusterService : null;
            if (svc != null) svc.getInputForwarder().setDaemonBinder(binder);
            // Restart mirror if currently shown so the daemon path can take over and surface
            // actual frames (v1.2.55-beta — symmetric to the broadcast receiver path).
            if (mCurrentDashboardApp != null
                    && frameMirror != null
                    && frameMirror.getVisibility() == View.VISIBLE) {
                if (svc != null) {
                    com.byd.dashcast.cluster.mirror.ClusterMirrorManager mm = svc.getMirrorManager();
                    if (mm.isMirrorActive() && !mm.isMirrorViaDaemon()) {
                        AppLogger.i(TAG, "Daemon resolved late — restarting mirror via daemon");
                        stopClusterMirror();
                    }
                }
                attemptStartMirrorWithCurrentHolder();
            }
        });
    }

    /**
     * v2.30: uses DisplayManager.createVirtualDisplay() like WindowManagement/byd_dashboard.
     * No need for clusterDisplay — the VirtualDisplay is independent of the cluster display.
     * After creation, also launches the current app on the preview display.
     */
    private void attemptStartMirrorWithCurrentHolder() {
        if (mMirrorCoordinator != null) mMirrorCoordinator.attemptStart();
    }

    // ---- Display state polling ----------------------------------------------
    //
    // Detects when the cluster app process dies (crash, OOM-kill, external
    // force-stop) so we can clear stale bookkeeping and stop the mirror.
    //
    // History:
    //   v0.1.43  /proc-based watchdog → false positives (hidepid=2 on DiLink)
    //   v0.8.0   OnUidImportanceListener → false positives (VD = "background")
    //   v0.8.1   getTasks() → doesn't report VirtualDisplay tasks on DiLink
    //   v0.8.3   `pidof <pkg>` via ADB shell (uid 2000 can read /proc)  ✓
    //
    // Started in onStart(), stopped in onStop().
    // -------------------------------------------------------------------------

    /**
     * Clears all cluster-app bookkeeping and returns to the app list.
     * Shared by reconcileDisplayState and other paths that need a clean reset.
     */
    private void clearClusterState() {
        mUsageTracker.trackStop(mCurrentDashboardPkg);
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        ClusterPrefs.setClusterPkg(this, null);
        ClusterPrefs.setClusterName(this, null);
        mAppListCoordinator.setCurrentPackage(null);
        updateDashboardStatus(null);
        showAppList();
    }

    // -------------------------------------------------------------------------

    /**
     * Hides the app list and displays the cluster mirror in full space.
     * Called from startClusterMirror().
     */
    private void showMirrorView() {
        // v0.9.7 — apps pane stays visible at all times (M3 split layout). The old
        // visibility toggles on llAppListSection/rvApps are intentionally no-ops now.
        // v1.2.85 — panelClusterControl is no longer shown together with the
        // preview card. The controls (Ajuster / ↺ / Split / ⌨) are mirror-only
        // actions and used to clutter the apps page; they are now revealed
        // exclusively from enterFullscreenMirror() and hidden again on exit.
        frameMirror.setVisibility(View.VISIBLE);
        // Pre-arm the inner content so it is expanded the next time the panel
        // becomes visible (i.e. when entering fullscreen mirror).
        if (mClusterControlCoordinator != null) mClusterControlCoordinator.expandContent();
        // Collapse resize panel and hide overlay on app switch
        if (mInsetOverlay != null) mInsetOverlay.setOverlayVisible(false);
        if (mClusterControlCoordinator != null) {
            mClusterControlCoordinator.collapseResizePanel();
        }

        // Load persisted insets for the new app into the seekbars
        if (mCurrentDashboardPkg != null && mClusterControlCoordinator != null) {
            SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int defH = p.getInt(SettingsActivity.PREF_INSET_H, SettingsActivity.DEFAULT_INSET_H);
            int defV = p.getInt(SettingsActivity.PREF_INSET_V, SettingsActivity.DEFAULT_INSET_V);
            int curW = p.getInt(SettingsActivity.PREF_INSET_H_PREFIX + mCurrentDashboardPkg, defH);
            int curH = p.getInt(SettingsActivity.PREF_INSET_V_PREFIX + mCurrentDashboardPkg, defV);
            mClusterControlCoordinator.loadInsets(mCurrentDashboardPkg, curW, curH);
        }
    }


    /**
     * Hides the mirror and restores the app list.
     * Called from showAppList().
     */
    private void showAppList() {
        // v0.9.7 — apps pane is always visible (M3 split layout). We only collapse the
        // cluster preview/control widgets here. Mirror frame stays VISIBLE so its empty
        // preview card serves as the idle state.
        stopClusterMirror();
        if (mClusterControlCoordinator != null) mClusterControlCoordinator.hidePanel();
    }

    /**
     * Signals to MainActivity that an app was launched on the cluster → show the mirror.
     * Called from onSendToDashboard after a successful launch.
     *
     * v1.2.82 — applies the same stop+delayed-rebind recipe as
     * enter/exitFullscreenMirror(). Without this the existing mirror token
     * (created with the OLD cluster displayId / layerStack, e.g. when the
     * cluster was just (re)activated and the previous displayId was -1 or a
     * stale value) keeps writing into our Surface but with a wrong layerStack
     * → black preview. The user-visible workaround was to toggle fullscreen
     * or to leave/return to the app (both already do this restart). Now any
     * launch triggers the rebind automatically.
     */
    private void startClusterMirror() {
        AppLogger.d(TAG, "startClusterMirror app=" + mCurrentDashboardApp);
        showMirrorView();
        frameMirror.setAlpha(0f);
        frameMirror.animate().alpha(1f).setDuration(150).start();
        // Tear down before rebind so attemptStartMirror() doesn't short-circuit
        // on isMirrorActive() with a stale token.
        stopClusterMirror();
        if (clusterMirror != null && mMirrorCoordinator != null) {
            clusterMirror.postDelayed(() -> mMirrorCoordinator.recreateSurfaceAndRestart(), 250);
        }
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
    }

    private void activateCluster() {
        if (mNavCoordinator != null) mNavCoordinator.setStatusActivating();
        mWasManualActivation = true;
        mTimeoutManager.start();
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
            if (mNavCoordinator != null) mNavCoordinator.setStatusPending();
                // Button is re-enabled natively by onClusterDisplayConnected or onClusterDisplayDisconnected callbacks.
        } else {
            // Service already up → manually restart projection natively without ADB
            AppLogger.log(TAG, "Calling native restartProjection via ClusterService");
            mClusterService.restartProjection();
            // onClusterDisplayConnected / onClusterDisplayDisconnected will re-enable the button
        }
    }

    /** ⋮ menu — developer tools accessible without cluttering the toolbar. */
    // ── OTA progress dialog ───────────────────────────────────────────────────

    private void restoreBydDashboard() {
        btnRestoreCluster.setEnabled(false);
        if (mNavCoordinator != null) mNavCoordinator.setStatusRestoring();
        mUsageTracker.trackStop(mCurrentDashboardPkg);

        final String capturedClusterPkg = mCurrentDashboardPkg;
        final String capturedSecondPkg  = mSplitController != null ? mSplitController.getSecondDashboardPkg() : null;

        // Eagerly clear tracked cluster state BEFORE async eviction so the
        // display-state poll does not see a stale mCurrentDashboardPkg on display 0
        // during the brief window between moveTaskToDisplay and forceStopApp.
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        ClusterPrefs.setClusterPkg(this, null);
        ClusterPrefs.setClusterName(this, null);
        mAppListCoordinator.setCurrentPackage(null);

        // v1.2.81 — unified stop semantics: every cluster-occupying app (main +
        // split second) is now moved back to display 0 AND force-stopped (am
        // force-stop + task remove) before sendInfo(18) is dispatched, mirroring
        // the long-press « kill » action. This replaces the v1.2.9 workaround that
        // force-stopped the cluster pkg in place on display 1 (which left the user
        // without any feedback that the app had really returned to the tablet).
        mSessionTracker.moveToMainDisplay(mServiceBound ? mClusterService : null);

        AppLogger.log(TAG, "restoreBydDashboard() via ADB (TEST 10)");

        mSessionTracker.evictAllThen(mServiceBound ? mClusterService : null,
                capturedClusterPkg, capturedSecondPkg, new Runnable() {
            @Override public void run() {
                // Cluster pkg already killed → pass null targetPackage so the helper
                // only sends sendInfo(18+0) without re-issuing force-stop.
                AdbLocalClient.restoreBydOnCluster(MainActivity.this, null, new AdbLocalClient.Callback() {
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
                        // Cluster state already cleared eagerly above (before async ops).
                        if (mSplitController != null) mSplitController.clearSplitState();
                        // v0.9.73 — projection just stopped → OFF state, not READY/idle.
                        setDashboardOffState();
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
                        Toast.makeText(getApplicationContext(),
                                getString(R.string.toast_restore_failed, error), Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "Restore FAILED: " + error);
                    }
                });
            }
        });
            }
        });
    }

    private void updateDashboardStatus(String appName) {
        if (mNavCoordinator != null) {
            if (appName == null) mNavCoordinator.setStatusDashboardByd();
            else                 mNavCoordinator.setStatusActive(appName);
        }
        if (appName == null) {
            btnShowMirror.setVisibility(View.GONE);
            FloatingRemoteButton.hide();
        } else {
            btnShowMirror.setVisibility(View.VISIBLE);
            FloatingRemoteButton.show();
        }
        btnRestoreCluster.setEnabled(true);
    }

    /**
     * v0.9.73 — explicit OFF state used after the user stops the projection or before
     * the cluster has been activated at all. Differs from updateDashboardStatus(null)
     * which represents the ACTIVE+IDLE case (projection on, no app yet).
     */
    private void setDashboardOffState() {
        if (mNavCoordinator != null) mNavCoordinator.setStatusOff();
        if (btnShowMirror != null) btnShowMirror.setVisibility(View.GONE);
        FloatingRemoteButton.hide();
        // v1.2.85 — also hide the cluster control card (Ajuster / ↺ / Split / ⌨).
        // It is meaningless when no app is being projected and the visual
        // remnants (small icons + collapse toggle) confused users when the
        // OFF state was reached via a path that skipped showAppList().
        if (mClusterControlCoordinator != null) mClusterControlCoordinator.hidePanel();
    }

    // ============================================================
    // v0.9.74 — Pseudo-fullscreen tactile mirror
    // ============================================================
    /**
     * Expands the cluster preview card to fill the screen by hiding the nav rail,
     * the left app list, the hero card, the preview section title and the 2x2 action
     * grid. The existing TextureView keeps its surface → touch injection still works.
     * The cluster control panel (with the "Ajuster" button) stays accessible.
     */
    private void enterFullscreenMirror() {
        if (mFullscreenCoordinator != null) mFullscreenCoordinator.enter();
    }

    /** Reverses enterFullscreenMirror(): restores all hidden views and shrinks the card back. */
    private void exitFullscreenMirror() {
        if (mFullscreenCoordinator != null) mFullscreenCoordinator.exit();
    }

    @Override
    public void onBackPressed() {
        if (mFullscreenCoordinator != null && mFullscreenCoordinator.isFullscreen()) { exitFullscreenMirror(); return; }
        super.onBackPressed();
    }

    /** Refreshes the InsetOverlayView projection params from the current mirror state. */
    private void refreshInsetOverlay() {
        if (mInsetOverlay == null || !mServiceBound || mClusterService == null) return;
        com.byd.dashcast.cluster.mirror.ClusterMirrorManager mirror = mClusterService.getMirrorManager();
        mInsetOverlay.setProjection(mirror.getProjScale(), mirror.getProjOffsetX(), mirror.getProjOffsetY());
        mInsetOverlay.setInsets(
                mClusterControlCoordinator != null ? mClusterControlCoordinator.getInsetH() : 0,
                mClusterControlCoordinator != null ? mClusterControlCoordinator.getInsetV() : 0);
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
                for (AppInfo a : mAppListCoordinator.getApps()) {
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
                for (AppInfo a : mAppListCoordinator.getApps()) {
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

    /** Original cluster — sendInfo(screenSize) + sendInfo(18) + sendInfo(0). */
    private void originCluster() {
        if (mNavCoordinator != null) mNavCoordinator.setStatusRestoringOrigin();
        mUsageTracker.trackStop(mCurrentDashboardPkg);

        final String capturedClusterPkg = mCurrentDashboardPkg;
        final String capturedSecondPkg  = mSplitController != null ? mSplitController.getSecondDashboardPkg() : null;

        // Eagerly clear tracked cluster state (same rationale as restoreBydDashboard).
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        ClusterPrefs.setClusterPkg(this, null);
        ClusterPrefs.setClusterName(this, null);
        mAppListCoordinator.setCurrentPackage(null);

        // v1.2.81 — see restoreBydDashboard for the unified eviction rationale.
        mSessionTracker.moveToMainDisplay(mServiceBound ? mClusterService : null);
        AppLogger.log(TAG, "originCluster() cmd=" + ClusterPrefs.getClusterType(this));

        mSessionTracker.evictAllThen(mServiceBound ? mClusterService : null,
                capturedClusterPkg, capturedSecondPkg, new Runnable() {
            @Override public void run() {
                AdbLocalClient.restoreOriginCluster(MainActivity.this, ClusterPrefs.getClusterType(MainActivity.this), null, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mServiceBound && mClusterService != null) {
                            mClusterService.stopProjectionNoAdb();
                        }
                        // Cluster state already cleared eagerly above.
                        if (mSplitController != null) mSplitController.clearSplitState();
                        updateDashboardStatus(null);
                        showAppList();
                        AppLogger.log(TAG, "Original cluster restored ✓");
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(getApplicationContext(),
                                getString(R.string.toast_origin_failed, error), Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "originCluster FAILED: " + error);
                    }
                });
            }
        });
            }
        });
    }

    /** Updates the app label in the cluster panel (supports "App A  |  App B" in split mode). */
    private void updateControlLabel() {
        String label = mCurrentDashboardApp == null ? null
                : mSplitController.getSecondDashboardApp() != null
                        ? mCurrentDashboardApp + "  |  " + mSplitController.getSecondDashboardApp()
                        : mCurrentDashboardApp;
        if (mClusterControlCoordinator != null) {
            mClusterControlCoordinator.setControlAppName(label);
        }
    }

    // ---- Async loading of the app list ----

    private void loadAppsAsync() {
        mAppRepo.loadApps(this, apps -> {
            if (isFinishing() || isDestroyed()) return;
            mAppListCoordinator.deliver(apps, true);
        });
    }

    // ── Coordinator wiring ────────────────────────────────────────────────────

    private void setupCoordinators() {
        mNavCoordinator = new NavigationCoordinator(
                findViewById(R.id.view_status_dot),
                (TextView) findViewById(R.id.tv_dashboard_status),
                ivNavLogo,
                findViewById(R.id.nav_hotspot),
                this);

        mMirrorCoordinator = new MirrorCoordinator(
                clusterMirror, frameMirror, (TextView) findViewById(R.id.tv_mirror_placeholder), this);

        LinearLayout panelClusterControl = (LinearLayout) findViewById(R.id.panel_cluster_control);
        mFullscreenCoordinator = new FullscreenMirrorCoordinator(
                btnExitFullscreen,
                findViewById(R.id.ll_nav_rail),
                findViewById(R.id.ll_top_bar),
                findViewById(R.id.card_hero_status),
                llAppListSection,
                findViewById(R.id.tv_preview_section),
                findViewById(R.id.grid_main_actions),
                findViewById(R.id.card_cluster_preview),
                findViewById(R.id.ll_right_pane_content),
                findViewById(R.id.sv_right_pane),
                panelClusterControl, vRootOverlay,
                this);

        mAppListCoordinator = new AppListCoordinator(
                (RecyclerView)   findViewById(R.id.rv_apps),
                (android.widget.EditText) findViewById(R.id.et_search_apps),
                (LinearLayout)   findViewById(R.id.ll_favorites_strip),
                (LinearLayout)   findViewById(R.id.ll_favorites_section),
                (Button)         findViewById(R.id.btn_filter_all),
                (Button)         findViewById(R.id.btn_filter_nav),
                (Button)         findViewById(R.id.btn_filter_media),
                (Button)         findViewById(R.id.btn_view_toggle),
                mAppRepo, this);

        mClusterControlCoordinator = new ClusterControlCoordinator(
                panelClusterControl,
                (LinearLayout) findViewById(R.id.panel_controls_content),
                (LinearLayout) findViewById(R.id.panel_resize),
                (SeekBar)  findViewById(R.id.sb_resize_w),
                (SeekBar)  findViewById(R.id.sb_resize_h),
                (TextView) findViewById(R.id.tv_resize_w_val),
                (TextView) findViewById(R.id.tv_resize_h_val),
                (Button)   findViewById(R.id.btn_resize_apply),
                (Button)   findViewById(R.id.btn_panel_toggle),
                (Button)   findViewById(R.id.btn_toggle_resize),
                (TextView) findViewById(R.id.tv_control_app_name),
                (Button)   findViewById(R.id.btn_cluster_split),
                (Button)   findViewById(R.id.btn_relaunch),
                this);

        // DL5 guard: hide resize affordance if task resize is unsupported on this ROM.
        if (!com.byd.dashcast.platform.Platform.get().isClusterTaskResizeSupported(this)) {
            mClusterControlCoordinator.hideResizeIfUnsupported();
            AppLogger.i(TAG, "Resize UI hidden: cluster task resize not supported on this ROM "
                    + "(DL5 — see DL5_CLUSTER_RESIZE_LIMITATION.md)");
        }

        mSplitController = new SplitController(this);

        mPermissionBannerCoordinator = new PermissionBannerCoordinator(
                findViewById(R.id.card_ime_a11y_banner),
                findViewById(R.id.card_hud_notif_banner),
                this);

        mUsageTracker = new UsageTracker(this);

        mFissionCoordinator = new FissionCoordinator(
                findViewById(R.id.ll_fission_layout_row),
                (TextView) findViewById(R.id.tv_main_fission_layout),
                findViewById(R.id.btn_main_switch_layout),
                this);

        mStatePollCoordinator = new DisplayStatePollCoordinator(this);
        mTimeoutManager       = new ActivateTimeoutManager(this);
        mInsetApplicator      = new InsetAutoApplicator(this);
    }

    // ── AppListCoordinator.Host ───────────────────────────────────────────────

    @Override
    public AppListAdapter.OnSendToDashboardListener getSendListener() { return this; }

    @Override
    public void onShowAppActions(com.byd.dashcast.model.AppInfo app) { onShowActions(app); }

    @Override
    public void onFirstLaunchTip() {
        Toast.makeText(getApplicationContext(), getString(R.string.tooltip_tap_send),
                Toast.LENGTH_LONG).show();
    }

    // ── ClusterControlCoordinator.Host ────────────────────────────────────────

    @Override
    public String getCurrentDashboardPkg() { return mCurrentDashboardPkg; }

    @Override
    public void onSplitLayoutRequested(android.view.View anchor) {
        if (mSplitController != null) mSplitController.showSplitMenu(anchor);
    }

    @Override
    public void onRelaunchRequested() { relaunchCurrentApp(); }

    @Override
    public void onInsetChanged(int h, int v) {
        if (mInsetOverlay != null) mInsetOverlay.setInsets(h, v);
    }

    @Override
    public void onResizePanelToggled(boolean visible) {
        if (mInsetOverlay == null) return;
        if (visible) {
            refreshInsetOverlay();
            mInsetOverlay.setOverlayVisible(true);
        } else {
            mInsetOverlay.setOverlayVisible(false);
        }
    }

    // ── NavigationCoordinator.Host / MirrorCoordinator.Host (shared) ─────────

    // getContext() satisfies all coordinators' Host interfaces that declare it.
    // (AppCompatActivity already provides getApplicationContext(); this returns `this`
    // so coordinators get the Activity context they need for dialogs and theming).
    public Context getContext() { return this; }

    @Override
    public void onShowOverflowMenu(View anchor) {
        OverflowMenuHelper.show(anchor, this);
    }

    // ── MirrorCoordinator.Host ────────────────────────────────────────────────

    @Override
    public ClusterService getClusterServiceIfBound() {
        return mServiceBound ? mClusterService : null;
    }

    @Override
    public IBinder getDaemonBinder() {
        return mDaemonBinder;
    }

    @Override
    public void onPreviewClicked() {
        // no-op: frameMirror touch is handled by clusterMirror.setOnTouchListener()
    }

    // ── FullscreenMirrorCoordinator.Host ─────────────────────────────────────

    @Override
    public void onMirrorShouldStop() {
        stopClusterMirror();
    }

    @Override
    public void onMirrorRestartAfterDelay() {
        if (mMirrorCoordinator != null) mMirrorCoordinator.recreateSurfaceAndRestart();
    }

    @Override
    public void setFullscreenImmersive(boolean on) {
        try {
            getWindow().getDecorView().setSystemUiVisibility(on
                    ? (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                       | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                       | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                       | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                       | View.SYSTEM_UI_FLAG_FULLSCREEN
                       | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        } catch (Throwable t) {
            AppLogger.w(TAG, "setSystemUiVisibility failed: " + t.getMessage());
        }
    }

    // ── SplitController.Host ──────────────────────────────────────────────────

    @Override public String getCurrentDashboardApp() { return mCurrentDashboardApp; }

    @Override public void setCurrentDashboardPkg(String pkg) { mCurrentDashboardPkg = pkg; }

    @Override public void setCurrentDashboardApp(String app) { mCurrentDashboardApp = app; }

    @Override
    public void onSplitStateChanged() {
        updateControlLabel();
        if (mClusterControlCoordinator != null)
            mClusterControlCoordinator.setSplitActive(mSplitController != null && mSplitController.isInSplitMode());
    }

    // ── PermissionBannerCoordinator.Host ─────────────────────────────────────

    @Override public boolean isActivityAlive() { return !isFinishing() && !isDestroyed(); }
    // getContext() and startActivity(Intent) are already provided by Activity / AppCompatActivity.

    // ── AppActionSheet.Host ───────────────────────────────────────────────────

    @Override
    public String getCurrentClusterPkg() {
        return mAppListCoordinator != null ? mAppListCoordinator.getCurrentPackage() : null;
    }

    @Override
    public String getMainDisplayPkg() {
        return mMainDisplayPkg;
    }

    // ── OverflowMenuHelper.Host ───────────────────────────────────────────────

    @Override public boolean isAppListGridMode() {
        return mAppListCoordinator != null && mAppListCoordinator.isGridMode();
    }
    @Override public void toggleAppListViewMode() {
        if (mAppListCoordinator != null) mAppListCoordinator.toggleViewMode();
    }
    @Override public void onOriginCluster() { originCluster(); }
    @Override public void showUsageStats()  { if (mUsageTracker != null) mUsageTracker.showStatsDialog(); }

    // ── DisplayStatePollCoordinator.Host ──────────────────────────────────────

    @Override public String getClusterPkg()     { return mCurrentDashboardPkg; }
    @Override public long   getLastLaunchTime() { return mLastLaunchTime; }
    @Override public void   onClusterPkgDied()  { clearClusterState(); stopClusterMirror(); }
    // getMainDisplayPkg() satisfied by AppActionSheet.Host impl above
    @Override public void   onMainPkgDied() {
        mMainDisplayPkg = null;
        ClusterPrefs.setMainPkg(this, null);
        if (mAppListCoordinator != null) mAppListCoordinator.setMainPackage(null);
    }
    @Override public void runOnMainThread(Runnable r) { runOnUiThread(r); }

    // ── ActivateTimeoutManager.Host ───────────────────────────────────────────

    @Override public void onActivateTimeout() {
        mWasManualActivation = false;
        if (mNavCoordinator != null) mNavCoordinator.setStatusDisconnected();
        Toast.makeText(getApplicationContext(),
                getString(R.string.toast_activate_timeout), Toast.LENGTH_LONG).show();
    }

    // ── InsetAutoApplicator.Host ──────────────────────────────────────────────
    // getContext() and getClusterServiceIfBound() already satisfied above.

    @Override public String getCurrentPkg() { return mCurrentDashboardPkg; }

}


