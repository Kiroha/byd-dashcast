package com.byd.dashcast.fission;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.MotionEvent;
import android.view.Display;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        public final int    layerStack;
        public       Rect   rect;

        SlotState(String pkg, String label, int displayId, int layerStack, Rect rect) {
            this.pkg = pkg; this.label = label;
            this.displayId = displayId; this.layerStack = layerStack; this.rect = new Rect(rect);
        }
    }

    /** Immutable target consumed by Main's tactile Layout mirror. */
    public static final class LayoutMirrorTarget {
        public final String pkg;
        public final String label;
        public final int displayId;
        public final int layerStack;
        public final int width;
        public final int height;

        LayoutMirrorTarget(SlotState slot) {
            pkg = slot.pkg;
            label = slot.label;
            displayId = slot.displayId;
            layerStack = slot.layerStack;
            width = slot.rect.width();
            height = slot.rect.height();
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
    // volatile: written on the fission-exec thread (initAsync / activateFavoriteLayout) and
    // on the main thread (switchToLayoutAsync), read on the main thread (getActiveLayout) —
    // without volatile a background write may not be visible to a later main-thread read
    // (stale layout-selector label/checkmark). Siblings above are already volatile.
    private volatile int      mFirstDisplayId = -1;
    private volatile LayoutPreset mActiveLayout = null;
    private volatile String mSelectedMirrorPackage = null;
    private volatile boolean mAutoStartAttempt = false;

    public FissionOrchestrator(Context context, ProjectionStateProvider projectionState,
                                Callbacks callbacks) {
        mAppCtx          = context.getApplicationContext();
        mProjectionState = projectionState;
        mCallbacks       = callbacks;
    }

    // ── App-launch auto-start ─────────────────────────────────────────────────

    /** One-shot per process: avoids re-activating the layout on every MainActivity return. */
    private static volatile boolean sAutoStartFired = false;

    public enum AutoStartResult {
        DISABLED,
        ALREADY_STARTED,
        MISSING_LAYOUT,
        PROJECTION_CONFLICT,
        STARTED
    }

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

    /** Returns the slot currently selected for Main's tactile mirror. */
    public static LayoutMirrorTarget getSelectedLayoutMirrorTarget() {
        FissionOrchestrator o = sAutoStartOrchestrator;
        return o != null ? o.selectedMirrorTarget() : null;
    }

    /** Selects a running Layout app as the tactile mirror target. */
    public static LayoutMirrorTarget selectLayoutMirrorPackage(String pkg) {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o == null || pkg == null || !o.mSlots.containsKey(pkg)) return null;
        o.mSelectedMirrorPackage = pkg;
        notifyLayoutChanged();
        return o.selectedMirrorTarget();
    }

    /** Selects the previous/next running slot in the saved Layout's zone order. */
    public static LayoutMirrorTarget stepLayoutMirrorSelection(int delta) {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o == null) return null;
        List<String> ordered = o.orderedSlotPackages();
        o.mSelectedMirrorPackage = LayoutSlotSelection.step(
                o.mSelectedMirrorPackage, ordered, delta);
        notifyLayoutChanged();
        return o.selectedMirrorTarget();
    }

    /** Starts a mirror of the selected slot and routes daemon input to that slot display. */
    public static LayoutMirrorTarget startSelectedLayoutMirror(
            Surface surface, int viewWidth, int viewHeight) {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o == null || o.mDaemonBinder == null || surface == null || !surface.isValid()) {
            return null;
        }
        LayoutMirrorTarget target = o.selectedMirrorTarget();
        if (target == null) return null;
        try {
            String focus = FissionClient.focusSlot(o.mDaemonBinder, target.pkg);
            if (focus == null || !focus.startsWith("OK ")) {
                AppLogger.w(TAG, "Layout tactile focus best-effort for " + target.pkg
                        + ": " + focus);
            }
        } catch (Exception focusError) {
            AppLogger.w(TAG, "Layout tactile focus unavailable for " + target.pkg
                    + ": " + focusError.getMessage());
        }
        try {
            boolean ok = FissionClient.startMirror(o.mDaemonBinder,
                    target.layerStack, target.width, target.height,
                    target.displayId, viewWidth, viewHeight, surface);
            if (!ok) return null;
            o.mMirrorReady = true;
            o.mFirstDisplayId = target.displayId;
            AppLogger.i(TAG, "Layout tactile mirror selected pkg=" + target.pkg
                    + " displayId=" + target.displayId + " layerStack=" + target.layerStack);
            return target;
        } catch (Exception error) {
            AppLogger.e(TAG, "startSelectedLayoutMirror failed for " + target.pkg, error);
            return null;
        }
    }

    public static void stopSelectedLayoutMirror() {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o == null || o.mDaemonBinder == null) return;
        FissionClient.stopMirror(o.mDaemonBinder);
        o.mMirrorReady = false;
        o.mFirstDisplayId = -1;
    }

    public static boolean injectSelectedLayoutMotion(MotionEvent event) {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o == null || o.mDaemonBinder == null || event == null) return false;
        try {
            FissionClient.injectMotion(o.mDaemonBinder, event);
            return true;
        } catch (Exception error) {
            AppLogger.e(TAG, "injectSelectedLayoutMotion failed", error);
            return false;
        }
    }

    /**
     * Kills a single layout slot: moves the app back to display 0, releases its VD and
     * force-stops it (via {@link #releaseSlotAsync}). No-op when no layout is active.
     */
    public static void killLayoutSlot(String pkg) {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o != null && pkg != null) o.releaseSlotAsync(pkg);
    }

    private synchronized LayoutMirrorTarget selectedMirrorTarget() {
        List<String> ordered = orderedSlotPackages();
        mSelectedMirrorPackage = LayoutSlotSelection.resolve(mSelectedMirrorPackage, ordered);
        SlotState slot = mSelectedMirrorPackage != null
                ? mSlots.get(mSelectedMirrorPackage) : null;
        return slot != null ? new LayoutMirrorTarget(slot) : null;
    }

    private List<String> orderedSlotPackages() {
        List<String> ordered = new ArrayList<>();
        LayoutPreset layout = mActiveLayout;
        if (layout != null) {
            for (LayoutPreset.SlotDef slot : layout.slots) {
                String pkg = slot.packageName;
                if (pkg != null && mSlots.containsKey(pkg) && !ordered.contains(pkg)) {
                    ordered.add(pkg);
                }
            }
        }
        List<String> extras = new ArrayList<>(mSlots.keySet());
        java.util.Collections.sort(extras);
        for (String pkg : extras) if (!ordered.contains(pkg)) ordered.add(pkg);
        return ordered;
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
    public static boolean isAutoStartRequested(Context context) {
        Context appCtx = context.getApplicationContext();
        return LayoutAutoStartPolicy.isRequested(
                com.byd.dashcast.proxy.DaemonConfig.isFissionModeEnabled(appCtx),
                com.byd.dashcast.data.prefs.ClusterPrefs.isFissionAutoLayout(appCtx));
    }

    public static synchronized AutoStartResult maybeAutoStartOnAppLaunch(Context context) {
        if (sAutoStartFired) return AutoStartResult.ALREADY_STARTED;
        final Context appCtx = context.getApplicationContext();
        if (!isAutoStartRequested(appCtx)) return AutoStartResult.DISABLED;
        LayoutPreset fav = LayoutPrefs.getAutoStartLayout(appCtx);
        if (fav == null) {
            AppLogger.w(TAG, "auto-start requested but no unambiguous saved layout has bound apps");
            return AutoStartResult.MISSING_LAYOUT;
        }
        if (isClassicProjectionActive()) {
            AppLogger.d(TAG, "auto-start skipped: classic projection already active");
            return AutoStartResult.PROJECTION_CONFLICT;
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
        orch.mAutoStartAttempt = true;
        // Tear down any previous headless orchestrator before orphaning it, or its
        // fission-exec thread leaks (the static ref was overwritten without a stop()).
        FissionOrchestrator prevAuto = sAutoStartOrchestrator;
        if (prevAuto != null) { prevAuto.stopAll(); prevAuto.shutdown(); }
        sAutoStartOrchestrator = orch;
        orch.initAsync(fav, true, false);
        return AutoStartResult.STARTED;
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
        LayoutPreset fav = LayoutPrefs.getAutoStartLayout(appCtx);
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
        // Tear down any previous headless orchestrator before orphaning it, or its
        // fission-exec thread leaks (the static ref was overwritten without a stop()).
        FissionOrchestrator prevAuto = sAutoStartOrchestrator;
        if (prevAuto != null) { prevAuto.stopAll(); prevAuto.shutdown(); }
        sAutoStartOrchestrator = orch;
        orch.initAsync(fav, true, false);
    }

    /**
     * Stops and clears the headless auto-start orchestrator (if any).
     * Called by FissionActivity on create so it starts with a clean slate and
     * any apps started headlessly are properly moved back to display 0 and killed.
     */
    public static void stopAutoOrchestrator() {
        stopAutoOrchestrator(null);
    }

    /**
     * Stops the headless orchestrator and invokes {@code onComplete} on the main thread only after
     * every Layout package has completed move → verified force-stop → optional slot release.
     */
    public static void stopAutoOrchestrator(Runnable onComplete) {
        FissionOrchestrator o = sAutoStartOrchestrator;
        sAutoStartOrchestrator = null;
        if (o != null) {
            AppLogger.i(TAG, "stopping headless auto-start orchestrator");
            o.stopAll(() -> {
                notifyLayoutChanged();
                if (onComplete != null) onComplete.run();
            });
            // stopAll() submitted its teardown to mExec but never shut it down; this
            // throwaway orchestrator is dropped here (never destroy()'d), so shut the
            // executor down gracefully or its worker thread leaks per headless stop.
            o.shutdown();
        } else if (onComplete != null) {
            new Handler(Looper.getMainLooper()).post(onComplete);
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

    /**
     * Shuts down the single-thread executor gracefully (an in-flight stopAll/teardown task
     * still runs to completion). Call after stopAll() on a throwaway headless orchestrator
     * that is never destroy()'d, so its "fission-exec" worker thread doesn't leak.
     */
    public void shutdown() {
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
                            markAutoStartFailed("cluster activation timeout");
                        }
                        // No-op (matches the former Kotlin-interface default body): the auto-layout
                        // flow doesn't act on a late-arriving display. Explicit because the
                        // DisplayReadyCallback default method is not exposed to Java without
                        // -Xjvm-default. No behaviour change.
                        @Override public void onDisplayLateReady(android.view.Display display,
                                                                  int displayId) {}
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
        stopAll(null);
    }

    public void stopAll(Runnable onComplete) {
        post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_stopping)));
        mExec.execute(() -> {
            final List<String> packages = new ArrayList<>(mSlots.keySet());
            if (!mProjecting && packages.isEmpty()) {
                mMainHandler.post(() -> {
                    mCallbacks.onStatusMessage(null);
                    if (onComplete != null) onComplete.run();
                });
                return;
            }
            mProjecting  = false;
            mMirrorReady = false;
            // When "Pre-create slots on startup" is on, keep VDs alive so they persist
            // for the next session — only kill apps and move them back to display 0.
            boolean keepVds = com.byd.dashcast.data.prefs.ClusterPrefs
                    .isFissionPrecreateSlots(mAppCtx);
            final IBinder binder = mDaemonBinder;
            FissionTeardownPlan.run(packages, keepVds, new FissionTeardownPlan.Operations() {
                @Override public String moveToDisplay0(String pkg) {
                    if (binder == null) throw new IllegalStateException("mirror daemon unavailable");
                    String result = FissionClient.moveToDisplay0(binder, pkg);
                    if (result == null || (!result.startsWith("OK ")
                            && !result.startsWith("SKIP ")
                            && !result.startsWith("no task for "))) {
                        throw new IllegalStateException(result == null ? "empty move result" : result);
                    }
                    AppLogger.i(TAG, "Layout teardown move verified pkg=" + pkg + " → " + result);
                    return result;
                }

                @Override public boolean forceStopAndWait(String pkg) {
                    return forceStopAndWaitForResult(pkg);
                }

                @Override public void releaseSlot(String pkg) throws Exception {
                    if (binder == null) throw new IllegalStateException("mirror daemon unavailable");
                    FissionClient.releaseSlot(binder, pkg);
                }

                @Override public void onStepError(String pkg, String step, Throwable error) {
                    AppLogger.e(TAG, "Layout teardown " + step + " failed for " + pkg
                            + ": " + error.getMessage());
                }
            });
            mSlots.clear();
            mSelectedMirrorPackage = null;
            if (binder != null) {
                try { FissionClient.stopMirror(binder); } catch (Throwable ignored) {}
            }
            mDaemonBinder   = null;
            mFirstDisplayId = -1;
            mMainHandler.post(() -> {
                mCallbacks.onSlotsChanged(mSlots.values());
                mCallbacks.onDaemonBinderAcquired(null);
                mCallbacks.onStatusMessage(null);
                if (onComplete != null) onComplete.run();
            });
        });
    }

    /** Worker-thread only: waits for the shared removeTask + force-stop + PID verification path. */
    private boolean forceStopAndWaitForResult(String pkg) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean killed = new AtomicBoolean(false);
        AdbLocalClient.forceStopApp(mAppCtx, pkg, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String result) {
                killed.set(true);
                AppLogger.i(TAG, "Layout teardown force-stop verified pkg=" + pkg
                        + " → " + result);
                done.countDown();
            }

            @Override public void onError(String error) {
                AppLogger.w(TAG, "Layout teardown force-stop failed pkg=" + pkg
                        + " → " + error);
                done.countDown();
            }
        });
        try {
            if (!done.await(20, TimeUnit.SECONDS)) {
                AppLogger.w(TAG, "Layout teardown force-stop timeout pkg=" + pkg);
                return false;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return killed.get();
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
                mSelectedMirrorPackage = LayoutSlotSelection.resolve(
                    mSelectedMirrorPackage, orderedSlotPackages());
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
    /** The SURFACE daemon's binder (see {@link FissionClient}), or {@code null} if not resolved. */
    public IBinder                          getSurfaceDaemonBinder() { return mDaemonBinder; }

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
        if (!com.byd.dashcast.proxy.daemon.TaskLaunchRecovery.isSuccessful(launchResult)) {
            AppLogger.w(TAG, "FISSION launchAndForce failed/incomplete: " + launchResult);
        }

        int layerStack = resolveLayerStack(displayId);

        // MIRROR_START on first slot
        if (isFirst && surfaceHolder != null && surfaceHolder.getSurface() != null
                && surfaceHolder.getSurface().isValid()) {
            post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_mirror)));
            mFirstDisplayId = displayId;
            int svW = surfaceHolder.getSurfaceFrame().width();
            int svH = surfaceHolder.getSurfaceFrame().height();
            if (svW <= 0 || svH <= 0) { svW = CLUSTER_W; svH = CLUSTER_H; }
            mMirrorReady = FissionClient.startMirror(mDaemonBinder,
                    layerStack, rect.width(), rect.height(),
                    displayId, svW, svH, surfaceHolder.getSurface());
            AppLogger.i(TAG, "FISSION MIRROR_START displayId=" + displayId + " ok=" + mMirrorReady);
        }

        mSlots.put(pkg, new SlotState(pkg, label, displayId, layerStack, rect));
        mSelectedMirrorPackage = LayoutSlotSelection.resolve(
            mSelectedMirrorPackage, orderedSlotPackages());
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
        LayoutPreset fav = LayoutPrefs.getAutoStartLayout(mAppCtx);
        if (fav == null) {
            post(() -> mCallbacks.onStatusMessage(null));
            markAutoStartFailed("saved favourite layout disappeared");
            return;
        }
        mActiveLayout = fav;
        try {
            doSwitchToLayout(fav, null);
        } catch (Exception e) {
            AppLogger.e(TAG, "activateFavoriteLayout failed", e);
            post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_autolayout_err_fmt, e.getMessage())));
            markAutoStartFailed("layout activation failed: " + e.getMessage());
        }
    }

    private void markAutoStartFailed(String reason) {
        if (!mAutoStartAttempt) return;
        synchronized (FissionOrchestrator.class) {
            if (sAutoStartOrchestrator == this) sAutoStartOrchestrator = null;
            sAutoStartFired = false;
        }
        AppLogger.w(TAG, "auto-start re-armed after failure: " + reason);
        if (!mSlots.isEmpty() || mProjecting) stopAll();
        shutdown();
        notifyLayoutChanged();
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

    private int resolveLayerStack(int displayId) {
        try {
            DisplayManager displayManager = (DisplayManager) mAppCtx.getSystemService(
                    Context.DISPLAY_SERVICE);
            Display display = displayManager != null ? displayManager.getDisplay(displayId) : null;
            if (display == null) return displayId;
            java.lang.reflect.Method method = Display.class.getDeclaredMethod("getLayerStack");
            method.setAccessible(true);
            Object value = method.invoke(display);
            if (value instanceof Integer && (Integer) value >= 0) return (Integer) value;
        } catch (Throwable error) {
            AppLogger.w(TAG, "slot layerStack lookup failed for display " + displayId
                    + ": " + error.getMessage());
        }
        return displayId;
    }

    private void post(Runnable r) {
        if (mDestroyed) return;
        mMainHandler.post(() -> { if (!mDestroyed) r.run(); });
    }
}
