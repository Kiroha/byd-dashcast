package com.byd.dashcast.fission;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.SurfaceHolder;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.domain.cluster.ProjectionStateProvider;
import com.byd.dashcast.R;
import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.proxy.ShellGateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Encapsulates all background fission logic: daemon acquisition, slot lifecycle
 * (attach / reuse / resize / release), mirror start/stop, and layout switching.
 *
 * <h3>Architecture improvements over original FissionActivity</h3>
 * <ul>
 *   <li><b>No static coupling to ClusterService</b>: normal projection state is queried
 *       via {@link ProjectionStateProvider}, which is trivially stub-able in tests.
 *   <li><b>No static binder field on FissionLayoutEditorActivity</b>: callers receive the
 *       binder through {@link Callbacks#onDaemonBinderAcquired(IBinder)} and pass it via
 *       Intent extras (using existing {@code BinderParcelable}) when starting the editor.
 *   <li><b>Single executor thread</b>: all background work is serialised on one daemon
 *       thread (same as original), preventing concurrent VD operations that confuse the
 *       XDJA fission daemon.
 * </ul>
 *
 * <h3>Threading contract</h3>
 * All public methods are main-thread safe and dispatch heavy work to the internal executor.
 * {@link Callbacks} are always invoked on the main thread.
 */
public final class FissionOrchestrator {

    private static final String TAG       = "FissionOrchestrator";
    private static final int    CLUSTER_W = 1920;
    private static final int    CLUSTER_H = 720;

    // ── Slot state (value type) ───────────────────────────────────────────────

    public static final class SlotState {
        public final String pkg;
        public final String label;
        public final int    displayId;
        public       Rect   rect;

        SlotState(String pkg, String label, int displayId, Rect rect) {
            this.pkg = pkg; this.label = label;
            this.displayId = displayId; this.rect = new Rect(rect);
        }
    }

    // ── Callbacks (all on main thread) ────────────────────────────────────────

    public interface Callbacks {
        /** Called whenever the slot map changes (add / remove / resize). */
        void onSlotsChanged(java.util.Collection<SlotState> slots);
        /** Called with the acquired daemon binder so the caller can pass it to sub-screens. */
        void onDaemonBinderAcquired(IBinder binder);
        /** Status message for UI display; null = idle / clear. */
        void onStatusMessage(String message);
        /** Called when an unrecoverable error occurs starting a slot. */
        void onSlotError(String pkg, String message);
        /** Called when normal projection is active and must be stopped first. */
        void onProjectionConflict(Runnable proceedCallback);
    }

    // ── State ────────────────────────────────────────────────────────────────

    private final Context                             mAppCtx;
    private final ProjectionStateProvider             mProjectionState;
    private final Callbacks                           mCallbacks;
    private final Handler                             mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService                     mExec =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fission-exec");
                t.setDaemon(true);
                return t;
            });

    private final ConcurrentHashMap<String, SlotState> mSlots = new ConcurrentHashMap<>();

    private volatile IBinder  mDaemonBinder   = null;
    private volatile boolean  mProjecting     = false;
    private volatile boolean  mMirrorReady    = false;
    private volatile boolean  mDestroyed      = false;
    private          int      mFirstDisplayId = -1;
    private          LayoutPreset mActiveLayout = null;

    public FissionOrchestrator(Context context, ProjectionStateProvider projectionState,
                                Callbacks callbacks) {
        mAppCtx          = context.getApplicationContext();
        mProjectionState = projectionState;
        mCallbacks       = callbacks;
    }

    // ── App-launch auto-start ─────────────────────────────────────────────────

    /** One-shot per process: avoids re-activating the layout on every MainActivity return. */
    private static volatile boolean sAutoStartFired = false;

    /** Keeps the headless orchestrator reachable while its executor works. */
    @SuppressWarnings("unused")
    private static FissionOrchestrator sAutoStartOrchestrator;

    /** Listener notified (on the main thread) when the headless orchestrator's slot set changes. */
    public interface LayoutChangeListener { void onLayoutPackagesChanged(); }

    private static volatile LayoutChangeListener sLayoutChangeListener;

    /** Registers a UI listener for headless layout slot changes (MainActivity onStart). */
    public static void setLayoutChangeListener(LayoutChangeListener l) { sLayoutChangeListener = l; }

    /** Fires the registered layout-change listener on the main thread (if any). */
    private static void notifyLayoutChanged() {
        final LayoutChangeListener l = sLayoutChangeListener;
        if (l != null) new Handler(Looper.getMainLooper()).post(l::onLayoutPackagesChanged);
    }

    /**
     * Snapshot of the package names currently projected by the headless auto-start
     * orchestrator (the layout-launch path). Empty when no layout is active.
     */
    public static java.util.Set<String> getActiveLayoutPackages() {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o == null) return java.util.Collections.emptySet();
        java.util.Set<String> pkgs = new HashSet<>();
        for (SlotState s : o.getSlots()) {
            if (s.pkg != null && !s.pkg.isEmpty()) pkgs.add(s.pkg);
        }
        return pkgs;
    }

    /** True when {@code pkg} is currently projected by the headless layout orchestrator. */
    public static boolean isLayoutPackage(String pkg) {
        return pkg != null && getActiveLayoutPackages().contains(pkg);
    }

    /**
     * Kills a single layout slot: moves the app back to display 0, releases its VD and
     * force-stops it (via {@link #releaseSlotAsync}). No-op when no layout is active.
     */
    public static void killLayoutSlot(String pkg) {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o != null && pkg != null) o.releaseSlotAsync(pkg);
    }

    /**
     * True only when ClusterService has an app actively projected (mProjectionActive == true).
     * Unlike sIsRunning, this returns false as soon as stopProjectionNoAdb() is called —
     * even if the service is still bound by MainActivity.
     */
    private static boolean isClassicProjectionActive() {
        com.byd.dashcast.cluster.ClusterService cs =
                com.byd.dashcast.cluster.ClusterService.getInstance();
        return cs != null && cs.isProjectionActive();
    }

    /**
     * Launch-time entry point: when "auto favourite layout" is enabled, activates the
     * cluster projection and the favourite layout (which then launches the bound apps)
     * as soon as DashCast starts — without requiring the user to open the Fission screen.
     *
     * <p>No-op when the option is off, Layouts mode is disabled, no favourite layout
     * exists, classic projection is already running, or it already fired this process.
     */
    public static void maybeAutoStartOnAppLaunch(Context context) {
        if (sAutoStartFired) return;
        final Context appCtx = context.getApplicationContext();
        if (!com.byd.dashcast.data.prefs.ClusterPrefs.isFissionAutoLayout(appCtx)) return;
        if (!com.byd.dashcast.proxy.DaemonConfig.isFissionModeEnabled(appCtx)) return;
        LayoutPreset fav = LayoutPrefs.getFavoriteLayout(appCtx);
        if (fav == null) return;
        if (isClassicProjectionActive()) {
            AppLogger.d(TAG, "auto-start skipped: classic projection already active");
            return;
        }
        sAutoStartFired = true;
        AppLogger.i(TAG, "auto-start on app launch: projection + layout « " + fav.name + " »");

        ProjectionStateProvider psp = new ProjectionStateProvider() {
            @Override public boolean isProjectionActive() {
                return isClassicProjectionActive();
            }
            @Override public void stopProjectionIfActive(Runnable onStopped) {
                com.byd.dashcast.cluster.ClusterService cs =
                        com.byd.dashcast.cluster.ClusterService.getInstance();
                if (cs != null) cs.stopProjectionNoAdb();
                if (onStopped != null) new Handler(Looper.getMainLooper()).post(onStopped);
            }
        };
        Callbacks headless = new Callbacks() {
            @Override public void onSlotsChanged(java.util.Collection<SlotState> slots) { notifyLayoutChanged(); }
            @Override public void onDaemonBinderAcquired(IBinder binder) {}
            @Override public void onStatusMessage(String message) {
                if (message != null) AppLogger.d(TAG, "auto-start: " + message);
            }
            @Override public void onSlotError(String pkg, String message) {
                AppLogger.w(TAG, "auto-start slot error " + pkg + ": " + message);
            }
            @Override public void onProjectionConflict(Runnable proceedCallback) {
                AppLogger.w(TAG, "auto-start: projection conflict — aborting (no UI to ask)");
            }
        };
        FissionOrchestrator orch = new FissionOrchestrator(appCtx, psp, headless);
        sAutoStartOrchestrator = orch;
        orch.initAsync(fav, true, false);
    }

    /**
     * Launches the apps bound to layout slots into their VDs.
     *
     * <p>Call this after {@link FissionClient#activateLayout} succeeds and has filled
     * {@code preset.slots[i].displayId}. Slots with a null/empty package name or
     * {@code displayId ≤ 0} are skipped. Runs blocking shell commands — must be
     * called from a background thread (e.g. LayoutManagerActivity's executor).
     */
    public static void launchAppsIntoPreset(Context ctx, LayoutPreset preset) {
        final Context appCtx = ctx.getApplicationContext();
        for (LayoutPreset.SlotDef slot : preset.slots) {
            final String pkg = slot.packageName;
            if (pkg == null || pkg.isEmpty() || slot.displayId <= 0) continue;
            if (!ProxyClient.isConnected()) {
                boolean ok = ProxyClient.connect(appCtx);
                if (!ok) {
                    AppLogger.w(TAG, "launchAppsIntoPreset: proxy not connected for " + pkg);
                    continue;
                }
            }
            try {
                String result = ProxyClient.launchAndForce(pkg, null, slot.displayId, slot.w, slot.h);
                String firstLine = (result != null) ? result.split("\n")[0] : "null";
                AppLogger.i(TAG, "launchAppsIntoPreset: " + pkg + "@" + slot.displayId
                        + " → " + firstLine);
            } catch (ProxyClient.ProxyException pe) {
                AppLogger.w(TAG, "launchAppsIntoPreset: launch failed for " + pkg + ": " + pe.getMessage());
            }
        }
    }

    /**
     * Manually launches the apps configured in the favourite layout.
     * Same flow as {@link #maybeAutoStartOnAppLaunch} but user-triggered — skips the
     * {@code isFissionAutoLayout} guard so it works when the auto-launch option is OFF.
     */
    public static void launchFavoriteLayoutApps(Context context) {
        final Context appCtx = context.getApplicationContext();
        if (!com.byd.dashcast.proxy.DaemonConfig.isFissionModeEnabled(appCtx)) return;
        LayoutPreset fav = LayoutPrefs.getFavoriteLayout(appCtx);
        if (fav == null) return;
        if (isClassicProjectionActive()) {
            AppLogger.d(TAG, "launchFavoriteLayoutApps skipped: classic projection active");
            return;
        }
        AppLogger.i(TAG, "manual launch layout apps: « " + fav.name + " »");

        ProjectionStateProvider psp = new ProjectionStateProvider() {
            @Override public boolean isProjectionActive() {
                return isClassicProjectionActive();
            }
            @Override public void stopProjectionIfActive(Runnable onStopped) {
                com.byd.dashcast.cluster.ClusterService cs =
                        com.byd.dashcast.cluster.ClusterService.getInstance();
                if (cs != null) cs.stopProjectionNoAdb();
                if (onStopped != null) new Handler(Looper.getMainLooper()).post(onStopped);
            }
        };
        Callbacks headless = new Callbacks() {
            @Override public void onSlotsChanged(java.util.Collection<SlotState> slots) { notifyLayoutChanged(); }
            @Override public void onDaemonBinderAcquired(IBinder binder) {}
            @Override public void onStatusMessage(String msg) {
                if (msg != null) AppLogger.d(TAG, "launch-layout: " + msg);
            }
            @Override public void onSlotError(String pkg, String msg) {
                AppLogger.w(TAG, "launch-layout slot error " + pkg + ": " + msg);
            }
            @Override public void onProjectionConflict(Runnable proceedCallback) {
                AppLogger.w(TAG, "launch-layout: projection conflict — aborting");
            }
        };
        FissionOrchestrator orch = new FissionOrchestrator(appCtx, psp, headless);
        sAutoStartOrchestrator = orch;
        orch.initAsync(fav, true, false);
    }

    /**
     * Stops and clears the headless auto-start orchestrator (if any).
     * Called by FissionActivity on create so it starts with a clean slate and
     * any apps started headlessly are properly moved back to display 0 and killed.
     */
    public static void stopAutoOrchestrator() {
        FissionOrchestrator o = sAutoStartOrchestrator;
        sAutoStartOrchestrator = null;
        if (o != null) {
            AppLogger.i(TAG, "stopping headless auto-start orchestrator");
            o.stopAll();
            notifyLayoutChanged();
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called on Activity.onDestroy() — shuts down executor and releases slots if finishing. */
    public void destroy(boolean isFinishing) {
        mDestroyed = true;
        mMainHandler.removeCallbacksAndMessages(null);
        if (isFinishing && !mSlots.isEmpty()) {
            final IBinder binder = mDaemonBinder;
            final List<String> pkgs = new ArrayList<>(mSlots.keySet());
            mExec.execute(() -> {
                boolean keepVds = com.byd.dashcast.data.prefs.ClusterPrefs
                        .isFissionPrecreateSlots(mAppCtx);
                for (String pkg : pkgs) {
                    // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
                    if (binder != null) FissionClient.moveToDisplay0(binder, pkg);
                    if (!keepVds && binder != null) {
                        try { FissionClient.releaseSlot(binder, pkg); } catch (Throwable ignored) {}
                    }
                    ShellGateway.execShell(mAppCtx, "am force-stop " + pkg);
                }
                if (binder != null) {
                    try { FissionClient.stopMirror(binder); } catch (Throwable ignored) {}
                }
            });
        }
        mExec.shutdown();
    }

    /** Probes the daemon and fires auto-layout / pre-create if configured. */
    public void initAsync(LayoutPreset favoriteLayout, boolean autoLayout, boolean precreate) {
        mExec.execute(() -> {
            tryGetBinder();
            if (favoriteLayout != null) {
                mActiveLayout = favoriteLayout;
                post(() -> mCallbacks.onSlotsChanged(mSlots.values()));
                if (autoLayout) {
                    post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_autoactivate)));
                    ensureClusterProjectionThen(() -> mExec.execute(this::activateFavoriteLayout));
                } else if (precreate) {
                    precreateSlots(favoriteLayout);
                }
            }
        });
    }

    /**
     * Drives Qt into projection mode before running {@code next}, if it isn't already.
     *
     * <p>Auto-layout used to launch the bound apps without checking the cluster state:
     * after a "restore BYD" (sendInfo 18+0) Qt renders natively, the mirror layerStack
     * targets a surface Qt owns, and every launched app stays invisible. The full
     * activation sequence (30→16→35 or warm path) must complete first.
     */
    private void ensureClusterProjectionThen(Runnable next) {
        if (com.byd.dashcast.cluster.display.ClusterManager.isQtInProjectionMode()) {
            next.run();
            return;
        }
        AppLogger.i(TAG, "auto-layout: Qt in native mode — activating cluster projection first");
        post(() -> {
            mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_projection));
            new com.byd.dashcast.cluster.display.ClusterManager(mAppCtx)
                    .activateClusterDisplay(
                            new com.byd.dashcast.cluster.display.ClusterManager.DisplayReadyCallback() {
                        @Override public void onDisplayReady(android.view.Display display,
                                                              int displayId) {
                            AppLogger.i(TAG, "auto-layout: cluster projection ready (display="
                                    + displayId + ")");
                            next.run();
                        }
                        @Override public void onDisplayTimeout() {
                            AppLogger.w(TAG, "auto-layout: cluster activation timed out — aborted");
                            post(() -> mCallbacks.onStatusMessage(null));
                        }
                    });
        });
    }

    /**
     * Starts a slot for the given package in the given bounds.
     * Checks for normal projection conflict before proceeding.
     *
     * @param surfaceHolder the SurfaceHolder for the cluster preview (may be {@code null}
     *                      when called from layout pre-create; mirror start is skipped then)
     */
    public void startSlot(String pkg, String label, Rect rect, SurfaceHolder surfaceHolder) {
        if (mProjectionState.isProjectionActive()) {
            post(() -> mCallbacks.onProjectionConflict(() -> {
                mProjectionState.stopProjectionIfActive(null);
                mMainHandler.postDelayed(() -> startSlot(pkg, label, rect, surfaceHolder), 400);
            }));
            return;
        }
        post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_starting_fmt, label)));
        mExec.execute(() -> {
            try {
                doStartSlot(pkg, label, rect, surfaceHolder);
            } catch (Exception e) {
                AppLogger.e(TAG, "startSlot error pkg=" + pkg, e);
                mSlots.remove(pkg);
                if (mDaemonBinder != null) {
                    try { FissionClient.releaseSlot(mDaemonBinder, pkg); } catch (Exception ignored) {}
                }
                post(() -> {
                    mCallbacks.onSlotsChanged(mSlots.values());
                    mCallbacks.onSlotError(pkg, e.getMessage());
                    mCallbacks.onStatusMessage(null);
                });
            }
        });
    }

    public void stopAll() {
        if (!mProjecting) return;
        post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_stopping)));
        mExec.execute(() -> {
            mProjecting  = false;
            mMirrorReady = false;
            // When "Pre-create slots on startup" is on, keep VDs alive so they persist
            // for the next session — only kill apps and move them back to display 0.
            boolean keepVds = com.byd.dashcast.data.prefs.ClusterPrefs
                    .isFissionPrecreateSlots(mAppCtx);
            for (String pkg : mSlots.keySet()) {
                // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
                if (mDaemonBinder != null) FissionClient.moveToDisplay0(mDaemonBinder, pkg);
                if (!keepVds && mDaemonBinder != null) {
                    try { FissionClient.releaseSlot(mDaemonBinder, pkg); } catch (Throwable ignored) {}
                }
                ShellGateway.execShell(mAppCtx, "am force-stop " + pkg);
            }
            mSlots.clear();
            if (mDaemonBinder != null) {
                try { FissionClient.stopMirror(mDaemonBinder); } catch (Throwable ignored) {}
            }
            mDaemonBinder   = null;
            mFirstDisplayId = -1;
            post(() -> {
                mCallbacks.onSlotsChanged(mSlots.values());
                mCallbacks.onDaemonBinderAcquired(null);
                mCallbacks.onStatusMessage(null);
            });
        });
    }

    public void releaseSlotAsync(String pkg) {
        mExec.execute(() -> {
            // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
            if (mDaemonBinder != null) FissionClient.moveToDisplay0(mDaemonBinder, pkg);
            if (mDaemonBinder != null) {
                try { FissionClient.releaseSlot(mDaemonBinder, pkg); }
                catch (Exception e) { AppLogger.e(TAG, "releaseSlot error", e); }
            }
            ShellGateway.execShell(mAppCtx, "am force-stop " + pkg);
            mSlots.remove(pkg);
            mProjecting = !mSlots.isEmpty();
            post(() -> mCallbacks.onSlotsChanged(mSlots.values()));
        });
    }

    public void resizeSlotAsync(String pkg, Rect rect) {
        SlotState slot = mSlots.get(pkg);
        if (slot != null) slot.rect = new Rect(rect);
        mExec.execute(() -> {
            if (mDaemonBinder == null) return;
            try {
                FissionClient.resizeSlot(mDaemonBinder, pkg,
                        rect.left, rect.top, rect.width(), rect.height());
            } catch (Exception e) {
                AppLogger.e(TAG, "resizeSlot error", e);
            }
        });
    }

    public void switchToLayoutAsync(LayoutPreset newLayout) {
        mActiveLayout = newLayout;
        mExec.execute(() -> {
            try {
                doSwitchToLayout(newLayout, null);
            } catch (Exception e) {
                AppLogger.e(TAG, "switchToLayout error", e);
                post(() -> mCallbacks.onSlotError("layout", e.getMessage()));
            } finally {
                post(() -> mCallbacks.onSlotsChanged(mSlots.values()));
            }
        });
    }

    public java.util.Collection<SlotState> getSlots()  { return mSlots.values(); }
    public boolean                          isProjecting() { return mProjecting; }
    public LayoutPreset                     getActiveLayout() { return mActiveLayout; }
    public IBinder                          getDaemonBinder() { return mDaemonBinder; }

    // ── Background logic ───────────────────────────────────────────────────────

    private void tryGetBinder() {
        IBinder b = FissionClient.getBinderFromServiceManager();
        if (b != null) {
            mDaemonBinder = b;
            post(() -> mCallbacks.onDaemonBinderAcquired(b));
            AppLogger.d(TAG, "Daemon binder found in ServiceManager");
        }
    }

    private boolean ensureDaemon() {
        if (mProjectionState.isProjectionActive()) {
            post(() -> mCallbacks.onProjectionConflict(null));
            return false;
        }
        IBinder b = FissionClient.getBinderFromServiceManager();
        if (b != null) {
            mDaemonBinder = b;
            final IBinder fb0 = b;
            post(() -> mCallbacks.onDaemonBinderAcquired(fb0));
            return true;
        }
        post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_daemon)));
        AdbLocalClient.startMirrorDaemon(mAppCtx);
        for (int i = 0; i < 16; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return false;
            }
            b = FissionClient.getBinderFromServiceManager();
            if (b != null) {
                mDaemonBinder = b;
                final IBinder fb = b;
                post(() -> mCallbacks.onDaemonBinderAcquired(fb));
                AppLogger.d(TAG, "Daemon binder acquired after " + ((i + 1) * 500) + "ms");
                return true;
            }
        }
        AppLogger.e(TAG, "Daemon binder NOT found after 8s");
        return false;
    }

    private void doStartSlot(String pkg, String label, Rect rect, SurfaceHolder surfaceHolder)
            throws Exception {
        boolean isFirst = mSlots.isEmpty();

        if (!ensureDaemon()) throw new RuntimeException(mAppCtx.getString(R.string.fo_err_daemon));

        // ATTACH_SLOT or REUSE if VD already alive in daemon
        int existingId = -1;
        try { existingId = FissionClient.querySlot(mDaemonBinder, pkg); } catch (Exception ignored) {}
        final int displayId;
        if (existingId > 0) {
            post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_reuse_fmt, label)));
            try {
                FissionClient.resizeSlot(mDaemonBinder, pkg,
                        rect.left, rect.top, rect.width(), rect.height());
            } catch (Exception ignored) {}
            displayId = existingId;
            AppLogger.i(TAG, "FISSION REUSE_SLOT pkg=" + pkg + " displayId=" + displayId);
        } else {
            post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_create_fmt, label)));
            int newId = FissionClient.attachSlot(mDaemonBinder, pkg,
                    rect.left, rect.top, rect.width(), rect.height());
            if (newId < 0) throw new RuntimeException(mAppCtx.getString(R.string.fo_err_attach_fmt, pkg));
            displayId = newId;
            AppLogger.i(TAG, "FISSION ATTACH_SLOT pkg=" + pkg + " displayId=" + displayId
                    + " rect=" + rect.left + "," + rect.top + "+" + rect.width() + "x" + rect.height());
        }

        // LAUNCH_AND_FORCE via ProxyClient
        post(() -> mCallbacks.onStatusMessage(
                mAppCtx.getString(R.string.fo_status_launching_fmt, label, displayId)));
        if (!ProxyClient.isConnected()) {
            AppLogger.d(TAG, "ProxyClient not connected — attempting connect…");
            boolean connected = ProxyClient.connect(mAppCtx);
            if (!connected) {
                throw new RuntimeException(
                        mAppCtx.getString(R.string.fo_err_proxy));
            }
        }
        String launchResult = ProxyClient.launchAndForce(pkg, null, displayId,
                rect.width(), rect.height());
        AppLogger.i(TAG, "FISSION launchAndForce result:\n" + launchResult);
        if (launchResult != null && !launchResult.startsWith("OK"))
            AppLogger.w(TAG, "FISSION launchAndForce non-OK: " + launchResult);

        // MIRROR_START on first slot
        if (isFirst && surfaceHolder != null && surfaceHolder.getSurface() != null
                && surfaceHolder.getSurface().isValid()) {
            post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_mirror)));
            mFirstDisplayId = displayId;
            int svW = surfaceHolder.getSurfaceFrame().width();
            int svH = surfaceHolder.getSurfaceFrame().height();
            if (svW <= 0 || svH <= 0) { svW = CLUSTER_W; svH = CLUSTER_H; }
            mMirrorReady = FissionClient.startMirror(mDaemonBinder,
                    displayId, rect.width(), rect.height(),
                    displayId, svW, svH, surfaceHolder.getSurface());
            AppLogger.i(TAG, "FISSION MIRROR_START displayId=" + displayId + " ok=" + mMirrorReady);
        }

        mSlots.put(pkg, new SlotState(pkg, label, displayId, rect));
        mProjecting = true;

        post(() -> {
            mCallbacks.onSlotsChanged(mSlots.values());
            mCallbacks.onStatusMessage(null);
        });
    }

    private void doSwitchToLayout(LayoutPreset newLayout, SurfaceHolder surfaceHolder)
            throws Exception {
        Set<String> newPkgs = new HashSet<>();
        if (newLayout != null) {
            for (LayoutPreset.SlotDef s : newLayout.slots) {
                if (s.packageName != null && !s.packageName.isEmpty()) newPkgs.add(s.packageName);
            }
        }
        // Release slots absent from the new layout (empty set = release all = free mode)
        for (String pkg : new ArrayList<>(mSlots.keySet())) {
            if (!newPkgs.contains(pkg)) {
                // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
                if (mDaemonBinder != null) FissionClient.moveToDisplay0(mDaemonBinder, pkg);
                if (mDaemonBinder != null) {
                    try { FissionClient.releaseSlot(mDaemonBinder, pkg); } catch (Exception ignored) {}
                }
                ShellGateway.execShell(mAppCtx, "am force-stop " + pkg);
                mSlots.remove(pkg);
            }
        }
        mProjecting = !mSlots.isEmpty();
        // Start / reuse bound slots (skipped in free mode when newLayout is null)
        if (newLayout != null) {
            for (LayoutPreset.SlotDef s : newLayout.slots) {
                if (s.packageName == null || s.packageName.isEmpty()) continue;
                if (mSlots.containsKey(s.packageName)) continue;
                String appLabel = getAppLabel(s.packageName);
                doStartSlot(s.packageName, appLabel, s.toRect(), surfaceHolder);
            }
        }
    }

    private void activateFavoriteLayout() {
        LayoutPreset fav = LayoutPrefs.getFavoriteLayout(mAppCtx);
        if (fav == null) { post(() -> mCallbacks.onStatusMessage(null)); return; }
        mActiveLayout = fav;
        try {
            doSwitchToLayout(fav, null);
        } catch (Exception e) {
            AppLogger.e(TAG, "activateFavoriteLayout failed", e);
            post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_autolayout_err_fmt, e.getMessage())));
        }
    }

    private void precreateSlots(LayoutPreset layout) {
        post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_precreate)));
        if (!ensureDaemon()) { post(() -> mCallbacks.onStatusMessage(null)); return; }
        for (LayoutPreset.SlotDef s : layout.slots) {
            String key = (s.packageName != null && !s.packageName.isEmpty())
                    ? s.packageName : s.label;
            try {
                int id = FissionClient.attachSlot(mDaemonBinder, key, s.x, s.y, s.w, s.h);
                if (id > 0) AppLogger.i(TAG, "FISSION PRECREATE slot=" + key + " displayId=" + id);
            } catch (Exception e) {
                AppLogger.w(TAG, "precreateSlots failed for " + key + ": " + e.getMessage());
            }
        }
        post(() -> mCallbacks.onStatusMessage(null));
    }

    private String getAppLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = mAppCtx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    private void post(Runnable r) {
        if (mDestroyed) return;
        mMainHandler.post(() -> { if (!mDestroyed) r.run(); });
    }
}
