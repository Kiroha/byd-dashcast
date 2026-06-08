package com.byd.dashcast;

import com.byd.dashcast.beta.ShellGateway;
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
import com.byd.dashcast.platform.Platform;

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
 *   - stopProjectionNoAdb() is called explicitly (Restore BYD button or app destruction)
 *
 * Communication with MainActivity:
 *   - LocalBinder.getService() returns the service instance
 *   - MainActivity implements ClusterService.Listener for display callbacks
 */
@SuppressWarnings("deprecation")
public class ClusterService extends Service implements DashboardDisplayHelper.Listener {

    private static final String TAG = "ClusterService";

    // v1.3.7-beta — some BYD ROMs (notably DiLink 3 API 29) ship a stripped
    // IActivityTaskManager that no longer exposes moveTaskToDisplay(int, int).
    // We probe it once per process and remember the outcome so subsequent calls
    // skip the reflection (which would log a noisy NoSuchMethodException stack
    // trace at every cluster-app tap) and go straight to the launcher-based
    // fallback path.
    //
    //   null    → unknown, try reflection
    //   TRUE    → available, use reflection
    //   FALSE   → stripped, skip reflection entirely
    private static volatile Boolean sMoveTaskToDisplayAvailable = null;
    private static final String CHANNEL_ID = "cluster_projection";
    private static final int NOTIF_ID = 1;
    // LOT 4 — volatile: written on main thread (onCreate/onDestroy) and read from
    // worker threads (auto-resize-thread in MainActivity.autoApplyInsetsIfNeeded,
    // BetaProxyClient connect path). Without volatile, the worker could observe a
    // stale `false` after the service started and bail out incorrectly.
    //
    // ARCHITECTURE NOTE: prefer the method-based accessor {@link #isRunning()} over
    // the raw field. Direct field reads bypass future logic changes and make it
    // harder to trace all callers. The field is kept public for backward-compat
    // with existing call sites that pre-date this accessor.
    public static volatile boolean sIsRunning = false;

    /** @return {@code true} while this service is alive between onCreate() and onDestroy(). */
    public static boolean isRunning() { return sIsRunning; }

    /** v1.2.8 — exposed so satellite activities (KeyboardBridgeActivity) can reach the
     *  InputForwarder without binding the service themselves. */
    private static volatile ClusterService sInstance = null;
    public static ClusterService getInstance() { return sInstance; }

    // v1.2.59-beta — one-shot log gate so we don't spam the buffer when the
    // resize SeekBar fires 30+ events per second on a ROM where resize is a
    // ROM-level no-op (DL5). Reset on process restart.
    private static boolean sResizeUnsupportedLogged = false;

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
    /**
     * Set to true in onDestroy().  Background threads ({@code move-task-thread})
     * check this before posting results to {@link #mMainHandler} to avoid
     * use-after-destroy NPEs on {@link #mLauncher} / {@link #mMirrorManager}.
     */
    private volatile boolean       mDestroyed = false;
    // Holds the pending launchOnDashboard callback so onDestroy() can fire it
    // with false when removeCallbacksAndMessages() cancels the postDelayed Runnable.
    private LaunchCallback         mPendingDashboardCallback;
    // Runnable reference for the 2-second postDelayed so a second launchOnDashboard
    // call can cancel the first before it fires (double-tap within the 2s window).
    private Runnable               mPendingLaunchRunnable;
    // Reusable handler on the main thread (replaces ephemeral new Handler() calls).
    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning = true;
        sInstance  = this;
        mDisplayHelper  = new DashboardDisplayHelper(this, this);
        mLauncher       = new DashboardLauncher(this);
        mMirrorManager  = new ClusterMirrorManager();
        mInputForwarder = new ClusterInputForwarder(this);
        
