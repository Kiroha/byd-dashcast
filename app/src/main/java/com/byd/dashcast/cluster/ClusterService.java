package com.byd.dashcast.cluster;

import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.MainActivity;
import com.byd.dashcast.R;
import com.byd.dashcast.ime.KeyboardBridgeActivity;
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

    private static final String PKG_FORCE_FRESH_LAUNCH = "com.telenav.app.arp";

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
        startNativeProjection();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
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

    private void startNativeProjection() {
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
        int insetH = getInsetH(packageName);
        int insetV = getInsetV(packageName);
        Rect bounds = new Rect(insetH, insetV, cw - insetH, ch - insetV);

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
                mPendingLaunchRunnable    = null;
                mPendingDashboardCallback = null;
                final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                AppLogger.i(TAG, "Launching on display=" + displayId + " → " + packageName);
                if (displayId > 0) {
                    try {
                        String cleanLog = com.byd.dashcast.proxy.ProxyClient
                                .cleanFissionStacks(displayId);
                        AppLogger.d(TAG, "cleanFissionStacks(" + displayId + ")\n" + cleanLog);
                    } catch (Throwable ce) {
                        AppLogger.w(TAG, "cleanFissionStacks failed: " + ce.getMessage());
                    }
                }
                boolean needsDpiSettle = com.byd.dashcast.cluster.dpi.ClusterDpiManager
                        .applyForLaunch(ClusterService.this, packageName, displayId);
                Runnable doLaunch = () -> {
                    try {
                        android.content.Intent launchIntent =
                                getPackageManager().getLaunchIntentForPackage(packageName);
                        if (launchIntent == null) {
                            AppLogger.e(TAG, "No launch intent for " + packageName);
                            if (callback != null) callback.onResult(false);
                            return;
                        }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                        opts.setLaunchDisplayId(displayId);
                        if (displayId > 0) applyClusterFreeformBounds(opts, displayId, packageName);
                        if (AdbLocalClient.isDiLink5Safe(ClusterService.this)) {
                            startActivityViaShell(packageName, displayId, launchIntent);
                        } else {
                            startActivityViaIAM(launchIntent, opts);
                        }
                        AppLogger.i(TAG, "launchOnDashboard OK → " + packageName);
                        if (callback != null) callback.onResult(true);
                    } catch (Exception e) {
                        AppLogger.e(TAG, "launchOnDashboard error for " + packageName, e);
                        if (callback != null) callback.onResult(false);
                    }
                };
                if (needsDpiSettle) {
                    mMainHandler.postDelayed(doLaunch,
                            com.byd.dashcast.cluster.dpi.ClusterDpiManager.SETTLE_MS);
                } else {
                    doLaunch.run();
                }
            }
        };
        mMainHandler.postDelayed(mPendingLaunchRunnable, 2000);
    }

    public void launchOnDashboardWithBounds(final String packageName,
            final int left, final int top, final int right, final int bottom,
            final LaunchCallback callback) {
        AppLogger.log(TAG, "launchOnDashboardWithBounds 500ms → " + packageName
                + " [" + left + "," + top + "," + right + "," + bottom + "]");
        mMainHandler.postDelayed(() -> {
            final int displayId = mDisplayHelper.getKnownClusterDisplayId();
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
                        if (callback != null) callback.onResult(false);
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
                    if (AdbLocalClient.isDiLink5Safe(ClusterService.this)) {
                        startActivityViaShell(packageName, displayId, launchIntent);
                    } else {
                        startActivityViaIAM(launchIntent, opts);
                    }
                    AppLogger.i(TAG, "launchOnDashboardWithBounds OK display=" + displayId);
                    if (callback != null) callback.onResult(true);
                } catch (Exception e) {
                    AppLogger.e(TAG, "launchOnDashboardWithBounds error", e);
                    if (callback != null) callback.onResult(false);
                }
            };
            if (needsDpiSettle) {
                mMainHandler.postDelayed(doLaunchWithBounds,
                        com.byd.dashcast.cluster.dpi.ClusterDpiManager.SETTLE_MS);
            } else {
                doLaunchWithBounds.run();
            }
        }, 500);
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

    private void startActivityViaIAM(android.content.Intent intent,
                                      android.app.ActivityOptions opts) {
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
        } catch (Exception ex) {
            AppLogger.w(TAG, "startActivityViaIAM → fallback context: " + ex.getMessage());
            startActivity(intent, opts.toBundle());
        }
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
    }

    @Override
    public void onDashboardDisplayDisconnected() {
        AppLogger.log(TAG, "Cluster display disconnected");
        mLauncher.setDashboardDisplayId(-1);
        updateNotification(getString(R.string.notif_cluster_disconnected));
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
