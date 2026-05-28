package com.byd.dashcast;

import com.byd.dashcast.beta.ShellGateway;
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
import android.view.ViewGroup;
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
@android.annotation.SuppressLint({"ClickableViewAccessibility","SetTextI18n"}) // cluster touches forwarded to display 1; debug labels intentional
@SuppressWarnings("deprecation")
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
            setActivateBtnEnabled(true);
            mMainDisplayPkg      = null;
            clearSplitState();
            if (mAdapter != null) mAdapter.setCurrentPackage(null);
            updateFavoritesIndicators();
            if (mAdapter != null) mAdapter.setMainPackage(null);
            updateFavoritesIndicators();
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
    /** v1.2.9 — set to true once the user permanently dismisses the IME a11y banner. */
    private static final String PREF_IME_BANNER_DISMISSED = "ime_a11y_banner_dismissed";
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
    // Category filter button tints
    private static final int FILTER_TINT_ACTIVE   = 0xFF1976D2;
    private static final int FILTER_TINT_INACTIVE = 0xFF607D8B;

    // UI — barre statut
    private View     mStatusDot;
    private android.graphics.drawable.GradientDrawable mStatusDotDrawable;
    private TextView tvDashboardStatus;
    private View     llAppListSection;  // wrapper for title header + search bar
    private Button   btnActivateCluster;  // v1.2.76 — button removed from layout, field kept as no-op host (always null).
                                          // The 8 setEnabled() callsites below are wrapped by setActivateBtnEnabled(),
                                          // a null-safe helper, so the call graph stays unchanged.
    private Button   btnRestoreCluster;
    private android.widget.ImageView ivNavLogo; // v0.9.81: long-press = overflow menu
    private Button   btnShowMirror;
    private Button   btnSplitLayout;
    private Button   btnRelaunch;
    private Button   btnViewToggle;
    private RecyclerView rvApps;
    private AppListAdapter mAdapter;
    private android.widget.EditText etSearch;

    // v0.9.74 — Favorites horizontal strip
    private LinearLayout llFavoritesSection;
    private LinearLayout llFavoritesStrip;

    // v0.9.74 — Pseudo-fullscreen mirror state
    private com.google.android.material.floatingactionbutton.FloatingActionButton btnExitFullscreen;
    private View vNavRail;
    private View vTopBar;
    private View cardHeroStatus;
    private View tvPreviewSection;
    private View cardClusterPreview;
    private View gridMainActions;
    private View svRightPane;
    private View llRightPaneContent;
    private boolean mIsFullscreenMirror = false;
    private int mSavedPreviewHeightPx = -1;
    private float mSavedPreviewWeight = 0f;
    private int mSavedInnerLLHeight  = ViewGroup.LayoutParams.WRAP_CONTENT;

    // v0.9.79 — reparented control panel during fullscreen (so Ajuster doesn't shrink card)
    private android.widget.FrameLayout vRootOverlay;
    private ViewGroup mPanelOriginalParent = null;
    private int mPanelOriginalIndex = -1;
    private ViewGroup.LayoutParams mPanelOriginalLp = null;

    // UI — category filter buttons
    private View llCategoryFilters;
    private Button btnFilterAll, btnFilterNav, btnFilterMedia;

    // Usage tracking
    private long mClusterAppStartTime = 0;

    // Session-scoped set of all packages that were launched on the cluster (display != 0).
    // Used to move them all back to Display 0 when the user stops the projection,
    // so Android doesn't re-launch them on the (still-alive) VirtualDisplay later.
    // Persisted to SharedPreferences so it survives a process kill (car shutdown).
    private final java.util.Set<String> mSessionClusterPackages = new java.util.LinkedHashSet<>();
    private static final String PREF_SESSION_CLUSTER_PKGS = "session_cluster_pkgs";
    private static final String PREF_GRID_MODE  = "grid_mode";
    private static final String PREF_FAVORITES  = "favorites";

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
    // Surface created from the TextureView's SurfaceTexture.
    // SF is the PRODUCER of this surface (setDisplaySurface) → TextureView renders.
    private Surface      mMirrorSurface;

    // Grace period check for state poll
    private long         mLastLaunchTime = 0;

    // Shared handler for state-poll runnables (was also used by the screenshot
    // mirror fallback removed in 1.2.29 — kept for startStatePoll/stopStatePoll).
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
            // Start the mirror if the surface is available.
            // v1.2.85 — proxy on frameMirror (the actual TextureView container)
            // because panelClusterControl is now only shown in fullscreen mode.
            if (mMirrorSurface != null && mMirrorSurface.isValid()
                    && frameMirror != null
                    && frameMirror.getVisibility() == View.VISIBLE) {
                // v1.2.55-beta — if a direct-path (no daemon) mirror was set up
                // during the cold-start window before the daemon Binder was
                // available, it is silently black on DL3/DL5 (no ACCESS_SURFACE_FLINGER).
                // Tear it down so attemptStartMirror picks the daemon path now.
                if (mServiceBound && mClusterService != null) {
                    com.byd.dashcast.dashboard.ClusterMirrorManager mm =
                            mClusterService.getMirrorManager();
                    if (mm.isMirrorActive() && !mm.isMirrorViaDaemon()) {
                        AppLogger.i(TAG, "Daemon arrived after direct-path mirror — restarting via daemon");
                        stopClusterMirror();
                    }
                }
                attemptStartMirrorWithCurrentHolder();
            }
        }
    };

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
        // Auto-launch (cf. PREF_AUTO_LAUNCH_PKG / onSetAutoLaunch / onClusterDisplayConnected).
        // The pending package is consumed at most once per Activity instance, in the cluster-connect
        // callback. Setting it here ensures that if the user previously marked an app as auto-launch,
        // it is sent to the cluster as soon as the dashboard display becomes available.
        mPendingAutoLaunchPkg = bootPrefs.getString(PREF_AUTO_LAUNCH_PKG, null);
        if (!bootPrefs.getBoolean(SettingsActivity.PREF_BOOT_AUTO_START, false)) {
            // Defensive: cleanup uses IActivityTaskManager binder reflection per package.
            // With a non-trivial persisted set, calling on the main thread during onCreate
            // could approach the ANR threshold. Off-load to a named daemon thread; the work
            // is purely a safety-net (BootReceiver already runs it asynchronously at boot).
            final Context appCtx = getApplicationContext();
            Thread cleanupThread = new Thread(new Runnable() {
                @Override public void run() { cleanupDisplayAffinityAtBoot(appCtx); }
            }, "boot-cleanup-fallback");
            cleanupThread.setDaemon(true);
            cleanupThread.start();
        } else {
            // Auto-start enabled: clear the persisted set (projection is active,
            // apps will be managed normally).
            bootPrefs.edit().remove(PREF_SESSION_CLUSTER_PKGS).apply();
        }

        // Unlock hidden Android APIs (SurfaceControl, etc.)
        // Must be called before any call to ClusterMirrorManager.startMirror(this, ).
        // Same mechanism as WindowManagement v1.2 (VMRuntime.setHiddenApiExemptions).
        com.byd.dashcast.dashboard.ClusterMirrorManager.unlockHiddenApis();

        // v1.2.55-beta — storage hygiene. Field report: app reached 1.08 GB
        // because every save/share/sniffer run produced a fresh timestamped
        // file in getExternalFilesDir and nothing rotated. Also kills any
        // orphan sniffer logcat processes that survived a previous DashCast
        // force-stop (setsid-detached, see DiagActivity.startSniffer). Both
        // calls are silent no-ops when nothing needs cleanup.
        try {
            AppLogger.pruneOldFiles(this, 3);
        } catch (Throwable t) {
            AppLogger.w(TAG, "pruneOldFiles failed: " + t.getMessage());
        }
        try {
            // Tag file lives on tmpfs (/data/local/tmp) and disappears at boot,
            // so a present tag without a recorded session in DiagActivity's prefs
            // means an orphan from a previous run (e.g. the user force-stopped
            // DashCast while a sniffer capture was running — the setsid-detached
            // logcat processes survived). We gate on the *absence* of the saved
            // sniffer path so we never clobber a legitimate session that simply
            // happens to coexist with MainActivity being (re)created. The
            // pref-name pair below is intentionally hard-coded — pulling them
            // via reflection from DiagActivity would force-load that Activity's
            // class graph at app start, which we want to avoid.
            String savedSnifferPath = getSharedPreferences("byd_diag_prefs", MODE_PRIVATE)
                    .getString("re_sniffer_file_path", null);
            if (savedSnifferPath == null) {
                com.byd.dashcast.beta.ShellGateway.execShell(this,
                    "if [ -f /data/local/tmp/.re_sniffer_run ]; then"
                  + "  rm -f /data/local/tmp/.re_sniffer_run;"
                  + "  if [ -f /data/local/tmp/.re_sniffer_pids ]; then"
                  + "    while IFS= read -r pid; do"
                  + "      [ -n \"$pid\" ] && kill -9 \"$pid\" 2>/dev/null;"
                  + "    done < /data/local/tmp/.re_sniffer_pids;"
                  + "    rm -f /data/local/tmp/.re_sniffer_pids;"
                  + "  fi;"
                  + "  pkill -f BYD_RE_Sniffer_ 2>/dev/null; true;"
                  + "fi");
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "orphan-sniffer cleanup failed: " + t.getMessage());
        }

        // Receiver to retrieve the MirrorDaemon Binder (uid=2000)
        registerReceiver(mDaemonReadyReceiver,
                new IntentFilter(com.byd.dashcast.daemon.MirrorDaemon.ACTION_DAEMON_READY));

        // Floating 📺 mirror button — started once, visibility controlled by show()/hide()
        startService(new Intent(this, FloatingRemoteButton.class));

        // Handle a tap on the floating button when the Activity is already alive
        // (Activity exists in back stack → onNewIntent fires instead of onCreate)
        handleShowMirrorIntent(getIntent());

        mStatusDot          =            findViewById(R.id.view_status_dot);
        tvDashboardStatus   = (TextView) findViewById(R.id.tv_dashboard_status);
        mStatusDotDrawable  = new android.graphics.drawable.GradientDrawable();
        mStatusDotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (mStatusDot != null) mStatusDot.setBackground(mStatusDotDrawable);
        btnActivateCluster  = null;  // v1.2.76 — layout entry removed; field nulled explicitly.
        btnRestoreCluster   = (Button)   findViewById(R.id.btn_restore_cluster);
        ivNavLogo           = (android.widget.ImageView) findViewById(R.id.iv_nav_logo);
        btnShowMirror       = (Button)   findViewById(R.id.btn_show_mirror);
        llAppListSection    =            findViewById(R.id.ll_app_list_section);
        rvApps             = (RecyclerView) findViewById(R.id.rv_apps);
        etSearch           = (android.widget.EditText) findViewById(R.id.et_search_apps);
        btnViewToggle      = (Button)   findViewById(R.id.btn_view_toggle);

        // v0.9.74 — Favorites strip + fullscreen overlay refs.
        llFavoritesSection = (LinearLayout) findViewById(R.id.ll_favorites_section);
        llFavoritesStrip   = (LinearLayout) findViewById(R.id.ll_favorites_strip);
        btnExitFullscreen  = findViewById(R.id.btn_exit_fullscreen);
        vNavRail           = findViewById(R.id.ll_nav_rail);
        vTopBar            = findViewById(R.id.ll_top_bar);
        cardHeroStatus     = findViewById(R.id.card_hero_status);
        tvPreviewSection   = findViewById(R.id.tv_preview_section);
        cardClusterPreview = findViewById(R.id.card_cluster_preview);
        gridMainActions    = findViewById(R.id.grid_main_actions);
        svRightPane        = findViewById(R.id.sv_right_pane);
        llRightPaneContent = findViewById(R.id.ll_right_pane_content);
        vRootOverlay       = findViewById(R.id.root_overlay);
        if (btnExitFullscreen != null) {
            btnExitFullscreen.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { exitFullscreenMirror(); }
            });
        }

        // App list
        mAdapter = new AppListAdapter(this);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // v0.9.71 — grid is now the default view mode (mockup fidelity).
        boolean isGrid = prefs.getBoolean(PREF_GRID_MODE, true);
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
        // v0.9.71 — category chips visible by default (mockup fidelity).
        boolean showFilters = prefs.getBoolean(SettingsActivity.PREF_SHOW_CATEGORY_FILTERS, true);
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

        // Button &#9654; View toggle (list ↔ grid) in the title header
        btnViewToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleViewMode(); }
        });

        // v0.9.81 — Long-press the nav rail logo opens the overflow menu (Language,
        // Updates, View toggle, Origin Cluster, Stats). The clock + ⋮ button are gone.
        if (ivNavLogo != null) {
            ivNavLogo.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showOverflowMenu(v);
                    return true;
                }
            });
        }

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

        // ── v0.9.7 — Nav rail clicks (left M3 navigation rail) ─────────────────
        View navSettings = findViewById(R.id.nav_settings);
        if (navSettings != null) navSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        View navDiag = findViewById(R.id.nav_diag);
        if (navDiag != null) navDiag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagActivity.class));
            }
        });
        View navSysinfo = findViewById(R.id.nav_sysinfo);
        if (navSysinfo != null) navSysinfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SysInfoActivity.class));
            }
        });
        View navLog = findViewById(R.id.nav_log);
        if (navLog != null) navLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogActivity.class));
            }
        });
        // v1.2.36 — Hotspot (DL3 only) ; v1.2.42 — conditionné sur pref "use_own_sim".
        refreshNavHotspot();
        View navHelp = findViewById(R.id.nav_help);
        if (navHelp != null) navHelp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent it = new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Kiroha/byd-dashcast"));
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(), R.string.main_nav_help, Toast.LENGTH_SHORT).show();
                }
            }
        });

        // ── v0.9.7 — Capture button removed in v1.2.76 (was a coming-soon placeholder).

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
        if (!com.byd.dashcast.platform.Platform.get().isClusterTaskResizeSupported(this)) {
            if (btnToggleResize != null) btnToggleResize.setVisibility(View.GONE);
            if (panelResize != null) panelResize.setVisibility(View.GONE);
            AppLogger.i(TAG, "Resize UI hidden: cluster task resize is not supported on this ROM "
                    + "(DL5 set-task-windowing-mode stripped — see DL5_CLUSTER_RESIZE_LIMITATION.md)");
        }
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
                ed.putInt(SettingsActivity.PREF_INSET_H_PREFIX + mCurrentDashboardPkg, w);
                ed.putInt(SettingsActivity.PREF_INSET_V_PREFIX + mCurrentDashboardPkg, h);
                ed.apply();
                
                AppLogger.i(TAG, "Applied custom resize " + w + "/" + h + " for " + mCurrentDashboardPkg);
                
                if (mServiceBound && mClusterService != null) {
                    // findRunningTaskId() calls getRunningTasks() — must run off the main thread.
                    final String pkg = mCurrentDashboardPkg;
                    final ClusterService svc = mClusterService;
                    // DL5 fix: cluster display id is NOT hardcoded 1 — resolve dynamically
                    // via DashboardDisplayHelper. Skip wm overscan if no cluster is connected
                    // (avoids shrinking display 0 on DL2/disconnected states).
                    final int clusterId = svc.getDisplayId();
                    if (clusterId > 0) {
                        // v1.2.13 — wm overscan was removed from Android 11+ (API 30+).
                        // DL5 is API 32 → "Unknown command: overscan" on every call.
                        // resizeActiveTask (typed verb below) is the real path on DL5.
                        if (AdbLocalClient.isDiLink5Safe(MainActivity.this)) {
                            AppLogger.d(TAG, "Apply resize DL5: skipping wm overscan (cmd removed in API 30+) — resizeTask handles it");
                        } else {
                            ShellGateway.execShell(MainActivity.this,
                                    "wm overscan " + w + "," + h + "," + w + "," + h + " -d " + clusterId);
                        }
                    } else {
                        AppLogger.w(TAG, "Apply resize: cluster display not connected — wm overscan skipped");
                    }
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
        mInsetOverlay       = (InsetOverlayView) findViewById(R.id.inset_overlay);

        // Restore mMainDisplayPkg (lost if Activity is destroyed and recreated)
        mMainDisplayPkg = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_MAIN_PKG, null);
        if (mMainDisplayPkg != null) {
            mAdapter.setMainPackage(mMainDisplayPkg);
            updateFavoritesIndicators();
        }

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

        // v1.2.9 — IME a11y onboarding banner (DL5 only)
        setupImeA11yBanner();

        // Cluster mirror: touch → map coordinates → inject on display 1
        clusterMirror.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // v0.9.79 — prevent NestedScrollView (or any ancestor) from stealing the
                // gesture once the finger moves past touchSlop, otherwise vertical drags
                // and pinch gestures get cancelled mid-flight.
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                forwardTouchFromMirror(v, event);
                return true;
            }
        });

        // Async loading of the app list (async to avoid blocking the UI)
        loadAppsAsync();

        // OTA update check — only on fresh launch, not on rotation
        if (savedInstanceState == null) {
            UpdateChecker.checkUpdate(this, makeOtaProgressListener(this, false));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShowMirrorIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Hotspot navrail entry depends on the "use_own_sim" pref which can
        // change in SettingsActivity ; re-evaluate on every resume.
        refreshNavHotspot();
        // v1.2.45 — Compact apps panel pref is also live-applied so the user
        // sees the new column layout as soon as they leave Settings.
        applyCompactAppsPanelMode();
    }

    /**
     * Show + wire the navrail Hotspot button only when:
     *  - the device is a DiLink 3 head-unit (TetherFi is our only path there)
     *  - AND the user has opted in via the "use_own_sim" Setting
     * Otherwise hide it.
     */
    private void refreshNavHotspot() {
        View navHotspot = findViewById(R.id.nav_hotspot);
        if (navHotspot == null) return;
        boolean isDl3 = com.byd.dashcast.platform.Platform.get().isDiLink3(this);
        // v1.2.38 fix: default is now TRUE so the navrail entry appears on DL3
        // out of the box. Previous default (false) made the icon invisible until
        // the user discovered the "I use my own SIM" toggle in Settings, which
        // was reported as a missing-icon bug. Users without a personal SIM can
        // still hide it via the same Settings toggle.
        boolean useOwnSim = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_USE_OWN_SIM, true);
        if (isDl3 && useOwnSim) {
            navHotspot.setVisibility(View.VISIBLE);
            navHotspot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, HotspotActivity.class));
                }
            });
        } else {
            navHotspot.setVisibility(View.GONE);
            navHotspot.setOnClickListener(null);
        }
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
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean compact = prefs.getBoolean(SettingsActivity.PREF_COMPACT_APPS_PANEL, false);

        // Adjust the left-pane weight inside the parent horizontal LinearLayout.
        android.view.ViewGroup.LayoutParams lp = llAppListSection.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            float target = compact ? 0.6f : 1.4f;
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
            if (llp.weight != target) {
                llp.weight = target;
                llAppListSection.setLayoutParams(llp);
            }
        }

        // Re-create the GridLayoutManager with the right spanCount if currently
        // in grid mode. In list mode there is no spanCount, so we skip — the
        // narrowed column still works fine with a vertical list.
        if (rvApps != null && mAdapter != null && mAdapter.isGridMode()) {
            int targetSpan = compact ? 2 : 5;
            androidx.recyclerview.widget.RecyclerView.LayoutManager cur = rvApps.getLayoutManager();
            int curSpan = (cur instanceof GridLayoutManager) ? ((GridLayoutManager) cur).getSpanCount() : -1;
            if (curSpan != targetSpan) {
                rvApps.setLayoutManager(new GridLayoutManager(this, targetSpan));
            }
        }

        // Chips visibility: forced GONE in compact mode, otherwise honour the
        // user's PREF_SHOW_CATEGORY_FILTERS pref so toggling compact off
        // restores the chips if the user had them enabled.
        View chips = findViewById(R.id.ll_category_filters);
        if (chips != null) {
            if (compact) {
                chips.setVisibility(View.GONE);
            } else {
                boolean showChips = prefs.getBoolean(
                        SettingsActivity.PREF_SHOW_CATEGORY_FILTERS, false);
                chips.setVisibility(showChips ? View.VISIBLE : View.GONE);
            }
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
        trackUsageStop(mCurrentDashboardPkg);
        int displayId = mClusterService.getDisplayId();
        if (displayId < 0) displayId = 1;
        mClusterService.moveTaskToDisplay(pkgName, displayId, new ClusterService.LaunchCallback() {
            @Override public void onResult(boolean launched) {
                if (launched) {
                    mLastLaunchTime = System.currentTimeMillis(); // set grace period on quick-switch launch
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
                    updateFavoritesIndicators();
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
        // v1.2.9 — user may have just enabled/disabled the a11y service in Settings.
        try { refreshImeA11yBanner(); } catch (Throwable t) {
            AppLogger.e("MainActivity", "refreshImeA11yBanner failed", t);
        }
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
                    setActivateBtnEnabled(true);
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
                tvDashboardStatus.setText(getString(R.string.status_starting_cluster));
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
        startStatePoll();
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStop");
        stopStatePoll();
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
            unbindService(mServiceConn);
            mServiceBound  = false;
            mBindRequested = false;
        }
        // Release the preview Surface wrapping the TextureView SurfaceTexture so
        // it is not retained until GC (the underlying SurfaceTexture is released
        // by the framework when the TextureView is destroyed, but the Surface
        // wrapper itself must be released explicitly).
        if (mMirrorSurface != null) {
            try { mMirrorSurface.release(); } catch (Exception ignored) {}
            mMirrorSurface = null;
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
                if (isFinishing() || isDestroyed()) return;
                cancelActivateTimeout();
                final boolean wasManual = mWasManualActivation;
                mWasManualActivation = false;
                updateDashboardStatus(null);
                setActivateBtnEnabled(true);

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
                        // NB: ne PAS armer mLastLaunchTime ici. Ce chemin est une
                        // restauration depuis les prefs après recreate d'Activity ;
                        // le « launch » réel est potentiellement très ancien et le
                        // process peut avoir été tué pendant que DashCast était en
                        // background. La grace period n'a de sens qu'autour d'un
                        // am start fraîchement dispatché (cf. les 3 sites de
                        // launch dans onSendToDashboard / quickSwitchToApp).
                        mAdapter.setCurrentPackage(_pkg);
                        updateFavoritesIndicators();
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
                    mMainDisplayPkg = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_MAIN_PKG, null);
                    if (mMainDisplayPkg != null) mAdapter.setMainPackage(mMainDisplayPkg);
                    updateFavoritesIndicators();
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
                // Guarded by user preference (can be disabled in Settings).
                boolean reconnectEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(SettingsActivity.PREF_RECONNECT_POPUP, false);
                if (reconnectEnabled && wasManual && mCurrentDashboardPkg == null) {
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
                setActivateBtnEnabled(true);
                mMainDisplayPkg = null;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .remove(PREF_MAIN_PKG)
                        .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
                clearSplitState();
                mAdapter.setCurrentPackage(null);
                updateFavoritesIndicators();
                mAdapter.setMainPackage(null);
                updateFavoritesIndicators();
                // v0.9.73 — unified OFF state ("Projection inactive") with grey dot.
                setDashboardOffState();
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
            @android.annotation.SuppressLint("NotifyDataSetChanged") // full refresh after auto-launch toggle
            @Override
            public void run() {
                mAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onToggleFavorite(AppInfo app) {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> favs = new HashSet<>(p.getStringSet(PREF_FAVORITES, new HashSet<>()));
        if (favs.contains(app.packageName)) {
            favs.remove(app.packageName);
            app.isFavorite = false;
        } else {
            favs.add(app.packageName);
            app.isFavorite = true;
        }
        p.edit().putStringSet(PREF_FAVORITES, favs).apply();
        loadAppsAsync(); // Reload and re-sort
    }

    // v0.9.72 — long-press opens a bottom sheet with the per-app actions that
    // used to live as cramped chips inside each grid cell.
    @Override
    @android.annotation.SuppressLint("InflateParams") // BottomSheetDialog content has no parent at inflation
    public void onShowActions(final AppInfo app) {
        if (app == null || isFinishing() || isDestroyed()) return;
        final com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.dialog_app_actions, null);
        dialog.setContentView(v);

        ImageView icon  = v.findViewById(R.id.sheet_icon);
        TextView  name  = v.findViewById(R.id.sheet_name);
        TextView  pkg   = v.findViewById(R.id.sheet_pkg);
        com.google.android.material.materialswitch.MaterialSwitch swAuto =
                v.findViewById(R.id.sheet_sw_auto);
        View      rowFav     = v.findViewById(R.id.sheet_action_favorite);
        TextView  lblFav     = v.findViewById(R.id.sheet_lbl_favorite);
        View      rowToMain  = v.findViewById(R.id.sheet_action_to_main);
        View      rowToClus  = v.findViewById(R.id.sheet_action_to_cluster);
        View      rowResize  = v.findViewById(R.id.sheet_action_resize);
        View      rowDpi     = v.findViewById(R.id.sheet_action_dpi);
        TextView  tvDpiVal   = v.findViewById(R.id.sheet_dpi_value);
        View      rowKill    = v.findViewById(R.id.sheet_action_kill);

        icon.setImageDrawable(app.icon);
        name.setText(app.appName);
        pkg.setText(app.packageName);
        lblFav.setText(app.isFavorite
                ? R.string.sheet_remove_favorite
                : R.string.sheet_add_favorite);

        swAuto.setChecked(app.isAutoLaunch);
        swAuto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                onSetAutoLaunch(app, checked);
            }
        });

        rowFav.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                onToggleFavorite(app);
                dialog.dismiss();
            }
        });

        // Visibility for screen-move actions mirrors the previous inline-chip logic.
        final boolean isActive = app.packageName != null
                && app.packageName.equals(mAdapter.getCurrentPackage());
        final boolean isOnMain = app.packageName != null
                && app.packageName.equals(mAdapter.getMainPackage());
        rowToMain.setVisibility(isActive ? View.VISIBLE : View.GONE);
        rowToClus.setVisibility(isOnMain || (!isActive && !isOnMain) ? View.VISIBLE : View.GONE);
        rowResize.setVisibility(isActive ? View.VISIBLE : View.GONE);
        rowKill.setVisibility((isActive || isOnMain) ? View.VISIBLE : View.GONE);

        rowToMain.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                onSendToMain(app);
                dialog.dismiss();
            }
        });
        rowToClus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                onSendToDashboard(app);
                dialog.dismiss();
            }
        });
        rowResize.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                int displayId = mClusterService != null ? mClusterService.getDisplayId() : 1;
                if (displayId < 0) displayId = 1; // Seal EU fission fallback
                Intent it = new Intent(MainActivity.this,
                        com.byd.dashcast.cluster.ClusterResizeActivity.class);
                it.putExtra(com.byd.dashcast.cluster.ClusterResizeActivity.EXTRA_PACKAGE,
                        app.packageName);
                it.putExtra(com.byd.dashcast.cluster.ClusterResizeActivity.EXTRA_DISPLAY_ID,
                        displayId);
                startActivity(it);
                dialog.dismiss();
            }
        });

        // v1.2.81 — per-app cluster DPI override. Always visible; the dialog
        // is read-only of the current display, applies on next launch.
        final int curDpi = com.byd.dashcast.cluster.ClusterDpiPrefs.getDpi(this, app.packageName);
        if (curDpi > 0) {
            tvDpiVal.setText(getString(R.string.sheet_dpi_value_fmt, curDpi));
        } else {
            tvDpiVal.setText(R.string.sheet_dpi_value_default);
        }
        rowDpi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                dialog.dismiss();
                showClusterDpiDialog(app);
            }
        });
        rowKill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                onKillApp(app);
                dialog.dismiss();
            }
        });

        dialog.show();
        // v0.9.73 — open fully expanded (skip the half-collapsed peek that hides actions).
        try {
            com.google.android.material.bottomsheet.BottomSheetBehavior<?> b = dialog.getBehavior();
            b.setSkipCollapsed(true);
            b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        } catch (Throwable t) {
            AppLogger.w(TAG, "BottomSheet expand failed: " + t.getMessage());
        }
    }

    // v1.2.81 — Cluster per-app DPI override picker.
    // Presets cover the common cluster-friendly densities (Waze, Netflix,
    // dashboards built for phones/tablets); "Custom" accepts 96..480 dpi.
    // The override is applied at the next launch on the cluster display
    // (ClusterDpiManager.applyForLaunch) — never on display 0 (head unit).
    @android.annotation.SuppressLint("InflateParams")
    private void showClusterDpiDialog(final com.byd.dashcast.model.AppInfo app) {
        if (app == null || app.packageName == null || isFinishing() || isDestroyed()) return;

        final int[] presets = { 120, 160, 200, 240, 280, 320 };
        final int current = com.byd.dashcast.cluster.ClusterDpiPrefs.getDpi(this, app.packageName);

        // Build entries: "Default" + presets + "Custom…"
        final String[] labels = new String[presets.length + 2];
        labels[0] = getString(R.string.dpi_default);
        for (int i = 0; i < presets.length; i++) {
            labels[i + 1] = getString(R.string.dpi_value_fmt, presets[i]);
        }
        labels[labels.length - 1] = getString(R.string.dpi_custom);

        // Pre-select current
        int checked = 0; // default
        if (current > 0) {
            checked = labels.length - 1; // custom by default
            for (int i = 0; i < presets.length; i++) {
                if (presets[i] == current) {
                    checked = i + 1;
                    break;
                }
            }
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.dpi_dialog_title, app.appName))
                .setSingleChoiceItems(labels, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            // Default — clear override
                            com.byd.dashcast.cluster.ClusterDpiPrefs.setDpi(
                                    MainActivity.this, app.packageName, 0);
                            d.dismiss();
                            android.widget.Toast.makeText(MainActivity.this,
                                    R.string.dpi_applied_next_launch,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        } else if (which == labels.length - 1) {
                            d.dismiss();
                            showClusterDpiCustomDialog(app, current);
                        } else {
                            int dpi = presets[which - 1];
                            com.byd.dashcast.cluster.ClusterDpiPrefs.setDpi(
                                    MainActivity.this, app.packageName, dpi);
                            d.dismiss();
                            android.widget.Toast.makeText(MainActivity.this,
                                    R.string.dpi_applied_next_launch,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @android.annotation.SuppressLint("InflateParams")
    private void showClusterDpiCustomDialog(final com.byd.dashcast.model.AppInfo app,
                                            final int prefill) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.dpi_custom_hint,
                com.byd.dashcast.cluster.ClusterDpiPrefs.MIN_DPI,
                com.byd.dashcast.cluster.ClusterDpiPrefs.MAX_DPI));
        if (prefill > 0) input.setText(String.valueOf(prefill));

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dpi_custom)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        int dpi;
                        try { dpi = Integer.parseInt(input.getText().toString().trim()); }
                        catch (NumberFormatException e) { dpi = 0; }
                        if (dpi < com.byd.dashcast.cluster.ClusterDpiPrefs.MIN_DPI
                                || dpi > com.byd.dashcast.cluster.ClusterDpiPrefs.MAX_DPI) {
                            android.widget.Toast.makeText(MainActivity.this,
                                    getString(R.string.dpi_custom_hint,
                                            com.byd.dashcast.cluster.ClusterDpiPrefs.MIN_DPI,
                                            com.byd.dashcast.cluster.ClusterDpiPrefs.MAX_DPI),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        com.byd.dashcast.cluster.ClusterDpiPrefs.setDpi(
                                MainActivity.this, app.packageName, dpi);
                        android.widget.Toast.makeText(MainActivity.this,
                                R.string.dpi_applied_next_launch,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onSendToDashboard(AppInfo app) {
        incrementLaunchCount(app.packageName);
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
            mAdapter.setMainPackage(null);
            updateFavoritesIndicators();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_MAIN_PKG).apply();
        }

        // ── Split mode: an app already occupies a slot → the new app goes into the other one ──
        if (mCurrentSplitSlot != 0 && mCurrentDashboardPkg != null) {
            // Same app as the main or secondary slot already present: ignore
            if (pkgName.equals(mCurrentDashboardPkg) || pkgName.equals(mSecondDashboardPkg)) {
                AppLogger.w(TAG, "split: duplicate ignored pkg=" + pkgName
                        + " (main=" + mCurrentDashboardPkg + " second=" + mSecondDashboardPkg + ")");
                Toast.makeText(getApplicationContext(), getString(R.string.toast_app_already_cluster), Toast.LENGTH_SHORT).show();
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
                        mLastLaunchTime = System.currentTimeMillis(); // set grace period on split launch
                        mSecondDashboardApp = appName;
                        mSecondDashboardPkg = pkgName;
                        mSessionClusterPackages.add(pkgName);
                        persistSessionClusterPackages();
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
                    updateFavoritesIndicators();
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
                    autoApplyInsetsIfNeeded(pkgName);
                } else {
                    Toast.makeText(getApplicationContext(),
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
        updateFavoritesIndicators();
        mAdapter.setMainPackage(app.packageName);
        updateFavoritesIndicators();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(PREF_MAIN_PKG, app.packageName).apply();
        updateDashboardStatus(null);
        setActivateBtnEnabled(true);
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
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
            mAdapter.setCurrentPackage(null);
            updateFavoritesIndicators();
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
                        AppLogger.i(TAG, "forceStop " + app.packageName + " OK");
                        // Cluster state already cleared eagerly above (before async ops).
                        if (app.packageName != null && app.packageName.equals(mSecondDashboardPkg)) {
                            mSecondDashboardPkg = null;
                            clearSplitState();
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
                        Toast.makeText(getApplicationContext(),
                                getString(R.string.toast_kill_failed, error), Toast.LENGTH_LONG).show();
                        AppLogger.log(TAG, "forceStop FAILED: " + error);
                    }
                });
            }
        };

        if (mSessionClusterPackages.contains(app.packageName)
                && mServiceBound && mClusterService != null) {
            mClusterService.moveTaskToDisplay(app.packageName, 0,
                    new com.byd.dashcast.ClusterService.LaunchCallback() {
                @Override public void onResult(boolean ok) {
                    AppLogger.i(TAG, "doKillApp: move→display0 " + (ok ? "OK" : "KO")
                            + " for " + app.packageName + " — now force-stop");
                    mSessionClusterPackages.remove(app.packageName);
                    persistSessionClusterPackages();
                    AdbLocalClient.forceStopApp(MainActivity.this, app.packageName, killCallback);
                }
            });
        } else {
            mSessionClusterPackages.remove(app.packageName);
            persistSessionClusterPackages();
            // 3. am force-stop via ADB
            AdbLocalClient.forceStopApp(this, app.packageName, killCallback);
        }
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
                                // 1.2.30 — activity teardown guard: the lookup is async
                                // (separate Thread) and may resolve after onDestroy()
                                // when the user backs out during daemon startup. Touching
                                // mClusterService / panelClusterControl after destroy
                                // can NPE the input forwarder.
                                if (isFinishing() || isDestroyed()) return;
                                mDaemonBinder = binder;
                                if (mServiceBound && mClusterService != null) {
                                    mClusterService.getInputForwarder().setDaemonBinder(binder);
                                }
                        // Restart the mirror if it is currently shown.
                        // v1.2.85 — was panelClusterControl (now fullscreen-only).
                        if (mCurrentDashboardApp != null
                                        && frameMirror != null
                                        && frameMirror.getVisibility() == View.VISIBLE) {
                                    // v1.2.55-beta — symmetric to the receiver path:
                                    // tear down any direct-path mirror so the daemon path
                                    // can take over and surface actual frames.
                                    if (mClusterService != null) {
                                        com.byd.dashcast.dashboard.ClusterMirrorManager mm =
                                                mClusterService.getMirrorManager();
                                        if (mm.isMirrorActive() && !mm.isMirrorViaDaemon()) {
                                            AppLogger.i(TAG, "Daemon resolved late — restarting mirror via daemon");
                                            stopClusterMirror();
                                        }
                                    }
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
            tvMirrorPlaceholder.setVisibility(View.GONE);
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
                    this, mDaemonBinder, clusterDisplay, mMirrorSurface, viewW, viewH);
        }
        // Fallback: direct SurfaceControl uid=10100 (fails if ACCESS_SURFACE_FLINGER missing)
        if (!mirrorOk) {
            mirrorOk = mClusterService.getMirrorManager().startMirror(
                    this, clusterDisplay, mMirrorSurface, viewW, viewH);
        }

        if (mirrorOk) {
            // Mirror active → show TextureView, hide placeholder.
            clusterMirror.setVisibility(View.VISIBLE);
            tvMirrorPlaceholder.setVisibility(View.GONE);
        } else {
            // Mirror unavailable (SurfaceControl + daemon both failed). Since 1.2.29
            // we no longer fall back to the 800 ms screencap loop — display a static
            // message instead. The cluster app keeps running normally; only the
            // local preview pane is unavailable.
            clusterMirror.setVisibility(View.GONE);
            tvMirrorPlaceholder.setText(R.string.mirror_unavailable);
            tvMirrorPlaceholder.setVisibility(View.VISIBLE);
        }
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

    private static final long STATE_POLL_INTERVAL_MS = 5_000;
    private Runnable mStatePollRunnable;

    private void startStatePoll() {
        if (mStatePollRunnable != null) return;
        mStatePollRunnable = new Runnable() {
            @Override public void run() {
                reconcileDisplayState();
                reconcileMainDisplayState(); // v0.9.72 — also drop stale "on main" markers
                mScreenshotHandler.postDelayed(this, STATE_POLL_INTERVAL_MS);
            }
        };
        // First poll after 5 s — let state settle after onStart.
        mScreenshotHandler.postDelayed(mStatePollRunnable, STATE_POLL_INTERVAL_MS);
    }

    private void stopStatePoll() {
        if (mStatePollRunnable != null) {
            mScreenshotHandler.removeCallbacks(mStatePollRunnable);
            mStatePollRunnable = null;
        }
    }

    /**
     * Checks if the cluster app process is still alive using {@code pidof}
     * via ADB shell.  The app process itself cannot read /proc for other UIDs
     * (hidepid=2), but ADB shell (uid 2000) can.
     *
     * If the process is gone, clears cluster bookkeeping and stops the mirror.
     */
    private void reconcileDisplayState() {
        final String clusterPkg = mCurrentDashboardPkg;
        if (clusterPkg == null) return;

        // Grace period of 8 seconds to allow the app process to launch and register in pidof
        if (System.currentTimeMillis() - mLastLaunchTime < 8000) {
            AppLogger.d(TAG, "state-poll: skipping pidof check during launch grace period for " + clusterPkg);
            return;
        }

        ShellGateway.execShellWithResult(this, "pidof " + clusterPkg,
                new AdbLocalClient.Callback() {
                    @Override
                    public void onSuccess(String output) {
                        final boolean alive = output != null && !output.trim().isEmpty();
                        if (alive) {
                            AppLogger.d(TAG, "state-poll: " + clusterPkg
                                    + " alive (pid " + output.trim() + ")");
                            return;
                        }
                        // Process not found → app died externally
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                // Activity may have been destroyed while pidof was in flight.
                                if (isFinishing() || isDestroyed()) return;
                                // Re-check: still tracking the same package?
                                if (!clusterPkg.equals(mCurrentDashboardPkg)) return;
                                AppLogger.w(TAG, "state-poll: " + clusterPkg
                                        + " process died → clearing cluster state");
                                clearClusterState();
                                stopClusterMirror();
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        AppLogger.w(TAG, "state-poll: pidof failed: " + error);
                    }
                });
    }

    /**
     * v0.9.72 — sibling of {@link #reconcileDisplayState()} for the MAIN display marker.
     * If the user kills the app sent to the main screen (via recents / system), the
     * adapter still tags it as "on main" until the next reload. This pidof check
     * clears the marker so the grid tile state stays in sync within ~5 s.
     */
    private void reconcileMainDisplayState() {
        final String mainPkg = mMainDisplayPkg;
        if (mainPkg == null) return;

        ShellGateway.execShellWithResult(this, "pidof " + mainPkg,
                new AdbLocalClient.Callback() {
                    @Override
                    public void onSuccess(String output) {
                        final boolean alive = output != null && !output.trim().isEmpty();
                        if (alive) return;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (isFinishing() || isDestroyed()) return;
                                if (!mainPkg.equals(mMainDisplayPkg)) return;
                                AppLogger.w(TAG, "state-poll: main-display app " + mainPkg
                                        + " process died → clearing main marker");
                                mMainDisplayPkg = null;
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                        .edit().remove(PREF_MAIN_PKG).apply();
                                if (mAdapter != null) {
                                    mAdapter.setMainPackage(null);
                                    updateFavoritesIndicators();
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        AppLogger.w(TAG, "state-poll: main pidof failed: " + error);
                    }
                });
    }

    /**
     * Clears all cluster-app bookkeeping and returns to the app list.
     * Shared by reconcileDisplayState and other paths that need a clean reset.
     */
    private void clearClusterState() {
        trackUsageStop(mCurrentDashboardPkg);
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
        mAdapter.setCurrentPackage(null);
        updateFavoritesIndicators();
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
            int curW = p.getInt(SettingsActivity.PREF_INSET_H_PREFIX + mCurrentDashboardPkg, defH);
            int curH = p.getInt(SettingsActivity.PREF_INSET_V_PREFIX + mCurrentDashboardPkg, defV);
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
        final int savedW = p.getInt(SettingsActivity.PREF_INSET_H_PREFIX + pkg, defH);
        final int savedH = p.getInt(SettingsActivity.PREF_INSET_V_PREFIX + pkg, defV);
        // Only apply if there are per-app custom insets (different from global defaults)
        if (savedW == defH && savedH == defV) return;
        AppLogger.d(TAG, "autoApplyInsets pkg=" + pkg + " w=" + savedW + " h=" + savedH);
        // Small delay: give the app time to render on the cluster before resizing
        mScreenshotHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!pkg.equals(mCurrentDashboardPkg)) return; // app changed in the meantime
                if (mServiceBound && mClusterService != null) {
                    final ClusterService svc = mClusterService;
                    // DL5 fix: resolve cluster display id dynamically (was hardcoded -d 1).
                    final int clusterId = svc.getDisplayId();
                    if (clusterId > 0) {
                        // v1.2.13 — wm overscan removed in API 30+ (DL5 = API 32). Skip on DL5.
                        if (AdbLocalClient.isDiLink5Safe(MainActivity.this)) {
                            AppLogger.d(TAG, "autoApplyInsets DL5: skipping wm overscan (cmd removed in API 30+) — resizeTask handles it");
                        } else {
                            ShellGateway.execShell(MainActivity.this,
                                    "wm overscan " + savedW + "," + savedH + "," + savedW + "," + savedH
                                            + " -d " + clusterId);
                        }
                    } else {
                        AppLogger.w(TAG, "autoApplyInsets: cluster display not connected — wm overscan skipped");
                    }
                    new Thread(new Runnable() {
                        @Override public void run() {
                            // LOT 4 — Waze taskId race fix: dumpsys activity recents
                            // does not always list a freshly-launched task within the
                            // initial 500 ms window (observed on field log
                            // BYD_RE_Sniffer_20260523_204155.txt: Waze launched at
                            // 20:42:25.669 but absent from recents at 20:42:26.696,
                            // 1027 ms later → resizeActiveTask aborted with
                            // taskId<=0). Retry up to 3 times with 500 ms backoff.
                            int taskId = -1;
                            for (int attempt = 1; attempt <= 3; attempt++) {
                                taskId = svc.findRunningTaskId(pkg);
                                if (taskId > 0) break;
                                if (!pkg.equals(mCurrentDashboardPkg)) return; // user switched app
                                AppLogger.d(TAG, "autoApplyInsets: taskId<=0 for " + pkg
                                        + " (attempt " + attempt + "/3) — retrying in 500 ms");
                                try { Thread.sleep(500); }
                                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                            }
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
        // v0.9.7 — apps pane is always visible (M3 split layout). We only collapse the
        // cluster preview/control widgets here. Mirror frame stays VISIBLE so its empty
        // preview card serves as the idle state.
        stopClusterMirror();
        panelClusterControl.setVisibility(View.GONE);
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
        if (clusterMirror != null) {
            clusterMirror.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        SurfaceTexture st = clusterMirror.getSurfaceTexture();
                        int w = clusterMirror.getWidth();
                        int h = clusterMirror.getHeight();
                        if (st == null || w <= 0 || h <= 0) {
                            AppLogger.w(TAG, "startClusterMirror restart: surface/size missing"
                                    + " (w=" + w + " h=" + h + ")");
                            // Last-resort: still try with whatever surface we have.
                            attemptStartMirrorWithCurrentHolder();
                            return;
                        }
                        st.setDefaultBufferSize(w, h);
                        if (mMirrorSurface != null) {
                            mMirrorSurface.release();
                            mMirrorSurface = null;
                        }
                        mMirrorSurface = new Surface(st);
                        AppLogger.i(TAG, "startClusterMirror rebind: " + w + "×" + h);
                        attemptStartMirrorWithCurrentHolder();
                    } catch (Throwable t) {
                        AppLogger.w(TAG, "startClusterMirror rebind failed: " + t.getMessage());
                    }
                }
            }, 250);
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

    // 1.2.31 — pre-allocated touch-forwarding scratch arrays. The mirror
    // touch path runs at 60-120 Hz, so a fresh int[] + 2 float[] per event was
    // ~180 array allocations/sec just for transcoding view coords → cluster
    // coords. Cap at 16 pointers (= Android InputDispatcher limit and matches
    // ClusterInputForwarder.MAX_POINTERS). The forwarder copies values into its
    // own MotionEvent so we can safely reuse these arrays on the next event.
    private static final int MAX_FWD_POINTERS = 16;
    private final int[]   mFwdPointerIds = new int[MAX_FWD_POINTERS];
    private final float[] mFwdClusterXs  = new float[MAX_FWD_POINTERS];
    private final float[] mFwdClusterYs  = new float[MAX_FWD_POINTERS];

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

        int pointerCount = Math.min(event.getPointerCount(), MAX_FWD_POINTERS);
        if (pointerCount <= 0) return;

        for (int i = 0; i < pointerCount; i++) {
            mFwdPointerIds[i] = event.getPointerId(i);
            float cx = (event.getX(i) - offsetX) / scale;
            float cy = (event.getY(i) - offsetY) / scale;
            mFwdClusterXs[i] = Math.max(0, Math.min(cx, clusterW - 1));
            mFwdClusterYs[i] = Math.max(0, Math.min(cy, clusterH - 1));
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
                        + " cluster=(" + (int)mFwdClusterXs[ai] + "," + (int)mFwdClusterYs[ai]
                        + ")/" + clusterW + "×" + clusterH);
            }
        }

        forwarder.forwardTouchFinalMulti(
                mFwdPointerIds,
                mFwdClusterXs,
                mFwdClusterYs,
                event.getActionMasked(),
                event.getActionIndex(),
                pointerCount
        );
    }

    // ---- Restaurer l'affichage BYD d'origine ----

    // v1.2.76 — showActivateClusterDialog() supprimé : tapper sur une app
    // lance maintenant activateCluster() automatiquement sans popup de confirmation
    // (cf. onSendToDashboard).

    /** v1.2.76 — null-safe wrapper: btnActivateCluster is now always null (button
     *  removed from layout), so this is a no-op. Kept to preserve the existing
     *  call graph (8 callsites across MainActivity) without per-site refactor. */
    private void setActivateBtnEnabled(boolean enabled) {
        if (btnActivateCluster != null) btnActivateCluster.setEnabled(enabled);
    }

    private void activateCluster() {
        setActivateBtnEnabled(false);
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
     * @param activity         the Activity hosting the dialog (used for context, theme, lifecycle).
     * @param notifyIfUpToDate if true, shows a toast when no update is found
     *                         (use true for manual checks, false for auto-check at launch)
     */
    public static UpdateChecker.ProgressListener makeOtaProgressListener(final android.app.Activity activity, final boolean notifyIfUpToDate) {
        final AlertDialog[] dlgHolder  = {null};
        final ProgressBar[] pbHolder   = {null};
        final TextView[]    pctHolder  = {null};

        return new UpdateChecker.ProgressListener() {
            @Override
            public void onUpdateFound(final String version, final String changelog, final String downloadUrl) {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
                layout.setPadding(pad, pad, pad, pad / 2);

                TextView tvVersion = new TextView(activity);
                tvVersion.setText(activity.getString(R.string.ota_version_label, version));
                tvVersion.setTextSize(16);
                tvVersion.setPadding(pad, 0, pad, pad / 2);
                tvVersion.setTextColor(activity.getColor(R.color.text_accent));
                layout.addView(tvVersion);

                ScrollView sv = new ScrollView(activity);
                TextView tvChangelog = new TextView(activity);
                tvChangelog.setText(renderMarkdown(changelog));
                tvChangelog.setTextSize(13);
                tvChangelog.setPadding(pad, 0, pad, pad);
                tvChangelog.setTextColor(activity.getColor(R.color.text_primary));
                sv.addView(tvChangelog);
                
                LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (activity.getResources().getDisplayMetrics().density * 250) // max height
                );
                layout.addView(sv, svParams);

                // Progress bar container (initially hidden)
                final LinearLayout progressLayout = new LinearLayout(activity);
                progressLayout.setOrientation(LinearLayout.VERTICAL);
                progressLayout.setPadding(pad, pad, pad, 0);
                progressLayout.setVisibility(View.GONE);

                ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(0);
                progressLayout.addView(pb);
                pbHolder[0] = pb;

                TextView tvPct = new TextView(activity);
                tvPct.setText(activity.getString(R.string.ota_progress_percent, 0));
                tvPct.setGravity(android.view.Gravity.CENTER);
                tvPct.setTextSize(12);
                tvPct.setTextColor(0xFF888888);
                progressLayout.addView(tvPct);
                pctHolder[0] = tvPct;

                layout.addView(progressLayout);

                dlgHolder[0] = new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.ota_dialog_title))
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton(activity.getString(R.string.ota_btn_update_now), null)
                        .setNegativeButton(activity.getString(R.string.ota_btn_later), (dialog, which) -> dialog.dismiss())
                        .create();
                
                dlgHolder[0].setOnShowListener(dialog -> {
                    Button posButton = dlgHolder[0].getButton(AlertDialog.BUTTON_POSITIVE);
                    posButton.setOnClickListener(v -> {
                        posButton.setEnabled(false);
                        dlgHolder[0].getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        sv.setVisibility(View.GONE);
                        tvVersion.setText(activity.getString(R.string.ota_downloading));
                        progressLayout.setVisibility(View.VISIBLE);
                        // Trigger download
                        UpdateChecker.startDownload(activity, downloadUrl, this);
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
                    if (pctHolder[0] != null) pctHolder[0].setText(activity.getString(R.string.ota_progress_unknown));
                } else {
                    pbHolder[0].setIndeterminate(false);
                    pbHolder[0].setProgress(percent);
                    if (pctHolder[0] != null) pctHolder[0].setText(activity.getString(R.string.ota_progress_percent, percent));
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
                    Toast.makeText(activity.getApplicationContext(),
                            activity.getString(R.string.ota_up_to_date), Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(getApplicationContext(), mAdapter.isGridMode() ? getString(R.string.toast_grid_mode_enabled) : getString(R.string.toast_list_mode_enabled), Toast.LENGTH_SHORT).show();
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
                                makeOtaProgressListener(MainActivity.this, true));
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

        final String capturedClusterPkg = mCurrentDashboardPkg;
        final String capturedSecondPkg  = mSecondDashboardPkg;

        // Eagerly clear tracked cluster state BEFORE async eviction so the
        // display-state poll does not see a stale mCurrentDashboardPkg on display 0
        // during the brief window between moveTaskToDisplay and forceStopApp.
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
        mAdapter.setCurrentPackage(null);
        updateFavoritesIndicators();

        // v1.2.81 — unified stop semantics: every cluster-occupying app (main +
        // split second) is now moved back to display 0 AND force-stopped (am
        // force-stop + task remove) before sendInfo(18) is dispatched, mirroring
        // the long-press « kill » action. This replaces the v1.2.9 workaround that
        // force-stopped the cluster pkg in place on display 1 (which left the user
        // without any feedback that the app had really returned to the tablet).
        moveSessionAppsToMainDisplay();

        AppLogger.log(TAG, "restoreBydDashboard() via ADB (TEST 10)");

        evictClusterAppsThen(buildEvictList(capturedClusterPkg, capturedSecondPkg), new Runnable() {
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
                        clearSplitState();
                        // v0.9.73 — projection just stopped → OFF state, not READY/idle.
                        setDashboardOffState();
                        setActivateBtnEnabled(true);
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

    /** v1.2.81 — Builds the deduped list of packages that occupy the cluster and must
     *  be evicted (moved to display 0 + force-stopped) before sendInfo(18). */
    private java.util.List<String> buildEvictList(String main, String second) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (main   != null && !main.isEmpty())   set.add(main);
        if (second != null && !second.isEmpty()) set.add(second);
        return new java.util.ArrayList<>(set);
    }

    /** v1.2.81 — Sequentially moves each cluster app back to display 0 then calls
     *  AdbLocalClient.forceStopApp (which does am force-stop + task remove), exactly
     *  like {@link #doKillApp(AppInfo)} does for the long-press kill button.
     *  Runs {@code onAllDone} on the main thread once every app has been processed
     *  (success or failure). */
    private void evictClusterAppsThen(final java.util.List<String> pkgs, final Runnable onAllDone) {
        if (pkgs == null || pkgs.isEmpty()) { onAllDone.run(); return; }
        if (!mServiceBound || mClusterService == null) {
            // Service not bound: fall back to bare force-stop and a short settle delay.
            for (String p : pkgs) {
                if (p != null && !p.isEmpty()) AdbLocalClient.forceStopApp(this, p, null);
            }
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(onAllDone, 800L);
            return;
        }
        evictNext(pkgs, 0, onAllDone);
    }

    private void evictNext(final java.util.List<String> pkgs, final int idx, final Runnable onAllDone) {
        if (idx >= pkgs.size()) {
            runOnUiThread(onAllDone);
            return;
        }
        final String pkg = pkgs.get(idx);
        if (pkg == null || pkg.isEmpty()) { evictNext(pkgs, idx + 1, onAllDone); return; }
        AppLogger.i(TAG, "evictClusterApp: move→display0 " + pkg);
        mClusterService.moveTaskToDisplay(pkg, 0, new com.byd.dashcast.ClusterService.LaunchCallback() {
            @Override public void onResult(boolean ok) {
                AppLogger.i(TAG, "evictClusterApp: move " + pkg + " → " + (ok ? "OK" : "KO") + " — force-stop");
                mSessionClusterPackages.remove(pkg);
                persistSessionClusterPackages();
                AdbLocalClient.forceStopApp(MainActivity.this, pkg, new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String r) { evictNext(pkgs, idx + 1, onAllDone); }
                    @Override public void onError(String e) {
                        AppLogger.w(TAG, "evictClusterApp: forceStop " + pkg + " ERR: " + e);
                        evictNext(pkgs, idx + 1, onAllDone);
                    }
                });
            }
        });
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
            AppLogger.d("DisplayCleanup", "No running task found for " + packageName + " — already gone, skipping");
            return true; // not an error: task no longer exists, nothing to move
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

    /**
     * v0.9.73 — explicit OFF state used after the user stops the projection or before
     * the cluster has been activated at all. Differs from updateDashboardStatus(null)
     * which represents the ACTIVE+IDLE case (projection on, no app yet).
     */
    private void setDashboardOffState() {
        if (tvDashboardStatus == null) return;
        tvDashboardStatus.setTextColor(Color.WHITE);
        tvDashboardStatus.setText(getString(R.string.main_cluster_status_off));
        setStatusDot(DOT_COLOR_OFF);
        if (btnShowMirror != null) btnShowMirror.setVisibility(View.GONE);
        FloatingRemoteButton.hide();
        // v1.2.85 — also hide the cluster control card (Ajuster / ↺ / Split / ⌨).
        // It is meaningless when no app is being projected and the visual
        // remnants (small icons + collapse toggle) confused users when the
        // OFF state was reached via a path that skipped showAppList().
        if (panelClusterControl != null) {
            panelClusterControl.setVisibility(View.GONE);
        }
    }

    // ============================================================
    // v0.9.74 — Favorites horizontal strip
    // ============================================================
    /**
     * Rebuilds the favorites strip from the current app list.
     * Hides the whole section when there are no favorites.
     */
    private void refreshFavoritesStrip(java.util.List<AppInfo> apps) {
        if (llFavoritesStrip == null || llFavoritesSection == null) return;
        llFavoritesStrip.removeAllViews();
        if (apps == null) {
            llFavoritesSection.setVisibility(View.GONE);
            return;
        }
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
        int added = 0;
        for (final AppInfo a : apps) {
            if (!a.isFavorite) continue;
            View tile = inflater.inflate(R.layout.item_favorite_strip, llFavoritesStrip, false);
            ImageView iv  = tile.findViewById(R.id.iv_fav_icon);
            TextView tv   = tile.findViewById(R.id.tv_fav_name);
            iv.setImageDrawable(a.icon);
            tv.setText(a.appName);
            tile.setTag(a.packageName);
            tile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onSendToDashboard(a); }
            });
            tile.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { onShowActions(a); return true; }
            });
            llFavoritesStrip.addView(tile);
            added++;
        }
        llFavoritesSection.setVisibility(added > 0 ? View.VISIBLE : View.GONE);
        updateFavoritesIndicators();
    }

    /**
     * v0.9.80 — Sync the "app launched" green bar on each favorite tile with the
     * adapter's current/main package state. Call this whenever those change.
     */
    private void updateFavoritesIndicators() {
        if (llFavoritesStrip == null || mAdapter == null) return;
        String curPkg  = mAdapter.getCurrentPackage();
        String mainPkg = mAdapter.getMainPackage();
        for (int i = 0; i < llFavoritesStrip.getChildCount(); i++) {
            View tile = llFavoritesStrip.getChildAt(i);
            Object tag = tile.getTag();
            if (!(tag instanceof String)) continue;
            String pkg = (String) tag;
            View ind = tile.findViewById(R.id.view_fav_active_indicator);
            if (ind == null) continue;
            boolean active = pkg.equals(curPkg) || pkg.equals(mainPkg);
            ind.setVisibility(active ? View.VISIBLE : View.GONE);
        }
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
        if (mIsFullscreenMirror) return;
        if (cardClusterPreview == null) return;
        mIsFullscreenMirror = true;

        // v0.9.76 — tear down the mirror BEFORE resizing the TextureView. Otherwise
        // attemptStartMirrorWithCurrentHolder() short-circuits on isMirrorActive() and
        // the VirtualDisplay keeps writing to the stale Surface that was released by
        // onSurfaceTextureSizeChanged → black card with no app content.
        stopClusterMirror();

        if (vNavRail != null)         vNavRail.setVisibility(View.GONE);
        if (vTopBar != null)          vTopBar.setVisibility(View.GONE);
        if (llAppListSection != null) llAppListSection.setVisibility(View.GONE);
        if (cardHeroStatus != null)   cardHeroStatus.setVisibility(View.GONE);
        if (tvPreviewSection != null) tvPreviewSection.setVisibility(View.GONE);
        if (gridMainActions != null)  gridMainActions.setVisibility(View.GONE);

        // Switch to weight-based layout so the card grows and the cluster control panel
        // (with the "Ajuster" button) keeps its natural wrap_content height at the bottom.
        if (llRightPaneContent != null && svRightPane != null) {
            ViewGroup.LayoutParams llLp = llRightPaneContent.getLayoutParams();
            mSavedInnerLLHeight = llLp.height;
            int h = svRightPane.getHeight();
            if (h <= 0) h = getResources().getDisplayMetrics().heightPixels;
            llLp.height = h;
            llRightPaneContent.setLayoutParams(llLp);
        }
        LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) cardClusterPreview.getLayoutParams();
        mSavedPreviewHeightPx = clp.height;
        mSavedPreviewWeight   = clp.weight;
        clp.height = 0;
        clp.weight = 1f;
        cardClusterPreview.setLayoutParams(clp);

        if (btnExitFullscreen != null) btnExitFullscreen.setVisibility(View.VISIBLE);

        // v1.2.85 — cluster control card is fullscreen-only now: reveal it here.
        if (panelClusterControl != null) panelClusterControl.setVisibility(View.VISIBLE);

        // v0.9.79 — reparent panelClusterControl from the inner LinearLayout to the root
        // FrameLayout (aligned bottom) so that expanding the "Ajuster" sub-panel doesn't
        // shrink the card (and therefore the orange inset overlay) underneath it.
        if (panelClusterControl != null && vRootOverlay != null
                && panelClusterControl.getParent() instanceof ViewGroup
                && panelClusterControl.getParent() != vRootOverlay) {
            mPanelOriginalParent = (ViewGroup) panelClusterControl.getParent();
            mPanelOriginalIndex  = mPanelOriginalParent.indexOfChild(panelClusterControl);
            mPanelOriginalLp     = panelClusterControl.getLayoutParams();
            mPanelOriginalParent.removeView(panelClusterControl);
            android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            flp.gravity = android.view.Gravity.BOTTOM;
            vRootOverlay.addView(panelClusterControl, flp);
        }

        // Immersive: hide system bars to maximise usable area.
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable t) {
            AppLogger.w(TAG, "immersive setSystemUiVisibility failed: " + t.getMessage());
        }

        // v0.9.77 — robust restart at the new TextureView size.
        // Layout settles via postDelayed (200ms) so getWidth/getHeight reflect fullscreen.
        // We then explicitly recreate mMirrorSurface from the (possibly resized)
        // SurfaceTexture buffer and call attemptStart — because onSurfaceTextureSizeChanged
        // may not fire when only the View bounds change without buffer change.
        cardClusterPreview.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    SurfaceTexture st = clusterMirror != null ? clusterMirror.getSurfaceTexture() : null;
                    int w = clusterMirror != null ? clusterMirror.getWidth() : 0;
                    int h = clusterMirror != null ? clusterMirror.getHeight() : 0;
                    if (st == null || w <= 0 || h <= 0) {
                        AppLogger.w(TAG, "fullscreen restart: surface or size missing (w=" + w + " h=" + h + ")");
                        return;
                    }
                    st.setDefaultBufferSize(w, h);
                    if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
                    mMirrorSurface = new Surface(st);
                    AppLogger.i(TAG, "fullscreen restart: new surface " + w + "×" + h);
                    attemptStartMirrorWithCurrentHolder();
                } catch (Throwable t) {
                    AppLogger.w(TAG, "fullscreen mirror restart failed: " + t.getMessage());
                }
            }
        }, 250);
        AppLogger.i(TAG, "enterFullscreenMirror");
    }

    /** Reverses enterFullscreenMirror(): restores all hidden views and shrinks the card back. */
    private void exitFullscreenMirror() {
        if (!mIsFullscreenMirror) return;
        mIsFullscreenMirror = false;

        // v0.9.76 — same as enter: tear down before resize so the mirror is recreated
        // at the original 200dp preview size by onSurfaceTextureSizeChanged.
        stopClusterMirror();

        if (vNavRail != null)         vNavRail.setVisibility(View.VISIBLE);
        if (vTopBar != null)          vTopBar.setVisibility(View.VISIBLE);
        if (llAppListSection != null) llAppListSection.setVisibility(View.VISIBLE);
        if (cardHeroStatus != null)   cardHeroStatus.setVisibility(View.VISIBLE);
        if (tvPreviewSection != null) tvPreviewSection.setVisibility(View.VISIBLE);
        if (gridMainActions != null)  gridMainActions.setVisibility(View.VISIBLE);

        if (llRightPaneContent != null) {
            ViewGroup.LayoutParams llLp = llRightPaneContent.getLayoutParams();
            llLp.height = mSavedInnerLLHeight;
            llRightPaneContent.setLayoutParams(llLp);
        }
        if (cardClusterPreview != null) {
            LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) cardClusterPreview.getLayoutParams();
            clp.height = (mSavedPreviewHeightPx > 0) ? mSavedPreviewHeightPx
                    : (int) (320 * getResources().getDisplayMetrics().density);
            clp.weight = mSavedPreviewWeight;
            cardClusterPreview.setLayoutParams(clp);
        }
        if (btnExitFullscreen != null) btnExitFullscreen.setVisibility(View.GONE);

        // v0.9.79 — restore panelClusterControl to its original parent (inner right-pane LL).
        if (panelClusterControl != null && mPanelOriginalParent != null
                && panelClusterControl.getParent() == vRootOverlay) {
            vRootOverlay.removeView(panelClusterControl);
            int idx = (mPanelOriginalIndex >= 0
                    && mPanelOriginalIndex <= mPanelOriginalParent.getChildCount())
                    ? mPanelOriginalIndex : mPanelOriginalParent.getChildCount();
            if (mPanelOriginalLp != null) {
                mPanelOriginalParent.addView(panelClusterControl, idx, mPanelOriginalLp);
            } else {
                mPanelOriginalParent.addView(panelClusterControl, idx);
            }
            mPanelOriginalParent = null;
            mPanelOriginalIndex  = -1;
            mPanelOriginalLp     = null;
        }

        // v1.2.85 — hide the cluster control card on exit (fullscreen-only).
        if (panelClusterControl != null) panelClusterControl.setVisibility(View.GONE);

        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } catch (Throwable t) { /* ignore */ }

        // Same robust delayed restart as enterFullscreenMirror, but at the preview size.
        if (cardClusterPreview != null) {
            cardClusterPreview.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        SurfaceTexture st = clusterMirror != null ? clusterMirror.getSurfaceTexture() : null;
                        int w = clusterMirror != null ? clusterMirror.getWidth() : 0;
                        int h = clusterMirror != null ? clusterMirror.getHeight() : 0;
                        if (st == null || w <= 0 || h <= 0) return;
                        st.setDefaultBufferSize(w, h);
                        if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
                        mMirrorSurface = new Surface(st);
                        attemptStartMirrorWithCurrentHolder();
                    } catch (Throwable t) { /* ignore */ }
                }
            }, 250);
        }
        AppLogger.i(TAG, "exitFullscreenMirror");
    }

    @Override
    public void onBackPressed() {
        if (mIsFullscreenMirror) { exitFullscreenMirror(); return; }
        super.onBackPressed();
    }

    /** Sets the status dot to a given ARGB color. Reuses the shared GradientDrawable to avoid allocations. */
    private void setStatusDot(int color) {
        if (mStatusDotDrawable == null) return;
        mStatusDotDrawable.setColor(color);
    }

    /** Toggles list ↔ grid mode, updates the toggle button icon and adapter layout. */
    private void toggleViewMode() {
        SharedPreferences p2 = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean nv = !mAdapter.isGridMode();
        p2.edit().putBoolean(PREF_GRID_MODE, nv).apply();
        mAdapter.setGridMode(nv);
        if (nv) {
            // v1.2.45 — honour compact-mode spanCount when re-creating the grid.
            boolean compact = p2.getBoolean(SettingsActivity.PREF_COMPACT_APPS_PANEL, false);
            rvApps.setLayoutManager(new GridLayoutManager(this, compact ? 2 : 5));
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
                setActivateBtnEnabled(true);
                setStatusDot(DOT_COLOR_OFF);
                tvDashboardStatus.setText(getString(R.string.status_disconnected));
                Toast.makeText(getApplicationContext(),
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
        trackUsageStop(mCurrentDashboardPkg);

        final String capturedClusterPkg = mCurrentDashboardPkg;
        final String capturedSecondPkg  = mSecondDashboardPkg;

        // Eagerly clear tracked cluster state (same rationale as restoreBydDashboard).
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
        mAdapter.setCurrentPackage(null);
        updateFavoritesIndicators();

        // v1.2.81 — see restoreBydDashboard for the unified eviction rationale.
        moveSessionAppsToMainDisplay();
        AppLogger.log(TAG, "originCluster() cmd=" + getClusterTypeCmd());

        evictClusterAppsThen(buildEvictList(capturedClusterPkg, capturedSecondPkg), new Runnable() {
            @Override public void run() {
                AdbLocalClient.restoreOriginCluster(MainActivity.this, getClusterTypeCmd(), null, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mServiceBound && mClusterService != null) {
                            mClusterService.stopProjectionNoAdb();
                        }
                        // Cluster state already cleared eagerly above.
                        clearSplitState();
                        updateDashboardStatus(null);
                        setActivateBtnEnabled(true);
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

    // ---- Split layout -------------------------------------------------------

    /** Displays the cluster layout menu (full screen / left 50% / right 50%). */
    private void showSplitMenu(View anchor) {
        if (!mServiceBound || mClusterService == null || mCurrentDashboardPkg == null) {
            AppLogger.w(TAG, "showSplitMenu ignored — serviceBound=" + mServiceBound
                    + " clusterService=" + (mClusterService != null)
                    + " currentPkg=" + mCurrentDashboardPkg);
            Toast.makeText(getApplicationContext(), getString(R.string.toast_no_app_cluster), Toast.LENGTH_SHORT).show();
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
                                    Toast.makeText(getApplicationContext(),
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
     */
    private void loadAppsAsync() {
        java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "load-apps");
            t.setDaemon(true);
            return t;
        });
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
                                    appInfo.shortcuts.add(new AppShortcut(shortcut.getId(), shortcut.getShortLabel().toString(), shortcutIcon));
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Ignored: app has no shortcuts or no permission
                    }
                    
                    apps.add(appInfo);
                }

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                Set<String> favs = prefs.getStringSet(PREF_FAVORITES, new HashSet<>());
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
                // v0.9.75 — favorites are shown in the dedicated strip above the list,
                // hide them from the main grid to avoid duplication.
                final List<AppInfo> nonFavs = new java.util.ArrayList<>(apps.size());
                for (AppInfo a : result) {
                    if (!a.isFavorite) nonFavs.add(a);
                }
                runOnUiThread(() -> {
                    // 1.2.30 — if the activity tore down while the loader thread was
                    // still walking PackageManager, do not touch the adapter / strip.
                    if (isFinishing() || isDestroyed()) return;
                    mAdapter.setApps(nonFavs);
                    refreshFavoritesStrip(result);
                    // One-shot tip: show once, on first ever launch
                    SharedPreferences _p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    if (!_p.getBoolean(PREF_FIRST_LAUNCH_TIP, false)) {
                        _p.edit().putBoolean(PREF_FIRST_LAUNCH_TIP, true).apply();
                        mScreenshotHandler.postDelayed(() ->
                                Toast.makeText(getApplicationContext(),
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
        btnFilterAll.getBackground().setTint(activeCategory == 0 ? FILTER_TINT_ACTIVE : FILTER_TINT_INACTIVE);
        btnFilterNav.getBackground().setTint(activeCategory == AppInfo.CATEGORY_NAVIGATION ? FILTER_TINT_ACTIVE : FILTER_TINT_INACTIVE);
        btnFilterMedia.getBackground().setTint(activeCategory == AppInfo.CATEGORY_MEDIA ? FILTER_TINT_ACTIVE : FILTER_TINT_INACTIVE);
    }

    // ── Quick-switch history ────────────────────────────────────────────────

    private static final String PREF_RECENT_APPS = SettingsActivity.PREF_RECENT_APPS;
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
            Toast.makeText(getApplicationContext(), getString(R.string.usage_empty), Toast.LENGTH_SHORT).show();
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
                    // Re-read at click time: new usage entries may have been added since the dialog opened.
                    for (String key : prefs.getAll().keySet()) {
                        if (key.startsWith("usage_ms_")) editor.remove(key);
                    }
                    editor.apply();
                    Toast.makeText(getApplicationContext(), getString(R.string.toast_usage_stats_reset), Toast.LENGTH_SHORT).show();
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

    // ─────────────────────────────────────────────────────────────────────────
    // v1.2.9 — IME a11y onboarding banner (DL5 only)
    // ─────────────────────────────────────────────────────────────────────────

    /** Wire the banner buttons once. Visibility is decided by {@link #refreshImeA11yBanner()}. */
    private void setupImeA11yBanner() {
        try {
            final View card = findViewById(R.id.card_ime_a11y_banner);
            if (card == null) return;

            View btnEnable  = findViewById(R.id.btn_ime_banner_enable);
            View btnLater   = findViewById(R.id.btn_ime_banner_later);
            View btnDismiss = findViewById(R.id.btn_ime_banner_dismiss);

            if (btnEnable != null) {
                btnEnable.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        enableImeA11yServiceOneClick(card, btnEnable);
                    }
                });
            }
            if (btnLater != null) {
                btnLater.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // Hide for this session only — banner reappears on next launch
                        // if the user still has not enabled the service.
                        card.setVisibility(View.GONE);
                    }
                });
            }
            if (btnDismiss != null) {
                btnDismiss.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit()
                                    .putBoolean(PREF_IME_BANNER_DISMISSED, true)
                                    .apply();
                        } catch (Throwable ignored) { }
                        card.setVisibility(View.GONE);
                    }
                });
            }

            refreshImeA11yBanner();
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "setupImeA11yBanner failed", t);
        }
    }

    /**
     * Recomputes whether the banner should be visible.
     * Banner shows iff (DL5) AND (a11y service NOT enabled) AND (user did not
     * permanently dismiss it). Safe to call repeatedly.
     */
    private void refreshImeA11yBanner() {
        final View card = findViewById(R.id.card_ime_a11y_banner);
        if (card == null) return;

        boolean shouldShow = false;
        try {
            boolean isDl5 = com.byd.dashcast.platform.Platform.get().isDiLink5(this);
            if (isDl5) {
                boolean dismissed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(PREF_IME_BANNER_DISMISSED, false);
                boolean enabled = com.byd.dashcast.ime.ClusterImeWatcherService.isEnabled(this);
                shouldShow = !dismissed && !enabled;
                if (enabled && card.getVisibility() == View.VISIBLE) {
                    // User returned from Settings after enabling — confirm + hide.
                    try {
                        android.widget.Toast.makeText(getApplicationContext(),
                                R.string.ime_banner_toast_enabled,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "refreshImeA11yBanner inner failed", t);
            shouldShow = false;
        }
        card.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    /**
     * v1.2.10 — One-click activation of the IME accessibility service via the
     * proxy daemon's shell (uid=2000 owns the same uid namespace and the `settings`
     * binary writes to {@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}
     * directly — no user trip to Settings).
     *
     * Falls back to opening the system Accessibility Settings screen if the
     * shell route fails (no daemon, ROM blocks `settings put`).
     */
    private void enableImeA11yServiceOneClick(final View card, final View btnEnable) {
        if (btnEnable != null) btnEnable.setEnabled(false);
        try {
            final String comp = "com.byd.dashcast/com.byd.dashcast.ime.ClusterImeWatcherService";
            // Single-line POSIX sh: read current list, append if missing, write back,
            // then flip the master accessibility_enabled flag to 1. Final `settings get`
            // is used as a verification echo (we still re-read Settings.Secure in
            // Android-land below before trusting it).
            final String cmd =
                "COMP='" + comp + "'; "
              + "CURRENT=$(settings get secure enabled_accessibility_services 2>/dev/null); "
              + "if [ \"$CURRENT\" = \"null\" ] || [ -z \"$CURRENT\" ]; then "
              +   "NEW=\"$COMP\"; "
              + "elif echo \"$CURRENT\" | grep -q \"$COMP\"; then "
              +   "NEW=\"$CURRENT\"; "
              + "else "
              +   "NEW=\"$CURRENT:$COMP\"; "
              + "fi; "
              + "settings put secure enabled_accessibility_services \"$NEW\"; "
              + "settings put secure accessibility_enabled 1; "
              + "echo OUT=$(settings get secure enabled_accessibility_services)";

            ShellGateway.execShellWithResult(this, cmd, new AdbLocalClient.Callback() {
                @Override public void onSuccess(final String report) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // Trust the OS, not the shell echo: re-read from
                            // Settings.Secure via the same helper used by the banner.
                            boolean ok = com.byd.dashcast.ime.ClusterImeWatcherService
                                    .isEnabled(MainActivity.this);
                            if (ok) {
                                AppLogger.i("MainActivity",
                                        "IME a11y enabled via shell (one-click) ✓");
                                try {
                                    android.widget.Toast.makeText(getApplicationContext(),
                                            R.string.ime_banner_toast_enabled,
                                            android.widget.Toast.LENGTH_SHORT).show();
                                } catch (Throwable ignored) { }
                                if (card != null) card.setVisibility(View.GONE);
                                if (btnEnable != null) btnEnable.setEnabled(true);
                            } else {
                                AppLogger.w("MainActivity",
                                        "shell succeeded but a11y still not enabled, falling back to Settings UI. report=" + report);
                                openA11ySettingsFallback();
                                if (btnEnable != null) btnEnable.setEnabled(true);
                            }
                        }
                    });
                }
                @Override public void onError(final String error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            AppLogger.w("MainActivity",
                                    "one-click a11y enable shell failed: " + error
                                  + " — falling back to Settings UI");
                            openA11ySettingsFallback();
                            if (btnEnable != null) btnEnable.setEnabled(true);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "enableImeA11yServiceOneClick threw", t);
            openA11ySettingsFallback();
            if (btnEnable != null) btnEnable.setEnabled(true);
        }
    }

    /** Best-effort fallback: open the system Accessibility Settings screen. */
    private void openA11ySettingsFallback() {
        // 1) Standard AOSP intent.
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable t) {
            AppLogger.w("MainActivity", "ACTION_ACCESSIBILITY_SETTINGS unavailable: "
                    + t.getMessage());
        }
        // 2) Direct component (BYD ROM may not advertise the standard action).
        try {
            android.content.Intent i = new android.content.Intent();
            i.setComponent(new android.content.ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings$AccessibilitySettingsActivity"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable t) {
            AppLogger.w("MainActivity", "direct AccessibilitySettingsActivity unavailable: "
                    + t.getMessage());
        }
        // 3) Generic Settings as a last resort.
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            AppLogger.e("MainActivity", "no Settings activity reachable on this ROM", t);
            try {
                android.widget.Toast.makeText(getApplicationContext(),
                        R.string.ime_banner_toast_cannot_open_settings,
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) { }
        }
    }

}


