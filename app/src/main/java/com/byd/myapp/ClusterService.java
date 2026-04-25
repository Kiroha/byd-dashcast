package com.byd.myapp;

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

import com.byd.myapp.dashboard.ClusterInputForwarder;
import com.byd.myapp.dashboard.ClusterMirrorManager;
import com.byd.myapp.dashboard.DashboardDisplayHelper;
import com.byd.myapp.dashboard.DashboardLauncher;

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

    // ── Listener for MainActivity ───────────────────────────────────────────
    public interface Listener {
        void onClusterDisplayConnected(Display display, int displayId);
        void onClusterDisplayDisconnected();
        /** Freedom state checked at service startup (called on the main thread). */
        void onFreedomStatus(AdbLocalClient.FreedomStatus status);
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
    // Last known Freedom state — cached for replay in setListener()
    private AdbLocalClient.FreedomStatus mFreedomStatus = null;
    // Reusable handler on the main thread (replaces ephemeral new Handler() calls).
    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        mDisplayHelper  = new DashboardDisplayHelper(this, this);
        mLauncher       = new DashboardLauncher(this);
        mMirrorManager  = new ClusterMirrorManager();
        mInputForwarder = new ClusterInputForwarder(this);
        
        // Pre-start the MirrorDaemon (app_process via ADB) for Real-Time Cluster Mirror + Touch
        // Executed here instead of in MainActivity to avoid restarting it on every screen rotation.
        AdbLocalClient.startMirrorDaemon(this);
        
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Cluster: initializing…"));
        AppLogger.log(TAG, "ClusterService created — checking Freedom state");
        mProjectionActive = true;
        // Signature + permissions diagnostics — debug only (opens an ADB connection).
        if (BuildConfig.DEBUG) {
            AdbLocalClient.dumpSignatureAndPermissions(this);
        }
        checkAndStartWithFreedom();
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
        mListener = null;
        // Cancel all pending Runnables on mMainHandler BEFORE release():
        // launchOnDashboard (postDelayed 2s) could post a callback
        // on a destroyed service (NPE / ADB thread leak).
        mMainHandler.removeCallbacksAndMessages(null);
        // release() = preview + cluster overlay (stopMirror() only releases the preview)
        mMirrorManager.release(this);
        if (mProjectionActive) {
            mDisplayHelper.stop();
        }
        AppLogger.log(TAG, "ClusterService destroyed");
    }

    // ── Public API (called from MainActivity via the binder) ─────────────────

    /**
     * Checks Freedom state via ADB, then:
     *   ACTIVE      → mDisplayHelper.start() directly
     *   INACTIVE    → startFreedom() (force-stop + navigationType=1 + am start) → 2s delay → start()
     *   NOT_INSTALLED → mDisplayHelper.start() anyway (let activateClusterDisplay handle the fallback)
     */
    private void checkAndStartWithFreedom() {
        AppLogger.i(TAG, "Freedom: checking state before cluster activation");
        AdbLocalClient.checkFreedomState(this, new AdbLocalClient.FreedomStateCallback() {
            @Override public void onResult(final AdbLocalClient.FreedomStatus status) {
                mMainHandler.post(new Runnable() {
                    @Override public void run() {
                        mFreedomStatus = status;
                        AppLogger.i(TAG, "Freedom state: " + status);
                        if (mListener != null) mListener.onFreedomStatus(status);
                        if (status == AdbLocalClient.FreedomStatus.INACTIVE) {
                            // Freedom installed but inactive → start in 全屏導航 mode first
                            AppLogger.i(TAG, "Freedom INACTIVE → startFreedom() before cluster activation");
                            AdbLocalClient.startFreedom(ClusterService.this, true, new AdbLocalClient.Callback() {
                                @Override public void onSuccess(String r) {
                                    AppLogger.i(TAG, "startFreedom pre-check OK → " + r.trim().replace("\n", " "));
                                    // 2s delay: let Freedom create the fission VirtualDisplay
                                    mMainHandler.postDelayed(new Runnable() {
                                        @Override public void run() { mDisplayHelper.start(true); }
                                    }, 2000);
                                }
                                @Override public void onError(String err) {
                                    AppLogger.w(TAG, "startFreedom pre-check ERROR (continuing): " + err);
                                    mDisplayHelper.start();
                                }
                            });
                        } else {
                            // ACTIVE (fast path) or NOT_INSTALLED (fallback in activateClusterDisplay)
                            mDisplayHelper.start();
                        }
                    }
                });
            }
        });
    }

    public void setListener(Listener listener) {
        mListener = listener;
        // Replay cached Freedom state if available (check launched before bind)
        if (mFreedomStatus != null && mListener != null) {
            mListener.onFreedomStatus(mFreedomStatus);
        }
        // If the display is already known, notify immediately (Activity reconnection)
        int knownId = mDisplayHelper.getKnownClusterDisplayId();
        if (knownId > 0 && mListener != null) {
            Display d = null;
            try {
                android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                    getSystemService(DISPLAY_SERVICE);
                if (dm != null) d = dm.getDisplay(knownId);
            } catch (Exception ignored) {}
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
                    launchIntent.addFlags(0x10008000); // NEW_TASK | CLEAR_TASK
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(displayId);

                    // Force FREEFORM mode + explicit landscape bounds on the cluster.
                    // IMPORTANT: always use hardcoded 1920×720, never getRealSize().
                    // getRealSize() may return portrait values (720×1920) if a previous
                    // app rotated the cluster display — launching with portrait FREEFORM
                    // bounds would lock the new app in portrait mode from the start.
                    // The BYD Seal EU cluster VirtualDisplay is created at 1920×720 by
                    // AutoDisplayService and this physical size never changes.
                    //
                    // Note: this ROM (DiLink 3.0) overrides FREEFORM to FULLSCREEN on
                    // secondary displays. Apps can still call setRequestedOrientation() and
                    // rotate the display at runtime. To counter this, we also issue a
                    // 'wm size 1920x720 --display N' ADB command after each launch.
                    final int clusterW = 1920;
                    final int clusterH = 720;
                    try {
                        java.lang.reflect.Method setWM = android.app.ActivityOptions.class
                                .getDeclaredMethod("setLaunchWindowingMode", int.class);
                        setWM.setAccessible(true);
                        setWM.invoke(opts, 5); // WINDOWING_MODE_FREEFORM = 5

                        java.lang.reflect.Method setLB = android.app.ActivityOptions.class
                                .getDeclaredMethod("setLaunchBounds", android.graphics.Rect.class);
                        setLB.setAccessible(true);
                        setLB.invoke(opts, new android.graphics.Rect(0, 0, clusterW, clusterH));
                        AppLogger.i(TAG, "Cluster launch: FREEFORM + bounds(0,0," + clusterW + "," + clusterH + ")");
                    } catch (Exception eFreeform) {
                        AppLogger.w(TAG, "Cluster FREEFORM setup failed: " + eFreeform.getMessage());
                    }

                    startActivityViaIAM(launchIntent, opts);

                    // Freeze cluster display rotation to landscape (ROTATION_0).
                    // IWindowManager.freezeDisplayRotation() prevents any app from calling
                    // setRequestedOrientation() and rotating the display.
                    // This is the definitive fix; wm size below is a safety net.
                    freezeClusterRotation(displayId);

                    // Also force display size to landscape via ADB as a safety net.
                    // 'wm size WxH --display N' calls IWindowManager.setForcedDisplaySize()
                    // which overrides the logical display dimensions if they got rotated.
                    com.byd.myapp.AdbLocalClient.executeShell(ClusterService.this,
                            "wm size " + clusterW + "x" + clusterH + " --display " + displayId);

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
     */
    public void launchOnDashboardWithBounds(final String packageName,
            final int left, final int top, final int right, final int bottom,
            final LaunchCallback callback) {
        AppLogger.log(TAG, "launchOnDashboardWithBounds 500ms → " + packageName
                + " bounds=[" + left + "," + top + "," + right + "," + bottom + "]");
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                AdbLocalClient.launchDirectWithBounds(ClusterService.this, packageName,
                        displayId, left, top, right, bottom,
                        new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String report) {
                        AppLogger.i(TAG, "ADB trampoline bounds OK: "
                                + report.trim().replace("\n", " "));
                        if (callback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override public void run() { callback.onResult(true); }
                            });
                        }
                    }
                    @Override public void onError(String error) {
                        AppLogger.e(TAG, "ADB trampoline bounds FAILURE — "
                                + error.replace("\n", " | "));
                        if (callback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override public void run() { callback.onResult(false); }
                            });
                        }
                    }
                });
            }
        }, 500);
    }

    public int getDisplayId() {
        return mDisplayHelper.getKnownClusterDisplayId();
    }

    /**
     * Launches an app on a specific displayId (e.g. VirtualDisplay preview from ClusterMirrorManager).
     * Short delay (500ms) because the display is already ready (no Freedom activation needed).
     * Uses the same IActivityManager reflection as launchOnDashboard().
     */
    public void launchOnSpecificDisplay(final String packageName, final int displayId,
            final LaunchCallback callback) {
        AppLogger.i(TAG, "launchOnSpecificDisplay → " + packageName + " on display=" + displayId);
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    android.content.Intent launchIntent =
                            getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        AppLogger.e(TAG, "No intent for " + packageName);
                        if (callback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override public void run() { callback.onResult(false); }
                            });
                        }
                        return;
                    }
                    // MULTIPLE_TASK (not CLEAR_TASK): allows 2 independent instances
                    // without killing the instance already launched on the cluster (Display 2)
                    launchIntent.addFlags(0x10000000 | 0x08000000); // NEW_TASK | MULTIPLE_TASK
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(displayId);

                    startActivityViaIAM(launchIntent, opts);

                    AppLogger.i(TAG, "launchOnSpecificDisplay succeeded → " + packageName
                            + " on display=" + displayId);
                    if (callback != null) {
                        mMainHandler.post(new Runnable() {
                            @Override public void run() { callback.onResult(true); }
                        });
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "launchOnSpecificDisplay ERROR", e);
                    if (callback != null) {
                        mMainHandler.post(new Runnable() {
                            @Override public void run() { callback.onResult(false); }
                        });
                    }
                }
            }
        }, 500);
    }

    /**
     * Invokes IActivityManager.startActivityAsUser() via reflection, with Context.startActivity() fallback.
     * Shared by launchOnDashboard() and launchOnSpecificDisplay() — eliminates 15 duplicated lines.
     */
    /**
     * Freezes the rotation of the cluster display to ROTATION_0 (landscape) via
     * IWindowManager.freezeDisplayRotation(). This prevents any app running on the
     * cluster from changing the display orientation via setRequestedOrientation(),
     * which on DiLink 3.0 rotates the VirtualDisplay from landscape to portrait.
     *
     * Requires the platform signature (INTERNAL_SYSTEM_WINDOW) — granted with our cert.
     * Falls back silently: if not available on this ROM, the 'wm size' ADB command
     * already mitigates the issue after-the-fact.
     */
    private void freezeClusterRotation(int displayId) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            android.os.IBinder wmBinder = (android.os.IBinder)
                    smClass.getMethod("getService", String.class).invoke(null, "window");
            if (wmBinder == null) {
                AppLogger.w(TAG, "freezeClusterRotation: window ServiceManager null");
                return;
            }
            Class<?> iwmStub = Class.forName("android.view.IWindowManager$Stub");
            Object iwm = iwmStub.getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, wmBinder);
            // freezeDisplayRotation(int displayId, int rotation)
            // rotation = Surface.ROTATION_0 = 0 → landscape
            java.lang.reflect.Method freeze = iwm.getClass()
                    .getMethod("freezeDisplayRotation", int.class, int.class);
            freeze.invoke(iwm, displayId, 0 /* ROTATION_0 */);
            AppLogger.i(TAG, "freezeDisplayRotation(display=" + displayId + ", ROTATION_0) OK");
        } catch (Exception e) {
            AppLogger.w(TAG, "freezeDisplayRotation not available on this ROM: " + e.getMessage());
        }
    }

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

    /** Cleanly stops the projection (sendInfo(0) + stopService AutoDisplayService). */
    public void stopProjection() {
        AppLogger.log(TAG, "stopProjection requested");
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
