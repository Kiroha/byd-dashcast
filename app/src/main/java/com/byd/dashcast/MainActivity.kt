package com.byd.dashcast

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.byd.dashcast.app.AppStartupTasks
import com.byd.dashcast.app.BootDisplayCleanup
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.cluster.ClusterSessionTracker
import com.byd.dashcast.cluster.display.DashboardLauncher
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager
import com.byd.dashcast.data.apps.AppRepository
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.fission.FissionOrchestrator
import com.byd.dashcast.fission.LayoutPrefs
import com.byd.dashcast.ime.ClusterImeWatcherService
import com.byd.dashcast.ime.KeyboardBridgeActivity
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.infrastructure.task.TaskLocation
import com.byd.dashcast.model.AppInfo
import com.byd.dashcast.model.AppShortcut
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.DaemonBinderResolver
import com.byd.dashcast.proxy.DaemonBroadcastRegistrar
import com.byd.dashcast.proxy.DaemonConfig
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import com.byd.dashcast.system.FloatingRemoteButton
import com.byd.dashcast.ui.AppListAdapter
import com.byd.dashcast.ui.InsetOverlayView
import com.byd.dashcast.ui.main.ActivateTimeoutManager
import com.byd.dashcast.ui.main.AppActionSheet
import com.byd.dashcast.ui.main.AppListCoordinator
import com.byd.dashcast.ui.main.AppsPanelLayoutPolicy
import com.byd.dashcast.ui.main.ClusterControlCoordinator
import com.byd.dashcast.ui.main.DisplayStatePollCoordinator
import com.byd.dashcast.ui.main.DashboardSelectionTracker
import com.byd.dashcast.ui.main.FissionCoordinator
import com.byd.dashcast.ui.main.FullscreenMirrorCoordinator
import com.byd.dashcast.ui.main.InsetAutoApplicator
import com.byd.dashcast.ui.main.MirrorCoordinator
import com.byd.dashcast.ui.main.NavigationCoordinator
import com.byd.dashcast.ui.main.OverflowMenuHelper
import com.byd.dashcast.ui.main.PermissionBannerCoordinator
import com.byd.dashcast.ui.main.SplitController
import com.byd.dashcast.ui.main.UsageTracker
import com.byd.dashcast.ui.nav.NavRailLayouts
import com.byd.dashcast.ui.nav.NavRailSetup
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.update.OtaProgressUi
import com.byd.dashcast.update.UpdateChecker
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.LocaleHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * MainActivity — 15-inch main screen.
 *
 * Displays the list of installed apps. The user selects an app and sends it to the
 * small screen behind the steering wheel. "Restore BYD" brings back the cluster widget.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n") // cluster touches forwarded; debug labels intentional
