package com.byd.dashcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.view.Display;

import com.byd.dashcast.dashboard.ClusterInputForwarder;
import com.byd.dashcast.dashboard.ClusterMirrorManager;
import com.byd.dashcast.dashboard.DashboardDisplayHelper;
import com.byd.dashcast.dashboard.DashboardLauncher;

/**
 * ClusterService — Foreground Service that maintains projection on the cluster
 * independently of the MainActivity lifecycle.
 *
 * The user can put the app in the background (return to the main BYD screen,
 * use other apps) without the cluster projection being interrupted.
 *
 * Lifecycle:
 *   - Started by MainActivity.onCreate() via startForegroundService()
 *   - MainActivity binds/unbinds in onStart()/onStop() to access data
 *   - The service keeps running until stopSelf() is called
 *   - stopProjection() is called explicitly (Restore BYD button or app destruction)
 *
 * Communication with MainActivity:
 *   - LocalBinder.getService() returns the service instance
 *   - MainActivity implements ClusterService.Listener for display callbacks
 */
public class ClusterService extends Service implements DashboardDisplayHelper.Listener {

    private static final String TAG = "ClusterService";
    private static final String CHANNEL_ID = "cluster_projection";
    private static final int NOTIF_ID = 1;
    public static boolean sIsRunning = false;

    // Overscan inset values are stored in SharedPreferences and editable via SettingsActivity.
    // Defaults: H=80 (left/right), V=50 (top/bottom). Read at each use so changes apply live.

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
    // Reusable handler on the main thread (replaces ephemeral new Handler() calls).
    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning = true;
        mDisplayHelper  = new DashboardDisplayHelper(this, this);
        mLauncher       = new DashboardLauncher(this);
        mMirrorManager  = new ClusterMirrorManager();
        mInputForwarder = new ClusterInputForwarder(this);
        