        // Pre-start the MirrorDaemon (app_process via ADB) for Real-Time Cluster Mirror + Touch
        // Executed here instead of in MainActivity to avoid restarting it on every screen rotation.
        AdbLocalClient.startMirrorDaemon(this);
        
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_cluster_initializing)));
        AppLogger.log(TAG, "ClusterService created — starting native projection");
        mProjectionActive = true;
        // v1.2.35 — one-shot cleanup of v1.2.32..v1.2.34 side effect on DiLink 5.
        // Those builds set `force_resizable_activities=1` globally on every
        // DL5 cluster launch and never reset it, which made BYD head-unit
        // apps (e.g. 360° camera) wrongly split-screen capable. We now read
        // it back and force it to 0 on DL5 only, so any user who installed
        // 1.2.32..1.2.34 is healed the next time they open DashCast. DL2/3/4
        // never set this flag and are not touched.
        if (AdbLocalClient.isDiLink5Safe(this)) {
            final String check = "v=$(settings get global force_resizable_activities); "
                    + "if [ \"$v\" = \"1\" ]; then "
                    + "settings put global force_resizable_activities 0 2>&1; "
                    + "echo RESET; else echo OK=$v; fi";
            AdbLocalClient.executeShellWithResult(this, check, new AdbLocalClient.Callback() {
                @Override public void onSuccess(String out) {
                    AppLogger.i(TAG, "DL5 force_resizable_activities cleanup → " + out.trim());
                }
                @Override public void onError(String err) {
                    AppLogger.e(TAG, "DL5 force_resizable_activities cleanup ERROR: " + err);
                }
            });
            // v1.3.5 — Proactively enable ClusterImeWatcherService so the auto-keyboard
            // works from the very first touch, without requiring a manual ⌨ tap first.
            // Field log 20260529: service was not enabled → first keyboard trigger only
            // fired after user tapped ⌨ in fullscreen mirror (which called tryAdbEnableA11y).
            KeyboardBridgeActivity.ensureClusterImeEnabled(this);
        }
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
        if (sInstance == this) sInstance = null;
        mDestroyed = true;
        mListener = null;
        // If launchOnDashboard is waiting on its 2s postDelayed, its Runnable
        // will be cancelled by removeCallbacksAndMessages() below — fire the
        // callback with false now so the caller is never left hanging.
        if (mPendingDashboardCallback != null) {
            LaunchCallback pending = mPendingDashboardCallback;
            mPendingDashboardCallback = null;
            pending.onResult(false);
        }
        // Cancel all pending Runnables on mMainHandler BEFORE release():
        // launchOnDashboard (postDelayed 2s) could post a callback
        // on a destroyed service (NPE / ADB thread leak).
        mMainHandler.removeCallbacksAndMessages(null);
        // v1.2.81 — safety net: ensure the cluster display is back to default
        // DPI even if stopProjectionNoAdb() was bypassed (e.g. process killed).
        try {
            com.byd.dashcast.cluster.ClusterDpiManager.restore(
                    this, mDisplayHelper.getKnownClusterDisplayId());
        } catch (Throwable t) {
            AppLogger.w(TAG, "DPI restore (onDestroy) failed: " + t.getMessage());
        }
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
        return prefs.getInt(SettingsActivity.PREF_INSET_H_PREFIX + packageName, defaultVal);
    }

    public int getInsetV(String packageName) {
        android.content.SharedPreferences prefs =
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        int defaultVal = prefs.getInt(SettingsActivity.PREF_INSET_V, SettingsActivity.DEFAULT_INSET_V);
        if (packageName == null || packageName.isEmpty()) return defaultVal;
        return prefs.getInt(SettingsActivity.PREF_INSET_V_PREFIX + packageName, defaultVal);
    }


    
    public void resizeActiveTask(int taskId, String packageName) {
        if (taskId <= 0) {
            // v1.2.13 — was silent return; the real cause is almost always that
            // ActivityManager.getRunningTasks() returned only the caller's own task
            // (API 21+ restriction). The dumpsys-based fallback in findRunningTaskId
            // should fix it; log here so a residual failure is visible in field logs.
            AppLogger.w(TAG, "resizeActiveTask: taskId<=0 for pkg=" + packageName
                    + " — cannot resize (lookup via AM + daemon dumpsys both failed)");
            return;
        }
        // v1.2.59-beta — DL5 ROM-level guard.
        // The DL5 fission test report (byd_report_20260528_081206.log F10/F11/F12)
        // proved that on BYD DiLink 5.0 / Android 12 build SKQ1.230128.001 the
        // `cmd activity set-task-windowing-mode` verb is stripped from the ROM
        // and `cmd activity task resize` returns exit=0 with zero visible effect.
        // Running the cascade anyway only spams the log; abort here once we
        // have the platform's confirmation. The probe (Platform.primeClusterResize
        // Probe) runs at app start, so by the time the user touches the SeekBar
        // the answer is cached. On DL2/DL3/DL4 isClusterTaskResizeSupported()
        // always returns true → no behavioural change.
        if (!Platform.get().isClusterTaskResizeSupported(this)) {
            if (!sResizeUnsupportedLogged) {
                sResizeUnsupportedLogged = true;
                AppLogger.w(TAG, "resizeActiveTask skipped: cluster task resize is not "
                        + "supported on this ROM (cmd activity set-task-windowing-mode "
                        + "stripped). See doc_api/DL5_CLUSTER_RESIZE_LIMITATION.md.");
            }
            return;
        }
        // HARD GUARD — never resize anything if no cluster display is connected.
        // resizeTask() applies bounds in the task's current display coordinates; if
        // the task happens to be on display 0 (head unit) because the cluster move
        // failed earlier, we would shrink the main UI. Abort instead.
        int clusterId = mDisplayHelper.getKnownClusterDisplayId();
        if (clusterId <= 0) {
            AppLogger.w(TAG, "resizeActiveTask aborted: no cluster display connected (taskId="
                    + taskId + " pkg=" + packageName + ")");
            return;
        }
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
            // v1.2.30 — DL5 framebuffer-space fix.
            // The task lives on display 3 (XDJA fission VirtualDisplay), whose
            // framebuffer reports 1920×1080 via getRealSize() — NOT the 1920×720
            // of the physical cluster face (display 2). Qt scales 1080→720
            // before compositing onto the dalle. resizeTask() applies bounds
            // in the task's own display coordinate space, so bounds must be
            // expressed in display 3 dimensions, otherwise we shrink the task
            // to a tiny rectangle inside a 1920×1080 buffer (visible in field
            // log BYD_RE_Sniffer_20260523_184727.txt as resize bounds capped
            // at 445 against a 1080-tall framebuffer).
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
                AppLogger.w(TAG, "resizeActiveTask: getRealSize(" + clusterId
                        + ") failed → falling back to InputForwarder dims: " + t.getMessage());
                if (mInputForwarder != null) {
                    int fw = mInputForwarder.getClusterWidth();
                    int fh = mInputForwarder.getClusterHeight();
                    if (fw > 0) cw = fw;
                    if (fh > 0) ch = fh;
                }
            }
            android.graphics.Rect bounds = new android.graphics.Rect(
                    insetH, insetV, cw - insetH, ch - insetV);
            
            // v1.2.16 — try the 3-arg signature first (Android 11/12),
            // then the 2-arg signature (older / vendor variants). If both
            // throw, surface the *real* cause: InvocationTargetException
            // hides it behind getCause(), and getMessage() on the wrapper
            // is null (exact symptom seen in v1.2.15 field log:
            // "resizeActiveTask failed: null").
            Throwable lastError = null;
            boolean done = false;
            try {
                iAtmClass.getMethod("resizeTask", int.class, android.graphics.Rect.class, int.class)
                        .invoke(iatm, taskId, bounds, 1 /* RESIZE_MODE_FORCED */);
                done = true;
            } catch (java.lang.reflect.InvocationTargetException ite) {
                lastError = ite.getTargetException() != null ? ite.getTargetException() : ite;
            } catch (NoSuchMethodException nsme) {
                lastError = nsme;
            } catch (Throwable t) {
                lastError = t;
            }
            if (!done) {
                try {
                    iAtmClass.getMethod("resizeTask", int.class, android.graphics.Rect.class)
                            .invoke(iatm, taskId, bounds);
                    done = true;
                    lastError = null;
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    lastError = ite.getTargetException() != null ? ite.getTargetException() : ite;
                } catch (NoSuchMethodException nsme) {
                    /* keep first error */
                } catch (Throwable t) {
                    if (lastError == null) lastError = t;
                }
            }
            if (done) {
                AppLogger.i(TAG, "resizeActiveTask " + packageName + " " + bounds
                        + " (cw=" + cw + " ch=" + ch + " clusterDisplay=" + clusterId + ") OK");
            } else {
                String detail = (lastError == null) ? "unknown"
                        : lastError.getClass().getSimpleName()
                                + ": " + lastError.getMessage();
                AppLogger.w(TAG, "resizeActiveTask reflection failed: " + detail
                        + " — falling back to shell `am task resize` (taskId=" + taskId
                        + " pkg=" + packageName + " bounds=" + bounds + ")");
                // v1.2.17 — Field log proved the reflection path hits
                // SecurityException because resizeTask() requires
                // android.permission.MANAGE_ACTIVITY_TASKS, which is
                // signature|privileged and unreachable for normal apps.
                // The shell-side `am task resize <id> <l> <t> <r> <b>` runs
                // in shell uid context via AdbLocalClient (same pipe that
                // already executes `wm overscan` successfully) and bypasses
                // the app-level perm check.
                //
                // v1.2.18 — Field log BYD_RE_Sniffer_20260523_161033.txt showed
                // `am task resize` returning empty stdout (apparent success) but
                // no visible effect on the cluster — on AOSP API 30+ the `am`
                // verb was rewritten and `am task resize` is often a silent
                // no-op. Capture exit code + stderr and, if the first attempt
                // doesn't look successful, chain a `cmd activity task resize`
                // attempt (modern equivalent).
                //
                // v1.2.26 — Field log BYD_RE_Sniffer_20260523_172007.txt (DL5)
                // confirms `am task resize` returns "exit=0" deterministically
                // on DL5 (API 32) with zero visible effect on the cluster face
                // (XDJA fission Presentation VirtualDisplay). The exit=0
                // ⇒ looksOk=true shortcut therefore swallows every resize on
                // DL5 and the `cmd activity task resize` fallback is never
                // attempted. Fix: on DL5, skip `am task` altogether and shell
                // straight to `cmd activity task resize` (the AOSP API 30+
                // verb). On DL3 (API 29) keep the legacy chain.
                final int rTaskId = taskId;
                final String rPkg = packageName;
                final android.graphics.Rect rBounds = bounds;
                final String coords = rTaskId + " " + rBounds.left + " " + rBounds.top
                        + " " + rBounds.right + " " + rBounds.bottom;
                final String amCmd  = "am task resize " + coords + " 2>&1; echo \"exit=$?\"";
                final String cmdAct = "cmd activity task resize " + coords + " 2>&1; echo \"exit=$?\"";
                if (AdbLocalClient.isDiLink5Safe(this)) {
                    AppLogger.i(TAG, "resizeActiveTask DL5: dispatching `cmd activity task resize` "
                            + "(skipping `am task resize` — known silent no-op on API 30+) taskId="
                            + rTaskId + " pkg=" + rPkg + " bounds=" + rBounds
                            + " (cluster framebuffer " + cw + "x" + ch + ")");
                    AdbLocalClient.executeShellWithResult(this, cmdAct, new AdbLocalClient.Callback() {
                        @Override public void onSuccess(String out) {
                            AppLogger.i(TAG, "resizeActiveTask `cmd activity task resize` -> \""
                                    + (out == null ? "" : out.trim()) + "\"");
                            // v1.2.30 — verification probe.
                            // Field log BYD_RE_Sniffer_20260523_184727.txt showed
                            // `cmd activity task resize` returning exit=0 with zero
                            // visible effect (cause: task launched in FULLSCREEN
                            // mode by `am start --display N`, no FREEFORM flag).
                            // Now that startActivityViaShell() launches with
                            // --windowingMode 5, dumpsys the activity to confirm
                            // the windowing mode AND that the post-resize bounds
                            // were actually accepted by ATM.
                            //
                            // v1.2.32 — verify probe refined.
                            // The previous probe spammed mGlobalConfig dumps (one
                            // per Configuration carrier in the activities tree)
                            // and head -20 truncated before reaching the actual
                            // task #N stanza. Use awk to extract precisely the
                            // task's own stanza (between "TaskRecord{...#N}" and
                            // the next TaskRecord or the end of the section)
                            // and trim to the only two fields that matter:
                            // mWindowingMode= and mBounds= inside that stanza.
                            final String verify =
                                    "dumpsys activity activities 2>/dev/null"
                                  + " | awk '/Task=Task\\{[^}]*#" + rTaskId + "[ }]/,"
                                  +       "/Task=Task\\{[^}]*#[0-9]+[ }]/'"
                                  + " | grep -E 'mBounds|WindowingMode|displayId|resizeMode|#" + rTaskId + " '"
                                  + " | head -25";
                            AdbLocalClient.executeShellWithResult(ClusterService.this, verify,
                                    new AdbLocalClient.Callback() {
                                        @Override public void onSuccess(String dump) {
                                            String d = (dump == null) ? "" : dump.trim();
                                            AppLogger.i(TAG, "resizeActiveTask VERIFY taskId="
                                                    + rTaskId + " pkg=" + rPkg + " →\n" + d);
                                        }
                                        @Override public void onError(String e) {
                                            AppLogger.w(TAG, "resizeActiveTask VERIFY error: " + e);
                                        }
                                    });
                        }
                        @Override public void onError(String err) {
                            AppLogger.w(TAG, "resizeActiveTask `cmd activity task resize` AdbLocal error: "
                                    + err + " (taskId=" + rTaskId + " pkg=" + rPkg + ")");
                        }
                    });
                } else {
                    AdbLocalClient.executeShellWithResult(this, amCmd, new AdbLocalClient.Callback() {
                        @Override public void onSuccess(String out) {
                            String trimmed = (out == null ? "" : out.trim());
                            boolean looksOk = trimmed.contains("exit=0")
                                    && !trimmed.toLowerCase().contains("unknown command")
                                    && !trimmed.toLowerCase().contains("error")
                                    && !trimmed.toLowerCase().contains("exception");
                            AppLogger.i(TAG, "resizeActiveTask `am task resize` -> \""
                                    + trimmed + "\" (looksOk=" + looksOk + ")");
                            if (looksOk) return;
                            AppLogger.i(TAG, "resizeActiveTask: trying `cmd activity task resize` fallback");
                            AdbLocalClient.executeShellWithResult(ClusterService.this, cmdAct,
                                    new AdbLocalClient.Callback() {
                                        @Override public void onSuccess(String out2) {
                                            AppLogger.i(TAG, "resizeActiveTask `cmd activity task resize` -> \""
                                                    + (out2 == null ? "" : out2.trim()) + "\"");
                                        }
                                        @Override public void onError(String err2) {
                                            AppLogger.w(TAG, "resizeActiveTask `cmd activity task resize` AdbLocal error: " + err2);
                                        }
                                    });
                        }
                        @Override public void onError(String err) {
                            AppLogger.w(TAG, "resizeActiveTask `am task resize` AdbLocal error: " + err
                                    + " (taskId=" + rTaskId + " pkg=" + rPkg + ")");
                        }
                    });
                }
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "resizeActiveTask outer failure: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
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
        // Path 1 — ActivityManager.getRunningTasks: on API 21+ returns ONLY the
        // caller's own task for non-system apps. Kept for the rare device where
        // DashCast has GET_TASKS (legacy BYD ROMs) and as a fast-path probe.
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    am.getRunningTasks(50);
            if (tasks != null) {
                for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
                    if (t.topActivity != null
                            && packageName.equals(t.topActivity.getPackageName())) {
                        AppLogger.d(TAG, "findRunningTaskId " + packageName
                                + " → taskId=" + t.id + " (via AM)");
                        return t.id;
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "findRunningTaskId AM path: " + e.getMessage());
        }

        // Path 2 (v1.2.13) — fallback via proxy daemon (uid 2000, shell privileges).
        // dumpsys activity recents lists all TaskRecords with their numeric id and
        // their affinity package, regardless of caller package. Required on DL5
        // (and any modern Android) because path 1 is API-21-restricted.
        try {
            if (com.byd.dashcast.beta.BetaProxyClient.isConnected()) {
                String out = com.byd.dashcast.beta.BetaProxyClient
                        .runShell("dumpsys activity recents");
                if (out != null && !out.isEmpty()) {
                    int id = parseTaskIdFromDumpsysRecents(out, packageName);
                    if (id > 0) {
                        AppLogger.d(TAG, "findRunningTaskId " + packageName
                                + " → taskId=" + id + " (via daemon dumpsys recents)");
                        return id;
                    }
                    AppLogger.d(TAG, "findRunningTaskId " + packageName
                            + " — not found in dumpsys recents (out.length=" + out.length() + ")");
                }
                // Path 2b (v1.3.4) — launcher-agnostic fallback via
                // `dumpsys activity activities`. Third-party launchers (e.g.
                // com.dudu.autoui observed in field log byd_log_20260529_214954)
                // replace the BYD launcher and manage the recent task stack
                // differently, so live tasks never show up in `dumpsys activity
                // recents`. `activities` lists every ActivityRecord across all
                // stacks regardless of launcher behaviour.
                String outAct = com.byd.dashcast.beta.BetaProxyClient
                        .runShell("dumpsys activity activities");
                if (outAct != null && !outAct.isEmpty()) {
                    int id = parseTaskIdFromDumpsysActivities(outAct, packageName);
                    if (id > 0) {
                        AppLogger.d(TAG, "findRunningTaskId " + packageName
                                + " → taskId=" + id + " (via daemon dumpsys activities)");
                        return id;
                    }
                    AppLogger.d(TAG, "findRunningTaskId " + packageName
                            + " — not found in dumpsys activities (out.length=" + outAct.length() + ")");
                }
            } else {
                AppLogger.w(TAG, "findRunningTaskId " + packageName
                        + " — daemon not connected; cannot fallback to dumpsys");
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "findRunningTaskId daemon dumpsys fallback: " + t.getMessage());
        }

        // Path 3 (v1.2.15) — fallback via AdbLocalClient shell. Required when the
        // proxy daemon is not running (DL5 testeur in field log
        // BYD_RE_Sniffer_20260523_150803.txt — daemon was off, every Apply tap
        // produced "daemon not connected" then taskId<=0). AdbLocalClient
        // already provides shell access through the local adb-over-TCP path
        // that is also used by MainActivity.btnResizeApply for the (dead) wm
        // overscan command, so we know it is functional on this ROM.
        //
        // executeShellWithResult is async by API — block with a CountDownLatch.
        // This method is documented "Must be called from a background thread"
        // so blocking is fine.
        try {
            final java.util.concurrent.atomic.AtomicReference<String> outRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
            final java.util.concurrent.atomic.AtomicReference<String> errRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            AdbLocalClient.executeShellWithResult(this, "dumpsys activity recents",
                    new AdbLocalClient.Callback() {
                        @Override public void onSuccess(String report) { outRef.set(report); latch.countDown(); }
                        @Override public void onError(String error)   { errRef.set(error);  latch.countDown(); }
                    });
            // Generous timeout — dumpsys activity recents normally returns in <1 s,
            // but the AdbLocal connect path may need to establish a TCP connection.
            boolean done = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                AppLogger.w(TAG, "findRunningTaskId " + packageName
                        + " — AdbLocal dumpsys timeout (5s)");
                return -1;
            }
            String err = errRef.get();
            if (err != null) {
                AppLogger.w(TAG, "findRunningTaskId " + packageName
                        + " — AdbLocal dumpsys error: " + err);
                return -1;
            }
            String out = outRef.get();
            if (out != null && !out.isEmpty()) {
                int id = parseTaskIdFromDumpsysRecents(out, packageName);
                if (id > 0) {
                    AppLogger.d(TAG, "findRunningTaskId " + packageName
                            + " → taskId=" + id + " (via AdbLocal dumpsys recents)");
                    return id;
                }
                AppLogger.d(TAG, "findRunningTaskId " + packageName
                        + " — not found in AdbLocal dumpsys (out.length=" + out.length() + ")");
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "findRunningTaskId AdbLocal dumpsys fallback: " + t.getMessage());
        }

        // Path 3b (v1.3.4) — launcher-agnostic fallback via AdbLocal
        // + `dumpsys activity activities`. Same rationale as Path 2b for
        // devices where the daemon is not running.
        try {
            final java.util.concurrent.atomic.AtomicReference<String> outRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
            final java.util.concurrent.atomic.AtomicReference<String> errRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            AdbLocalClient.executeShellWithResult(this, "dumpsys activity activities",
                    new AdbLocalClient.Callback() {
                        @Override public void onSuccess(String report) { outRef.set(report); latch.countDown(); }
                        @Override public void onError(String error)   { errRef.set(error);  latch.countDown(); }
                    });
            boolean done = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                AppLogger.w(TAG, "findRunningTaskId " + packageName
                        + " — AdbLocal dumpsys activities timeout (5s)");
                return -1;
            }
            String err = errRef.get();
            if (err != null) {
                AppLogger.w(TAG, "findRunningTaskId " + packageName
                        + " — AdbLocal dumpsys activities error: " + err);
                return -1;
            }
            String out = outRef.get();
            if (out != null && !out.isEmpty()) {
                int id = parseTaskIdFromDumpsysActivities(out, packageName);
                if (id > 0) {
                    AppLogger.d(TAG, "findRunningTaskId " + packageName
                            + " → taskId=" + id + " (via AdbLocal dumpsys activities)");
                    return id;
                }
                AppLogger.d(TAG, "findRunningTaskId " + packageName
                        + " — not found in AdbLocal dumpsys activities (out.length=" + out.length() + ")");
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "findRunningTaskId AdbLocal dumpsys activities fallback: " + t.getMessage());
        }
        return -1;
    }

    /**
     * v1.2.13 — Parse a numeric taskId out of a {@code dumpsys activity recents} dump
     * for the given package. Each Task appears as
     * <pre>* Recent #N: Task{xxxxxxx #88 type=standard A=ru.yandex.yandexmaps U=0 ...</pre>
     * The affinity {@code A=...} is almost always equal to the package name; if a Task
     * has a different affinity, we fall back to matching {@code realActivity=&lt;pkg&gt;/...}
     * or {@code cmp=&lt;pkg&gt;/...} on a line within the same Task block.
     *
     * Returns the first match (most-recent task), or -1 if none.
     */
    static int parseTaskIdFromDumpsysRecents(String dump, String packageName) {
        if (dump == null || packageName == null) return -1;
        // Fast path — "Task{... #ID ... A=<pkg>" on the same line.
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "Task\\{[^}]*#(\\d+)[^}]*\\bA=" + java.util.regex.Pattern.quote(packageName) + "\\b");
            java.util.regex.Matcher m = p.matcher(dump);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}

        // Fallback — scan block by block, accept realActivity= or cmp= match.
        try {
            // Split on the "* Recent #" boundary so each block contains exactly one Task header.
            String[] blocks = dump.split("(?m)^\\s*\\* Recent #\\d+:\\s*");
            java.util.regex.Pattern idP =
                    java.util.regex.Pattern.compile("Task\\{[^}]*#(\\d+)");
            String marker1 = "realActivity=" + packageName + "/";
            String marker2 = "cmp=" + packageName + "/";
            String marker3 = " A=" + packageName + " ";
            for (String block : blocks) {
                if (block == null || block.isEmpty()) continue;
                if (block.contains(marker1) || block.contains(marker2) || block.contains(marker3)) {
                    java.util.regex.Matcher mm = idP.matcher(block);
                    if (mm.find()) {
                        try { return Integer.parseInt(mm.group(1)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * v1.3.4 — Parse a numeric taskId out of a {@code dumpsys activity activities}
     * dump for the given package. Used as a launcher-agnostic fallback when the
     * stock recent-task stack is not populated (third-party launchers such as
     * {@code com.dudu.autoui} bypass {@code mRecentTasks}).
     *
     * AOSP {@code ActivityRecord.toString()} emits a stable form across API 28–33:
     * <pre>ActivityRecord{hash u0 com.waze/.MainActivity t88}</pre>
     * The {@code t<N>} suffix is the owning task id. This is present whether the
     * record appears under a {@code Stack}/{@code Task} block, a {@code Hist}/{@code Run}
     * list, or the resumed/focused-activity lines, so a single regex covers all
     * dumpsys variants we have observed on DiLink 2 / 3 / 4 / 5.
     *
     * Returns the first match (top-most resumed activity for the package),
     * or -1 if none.
     */
    static int parseTaskIdFromDumpsysActivities(String dump, String packageName) {
        if (dump == null || packageName == null) return -1;
        try {
            // ActivityRecord{<hash> u<uid> <pkg>/<class> t<taskId>}
            // <class> may start with a leading dot or be a fully-qualified name;
            // it never contains a space or closing brace.
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "ActivityRecord\\{[^}]*\\s"
                            + java.util.regex.Pattern.quote(packageName)
                            + "/[^\\s}]+\\st(\\d+)\\}");
            java.util.regex.Matcher m = p.matcher(dump);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
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
    // TeleNav nav app is auto-started by BYD at boot; its process is often a zombie
    // that moveTaskToDisplay() moves as-is → black screen on cluster.  Force a fresh
    // launch instead (launchOnDashboard uses FLAG_ACTIVITY_CLEAR_TASK).
    private static final String PKG_FORCE_FRESH_LAUNCH = "com.telenav.app.arp";

    public void moveTaskToDisplay(final String packageName, final int targetDisplayId,
                                   final LaunchCallback callback) {
        moveTaskToDisplayInternal(packageName, targetDisplayId, callback, false);
    }

    /**
     * v1.2.55-beta — enforce display placement WITHOUT falling back to a fresh
     * launch. Used to repair the case where a normal launch (with
     * ActivityOptions.launchDisplayId) spawned the app's process but the system
     * placed the task on display 0 instead of the target cluster display —
     * observed on Waze first-tap from a cold app process (field report after
     * v1.2.54). Shares the move + setTaskWindowingMode(FREEFORM) + resizeTask
     * post-move sequence with {@link #moveTaskToDisplay} so apps with per-app
     * insets (auto-applied by MainActivity.autoApplyInsetsIfNeeded at T+500 ms)
     * keep their cluster bounds when the move happens at T+2500 ms. Silent
     * no-op when the task is not yet registered (the user can re-tap) and
     * skipped for {@code PKG_FORCE_FRESH_LAUNCH} packages since their normal
     * launch path is "fresh launch only" by design.
     */
    public void enforceTaskOnDisplay(final String packageName, final int targetDisplayId) {
        moveTaskToDisplayInternal(packageName, targetDisplayId, null, true);
    }

    private void moveTaskToDisplayInternal(final String packageName, final int targetDisplayId,
                                            final LaunchCallback callback,
                                            final boolean enforceOnly) {
        // ── Ghost-nav workaround: always fresh-launch TeleNav instead of moving ──
        if (PKG_FORCE_FRESH_LAUNCH.equals(packageName) && targetDisplayId > 0) {
            if (enforceOnly) {
                AppLogger.d(TAG, "enforceTaskOnDisplay: skip force-fresh-launch package "
                        + packageName);
                return;
            }
            AppLogger.i(TAG, "moveTaskToDisplay: force fresh launch for " + packageName
                    + " (ghost-nav workaround)");
            fallbackLaunch(packageName, targetDisplayId, callback);
            return;
        }

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int taskId = findRunningTaskId(packageName);
                    if (taskId == -1) {
                        if (enforceOnly) {
                            AppLogger.d(TAG, "enforceTaskOnDisplay: no task yet for "
                                    + packageName + " (skip, no fallback launch)");
                            return;
                        }
                        AppLogger.w(TAG, "moveTaskToDisplay: no running task for "
                                + packageName + " → fallback launch");
                        fallbackLaunch(packageName, targetDisplayId, callback);
                        return;
                    }

                    // IActivityTaskManager.moveTaskToDisplay(taskId, displayId)
                    // v1.3.7-beta — short-circuit when a previous call has
                    // already proven this ROM strips the method, to avoid
                    // logging NoSuchMethodException on every cluster-app tap.
                    if (Boolean.FALSE.equals(sMoveTaskToDisplayAvailable)) {
                        AppLogger.d(TAG, (enforceOnly ? "enforceTaskOnDisplay" : "moveTaskToDisplay")
                                + ": method unavailable on this ROM → "
                                + (enforceOnly ? "skip" : "fallback launch"));
                        if (mDestroyed) return;
                        if (enforceOnly) return;
                        fallbackLaunch(packageName, targetDisplayId, callback);
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
                        // let WM settle after the display move; isolated catch so
                        // InterruptedException does not reach the outer catch(Exception)
                        // which would wrongly trigger fallbackLaunch on a successful move.
                        try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

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
                            // DL5 fix: dynamic cluster size (was hardcoded 1920×720).
                            int cw = (mInputForwarder != null) ? mInputForwarder.getClusterWidth()  : 1920;
                            int ch = (mInputForwarder != null) ? mInputForwarder.getClusterHeight() :  720;
                            if (cw <= 0) cw = 1920;
                            if (ch <= 0) ch = 720;
                            android.graphics.Rect bounds = new android.graphics.Rect(
                                    insetH, insetV, cw - insetH, ch - insetV);
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
                            if (mDestroyed) return;
                            if (callback != null) callback.onResult(true);
                        }
                    });

                } catch (Exception e) {
                    // v1.3.7-beta — a NoSuchMethodException on moveTaskToDisplay
                    // means the BYD ROM stripped the hidden API (observed on
                    // DiLink 3 API 29). Remember the outcome and downgrade
                    // subsequent failures to a single WARN at first sight; the
                    // launcher-based fallback handles the user-visible path.
                    boolean stripped = (e instanceof NoSuchMethodException)
                            || (e.getCause() instanceof NoSuchMethodException);
                    if (stripped && sMoveTaskToDisplayAvailable == null) {
                        sMoveTaskToDisplayAvailable = Boolean.FALSE;
                        AppLogger.w(TAG, (enforceOnly ? "enforceTaskOnDisplay" : "moveTaskToDisplay")
                                + ": IActivityTaskManager.moveTaskToDisplay(int,int) stripped on this ROM — will use launcher fallback from now on");
                    } else if (!stripped) {
                        AppLogger.e(TAG, (enforceOnly ? "enforceTaskOnDisplay" : "moveTaskToDisplay")
                                + " error", e);
                    }
                    if (mDestroyed) return;
                    if (enforceOnly) return; // never re-launch in enforce mode
                    fallbackLaunch(packageName, targetDisplayId, callback);
                }
            }
        }, enforceOnly ? "enforce-task-display" : "move-task-thread").start();
    }

    private void fallbackLaunch(final String packageName, final int targetDisplayId,
                                 final LaunchCallback callback) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                if (mDestroyed) return;
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
        // Cancel any pending launch from a previous call within the 2-s window so
        // the superseded callback is never silently dropped (double-tap scenario).
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
                // Clear tracking fields first so onDestroy() does not double-fire.
                mPendingLaunchRunnable = null;
                mPendingDashboardCallback = null;
                // Direct launch via IActivityManager on the Freedom display (proven v2.29).
                final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                AppLogger.i(TAG, "Launching via IActivityManager on display=" + displayId + " → " + packageName);

                // Recovery : a previous diag session may have left an orphan
                // split-screen-primary stack on this display (v1.2.61 fallback
                // path used setCustomTaskWindowingModeSplitScreenPrimary which
                // poisons the stack mode). AOSP then routes our new task into
                // that stack and throws "Can only have one child on stack
                // mode=split-screen-primary". Pre-cleanup is cheap and a no-op
                // on a healthy display.
                if (displayId > 0) {
                    try {
                        String cleanLog = com.byd.dashcast.beta.BetaProxyClient
                                .cleanFissionStacks(displayId);
                        AppLogger.d(TAG, "cleanFissionStacks(" + displayId + ")\n" + cleanLog);
                    } catch (Throwable ce) {
                        AppLogger.w(TAG, "cleanFissionStacks pre-launch failed: " + ce.getMessage());
                    }
                }

                // v1.2.81 — apply per-app DPI override BEFORE am start so the
                // app reads the new density at process boot. Guarded by
                // displayId > 0 inside the manager (NEVER touches display 0).
                com.byd.dashcast.cluster.ClusterDpiManager.applyForLaunch(
                        ClusterService.this, packageName, displayId);

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

                    // DiLink 5.0: our app (uid 10148) is denied launchDisplayId by IATM
                    // (SecurityException seen in field log 22/05/2026 build 187).
                    // Route through ADB shell (uid 2000) which D31 validated end-to-end.
                    if (AdbLocalClient.isDiLink5Safe(ClusterService.this)) {
                        startActivityViaShell(packageName, displayId, launchIntent);
                    } else {
                        startActivityViaIAM(launchIntent, opts);
                    }

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
        };
        mMainHandler.postDelayed(mPendingLaunchRunnable, 2000);
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
                // Recovery cleanup — see launchOnDashboard() for rationale.
                if (displayId > 0) {
                    try {
                        com.byd.dashcast.beta.BetaProxyClient.cleanFissionStacks(displayId);
                    } catch (Throwable ce) {
                        AppLogger.w(TAG, "cleanFissionStacks(WithBounds) failed: " + ce.getMessage());
                    }
                }
                // v1.2.81 — same per-app DPI apply on the split-bounds path.
                com.byd.dashcast.cluster.ClusterDpiManager.applyForLaunch(
                        ClusterService.this, packageName, displayId);
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
                    if (AdbLocalClient.isDiLink5Safe(ClusterService.this)) {
                        // DL5: IATM denies our uid for cross-display launches — use shell.
                        startActivityViaShell(packageName, displayId, launchIntent);
                    } else {
                        startActivityViaIAM(launchIntent, opts);
                    }
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
            // wm overscan was removed from Android 11+ (API 30+). DL5 is API 32
            // → "Unknown command: overscan". Skip the call entirely on DL5.
            if (AdbLocalClient.isDiLink5Safe(this)) {
                AppLogger.d(TAG, "DL5: skipping app-specific wm overscan (cmd removed in API 30+)");
            } else {
                ShellGateway.execShell(this, "wm overscan " + insetH + "," + insetV + "," + insetH + "," + insetV + " -d " + displayId);
                AppLogger.i(TAG, "Applied app-specific wm overscan during launch on display " + displayId);
            }
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

    /**
     * DL5 launch path — bypasses IATM by shelling out to {@code am start --display N}.
     *
     * Background: on DiLink 5.0 (BYD-AUTO API 32), our process (uid 10148) is denied
     * cross-display activity launches by IActivityTaskManager: a typed
     * {@code startActivityAsUser} with {@code ActivityOptions.setLaunchDisplayId(3)}
     * is rejected with {@code SecurityException: Permission Denial ... with launchDisplayId=3}
     * (field log 22/05/2026 build 187, lines 38.767 / 46.196). The shell uid 2000
     * has the privileges to land the activity on the cluster display — this is exactly
     * what the D31 diagnostic probe validated end-to-end before build 188.
     *
     * The command pattern mirrors D31:
     *   {@code am start --display N -a MAIN -c LAUNCHER -n <pkg>/<launcher> 2>&1}
     *
     * Fire-and-forget through {@link AdbLocalClient#executeShellWithResult}: the
     * existing {@code launchOnDashboard} callback already optimistically reports
     * success after dispatch, so the shell error path is logged but does not
     * propagate (consistent with the legacy IATM path which also swallows
     * RemoteException after best-effort).
     */
    private void startActivityViaShell(String packageName, int displayId,
                                       android.content.Intent launchIntent) {
        String component = null;
        if (launchIntent != null && launchIntent.getComponent() != null) {
            android.content.ComponentName cn = launchIntent.getComponent();
            component = cn.getPackageName() + "/" + cn.getClassName();
        }
        if (component == null) {
            AppLogger.e(TAG, "startActivityViaShell: cannot resolve component for " + packageName);
            return;
        }
        // v1.2.30 — DL5 resize root cause fix.
        // Field log BYD_RE_Sniffer_20260523_184727.txt proved that without
        // an explicit windowing mode the task lands as WINDOWING_MODE_FULLSCREEN
        // on display 3, and `cmd activity task resize` is a silent no-op on
        // fullscreen tasks since API 30+ (returns exit=0, never changes bounds).
        // Adding `--windowingMode 5` (FREEFORM) is the documented `am start`
        // flag for selecting a windowing mode at launch time (AOSP ≥API 26,
        // still present in API 32) and is the prerequisite for the subsequent
        // resizeActiveTask() call to actually apply our inset bounds on the
        // XDJA fission VirtualDisplay backing the cluster.
        //
        // v1.2.32 — DL5 resize second-order fix (REVERTED in v1.2.35).
        // We used to also `settings put global force_resizable_activities 1`
        // here to make non-resizable navigation apps (Yandex Maps, Google
        // Maps, …) honor freeform bounds on the cluster display. That global
        // is system-wide and persistent (Settings.Global) and had the very
        // unwanted side effect of making every BYD head-unit app split-screen
        // capable too (e.g. the 360° camera became resizable). We no longer
        // touch that setting at launch time, and ClusterService.onCreate()
        // proactively resets it back to 0 on DiLink 5 to clean up users who
        // upgraded from v1.2.32 – v1.2.34. The freeform launch + per-task
        // resize still works for apps whose manifest already declares
        // resizeableActivity=true (or doesn't declare it on API 24+).
        final String cmd = "am force-stop " + packageName + " 2>&1; "
                + "am start --display " + displayId
                + " --windowingMode 5"
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -n " + component
                + " --activity-clear-task 2>&1";
        AppLogger.i(TAG, "DL5 launch via shell: " + cmd);
        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                AppLogger.i(TAG, "DL5 am start → " + out.trim());
            }
            @Override public void onError(String err) {
                AppLogger.e(TAG, "DL5 am start ERROR: " + err);
            }
        });
    }

    public void restartProjection() {
        AppLogger.log(TAG, "restartProjection requested natively");
        if (mDisplayHelper != null) {
            mDisplayHelper.start();
        }
    }

    /**
     * Syncs the service state WITHOUT resending the ADB restore commands.
     * To be used when ADB restore has already been done upstream (e.g. restoreBydDashboard).
     * Avoids double sending sendInfo(18+0).
     */
    public void stopProjectionNoAdb() {
        AppLogger.log(TAG, "stopProjectionNoAdb requested (ADB already sent)");
        if (!AdbLocalClient.isDiLink5Safe(this)) {
            ShellGateway.execShell(this, "wm overscan reset -d 1");
        }
        // v1.2.81 — restore default DPI on the cluster display before tearing
        // down. Guarded by displayId > 0 inside the manager.
        try {
            com.byd.dashcast.cluster.ClusterDpiManager.restore(
                    this, mDisplayHelper.getKnownClusterDisplayId());
        } catch (Throwable t) {
            AppLogger.w(TAG, "DPI restore (stopProjectionNoAdb) failed: " + t.getMessage());
        }
        mProjectionActive = false;
        mDisplayHelper.stopWithoutAdb();
        mLauncher.setDashboardDisplayId(-1);
        // v1.2.77 — drop the FG notification entirely instead of pushing an
        // ongoing "Cluster : arrêté" that the user cannot swipe away. The
        // service is about to stopSelf() so there is no reason to keep any
        // notification at all.
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            AppLogger.w(TAG, "stopForeground failed: " + t.getMessage());
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
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
        updateNotification(getString(R.string.notif_cluster_active, displayId));

        // Apply display-level insets via wm overscan so all apps launched on this display
        // stay within the safe area [INSET_H, INSET_V, 1920-INSET_H, 720-INSET_V].
        // This is the only approach that works on FLAG_PRESENTATION VirtualDisplays (Freedom)
        // because apps there are not tracked by the standard WM task system.
        // SAFETY GUARD: never apply overscan to the main display (id 0 or negative).
        if (displayId > 0) {
            if (AdbLocalClient.isDiLink5Safe(this)) {
                // wm overscan removed in API 30+ — DL5 is API 32. No-op on DL5.
                AppLogger.d(TAG, "DL5: skipping display-level wm overscan (cmd removed in API 30+)");
            } else {
                final int insetH = getInsetH(null);
                final int insetV = getInsetV(null);
                ShellGateway.execShell(this,
                        "wm overscan " + insetH + "," + insetV
                        + "," + insetH + "," + insetV
                        + " -d " + displayId);
                AppLogger.i(TAG, "wm overscan applied on display " + displayId
                        + " inset=" + insetH + "," + insetV);
            }
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
        updateNotification(getString(R.string.notif_cluster_disconnected));
        if (mListener != null) {
            mListener.onClusterDisplayDisconnected();
        }
    }

    // ── Notification (required for Foreground Service) ───────────────────────

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
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
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
