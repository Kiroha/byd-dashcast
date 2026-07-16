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
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.view.Display;

import com.byd.dashcast.cluster.mirror.ClusterInputForwarder;
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager;
import com.byd.dashcast.cluster.display.DashboardDisplayHelper;
import com.byd.dashcast.cluster.display.DashboardLauncher;
import com.byd.dashcast.domain.cluster.ProjectionStateProvider;
import com.byd.dashcast.infrastructure.launch.PlatformAdaptiveAppLauncher;
import com.byd.dashcast.infrastructure.task.AdbLocalTaskFinder;
import com.byd.dashcast.infrastructure.task.AmTaskFinder;
import com.byd.dashcast.infrastructure.task.ChainedTaskFinder;
import com.byd.dashcast.infrastructure.task.ChainedTaskResizer;
import com.byd.dashcast.infrastructure.task.TaskFinder;
import com.byd.dashcast.infrastructure.task.TaskResizer;
import com.byd.dashcast.platform.Platform;
import com.byd.dashcast.data.prefs.ClusterPrefs;

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
 *   <li>{@link #resizeActiveTask(int, String)} now delegates to {@link ChainedTaskResizer}
 *       (reflection → shell), removing 80 lines of inline cascade code.
 *   <li>Implements {@link ProjectionStateProvider} so FissionActivity / FissionOrchestrator
 *       no longer depend on the static field {@code sIsRunning} or a direct class reference.
 * </ul>
 *
 * Zero regression: all runtime logic (IATM reflection, shell commands, timing, DPI settle,
 * DL5 guards, wm overscan, cleanFissionStacks, enforceTaskOnDisplay) is preserved verbatim.
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
        return !Boolean.FALSE.equals(sMoveTaskToDisplayAvailable);
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
    private LaunchCallback         mPendingDashboardCallback;
    private Runnable               mPendingLaunchRunnable;

    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private PendingIntent mNotifPi;

    // ── Strategy objects (injected in onCreate) ─────────────────────────────
    private TaskFinder   mTaskFinder;
    private TaskResizer  mTaskResizer;

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

        // Strategy wiring: ChainedTaskFinder tries AM → ProxyDaemon → AdbLocal.
        mTaskFinder = new ChainedTaskFinder(
                new AmTaskFinder(this),
                new com.byd.dashcast.infrastructure.task.ProxyTaskFinder(),
                new AdbLocalTaskFinder(this));
        mTaskResizer = new ChainedTaskResizer(this);

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
                                mMainHandler.post(() -> updateNotification(
                                        getString(R.string.dl3_singleos_cluster_unsupported_title)));
                            }
                        }
                        @Override public void onError(String err) { /* stays unflagged; projection proceeds (fail-open) */ }
                    });
        }
        startNativeProjection();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Headless boot auto-launch: BootReceiver starts us with EXTRA_BOOT_AUTOLAUNCH so the
        // configured app is launched onto the cluster at startup WITHOUT the user opening DashCast
        // (INC-20260716-091016). Arm it here (intent is delivered to onStartCommand); the actual
        // launch happens in onDashboardDisplayConnected once the cluster display is up.
        if (intent != null && intent.getBooleanExtra(EXTRA_BOOT_AUTOLAUNCH, false)) {
            // Mirror MainActivity's guard: when a Layout auto-start is configured, IT owns startup
            // launching (activates projection + launches every bound app). A headless single-app
            // launch here would create a classic projection first, and the layout's ensureDaemon()
            // would then abort with "Daemon unavailable". So skip the single-app path for layout users.
            if (isLayoutAutoStartConfigured()) {
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

    /** Same predicate as MainActivity.isLayoutAutoStartConfigured — the Layout auto-start owns
     *  startup launching, so the headless single-app boot launch must stand down for layout users. */
    private boolean isLayoutAutoStartConfigured() {
        try {
            return ClusterPrefs.isFissionAutoLayout(this)
                    && com.byd.dashcast.proxy.DaemonConfig.isFissionModeEnabled(this)
                    && com.byd.dashcast.fission.LayoutPrefs.getFavoriteLayout(this) != null;
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
        super.onDestroy();
        sIsRunning = false;
        if (sInstance == this) sInstance = null;
        mDestroyed = true;
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

    private void startNativeProjection() {
        if (isDl3SingleOsFission()) {
            AppLogger.w(TAG, "DL3 single-OS fission (fission_single_os=1) — no projectable cluster display; skipping AutoContainer activation");
            updateNotification(getString(R.string.dl3_singleos_cluster_unsupported_title));
            return;
        }
        AppLogger.i(TAG, "Starting cluster projection (native)...");
        mDisplayHelper.start();
    }

    public int getInsetH(String packageName) {
        android.content.SharedPreferences prefs =
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        int def = prefs.getInt(SettingsActivity.PREF_INSET_H, SettingsActivity.DEFAULT_INSET_H);
        if (packageName == null || packageName.isEmpty()) return def;
        return prefs.getInt(SettingsActivity.PREF_INSET_H_PREFIX + packageName, def);
    }

    public int getInsetV(String packageName) {
        android.content.SharedPreferences prefs =
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        int def = prefs.getInt(SettingsActivity.PREF_INSET_V, SettingsActivity.DEFAULT_INSET_V);
        if (packageName == null || packageName.isEmpty()) return def;
        return prefs.getInt(SettingsActivity.PREF_INSET_V_PREFIX + packageName, def);
    }

    /**
     * Resizes the active cluster task to its per-app inset bounds.
     *
     * <p>Delegates to {@link ChainedTaskResizer} (reflection → shell). All DL5/ROM guards
     * are preserved; this method is unchanged from the user's perspective.
     */
    public void resizeActiveTask(int taskId, String packageName) {
        if (taskId <= 0) {
            AppLogger.w(TAG, "resizeActiveTask: taskId<=0 for pkg=" + packageName);
            return;
        }
        if (!Platform.get().isClusterTaskResizeSupported(this)) {
            if (!sResizeUnsupportedLogged) {
                sResizeUnsupportedLogged = true;
                AppLogger.w(TAG, "resizeActiveTask skipped: cluster task resize not supported on ROM");
            }
            return;
        }
        int clusterId = mDisplayHelper.getKnownClusterDisplayId();
        if (clusterId <= 0) {
            AppLogger.w(TAG, "resizeActiveTask aborted: no cluster display (taskId=" + taskId + ")");
            return;
        }

        // Compute bounds (DL5 framebuffer-space fix: use real display dimensions).
        int cw = 1920, ch = 720;
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display d = (dm != null) ? dm.getDisplay(clusterId) : null;
            if (d != null) {
                android.graphics.Point sz = new android.graphics.Point();
                d.getRealSize(sz);
                if (sz.x > 0) cw = sz.x;
                if (sz.y > 0) ch = sz.y;
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "getRealSize(" + clusterId + ") failed: " + t.getMessage());
            if (mInputForwarder != null) {
                int fw = mInputForwarder.getClusterWidth();
                int fh = mInputForwarder.getClusterHeight();
                if (fw > 0) cw = fw;
                if (fh > 0) ch = fh;
            }
        }
        // Normalize: cluster is always landscape; getRealSize() can report portrait dimensions
        // after a portrait app triggered a VirtualDisplay rotation on DL3.
        if (cw > 0 && ch > 0 && ch > cw) {
            int tmp = cw; cw = ch; ch = tmp;
            AppLogger.w(TAG, "resizeActiveTask: portrait VD detected — swapped to " + cw + "x" + ch);
        }
        int insetH = getInsetH(packageName);
        int insetV = getInsetV(packageName);
        Rect bounds = new Rect(insetH, insetV, cw - insetH, ch - insetV);

        // Primary: daemon-backed FREEFORM flip + resize (uid=2000 bypasses permission check).
        // The containing stack stays FULLSCREEN when the task is launched via startActivity,
        // and am task resize rejects non-FREEFORM tasks. Phase4TaskVerbs.moveAndResize()
        // calls setTaskWindowingMode(FREEFORM) on both the task and stack before resizing.
        try {
            String log = ProxyClient.moveAndResize(packageName, clusterId,
                    bounds.left, bounds.top, bounds.right, bounds.bottom);
            AppLogger.i(TAG, "resizeActiveTask via daemon: " + log);
            return;
        } catch (Exception daemonEx) {
            AppLogger.w(TAG, "resizeActiveTask daemon unavailable — fallback: " + daemonEx.getMessage());
        }
        // Fallback: ChainedTaskResizer (reflection → shell)
        try {
            mTaskResizer.resize(taskId, packageName, bounds);
        } catch (TaskResizer.ResizeException e) {
            AppLogger.w(TAG, "resizeActiveTask final failure for " + packageName + ": " + e.getMessage());
        }
    }

    public void setListener(Listener listener) {
        mListener = listener;
        int knownId = mDisplayHelper.getKnownClusterDisplayId();
        if (knownId > 0 && mListener != null) {
            Display d = null;
            try {
                android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
                if (dm != null) d = dm.getDisplay(knownId);
            } catch (Exception e) {
                AppLogger.w(TAG, "getDisplay(" + knownId + ") failed: " + e.getMessage());
            }
            if (d != null) mListener.onClusterDisplayConnected(d, knownId);
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
     * Delegates to {@link ChainedTaskFinder}: AM → ProxyDaemon recents/activities → AdbLocal.
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
        moveTaskToDisplayInternal(packageName, targetDisplayId, callback, false);
    }

    public void enforceTaskOnDisplay(final String packageName, final int targetDisplayId) {
        moveTaskToDisplayInternal(packageName, targetDisplayId, null, true);
    }

    private void moveTaskToDisplayInternal(final String packageName, final int targetDisplayId,
                                            final LaunchCallback callback,
                                            final boolean enforceOnly) {
        if (PKG_FORCE_FRESH_LAUNCH.equals(packageName) && targetDisplayId > 0) {
            if (enforceOnly) {
                AppLogger.d(TAG, "enforceTaskOnDisplay: skip force-fresh-launch pkg " + packageName);
                return;
            }
            AppLogger.i(TAG, "moveTaskToDisplay: force fresh launch for " + packageName);
            fallbackLaunch(packageName, targetDisplayId, callback);
            return;
        }

        sMoveTaskExecutor.execute(() -> {
            try {
                int taskId = findRunningTaskId(packageName);
                if (taskId == -1) {
                    if (enforceOnly) {
                        AppLogger.d(TAG, "enforceTaskOnDisplay: no task yet for " + packageName);
                        return;
                    }
                    AppLogger.w(TAG, "moveTaskToDisplay: no task for " + packageName + " → fallback");
                    fallbackLaunch(packageName, targetDisplayId, callback);
                    return;
                }

                if (Boolean.FALSE.equals(sMoveTaskToDisplayAvailable)) {
                    AppLogger.d(TAG, (enforceOnly ? "enforceTaskOnDisplay" : "moveTaskToDisplay")
                            + ": method unavailable on ROM → "
                            + (enforceOnly ? "skip" : "fallback launch"));
                    if (mDestroyed) return;
                    if (!enforceOnly) fallbackLaunch(packageName, targetDisplayId, callback);
                    return;
                }

                Class<?> atmClass  = Class.forName("android.app.ActivityTaskManager");
                Object   iatm      = atmClass.getMethod("getService").invoke(null);
                Class<?> iAtmClass = iatm.getClass();
                iAtmClass.getMethod("moveTaskToDisplay", int.class, int.class)
                        .invoke(iatm, taskId, targetDisplayId);
                AppLogger.i(TAG, (enforceOnly ? "enforceTaskOnDisplay" : "moveTaskToDisplay")
                        + " taskId=" + taskId + " → display=" + targetDisplayId + " OK");

                if (targetDisplayId > 0) {
                    try { Thread.sleep(300); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    // WINDOWING_MODE_FREEFORM = 5
                    try {
                        iAtmClass.getMethod("setTaskWindowingMode",
                                int.class, int.class, boolean.class)
                                .invoke(iatm, taskId, 5, true);
                        AppLogger.i(TAG, "setTaskWindowingMode(FREEFORM) OK");
                    } catch (Exception e) {
                        AppLogger.w(TAG, "setTaskWindowingMode: " + e.getMessage());
                    }
                    // Apply inset bounds post-move
                    try {
                        int insetH = getInsetH(packageName);
                        int insetV = getInsetV(packageName);
                        int cw = (mInputForwarder != null) ? mInputForwarder.getClusterWidth()  : 1920;
                        int ch = (mInputForwarder != null) ? mInputForwarder.getClusterHeight() :  720;
                        if (cw <= 0) cw = 1920;
                        if (ch <= 0) ch =  720;
                        Rect bounds = new Rect(insetH, insetV, cw - insetH, ch - insetV);
                        iAtmClass.getMethod("resizeTask",
                                int.class, Rect.class, int.class)
                                .invoke(iatm, taskId, bounds, 1 /* RESIZE_MODE_FORCED */);
                        AppLogger.i(TAG, "resizeTask " + bounds + " OK");
                    } catch (Exception e) {
                        AppLogger.w(TAG, "resizeTask: " + e.getMessage());
                    }
                }

                mMainHandler.post(() -> {
                    if (mDestroyed) return;
                    if (callback != null) callback.onResult(true);
                });

            } catch (Exception e) {
                boolean stripped = (e instanceof NoSuchMethodException)
                        || (e.getCause() instanceof NoSuchMethodException);
                if (stripped && sMoveTaskToDisplayAvailable == null) {
                    sMoveTaskToDisplayAvailable = Boolean.FALSE;
                    AppLogger.w(TAG, (enforceOnly ? "enforceTaskOnDisplay" : "moveTaskToDisplay")
                            + ": moveTaskToDisplay stripped on ROM — using launcher fallback");
                } else if (!stripped) {
                    AppLogger.e(TAG, (enforceOnly ? "enforceTaskOnDisplay" : "moveTaskToDisplay")
                            + " error", e);
                }
                if (mDestroyed) return;
                if (!enforceOnly) fallbackLaunch(packageName, targetDisplayId, callback);
            }
        });
    }

    private void fallbackLaunch(final String packageName, final int targetDisplayId,
                                 final LaunchCallback callback) {
        mMainHandler.post(() -> {
            if (mDestroyed) return;
            if (targetDisplayId > 0) {
                launchOnDashboard(packageName, callback);
            } else {
                boolean ok = mLauncher.launchOnMainDisplay(packageName);
                if (callback != null) callback.onResult(ok);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch on cluster
    // ─────────────────────────────────────────────────────────────────────────

    public void launchOnDashboard(final String packageName, final LaunchCallback callback) {
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
                // Hop the whole cascade off the main thread. cleanFissionStacks() is a
                // synchronous binder/proxy transact, applyForLaunch() does a shell round-trip
                // and startActivityViaIAM() bootstraps the uid-2000 proxy daemon — none of that
                // may run on the UI thread (ANR when the daemon is cold). Reusing the existing
                // single-thread sMoveTaskExecutor keeps this launch serialized behind any
                // in-flight moveTaskToDisplay task-move, so no new races are introduced.
                sMoveTaskExecutor.execute(() -> {
                final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                AppLogger.i(TAG, "Launching on display=" + displayId + " → " + packageName);
                if (displayId <= 0) {
                    AppLogger.w(TAG, "launchOnDashboard: cluster display not ready (id="
                            + displayId + ") — aborting launch for " + packageName);
                    postLaunchResult(callback, false);
                    return;
                }
                try {
                    String cleanLog = com.byd.dashcast.proxy.ProxyClient
                            .cleanFissionStacks(displayId);
                    AppLogger.d(TAG, "cleanFissionStacks(" + displayId + ")\n" + cleanLog);
                } catch (Throwable ce) {
                    AppLogger.w(TAG, "cleanFissionStacks failed: " + ce.getMessage());
                }
                boolean needsDpiSettle = com.byd.dashcast.cluster.dpi.ClusterDpiManager
                        .applyForLaunch(ClusterService.this, packageName, displayId);
                Runnable doLaunch = () -> {
                    try {
                        android.content.Intent launchIntent =
                                getPackageManager().getLaunchIntentForPackage(packageName);
                        if (launchIntent == null) {
                            AppLogger.e(TAG, "No launch intent for " + packageName);
                            postLaunchResult(callback, false);
                            return;
                        }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                        opts.setLaunchDisplayId(displayId);
                        if (displayId > 0) applyClusterFreeformBounds(opts, displayId, packageName);
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
                            if (daemonLaunchSync(packageName, displayId, clW, clH)) {
                                AppLogger.i(TAG, "launchOnDashboard OK (daemon) → " + packageName);
                                postLaunchResult(callback, true);
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
                        try {
                            iamOk = startActivityViaIAM(launchIntent, opts, displayId);
                        } catch (Throwable iamErr) {
                            AppLogger.w(TAG, "startActivityViaIAM threw ("
                                    + iamErr.getClass().getSimpleName() + ": " + iamErr.getMessage() + ")");
                            iamOk = false;
                            iamThrew = true;
                        }
                        if (!iamOk && !daemonTried && AdbLocalClient.isDiLink5Safe(ClusterService.this)) {
                            // DL5 app-side DENIED (cross-user) and the daemon was not tried yet
                            // (use_legacy_path ON, or daemon was down at decision time) — the daemon
                            // HOLDS the cross-user permission, so it is the only path that can land it.
                            AppLogger.w(TAG, "DL5: app-side launch failed — routing via proxy daemon launchAndForce");
                            boolean ok = daemonLaunchSync(packageName, displayId, clW, clH);
                            AppLogger.i(TAG, "launchOnDashboard → daemon force path (ok=" + ok + ") → " + packageName);
                            postLaunchResult(callback, ok);
                        } else if (iamThrew) {
                            // Non-DL5 (DL3) and EVERY app-side attempt threw — a genuine failure.
                            AppLogger.e(TAG, "launchOnDashboard failed (app-side) → " + packageName);
                            postLaunchResult(callback, false);
                        } else {
                            // iamOk==true, OR (non-DL5 && returned false = startActivity fallback
                            // launched it — the normal DL3 path). Report success so it is tracked.
                            AppLogger.i(TAG, "launchOnDashboard OK → " + packageName);
                            postLaunchResult(callback, true);
                        }
                    } catch (Exception e) {
                        AppLogger.e(TAG, "launchOnDashboard error for " + packageName, e);
                        postLaunchResult(callback, false);
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
                if (mDestroyed) return;
                doLaunch.run();
                });
            }
        };
        mMainHandler.postDelayed(mPendingLaunchRunnable, 2000);
    }

    public void launchOnDashboardWithBounds(final String packageName,
            final int left, final int top, final int right, final int bottom,
            final LaunchCallback callback) {
        AppLogger.log(TAG, "launchOnDashboardWithBounds 500ms → " + packageName
                + " [" + left + "," + top + "," + right + "," + bottom + "]");
        // Keep the 500ms schedule on the main handler (preserves timing + cancel semantics),
        // then hop the whole binder/proxy/shell cascade onto the shared single-thread move-task
        // executor so the main thread never blocks and launches stay serialized behind any
        // in-flight task-move.
        mMainHandler.postDelayed(() -> sMoveTaskExecutor.execute(() -> {
            final int displayId = mDisplayHelper.getKnownClusterDisplayId();
            if (displayId <= 0) {
                AppLogger.w(TAG, "launchOnDashboardWithBounds: cluster display not ready (id="
                        + displayId + ") — aborting launch for " + packageName);
                postLaunchResult(callback, false);
                return;
            }
            if (displayId > 0) {
                try {
                    com.byd.dashcast.proxy.ProxyClient.cleanFissionStacks(displayId);
                } catch (Throwable ce) {
                    AppLogger.w(TAG, "cleanFissionStacks(WithBounds) failed: " + ce.getMessage());
                }
            }
            boolean needsDpiSettle = com.byd.dashcast.cluster.dpi.ClusterDpiManager
                    .applyForLaunch(ClusterService.this, packageName, displayId);
            Runnable doLaunchWithBounds = () -> {
                try {
                    android.content.Intent launchIntent =
                            getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        AppLogger.e(TAG, "launchOnDashboardWithBounds: no intent for " + packageName);
                        postLaunchResult(callback, false);
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
                    boolean iamOkWB = startActivityViaIAM(launchIntent, opts, displayId);
                    if (!iamOkWB && AdbLocalClient.isDiLink5Safe(ClusterService.this)) {
                        AppLogger.w(TAG, "DL5: IAM fell back to startActivity (WithBounds) — routing via proxy daemon launchAndForce");
                        int wbW = right - left, wbH = bottom - top;
                        // Use the synchronous, verdict-checked daemon launch — same path
                        // launchOnDashboard uses. The old launchViaDaemonForce was
                        // fire-and-forget, so WithBounds reported unconditional success even
                        // when the DL5 daemon launch actually failed, mistracking the app.
                        boolean okWB = daemonLaunchSync(packageName, displayId,
                                wbW > 0 ? wbW : clusterWidthOr(1920),
                                wbH > 0 ? wbH : clusterHeightOr(720));
                        AppLogger.i(TAG, "launchOnDashboardWithBounds → daemon force path (ok="
                                + okWB + ") → " + packageName);
                        postLaunchResult(callback, okWB);
                    } else {
                        AppLogger.i(TAG, "launchOnDashboardWithBounds OK display=" + displayId);
                        postLaunchResult(callback, true);
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "launchOnDashboardWithBounds error", e);
                    postLaunchResult(callback, false);
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
            if (mDestroyed) return;
            doLaunchWithBounds.run();
        }), 500);
    }

    /**
     * Marshals a launch callback result back onto the main looper. The launch cascades now
     * run on {@link #sMoveTaskExecutor}; per the hardening contract only {@code onResult} is
     * allowed back on the main thread. Follows the same drop-on-destroy contract already used
     * by {@link #fallbackLaunch} and moveTaskToDisplayInternal so we never call back into a
     * torn-down service.
     */
    private void postLaunchResult(final LaunchCallback callback, final boolean success) {
        if (callback == null) return;
        mMainHandler.post(() -> {
            if (mDestroyed) return;
            callback.onResult(success);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void applyClusterFreeformBounds(android.app.ActivityOptions opts,
                                             int displayId, String packageName) {
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
            if (d != null) d.getRealSize(sz);
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
        int insetH = getInsetH(packageName);
        int insetV = getInsetV(packageName);
        Rect bounds = new Rect(insetH, insetV, sz.x - insetH, sz.y - insetV);
        try {
            java.lang.reflect.Method setLB = android.app.ActivityOptions.class
                    .getDeclaredMethod("setLaunchBounds", Rect.class);
            setLB.setAccessible(true);
            setLB.invoke(opts, bounds);
            AppLogger.i(TAG, "cluster FREEFORM bounds=" + bounds + " display=" + displayId);
        } catch (Exception e) {
            AppLogger.w(TAG, "setLaunchBounds: " + e.getMessage());
        }
        if (displayId > 0) {
            if (AdbLocalClient.isDiLink5Safe(this)) {
                AppLogger.d(TAG, "DL5: skipping wm overscan (removed in API 30+)");
            } else {
                ShellGateway.execShell(this, "wm overscan "
                        + insetH + "," + insetV + "," + insetH + "," + insetV
                        + " -d " + displayId);
                AppLogger.i(TAG, "Applied wm overscan on display " + displayId);
            }
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
        sMoveTaskExecutor.execute(() -> {
            try {
                if (!ProxyClient.isConnected()) ProxyClient.connect(ClusterService.this);
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
            tryClusterFixedActivityExperiment(displayId, packageName);
            // Post-launch verification: ~1.5 s after the cascade, dump what is actually on
            // the cluster display (package / visible / windowing mode / bounds) so the next
            // bug report shows whether the app stayed put and in which mode it rendered.
            // Runs on the diagnostic executor so its 1.5 s sleep never holds the move-task
            // worker (frees it immediately for a queued eviction / task-move).
            sDiagExecutor.execute(() -> verifyClusterDisplayState(displayId, packageName));
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
                                      final int width, final int height) {
        boolean ok;
        try {
            if (!ProxyClient.isConnected()) ProxyClient.connect(ClusterService.this);
            String log = ProxyClient.launchAndForce(packageName, null, displayId, width, height);
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
        tryClusterFixedActivityExperiment(displayId, packageName);
        sDiagExecutor.execute(() -> verifyClusterDisplayState(displayId, packageName));
        return ok;
    }

    /**
     * Diagnostic only (no behaviour change): after a DL5 daemon launch, query the
     * cluster display's top tasks and log them to the journal. Helps determine whether
     * the launched app stays on the display and in which windowing mode / bounds.
     */
    private void verifyClusterDisplayState(final int displayId, final String packageName) {
        try { Thread.sleep(1500); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); return;
        }
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
                boolean mirrorActive = mMirrorManager != null && mMirrorManager.isMirrorActive();
                AppLogger.i(TAG, "DL5 post-launch display " + displayId + " state ("
                        + packageName + ") — in-app mirror active=" + mirrorActive
                        + " [mirror reads layerStack 2 = exact panel content]:\n"
                        + (out == null || out.trim().isEmpty()
                        ? "(nothing on display " + displayId + ")" : out.trim()));
            }
            @Override public void onError(String err) {
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
    private void tryClusterFixedActivityExperiment(final int displayId, final String packageName) {
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
        ShellGateway.execShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                AppLogger.i(TAG, "AAOS start-fixed-activity experiment (" + packageName
                        + " → display " + displayId + "):\n"
                        + (out == null || out.trim().isEmpty() ? "(no output)" : out.trim()));
            }
            @Override public void onError(String err) {
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
        if (isDl3SingleOsFission()) {
            AppLogger.w(TAG, "DL3 single-OS fission — skipping restartProjection (no projectable cluster display)");
            return;
        }
        if (mDisplayHelper != null) mDisplayHelper.start();
    }

    public void stopProjectionNoAdb() {
        AppLogger.log(TAG, "stopProjectionNoAdb requested");
        if (!AdbLocalClient.isDiLink5Safe(this)) {
            ShellGateway.execShell(this, "wm overscan reset -d 1");
        }
        try {
            com.byd.dashcast.cluster.dpi.ClusterDpiManager.restore(
                    this, mDisplayHelper.getKnownClusterDisplayId());
        } catch (Throwable t) {
            AppLogger.w(TAG, "DPI restore (stopProjectionNoAdb) failed: " + t.getMessage());
        }
        mProjectionActive = false;
        mDisplayHelper.stopWithoutAdb();
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
        updateNotification(getString(R.string.notif_cluster_active, displayId));
        if (displayId > 0) {
            if (AdbLocalClient.isDiLink5Safe(this)) {
                AppLogger.d(TAG, "DL5: skipping display-level wm overscan (API 30+)");
            } else {
                final int insetH = getInsetH(null);
                final int insetV = getInsetV(null);
                ShellGateway.execShell(this,
                        "wm overscan " + insetH + "," + insetV
                        + "," + insetH + "," + insetV + " -d " + displayId);
                AppLogger.i(TAG, "wm overscan on display " + displayId
                        + " inset=" + insetH + "," + insetV);
            }
        } else {
            AppLogger.w(TAG, "wm overscan skipped: displayId=" + displayId);
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
        // On a DL3 single-OS car the "disconnect" is really "activation timed out because no
        // VirtualDisplay can exist" — tell the user projection is unavailable rather than the
        // generic "disconnected" (which reads like a transient glitch they should retry).
        updateNotification(getString(isDl3SingleOsFission()
                ? R.string.dl3_singleos_cluster_unsupported_title
                : R.string.notif_cluster_disconnected));
        if (mListener != null) mListener.onClusterDisplayDisconnected();
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