        // Pre-start the MirrorDaemon (app_process via ADB) for Real-Time Cluster Mirror + Touch
        // Executed here instead of in MainActivity to avoid restarting it on every screen rotation.
        AdbLocalClient.startMirrorDaemon(this);
        
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Cluster: initializing…"));
        AppLogger.log(TAG, "ClusterService created — starting native projection");
        mProjectionActive = true;
        // Signature + permissions diagnostics — debug only (opens an ADB connection).
        if (BuildConfig.DEBUG) {
            AdbLocalClient.dumpSignatureAndPermissions(this);
        }
        startNativeProjection();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: the system recreates the service if killed due to low memory
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Keep listener null to avoid leaks if MainActivity is destroyed.
        // return false: each new bindService() call goes through onBind() normally.
        mListener = null;
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sIsRunning = false;
        mListener = null;
        // Cancel all pending Runnables on mMainHandler BEFORE release():
        // launchOnDashboard (postDelayed 2s) could post a callback
        // on a destroyed service (NPE / ADB thread leak).
        mMainHandler.removeCallbacksAndMessages(null);
        // release() = preview + cluster overlay (stopMirror() only releases the preview)
        mMirrorManager.release();
        if (mProjectionActive) {
            mDisplayHelper.stop();
        }
        AppLogger.log(TAG, "ClusterService destroyed");
    }

    // ── Public API (called from MainActivity via the binder) ─────────────────

    private void startNativeProjection() {
        AppLogger.i(TAG, "Starting cluster projection (native)...");
        mDisplayHelper.start();
    }

    /** Returns the current horizontal overscan inset (left + right) from persistent settings. */



    public int getInsetH(String packageName) {
        android.content.SharedPreferences prefs =
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        int defaultVal = prefs.getInt(SettingsActivity.PREF_INSET_H, SettingsActivity.DEFAULT_INSET_H);
        if (packageName == null || packageName.isEmpty()) return defaultVal;
        return prefs.getInt("inset_h_" + packageName, defaultVal);
    }

    public int getInsetV(String packageName) {
        android.content.SharedPreferences prefs =
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        int defaultVal = prefs.getInt(SettingsActivity.PREF_INSET_V, SettingsActivity.DEFAULT_INSET_V);
        if (packageName == null || packageName.isEmpty()) return defaultVal;
        return prefs.getInt("inset_v_" + packageName, defaultVal);
    }


    
    public void resizeActiveTask(int taskId, String packageName) {
        if (taskId <= 0) return;
        try {
            Class<?> iAtmClass = Class.forName("android.app.IActivityTaskManager");
            Object iatm;
            try {
                iatm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            } catch (Exception e) {
                iatm = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            }

            int insetH = getInsetH(packageName);
            int insetV = getInsetV(packageName);
            android.graphics.Rect bounds = new android.graphics.Rect(
                    insetH, insetV, 1920 - insetH, 720 - insetV);
            
            iAtmClass.getMethod("resizeTask", int.class, android.graphics.Rect.class, int.class)
                    .invoke(iatm, taskId, bounds, 1 /* RESIZE_MODE_FORCED */);
            AppLogger.i(TAG, "resizeActiveTask " + packageName + " " + bounds + " OK");
        } catch (Exception e) {
            AppLogger.w(TAG, "resizeActiveTask failed: " + e.getMessage());
        }
    }

    public void setListener(Listener listener) {
        mListener = listener;
                // If the display is already known, notify immediately (Activity reconnection)
        int knownId = mDisplayHelper.getKnownClusterDisplayId();
        if (knownId > 0 && mListener != null) {
            Display d = null;
            try {
                android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                    getSystemService(DISPLAY_SERVICE);
                if (dm != null) d = dm.getDisplay(knownId);
            } catch (Exception e) {
                AppLogger.w(TAG, "getDisplay(" + knownId + ") failed: " + e.getMessage());
            }
            mListener.onClusterDisplayConnected(d, knownId);
        }
    }

    public DashboardLauncher getLauncher() {
        return mLauncher;
    }

    public ClusterMirrorManager getMirrorManager() {
        return mMirrorManager;
    }

    public ClusterInputForwarder getInputForwarder() {
        return mInputForwarder;
    }

    public interface LaunchCallback {
        void onResult(boolean success);
    }

    // ── Task reparenting ─────────────────────────────────────────────────────

    /**
     * Finds the taskId of the running task whose top activity belongs to packageName.
     * Must be called from a background thread.
     * Returns -1 if no running task is found.
     */
    public int findRunningTaskId(String packageName) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    am.getRunningTasks(50);
            for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
                if (t.topActivity != null
                        && packageName.equals(t.topActivity.getPackageName())) {
                    AppLogger.d(TAG, "findRunningTaskId " + packageName + " → taskId=" + t.id);
                    return t.id;
                }
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "findRunningTaskId: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Moves the running task for packageName to targetDisplayId using
     * IActivityTaskManager.moveTaskToDisplay() (hidden API, reflection — no relaunch, no state loss).
     *
     * For the cluster (targetDisplayId > 0), also applies FREEFORM + inset bounds after the move.
     *
     * Fallback: if the task is not found (app not yet running) or the IATM call fails,
     *   - targetDisplayId > 0 → launchOnDashboard() (fresh launch with 2s delay)
     *   - targetDisplayId == 0 → mLauncher.launchOnMainDisplay() (relaunch on main)
     *
     * Callback is always called on the main thread.
     */
    public void moveTaskToDisplay(final String packageName, final int targetDisplayId,
                                   final LaunchCallback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int taskId = findRunningTaskId(packageName);
                    if (taskId == -1) {
                        AppLogger.w(TAG, "moveTaskToDisplay: no running task for "
                                + packageName + " → fallback launch");
                        fallbackLaunch(packageName, targetDisplayId, callback);
                        return;
                    }

                    // IActivityTaskManager.moveTaskToDisplay(taskId, displayId)
                    Class<?> atmClass  = Class.forName("android.app.ActivityTaskManager");
                    Object   iatm      = atmClass.getMethod("getService").invoke(null);
                    Class<?> iAtmClass = iatm.getClass();
                    iAtmClass.getMethod("moveTaskToDisplay", int.class, int.class)
                            .invoke(iatm, taskId, targetDisplayId);
                    AppLogger.i(TAG, "moveTaskToDisplay taskId=" + taskId
                            + " → display=" + targetDisplayId + " OK");

                    if (targetDisplayId > 0) {
                        Thread.sleep(300); // let WM settle after the display move

                        // WINDOWING_MODE_FREEFORM = 5
                        try {
                            iAtmClass.getMethod("setTaskWindowingMode",
                                    int.class, int.class, boolean.class)
                                    .invoke(iatm, taskId, 5, true);
                            AppLogger.i(TAG, "setTaskWindowingMode(FREEFORM) OK");
                        } catch (Exception e) {
                            AppLogger.w(TAG, "setTaskWindowingMode: " + e.getMessage());
                        }
                        // Apply the same inset bounds as applyClusterFreeformBounds()
                        try {
                            int insetH = getInsetH(packageName);
                            int insetV = getInsetV(packageName);
                            android.graphics.Rect bounds = new android.graphics.Rect(
                                    insetH, insetV, 1920 - insetH, 720 - insetV);
                            iAtmClass.getMethod("resizeTask",
                                    int.class, android.graphics.Rect.class, int.class)
                                    .invoke(iatm, taskId, bounds, 1 /* RESIZE_MODE_FORCED */);
                            AppLogger.i(TAG, "resizeTask " + bounds + " OK");
                        } catch (Exception e) {
                            AppLogger.w(TAG, "resizeTask: " + e.getMessage());
                        }
                    }

                    mMainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (callback != null) callback.onResult(true);
                        }
                    });

                } catch (Exception e) {
                    AppLogger.e(TAG, "moveTaskToDisplay error", e);
                    fallbackLaunch(packageName, targetDisplayId, callback);
                }
            }
        }, "move-task-thread").start();
    }

    private void fallbackLaunch(final String packageName, final int targetDisplayId,
                                 final LaunchCallback callback) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                if (targetDisplayId > 0) {
                    launchOnDashboard(packageName, callback);
                } else {
                    boolean ok = mLauncher.launchOnMainDisplay(packageName);
                    if (callback != null) callback.onResult(ok);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Launches an app on the cluster.
     * Activation sequence:
     *   1. sendInfo(1000, 30) — Seal EU screen size (CONFIRMED 16/04/2026)
     *   2. sendInfo(1000, 16) — Qt standby
     * Both commands are already sent by activateClusterDisplay() at service startup.
     * This method adds the post-activation delay then launches the app.
     * The callback is called on the main thread.
     */
    public void launchOnDashboard(final String packageName, final LaunchCallback callback) {
        // sendInfo(16) already sent by activateClusterDisplay() — do not call again here
        // (risk of toggling Qt if cmd=16 is not idempotent).
        // For the direct path (tap app without going through activateCluster),
        // activateClusterDisplay() was called at service startup → Qt already in standby.
        AppLogger.log(TAG, "launchOnDashboard — 2s delay → " + packageName);
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                // Direct launch via IActivityManager on the Freedom display (proven v2.29).
                final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                AppLogger.i(TAG, "Launching via IActivityManager on display=" + displayId + " → " + packageName);
                try {
                    android.content.Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        AppLogger.e(TAG, "No launch intent found for " + packageName);
                        if (callback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override public void run() { callback.onResult(false); }
                            });
                        }
                        return;
                    }
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(displayId);
                    if (displayId > 0) applyClusterFreeformBounds(opts, displayId, packageName);

                    startActivityViaIAM(launchIntent, opts);

                    AppLogger.i(TAG, "launchOnDashboard OK → " + packageName);
                    if (callback != null) {
                        mMainHandler.post(new Runnable() {
                            @Override public void run() { callback.onResult(true); }
                        });
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "Global launch error for " + packageName, e);
                    if (callback != null) {
                        mMainHandler.post(new Runnable() {
                            @Override public void run() { callback.onResult(false); }
                        });
                    }
                }
            }
        }, 2000);
    }

    /**
     * Launches an app on the cluster with explicit FREEFORM bounds (split mode).
     * Since the display is already active, the delay is reduced to 500 ms.
     *
     * Uses the same IActivityManager path as launchOnDashboard() to avoid the
     * broadcast-to-daemon approach (registerReceiver removed from daemon — SecurityException
     * since systemMain()); the broadcast was silently dropped, causing split mode to
     * report success while the second app was never actually launched.
     */
    public void launchOnDashboardWithBounds(final String packageName,
            final int left, final int top, final int right, final int bottom,
            final LaunchCallback callback) {
        AppLogger.log(TAG, "launchOnDashboardWithBounds 500ms → " + packageName
                + " bounds=[" + left + "," + top + "," + right + "," + bottom + "]");
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                try {
                    android.content.Intent launchIntent =
                            getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        AppLogger.e(TAG, "launchOnDashboardWithBounds: no launch intent for "
                                + packageName);
                        if (callback != null) callback.onResult(false);
                        return;
                    }
                    launchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(displayId);
                    // WINDOWING_MODE_FREEFORM = 5
                    try {
                        java.lang.reflect.Method setWM = android.app.ActivityOptions.class
                                .getDeclaredMethod("setLaunchWindowingMode", int.class);
                        setWM.setAccessible(true);
                        setWM.invoke(opts, 5);
                    } catch (Exception e) {
                        AppLogger.w(TAG, "setLaunchWindowingMode unavailable: " + e.getMessage());
                    }
                    // Explicit bounds from the caller (split slot geometry)
                    try {
                        java.lang.reflect.Method setLB = android.app.ActivityOptions.class
                                .getDeclaredMethod("setLaunchBounds",
                                        android.graphics.Rect.class);
                        setLB.setAccessible(true);
                        setLB.invoke(opts, new android.graphics.Rect(left, top, right, bottom));
                    } catch (Exception e) {
                        AppLogger.w(TAG, "setLaunchBounds unavailable: " + e.getMessage());
                    }
                    startActivityViaIAM(launchIntent, opts);
                    AppLogger.i(TAG, "launchOnDashboardWithBounds OK [" + left + "," + top
                            + "," + right + "," + bottom + "] display=" + displayId);
                    if (callback != null) callback.onResult(true);
                } catch (Exception e) {
                    AppLogger.e(TAG, "launchOnDashboardWithBounds error", e);
                    if (callback != null) callback.onResult(false);
                }
            }
        }, 500);
    }

    public int getDisplayId() {
        return mDisplayHelper.getKnownClusterDisplayId();
    }

    /**
     * Applies WINDOWING_MODE_FREEFORM + inset bounds to ActivityOptions for cluster launches.
     * Both @hide APIs are accessed via reflection.
     * Inset (H/V from SettingsActivity prefs) avoids content clipping at the
     * physical curved edges of the BYD Seal EU cluster screen.
     */
    private void applyClusterFreeformBounds(android.app.ActivityOptions opts, int displayId, String packageName) {
        try {
            java.lang.reflect.Method setWM = android.app.ActivityOptions.class
                    .getDeclaredMethod("setLaunchWindowingMode", int.class);
            setWM.setAccessible(true);
            setWM.invoke(opts, 5); // WINDOWING_MODE_FREEFORM = 5
        } catch (Exception e) {
            AppLogger.w(TAG, "setLaunchWindowingMode unavailable: " + e.getMessage());
        }
        android.graphics.Point sz = new android.graphics.Point(1920, 720); // confirmed: fission_bg_xdjaVirtualSurface is 1920×720 (not 1080)
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display d = (dm != null) ? dm.getDisplay(displayId) : null;
            if (d != null) d.getRealSize(sz);
        } catch (Exception e) {
            AppLogger.w(TAG, "getRealSize failed: " + e.getMessage());
        }
        int insetH = getInsetH(packageName);
        int insetV = getInsetV(packageName);
        android.graphics.Rect bounds = new android.graphics.Rect(
                insetH, insetV, sz.x - insetH, sz.y - insetV);
        try {
            java.lang.reflect.Method setLB = android.app.ActivityOptions.class
                    .getDeclaredMethod("setLaunchBounds", android.graphics.Rect.class);
            setLB.setAccessible(true);
            setLB.invoke(opts, bounds);
            AppLogger.i(TAG, "cluster FREEFORM bounds=" + bounds
                    + " display=" + displayId + " " + sz.x + "\u00d7" + sz.y);
        } catch (Exception e) {
            AppLogger.w(TAG, "setLaunchBounds unavailable: " + e.getMessage());
        }
        
        // ---- BYD SPECIFIC FIX ----
        // Android's setLaunchBounds is ignored on BYD VirtualDisplays (Presentation).
        // Since we run only one app at a time on the cluster, we apply the app-specific 
        // bounds directly as a display overscan at launch.
        if (displayId > 0) {
            AdbLocalClient.executeShell(this, "wm overscan " + insetH + "," + insetV + "," + insetH + "," + insetV + " -d " + displayId);
            AppLogger.i(TAG, "Applied app-specific wm overscan during launch on display " + displayId);
        }
    }

    /**
     * Invokes IActivityManager.startActivityAsUser() via reflection, with Context.startActivity() fallback.
     * Shared by launchOnDashboard() and launchOnSpecificDisplay() — eliminates 15 duplicated lines.
     */
    private void startActivityViaIAM(android.content.Intent intent, android.app.ActivityOptions opts) {
        try {
            Class<?> amClass = Class.forName("android.app.ActivityManager");
            Object iam = amClass.getMethod("getService").invoke(null);
            Class<?> iAmClass = Class.forName("android.app.IActivityManager");
            Class<?> iAppThreadClass = Class.forName("android.app.IApplicationThread");
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

    public void restartProjection() {
        AppLogger.log(TAG, "restartProjection requested natively");
        if (mDisplayHelper != null) {
            mDisplayHelper.start();
        }
    }

    /** Cleanly stops the projection (sendInfo(0) + stopService AutoDisplayService). */
    public void stopProjection() {
        AppLogger.log(TAG, "stopProjection requested");
        AdbLocalClient.executeShell(this, "wm overscan reset -d 1");
        mProjectionActive = false;
        mDisplayHelper.stop();
        mLauncher.setDashboardDisplayId(-1);
        updateNotification("Cluster: stopped");
        stopSelf();
    }

    /**
     * Syncs the service state WITHOUT resending the ADB restore commands.
     * To be used when ADB restore has already been done upstream (e.g. restoreBydDashboard).
     * Avoids double sending sendInfo(18+0).
     */
    public void stopProjectionNoAdb() {
        AppLogger.log(TAG, "stopProjectionNoAdb requested (ADB already sent)");
        AdbLocalClient.executeShell(this, "wm overscan reset -d 1");
        mProjectionActive = false;
        mDisplayHelper.stopWithoutAdb();
        mLauncher.setDashboardDisplayId(-1);
        updateNotification("Cluster: stopped");
        stopSelf();
    }

    // ── DashboardDisplayHelper.Listener ─────────────────────────────────────

    @Override
    public void onDashboardDisplayConnected(final Display display, final int displayId) {
        AppLogger.log(TAG, "Cluster display connected: id=" + displayId);
        mLauncher.setDashboardDisplayId(displayId);
        // Update the forwarder with the real dimensions and ID of the display
        mInputForwarder.setClusterDisplay(display);
        mInputForwarder.setClusterDisplayId(displayId);
        updateNotification("Cluster active — display " + displayId);

        // Apply display-level insets via wm overscan so all apps launched on this display
        // stay within the safe area [INSET_H, INSET_V, 1920-INSET_H, 720-INSET_V].
        // This is the only approach that works on FLAG_PRESENTATION VirtualDisplays (Freedom)
        // because apps there are not tracked by the standard WM task system.
        // SAFETY GUARD: never apply overscan to the main display (id 0 or negative).
        if (displayId > 0) {
            final int insetH = getInsetH(null);
            final int insetV = getInsetV(null);
            AdbLocalClient.executeShell(this,
                    "wm overscan " + insetH + "," + insetV
                    + "," + insetH + "," + insetV
                    + " -d " + displayId);
            AppLogger.i(TAG, "wm overscan applied on display " + displayId
                    + " inset=" + insetH + "," + insetV);
        } else {
            AppLogger.w(TAG, "wm overscan skipped: displayId=" + displayId + " (must be > 0)");
        }

        if (mListener != null) {
            mListener.onClusterDisplayConnected(display, displayId);
        }
    }

    @Override
    public void onDashboardDisplayDisconnected() {
        AppLogger.log(TAG, "Cluster display disconnected");
        mLauncher.setDashboardDisplayId(-1);
        updateNotification("Cluster: disconnected");
        if (mListener != null) {
            mListener.onClusterDisplayDisconnected();
        }
    }

    // ── Notification (required for Foreground Service) ───────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Cluster projection",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Maintains display on the BYD cluster");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BYD App")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
