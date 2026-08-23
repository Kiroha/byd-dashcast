package com.byd.dashcast.cluster;
import com.byd.dashcast.BuildConfig;

import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.MainActivity;
import com.byd.dashcast.R;
import com.byd.dashcast.ime.KeyboardBridgeActivity;
import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.proxy.ShellGateway;
import com.byd.dashcast.ui.settings.SettingsActivity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.view.Display;

import com.byd.dashcast.cluster.mirror.ClusterInputForwarder;
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager;
import com.byd.dashcast.cluster.display.ClusterDisplayInfo;
import com.byd.dashcast.cluster.display.ClusterGeometryPolicy;
import com.byd.dashcast.cluster.display.ClusterDisplayRegistry;
import com.byd.dashcast.cluster.display.ClusterManager;
import com.byd.dashcast.cluster.display.DashboardDisplayHelper;
import com.byd.dashcast.cluster.display.DashboardLauncher;
import com.byd.dashcast.domain.cluster.ProjectionStateProvider;
import com.byd.dashcast.infrastructure.launch.PlatformAdaptiveAppLauncher;
import com.byd.dashcast.infrastructure.task.AdbLocalTaskFinder;
import com.byd.dashcast.infrastructure.task.AmTaskFinder;
import com.byd.dashcast.infrastructure.task.ChainedTaskFinder;
import com.byd.dashcast.infrastructure.task.TaskFinder;
import com.byd.dashcast.infrastructure.task.TaskLocation;
import com.byd.dashcast.infrastructure.task.TypedProxyTaskFinder;
import com.byd.dashcast.platform.Platform;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.util.concurrent.LifecycleGate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ClusterService — Foreground Service that maintains projection on the cluster
 * independently of the MainActivity lifecycle.
 *
 * <h3>Architecture changes (refactor v1.5)</h3>
 * <ul>
 *   <li>{@link #findRunningTaskId(String)} now delegates to {@link ChainedTaskFinder}
 *       (AM → ProxyDaemon → AdbLocal), removing 140 lines of inline strategy code.
 *   <li>Implements {@link ProjectionStateProvider} so FissionActivity / FissionOrchestrator
 *       no longer depend on the static field {@code sIsRunning} or a direct class reference.
 * </ul>
 *
 * Zero regression: all runtime logic (IATM reflection, shell commands, timing, DPI settle,
 * DL5 guards, wm overscan, cleanFissionStacks, enforceTaskOnDisplay) is preserved verbatim.
 *
 * <h3>Both daemons run through here</h3>
 * Besides the ProxyDaemon commands above, this service drives the <b>SURFACE</b> daemon
 * ({@link com.byd.dashcast.proxy.daemon.SurfaceDaemon}): it owns the {@link ClusterMirrorManager}
 * and the {@link com.byd.dashcast.cluster.mirror.ClusterInputForwarder}, and
 * {@link #stopProjectionNoAdb()} / {@link #onDestroy()} are the two paths that tear the mirror
 * down. Their binder comes from
 * {@link com.byd.dashcast.proxy.DaemonBinderResolver#surfaceDaemonBinder()} — never from
 * {@code ProxyClient.getProxyDaemonBinder()}; see the boundary rule on
 * {@link com.byd.dashcast.proxy.daemon.SurfaceDaemon}.
 */
@SuppressWarnings("deprecation")
public class ClusterService extends Service
        implements DashboardDisplayHelper.Listener, ProjectionStateProvider {

    private static final String TAG = "ClusterService";

    // v1.3.7-beta — probe result for IActivityTaskManager.moveTaskToDisplay availability.
    //   null = unknown (try), TRUE = available, FALSE = stripped (skip reflection)
    private static volatile Boolean sMoveTaskToDisplayAvailable = null;

    /**
     * Whether IActivityTaskManager.moveTaskToDisplay is usable on this ROM.
     * Returns false only once the reflection probe has positively found it stripped
     * (DiLink3.0); null/unknown and TRUE both report true.
     *
     * Callers use this to decide whether the app already on the cluster must be stopped
     * before launching a new one: when the move is stripped, the fallback launch path
     * cannot reparent the previous app, so a second app lands in split-screen on the
     * main display (INC-20260621-130238).
     */
    public boolean isMoveTaskToDisplaySupported() {
        // Resolved on demand, by the reader that actually needs the answer. Previously the flag was
        // only ever latched as a SIDE EFFECT of a move attempt, and the corpus shows 82% of real
        // sessions got it from the deferred re-anchor alone — the very call that now goes through
        // the daemon instead. A fire-and-forget prime at startup would have left a silent window
        // (and a race); a cheap interface lookup here has neither.
        Boolean known = sMoveTaskToDisplayAvailable;
        if (known == null) known = resolveMoveTaskToDisplaySupport();
        return !Boolean.FALSE.equals(known);
    }

    private static final String CHANNEL_ID = "cluster_projection";
    private static final int    NOTIF_ID   = 1;

    public static volatile boolean sIsRunning = false;
    public static boolean isRunning() { return sIsRunning; }

    private static volatile ClusterService sInstance = null;
    public static ClusterService getInstance() { return sInstance; }

    private static boolean sResizeUnsupportedLogged = false;

    // ── Listener for MainActivity ───────────────────────────────────────────
    public interface Listener {
        void onClusterDisplayConnected(Display display, int displayId);
        void onClusterDisplayDisconnected();
    }

    // ── Binder ──────────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public ClusterService getService() { return ClusterService.this; }
    }

    private final IBinder mBinder = new LocalBinder();

    // ── State ───────────────────────────────────────────────────────────────
    private DashboardDisplayHelper mDisplayHelper;
    private DashboardLauncher      mLauncher;
    private ClusterMirrorManager   mMirrorManager;
    private ClusterInputForwarder  mInputForwarder;
    private Listener               mListener;
    private boolean                mProjectionActive = false;
    private volatile boolean       mDestroyed        = false;
    private final LifecycleGate    mOperationGate    = new LifecycleGate();
    private LaunchCallback         mPendingDashboardCallback;
    private Runnable               mPendingLaunchRunnable;

    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private PendingIntent mNotifPi;

    // ── Strategy objects (injected in onCreate) ─────────────────────────────
    private TaskFinder   mTaskFinder;

    // ── Background executor for task-move operations ────────────────────────
    private static final ExecutorService sMoveTaskExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "move-task-thread");
        t.setDaemon(true);
        return t;
    });

    // ── Separate executor for diagnostic-only post-launch verification ──────
    // verifyClusterDisplayState sleeps ~1.5s before a dumpsys; it must NOT run on
    // sMoveTaskExecutor (the single move-task worker) or it serializes ahead of a
    // pending moveTaskToDisplay/eviction, delaying app return-to-display-0. It has
    // no bearing on the launch outcome, so it runs off the critical path here.
    private static final ExecutorService sDiagExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cluster-diag-thread");
        t.setDaemon(true);
        return t;
    });

    private static final String PKG_FORCE_FRESH_LAUNCH = "com.telenav.app.arp";

    /** Set by BootReceiver when it starts us at boot: also auto-launch the configured app. */
    public static final String EXTRA_BOOT_AUTOLAUNCH = "boot_autolaunch";
        /** Re-arms a stopped-but-still-bound service before restarting native projection. */
        public static final String ACTION_RESTART_PROJECTION =
            "com.byd.dashcast.action.RESTART_CLUSTER_PROJECTION";
    /** The app this service auto-launched at boot (headless), so MainActivity doesn't relaunch it
     *  and force-stop a running nav. Null until a boot auto-launch succeeds. */
    public static volatile String sBootLaunchedPkg = null;
    /** Package to auto-launch once the cluster display connects (armed in onStartCommand at boot). */
    private volatile String mBootAutoLaunchPkg = null;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning = true;
        sInstance  = this;

        mDisplayHelper  = new DashboardDisplayHelper(this, this);
        mLauncher       = new DashboardLauncher(this);
        mMirrorManager  = new ClusterMirrorManager();
        mInputForwarder = new ClusterInputForwarder(this);

        // v1.6.147 — PULL the daemon Binder instead of waiting to be pushed one.
        // When the SurfaceDaemon survives an ACC off/on cycle it is REUSED, so it is already
        // registered in ServiceManager before MainActivity binds this service. The push path in
        // MainActivity resolves the binder first, finds mClusterService still null and drops it,
        // and no ACTION_DAEMON_READY broadcast follows a reused (never re-spawned) daemon — so
        // setDaemonBinder was never called and every touch fell back to the unprivileged direct
        // path, silently reaching nothing (INC-20260724-102136). Pulling here removes the
        // Activity-ordering dependency entirely; it is a cheap local ServiceManager lookup.
        IBinder reusedDaemon = com.byd.dashcast.proxy.DaemonBinderResolver.surfaceDaemonBinder();
        if (reusedDaemon != null) {
            mInputForwarder.setDaemonBinder(reusedDaemon);
        }

        // Strategy wiring: app AM → typed daemon ATM → daemon dumpsys → AdbLocal dumpsys.
        mTaskFinder = new ChainedTaskFinder(
                new AmTaskFinder(this),
                new TypedProxyTaskFinder(),
                new com.byd.dashcast.infrastructure.task.ProxyTaskFinder(),
                new AdbLocalTaskFinder(this));

        AdbLocalClient.startMirrorDaemon(this);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_cluster_initializing)));
        AppLogger.log(TAG, "ClusterService created — starting native projection");
        mProjectionActive = true;

        if (AdbLocalClient.isDiLink5Safe(this)) {
            final String check = "v=$(settings get global force_resizable_activities); "
                    + "if [ \"$v\" = \"1\" ]; then "
                    + "settings put global force_resizable_activities 0 2>&1; "
                    + "echo RESET; else echo OK=$v; fi";
            ShellGateway.execShellWithResult(this, check, new AdbLocalClient.Callback() {
                @Override public void onSuccess(String out) {
                    AppLogger.i(TAG, "DL5 force_resizable_activities cleanup → " + out.trim());
                }
                @Override public void onError(String err) {
                    AppLogger.e(TAG, "DL5 force_resizable_activities cleanup ERROR: " + err);
                }
            });
            KeyboardBridgeActivity.ensureClusterImeEnabled(this);
        }
        if (BuildConfig.DEBUG) {
            AdbLocalClient.dumpSignatureAndPermissions(this);
        }

        // Load any persisted single-OS verdict BEFORE startNativeProjection() so its guard fires
        // immediately on a car already known to have no projectable cluster. Also read the prop
        // via the shell (uid 2000 CAN read ro.build.system.fission_single_os even when the app's
        // own in-process SystemProperties.get returns "" — SELinux prop context, INC-20260715-140107),
        // so a first-seen single-OS DL3 gets flagged for next time without waiting on a doomed cycle.
        Platform.primeClusterSingleOs(this);
        if (Platform.get().isDiLink3(this)) {
            AdbLocalClient.executeShellWithResult(this,
                    "getprop ro.build.system.fission_single_os",
                    new AdbLocalClient.Callback() {
                        @Override public void onSuccess(String out) {
                            if (out != null && "1".equals(out.trim())) {
                                Platform.noteClusterSingleOsDetected(ClusterService.this);
                                AppLogger.i(TAG, "shell getprop: DL3 single-OS cluster (fission_single_os=1) — projection unsupported");
                                // If this resolved only AFTER the first activation already timed
                                // out, correct the notification now (it would otherwise read the
                                // generic "disconnected" until the next boot).
                                //
                                // Guarded: this is a shell round-trip, so it can land after the
                                // user has already stopped projection — and updateNotification
                                // would then re-post NOTIF_ID for a session that is over, putting
                                // an ongoing notification back on screen with nothing behind it.
                                // mProjectionActive covers the common stopped-but-still-bound
                                // case that mDestroyed misses; it is cleared in
                                // stopProjectionNoAdb() before the cancel/stopSelf.
                                mMainHandler.post(() -> {
                                    if (mDestroyed || !mProjectionActive) return;
                                    updateNotification(
                                        getString(R.string.dl3_singleos_cluster_unsupported_title));
                                });
                            }
                        }
                        @Override public void onError(String err) { /* stays unflagged; projection proceeds (fail-open) */ }
                    });
        }
        startNativeProjection();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESTART_PROJECTION.equals(intent.getAction())) {
            restartProjection();
        }
        // Headless boot auto-launch: BootReceiver starts us with EXTRA_BOOT_AUTOLAUNCH so the
        // configured app is launched onto the cluster at startup WITHOUT the user opening DashCast
        // (INC-20260716-091016). Arm it here (intent is delivered to onStartCommand); the actual
        // launch happens in onDashboardDisplayConnected once the cluster display is up.
        if (intent != null && intent.getBooleanExtra(EXTRA_BOOT_AUTOLAUNCH, false)) {
            // Mirror MainActivity's guard: when a Layout auto-start is configured, IT owns startup
            // launching (activates projection + launches every bound app). A headless single-app
            // launch here would create a classic projection first, and the layout's ensureDaemon()
            // would then abort with "Daemon unavailable". So skip the single-app path for layout users.
            if (isLayoutAutoStartRequested()) {
                AppLogger.i(TAG, "boot auto-launch: layout auto-start owns startup — headless single-app launch skipped");
            } else {
                String pkg = ClusterPrefs.getAutoLaunchPkg(this);
                if (pkg != null && !pkg.isEmpty()) {
                    mBootAutoLaunchPkg = pkg;
                    AppLogger.i(TAG, "boot auto-launch armed (headless) → " + pkg);
                } else {
                    AppLogger.i(TAG, "boot auto-launch requested but no app configured");
                }
            }
        }
        return START_STICKY;
    }

    /** A requested Layout auto-start owns startup, so the single-app path must stand down. */
    private boolean isLayoutAutoStartRequested() {
        try {
            return com.byd.dashcast.fission.FissionOrchestrator.isAutoStartRequested(this);
        } catch (Throwable t) {
            return false; // fail-open to the single-app path (never block a boot launch on a probe error)
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mListener = null;
        return false;
    }

    @Override
    public void onDestroy() {
        mOperationGate.invalidate();
        mDestroyed = true;
        super.onDestroy();
        sIsRunning = false;
        if (sInstance == this) sInstance = null;
        mListener  = null;
        // NOTE: the rolling screenshots are deliberately NOT wiped here — a tester often stops a
        // broken projection and THEN files the report, so the shots must survive the stop. They
        // self-clean via the recorder's max-age prune (runs on the keeper heartbeat even when idle)
        // and after a send. See ClusterShotRecorder.
        if (mPendingDashboardCallback != null) {
            LaunchCallback pending = mPendingDashboardCallback;
            mPendingDashboardCallback = null;
            pending.onResult(false);
        }
        mMainHandler.removeCallbacksAndMessages(null);
        try {
            com.byd.dashcast.cluster.dpi.ClusterDpiManager.restore(
                    this, mDisplayHelper.getKnownClusterDisplayId());
        } catch (Throwable t) {
            AppLogger.w(TAG, "DPI restore (onDestroy) failed: " + t.getMessage());
        }
        mMirrorManager.release();
        if (mProjectionActive) {
            mDisplayHelper.stop();
        }
        // Same reason as stopProjectionNoAdb: the registry is process-wide and outlives this
        // service, so a geometry left behind here would be picked up by the next mirror attempt
        // even though no projection is running. No-op on DL3/DL5.
        ClusterDisplayRegistry.clear();
        AppLogger.log(TAG, "ClusterService destroyed");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProjectionStateProvider
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean isProjectionActive() {
        return mProjectionActive;
    }

    @Override
    public void stopProjectionIfActive(Runnable onStopped) {
        if (mProjectionActive) {
            stopProjectionNoAdb();
        }
        if (onStopped != null) {
            mMainHandler.post(onStopped);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API (called from MainActivity via the binder)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * DL3 with {@code ro.build.system.fission_single_os==1}: the instrument cluster is rendered
     * natively (Qt/fission) and there is NO projectable Android display — AutoContainer has no
     * native backend ("no AutoContainerNative"), so the activation sequence just churns for 12 s
     * per cycle forever (this is what made "auto-start" appear broken; INC-20260701-083007).
     * Skip the activation entirely on these cars. STRICT + safe: a normal 1-for-2 DL3
     * ({@code ==0}) and DL5/DL5.1 ({@code isDiLink3()==false}) are NOT affected; fail-open if the
     * prop can't be read.
     */
    private boolean isDl3SingleOsFission() {
        return Platform.get().isDiLink3(this) && Platform.isClusterSingleOs();
    }

    /**
     * DiLink 4.0 where the cluster display is invisible to BOTH the app process and the
     * uid-2000 daemon. The OEM patched {@code DisplayManagerService.getDisplayIdsInternal} with
     * an app whitelist DashCast is not on, which is why the app process alone proves nothing —
     * so this is only true once {@link ClusterManager} recorded that a SUCCESSFUL daemon
     * {@code dumpsys display} also listed no cluster display. A shell that merely failed to
     * answer leaves this false and the generic "disconnected" message stands.
     *
     * <p>STRICT: gated on {@link Platform#isDiLink4}, and {@code isDiLink3()} is defined as
     * "not DL2/DL4/DL5" — DL3 and DL5 can never reach this branch.
     */
    private boolean isDl4ProjectionBlocked() {
        return Platform.get().isDiLink4(this) && ClusterManager.isDl4ProjectionUnavailable();
    }

    private void startNativeProjection() {
        if (isDl3SingleOsFission()) {
            AppLogger.w(TAG, "DL3 single-OS fission (fission_single_os=1) — no projectable cluster display; skipping AutoContainer activation");
            updateNotification(getString(R.string.dl3_singleos_cluster_unsupported_title));
            return;
        }
        AppLogger.i(TAG, "Starting cluster projection (native)...");
        mDisplayHelper.start();
    }

    /**
     * Set when the cluster display disconnects with no listener attached, so the edge can be
     * replayed on re-attach. Volatile: written from the display callback thread, read in
     * setListener on the main thread.
     */
    private volatile boolean mMissedDisconnect = false;

    public void setListener(Listener listener) {
        mListener = listener;
        int knownId = mDisplayHelper.getKnownClusterDisplayId();
        // Replay a disconnect that happened while nobody was listening.
        //
        // MainActivity detaches in onStop, which is the app's NORMAL state during projection --
        // the user sends an app to the cluster and then leaves DashCast. If the cluster display
        // goes away in that window (ACC off/on, OEM teardown, activation timeout) the callback at
        // onDashboardDisplayDisconnected was simply dropped: nothing latched it, and this method
        // only ever replayed the CONNECTED edge. The Activity came back still showing the app as
        // live on a display that no longer exists -- green state, "<app> -> Cluster", mirror
        // button, and a mirror restart aimed at a dead display. Worse, ClusterPrefs still recorded
        // that package as the cluster app, and that persisted value is what the next successful
        // connect restores, so the lie outlived the process.
        //
        // Nothing automatic corrected it. The five other places that clear the persisted package
        // are all user-initiated (send-to-main, kill, clear, restore-BYD, origin-cluster), and
        // onStart's re-sync is one-sided by construction: `if (curDispId > 0)`, no else.
        if (listener != null && mMissedDisconnect && knownId <= 0) {
            mMissedDisconnect = false;
            AppLogger.i(TAG, "setListener: replaying cluster disconnect missed while detached");
            listener.onClusterDisplayDisconnected();
            return;
        }
        if (knownId > 0 && mListener != null) {
            // A disconnect followed by a reconnect while detached nets out to "still up" -- do not
            // deliver a spurious off-state on top of the connected replay below.
            mMissedDisconnect = false;
            Display d = null;
            try {
                android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
                if (dm != null) d = dm.getDisplay(knownId);
            } catch (Exception e) {
                AppLogger.w(TAG, "getDisplay(" + knownId + ") failed: " + e.getMessage());
            }
            if (d != null) {
                mListener.onClusterDisplayConnected(d, knownId);
            } else if (ClusterDisplayRegistry.forDisplayId(knownId) != null) {
                // DiLink 4.0: dm.getDisplay(knownId) is blocked by the same OEM whitelist that
                // hides the id list, so this branch used to DROP the callback and MainActivity
                // never learned the cluster was up after an Activity re-create (rotation, back
                // from another app) — the mirror and the green state stayed off for the rest of
                // the session. The id IS valid (the daemon resolved it) and Listener already
                // declares Display as nullable, so deliver it. Registry is null on DL3/DL5.
                AppLogger.i(TAG, "setListener: no Display object for id=" + knownId
                        + " (daemon-resolved cluster) — notifying with null Display");
                mListener.onClusterDisplayConnected(null, knownId);
            }
        }
    }

    public DashboardLauncher        getLauncher()       { return mLauncher; }
    public ClusterMirrorManager     getMirrorManager()  { return mMirrorManager; }
    public ClusterInputForwarder    getInputForwarder() { return mInputForwarder; }
    public int                      getDisplayId()      { return mDisplayHelper.getKnownClusterDisplayId(); }

    public interface LaunchCallback {
        void onResult(boolean success);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task finding (now via ChainedTaskFinder)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Finds the taskId of the running task for {@code packageName}.
     * Must be called from a background thread.
     *
    * Delegates to {@link ChainedTaskFinder}: AM → typed daemon ATM → daemon dumpsys → AdbLocal.
     * Returns -1 if no task is found.
     */
    public int findRunningTaskId(String packageName) {
        try {
            int id = mTaskFinder.findTaskId(packageName);
            return (id == TaskFinder.NOT_FOUND) ? -1 : id;
        } catch (TaskFinder.TaskFinderException e) {
            AppLogger.w(TAG, "findRunningTaskId failed for " + packageName + ": " + e.getMessage());
            return -1;
        }
    }

    // Preserved static helpers — still used by callers that have direct access to dump strings.
    static int parseTaskIdFromDumpsysRecents(String dump, String packageName) {
        return com.byd.dashcast.infrastructure.task.ProxyTaskFinder.parseFromRecents(dump, packageName);
    }
    static int parseTaskIdFromDumpsysActivities(String dump, String packageName) {
        return com.byd.dashcast.infrastructure.task.ProxyTaskFinder.parseFromActivities(dump, packageName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task reparenting
    // ─────────────────────────────────────────────────────────────────────────

    public void moveTaskToDisplay(final String packageName, final int targetDisplayId,
                                   final LaunchCallback callback) {
        moveTaskToDisplayInternal(packageName, targetDisplayId, callback);
    }

    /**
     * Record, permanently, that this car has the small 1280x480 cluster panel — the one that drops
     * into its degraded "simple mode" if a larger shape preset is ever sent to it.
     *
     * <p><b>Second chance, not the primary reading.</b> This runs after activation, so on a car
     * where the ADAS fix has just forced the 12.3" shape it sees 1920x720 and correctly declines to
     * latch — which is why v1.8.27, where this was the ONLY latch site, failed to fire on exactly
     * the cars it protects. {@code ClusterManager.activateClusterDisplay} now latches from the
     * display it already holds BEFORE sending anything; this site still earns its place for the
     * DiLink 4 path, where the app cannot enumerate the display at all and the geometry only
     * arrives later via the uid-2000 daemon's {@code dumpsys display} parse.
     *
     * <p>Latching rather than re-reading is the whole point: the damage is self-concealing, because
     * a panel already pushed to 1920x720 reports 1920x720 from then on. One sighting is enough and
     * it has to outlive the process.
     */
    private void latchPanelGeometry(int width, int height) {
        if (!ClusterGeometryPolicy.isSmallPanelGeometry(width, height)) return;
        if (ClusterPrefs.isSmallClusterPanelLatched(this)) return;
        ClusterPrefs.latchSmallClusterPanel(this);
        AppLogger.i(TAG, "small cluster panel observed (" + width + "x" + height + ") — latched; "
                + "shape presets 30/31 will be refused on this car from now on");
    }

    /**
     * Resolve once, at service start, whether this ROM still has
     * {@code IActivityTaskManager.moveTaskToDisplay}.
     *
     * <p>Used to be a side effect: the deferred re-anchor was, on DiLink 3, the call that happened
     * to reach the reflection first and latch the flag to FALSE. Routing that re-anchor through the
     * daemon removed the side effect, and with it the only primer on a path where every launch goes
     * through {@code fallbackLaunch} (a cold app has no task, so the reflection is never reached).
     * The flag then stayed {@code null}, {@link #isMoveTaskToDisplaySupported()} kept answering
     * "supported", and {@code MainActivity}'s DiLink-3 workaround — force-stop the previous cluster
     * app before launching the next — never armed. The second app would land split-screen on the
     * main display: exactly the {@code ActivityStack.getBounds} NPE of INC-20260621-130238 that the
     * workaround exists to prevent. Caught by adversarial review, not by a test.
     *
     * <p>A lookup, never an invoke — it cannot move or disturb anything.
     */
    private static Boolean resolveMoveTaskToDisplaySupport() {
        Boolean known = sMoveTaskToDisplayAvailable;
        if (known != null) return known;
        try {
            // The INTERFACE, not a live binder. An earlier version resolved
            // ActivityTaskManager.getService() and inspected the returned Stub$Proxy, which asks a
            // question about a class through an object: getService() can return null before
            // activity_task is registered (NPE, answered "inconclusive" for a reason unrelated to
            // the method), can block on ServiceManager, and forced the whole probe onto a worker
            // thread — which is the only reason there was ever a race to reason about. The AIDL
            // method is a property of the interface; verified against this ROM's own decompiled
            // framework.jar, where IActivityTaskManager declares no moveTaskToDisplay at all.
            Class.forName("android.app.IActivityTaskManager")
                    .getMethod("moveTaskToDisplay", int.class, int.class);
            sMoveTaskToDisplayAvailable = Boolean.TRUE;
            return Boolean.TRUE;
        } catch (NoSuchMethodException nsme) {
            // Means "unreachable from this process" — stripped by the OEM, OR hidden-API filtered,
            // which surfaces identically because Android's enforcement makes a blocked member
            // invisible to reflection (see the 1.8.24 hidden-API work: the bypass ships in the
            // DAEMON process, not this one). Both are the same verdict for every consumer of this
            // flag: the in-process fallback cannot reparent a task either way. On DiLink 3 the
            // daemon dump settles which it is — genuine absence.
            sMoveTaskToDisplayAvailable = Boolean.FALSE;
            AppLogger.i(TAG, "moveTaskToDisplay unreachable on this ROM — launcher fallback will "
                    + "be used, and the stop-previous-app guard is armed");
            return Boolean.FALSE;
        } catch (Throwable t) {
            // ClassNotFoundException on a ROM without the interface at all, or a LinkageError.
            // Genuinely inconclusive: leave the flag null so the NEXT reader probes again rather
            // than latching a guess for the life of the process.
            AppLogger.d(TAG, "moveTaskToDisplay probe inconclusive ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ") — will retry");
            return null;
        }
    }

    /**
     * Deferred post-launch re-anchor: 2.5 s after a launch, make sure the task really is on the
     * cluster, because AOSP sometimes places it on display 0 anyway.
     *
     * <p>Looks before it acts, and moves through the uid-2000 daemon rather than in-process
     * reflection. The old implementation called {@code IActivityTaskManager.moveTaskToDisplay}
     * from the app process; that method does not exist on DiLink 3 (the OEM stripped it), so it
     * threw {@code NoSuchMethodException}, logged a WARN claiming a launcher fallback that the
     * {@code enforceOnly} branch never actually ran, and gave up — the re-anchor was a silent
     * no-op on the platform that needs it most. The daemon has a working path
     * ({@code moveAndResize} → {@code setDisplayToSingleTaskInstance} + stack move), proven on
     * DL3 in every {@code launchAndForce} transcript.
     *
     * <p>The probe is what keeps this cheap: when the daemon's own post-launch watchdog has
     * already done the job — the normal case — the task is found on the right display and nothing
     * is sent at all.
     */
    public void enforceTaskOnDisplay(final String packageName, final int targetDisplayId) {
        if (packageName == null || packageName.isEmpty() || targetDisplayId <= 0) return;
        if (PKG_FORCE_FRESH_LAUNCH.equals(packageName)) {
            AppLogger.d(TAG, "enforceTaskOnDisplay: skip force-fresh-launch pkg " + packageName);
            return;
        }
        final LifecycleGate.Token operation = mOperationGate.capture();
        sMoveTaskExecutor.execute(() -> {
            if (!operation.isValid()) return;
            TaskLocation location;
            try {
                location = ProxyClient.findTaskLocationForPackage(packageName);
            } catch (Throwable t) {
                // A failed probe says nothing about where the task is; acting on that would be the
                // mistake TaskLocation exists to prevent.
                AppLogger.d(TAG, "enforceTaskOnDisplay: probe failed for " + packageName
                        + " (" + t.getClass().getSimpleName() + ") — leaving it alone");
                return;
            }
            if (!operation.isValid()) return;
            switch (location.matchDisplay(targetDisplayId)) {
                case ON_EXPECTED_DISPLAY:
                    AppLogger.d(TAG, "enforceTaskOnDisplay: " + packageName
                            + " already on display " + targetDisplayId + " — nothing to do");
                    return;
                case ABSENT:
                    AppLogger.d(TAG, "enforceTaskOnDisplay: no task yet for " + packageName);
                    return;
                case UNKNOWN:
                    AppLogger.d(TAG, "enforceTaskOnDisplay: location unknown for " + packageName
                            + " — leaving it alone");
                    return;
                default:
                    break; // ON_OTHER_DISPLAY — the case this method exists for.
            }
            int w = (mInputForwarder != null) ? mInputForwarder.getClusterWidth() : 0;
            int h = (mInputForwarder != null) ? mInputForwarder.getClusterHeight() : 0;
            if (w <= 0) w = 1920;
            if (h <= 0) h = 720;
            AppLogger.i(TAG, "enforceTaskOnDisplay: " + packageName + " is on display "
                    + location.getDisplayId() + ", re-anchoring to " + targetDisplayId
                    + " via daemon");
            try {
                String log = ProxyClient.moveAndResize(packageName, targetDisplayId, 0, 0, w, h);
                AppLogger.i(TAG, "enforceTaskOnDisplay result:\n"
                        + (log == null || log.isEmpty() ? "(empty)" : log));
            } catch (Throwable t) {
                AppLogger.w(TAG, "enforceTaskOnDisplay: daemon move failed for " + packageName
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    public interface TaskLocationCallback {
        void onResult(TaskLocation location);
    }

    /**
     * Async, side-effect-free task-location query. This deliberately uses the typed daemon ATM
     * verb instead of the legacy finder chain: transport/reflection failure must remain UNKNOWN,
     * never collapse to ABSENT and trigger a destructive navigation relaunch.
     */
    public void findPackageLocation(final String packageName, final TaskLocationCallback callback) {
        if (callback == null) return;
        final LifecycleGate.Token operation = mOperationGate.capture();
        sMoveTaskExecutor.execute(() -> {
            if (!operation.isValid()) return;
            TaskLocation location;
            try {
                location = ProxyClient.findTaskLocationForPackage(packageName);
            } catch (Throwable t) {
                AppLogger.w(TAG, "findPackageLocation unknown for " + packageName
                        + ": " + t.getMessage());
                location = TaskLocation.unknown();
            }
            if (!operation.isValid()) return;
            final TaskLocation result = location;
            mMainHandler.post(() -> {
                if (!operation.isValid()) return;
                callback.onResult(result);
            });
        });
    }

    private void moveTaskToDisplayInternal(final String packageName, final int targetDisplayId,
                                            final LaunchCallback callback) {
        final LifecycleGate.Token operation = mOperationGate.capture();
        if (PKG_FORCE_FRESH_LAUNCH.equals(packageName) && targetDisplayId > 0) {
            AppLogger.i(TAG, "moveTaskToDisplay: force fresh launch for " + packageName);
            fallbackLaunch(packageName, targetDisplayId, callback, operation);
            return;
        }

        sMoveTaskExecutor.execute(() -> {
            if (!operation.isValid()) return;
            try {
                int taskId = findRunningTaskId(packageName);
                if (!operation.isValid()) return;
                if (taskId == -1) {
                    AppLogger.w(TAG, "moveTaskToDisplay: no task for " + packageName + " → fallback");
                    fallbackLaunch(packageName, targetDisplayId, callback, operation);
                    return;
                }

                if (Boolean.FALSE.equals(sMoveTaskToDisplayAvailable)) {
                    AppLogger.d(TAG, "moveTaskToDisplay: method unavailable on ROM → fallback launch");
                    if (!operation.isValid()) return;
                    fallbackLaunch(packageName, targetDisplayId, callback, operation);
                    return;
                }

                Class<?> atmClass  = Class.forName("android.app.ActivityTaskManager");
                Object   iatm      = atmClass.getMethod("getService").invoke(null);
                Class<?> iAtmClass = iatm.getClass();
                if (!operation.isValid()) return;
                iAtmClass.getMethod("moveTaskToDisplay", int.class, int.class)
                        .invoke(iatm, taskId, targetDisplayId);
                AppLogger.i(TAG, "moveTaskToDisplay taskId=" + taskId
                        + " → display=" + targetDisplayId + " OK");

                if (targetDisplayId > 0) {
                    try { Thread.sleep(300); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!operation.isValid()) return;
                    // WINDOWING_MODE_FREEFORM = 5
                    try {
                        iAtmClass.getMethod("setTaskWindowingMode",
                                int.class, int.class, boolean.class)
                                .invoke(iatm, taskId, 5, true);
                        AppLogger.i(TAG, "setTaskWindowingMode(FREEFORM) OK");
                    } catch (Exception e) {
                        AppLogger.w(TAG, "setTaskWindowingMode: " + e.getMessage());
                    }
                    // Fill the panel post-move (v1.8.2 — no more inset margins; a per-app
                    // hand-drawn rectangle is re-applied afterwards by InsetAutoApplicator).
                    try {
                        if (!operation.isValid()) return;
                        int cw = (mInputForwarder != null) ? mInputForwarder.getClusterWidth()  : 1920;
                        int ch = (mInputForwarder != null) ? mInputForwarder.getClusterHeight() :  720;
                        if (cw <= 0) cw = 1920;
                        if (ch <= 0) ch =  720;
                        Rect bounds = new Rect(0, 0, cw, ch);
                        iAtmClass.getMethod("resizeTask",
                                int.class, Rect.class, int.class)
                                .invoke(iatm, taskId, bounds, 1 /* RESIZE_MODE_FORCED */);
                        AppLogger.i(TAG, "resizeTask " + bounds + " OK");
                    } catch (Exception e) {
                        AppLogger.w(TAG, "resizeTask: " + e.getMessage());
                    }
                }

                mMainHandler.post(() -> {
                    if (!operation.isValid()) return;
                    if (callback != null) callback.onResult(true);
                });

            } catch (Exception e) {
                boolean stripped = (e instanceof NoSuchMethodException)
                        || (e.getCause() instanceof NoSuchMethodException);
                if (stripped && sMoveTaskToDisplayAvailable == null) {
                    sMoveTaskToDisplayAvailable = Boolean.FALSE;
                    AppLogger.w(TAG, "moveTaskToDisplay stripped on ROM — using launcher fallback");
                } else if (!stripped) {
                    AppLogger.e(TAG, "moveTaskToDisplay error", e);
                }
                if (!operation.isValid()) return;
                fallbackLaunch(packageName, targetDisplayId, callback, operation);
            }
        });
    }

    private void fallbackLaunch(final String packageName, final int targetDisplayId,
                                 final LaunchCallback callback,
                                 final LifecycleGate.Token operation) {
        mMainHandler.post(() -> {
            if (!operation.isValid()) return;
            if (targetDisplayId > 0) {
                launchOnDashboard(packageName, callback);
            } else {
                boolean ok = mLauncher.launchOnMainDisplay(packageName);
                if (callback != null) callback.onResult(ok);
            }
        });
    }

    /**
     * Resolves this package's flags and the current HOME handler, then asks
     * {@link ProjectionSafetyPolicy}. Fails OPEN: if PackageManager cannot answer, the launch
     * proceeds exactly as before — a lookup failure is not evidence that a package is dangerous,
     * and this guard must never be the reason a working car stops projecting.
     */
    private boolean isProjectionAllowed(String packageName) {
        boolean isHome = false;
        try {
            PackageManager pm = getPackageManager();
            ResolveInfo home = pm.resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            isHome = home != null && packageName.equals(home.activityInfo.packageName);
        } catch (Throwable t) {
            AppLogger.d(TAG, "projection safety check inconclusive for " + packageName
                    + " (" + t.getClass().getSimpleName() + ") — allowing");
            return true;
        }
        ProjectionSafetyPolicy.Verdict v =
                ProjectionSafetyPolicy.verdict(packageName, isHome);
        if (v == ProjectionSafetyPolicy.Verdict.ALLOWED) return true;
        AppLogger.w(TAG, "refusing to project " + packageName + " — "
                + ProjectionSafetyPolicy.reason(v));
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch on cluster
    // ─────────────────────────────────────────────────────────────────────────

    public void launchOnDashboard(final String packageName, final LaunchCallback callback) {
        final LifecycleGate.Token operation = mOperationGate.capture();
        if (!operation.isValid()) return;
        // Backstop for the paths the app picker does not gate: auto-launch, saved layouts, the
        // headless boot launch, and a target persisted from before this build. Hiding it in the
        // list is the primary defence; refusing here is what makes it unreachable.
        if (!isProjectionAllowed(packageName)) {
            if (callback != null) callback.onResult(false);
            return;
        }
        AppLogger.log(TAG, "launchOnDashboard — 2s delay → " + packageName);
        if (mPendingLaunchRunnable != null) {
            mMainHandler.removeCallbacks(mPendingLaunchRunnable);
            mPendingLaunchRunnable = null;
            if (mPendingDashboardCallback != null) {
                LaunchCallback prev = mPendingDashboardCallback;
                mPendingDashboardCallback = null;
                prev.onResult(false);
            }
        }
        mPendingDashboardCallback = callback;
        mPendingLaunchRunnable = new Runnable() {
            @Override public void run() {
                // Cancellation bookkeeping stays on the MAIN thread: these two fields are
                // read/written from launchOnDashboard() and onDestroy() on the main looper,
                // so they must be cleared here (still on main) before the executor hop.
                mPendingLaunchRunnable    = null;
                mPendingDashboardCallback = null;
                if (!operation.isValid()) return;
                // Hop the whole cascade off the main thread. cleanFissionStacks() is a
                // synchronous binder/proxy transact, applyForLaunch() does a shell round-trip
                // and startActivityViaIAM() bootstraps the uid-2000 proxy daemon — none of that
                // may run on the UI thread (ANR when the daemon is cold). Reusing the existing
                // single-thread sMoveTaskExecutor keeps this launch serialized behind any
                // in-flight moveTaskToDisplay task-move, so no new races are introduced.
                sMoveTaskExecutor.execute(() -> {
                if (!operation.isValid()) return;
                final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                AppLogger.i(TAG, "Launching on display=" + displayId + " → " + packageName);
                if (displayId <= 0) {
                    AppLogger.w(TAG, "launchOnDashboard: cluster display not ready (id="
                            + displayId + ") — aborting launch for " + packageName);
                    postLaunchResult(callback, false, operation);
                    return;
                }
                if (!operation.isValid()) return;
                try {
                    String cleanLog = com.byd.dashcast.proxy.ProxyClient
                            .cleanFissionStacks(displayId);
                    AppLogger.d(TAG, "cleanFissionStacks(" + displayId + ")\n" + cleanLog);
                } catch (Throwable ce) {
                    AppLogger.w(TAG, "cleanFissionStacks failed: " + ce.getMessage());
                }
                if (!operation.isValid()) return;
                boolean needsDpiSettle = com.byd.dashcast.cluster.dpi.ClusterDpiManager
                        .applyForLaunch(ClusterService.this, packageName, displayId);
                Runnable doLaunch = () -> {
                    if (!operation.isValid()) return;
                    try {
                        android.content.Intent launchIntent =
                                getPackageManager().getLaunchIntentForPackage(packageName);
                        if (launchIntent == null) {
                            AppLogger.e(TAG, "No launch intent for " + packageName);
                            postLaunchResult(callback, false, operation);
                            return;
                        }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                        opts.setLaunchDisplayId(displayId);
                        if (displayId > 0) {
                            applyClusterFreeformBounds(opts, displayId, packageName, operation);
                        }
                        if (!operation.isValid()) return;
                        // ── DAEMON-PRIMARY launch (matches DaemonConfig: the uid-2000 proxy daemon
                        // is the DEFAULT privileged path in ALL modes; the app-side path is a fallback
                        // gated by use_legacy_path). Layout mode already launches via the daemon —
                        // this unifies normal mode. On unprivileged DL5 the app-side launch is
                        // cross-user DENIED anyway, so the daemon is essential there; on DL3 the daemon
                        // `am start --display` is the same cascade fission/layout uses successfully.
                        // The app-side path stays as the fallback (use_legacy_path ON, daemon down, or
                        // the daemon launch failed).
                        final int clW = clusterWidthOr(1920);
                        final int clH = clusterHeightOr(720);
                        boolean legacyPath = com.byd.dashcast.proxy.DaemonConfig
                                .isLegacyPathEnabled(ClusterService.this);
                        boolean daemonTried = false;
                        if (!legacyPath && ProxyClient.isConnected()) {
                            daemonTried = true;
                            if (daemonLaunchSync(packageName, displayId, clW, clH, operation)) {
                                AppLogger.i(TAG, "launchOnDashboard OK (daemon) → " + packageName);
                                postLaunchResult(callback, true, operation);
                                return;
                            }
                            AppLogger.w(TAG, "daemon launch failed — falling back to app-side for " + packageName);
                        }

                        // App-side fallback. startActivityViaIAM RETURNS false when IAM reflection
                        // failed but its internal startActivity(opts) fallback DID launch the app —
                        // the normal DL3 path, a SUCCESS that must be reported so the app is tracked
                        // (green bar / resize / stop all depend on it). It only THROWS when every
                        // app-side attempt failed. 1.6.106 conflated the two (INC-20260705-195632
                        // Telenav / 195856 Waze — app shown but never tracked).
                        boolean iamOk;
                        boolean iamThrew = false;
                        if (!operation.isValid()) return;
                        try {
                            iamOk = startActivityViaIAM(launchIntent, opts, displayId);
                        } catch (Throwable iamErr) {
                            AppLogger.w(TAG, "startActivityViaIAM threw ("
                                    + iamErr.getClass().getSimpleName() + ": " + iamErr.getMessage() + ")");
                            iamOk = false;
                            iamThrew = true;
                        }
                        if (!operation.isValid()) return;
                        // DL4 joins DL5 here for the same structural reason: on DL4 the app process
                        // is not allowed to see the cluster display at all (OEM
                        // DisplayManagerService whitelist), so an app-side setLaunchDisplayId is
                        // at best unverifiable — the daemon is the only path with a proven view of
                        // that display. Whether DL4 ALSO blocks cross-display launches for our uid
                        // is unproven; routing through the daemon removes the question either way.
                        // isDiLink4() is false on DL3 and on DL5, so neither changes.
                        boolean daemonOnlyLaunch = AdbLocalClient.isDiLink5Safe(ClusterService.this)
                                || Platform.get().isDiLink4(ClusterService.this);
                        if (!iamOk && !daemonTried && daemonOnlyLaunch) {
                            // App-side DENIED / unverifiable and the daemon was not tried yet
                            // (use_legacy_path ON, or daemon was down at decision time) — the daemon
                            // HOLDS the cross-user permission, so it is the only path that can land it.
                            AppLogger.w(TAG, "app-side launch failed — routing via proxy daemon launchAndForce");
                                boolean ok = daemonLaunchSync(
                                    packageName, displayId, clW, clH, operation);
                            AppLogger.i(TAG, "launchOnDashboard → daemon force path (ok=" + ok + ") → " + packageName);
                                postLaunchResult(callback, ok, operation);
                        } else if (iamThrew) {
                            // Neither DL5 nor DL4 (i.e. DL3) and EVERY app-side attempt threw —
                            // a genuine failure.
                            AppLogger.e(TAG, "launchOnDashboard failed (app-side) → " + packageName);
                            postLaunchResult(callback, false, operation);
                        } else {
                            // iamOk==true, OR (non-DL5 && returned false = startActivity fallback
                            // launched it — the normal DL3 path). Report success so it is tracked.
                            AppLogger.i(TAG, "launchOnDashboard OK → " + packageName);
                            postLaunchResult(callback, true, operation);
                        }
                    } catch (Exception e) {
                        AppLogger.e(TAG, "launchOnDashboard error for " + packageName, e);
                        postLaunchResult(callback, false, operation);
                    }
                };
                // DPI settle delay is now applied on THIS background thread (ClusterDpiManager
                // documents that the caller must delay SETTLE_MS off the main thread) instead of
                // a main-handler postDelayed, so the whole cascade stays on the executor.
                if (needsDpiSettle) {
                    try {
                        Thread.sleep(com.byd.dashcast.cluster.dpi.ClusterDpiManager.SETTLE_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (!operation.isValid()) return;
                doLaunch.run();
                });
            }
        };
        mMainHandler.postDelayed(mPendingLaunchRunnable, 2000);
    }

    public void launchOnDashboardWithBounds(final String packageName,
            final int left, final int top, final int right, final int bottom,
            final LaunchCallback callback) {
        final LifecycleGate.Token operation = mOperationGate.capture();
        if (!operation.isValid()) return;
        AppLogger.log(TAG, "launchOnDashboardWithBounds 500ms → " + packageName
                + " [" + left + "," + top + "," + right + "," + bottom + "]");
        // Keep the 500ms schedule on the main handler (preserves timing + cancel semantics),
        // then hop the whole binder/proxy/shell cascade onto the shared single-thread move-task
        // executor so the main thread never blocks and launches stay serialized behind any
        // in-flight task-move.
        mMainHandler.postDelayed(() -> {
            if (!operation.isValid()) return;
            sMoveTaskExecutor.execute(() -> {
            if (!operation.isValid()) return;
            final int displayId = mDisplayHelper.getKnownClusterDisplayId();
            if (displayId <= 0) {
                AppLogger.w(TAG, "launchOnDashboardWithBounds: cluster display not ready (id="
                        + displayId + ") — aborting launch for " + packageName);
                postLaunchResult(callback, false, operation);
                return;
            }
            if (!operation.isValid()) return;
            if (displayId > 0) {
                try {
                    com.byd.dashcast.proxy.ProxyClient.cleanFissionStacks(displayId);
                } catch (Throwable ce) {
                    AppLogger.w(TAG, "cleanFissionStacks(WithBounds) failed: " + ce.getMessage());
                }
            }
            if (!operation.isValid()) return;
            boolean needsDpiSettle = com.byd.dashcast.cluster.dpi.ClusterDpiManager
                    .applyForLaunch(ClusterService.this, packageName, displayId);
            Runnable doLaunchWithBounds = () -> {
                if (!operation.isValid()) return;
                try {
                    android.content.Intent launchIntent =
                            getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        AppLogger.e(TAG, "launchOnDashboardWithBounds: no intent for " + packageName);
                        postLaunchResult(callback, false, operation);
                        return;
                    }
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(displayId);
                    try {
                        java.lang.reflect.Method setWM = android.app.ActivityOptions.class
                                .getDeclaredMethod("setLaunchWindowingMode", int.class);
                        setWM.setAccessible(true);
                        setWM.invoke(opts, 5);
                    } catch (Exception e) {
                        AppLogger.w(TAG, "setLaunchWindowingMode: " + e.getMessage());
                    }
                    try {
                        java.lang.reflect.Method setLB = android.app.ActivityOptions.class
                                .getDeclaredMethod("setLaunchBounds", Rect.class);
                        setLB.setAccessible(true);
                        setLB.invoke(opts, new Rect(left, top, right, bottom));
                    } catch (Exception e) {
                        AppLogger.w(TAG, "setLaunchBounds: " + e.getMessage());
                    }
                    // Same chain as launchOnDashboard: IAM first, then the proxy daemon's
                    // launchAndForce cascade as the DL5 fallback (shell `am start --display`
                    // provably lands the app on display 0 on some DX_BYD_AUTO ROMs).
                    if (!operation.isValid()) return;
                    boolean iamOkWB = startActivityViaIAM(launchIntent, opts, displayId);
                    if (!operation.isValid()) return;
                    if (!iamOkWB && AdbLocalClient.isDiLink5Safe(ClusterService.this)) {
                        AppLogger.w(TAG, "DL5: IAM fell back to startActivity (WithBounds) — routing via proxy daemon launchAndForce");
                        int wbW = right - left, wbH = bottom - top;
                        // Use the synchronous, verdict-checked daemon launch — same path
                        // launchOnDashboard uses. The old launchViaDaemonForce was
                        // fire-and-forget, so WithBounds reported unconditional success even
                        // when the DL5 daemon launch actually failed, mistracking the app.
                        boolean okWB = daemonLaunchSync(packageName, displayId,
                                wbW > 0 ? wbW : clusterWidthOr(1920),
                            wbH > 0 ? wbH : clusterHeightOr(720), operation);
                        AppLogger.i(TAG, "launchOnDashboardWithBounds → daemon force path (ok="
                                + okWB + ") → " + packageName);
                        postLaunchResult(callback, okWB, operation);
                    } else {
                        AppLogger.i(TAG, "launchOnDashboardWithBounds OK display=" + displayId);
                        postLaunchResult(callback, true, operation);
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "launchOnDashboardWithBounds error", e);
                    postLaunchResult(callback, false, operation);
                }
            };
            // DPI settle delay applied on this background thread (see launchOnDashboard).
            if (needsDpiSettle) {
                try {
                    Thread.sleep(com.byd.dashcast.cluster.dpi.ClusterDpiManager.SETTLE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!operation.isValid()) return;
            doLaunchWithBounds.run();
            });
        }, 500);
    }

    /**
     * Marshals a launch callback result back onto the main looper. The launch cascades now
     * run on {@link #sMoveTaskExecutor}; per the hardening contract only {@code onResult} is
     * allowed back on the main thread. Follows the same drop-on-destroy contract already used
     * by {@link #fallbackLaunch} and moveTaskToDisplayInternal so we never call back into a
     * torn-down service.
     */
    private void postLaunchResult(final LaunchCallback callback, final boolean success,
                                  final LifecycleGate.Token operation) {
        if (callback == null) return;
        mMainHandler.post(() -> {
            if (!operation.isValid()) return;
            callback.onResult(success);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void applyClusterFreeformBounds(android.app.ActivityOptions opts,
                                             int displayId, String packageName,
                                             LifecycleGate.Token operation) {
        if (!operation.isValid()) return;
        try {
            java.lang.reflect.Method setWM = android.app.ActivityOptions.class
                    .getDeclaredMethod("setLaunchWindowingMode", int.class);
            setWM.setAccessible(true);
            setWM.invoke(opts, 5);
        } catch (Exception e) {
            AppLogger.w(TAG, "setLaunchWindowingMode: " + e.getMessage());
        }
        android.graphics.Point sz = new android.graphics.Point(1920, 720);
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display d = (dm != null) ? dm.getDisplay(displayId) : null;
            if (d != null) {
                d.getRealSize(sz);
            } else {
                // DL4: no Display object obtainable (OEM whitelist). Use the geometry the
                // uid-2000 daemon read from `dumpsys display` rather than the 1920x720 literal,
                // so a DL4 variant with a different panel still gets correct launch bounds.
                // Null on DL3/DL5 → the literal above stands, exactly as before.
                ClusterDisplayInfo info = ClusterDisplayRegistry.forDisplayId(displayId);
                if (info != null && info.getWidth() > 0 && info.getHeight() > 0) {
                    sz.set(info.getWidth(), info.getHeight());
                }
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "getRealSize: " + e.getMessage());
        }
        // Normalize: cluster is always landscape; getRealSize() can report portrait dimensions
        // after a portrait app (e.g. TikTok) triggered a VirtualDisplay rotation on DL3.
        if (sz.x > 0 && sz.y > 0 && sz.y > sz.x) {
            int tmp = sz.x; sz.x = sz.y; sz.y = tmp;
            AppLogger.w(TAG, "applyClusterFreeformBounds: portrait VD detected — swapped to "
                    + sz.x + "x" + sz.y);
        }
        // v1.8.2 — always launch onto the FULL panel. This used to inset the launch bounds by
        // the per-app/global margins AND then apply the same margins again as a display overscan;
        // the two stacked, so an 80/50 setting removed 160/100 of usable area on every side pair
        // (measured 1600x520 of a 1920x720 panel — INC-20260725-211405). Shrinking is now the sole
        // job of the per-app hand-drawn rectangle, re-applied after launch by InsetAutoApplicator.
        Rect bounds = new Rect(0, 0, sz.x, sz.y);
        try {
            java.lang.reflect.Method setLB = android.app.ActivityOptions.class
                    .getDeclaredMethod("setLaunchBounds", Rect.class);
            setLB.setAccessible(true);
            setLB.invoke(opts, bounds);
            AppLogger.i(TAG, "cluster FREEFORM bounds=" + bounds + " display=" + displayId);
        } catch (Exception e) {
            AppLogger.w(TAG, "setLaunchBounds: " + e.getMessage());
        }
    }

    // Returns true if IAM reflection succeeded, false if fell back to startActivity().
    // Callers on DL5 can use the return value to decide whether to retry via shell.
    private boolean startActivityViaIAM(android.content.Intent intent,
                                         android.app.ActivityOptions opts, int displayId) {
        try {
            Class<?> amClass        = Class.forName("android.app.ActivityManager");
            Object   iam            = amClass.getMethod("getService").invoke(null);
            Class<?> iAmClass       = Class.forName("android.app.IActivityManager");
            Class<?> iAppThreadClass  = Class.forName("android.app.IApplicationThread");
            Class<?> profilerInfoClass = Class.forName("android.app.ProfilerInfo");
            iAmClass.getMethod("startActivityAsUser",
                    iAppThreadClass, String.class, android.content.Intent.class,
                    String.class, android.os.IBinder.class, String.class,
                    int.class, int.class, profilerInfoClass,
                    android.os.Bundle.class, int.class)
                .invoke(iam, null, getPackageName(), intent,
                    null, null, null, 0, 0, null, opts.toBundle(), -2);
            return true;
        } catch (Exception ex) {
            AppLogger.w(TAG, "startActivityViaIAM → fallback startActivity: " + ex.getMessage());
            try {
                startActivity(intent, opts.toBundle());
            } catch (Exception e2) {
                // DL3 cold-launch: launching a not-yet-running app onto the fission
                // VirtualDisplay with FREEFORM + launch bounds NPEs in the WM
                // (ActivityStack.getBounds() on a stack that doesn't exist yet —
                // INC-20260625-123938). Retry targeting only the display (no FREEFORM,
                // no bounds) so the task is created on the cluster; the inset bounds are
                // re-applied afterwards by the auto-resize path. DL5 never reaches this
                // catch — there startActivity(opts) succeeds (it lands on the wrong
                // display) and launchViaDaemonForce takes over, so DL5 is unchanged.
                AppLogger.w(TAG, "fallback startActivity(opts) failed (" + e2.getMessage()
                        + ") — retrying plain launch on display " + displayId);
                android.app.ActivityOptions plain = android.app.ActivityOptions.makeBasic();
                if (displayId > 0) plain.setLaunchDisplayId(displayId);
                startActivity(intent, plain.toBundle());
            }
            return false;
        }
    }

    private int clusterWidthOr(int fallback) {
        int w = (mInputForwarder != null) ? mInputForwarder.getClusterWidth() : 0;
        return w > 0 ? w : fallback;
    }

    private int clusterHeightOr(int fallback) {
        int h = (mInputForwarder != null) ? mInputForwarder.getClusterHeight() : 0;
        return h > 0 ? h : fallback;
    }

    // DL5 fallback when IAM fails and a shell `am start --display N` provably lands the
    // app on display 0 (DX_BYD_AUTO HDMI cluster — the live `displayId=1 realActivity`
    // query stays empty). Routes through the proxy daemon's launchAndForce cascade, which
    // follows the launch with the privileged moveRootTaskToDisplay + an async watchdog that
    // re-anchors the task on the cluster display — the same path fission uses. Runs off the
    // main thread because launchAndForce blocks for several seconds.
    private void launchViaDaemonForce(final String packageName, final int displayId,
                                       final int width, final int height) {
        final LifecycleGate.Token operation = mOperationGate.capture();
        sMoveTaskExecutor.execute(() -> {
            if (!operation.isValid()) return;
            try {
                if (!ProxyClient.isConnected()) ProxyClient.connect(ClusterService.this);
                if (!operation.isValid()) return;
                String log = ProxyClient.launchAndForce(packageName, null, displayId, width, height);
                // Log the FULL cascade result, not just the first line — we need to see
                // whether moveRootTaskToDisplay / setTaskWindowingModeFreeform / resizeTask
                // actually succeeded on this ROM (INC-20260621-201303: app reaches display 1
                // but ends up mode=fullscreen and the user still sees nothing).
                AppLogger.i(TAG, "DL5 daemon launchAndForce result:\n"
                        + (log != null && !log.isEmpty() ? log : "(empty)"));
            } catch (Throwable t) {
                AppLogger.e(TAG, "DL5 daemon launchAndForce failed: " + t.getMessage());
            }
            // AAOS-only experiment: on Android Automotive head units the normal launch lands the
            // app on the logical cluster display (visible in our preview) but never on the
            // physical cluster panel. Probe the AAOS `start-fixed-activity` mechanism — see the
            // method for details. No-op on DL3/DL5 fission ROMs.
            if (!operation.isValid()) return;
            tryClusterFixedActivityExperiment(displayId, packageName, operation);
            // Post-launch verification: ~1.5 s after the cascade, dump what is actually on
            // the cluster display (package / visible / windowing mode / bounds) so the next
            // bug report shows whether the app stayed put and in which mode it rendered.
            // Runs on the diagnostic executor so its 1.5 s sleep never holds the move-task
            // worker (frees it immediately for a queued eviction / task-move).
            // AUD-PERF-P3 — opt-in (Diagnostics screen), OFF by default. This dump shells
            // `dumpsys SurfaceFlinger`, taking SF's global lock 1.5 s into the projected app's
            // cold start. NOTE: tryClusterFixedActivityExperiment above is deliberately NOT
            // gated by this — despite the name it invokes `cmd car_service start-fixed-activity`,
            // which has a real effect on AAOS units, so gating it could regress DX_BYD_AUTO.
            if (ClusterPrefs.isLaunchDiagnosticsEnabled(this)) {
                sDiagExecutor.execute(() -> verifyClusterDisplayState(
                    displayId, packageName, operation));
            }
        });
    }

    /**
     * Synchronous daemon launch (uid 2000): runs the {@code launchAndForce} cascade
     * (am start --display + moveRootTaskToDisplay + resize) and RETURNS whether the app
     * was launched, so the caller can register it and decide a fallback. Used as the
     * daemon-PRIMARY path in {@link #launchOnDashboard} (DaemonConfig design). MUST run
     * off the main thread — {@code launchAndForce} blocks for several seconds; callers
     * already run on {@code sMoveTaskExecutor}. The diagnostic post-launch verify is
     * scheduled on {@code sDiagExecutor} so it never holds the move-task worker.
     *
     * <p>Success = the daemon did not report its own "FAIL: no task discovered" verdict AND
     * the am transcript shows an accepted start. The daemon's verdict wins, because it is the
     * only signal that actually proves a task exists; the transcript alone can look like a
     * success on a launch that system_server threw away.
     */
    private boolean daemonLaunchSync(final String packageName, final int displayId,
                                      final int width, final int height,
                                      final LifecycleGate.Token operation) {
        if (!operation.isValid()) return false;
        boolean ok;
        try {
            if (!ProxyClient.isConnected()) ProxyClient.connect(ClusterService.this);
            if (!operation.isValid()) return false;
            String log = ProxyClient.launchAndForce(packageName, null, displayId, width, height);
            if (!operation.isValid()) return false;
            String low = (log == null) ? "" : log.toLowerCase(java.util.Locale.ROOT);
            // The daemon polls for the task after `am start` and appends its OWN verdict:
            // "FAIL: no task discovered for <pkg>" when nothing came up. That verdict is
            // authoritative and MUST win — the am transcript above it can read like a success
            // ("Starting: Intent {…}") while system_server threw and no activity ever started
            // (DiLink 3.0 FREEFORM stack creation NPE, INC-20260714-215700). Trusting the
            // transcript alone reported ok=true on a launch that showed nothing on the
            // cluster, and suppressed the app-side fallback.
            boolean daemonSaysFail = low.contains("fail: no task discovered");
            // The daemon's poll-based verdict is authoritative: if it did NOT append
            // "FAIL: no task discovered", a task WAS discovered on the cluster display. Do NOT
            // also require an am-transcript keyword ("status: ok" / "starting: intent") —
            // launchAndForce does not emit a positive verdict string when a task is found, so
            // ANDing that in vetoed task-PROVEN launches: it dropped the app's tracking (green
            // bar / resize / stop) and forced the app-side fallback (cross-user DENIED on DL5).
            // Only guard against EMPTY output (a transport error before the daemon ran), which
            // cannot count as success. Do NOT infer failure from "exception" either — the
            // cascade's optional-method reflection probes (setDisplayToSingleTaskInstance /
            // setTaskWindowingModeFreeform) log a BENIGN NoSuchMethodException even on success.
            ok = !low.isEmpty() && !daemonSaysFail;
            AppLogger.i(TAG, "daemon launchAndForce result (ok=" + ok + "):\n"
                    + (log != null && !log.isEmpty() ? log : "(empty)"));
        } catch (Throwable t) {
            AppLogger.e(TAG, "daemon launchAndForce failed: " + t.getMessage());
            ok = false;
        }
        // AAOS-only experiment + post-launch verify — diagnostic, off the critical path.
        if (!operation.isValid()) return false;
        tryClusterFixedActivityExperiment(displayId, packageName, operation);
        // AUD-PERF-P3 — opt-in (Diagnostics screen), OFF by default. This dump shells
        // `dumpsys SurfaceFlinger`, taking SF's global lock 1.5 s into the projected app's
        // cold start. NOTE: tryClusterFixedActivityExperiment above is deliberately NOT
        // gated by this — despite the name it invokes `cmd car_service start-fixed-activity`,
        // which has a real effect on AAOS units, so gating it could regress DX_BYD_AUTO.
        if (ClusterPrefs.isLaunchDiagnosticsEnabled(this)) {
            sDiagExecutor.execute(() -> verifyClusterDisplayState(displayId, packageName, operation));
        }
        return ok;
    }

    /**
     * Diagnostic only (no behaviour change): after a DL5 daemon launch, query the
     * cluster display's top tasks and log them to the journal. Helps determine whether
     * the launched app stays on the display and in which windowing mode / bounds.
     */
    private void verifyClusterDisplayState(final int displayId, final String packageName,
                                           final LifecycleGate.Token operation) {
        if (!operation.isValid()) return;
        try { Thread.sleep(1500); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); return;
        }
        if (!operation.isValid()) return;
        // v1.6.108 — decisive pump-vs-placement capture (INC-20260705-175936). The app is known
        // to reach the cluster display at the WM level; the open question is whether the OEM
        // fission container actually forwards a FOREIGN window on the composed cluster output
        // (layerStack 2 / fission_bg_XDJAScreenProjection) to the physical panel. Capture both
        // the WM task state AND the SurfaceFlinger layers on the cluster surface (is the app's
        // layer present + non-empty visibleRegion on layerStack 2?). Combined with the app-side
        // mirror state below — the mirror reads layerStack 2, i.e. EXACTLY what the panel is fed —
        // this disambiguates "container pump ignores foreign content" (preview shows the app but
        // the panel is blank) from "window never composited to layerStack 2".
        final String cmd = "echo '--- WM tasks on display " + displayId + " ---';"
                + " dumpsys activity activities 2>/dev/null"
                + "   | grep -A 25 'Display #" + displayId + "'"
                + "   | grep -E 'Stack #|Task\\{|mResumed|visible=' | head -20;"
                + " echo '--- SurfaceFlinger cluster layers (" + packageName + ") ---';"
                + " dumpsys SurfaceFlinger 2>/dev/null"
                + "   | grep -iE 'fission_bg_XDJAScreenProjection|" + packageName + "|layerStack|visibleRegion'"
                + "   | head -40";
        ShellGateway.execShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                if (!operation.isValid()) return;
                boolean mirrorActive = mMirrorManager != null && mMirrorManager.isMirrorActive();
                AppLogger.i(TAG, "DL5 post-launch display " + displayId + " state ("
                        + packageName + ") — in-app mirror active=" + mirrorActive
                        + " [mirror reads layerStack 2 = exact panel content]:\n"
                        + (out == null || out.trim().isEmpty()
                        ? "(nothing on display " + displayId + ")" : out.trim()));
            }
            @Override public void onError(String err) {
                if (!operation.isValid()) return;
                AppLogger.w(TAG, "DL5 post-launch verify failed: " + err);
            }
        });
    }

    /**
     * EXPERIMENT — Android Automotive (AAOS) clusters only. On these head units (e.g.
     * DX_BYD_AUTO / Bosch, INC-20260624-221542) the instrument cluster panel is driven by the
     * AAOS cluster-rendering pipeline (InstrumentClusterService + IAutomotiveDisplayProxyService),
     * NOT by a raw scan-out of the logical display. So a normal {@code am start --display N} puts
     * the app on the logical cluster display (it shows in our SurfaceControl preview) but never on
     * the physical cluster, where only the OEM system nav is presented.
     *
     * {@code cmd car_service start-fixed-activity <displayId> <pkg> <activity>} is the AAOS way to
     * pin an activity on a display in "fixed" mode. This probes whether it (a) is permitted on this
     * production/user build and (b) actually reaches the physical cluster. The full result is logged
     * for the bug report. Gated to {@code FEATURE_AUTOMOTIVE} so DL3/DL5 fission ROMs — where the
     * normal launch already works — are left completely untouched.
     */
    private void tryClusterFixedActivityExperiment(final int displayId, final String packageName,
                                                   final LifecycleGate.Token operation) {
        if (!operation.isValid()) return;
        if (displayId <= 0 || packageName == null || packageName.isEmpty()) return;
        if (!getPackageManager().hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            return; // not AAOS — do not disturb working fission ROMs
        }
        // Resolve the package's launcher activity, normalise a relative ".Name" to a full
        // component, then ask car_service to pin it on the cluster display in fixed mode.
        final String cmd =
              "PKG=" + packageName + " ; DID=" + displayId + " ; "
            + "COMP=$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER \"$PKG\" 2>/dev/null | tail -1) ; "
            + "ACT=${COMP#*/} ; case \"$ACT\" in .*) ACT=\"$PKG$ACT\" ;; esac ; "
            + "echo \"[exp] resolved=$COMP -> start-fixed-activity $DID $PKG $ACT\" ; "
            + "cmd car_service start-fixed-activity \"$DID\" \"$PKG\" \"$ACT\" 2>&1";
        if (!operation.isValid()) return;
        ShellGateway.execShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                if (!operation.isValid()) return;
                AppLogger.i(TAG, "AAOS start-fixed-activity experiment (" + packageName
                        + " → display " + displayId + "):\n"
                        + (out == null || out.trim().isEmpty() ? "(no output)" : out.trim()));
            }
            @Override public void onError(String err) {
                if (!operation.isValid()) return;
                AppLogger.w(TAG, "AAOS start-fixed-activity experiment error: " + err);
            }
        });
    }

    // DL5 shell fallback used when IAM reflection fails and startActivity() ignores
    // setLaunchDisplayId on a real HDMI secondary display (e.g. DX_BYD_AUTO).
    // Intentionally omits --windowingMode 5: some ROMs silently reject --display N
    // when FREEFORM mode is requested on a display that does not support it, causing
    // the app to land on display=0 instead.
    private void startActivityViaShellSimple(String packageName, int displayId,
                                              android.content.Intent launchIntent) {
        android.content.ComponentName cn = (launchIntent != null)
                ? launchIntent.getComponent() : null;
        if (cn == null) {
            AppLogger.e(TAG, "startActivityViaShellSimple: no component for " + packageName);
            return;
        }
        String component = cn.getPackageName() + "/" + cn.getClassName();
        final String cmd = "am force-stop " + packageName + " 2>&1; "
                + "am start --display " + displayId
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -n " + component + " 2>&1";
        AppLogger.i(TAG, "DL5 shell fallback (no windowingMode): " + cmd);
        ShellGateway.execShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                AppLogger.i(TAG, "DL5 shell fallback → " + (out == null ? "" : out.trim()));
            }
            @Override public void onError(String err) {
                AppLogger.e(TAG, "DL5 shell fallback ERROR: " + err);
            }
        });
    }

    private void startActivityViaShell(String packageName, int displayId,
                                        android.content.Intent launchIntent) {
        android.content.ComponentName cn = (launchIntent != null)
                ? launchIntent.getComponent() : null;
        if (cn == null) {
            AppLogger.e(TAG, "startActivityViaShell: cannot resolve component for " + packageName);
            return;
        }
        String component = cn.getPackageName() + "/" + cn.getClassName();
        final String cmd = "am force-stop " + packageName + " 2>&1; "
                + "am start --display " + displayId
                + " --windowingMode 5"
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -n " + component
                + " --activity-clear-task 2>&1";
        AppLogger.i(TAG, "DL5 launch via shell: " + cmd);
        ShellGateway.execShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                AppLogger.i(TAG, "DL5 am start → " + (out == null ? "" : out.trim()));
            }
            @Override public void onError(String err) {
                AppLogger.e(TAG, "DL5 am start ERROR: " + err);
            }
        });
    }

    public void restartProjection() {
        AppLogger.log(TAG, "restartProjection requested natively");
        mProjectionActive = true;
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_cluster_initializing)));
        startNativeProjection();
    }

    public void stopProjectionNoAdb() {
        AppLogger.log(TAG, "stopProjectionNoAdb requested");
        // v1.8.2 — was hardcoded "-d 1". The fission VirtualDisplay is display 1 on most DiLink 3
        // units but not all (INC-20260721 saw it come up as display 3), and resetting the wrong
        // display both misses the cluster and touches a display we do not own. Never display 0:
        // the id is the one the helper resolved, and ShellGateway blocks -d 0 outright anyway.
        int clusterId = mDisplayHelper.getKnownClusterDisplayId();
        if (clusterId > 0 && !AdbLocalClient.isDiLink5Safe(this)) {
            ShellGateway.execShell(this, "wm overscan reset -d " + clusterId);
        }
        try {
            com.byd.dashcast.cluster.dpi.ClusterDpiManager.restore(
                    this, mDisplayHelper.getKnownClusterDisplayId());
        } catch (Throwable t) {
            AppLogger.w(TAG, "DPI restore (stopProjectionNoAdb) failed: " + t.getMessage());
        }
        mProjectionActive = false;
        mDisplayHelper.stopWithoutAdb();
        // The projection session is over: drop the daemon-resolved geometry so nothing downstream
        // can start a mirror / inject touch on a display that is no longer projected. This used to
        // be cleared ONLY from onDashboardDisplayDisconnected, which on DL4 essentially never
        // fires (its producers need the app process to observe the display — the very thing the
        // OEM whitelist forbids). No-op on DL3/DL5, where the registry is never populated.
        ClusterDisplayRegistry.clear();
        // Tear down the mirror NOW (local token + daemon SurfaceControl via stopPreview) so
        // SurfaceFlinger stops compositing the cluster immediately on a real stop, instead of
        // waiting for the async stopSelf()→onDestroy()→release(). Background keepalive is not
        // affected: stopProjectionNoAdb() is only called on an explicit restore/stop.
        mMirrorManager.stopMirror();
        mLauncher.setDashboardDisplayId(-1);
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            AppLogger.w(TAG, "stopForeground failed: " + t.getMessage());
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
        stopSelf();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DashboardDisplayHelper.Listener
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onDashboardDisplayConnected(final Display display, final int displayId) {
        AppLogger.log(TAG, "Cluster display connected: id=" + displayId);
        mLauncher.setDashboardDisplayId(displayId);
        mInputForwarder.setClusterDisplay(display);
        mInputForwarder.setClusterDisplayId(displayId);
        // DiLink 4.0 has no Display object to hand around (the OEM DisplayManagerService
        // whitelist hides the id from our uid, so dm.getDisplay() returns null): the call above
        // no-ops and the forwarder would keep its 1920x720 compile-time defaults. Feed it the
        // geometry the uid-2000 daemon read out of `dumpsys display` instead. Double-gated —
        // display==null never happens on DL3/DL5, and only the DL4 activation path writes
        // ClusterDisplayRegistry, so both platforms skip this entirely.
        if (display == null) {
            ClusterDisplayInfo info = ClusterDisplayRegistry.forDisplayId(displayId);
            if (info != null) {
                mInputForwarder.setClusterGeometry(displayId, info.getWidth(), info.getHeight());
                latchPanelGeometry(info.getWidth(), info.getHeight());
            }
        } else {
            android.graphics.Point size = new android.graphics.Point();
            try {
                display.getRealSize(size);
                latchPanelGeometry(size.x, size.y);
            } catch (Throwable ignored) {
                // Unreadable geometry latches nothing — the policy stays on the configured type.
            }
        }
        updateNotification(getString(R.string.notif_cluster_active, displayId));
        // v1.8.2 — the global overscan is gone: the cluster is always driven full-screen and
        // only a per-app hand-drawn rectangle (ClusterResizeActivity) may shrink it afterwards.
        // We RESET here rather than simply not applying, because `wm overscan` is display state
        // that survives in WindowManager: a unit upgrading from a build that set 80,50 would
        // otherwise keep the old inset with no UI left to clear it (INC-20260725-211405, where
        // the 80/50 default combined with the launch bounds to eat 40% of the panel).
        if (displayId > 0 && !AdbLocalClient.isDiLink5Safe(this)) {
            ShellGateway.execShell(this, "wm overscan reset -d " + displayId);
            AppLogger.i(TAG, "wm overscan reset on display " + displayId + " (full-screen cluster)");
        }
        if (mListener != null) mListener.onClusterDisplayConnected(display, displayId);

        // Headless boot auto-launch (armed in onStartCommand): the cluster is now up, so launch the
        // configured app onto it — no MainActivity needed. Record it as the cluster app on success
        // so a later MainActivity open adopts it (via getLastClusterPkg / sBootLaunchedPkg) instead
        // of relaunching (which would force-stop the running nav). Fires once.
        if (displayId > 0 && mBootAutoLaunchPkg != null) {
            final String pkg = mBootAutoLaunchPkg;
            mBootAutoLaunchPkg = null;
            AppLogger.i(TAG, "boot auto-launch → " + pkg + " on display " + displayId);
            launchOnDashboard(pkg, new LaunchCallback() {
                @Override public void onResult(boolean success) {
                    AppLogger.i(TAG, "boot auto-launch result=" + success + " → " + pkg);
                    // Mark it so a later MainActivity open adopts it (shows the mirror) instead of
                    // relaunching — see MainActivity.onSendToDashboard's sBootLaunchedPkg guard.
                    if (success) sBootLaunchedPkg = pkg;
                }
            });
        }
    }

    @Override
    public void onDashboardDisplayDisconnected() {
        AppLogger.log(TAG, "Cluster display disconnected");
        mLauncher.setDashboardDisplayId(-1);
        // Projection ended — the boot-launched app is no longer on the cluster, so drop the latch
        // (defensive: the consume-once in MainActivity is the primary clear).
        sBootLaunchedPkg = null;
        // Nothing downstream may keep using a dead display's geometry. No-op on DL3/DL5.
        ClusterDisplayRegistry.clear();

        // The mirror was compositing onto the display that just went away, and nothing here used to
        // tell it so. stopProjectionNoAdb tears it down; this path did not, so mMirrorActive stayed
        // true against a dead surface — and because every start is guarded by that flag, the mirror
        // could never be started again for the rest of the process's life. On a head unit that runs
        // for weeks, "never again" means what it says: the preview stays black until the app is
        // force-stopped, and nothing on screen explains why.
        try {
            if (mMirrorManager != null) mMirrorManager.stopMirror();
        } catch (Throwable t) {
            AppLogger.w(TAG, "mirror teardown on disconnect failed: " + t.getMessage());
        }
        // On a DL3 single-OS car the "disconnect" is really "activation timed out because no
        // VirtualDisplay can exist" — tell the user projection is unavailable rather than the
        // generic "disconnected" (which reads like a transient glitch they should retry).
        // Same treatment on a DL4 whose firmware hides the display from BOTH the app process and
        // the uid-2000 daemon: there is nothing the user can retry.
        final int titleRes;
        if (isDl3SingleOsFission()) {
            titleRes = R.string.dl3_singleos_cluster_unsupported_title;
        } else if (isDl4ProjectionBlocked()) {
            titleRes = R.string.dl4_cluster_unsupported_title;
        } else {
            titleRes = R.string.notif_cluster_disconnected;
        }
        updateNotification(getString(titleRes));
        if (mListener != null) {
            mListener.onClusterDisplayDisconnected();
        } else {
            // Nobody is listening (MainActivity is stopped). Latch it so setListener can replay
            // the edge on re-attach; otherwise the UI and the persisted cluster package stay
            // wrong for the rest of the session and beyond. See setListener for the full note.
            mMissedDisconnect = true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_cluster_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_cluster_channel_desc));
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        if (mNotifPi == null) {
            Intent tapIntent = new Intent(this, MainActivity.class);
            tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mNotifPi = PendingIntent.getActivity(this, 0, tapIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(mNotifPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