@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(),
    ClusterService.Listener,
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

    // Cluster service
    private var mClusterService: ClusterService? = null
    private var mServiceBound = false
    private var mBindRequested = false // true as soon as a bindService is in progress
    private lateinit var mDashboardLauncher: DashboardLauncher // local ref updated after bind

    /** Single source of truth for app loading, caching, and favorite/auto-launch mutations. */
    private val mAppRepo = AppRepository()
    private var mPendingAutoLaunchPkg: String? = null
    private var mPendingAppAfterActivation: AppInfo? = null
    private var mPendingShortcutAfterActivation: Pair<AppInfo, AppShortcut>? = null
    private var mMissingAutoLayoutToastShown = false
    private val mDashboardSelectionTracker = DashboardSelectionTracker()

    private val mServiceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mClusterService = (binder as ClusterService.LocalBinder).getService()
            mServiceBound = true
            mDashboardLauncher = mClusterService!!.getLauncher()
            mClusterService!!.setListener(this@MainActivity)
            AppLogger.log(TAG, "Bind ClusterService OK — displayId=" + mClusterService!!.getDisplayId())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            restorePendingBootAdoption()
            mServiceBound = false
            mBindRequested = false // allow a new bindService if needed
            mClusterService = null
            mDashboardLauncher.setDashboardDisplayId(-1)
            mUsageTracker.trackStop(mCurrentDashboardPkg)
            mCurrentDashboardApp = null
            mCurrentDashboardPkg = null
            mMainDisplayPkg = null
            mSplitController?.clearSplitState()
            mAppListCoordinator.setCurrentPackage(null)
            mAppListCoordinator.setMainPackage(null)
            AppLogger.log(TAG, "ClusterService disconnected")
        }
    }

    private var mCurrentDashboardApp: String? = null // readable name (displayed in the status bar)
    private var mCurrentDashboardPkg: String? = null // package name (for am force-stop)
    private var mMainDisplayPkg: String? = null // package sent to the main display ("→ Cluster")

    /** True if the current activation was triggered by the user (not Activity restore). */
    private var mWasManualActivation = false
    private lateinit var mTimeoutManager: ActivateTimeoutManager
    private lateinit var mInsetApplicator: InsetAutoApplicator

    // UI
    private lateinit var llAppListSection: View // wrapper for title header + search bar
    private lateinit var btnRestoreCluster: Button
    private lateinit var ivNavLogo: ImageView // v0.9.81: long-press = overflow menu
    private lateinit var btnShowMirror: Button
    private lateinit var btnLaunchLayoutApps: MaterialButton
    // Cluster-layout section is collapsed by default so the live preview keeps its
    // full height; the header row expands/collapses it on demand.
    private lateinit var mLayoutSectionBody: View
    private lateinit var mLayoutSectionChevron: ImageView
    private var mLayoutSectionExpanded = false
    private lateinit var btnExitFullscreen: FloatingActionButton
    private lateinit var vRootOverlay: FrameLayout
    private lateinit var llCategoryFilters: View

    private lateinit var mUsageTracker: UsageTracker
    private lateinit var mSessionTracker: ClusterSessionTracker
    private var mInsetOverlay: InsetOverlayView? = null
    private lateinit var frameMirror: FrameLayout
    private lateinit var clusterMirror: TextureView
    private lateinit var mLayoutMirrorSwitcher: View
    private lateinit var mLayoutMirrorSelected: TextView
    private lateinit var mLayoutMirrorPrev: ImageButton
    private lateinit var mLayoutMirrorNext: ImageButton

    // ── Coordinators / Controllers ────────────────────────────────────────────
    private lateinit var mAppListCoordinator: AppListCoordinator
    private var mClusterControlCoordinator: ClusterControlCoordinator? = null
    private var mNavCoordinator: NavigationCoordinator? = null
    private var mMirrorCoordinator: MirrorCoordinator? = null
    private var mFullscreenCoordinator: FullscreenMirrorCoordinator? = null
    private var mSplitController: SplitController? = null
    private var mPermissionBannerCoordinator: PermissionBannerCoordinator? = null
    private var mFissionCoordinator: FissionCoordinator? = null
    private var mStatePollCoordinator: DisplayStatePollCoordinator? = null

    // Grace period check for state poll
    private var mLastLaunchTime = 0L

    private val mScreenshotHandler = Handler(Looper.getMainLooper())

    // SurfaceDaemon — Binder received via broadcast ACTION_DAEMON_READY
    private var mDaemonBinder: IBinder? = null
    private val mDaemonReadyReceiver: BroadcastReceiver =
        DaemonBinderResolver.createActionReceiver { binder ->
            mDaemonBinder = binder
            val svc = if (mServiceBound) mClusterService else null
            svc?.getInputForwarder()?.setDaemonBinder(binder)
            mMirrorCoordinator?.onDaemonBinderAvailable(binder)
        }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppLogger.lifecycle(javaClass.simpleName, "onCreate")

        // Safety-net: if projection auto-start is disabled, move any leftover cluster apps
        // back to Display 0. Auto-launch pending package consumed in the cluster-connect callback.
        val bootAutoStartEnabled = ClusterPrefs.isBootAutoStartEnabled(this)
        var sessionResumePending = false
        mPendingAutoLaunchPkg = ClusterPrefs.getAutoLaunchPkg(this)
        // Session-resume: re-activate the last cluster package if a previous projection was
        // interrupted. Read the session set HERE — before the cleanup thread clears it.
        if (mPendingAutoLaunchPkg == null && !bootAutoStartEnabled) {
            val prevSession = ClusterPrefs.getSessionClusterPkgs(this)
            if (prevSession.isNotEmpty()) {
            sessionResumePending = true
                val lastPkg = ClusterPrefs.getLastClusterPkg(this)
                mPendingAutoLaunchPkg = if (lastPkg != null && prevSession.contains(lastPkg))
                    lastPkg
                else
                    prevSession.iterator().next()
                AppLogger.i(
                    TAG, "session-resume: pending auto-launch « " + mPendingAutoLaunchPkg +
                        " » from interrupted session (" + prevSession.size + " app(s))"
                )
            }
        }
        // When the Layouts auto-start owns startup, it activates the cluster projection AND
        // launches every bound app itself. The single-app auto-launch must NOT also fire: it
        // would create a classic ClusterService projection first, and the layout's ensureDaemon()
        // then sees an active projection → aborts the whole layout with "Daemon unavailable" and
        // nothing launches (byd_log 20260705_194004). Suppress it here so the two auto-starts
        // don't race; the layout is the single source of truth for startup launching.
        if (isLayoutAutoStartRequested() && mPendingAutoLaunchPkg != null) {
            AppLogger.i(TAG, "Layout auto-start configured — suppressing single-app auto-launch of « "
                    + mPendingAutoLaunchPkg + " » (the layout owns startup launching)")
        }
        if (BootDisplayCleanup.shouldRunAtStartup(bootAutoStartEnabled, sessionResumePending)) {
            // Off-load the (binder-reflection) cleanup to a named daemon thread.
            val appCtx = applicationContext
            val cleanupThread = Thread({ BootDisplayCleanup.cleanup(appCtx) }, "boot-cleanup-fallback")
            cleanupThread.isDaemon = true
            cleanupThread.start()
        } else if (bootAutoStartEnabled) {
            // Auto-start enabled: clear the persisted set (apps managed normally).
            ClusterPrefs.clearSessionClusterPkgs(this)
        } else {
            AppLogger.i(TAG, "Session resume owns startup — skipping display cleanup")
        }

        // Unlock hidden Android APIs (SurfaceControl, etc.) before any startMirror call.
        ClusterMirrorManager.unlockHiddenApis()

        // v1.2.55-beta — log pruning + orphan-sniffer cleanup (run once per process).
        val killOrphanSniffer = !sOrphanSnifferKillDone
        if (killOrphanSniffer) sOrphanSnifferKillDone = true
        AppStartupTasks.run(applicationContext, killOrphanSniffer)

        // Receiver to retrieve the SurfaceDaemon Binder (uid=2000)
        DaemonBroadcastRegistrar.register(
            this, mDaemonReadyReceiver, IntentFilter(SurfaceDaemon.ACTION_DAEMON_READY)
        )

        // Floating mirror button — started once, visibility controlled by show()/hide().
        // Deferred to post-first-frame: FloatingRemoteButton.onStartCommand inflates a
        // WindowManager overlay (addView) on the main looper and the badge starts hidden
        // (revealed later through the sShouldBeVisible latch, which survives a not-yet-
        // started service), so that overlay work must not compete with the launcher's
        // first traversal. Posting on the decor view's run queue runs it after the first
        // layout pass. The application context keeps the service independent of this
        // Activity's lifecycle, and onStartCommand is idempotent (early-returns once
        // mFloatView is created), so the deferral cannot double-start it.
        val floatingButtonCtx = applicationContext
        window.decorView.post {
            floatingButtonCtx.startService(Intent(floatingButtonCtx, FloatingRemoteButton::class.java))
        }

        // Handle a tap on the floating button when the Activity is already alive.
        handleShowMirrorIntent(intent)

        btnRestoreCluster = findViewById(R.id.btn_restore_cluster)
        ivNavLogo = findViewById(R.id.iv_nav_logo)
        btnShowMirror = findViewById(R.id.btn_show_mirror)
        btnLaunchLayoutApps = findViewById(R.id.btn_launch_layout_apps)
        btnLaunchLayoutApps.setOnClickListener { FissionOrchestrator.launchFavoriteLayoutApps(this) }
        llAppListSection = findViewById(R.id.ll_app_list_section)

        btnExitFullscreen = findViewById(R.id.btn_exit_fullscreen)
        vRootOverlay = findViewById(R.id.root_overlay)
        llCategoryFilters = findViewById(R.id.ll_category_filters)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val showFilters = prefs.getBoolean(SettingsActivity.PREF_SHOW_CATEGORY_FILTERS,
            SettingsActivity.DEFAULT_SHOW_CATEGORY_FILTERS)
        llCategoryFilters.visibility = if (showFilters) View.VISIBLE else View.GONE

        // Button "Restore cluster" — quick stop or full origin-restore per PREF_QUICK_STOP.
        btnRestoreCluster.setOnClickListener {
            val quick = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_QUICK_STOP, false)
            if (quick) restoreBydDashboard() else originCluster()
        }

        // Button 📺 Mirror — v0.9.74: open the pseudo-fullscreen tactile mirror.
        btnShowMirror.setOnClickListener {
            showMirrorView()
            attemptStartMirrorWithCurrentHolder()
            enterFullscreenMirror()
            AppLogger.d(TAG, "btn_show_mirror → enterFullscreenMirror for $mCurrentDashboardApp")
        }

        // Static nav rail entries; Hotspot wired by NavigationCoordinator, Layouts by NavRailLayouts.
        NavRailSetup.wire(this)
        NavRailLayouts.apply(this, R.id.nav_layouts, false)

        // Start ClusterService now (startForegroundService in onStart)
        mDashboardLauncher = DashboardLauncher(this) // temporary until bind

        frameMirror = findViewById(R.id.frame_cluster_mirror)
        clusterMirror = findViewById(R.id.cluster_mirror)
        mLayoutMirrorSwitcher = findViewById(R.id.layout_mirror_switcher)
        mLayoutMirrorSelected = findViewById(R.id.tv_layout_mirror_selected)
        mLayoutMirrorPrev = findViewById(R.id.btn_layout_mirror_prev)
        mLayoutMirrorNext = findViewById(R.id.btn_layout_mirror_next)
        mInsetOverlay = findViewById(R.id.inset_overlay)

        mLayoutMirrorPrev.setOnClickListener { stepLayoutMirror(-1) }
        mLayoutMirrorNext.setOnClickListener { stepLayoutMirror(1) }

        // Read persisted mMainDisplayPkg early so it is available for display-connected callbacks.
        mMainDisplayPkg = ClusterPrefs.getMainPkg(this)

        // TextureView optimizations.
        // Opaque avoids alpha-blending overhead. We deliberately do NOT set
        // LAYER_TYPE_HARDWARE here: a TextureView already renders its stream into a
        // GPU texture, so forcing an extra hardware layer adds a second FBO + a GPU
        // blit per frame (60 fps) — that double-composition is what pushed frames past
        // the 16 ms vsync budget (high janky-frame ratio in gfxinfo).
        clusterMirror.isOpaque = true

        // Auto-trigger the mirror once the TextureView is measured (avoid cold-start black screens).
        clusterMirror.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0) {
                AppLogger.d(TAG, "clusterMirror layed out: " + w + "x" + h + " -> invoking attemptStartMirror")
                attemptStartMirrorWithCurrentHolder()
            }
        }

        // Hide → return to list
        val btnControlHide = findViewById<Button>(R.id.btn_control_hide)
        btnControlHide.setOnClickListener { showAppList() }

        // v1.2.8 — Keyboard bridge (DL5 only): relays IME from head unit to cluster window.
        val btnKeyboardBridge = findViewById<Button>(R.id.btn_keyboard_bridge)
        if (btnKeyboardBridge != null) {
            var isDl5 = false
            try {
                isDl5 = Platform.get().isDiLink5(this)
            } catch (t: Throwable) {
                AppLogger.e("MainActivity", "isDiLink5 check failed (keyboard btn)", t)
            }
            btnKeyboardBridge.visibility = if (isDl5) View.VISIBLE else View.GONE
            btnKeyboardBridge.setOnClickListener {
                try {
                    if (!ClusterImeWatcherService.prepareAndLaunchBridgeManually()) {
                        startActivity(Intent(this, KeyboardBridgeActivity::class.java))
                    }
                } catch (e: Exception) {
                    AppLogger.e("MainActivity", "KeyboardBridge launch failed", e)
                }
            }
        }

        mSessionTracker = ClusterSessionTracker(this)

        // Wire coordinator layer (status dot, mirror lifecycle, fullscreen state machine).
        setupCoordinators()
        applyCompactAppsPanelMode()

        // Restore main-display pkg into the adapter now that mAppListCoordinator exists.
        if (mMainDisplayPkg != null) {
            mAppListCoordinator.setMainPackage(mMainDisplayPkg)
        }

        // Async loading of the app list
        loadAppsAsync()

        // OTA update check — only on fresh launch, not on rotation
        if (savedInstanceState == null) {
            UpdateChecker.checkUpdate(this, OtaProgressUi.makeListener(this, false))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShowMirrorIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after returning from Settings/Layout Manager, not only on process creation.
        // This lets a newly saved/favourited layout launch immediately without restarting DashCast.
        when (FissionOrchestrator.maybeAutoStartOnAppLaunch(this)) {
            FissionOrchestrator.AutoStartResult.MISSING_LAYOUT -> {
                if (!mMissingAutoLayoutToastShown) {
                    mMissingAutoLayoutToastShown = true
                    Toast.makeText(applicationContext, R.string.lm_save_before_favorite,
                        Toast.LENGTH_LONG).show()
                }
            }
            FissionOrchestrator.AutoStartResult.DISABLED -> mMissingAutoLayoutToastShown = false
            FissionOrchestrator.AutoStartResult.STARTED -> mMissingAutoLayoutToastShown = false
            else -> Unit
        }
        // Hotspot navrail entry depends on the "use_own_sim" pref; re-evaluate on every resume.
        mNavCoordinator?.refreshHotspot()
        // v1.2.45 — Compact apps panel pref is live-applied.
        applyCompactAppsPanelMode()
        // Fission button visibility follows its toggle in SettingsActivity.
        mFissionCoordinator?.refresh()
        updateLaunchLayoutAppsButton()
    }

    private fun updateLaunchLayoutAppsButton() {
        val show = !ClusterPrefs.isFissionAutoLayout(this) &&
            DaemonConfig.isFissionModeEnabled(this) &&
            favoriteLayoutHasApps()
        btnLaunchLayoutApps.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Expands/collapses the Cluster-layout section (collapsed by default so the live
     *  preview keeps its full height). Rotates the chevron down (collapsed) / up (open). */
    private fun applyLayoutSectionCollapsed() {
        mLayoutSectionBody.visibility = if (mLayoutSectionExpanded) View.VISIBLE else View.GONE
        mLayoutSectionChevron.rotation = if (mLayoutSectionExpanded) 180f else 0f
    }

    private fun favoriteLayoutHasApps(): Boolean {
        val fav = LayoutPrefs.getFavoriteLayout(this) ?: return false
        for (s in fav.slots) {
            if (!s.packageName.isNullOrEmpty()) return true
        }
        return false
    }

    /** Applies the regular or compact apps-panel preference. Idempotent. */
    private fun applyCompactAppsPanelMode() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val compact = prefs.getBoolean(SettingsActivity.PREF_COMPACT_APPS_PANEL,
            SettingsActivity.DEFAULT_COMPACT_APPS_PANEL)
        val filtersEnabled = prefs.getBoolean(SettingsActivity.PREF_SHOW_CATEGORY_FILTERS,
            SettingsActivity.DEFAULT_SHOW_CATEGORY_FILTERS)
        val config = AppsPanelLayoutPolicy.resolve(compact, filtersEnabled)

        val lp = llAppListSection.layoutParams
        if (lp is LinearLayout.LayoutParams) {
            val targetW = config.fixedWidthDp?.let {
                (it * resources.displayMetrics.density).toInt()
            } ?: 0
            if (lp.width != targetW || lp.weight != config.weight) {
                lp.width = targetW
                lp.weight = config.weight
                llAppListSection.layoutParams = lp
            }
        }

        mAppListCoordinator.ensureGridSpanCount(config.gridSpanCount)
        llCategoryFilters.visibility =
            if (config.showCategoryFilters) View.VISIBLE else View.GONE
    }

    private fun handleShowMirrorIntent(intent: Intent?) {
        if (intent == null) return
        if (FloatingRemoteButton.ACTION_SHOW_MIRROR == intent.action && mCurrentDashboardApp != null) {
            showMirrorView()
            attemptStartMirrorWithCurrentHolder()
            AppLogger.d(TAG, "handleShowMirrorIntent → showMirrorView for $mCurrentDashboardApp")
        } else if (FloatingRemoteButton.ACTION_QUICK_SWITCH == intent.action) {
            val pkg = intent.getStringExtra(FloatingRemoteButton.EXTRA_QUICK_SWITCH_PKG)
            if (pkg != null) {
                AppLogger.i(TAG, "Quick-switch intent → $pkg")
                quickSwitchToApp(pkg)
            }
        }
    }

    private fun quickSwitchToApp(pkgName: String) {
        val svc = mClusterService ?: return
        if (pkgName == mCurrentDashboardPkg) {
            startClusterMirror()
            return
        }
        mUsageTracker.trackStop(mCurrentDashboardPkg)
        // Remove from tracker before launch to avoid a concurrent eviction force-stopping it.
        mSessionTracker.remove(pkgName)
        val displayId = svc.getDisplayId()
        if (displayId <= 0) {
            AppLogger.w(TAG, "quickSwitchToApp: cluster display unavailable — reactivating")
            mAppRepo.findByPackage(pkgName)?.let { mPendingAppAfterActivation = it }
            activateCluster()
            return
        }
        svc.moveTaskToDisplay(pkgName, displayId, object : ClusterService.LaunchCallback {
            override fun onResult(launched: Boolean) {
                if (launched) {
                    mLastLaunchTime = System.currentTimeMillis()
                    mCurrentDashboardPkg = pkgName
                    mSessionTracker.add(pkgName)
                    var name = pkgName
                    try {
                        val ai = packageManager.getApplicationInfo(pkgName, 0)
                        val label = packageManager.getApplicationLabel(ai)
                        name = label.toString()
                    } catch (ignored: Exception) {
                    }
                    mCurrentDashboardApp = name
                    ClusterPrefs.addRecentApp(this@MainActivity, pkgName, name)
                    mUsageTracker.trackStart()
                    ClusterPrefs.setClusterPkg(this@MainActivity, pkgName)
                    ClusterPrefs.setClusterName(this@MainActivity, name)
                    ClusterPrefs.setLastCluster(this@MainActivity, pkgName, name)
                    mAppListCoordinator.setCurrentPackage(pkgName)
                    updateDashboardStatus(mCurrentDashboardApp)
                    updateControlLabel()
                    startClusterMirror()
                    mInsetApplicator.apply(pkgName)
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        AppLogger.lifecycle(javaClass.simpleName, "onStart")
        if (AdbLocalClient.isAdbPortRefused() && !sAdbWarningShown) {
            sAdbWarningShown = true
            Toast.makeText(applicationContext, R.string.toast_adb_port_refused, Toast.LENGTH_LONG).show()
        }
        mPermissionBannerCoordinator?.refresh()
        // Refresh category filter visibility (may have been toggled in Settings)
        applyCompactAppsPanelMode()
        // Retrieve the daemon Binder from ServiceManager if not yet available.
        if (mDaemonBinder == null) {
            tryGetDaemonBinderFromServiceManager()
            // On ROMs where ServiceManager.addService is blocked, the binder arrives only via the
            // startup broadcast; trigger ProxyClient.connect() to make the daemon re-emit it.
            if (mCurrentDashboardApp != null) {
                Thread({
                    val ok = ProxyClient.connect(this@MainActivity)
                    if (!ok) AppLogger.w(TAG, "onStart daemon reconnect failed")
                }, "onstart-daemon-reconnect").start()
            }
        }
        val svc = mClusterService
        if (mServiceBound && svc != null) {
            // Activity back in the foreground: re-attach the listener.
            svc.setListener(this)
            // v1.2.82 — re-sync the status UI if the cluster display is currently up.
            try {
                val curDispId = svc.getDisplayId()
                if (curDispId > 0) {
                    updateDashboardStatus(mCurrentDashboardApp)
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "onStart: status re-sync failed: " + t.message)
            }
            // If an app was active and the mirror is shown, restart it.
            if (mCurrentDashboardApp != null && frameMirror.isVisible) {
                attemptStartMirrorWithCurrentHolder()
            }
            if (mCurrentDashboardApp != null) {
                btnShowMirror.visibility = View.VISIBLE
                FloatingRemoteButton.show()
            }
        } else if (!mBindRequested) {
            // Check if the service is already running (e.g. Activity re-opened)
            if (ClusterService.sIsRunning) {
                mBindRequested = true
                mNavCoordinator?.setStatusPending()
                val svcIntent = Intent(this, ClusterService::class.java)
                bindService(svcIntent, mServiceConn, Context.BIND_AUTO_CREATE)
            } else if (mPendingAutoLaunchPkg != null && !isLayoutAutoStartRequested()) {
                // v1.4.24 — an explicitly configured auto-launch app activates projection at startup.
                AppLogger.i(
                    TAG, "auto-launch app configured (" + mPendingAutoLaunchPkg +
                        ") — activating projection on startup"
                )
                activateCluster()
            }
        }
        mStatePollCoordinator?.start()
        // Reflect fission-layout apps in the app list (indicator + long-press kill).
        FissionOrchestrator.setLayoutChangeListener { refreshLayoutPackages() }
        refreshLayoutPackages()
    }

    override fun onStop() {
        super.onStop()
        AppLogger.lifecycle(javaClass.simpleName, "onStop")
        mStatePollCoordinator?.stop()
        // Keep the daemon mirror alive when a nav app is actively streaming on the cluster.
        val svc = mClusterService
        val clusterAppActive = mCurrentDashboardApp != null &&
            mServiceBound && svc != null && svc.getDisplayId() > 0
        // DL3 keepalive: the daemon mirror (layerStack=2 fallback) must survive onStop.
        val keepDaemonMirror = mServiceBound && svc != null &&
            svc.getMirrorManager().isMirrorViaDaemon() && svc.getDisplayId() <= 0
        if (!clusterAppActive && !keepDaemonMirror) {
            stopClusterMirror()
        }
        if (mServiceBound && svc != null) {
            svc.setListener(null)
        }
        // Detach the fission layout listener — only the foreground Activity drives the indicators.
        FissionOrchestrator.setLayoutChangeListener(null)
    }

    override fun onDestroy() {
        restorePendingBootAdoption()
        if (::mTimeoutManager.isInitialized) mTimeoutManager.destroy()
        if (::mInsetApplicator.isInitialized) mInsetApplicator.destroy()
        try {
            mClusterService?.setListener(null)
        } catch (ignore: Throwable) {
        }
        // AppRepository owns a never-idle single-thread "app-repo-loader" executor. It is a
        // per-Activity field, so without this shutdown every recreation (locale/theme/config
        // or a cluster display add/remove) leaked one worker thread for the process lifetime.
        try { mAppRepo.shutdown() } catch (ignore: Throwable) {}
        super.onDestroy()
        AppLogger.lifecycle(javaClass.simpleName, "onDestroy")
        // Cancel all pending runnables.
        mScreenshotHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(mDaemonReadyReceiver)
        if (mServiceBound) {
            stopClusterMirror()
            unbindService(mServiceConn)
            mServiceBound = false
            mBindRequested = false
        }
        mMirrorCoordinator?.destroy()
    }

    private fun restorePendingBootAdoption() {
        val pendingPkg = mDashboardSelectionTracker.takePendingForInvalidation() ?: return
        if (ClusterService.sBootLaunchedPkg == null) {
            ClusterService.sBootLaunchedPkg = pendingPkg
            AppLogger.d(TAG, "restored pending boot adoption for $pendingPkg")
        }
    }

    // ---- ClusterService.Listener ----

    override fun onClusterDisplayConnected(display: Display?, displayId: Int) {
        AppLogger.log(
            TAG, "Dashboard connected — displayId=" + displayId +
                " name=" + (display?.name ?: "IActivityManager/fallback")
        )
        val svc = mClusterService
        if (mServiceBound && svc != null) {
            mDashboardLauncher = svc.getLauncher()
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            mTimeoutManager.cancel()
            val wasManual = mWasManualActivation
            mWasManualActivation = false
            updateDashboardStatus(null)

            // Restore active cluster app if the Activity was recreated (in-memory state lost).
            if (mCurrentDashboardPkg == null) {
                val pkg = ClusterPrefs.getClusterPkg(this)
                val name = ClusterPrefs.getClusterName(this)
                if (pkg != null) {
                    mCurrentDashboardPkg = pkg
                    mCurrentDashboardApp = name
                    mAppListCoordinator.setCurrentPackage(pkg)
                    updateDashboardStatus(name)
                    updateControlLabel()
                    showMirrorView() // makes panelClusterControl visible
                    AppLogger.i(TAG, "cluster active app restored: $pkg")
                    // Relaunch the mirror daemon if its startup broadcast was missed after recreate.
                    if (mDaemonBinder == null) {
                        val msSinceStart = System.currentTimeMillis() - AdbLocalClient.getLastDaemonStartMs()
                        if (msSinceStart > 3000) {
                            AppLogger.w(TAG, "onClusterDisplayConnected: Activity restored but daemon binder lost — relaunching mirror daemon")
                            AdbLocalClient.startMirrorDaemon(this)
                        } else {
                            AppLogger.d(TAG, "onClusterDisplayConnected: daemon start recent ($msSinceStart ms ago) — waiting for broadcast")
                        }
                    }
                }
            }

            // If the mirror is shown (app already active), start/reconfigure it.
            if (frameMirror.isVisible) {
                attemptStartMirrorWithCurrentHolder()
            }

            // Restore mMainDisplayPkg if the Activity was recreated.
            if (mMainDisplayPkg == null) {
                mMainDisplayPkg = ClusterPrefs.getMainPkg(this)
                if (mMainDisplayPkg != null) mAppListCoordinator.setMainPackage(mMainDisplayPkg)
            }

            // Pending app from "activate cluster" dialog.
            val pendingShortcut = mPendingShortcutAfterActivation
            if (pendingShortcut != null) {
                mPendingShortcutAfterActivation = null
                onLaunchShortcut(pendingShortcut.first, pendingShortcut.second)
            }
            val pending = mPendingAppAfterActivation
            if (pending != null) {
                mPendingAppAfterActivation = null
                AppLogger.i(TAG, "Auto-sending pending app after activation: " + pending.packageName)
                // v1.3.8-beta — if the cached pkg matches a restored app, pre-check pidof and force a
                // fresh launch if the process is dead (avoids mirroring an empty cluster display).
                if (pending.packageName == mCurrentDashboardPkg) {
                    val checkPkg = pending.packageName
                    ShellGateway.execShellWithResult(this, "pidof $checkPkg", object : AdbLocalClient.Callback {
                        override fun onSuccess(output: String?) {
                            val alive = output != null && output.trim().isNotEmpty()
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                if (!alive) {
                                    AppLogger.w(TAG, "auto-restore: $checkPkg process is dead → clearing cluster state for fresh launch")
                                    clearClusterState()
                                }
                                onSendToDashboard(pending)
                            }
                        }

                        override fun onError(error: String?) {
                            AppLogger.w(TAG, "auto-restore: pidof failed ($error) — proceeding with mirror shortcut")
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                onSendToDashboard(pending)
                            }
                        }
                    })
                } else {
                    onSendToDashboard(pending)
                }
            }

            // Auto-Launch process. Only runs if the app list is already loaded; if the
            // cluster connected before the async load finished, the pending is kept and
            // replayed from loadAppsAsync()'s callback (see tryExecutePendingAutoLaunch).
            tryExecutePendingAutoLaunch()

            // Reconnect reminder (guarded by user preference).
            val reconnectEnabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_RECONNECT_POPUP, false)
            if (reconnectEnabled && wasManual && mCurrentDashboardPkg == null) {
                val lastPkg = ClusterPrefs.getLastClusterPkg(this)
                val lastName = ClusterPrefs.getLastClusterName(this)
                if (lastPkg != null && lastName != null) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_reconnect_title))
                        .setMessage(getString(R.string.dialog_reconnect_msg, lastName))
                        .setPositiveButton(getString(R.string.dialog_reconnect_yes)) { _, _ ->
                            for (a in mAppListCoordinator.getApps()) {
                                if (a.packageName == lastPkg) {
                                    onSendToDashboard(a)
                                    break
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    override fun onClusterDisplayDisconnected() {
        mDashboardSelectionTracker.takePendingForInvalidation()
        AppLogger.log(TAG, "Dashboard disconnected")
        runOnUiThread {
            mTimeoutManager.cancel()
            mWasManualActivation = false
            mCurrentDashboardApp = null
            mCurrentDashboardPkg = null
            mMainDisplayPkg = null
            ClusterPrefs.setMainPkg(this, null)
            ClusterPrefs.setClusterPkg(this, null)
            ClusterPrefs.setClusterName(this, null)
            mSplitController?.clearSplitState()
            mAppListCoordinator.setCurrentPackage(null)
            mAppListCoordinator.setMainPackage(null)
            // v0.9.73 — unified OFF state ("Projection inactive") with grey dot.
            setDashboardOffState()
            showAppList()
        }
    }

    // ---- AppListAdapter.OnSendToDashboardListener ----

    override fun onSetAutoLaunch(app: AppInfo, enable: Boolean) {
        mAppRepo.setAutoLaunch(this, app.packageName, enable)
        // Post to avoid IllegalStateException (cannot call notify during bind)
        val coord = mAppListCoordinator
        Handler(Looper.getMainLooper()).post { coord.notifyAppsChanged() }
    }

    override fun onToggleFavorite(app: AppInfo) {
        // setFavorite patches the in-memory cache and re-sorts — no PackageManager re-query.
        mAppRepo.setFavorite(this, app.packageName, !app.isFavorite)
        mAppListCoordinator.deliver(mAppRepo.getApps(), false)
    }

    // v0.9.72 — long-press opens a bottom sheet with the per-app actions.
    override fun onShowActions(app: AppInfo) {
        AppActionSheet.show(app, this)
    }

    override fun onSendToDashboard(app: AppInfo) {
        mPendingShortcutAfterActivation = null
        // A running Layout app already owns a dedicated VD. A normal launch here would start a
        // competing classic projection; instead, make this app the tactile mirror target.
        if (FissionOrchestrator.isLayoutPackage(app.packageName)) {
            selectLayoutMirror(app.packageName)
            return
        }
        val selection = mDashboardSelectionTracker.begin(app.packageName)
        if (selection.isDuplicatePending) {
            AppLogger.d(TAG, "onSendToDashboard: boot adoption already pending for " + app.packageName)
            return
        }
        val selectionGeneration = selection.generation
        if (rejectUnsupportedDashboardProjection()) return
        val svc = mClusterService
        if (svc == null) {
            // v1.2.76 — auto-trigger activateCluster() and replay the app once the service is up.
            AppLogger.i(TAG, "ClusterService null — auto-activating for " + app.packageName)
            mPendingAppAfterActivation = app
            activateCluster()
            return
        }

        // v1.2.83 — same auto-activation if bound but projection was stopped (displayId=-1).
        if (svc.getDisplayId() <= 0) {
            AppLogger.i(TAG, "ClusterService bound but displayId<=0 — auto-activating for " + app.packageName)
            mPendingAppAfterActivation = app
            activateCluster()
            return
        }

        AppLogger.log(
            TAG, "Envoi cluster — " + app.packageName +
                " display=" + mDashboardLauncher.getDashboardDisplayId()
        )
        val appName = app.appName
        val pkgName = app.packageName

        // Guard: the boot flow already launched this app onto the cluster headlessly
        // (ClusterService.sBootLaunchedPkg). Adopt it and show the mirror instead of relaunching —
        // a relaunch would force-stop the running nav (INC-20260716-091016).
        if (pkgName == com.byd.dashcast.cluster.ClusterService.sBootLaunchedPkg
                && (mClusterService?.displayId ?: -1) > 0) {
            // Consume-once up front so a re-entrant/racing tap can't take this branch twice, and so a
            // later switch-away-then-back is governed by the normal mCurrentDashboardPkg guard.
            com.byd.dashcast.cluster.ClusterService.sBootLaunchedPkg = null
            if (!mDashboardSelectionTracker.markBootPending(pkgName, selectionGeneration)) {
                com.byd.dashcast.cluster.ClusterService.sBootLaunchedPkg = pkgName
                return
            }
            // The boot flow launched this app headlessly onto the cluster. If it is STILL on the
            // cluster, just show the mirror — a relaunch would recreate the running nav
            // (INC-20260716-091016). But if it has since left the cluster (crash, OEM nav takeover, or
            // its task was moved away) the mirror would be blank/stale, so fall through to a normal
            // launch instead. Location is checked off the main thread through the typed daemon verb.
            val expectedDisplayId = svc.displayId
            svc.findPackageLocation(pkgName, object : ClusterService.TaskLocationCallback {
                override fun onResult(location: TaskLocation) {
                    if (isFinishing || isDestroyed) return
                    if (mClusterService !== svc || svc.displayId != expectedDisplayId) {
                        if (mDashboardSelectionTracker.completeBoot(pkgName, selectionGeneration)
                                && ClusterService.sBootLaunchedPkg == null) {
                            ClusterService.sBootLaunchedPkg = pkgName
                        }
                        return
                    }
                    if (!mDashboardSelectionTracker.completeBoot(pkgName, selectionGeneration)) return
                    when (location.matchDisplay(expectedDisplayId)) {
                        TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY -> {
                            ClusterPrefs.incrementLaunchCount(this@MainActivity, pkgName)
                            AppLogger.d(TAG, "onSendToDashboard: boot-launched task present — show mirror only")
                            mCurrentDashboardPkg = pkgName
                            startClusterMirror()
                        }
                        TaskLocation.DisplayMatch.ON_OTHER_DISPLAY,
                        TaskLocation.DisplayMatch.ABSENT -> {
                            // Latch already cleared → this re-entry takes the normal launch path.
                            mCurrentDashboardPkg = null
                            AppLogger.i(TAG, "onSendToDashboard: boot-launched task not on display "
                                    + expectedDisplayId + " — launching $pkgName")
                            onSendToDashboard(app)
                        }
                        TaskLocation.DisplayMatch.UNKNOWN -> {
                            // Never turn an ATM/transport failure into a destructive nav relaunch.
                            // Preserve the prior mirror-only behavior and let a later tap retry.
                            if (ClusterService.sBootLaunchedPkg == null) {
                                ClusterService.sBootLaunchedPkg = pkgName
                            }
                            mCurrentDashboardPkg = pkgName
                            ClusterPrefs.incrementLaunchCount(this@MainActivity, pkgName)
                            AppLogger.w(TAG, "onSendToDashboard: task location unknown — preserving boot-launched app")
                            startClusterMirror()
                        }
                    }
                }
            })
            return
        }

        ClusterPrefs.incrementLaunchCount(this, app.packageName)

        // Guard: if already on the cluster, just show the mirror (no moveTaskToDisplay).
        if (pkgName == mCurrentDashboardPkg) {
            AppLogger.d(TAG, "onSendToDashboard: already on cluster — show mirror only")
            startClusterMirror()
            return
        }

        // If this app was on the main display, clear that state immediately.
        if (pkgName == mMainDisplayPkg) {
            mMainDisplayPkg = null
            mAppListCoordinator.setMainPackage(null)
            ClusterPrefs.setMainPkg(this, null)
        }

        // ── Split mode: the new app goes into the complementary slot ──
        val split = mSplitController
        if (split != null && split.isInSplitMode() && mCurrentDashboardPkg != null) {
            if (pkgName == mCurrentDashboardPkg || pkgName == split.getSecondDashboardPkg()) {
                AppLogger.w(
                    TAG, "split: duplicate ignored pkg=" + pkgName +
                        " (main=" + mCurrentDashboardPkg + " second=" + split.getSecondDashboardPkg() + ")"
                )
                Toast.makeText(applicationContext, getString(R.string.toast_app_already_cluster), Toast.LENGTH_SHORT).show()
                return
            }
            val dims = split.getClusterDimensions()
            val w = dims[0]
            val h = dims[1]
            val newLeft = if (split.getCurrentSplitSlot() == 1) w / 2 else 0
            val newRight = if (split.getCurrentSplitSlot() == 1) w else w / 2
            AppLogger.log(
                TAG, "split — slot courant=" + split.getCurrentSplitSlot() +
                    " → complementary slot bounds=[" + newLeft + ",0," + newRight + "," + h + "]" +
                    " pkg=" + pkgName
            )
            val replacementGeneration = split.beginSecondDashboardReplacement()
            fun launchInComplementarySlot() {
                if (!split.isCurrentSecondDashboardReplacement(replacementGeneration)) return
                mSessionTracker.remove(pkgName)
                svc.launchOnDashboardWithBounds(pkgName, newLeft, 0, newRight, h, object : ClusterService.LaunchCallback {
                    override fun onResult(launched: Boolean) {
                        if (!split.isCurrentSecondDashboardReplacement(replacementGeneration)) return
                        if (launched) {
                            mLastLaunchTime = System.currentTimeMillis()
                            split.setSecondDashboardApp(appName)
                            split.setSecondDashboardPkg(pkgName)
                            mSessionTracker.add(pkgName)
                            updateControlLabel()
                        } else {
                            Toast.makeText(applicationContext, getString(R.string.toast_app_launch_failed, appName), Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
            val previousSecond = split.getSecondDashboardPkg()
            if (previousSecond != null) {
                AdbLocalClient.forceStopApp(this, previousSecond, object : AdbLocalClient.Callback {
                    override fun onSuccess(report: String?) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (!split.clearSecondDashboardIfMatches(
                                    previousSecond, replacementGeneration)) return@runOnUiThread
                            mSessionTracker.remove(previousSecond)
                            launchInComplementarySlot()
                        }
                    }

                    override fun onError(error: String?) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (split.isCurrentSecondDashboardReplacement(replacementGeneration) &&
                                split.getSecondDashboardPkg() == previousSecond) {
                                launchInComplementarySlot()
                            }
                        }
                    }
                })
            } else {
                launchInComplementarySlot()
            }
            return
        }

        // ── Normal behavior — move (or launch if not running) ──
        // Factored out so it can run either immediately or after the previous cluster app
        // has been stopped (see the guard below).
        fun proceedMove() {
            mSessionTracker.remove(pkgName)
            val clusterDisplayId = svc.getDisplayId()
            if (clusterDisplayId <= 0) {
                AppLogger.w(TAG, "proceedMove: cluster display disappeared — reactivating")
                mPendingAppAfterActivation = app
                activateCluster()
                return
            }
            val targetDisplayId = clusterDisplayId
            svc.moveTaskToDisplay(pkgName, targetDisplayId, object : ClusterService.LaunchCallback {
                override fun onResult(launched: Boolean) {
                    AppLogger.log(
                        TAG, "moveTaskToDisplay " + pkgName + " → display=" + targetDisplayId +
                            " " + (if (launched) "OK" else "FAILED")
                    )
                    if (launched) {
                        mLastLaunchTime = System.currentTimeMillis()
                        mUsageTracker.trackStop(mCurrentDashboardPkg)
                        mCurrentDashboardApp = appName
                        mCurrentDashboardPkg = pkgName
                        mSessionTracker.add(pkgName)
                        ClusterPrefs.addRecentApp(this@MainActivity, pkgName, appName)
                        mUsageTracker.trackStart()
                        ClusterPrefs.setClusterPkg(this@MainActivity, pkgName)
                        ClusterPrefs.setClusterName(this@MainActivity, appName)
                        ClusterPrefs.setLastCluster(this@MainActivity, pkgName, appName)
                        mAppListCoordinator.setCurrentPackage(pkgName)
                        updateDashboardStatus(appName)
                        updateControlLabel()
                        startClusterMirror()
                        // v1.2.55-beta — deferred enforcement for apps AOSP placed on display 0.
                        mScreenshotHandler.postDelayed({
                            if (isFinishing || isDestroyed) return@postDelayed
                            if (pkgName != mCurrentDashboardPkg) return@postDelayed
                            svc.enforceTaskOnDisplay(pkgName, targetDisplayId)
                        }, 2500L)
                        mInsetApplicator.apply(pkgName)
                    } else {
                        Toast.makeText(applicationContext, getString(R.string.toast_app_launch_failed, appName), Toast.LENGTH_LONG).show()
                    }
                }
            })
        }

        // INC-20260621-130238 — on ROMs where IActivityTaskManager.moveTaskToDisplay is
        // stripped (DiLink3.0), the fallback launch cannot reparent the app already on the
        // cluster, so the second app lands in split-screen on the main display (NPE
        // ActivityStack.getBounds → split-screen-primary). Free the cluster by stopping the
        // previous app first, then launch. No-op on ROMs where the move works (DL5/DL4) —
        // there the running app is reparented as before, behaviour unchanged.
        val previousClusterPkg = mCurrentDashboardPkg
        if (previousClusterPkg != null && previousClusterPkg != pkgName &&
            !svc.isMoveTaskToDisplaySupported()
        ) {
            AppLogger.i(
                TAG, "cluster occupied by " + previousClusterPkg +
                    " and moveTaskToDisplay unavailable → stopping it before launching " + pkgName
            )
            AdbLocalClient.forceStopApp(this, previousClusterPkg, object : AdbLocalClient.Callback {
                override fun onSuccess(report: String?) {
                    runOnUiThread { if (!isFinishing && !isDestroyed) proceedMove() }
                }

                override fun onError(error: String?) {
                    runOnUiThread { if (!isFinishing && !isDestroyed) proceedMove() }
                }
            })
        } else {
            proceedMove()
        }
    }

    override fun onLaunchShortcut(app: AppInfo, shortcut: AppShortcut) {
        mDashboardSelectionTracker.begin(null)
        mPendingAppAfterActivation = null
        mPendingShortcutAfterActivation = null
        if (rejectUnsupportedDashboardProjection()) return

        val layoutTarget = if (FissionOrchestrator.isLayoutPackage(app.packageName)) {
            FissionOrchestrator.selectLayoutMirrorPackage(app.packageName)
        } else null
        val service = mClusterService
        val targetDisplayId = layoutTarget?.displayId ?: (service?.displayId ?: -1)
        if (targetDisplayId <= 0) {
            if (layoutTarget != null) return
            mPendingShortcutAfterActivation = app to shortcut
            activateCluster()
            return
        }

        val split = if (layoutTarget == null) mSplitController else null
        var splitBounds: Rect? = null
        var splitOccupantToStop: String? = null
        var splitReplacementGeneration: Int? = null
        if (split != null && split.isInSplitMode() && mCurrentDashboardPkg != null) {
            if (app.packageName == mCurrentDashboardPkg ||
                app.packageName == split.getSecondDashboardPkg()) {
                Toast.makeText(applicationContext, getString(R.string.toast_app_already_cluster),
                    Toast.LENGTH_SHORT).show()
                return
            }
            val dimensions = split.getClusterDimensions()
            val left = if (split.getCurrentSplitSlot() == 1) dimensions[0] / 2 else 0
            val right = if (split.getCurrentSplitSlot() == 1) dimensions[0] else dimensions[0] / 2
            splitBounds = Rect(left, 0, right, dimensions[1])
            splitOccupantToStop = split.getSecondDashboardPkg()
            splitReplacementGeneration = split.beginSecondDashboardReplacement()
        }

        fun launch() {
            val replacementGeneration = splitReplacementGeneration
            if (replacementGeneration != null &&
                !split!!.isCurrentSecondDashboardReplacement(replacementGeneration)) return
            if (layoutTarget == null &&
                (mClusterService !== service || service?.displayId != targetDisplayId)) {
                mPendingShortcutAfterActivation = app to shortcut
                activateCluster()
                return
            }
            try {
                val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                    ?: throw IllegalStateException("LauncherApps unavailable")
                val layoutBounds = layoutTarget?.let { Rect(0, 0, it.width, it.height) }
                val options = mDashboardLauncher.createLaunchOptions(
                    targetDisplayId, splitBounds ?: layoutBounds)
                launcherApps.startShortcut(
                    app.packageName,
                    shortcut.id,
                    null,
                    options.toBundle(),
                    android.os.Process.myUserHandle(),
                )
                ClusterPrefs.incrementLaunchCount(this, app.packageName)
                if (layoutTarget != null) {
                    selectLayoutMirror(app.packageName)
                    return
                }
                val activeSplitBounds = splitBounds
                if (split != null && activeSplitBounds != null) {
                    mLastLaunchTime = System.currentTimeMillis()
                    split.setSecondDashboardApp(app.appName)
                    split.setSecondDashboardPkg(app.packageName)
                    mSessionTracker.remove(app.packageName)
                    mSessionTracker.add(app.packageName)
                    updateControlLabel()
                    return
                }
                if (app.packageName == mMainDisplayPkg) {
                    mMainDisplayPkg = null
                    mAppListCoordinator.setMainPackage(null)
                    ClusterPrefs.setMainPkg(this, null)
                }
                mLastLaunchTime = System.currentTimeMillis()
                mUsageTracker.trackStop(mCurrentDashboardPkg)
                mSessionTracker.remove(app.packageName)
                mCurrentDashboardApp = app.appName
                mCurrentDashboardPkg = app.packageName
                mSessionTracker.add(app.packageName)
                ClusterPrefs.addRecentApp(this, app.packageName, app.appName)
                mUsageTracker.trackStart()
                ClusterPrefs.setClusterPkg(this, app.packageName)
                ClusterPrefs.setClusterName(this, app.appName)
                ClusterPrefs.setLastCluster(this, app.packageName, app.appName)
                mAppListCoordinator.setCurrentPackage(app.packageName)
                updateDashboardStatus(app.appName)
                updateControlLabel()
                startClusterMirror()
                mScreenshotHandler.postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    if (app.packageName != mCurrentDashboardPkg) return@postDelayed
                    service?.enforceTaskOnDisplay(app.packageName, targetDisplayId)
                }, 2500L)
                mInsetApplicator.apply(app.packageName)
            } catch (error: Exception) {
                AppLogger.e(TAG, "shortcut launch failed ${app.packageName}/${shortcut.id}", error)
                Toast.makeText(applicationContext,
                    getString(R.string.toast_app_launch_failed, app.appName), Toast.LENGTH_LONG).show()
            }
        }

        val previous = mCurrentDashboardPkg
        if (splitOccupantToStop != null) {
            AdbLocalClient.forceStopApp(this, splitOccupantToStop, object : AdbLocalClient.Callback {
                override fun onSuccess(report: String?) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val generation = splitReplacementGeneration ?: return@runOnUiThread
                        if (!split!!.clearSecondDashboardIfMatches(
                                splitOccupantToStop, generation)) return@runOnUiThread
                        mSessionTracker.remove(splitOccupantToStop)
                        launch()
                    }
                }
                override fun onError(error: String?) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val generation = splitReplacementGeneration ?: return@runOnUiThread
                        if (split!!.isCurrentSecondDashboardReplacement(generation) &&
                            split.getSecondDashboardPkg() == splitOccupantToStop) launch()
                    }
                }
            })
        } else if (layoutTarget == null && splitBounds == null &&
            previous != null && previous != app.packageName
                && service != null && !service.isMoveTaskToDisplaySupported()) {
            AdbLocalClient.forceStopApp(this, previous, object : AdbLocalClient.Callback {
                override fun onSuccess(report: String?) {
                    runOnUiThread { if (!isFinishing && !isDestroyed) launch() }
                }
                override fun onError(error: String?) {
                    runOnUiThread { if (!isFinishing && !isDestroyed) launch() }
                }
            })
        } else {
            launch()
        }
    }

    private fun rejectUnsupportedDashboardProjection(): Boolean {
        if (com.byd.dashcast.hud.AaosClusterProbe.isAaos(this)) {
            AppLogger.i(TAG, "Dashboard projection unavailable on AAOS")
            AlertDialog.Builder(this)
                .setTitle(R.string.aaos_cluster_unsupported_title)
                .setMessage(R.string.aaos_cluster_unsupported_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return true
        }
        if (Platform.get().isDiLink3(this) && Platform.isClusterSingleOs()) {
            AppLogger.i(TAG, "Dashboard projection unavailable on DL3 single-OS")
            AlertDialog.Builder(this)
                .setTitle(R.string.dl3_singleos_cluster_unsupported_title)
                .setMessage(R.string.dl3_singleos_cluster_unsupported_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return true
        }
        if (Platform.get().isDiLink4(this) &&
            com.byd.dashcast.cluster.display.ClusterManager.isDl4ProjectionUnavailable()) {
            AppLogger.i(TAG, "Dashboard projection unavailable on DL4")
            AlertDialog.Builder(this)
                .setTitle(R.string.dl4_cluster_unsupported_title)
                .setMessage(R.string.dl4_cluster_unsupported_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return true
        }
        return false
    }

    override fun onSendToMain(app: AppInfo) {
        ClusterPrefs.incrementLaunchCount(this, app.packageName)
        mUsageTracker.trackStop(mCurrentDashboardPkg)
        // Clean up cluster state before move
        mCurrentDashboardApp = null
        mCurrentDashboardPkg = null
        ClusterPrefs.setClusterPkg(this, null)
        ClusterPrefs.setClusterName(this, null)
        // Force-stop the secondary slot in split mode.
        val split = mSplitController
        if (split != null && split.getSecondDashboardPkg() != null) {
            AdbLocalClient.forceStopApp(this, split.getSecondDashboardPkg(), null)
        }
        split?.clearSplitState()
        // Record that the app is on the main display → shows "→ Cluster" in the list.
        mMainDisplayPkg = app.packageName
        mAppListCoordinator.setCurrentPackage(null)
        mAppListCoordinator.setMainPackage(app.packageName)
        ClusterPrefs.setMainPkg(this, app.packageName)
        updateDashboardStatus(null)
        showAppList()
        // Move the running task to display 0 without relaunching. Deliberately does NOT untrack the
        // package: this move is unverified (on a ROM without moveTaskToDisplay it is an async
        // relaunch that can silently not happen), and dropping it here is what stranded an app on
        // the cluster in INC-20260809-122719. Stop projection probes where it actually is and skips
        // it if it did land on display 0.
        val svc = mClusterService
        if (mServiceBound && svc != null) {
            svc.moveTaskToDisplay(app.packageName, 0, null)
        } else {
            mDashboardLauncher.launchOnMainDisplay(app.packageName)
        }
        AppLogger.log(TAG, "Send to main display — " + app.packageName)
    }

    override fun onKillApp(app: AppInfo) {
        // Confirm before killing — accidental taps are easy on a car touchscreen.
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_kill_title))
            .setMessage(getString(R.string.confirm_kill_msg, app.appName))
            .setPositiveButton(getString(R.string.confirm_kill_ok)) { _, _ -> doKillApp(app) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Performs the actual force-stop after the user confirmed. */
    private fun doKillApp(app: AppInfo) {
        // Fission-layout app: route through the orchestrator so its VD slot is released.
        if (FissionOrchestrator.isLayoutPackage(app.packageName)) {
            AppLogger.log(TAG, "doKillApp: fission layout slot — " + app.packageName)
            FissionOrchestrator.killLayoutSlot(app.packageName)
            return
        }

        // 1. If still on the cluster, just kill it in memory (don't stop projection).
        val isOnCluster = mCurrentDashboardPkg != null && app.packageName == mCurrentDashboardPkg

        // Eagerly clear tracked state BEFORE async move/kill.
        if (isOnCluster) {
            mCurrentDashboardApp = null
            mCurrentDashboardPkg = null
            ClusterPrefs.setClusterPkg(this, null)
            ClusterPrefs.setClusterName(this, null)
            mAppListCoordinator.setCurrentPackage(null)
            updateDashboardStatus(null)
        }

        // 2. Move the app back to Display 0 before killing (serialised move → forceStop).
        val killCallback = object : AdbLocalClient.Callback {
            override fun onSuccess(report: String?) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AppLogger.i(TAG, "forceStop " + app.packageName + " OK")
                    val split = mSplitController
                    if (split != null && app.packageName == split.getSecondDashboardPkg()) {
                        split.setSecondDashboardPkg(null)
                        split.clearSplitState()
                    }
                    showAppList()
                    Toast.makeText(applicationContext, getString(R.string.toast_app_stopped, app.appName), Toast.LENGTH_SHORT).show()
                    AppLogger.log(TAG, "forceStop " + app.packageName + " OK")
                }
            }

            override fun onError(error: String?) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(applicationContext, getString(R.string.toast_kill_failed, error), Toast.LENGTH_LONG).show()
                    AppLogger.log(TAG, "forceStop FAILED: $error")
                }
            }
        }

        val svc = mClusterService
        if (mSessionTracker.contains(app.packageName) && mServiceBound && svc != null) {
            svc.moveTaskToDisplay(app.packageName, 0, object : ClusterService.LaunchCallback {
                override fun onResult(ok: Boolean) {
                    AppLogger.i(TAG, "doKillApp: move→display0 " + (if (ok) "OK" else "KO") + " for " + app.packageName + " — now force-stop")
                    mSessionTracker.remove(app.packageName)
                    AdbLocalClient.forceStopApp(this@MainActivity, app.packageName, killCallback)
                }
            })
        } else {
            mSessionTracker.remove(app.packageName)
            AdbLocalClient.forceStopApp(this, app.packageName, killCallback)
        }
    }

    // ---- Miroir cluster ----

    private fun tryGetDaemonBinderFromServiceManager() {
        DaemonBinderResolver.fetch { binder ->
            // Guard: lookup is async and may resolve after onDestroy().
            if (isFinishing || isDestroyed) return@fetch
            mDaemonBinder = binder
            val svc = if (mServiceBound) mClusterService else null
            svc?.getInputForwarder()?.setDaemonBinder(binder)
            // Restart mirror if currently shown so the daemon path can take over.
            if (mCurrentDashboardApp != null && frameMirror.isVisible) {
                if (svc != null) {
                    val mm = svc.getMirrorManager()
                    if (mm.isMirrorActive() && !mm.isMirrorViaDaemon()) {
                        AppLogger.i(TAG, "Daemon resolved late — restarting mirror via daemon")
                        stopClusterMirror()
                    }
                }
                attemptStartMirrorWithCurrentHolder()
            }
        }
    }

    private fun attemptStartMirrorWithCurrentHolder() {
        mMirrorCoordinator?.attemptStart()
    }

    /** Clears all cluster-app bookkeeping and returns to the app list. */
    private fun clearClusterState() {
        mUsageTracker.trackStop(mCurrentDashboardPkg)
        mCurrentDashboardApp = null
        mCurrentDashboardPkg = null
        ClusterPrefs.setClusterPkg(this, null)
        ClusterPrefs.setClusterName(this, null)
        mAppListCoordinator.setCurrentPackage(null)
        updateDashboardStatus(null)
        showAppList()
    }

    /** Hides the app list and displays the cluster mirror in full space. */
    private fun showMirrorView() {
        frameMirror.visibility = View.VISIBLE
        // Pre-arm the inner content for the next time the panel becomes visible.
        mClusterControlCoordinator?.expandContent()
        // Collapse resize panel and hide overlay on app switch.
        mInsetOverlay?.setOverlayVisible(false)
        mClusterControlCoordinator?.collapseResizePanel()

        // v1.8.2 — nothing to preload: the symmetric per-app inset seekbars are gone, and the
        // only way to shrink a cluster app is now the hand-drawn rectangle editor, which reads
        // its own saved rect when opened.
    }

    /** Hides the mirror and restores the app list. */
    private fun showAppList() {
        // The preview is permanently visible on Main. Keep a selected Layout mirror alive while
        // the user browses apps; tile taps are the primary fast slot switcher.
        if (FissionOrchestrator.getSelectedLayoutMirrorTarget() != null) {
            mClusterControlCoordinator?.hidePanel()
            return
        }
        // DL3 keepalive: the daemon mirror (layerStack=2 fallback) must survive the periodic timeout.
        val svc = mClusterService
        val keepDaemonMirror = mServiceBound && svc != null &&
            svc.getMirrorManager().isMirrorViaDaemon() && svc.getDisplayId() <= 0
        if (!keepDaemonMirror) {
            stopClusterMirror()
        }
        mClusterControlCoordinator?.hidePanel()
    }

    /** Signals that an app was launched on the cluster → show the mirror (stop+delayed-rebind). */
    private fun startClusterMirror() {
        AppLogger.d(TAG, "startClusterMirror app=$mCurrentDashboardApp")
        showMirrorView()
        frameMirror.alpha = 0f
        frameMirror.animate().alpha(1f).setDuration(150).start()
        // Tear down before rebind so attemptStartMirror() doesn't short-circuit on a stale token.
        stopClusterMirror()
        val coord = mMirrorCoordinator
        if (coord != null) {
            clusterMirror.postDelayed({ coord.recreateSurfaceAndRestart() }, 250)
        }
    }

    /** Stops the SurfaceControl mirror and hides the panel. */
    private fun stopClusterMirror() {
        mMirrorCoordinator?.stopMirror()
    }

    private fun activateCluster() {
        mNavCoordinator?.setStatusActivating()
        mWasManualActivation = true
        mTimeoutManager.start()
        AppLogger.log(
            TAG, "activateCluster() — serviceBound=" + mServiceBound +
                " bindRequested=" + mBindRequested +
                " displayId=" + (mClusterService?.getDisplayId() ?: "N/A")
        )

        val svc = mClusterService
        if (!mServiceBound || svc == null) {
            // Service stopped or not started yet — start + bind it.
            if (!mBindRequested) {
                mBindRequested = true
                val svcIntent = Intent(this, ClusterService::class.java)
                startForegroundService(svcIntent)
                bindService(svcIntent, mServiceConn, Context.BIND_AUTO_CREATE)
            }
            mNavCoordinator?.setStatusPending()
        } else {
            // stopProjectionNoAdb() may have removed the service's started state while this
            // Activity kept it alive through the binding. Re-arm it before native restart so a
            // later Activity recreation cannot destroy an active projection on unbind.
            AppLogger.log(TAG, "Re-arming ClusterService before native projection restart")
            val restartIntent = Intent(this, ClusterService::class.java).apply {
                action = ClusterService.ACTION_RESTART_PROJECTION
            }
            startForegroundService(restartIntent)
        }
    }

    /** True when the Layouts auto-start owns the startup launch. */
    private fun isLayoutAutoStartRequested(): Boolean =
        FissionOrchestrator.isAutoStartRequested(this)

    private fun restoreBydDashboard() {
        btnRestoreCluster.isEnabled = false
        mNavCoordinator?.setStatusRestoring()
        mUsageTracker.trackStop(mCurrentDashboardPkg)

        val capturedClusterPkg = mCurrentDashboardPkg
        val capturedSecondPkg = mSplitController?.getSecondDashboardPkg()

        // Eagerly clear tracked cluster state BEFORE async eviction.
        mCurrentDashboardApp = null
        mCurrentDashboardPkg = null
        ClusterPrefs.setClusterPkg(this, null)
        ClusterPrefs.setClusterName(this, null)
        mAppListCoordinator.setCurrentPackage(null)

        // Layout teardown is asynchronous but MUST complete first: each slot app is moved to
        // display 0, removeTask+force-stopped with PID verification, then its VD is released.
        FissionOrchestrator.stopAutoOrchestrator {
            continueRestoreBydDashboard(capturedClusterPkg, capturedSecondPkg)
        }
    }

    /**
     * Puts the head unit's home screen back in front when an eviction left an app we could not
     * kill sitting resumed on display 0.
     *
     * INC-20260816: the tester projected Android Auto, pressed Stop, and the centre screen was
     * unusable until he rebooted — the OEM drawer and the home screen each lasted about 0.3 s
     * before Android Auto returned. Keeping that task is correct and stays (destroying it is what
     * made the app unreachable in INC-20260815-181820); what was missing is covering it. The app
     * is left on the display with a live phone session, which is a foreground for the OEM host to
     * reclaim, and we handed it over.
     *
     * Gated on [ClusterSessionTracker.consumeHomeRestoreRequest], which is only ever set when a
     * force-stop went unverified AND the task was seen on display 0 — so for every package whose
     * kill succeeds, which is nearly all of them, this is not reached and Stop behaves exactly as
     * before. The visible consequence where it DOES fire is that Stop leaves you on the launcher
     * rather than on DashCast's app list. That is the trade, and it is the better half of it.
     *
     * Fails silently on purpose: a home screen that will not start must never be the reason the
     * projection teardown reports failure.
     */
    private fun restoreHomeIfRequested() {
        if (!mSessionTracker.consumeHomeRestoreRequest()) return
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            AppLogger.i(TAG, "home screen restored on display 0 after an unverified kill")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "could not restore the home screen: ${t.message}")
        }
    }

    private fun continueRestoreBydDashboard(capturedClusterPkg: String?, capturedSecondPkg: String?) {
        // v1.2.81 — every classic cluster app is moved back to display 0 AND force-stopped.
        // evictAllThen now covers the whole tracked set, so the unverified moveToMainDisplay() that
        // used to run first is gone: it fired moves nobody checked and then CLEARED the set, which
        // is what left the verified pipeline with nothing to work on (INC-20260809-122719).
        AppLogger.log(TAG, "restoreBydDashboard() via ADB (TEST 10)")

        mSessionTracker.evictAllThen(
            if (mServiceBound) mClusterService else null,
            capturedClusterPkg, capturedSecondPkg
        ) {
            // Cluster pkg already killed → pass null so the helper only sends sendInfo(18+0).
            AdbLocalClient.restoreBydOnCluster(this, null, object : AdbLocalClient.Callback {
                override fun onSuccess(report: String?) {
                    runOnUiThread {
                        // Sync ClusterService: invalidate mDashboardDisplayId.
                        if (mServiceBound && mClusterService != null) {
                            mClusterService!!.stopProjectionNoAdb()
                        }
                        restoreHomeIfRequested()
                        mSplitController?.clearSplitState()
                        // v0.9.73 — projection just stopped → OFF state.
                        setDashboardOffState()
                        showAppList()
                        btnRestoreCluster.isEnabled = true
                        AppLogger.log(TAG, "BYD restored via ADB ✓")
                    }
                }

                override fun onError(error: String?) {
                    runOnUiThread {
                        btnRestoreCluster.isEnabled = true
                        Toast.makeText(applicationContext, getString(R.string.toast_restore_failed, error), Toast.LENGTH_LONG).show()
                        AppLogger.log(TAG, "Restore FAILED: $error")
                        // The hazardous state was created by the EVICTION, which has already
                        // finished. Whether the separate cluster-restore call then succeeded says
                        // nothing about it — and that call has several documented flaky paths, so
                        // hanging the cover on its success would drop it exactly when a Stop is
                        // already going badly.
                        restoreHomeIfRequested()
                    }
                }
            })
        }
    }

    private fun updateDashboardStatus(appName: String?) {
        val nav = mNavCoordinator
        if (nav != null) {
            if (appName == null) nav.setStatusDashboardByd() else nav.setStatusActive(appName)
        }
        if (appName == null) {
            btnShowMirror.visibility = View.GONE
            FloatingRemoteButton.hide()
        } else {
            btnShowMirror.visibility = View.VISIBLE
            FloatingRemoteButton.show()
        }
        btnRestoreCluster.isEnabled = true
    }

    /** v0.9.73 — explicit OFF state after stopping projection or before any activation. */
    private fun setDashboardOffState() {
        mNavCoordinator?.setStatusOff()
        btnShowMirror.visibility = View.GONE
        FloatingRemoteButton.hide()
        mClusterControlCoordinator?.hidePanel()
    }

    // ============================================================
    // v0.9.74 — Pseudo-fullscreen tactile mirror
    // ============================================================

    private fun enterFullscreenMirror() {
        mFullscreenCoordinator?.enter()
    }

    private fun exitFullscreenMirror() {
        mFullscreenCoordinator?.exit()
    }

    override fun onBackPressed() {
        val coord = mFullscreenCoordinator
        if (coord != null && coord.isFullscreen()) {
            exitFullscreenMirror()
            return
        }
        super.onBackPressed()
    }

    /** Refreshes the InsetOverlayView projection params from the current mirror state. */
    private fun refreshInsetOverlay() {
        val overlay = mInsetOverlay ?: return
        val svc = mClusterService
        if (!mServiceBound || svc == null) return
        val mirror = svc.getMirrorManager()
        overlay.setProjection(mirror.getProjScale(), mirror.getProjOffsetX().toFloat(), mirror.getProjOffsetY().toFloat())
        // v1.8.2 — the inset seekbars that fed this preview are gone; the cluster is always
        // full-screen unless a hand-drawn rectangle says otherwise, so there is no band to draw.
        overlay.setInsets(0, 0)
    }

    // ── Relaunch current cluster app ─────────────────────────────────────────

    /** Force-stops then relaunches the app currently active on the cluster. */
    private fun relaunchCurrentApp() {
        val pkg = mCurrentDashboardPkg ?: return
        AppLogger.i(TAG, "relaunchCurrentApp → $pkg")
        AdbLocalClient.forceStopApp(this, pkg, object : AdbLocalClient.Callback {
            override fun onSuccess(ignored: String?) {
                relaunchFromList(pkg)
            }

            override fun onError(error: String?) {
                AppLogger.w(TAG, "relaunchCurrentApp: forceStop error: $error")
                relaunchFromList(pkg)
            }
        })
    }

    /**
     * Second half of Relaunch: the app has just been force-stopped, now put it back.
     *
     * Resolve from the repository FIRST, the grid only as a fallback — the same correction already
     * applied to the auto-launch path below, for the same reason. getApps() is the UI grid, and the
     * grid deliberately excludes favourites into their own strip. So Relaunch on a favourited app
     * force-stopped it, failed to find it, logged a warning nobody sees, and left the cluster dark
     * with no way back except sending the app again by hand. The more an app is used — favourited —
     * the more certainly this broke.
     */
    private fun relaunchFromList(pkg: String) {
        val app = mAppRepo.findByPackage(pkg)
            ?: mAppListCoordinator.getApps().firstOrNull { it.packageName == pkg }
        if (app != null) {
            mCurrentDashboardPkg = null // clear so onSendToDashboard doesn't bail early
            mCurrentDashboardApp = null
            runOnUiThread { onSendToDashboard(app) }
            return
        }
        // Not reachable in practice once the repository is consulted — the package was on the
        // cluster a moment ago, so the repository knows it. Kept as the honest last word: the app
        // has just been force-stopped and the cluster is empty, and a silent return would leave no
        // trace of why.
        AppLogger.w(TAG, "relaunchCurrentApp: pkg not found in repository or list — $pkg")
    }

    /** Original cluster — sendInfo(screenSize) + sendInfo(18) + sendInfo(0). */
    private fun originCluster() {
        btnRestoreCluster.isEnabled = false
        mNavCoordinator?.setStatusRestoringOrigin()
        mUsageTracker.trackStop(mCurrentDashboardPkg)

        val capturedClusterPkg = mCurrentDashboardPkg
        val capturedSecondPkg = mSplitController?.getSecondDashboardPkg()

        // Eagerly clear tracked cluster state (same rationale as restoreBydDashboard).
        mCurrentDashboardApp = null
        mCurrentDashboardPkg = null
        ClusterPrefs.setClusterPkg(this, null)
        ClusterPrefs.setClusterName(this, null)
        mAppListCoordinator.setCurrentPackage(null)

        FissionOrchestrator.stopAutoOrchestrator {
            continueOriginCluster(capturedClusterPkg, capturedSecondPkg)
        }
    }

    private fun continueOriginCluster(capturedClusterPkg: String?, capturedSecondPkg: String?) {
        // Same as continueRestoreBydDashboard: evictAllThen covers the tracked set itself.
        AppLogger.log(TAG, "originCluster() cmd=" + ClusterPrefs.getClusterType(this))

        mSessionTracker.evictAllThen(
            if (mServiceBound) mClusterService else null,
            capturedClusterPkg, capturedSecondPkg
        ) {
            AdbLocalClient.restoreOriginCluster(this, ClusterPrefs.getClusterType(this), null, object : AdbLocalClient.Callback {
                override fun onSuccess(report: String?) {
                    runOnUiThread {
                        if (mServiceBound && mClusterService != null) {
                            mClusterService!!.stopProjectionNoAdb()
                        }
                        restoreHomeIfRequested()
                        mSplitController?.clearSplitState()
                        updateDashboardStatus(null)
                        showAppList()
                        btnRestoreCluster.isEnabled = true
                        AppLogger.log(TAG, "Original cluster restored ✓")
                    }
                }

                override fun onError(error: String?) {
                    runOnUiThread {
                        btnRestoreCluster.isEnabled = true
                        Toast.makeText(applicationContext, getString(R.string.toast_origin_failed, error), Toast.LENGTH_LONG).show()
                        AppLogger.log(TAG, "originCluster FAILED: $error")
                        // The hazardous state was created by the EVICTION, which has already
                        // finished. Whether the separate cluster-restore call then succeeded says
                        // nothing about it — and that call has several documented flaky paths, so
                        // hanging the cover on its success would drop it exactly when a Stop is
                        // already going badly.
                        restoreHomeIfRequested()
                    }
                }
            })
        }
    }

    /** Updates the app label in the cluster panel (supports "App A  |  App B" in split mode). */
    private fun updateControlLabel() {
        val second = mSplitController?.getSecondDashboardApp()
        val label = if (mCurrentDashboardApp == null) null
        else if (second != null) "$mCurrentDashboardApp  |  $second"
        else mCurrentDashboardApp
        mClusterControlCoordinator?.setControlAppName(label)
    }

    // ---- Async loading of the app list ----

    private fun loadAppsAsync() {
        mAppRepo.loadApps(this) { apps ->
            if (isFinishing || isDestroyed) return@loadApps
            mAppListCoordinator.deliver(apps, true)
            // The list is now ready: run an auto-launch that was deferred because the
            // cluster connected before this async load finished (no-op otherwise).
            tryExecutePendingAutoLaunch()
        }
    }

    /**
     * Runs the configured auto-launch app once it is available in the app list.
     * The pending package is consumed ONLY when the app is found, so a cluster-connect
     * that races ahead of the async app-list load no longer drops the auto-launch:
     * [onClusterDisplayConnected] keeps the pending and [loadAppsAsync]'s callback retries it.
     * [onSendToDashboard] handles activation if the service/display is not up yet.
     */
    private fun tryExecutePendingAutoLaunch() {
        val targetPkg = mPendingAutoLaunchPkg ?: return
        if (isLayoutAutoStartRequested()) {
            AppLogger.d(TAG, "single-app auto-launch deferred: Layout auto-start owns startup")
            return
        }
        // Resolve from the FULL app list (favorites INCLUDED). The UI grid getApps()
        // excludes favorites into a separate strip, so a favorited auto-launch / boot-
        // projection app (e.g. a favorited Waze) was never found here and stayed
        // "deferred: app list not ready yet" forever — and thus never launched.
        val app = mAppRepo.findByPackage(targetPkg)
            ?: mAppListCoordinator.getApps().firstOrNull { it.packageName == targetPkg }
        if (app != null) {
            mPendingAutoLaunchPkg = null // consume only once the app is actually found
            AppLogger.i(TAG, "Executing pending auto-launch for $targetPkg")
            onSendToDashboard(app)
            return
        }
        AppLogger.i(TAG, "auto-launch $targetPkg deferred: app list not ready yet")
    }

    // ── Coordinator wiring ────────────────────────────────────────────────────

    private fun setupCoordinators() {
        mNavCoordinator = NavigationCoordinator(
            findViewById(R.id.view_status_dot),
            findViewById(R.id.tv_dashboard_status),
            ivNavLogo,
            findViewById(R.id.nav_hotspot),
            this
        )

        mMirrorCoordinator = MirrorCoordinator(
            clusterMirror, frameMirror, findViewById(R.id.tv_mirror_placeholder), this
        )

        val panelClusterControl = findViewById<LinearLayout>(R.id.panel_cluster_control)
        mFullscreenCoordinator = FullscreenMirrorCoordinator(
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
            this
        )

        mAppListCoordinator = AppListCoordinator(
            findViewById(R.id.rv_apps),
            findViewById(R.id.et_search_apps),
            findViewById(R.id.ll_favorites_strip),
            findViewById(R.id.ll_favorites_section),
            findViewById(R.id.btn_filter_all),
            findViewById(R.id.btn_filter_nav),
            findViewById(R.id.btn_filter_media),
            findViewById(R.id.btn_view_toggle),
            mAppRepo, this
        )

        mClusterControlCoordinator = ClusterControlCoordinator(
            panelClusterControl,
            findViewById(R.id.panel_controls_content),
            findViewById(R.id.btn_panel_toggle),
            findViewById(R.id.btn_toggle_resize),
            findViewById(R.id.tv_control_app_name),
            findViewById(R.id.btn_cluster_split),
            findViewById(R.id.btn_relaunch),
            this
        )

        // DL5 guard: hide the resize affordance if task resize is unsupported on this ROM.
        // isClusterTaskResizeSupported forks a shell on a cold cache (Platform.probeSetTaskWindowingMode,
        // ~200 ms typical, bounded at 1500 ms) and must NEVER be resolved on the UI thread — on a
        // first-launch / prefs-wipe cold start that would block the launcher's first frame while it
        // races the app-startup prime worker. Default the affordance to SHOWN and resolve the
        // (normally already-primed) cached value on a short-lived daemon worker; post the hide back
        // to the main thread only if the ROM is confirmed unsupported. isActivityAlive() guards
        // against a config-change recreate/destroy landing before the post runs.
        val resizeProbeCtx = applicationContext
        // Reach the Activity through a WeakReference so this fire-and-forget probe (which blocks
        // up to ~1.5s on a cold cache) does not strong-capture `this` and retain a destroyed
        // MainActivity + its view tree. Referencing runOnUiThread / isActivityAlive() /
        // mClusterControlCoordinator directly would have captured the instance.
        val selfRef = java.lang.ref.WeakReference(this)
        Thread({
            val supported = try {
                Platform.get().isClusterTaskResizeSupported(resizeProbeCtx)
            } catch (t: Throwable) {
                // Never downgrade UX on an unclassified probe failure — leave resize shown.
                AppLogger.w(TAG, "cluster-resize probe failed; leaving resize UI shown", t)
                true
            }
            if (!supported) {
                val act = selfRef.get()
                if (act != null) {
                    act.runOnUiThread {
                        if (!act.isActivityAlive()) return@runOnUiThread
                        act.mClusterControlCoordinator?.hideResizeIfUnsupported()
                        AppLogger.i(TAG, "Resize UI hidden: cluster task resize not supported on this ROM (DL5)")
                    }
                }
            }
        }, "resize-affordance-probe").apply { isDaemon = true }.start()

        mSplitController = SplitController(this)

        mPermissionBannerCoordinator = PermissionBannerCoordinator(
            findViewById(R.id.card_ime_a11y_banner),
            findViewById(R.id.card_hud_notif_banner),
            this
        )

        mUsageTracker = UsageTracker(this)

        mFissionCoordinator = FissionCoordinator(
            findViewById(R.id.ll_layout_carousel_section),
            findViewById(R.id.ll_layout_carousel),
            this
        )
        // Cluster-layout section: collapsed by default (keeps the live preview tall);
        // the header row toggles it open/closed on demand.
        mLayoutSectionBody = findViewById(R.id.layout_section_body)
        mLayoutSectionChevron = findViewById(R.id.iv_layout_chevron)
        findViewById<View>(R.id.layout_section_header).setOnClickListener {
            mLayoutSectionExpanded = !mLayoutSectionExpanded
            applyLayoutSectionCollapsed()
        }
        applyLayoutSectionCollapsed()

        mStatePollCoordinator = DisplayStatePollCoordinator(this)
        mTimeoutManager = ActivateTimeoutManager(this)
        mInsetApplicator = InsetAutoApplicator(this)
    }

    // ── AppListCoordinator.Host ───────────────────────────────────────────────

    override fun getSendListener(): AppListAdapter.OnSendToDashboardListener = this

    override fun onShowAppActions(app: AppInfo) = onShowActions(app)

    override fun onFirstLaunchTip() {
        Toast.makeText(applicationContext, getString(R.string.tooltip_tap_send), Toast.LENGTH_LONG).show()
    }

    // ── ClusterControlCoordinator.Host ────────────────────────────────────────

    override fun getCurrentDashboardPkg(): String? = mCurrentDashboardPkg

    override fun onSplitLayoutRequested(anchor: View) {
        mSplitController?.showSplitMenu(anchor)
    }

    override fun onRelaunchRequested() = relaunchCurrentApp()

    override fun onInsetChanged(h: Int, v: Int) {
        mInsetOverlay?.setInsets(h, v)
    }

    override fun onResizePanelToggled(visible: Boolean) {
        val overlay = mInsetOverlay ?: return
        if (visible) {
            refreshInsetOverlay()
            overlay.setOverlayVisible(true)
        } else {
            overlay.setOverlayVisible(false)
        }
    }

    // ── NavigationCoordinator.Host / MirrorCoordinator.Host (shared) ─────────

    override fun getContext(): Context = this

    override fun onShowOverflowMenu(anchor: View) {
        OverflowMenuHelper.show(anchor, this)
    }

    // ── MirrorCoordinator.Host ────────────────────────────────────────────────

    override fun getClusterServiceIfBound(): ClusterService? = if (mServiceBound) mClusterService else null

    /**
     * The surface daemon's binder, re-acquired if the cached one has died — AUD-009, last step.
     *
     * The five call sites that mishandled a dead surface binder are fixed, but two of them live in
     * [ClusterMirrorManager], which receives the binder as a parameter and therefore cannot forget
     * a dead reference on anyone's behalf. The reference it is handed comes from here, so this is
     * where the forgetting has to happen.
     *
     * Two ways to ask whether a binder is alive, and the difference is the failure mode this
     * finding is about. `isBinderAlive` is a local flag; on DiLink 3 the kernel's binderDied
     * notification is sometimes never delivered, so it keeps saying "alive" about a process that
     * is gone. Only `pingBinder` catches that — and it is a blocking round trip.
     * `ProxyKeeperService` learned the same lesson on the other daemon, but it runs on its own
     * heartbeat thread and can afford to block. THIS GETTER CANNOT, and an earlier version of it
     * did, which is the mistake this comment exists to stop repeating.
     *
     * `MirrorCoordinator` states the contract in its own class doc — every method on the main
     * thread — and both call sites honour it. Worse than "main thread occasionally": one of them
     * is reached from `clusterMirror.addOnLayoutChangeListener`, so it runs inside the view
     * traversal on every layout pass with a non-zero size. A daemon that is WEDGED rather than
     * cleanly dead does not fail a ping quickly; it blocks it. Blocking there blocks the traversal,
     * and the lifecycle callers (`onStop`, `onDestroy`) charge the same block against the
     * transition ANR budget.
     *
     * So liveness is established in two steps, neither of which blocks:
     *
     *  - [android.os.IBinder.isBinderAlive] is local and free. It answers correctly once the kernel
     *    has delivered `binderDied`, which is the ordinary case. If it says dead, the replacement
     *    comes from a ServiceManager lookup — reflection plus a lookup, no IPC to the daemon.
     *  - the silent death, where the notification never arrives and the flag keeps saying "alive",
     *    is checked with a real ping on a background thread. This call returns the cached binder
     *    immediately; if the ping comes back dead, the cache is dropped and republished, so the
     *    NEXT caller is correct. The current one may still transact against a corpse and take a
     *    DeadObjectException — which every one of these call sites now handles, because that is
     *    what the rest of AUD-009 was about.
     *
     * One stale attempt is the price of never blocking the UI thread. It is the right trade: the
     * attempt fails loudly and recovers, an ANR does not.
     *
     * Republishes to the input forwarder as well, so a recovered binder reaches the path that needs
     * it most without waiting for the next ACTION_DAEMON_READY broadcast.
     */
    override fun getSurfaceDaemonBinder(): IBinder? {
        val cached = mDaemonBinder ?: return null
        if (cached.isBinderAlive()) {
            checkSurfaceBinderLivenessAsync(cached)
            return cached
        }
        val fresh = DaemonBinderResolver.reacquireSurfaceBinder("getSurfaceDaemonBinder")
        adoptSurfaceBinder(fresh, "was dead")
        return fresh
    }

    /**
     * Confirms a seemingly-alive binder really is, without blocking the caller.
     *
     * `pingBinder` is the only thing that catches the silent death this whole finding is about, and
     * it is a blocking round trip. Off the main thread it costs nothing anyone can feel; on it, it
     * is an ANR waiting for a wedged daemon. Throttled by [DaemonBinderResolver], so a layout storm
     * cannot spawn a thread per pass.
     */
    private fun checkSurfaceBinderLivenessAsync(binder: IBinder) {
        if (mSurfaceLivenessCheckInFlight.getAndSet(true)) return
        Thread({
            try {
                if (binder.pingBinder()) return@Thread
                val fresh = DaemonBinderResolver.reacquireSurfaceBinder("silent-death")
                runOnUiThread {
                    // Only if nothing better arrived meanwhile — a broadcast may have republished.
                    if (mDaemonBinder === binder) adoptSurfaceBinder(fresh, "silently dead")
                }
            } catch (_: Throwable) {
            } finally {
                mSurfaceLivenessCheckInFlight.set(false)
            }
        }, "surface-binder-ping").start()
    }

    /** Main thread. Replaces the cached surface binder and tells the touch path about it. */
    private fun adoptSurfaceBinder(fresh: IBinder?, why: String) {
        mDaemonBinder = fresh
        if (mServiceBound) mClusterService?.getInputForwarder()?.setDaemonBinder(fresh)
        AppLogger.w(TAG, "surface binder " + why + " — "
                + (if (fresh != null) "re-acquired" else "dropped, waiting for the daemon"))
    }

    private val mSurfaceLivenessCheckInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onPreviewClicked() {
        // no-op: frameMirror touch is handled by clusterMirror.setOnTouchListener()
    }

    // ── FullscreenMirrorCoordinator.Host ─────────────────────────────────────

    override fun onMirrorShouldStop() {
        stopClusterMirror()
    }

    override fun onMirrorRestartAfterDelay() {
        mMirrorCoordinator?.recreateSurfaceAndRestart()
    }

    override fun setFullscreenImmersive(on: Boolean) {
        try {
            window.decorView.systemUiVisibility = if (on)
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            else
                View.SYSTEM_UI_FLAG_VISIBLE
        } catch (t: Throwable) {
            AppLogger.w(TAG, "setSystemUiVisibility failed: " + t.message)
        }
    }

    // ── SplitController.Host ──────────────────────────────────────────────────

    override fun getCurrentDashboardApp(): String? = mCurrentDashboardApp

    override fun setCurrentDashboardPkg(pkg: String?) {
        mCurrentDashboardPkg = pkg
    }

    override fun setCurrentDashboardApp(app: String?) {
        mCurrentDashboardApp = app
    }

    override fun onSplitStateChanged() {
        updateControlLabel()
        val split = mSplitController
        mClusterControlCoordinator?.setSplitActive(split != null && split.isInSplitMode())
    }

    // ── PermissionBannerCoordinator.Host ─────────────────────────────────────

    override fun isActivityAlive(): Boolean = !isFinishing && !isDestroyed
    // getContext() and startActivity(Intent) are already provided.

    // ── AppActionSheet.Host ───────────────────────────────────────────────────

    override fun getCurrentClusterPkg(): String? =
        if (this::mAppListCoordinator.isInitialized) mAppListCoordinator.getCurrentPackage() else null

    override fun getMainDisplayPkg(): String? = mMainDisplayPkg

    override fun getLayoutPkgs(): Set<String>? = FissionOrchestrator.getActiveLayoutPackages()

    /** Pushes the current fission-layout package set to the app list (indicators + actions). */
    private fun refreshLayoutPackages() {
        val packages = FissionOrchestrator.getActiveLayoutPackages()
        mAppListCoordinator.setLayoutPackages(packages)
        updateLayoutMirrorSwitcher()
        mMirrorCoordinator?.onLayoutTargetChanged()
    }

    private fun selectLayoutMirror(packageName: String) {
        val target = FissionOrchestrator.selectLayoutMirrorPackage(packageName) ?: return
        AppLogger.i(TAG, "Selected tactile Layout mirror: ${target.pkg}@${target.displayId}")
        updateLayoutMirrorSwitcher()
        showMirrorView()
        mMirrorCoordinator?.onLayoutTargetChanged()
    }

    private fun stepLayoutMirror(delta: Int) {
        val target = FissionOrchestrator.stepLayoutMirrorSelection(delta) ?: return
        AppLogger.i(TAG, "Stepped tactile Layout mirror: ${target.pkg}@${target.displayId}")
        updateLayoutMirrorSwitcher()
        showMirrorView()
        mMirrorCoordinator?.onLayoutTargetChanged()
    }

    private fun updateLayoutMirrorSwitcher() {
        val target = FissionOrchestrator.getSelectedLayoutMirrorTarget()
        if (target == null) {
            mLayoutMirrorSwitcher.visibility = View.GONE
            return
        }
        mLayoutMirrorSwitcher.visibility = View.VISIBLE
        mLayoutMirrorSelected.text = target.label
        val multiple = FissionOrchestrator.getActiveLayoutPackages().size > 1
        mLayoutMirrorPrev.visibility = if (multiple) View.VISIBLE else View.INVISIBLE
        mLayoutMirrorNext.visibility = if (multiple) View.VISIBLE else View.INVISIBLE
    }

    // ── OverflowMenuHelper.Host ───────────────────────────────────────────────

    override fun isAppListGridMode(): Boolean =
        this::mAppListCoordinator.isInitialized && mAppListCoordinator.isGridMode()

    override fun toggleAppListViewMode() {
        if (this::mAppListCoordinator.isInitialized) mAppListCoordinator.toggleViewMode()
    }

    override fun onOriginCluster() = originCluster()

    override fun showUsageStats() {
        mUsageTracker.showStatsDialog()
    }

    // ── DisplayStatePollCoordinator.Host ──────────────────────────────────────

    override fun getClusterPkg(): String? = mCurrentDashboardPkg
    override fun getLastLaunchTime(): Long = mLastLaunchTime
    override fun onClusterPkgDied() {
        clearClusterState()
        stopClusterMirror()
    }

    override fun onMainPkgDied() {
        mMainDisplayPkg = null
        ClusterPrefs.setMainPkg(this, null)
        mAppListCoordinator.setMainPackage(null)
    }

    override fun runOnMainThread(r: Runnable) = runOnUiThread(r)

    // ── ActivateTimeoutManager.Host ───────────────────────────────────────────

    override fun onActivateTimeout() {
        mWasManualActivation = false
        mNavCoordinator?.setStatusDisconnected()
        Toast.makeText(applicationContext, getString(R.string.toast_activate_timeout), Toast.LENGTH_LONG).show()
    }

    // ── InsetAutoApplicator.Host ──────────────────────────────────────────────

    override fun getCurrentPkg(): String? = mCurrentDashboardPkg

    companion object {
        private const val TAG = "BYDApp"
        private const val PREFS_NAME = ClusterPrefs.PREFS_NAME

        // Orphan sniffer kill must only run once per process lifetime (first cold start).
        private var sOrphanSnifferKillDone = false

        /** Shown at most once per process — avoids repeating on every onStart() cycle. */
        private var sAdbWarningShown = false
    }
}
