package com.byd.dashcast.fission;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.DeadObjectException;
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
import com.byd.dashcast.proxy.DaemonBinderResolver;

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
    private volatile long mActivationGuardToken = 0L;
    private volatile com.byd.dashcast.cluster.display.ClusterManager mClusterActivationManager;

    /**
     * Daemon slot keys of the layout zones that have NO app bound ("free zones"), created by
     * the last manual activation. Deliberately kept out of {@link #mSlots}: those keys are
     * package names and feed {@link #getActiveLayoutPackages()} and Main's mirror selector.
     */
    private final Set<String> mFreeZoneKeys = new java.util.LinkedHashSet<>();

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
    private static volatile FissionOrchestrator sAutoStartOrchestrator;

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
        if (o == null || surface == null || !surface.isValid()) {
            return null;
        }
        IBinder binder = o.surfaceBinderForTactile("LayoutMirrorStart");
        if (binder == null) return null;
        LayoutMirrorTarget target = o.selectedMirrorTarget();
        if (target == null) return null;
        try {
            String focus = FissionClient.focusSlot(binder, target.pkg);
            if (focus == null || !focus.startsWith("OK ")) {
                AppLogger.w(TAG, "Layout tactile focus best-effort for " + target.pkg
                        + ": " + focus);
            }
        } catch (DeadObjectException dead) {
            o.recoverSurfaceBinderIfCurrent(binder, "LayoutFocus");
            AppLogger.w(TAG, "Layout tactile focus lost surface daemon for " + target.pkg);
            return null;
        } catch (Exception focusError) {
            AppLogger.w(TAG, "Layout tactile focus unavailable for " + target.pkg
                    + ": " + focusError.getMessage());
        }
        try {
            boolean ok = FissionClient.startMirror(binder,
                    target.layerStack, target.width, target.height,
                    target.displayId, viewWidth, viewHeight, surface);
            if (!ok) return null;
            o.mMirrorReady = true;
            o.mFirstDisplayId = target.displayId;
            AppLogger.i(TAG, "Layout tactile mirror selected pkg=" + target.pkg
                    + " displayId=" + target.displayId + " layerStack=" + target.layerStack);
            return target;
        } catch (DeadObjectException dead) {
            o.recoverSurfaceBinderIfCurrent(binder, "LayoutMirrorStart");
            AppLogger.w(TAG, "Layout tactile mirror lost surface daemon for " + target.pkg);
            return null;
        } catch (Exception error) {
            AppLogger.e(TAG, "startSelectedLayoutMirror failed for " + target.pkg, error);
            return null;
        }
    }

    public static void stopSelectedLayoutMirror() {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o == null) return;
        IBinder binder = o.mDaemonBinder;
        boolean accepted = FissionClient.stopMirror(binder);
        boolean ownerGone = binder == null || !binder.isBinderAlive();
        if (!accepted && binder != null && !binder.isBinderAlive()) {
            // The old process already destroyed its mirror/input state. Reacquire for subsequent
            // tactile operations, but do not pretend the dead binder accepted this command.
            o.recoverSurfaceBinderIfCurrent(binder, "LayoutMirrorStop");
        }
        if (accepted || ownerGone) {
            o.mMirrorReady = false;
            o.mFirstDisplayId = -1;
        } else {
            AppLogger.e(TAG, "Layout tactile mirror STOP was not accepted; retaining local state");
        }
    }

    public static boolean injectSelectedLayoutMotion(MotionEvent event) {
        FissionOrchestrator o = sAutoStartOrchestrator;
        if (o == null || event == null) return false;
        IBinder binder = o.surfaceBinderForTactile("LayoutMotion");
        if (binder == null) return false;
        try {
            FissionClient.injectMotion(binder, event);
            return true;
        } catch (DeadObjectException dead) {
            o.recoverSurfaceBinderIfCurrent(binder, "LayoutMotion");
            AppLogger.w(TAG, "Layout tactile input lost surface daemon");
            return false;
        } catch (Exception error) {
            AppLogger.e(TAG, "injectSelectedLayoutMotion failed", error);
            return false;
        }
    }

    private IBinder surfaceBinderForTactile(String reason) {
        IBinder current = mDaemonBinder;
        if (current != null) return current;
        IBinder fresh = DaemonBinderResolver.reacquireSurfaceBinder(reason);
        if (fresh == null) return null;
        boolean adopted = false;
        synchronized (this) {
            if (mDaemonBinder == null) {
                mDaemonBinder = fresh;
                adopted = true;
            }
            current = mDaemonBinder;
        }
        if (adopted) {
            final IBinder published = current;
            post(() -> mCallbacks.onDaemonBinderAcquired(published));
        }
        return current;
    }

    private IBinder recoverSurfaceBinderIfCurrent(IBinder failed, String reason) {
        synchronized (this) {
            if (mDaemonBinder != failed) return mDaemonBinder;
            mDaemonBinder = null;
        }
        IBinder fresh = DaemonBinderResolver.reacquireSurfaceBinder(reason);
        if (fresh == failed || (fresh != null && !fresh.isBinderAlive())) fresh = null;
        boolean adopted = false;
        synchronized (this) {
            if (mDaemonBinder == null) {
                mDaemonBinder = fresh;
                adopted = true;
            }
            fresh = mDaemonBinder;
        }
        if (adopted) {
            final IBinder published = fresh;
            post(() -> mCallbacks.onDaemonBinderAcquired(published));
        }
        return fresh;
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
        // Take the SAME guard activateLayoutManually takes, for the same reason it exists: two
        // concurrent activations build two ClusterManagers, and the second one's cancel() cannot
        // unregister the first's DisplayListener — the listener leak + double-launch fixed in
        // 1.2.29. Only the manual path ever took it, so the two paths did not exclude each other:
        // auto-start runs for several seconds at launch (30 → 3s → 16 → 3s → 35), and a user who
        // opens Layout Manager and taps Activate inside that window walked straight past a guard
        // that was never armed.
        //
        // Released in activateFavoriteLayout and in markAutoStartFailed — the success and failure
        // funnels of this path — and, if both are somehow missed, stolen by the existing
        // ACTIVATION_GUARD_MAX_MS expiry. A lost guard here cannot disable anything permanently.
        final long nowMs = android.os.SystemClock.elapsedRealtime();
        ActivationAttemptGate.Acquisition activation = sActivationGate.tryAcquire(nowMs);
        if (activation == null) {
            AppLogger.w(TAG, "auto-start skipped: an activation is already in flight (held "
                    + sActivationGate.heldMs(nowMs) + "ms)");
            return AutoStartResult.PROJECTION_CONFLICT;
        }
        if (activation.getReclaimed()) {
            AppLogger.w(TAG, "auto-start reclaimed an expired activation guard");
        }
        sAutoStartFired = true;
        AppLogger.i(TAG, "auto-start on app launch: projection + layout « " + fav.name + " »");

        FissionOrchestrator orch = new FissionOrchestrator(appCtx,
                headlessProjectionState(), headlessCallbacks("auto-start"));
        orch.mAutoStartAttempt = true;
        orch.mActivationGuardToken = activation.getToken();
        replaceHeadlessAfterStop(orch, fav);
        return AutoStartResult.STARTED;
    }

    /**
     * Projection-state provider shared by every headless (no-Activity) orchestrator.
     * Extracted so a new entry point cannot silently diverge from the auto-start path.
     */
    private static ProjectionStateProvider headlessProjectionState() {
        return new ProjectionStateProvider() {
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
    }

    /** Log-only callbacks for a headless orchestrator; {@code logPrefix} names the entry point. */
    private static Callbacks headlessCallbacks(String logPrefix) {
        return new Callbacks() {
            @Override public void onSlotsChanged(java.util.Collection<SlotState> slots) { notifyLayoutChanged(); }
            @Override public void onDaemonBinderAcquired(IBinder binder) {}
            @Override public void onStatusMessage(String message) {
                if (message != null) AppLogger.d(TAG, logPrefix + ": " + message);
            }
            @Override public void onSlotError(String pkg, String message) {
                AppLogger.w(TAG, logPrefix + " slot error " + pkg + ": " + message);
            }
            @Override public void onProjectionConflict(Runnable proceedCallback) {
                AppLogger.w(TAG, logPrefix + ": projection conflict — aborting (no UI to ask)");
            }
        };
    }

    // ── Manual (user-triggered) layout activation ─────────────────────────────

    /**
     * Stable, <b>English</b> failure codes handed to {@link ActivationCallback}.
     *
     * <p>Deliberately not user text: this value is what {@code AppLogger.e} writes into the
     * journal that ships inside every bug report, and triage greps the whole corpus for it. A
     * localised message here would make a Turkish or Russian capture unsearchable. The UI maps
     * these to an {@code R.string} for display — see {@code LayoutManagerActivity#activateLayout}.
     */
    public static final String ERR_CLUSTER_TIMEOUT = "cluster activation timeout";
    /** @see #ERR_CLUSTER_TIMEOUT */
    public static final String ERR_PROJECTION_CONFLICT = "classic projection already active";
    /** @see #ERR_CLUSTER_TIMEOUT */
    public static final String ERR_NO_DAEMON = "surface daemon unavailable";
    /** @see #ERR_CLUSTER_TIMEOUT */
    public static final String ERR_BUSY = "activation already in flight";
    /** @see #ERR_CLUSTER_TIMEOUT */
    public static final String ERR_ABANDONED = "layout stopped during cluster activation";

    /**
    * The generation-bound gate records when its current owner was acquired.
     *
     * <p>The guard is cleared from the activation callback, and that callback is only reached
     * through {@link #deliver}. {@code ClusterManager} posts no deadline of its own on the warm
     * and DiLink 5 paths: if the underlying {@code sendInfo} callback never arrives — a
     * half-open ADB-TCP socket is the documented D50F_LC condition — NEITHER
     * {@code onDisplayReady} nor {@code onDisplayTimeout} fires, nothing calls back, and the
     * guard would stay taken for the life of the process, silently killing the Activate button.
     * So the guard also expires: an attempt older than this bound is assumed lost and stolen.
     * Generous on purpose — a real sequence is ~6.5 s (30 → 3 s → 16 → 3 s → 35) and the DiLink
     * 4 daemon display probe is bounded at 13 s.
     */
    private static final long ACTIVATION_GUARD_MAX_MS = 60_000L;
        private static final ActivationAttemptGate sActivationGate =
            new ActivationAttemptGate(ACTIVATION_GUARD_MAX_MS);

    /**
     * Set once {@link #ensureDaemon()} has let {@code AdbLocalClient.startMirrorDaemon} compare
     * the running daemon's build marker against this APK's. Process-scoped on purpose: a daemon
     * outlives an app update, so the check is needed once per app process, not once per layout.
     */
    private static volatile boolean sDaemonFreshnessChecked = false;

    /** Outcome of {@link #activateLayoutManually}; always delivered on the main thread. */
    public interface ActivationCallback {
        /**
         * @param ok    {@code true} when every zone of the preset got a live display
         * @param error {@code null} when the activation ran to completion (check {@code ok} for
         *              partial success), otherwise one of the {@code ERR_*} codes above, or a
         *              raw exception message. Always English — see {@link #ERR_CLUSTER_TIMEOUT}.
         */
        void onActivationResult(boolean ok, String error);
    }

    /**
     * Activates an explicit layout on user request (Layout Manager "Activate").
     *
     * <p>Runs the <b>same</b> sequence as the auto-start path, which the manual button never
     * did: cluster projection first via {@link #ensureClusterProjectionThen} (without it the
     * slots are created while Qt still scans out the OEM's native view and nothing is
     * visible), then {@link #ensureDaemon} — which <em>starts</em> the SurfaceDaemon and polls
     * for it instead of merely testing the binder and giving up — then one {@code ATTACH_SLOT}
     * per zone, so every slot stays addressable by QUERY / RESIZE / RELEASE.
     *
     * <p>Reuses the process-wide headless orchestrator when one exists, so an already-running
     * layout is <em>switched</em> (zones absent from the new preset released, shared ones kept
     * alive) instead of being torn down and rebuilt.
     *
     * <p>Re-entrant taps are rejected with {@link #ERR_BUSY}: the cluster sequence takes
     * seconds (30 → 3 s → 16 → 3 s → 35) with only a short toast for feedback, so a second tap
     * is likely, and it would build a second {@code ClusterManager} whose {@code cancel()}
     * cannot unregister the first instance's DisplayListener — the exact listener leak +
     * double-launch fixed in 1.2.29.
     */
    public static synchronized void activateLayoutManually(Context context, LayoutPreset preset,
                                                           ActivationCallback callback) {
        final Context appCtx = context.getApplicationContext();
        final long nowMs = android.os.SystemClock.elapsedRealtime();
        ActivationAttemptGate.Acquisition activation = sActivationGate.tryAcquire(nowMs);
        if (activation == null) {
            AppLogger.w(TAG, "activateLayoutManually: " + ERR_BUSY + " (held "
                    + sActivationGate.heldMs(nowMs) + "ms) — ignoring tap");
            if (callback != null) {
                new Handler(Looper.getMainLooper())
                        .post(() -> callback.onActivationResult(false, ERR_BUSY));
            }
            return;
        }
        if (activation.getReclaimed()) {
            AppLogger.w(TAG, "activateLayoutManually: reclaimed expired activation guard");
        }
        final long activationToken = activation.getToken();
        FissionOrchestrator orch = sAutoStartOrchestrator;
        // A shut-down executor would make activatePresetAsync throw RejectedExecutionException
        // on the UI thread; replace such an orchestrator instead of reusing it.
        final boolean fresh = (orch == null || !orch.isUsable());
        if (fresh) {
            orch = new FissionOrchestrator(appCtx,
                    headlessProjectionState(), headlessCallbacks("activate-layout"));
            sAutoStartOrchestrator = orch;
        }
        // This IS the layout start, so the launch-time auto-start has nothing left to do:
        // without the latch, returning to MainActivity would fire maybeAutoStartOnAppLaunch,
        // orphan this orchestrator and rebuild every slot. Restored on failure so a manual
        // attempt that fails does not disable the automatic one.
        final boolean previouslyFired = sAutoStartFired;
        sAutoStartFired = true;
        orch.activatePresetAsync(preset, fresh, (ok, error) -> {
            boolean ownedCompletion = sActivationGate.release(activationToken);
            // Under the SAME monitor markAutoStartFailed() uses. This is a read-modify-write on
            // a static that the fission-exec thread also writes, and this lambda runs on the
            // main thread: unsynchronised, a markAutoStartFailed() landing between the read and
            // the write is clobbered, which pins sAutoStartFired true and permanently disables
            // the auto-start this line exists to preserve.
            //
            // Only a hard failure re-arms, and only if nothing else already re-armed it. A
            // PARTIAL activation must NOT re-arm: it still owns the cluster, and the next
            // onResume would tear it down and rebuild it.
            synchronized (FissionOrchestrator.class) {
                if (ownedCompletion && error != null && sAutoStartFired) {
                    sAutoStartFired = previouslyFired;
                }
            }
            if (callback != null) callback.onActivationResult(ok, error);
        });
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
        ActivationAttemptGate.Acquisition activation = sActivationGate.tryAcquire(
            android.os.SystemClock.elapsedRealtime());
        if (activation == null) {
            AppLogger.w(TAG, "launchFavoriteLayoutApps skipped: activation already in flight");
            return;
        }
        AppLogger.i(TAG, "manual launch layout apps: « " + fav.name + " »");

        FissionOrchestrator orch = new FissionOrchestrator(appCtx,
                headlessProjectionState(), headlessCallbacks("launch-layout"));
        orch.mAutoStartAttempt = true;
        orch.mActivationGuardToken = activation.getToken();
        replaceHeadlessAfterStop(orch, fav);
    }

    /** Publishes and starts {@code next} only after the current slot owner has fully stopped. */
    private static void replaceHeadlessAfterStop(FissionOrchestrator next, LayoutPreset layout) {
        final FissionOrchestrator previous = sAutoStartOrchestrator;
        if (previous == null) {
            sAutoStartOrchestrator = next;
            next.initAsync(layout, true, false);
            return;
        }
        previous.stopAll(() -> {
            previous.shutdown();
            if (sAutoStartOrchestrator != previous) {
                next.shutdown();
                sActivationGate.release(next.mActivationGuardToken);
                AppLogger.i(TAG, "headless replacement cancelled while prior teardown completed");
                return;
            }
            sAutoStartOrchestrator = next;
            next.initAsync(layout, true, false);
        });
    }

    /**
     * Stops the headless orchestrator and invokes {@code onComplete} on the main thread only after
     * every Layout package has completed move → verified force-stop → optional slot release.
     */
    public static void stopAutoOrchestrator(Runnable onComplete) {
        stopAutoOrchestrator(false, null, onComplete);
    }

    /** Stops all tracked slots, then globally purges daemon slots before activation can resume. */
    public static void stopAutoOrchestratorAndPurge(Context context, Runnable onComplete) {
        stopAutoOrchestrator(true, context.getApplicationContext(), onComplete);
    }

    private static void stopAutoOrchestrator(boolean purgeDaemonSlots, Context context,
                                             Runnable onComplete) {
        FissionOrchestrator o = sAutoStartOrchestrator;
        sAutoStartOrchestrator = null;
        final long teardownToken = sActivationGate.forceAcquire(
                android.os.SystemClock.elapsedRealtime()).getToken();
        Runnable complete = () -> {
            sActivationGate.release(teardownToken);
            notifyLayoutChanged();
            if (onComplete != null) onComplete.run();
        };
        if (o != null) {
            AppLogger.i(TAG, "stopping headless auto-start orchestrator");
            if (purgeDaemonSlots) o.stopAllAndPurge(complete);
            else o.stopAll(complete);
            // stopAll() submitted its teardown to mExec but never shut it down; this
            // throwaway orchestrator is dropped here (never destroy()'d), so shut the
            // executor down gracefully or its worker thread leaks per headless stop.
            o.shutdown();
        } else if (purgeDaemonSlots) {
            Thread purge = new Thread(() -> {
                IBinder binder = FissionClient.getBinderFromServiceManager();
                if (binder != null) {
                    try {
                        FissionClient.deactivateLayout(binder);
                        FissionReleaseDebt.clearAll();
                    } catch (Exception error) {
                        AppLogger.e(TAG, "global slot purge failed: " + error.getMessage());
                    }
                }
                new Handler(Looper.getMainLooper()).post(complete);
            }, "fission-global-purge");
            purge.setDaemon(true);
            purge.start();
        } else {
            new Handler(Looper.getMainLooper()).post(complete);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called on Activity.onDestroy() — shuts down executor and releases slots if finishing. */
    public void destroy(boolean isFinishing) {
        mDestroyed = true;
        abandonClusterActivation();
        mMainHandler.removeCallbacksAndMessages(null);
        if (isFinishing && !mSlots.isEmpty()) {
            final IBinder binder = mDaemonBinder;
            final List<String> pkgs = new ArrayList<>(mSlots.keySet());
            submitQuietly("destroy teardown", () -> {
                boolean keepVds = com.byd.dashcast.data.prefs.ClusterPrefs
                        .isFissionPrecreateSlots(mAppCtx);
                for (String pkg : pkgs) {
                    // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
                    if (binder != null) FissionClient.moveToDisplay0(binder, pkg);
                    if (!keepVds) {
                        if (binder != null) {
                            try {
                                FissionClient.releaseSlot(binder, pkg);
                                FissionReleaseDebt.settled(pkg);
                            } catch (Throwable error) {
                                FissionReleaseDebt.record(pkg);
                            }
                        } else {
                            FissionReleaseDebt.record(pkg);
                        }
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
        submitQuietly("initAsync", () -> {
            tryGetBinder();
            if (favoriteLayout != null) {
                post(() -> mCallbacks.onSlotsChanged(mSlots.values()));
                if (autoLayout) {
                    post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_autoactivate)));
                    // submitOrFail, not mExec.execute: onDisplayReady runs on the main looper
                    // seconds later, and Restore-BYD / a new auto-start can shut this executor
                    // down in between (pre-existing crash on this path, same shape as the
                    // manual one).
                    ensureClusterProjectionThen(
                            () -> submitOrFail(this::activateFavoriteLayout, null));
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
        ensureClusterProjectionThen(next, null);
    }

    /**
     * Same as {@link #ensureClusterProjectionThen(Runnable)} with an explicit failure hook.
     *
     * @param onFailure run when the cluster never reaches projection mode. {@code null} — the
     *                  auto-start path — keeps the previous behaviour byte for byte; the
     *                  user-triggered path passes one so the failure reaches the UI.
     */
    private void ensureClusterProjectionThen(Runnable next, Runnable onFailure) {
        if (com.byd.dashcast.cluster.display.ClusterManager.isQtInProjectionMode()) {
            next.run();
            return;
        }
        AppLogger.i(TAG, "auto-layout: Qt in native mode — activating cluster projection first");
        post(() -> {
            mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_projection));
            abandonClusterActivation();
            final com.byd.dashcast.cluster.display.ClusterManager manager =
                new com.byd.dashcast.cluster.display.ClusterManager(mAppCtx);
            mClusterActivationManager = manager;
            manager.activateClusterDisplay(
                            new com.byd.dashcast.cluster.display.ClusterManager.DisplayReadyCallback() {
                        @Override public void onDisplayReady(android.view.Display display,
                                                              int displayId) {
                    if (mClusterActivationManager != manager || mDestroyed) return;
                            AppLogger.i(TAG, "auto-layout: cluster projection ready (display="
                                    + displayId + ")");
                            next.run();
                        }
                        @Override public void onDisplayTimeout() {
                            if (mClusterActivationManager != manager || mDestroyed) return;
                            mClusterActivationManager = null;
                            manager.abandon();
                            AppLogger.w(TAG, "auto-layout: cluster activation timed out — aborted");
                            post(() -> mCallbacks.onStatusMessage(null));
                            markAutoStartFailed("cluster activation timeout");
                            if (onFailure != null) onFailure.run();
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

    private void abandonClusterActivation() {
        com.byd.dashcast.cluster.display.ClusterManager manager = mClusterActivationManager;
        mClusterActivationManager = null;
        if (manager != null) manager.abandon();
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
        submitQuietly("startSlot " + pkg, () -> {
            try {
                doStartSlot(pkg, label, rect, surfaceHolder);
            } catch (Exception e) {
                AppLogger.e(TAG, "startSlot error pkg=" + pkg, e);
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
        stopAll(false, onComplete);
    }

    private void stopAllAndPurge(Runnable onComplete) {
        stopAll(true, onComplete);
    }

    private void stopAll(boolean purgeDaemonSlots, Runnable onComplete) {
        abandonClusterActivation();
        post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_stopping)));
        boolean accepted = submitQuietly("stopAll", () -> {
            // Free zones hold no app and are not in mSlots, so the teardown plan below never
            // sees them — release them first or a stop leaves an orphaned overlay on the
            // cluster. No-op unless a manual activation created some.
            releaseFreeZones();
            final List<String> packages = new ArrayList<>(mSlots.keySet());
            if (!purgeDaemonSlots && !mProjecting && packages.isEmpty()) {
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
                java.util.Set<String> unreleased = FissionTeardownPlan.run(
                    packages, keepVds, new FissionTeardownPlan.Operations() {
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
                    FissionReleaseDebt.settled(pkg);
                }

                @Override public void onStepError(String pkg, String step, Throwable error) {
                    AppLogger.e(TAG, "Layout teardown " + step + " failed for " + pkg
                            + ": " + error.getMessage());
                }
            });
            FissionReleaseDebt.recordAll(unreleased);
            mSlots.clear();
            mSelectedMirrorPackage = null;
            if (purgeDaemonSlots && binder != null) {
                try {
                    FissionClient.deactivateLayout(binder);
                    FissionReleaseDebt.clearAll();
                } catch (Exception error) {
                    AppLogger.e(TAG, "global slot purge failed: " + error.getMessage());
                }
            }
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
        if (!accepted && onComplete != null) {
            // The executor was already shut down, so nothing above will ever run — but the caller
            // is waiting on this continuation (stopAutoOrchestrator hands it purgeDaemonSlotsAsync).
            // Run it anyway, on the same looper the accepted path uses, so no caller hangs.
            mMainHandler.post(onComplete);
        }
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
        submitQuietly("releaseSlotAsync " + pkg, () -> {
            // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
            if (mDaemonBinder != null) FissionClient.moveToDisplay0(mDaemonBinder, pkg);
            if (mDaemonBinder != null) {
                try {
                    FissionClient.releaseSlot(mDaemonBinder, pkg);
                    FissionReleaseDebt.settled(pkg);
                } catch (Exception e) {
                    FissionReleaseDebt.record(pkg);
                    AppLogger.e(TAG, "releaseSlot error", e);
                }
            } else {
                FissionReleaseDebt.record(pkg);
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
        final Rect requestedRect = new Rect(rect);
        submitQuietly("resizeSlotAsync " + pkg, () -> {
            boolean resized = false;
            try {
                if (mDaemonBinder != null) {
                    resized = FissionClient.resizeSlot(mDaemonBinder, pkg,
                            requestedRect.left, requestedRect.top,
                            requestedRect.width(), requestedRect.height());
                }
            } catch (Exception e) {
                AppLogger.e(TAG, "resizeSlot error", e);
            }
            SlotState slot = mSlots.get(pkg);
            if (!applyAcceptedResize(slot, requestedRect, resized)) {
                AppLogger.w(TAG, "resizeSlot rejected pkg=" + pkg);
            }
            post(() -> mCallbacks.onSlotsChanged(mSlots.values()));
        });
    }

    static boolean applyAcceptedResize(SlotState slot, Rect requestedRect, boolean accepted) {
        if (!accepted || slot == null || requestedRect == null) return false;
        slot.rect = new Rect(requestedRect);
        return true;
    }

    public void switchToLayoutAsync(LayoutPreset newLayout) {
        submitQuietly("switchToLayoutAsync", () -> {
            try {
                switchActiveLayout(newLayout, null);
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
            retryReleaseDebt(b);
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
        if (b != null && sDaemonFreshnessChecked) {
            mDaemonBinder = b;
            if (!retryReleaseDebt(b)) return false;
            final IBinder fb0 = b;
            post(() -> mCallbacks.onDaemonBinderAcquired(fb0));
            return true;
        }
        // A LIVE binder is not proof of a CURRENT daemon. The SurfaceDaemon is a separate
        // uid-2000 process that survives an APK reinstall, and its ServiceManager registration
        // survives with it — so after an app update the previous build's daemon answers every
        // transaction while looking perfectly healthy. That is how a capture comes back full of
        // plausible, useless log lines, and how a new per-package slot key meets an old
        // handleDeactivateLayout that still filters on the "layout_" prefix and releases nothing.
        //
        // The build comparison lives in SurfaceDaemonReusePolicy.shouldReuse, which is only
        // reached from AdbLocalClient.startMirrorDaemon — i.e. on the path this method used to
        // skip entirely whenever a binder existed. So fall through to it ONCE per process: it
        // reuses the daemon when the marker build matches (the binder survives, the poll below
        // finds it on the first 500 ms tick) and kills + respawns it when it does not.
        //
        // Fail-safe: if ADB-local is unreachable, startMirrorDaemon cannot kill anything, the
        // existing daemon stays registered and the poll finds it — we lose one tick, never the
        // daemon. The flag is set BEFORE the call so a failure cannot make this repeat forever.
        if (b != null) {
            AppLogger.i(TAG, "daemon binder present but not yet build-checked this process — "
                    + "validating against build " + com.byd.dashcast.BuildConfig.VERSION_CODE);
        }
        sDaemonFreshnessChecked = true;
        post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_daemon)));
        AdbLocalClient.startMirrorDaemon(mAppCtx);
        for (int i = 0; i < 16; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return false;
            }
            b = FissionClient.getBinderFromServiceManager();
            if (b != null) {
                mDaemonBinder = b;
                if (!retryReleaseDebt(b)) return false;
                final IBinder fb = b;
                post(() -> mCallbacks.onDaemonBinderAcquired(fb));
                AppLogger.d(TAG, "Daemon binder acquired after " + ((i + 1) * 500) + "ms");
                return true;
            }
        }
        AppLogger.e(TAG, "Daemon binder NOT found after 8s");
        return false;
    }

    private boolean retryReleaseDebt(IBinder binder) {
        java.util.Set<String> remaining = FissionReleaseDebt.retry(
                key -> FissionClient.releaseSlot(binder, key));
        if (!remaining.isEmpty()) {
            AppLogger.w(TAG, "slot release debt still pending: " + remaining);
            return false;
        }
        return true;
    }

    private void doStartSlot(String pkg, String label, Rect rect, SurfaceHolder surfaceHolder)
            throws Exception {
        boolean isFirst = mSlots.isEmpty();
        boolean slotAcquired = false;

        try {
            if (!ensureDaemon()) throw new RuntimeException(mAppCtx.getString(R.string.fo_err_daemon));

            // ATTACH_SLOT or REUSE if VD already alive in daemon
            int existingId = -1;
            try { existingId = FissionClient.querySlot(mDaemonBinder, pkg); } catch (Exception ignored) {}
            final int displayId;
            if (existingId > 0) {
                post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_reuse_fmt, label)));
                boolean resized = FissionClient.resizeSlot(mDaemonBinder, pkg,
                        rect.left, rect.top, rect.width(), rect.height());
                if (!resized) {
                    throw new IllegalStateException("existing slot resize rejected for " + pkg);
                }
                displayId = existingId;
                slotAcquired = true;
                AppLogger.i(TAG, "FISSION REUSE_SLOT pkg=" + pkg + " displayId=" + displayId);
            } else {
                post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_create_fmt, label)));
                int newId = FissionClient.attachSlot(mDaemonBinder, pkg,
                        rect.left, rect.top, rect.width(), rect.height());
                if (newId < 0) throw new RuntimeException(mAppCtx.getString(R.string.fo_err_attach_fmt, pkg));
                displayId = newId;
                slotAcquired = true;
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
                // The verdict is load-bearing, the way the teardown path already makes moveToDisplay0
                // load-bearing above. It used to be logged and stepped over: the slot was then
                // registered as live, the layout was reported "activated", and — because activation
                // success is what marks a layout as the auto-start favourite — a layout whose app never
                // started could be saved as the one to bring up on every boot. The driver sees an empty
                // cluster and an interface telling them it worked.
                AppLogger.w(TAG, "FISSION launchAndForce failed/incomplete: " + launchResult);
                throw new IllegalStateException("launch failed for " + pkg + ": " + launchResult);
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
        } catch (Exception error) {
            if (slotAcquired) rollbackStartedSlot(pkg);
            throw error;
        }
    }

    private void doSwitchToLayout(LayoutPreset newLayout, SurfaceHolder surfaceHolder)
            throws Exception {
        java.util.Map<String, LayoutPreset.SlotDef> targetSlots = new java.util.LinkedHashMap<>();
        if (newLayout != null) {
            for (LayoutPreset.SlotDef s : newLayout.slots) {
                if (s.packageName != null && !s.packageName.isEmpty()) {
                    targetSlots.putIfAbsent(s.packageName, s);
                }
            }
        }

        FissionLayoutSwitchPlan.run(
                new ArrayList<>(mSlots.keySet()), targetSlots.keySet(),
                new FissionLayoutSwitchPlan.Operations() {
            @Override public void start(String pkg) throws Exception {
                LayoutPreset.SlotDef slot = targetSlots.get(pkg);
                if (slot == null) throw new IllegalStateException("missing target slot for " + pkg);
                doStartSlot(pkg, getAppLabel(pkg), slot.toRect(), surfaceHolder);
            }

            @Override public void rollback(String pkg) {
                rollbackStartedSlot(pkg);
            }

            @Override public void stop(String pkg) throws Exception {
                // Mirror stop pattern: move to display 0 first so the app relaunches cleanly.
                if (mDaemonBinder != null) FissionClient.moveToDisplay0(mDaemonBinder, pkg);
                if (mDaemonBinder != null) {
                    try {
                        FissionClient.releaseSlot(mDaemonBinder, pkg);
                        FissionReleaseDebt.settled(pkg);
                    } catch (Exception error) {
                        FissionReleaseDebt.record(pkg);
                    }
                }
                ShellGateway.execShell(mAppCtx, "am force-stop " + pkg);
                mSlots.remove(pkg);
            }
        });
        mSelectedMirrorPackage = LayoutSlotSelection.resolve(
                mSelectedMirrorPackage, orderedSlotPackages());
        mProjecting = !mSlots.isEmpty();
    }

    /** Worker-thread rollback for slots successfully acquired by the failed switch attempt. */
    private void rollbackStartedSlot(String pkg) {
        final IBinder binder = mDaemonBinder;
        java.util.Set<String> unreleased = FissionTeardownPlan.run(
            java.util.Collections.singletonList(pkg), false,
                new FissionTeardownPlan.Operations() {
            @Override public String moveToDisplay0(String packageName) {
                if (binder == null) throw new IllegalStateException("mirror daemon unavailable");
                return FissionClient.moveToDisplay0(binder, packageName);
            }

            @Override public boolean forceStopAndWait(String packageName) {
                return forceStopAndWaitForResult(packageName);
            }

            @Override public void releaseSlot(String packageName) throws Exception {
                if (binder == null) throw new IllegalStateException("mirror daemon unavailable");
                FissionClient.releaseSlot(binder, packageName);
                FissionReleaseDebt.settled(packageName);
            }

            @Override public void onStepError(String packageName, String step, Throwable error) {
                AppLogger.e(TAG, "Layout activation rollback " + step + " failed for "
                        + packageName + ": " + error.getMessage());
            }
        });
        FissionReleaseDebt.recordAll(unreleased);
        mSlots.remove(pkg);
        mSelectedMirrorPackage = LayoutSlotSelection.resolve(
                mSelectedMirrorPackage, orderedSlotPackages());
        mProjecting = !mSlots.isEmpty();
    }

    private void activateFavoriteLayout() {
        LayoutPreset fav = LayoutPrefs.getAutoStartLayout(mAppCtx);
        if (fav == null) {
            post(() -> mCallbacks.onStatusMessage(null));
            markAutoStartFailed("saved favourite layout disappeared");
            return;
        }
        try {
            switchActiveLayout(fav, null);
        } catch (Exception e) {
            AppLogger.e(TAG, "activateFavoriteLayout failed", e);
            post(() -> mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_autolayout_err_fmt, e.getMessage())));
            markAutoStartFailed("layout activation failed: " + e.getMessage());
        } finally {
            // The success funnel of the auto-start path: the ClusterManager sequence is done, so
            // a manual Activate is safe again. markAutoStartFailed covers the failure funnel; the
            // guard's own expiry covers anything that reaches neither.
            if (sActivationGate.release(mActivationGuardToken)) {
                mAutoStartAttempt = false;
            }
        }
    }

    /** True while this orchestrator can still accept work on its executor. */
    private boolean isUsable() {
        return !mDestroyed && !mExec.isShutdown();
    }

    /**
     * Submits to the executor from a thread that is <b>not</b> the executor — currently the
     * main looper, via {@code ClusterManager.DisplayReadyCallback}.
     *
     * <p>{@code mExec} is a single-thread executor with the default AbortPolicy, so
     * {@code execute()} after {@code shutdown()} throws
     * {@link java.util.concurrent.RejectedExecutionException}. {@code onDisplayReady} is
     * invoked directly on the main looper with no try/catch, so that throw is a FATAL
     * EXCEPTION on the main thread. It is reachable: Activate preset B, then Deactivate (or
     * Delete) preset A during the multi-second cluster sequence — {@code stopAutoOrchestrator}
     * shuts this executor down, and the display then arrives.
     */
    /**
     * Submits to {@code mExec} tolerating the shutdown race described on {@link #submitOrFail}.
     *
     * <p>Same hazard, different callers: these submissions have no {@code ActivationCallback} to
     * report to, so a rejection is logged and swallowed instead of being delivered. Without this,
     * a {@code RejectedExecutionException} raised on the {@code fission-exec} thread itself — that
     * thread has no {@code UncaughtExceptionHandler} — reaches Android's KillApplicationHandler
     * and takes the whole process down (AUD-002).
     *
     * @return {@code true} when the task was accepted by the executor.
     */
    private boolean submitQuietly(String what, Runnable task) {
        try {
            mExec.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AppLogger.w(TAG, what + " skipped: the orchestrator was already stopped");
            return false;
        }
    }

    private void submitOrFail(Runnable task, ActivationCallback callback) {
        try {
            mExec.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AppLogger.w(TAG, "activation abandoned: the orchestrator was stopped while the "
                    + "cluster was activating");
            deliver(callback, false, ERR_ABANDONED);
        }
    }

    /**
     * Instance half of {@link #activateLayoutManually}. Nothing runs on the caller's thread.
     *
     * @param purgeStaleDaemonSlots true when this orchestrator was just created, i.e. nothing
     *                              in this process owns the slots the daemon may still hold
     *                              from an earlier run — they must be dropped or the new layout
     *                              inherits overlays nobody tracks.
     */
    private void activatePresetAsync(LayoutPreset preset, boolean purgeStaleDaemonSlots,
                                     ActivationCallback callback) {
        // submitOrFail even for the FIRST submit: isUsable() was checked on the caller's thread
        // and another thread can shut this executor down before we get here. A raw execute()
        // would then throw on the UI thread AND leave the activation gate latched, disabling
        // Activate for the rest of the process.
        submitOrFail(() -> {
            tryGetBinder();
            post(() -> {
                mCallbacks.onSlotsChanged(mSlots.values());
                mCallbacks.onStatusMessage(mAppCtx.getString(R.string.fo_status_autoactivate));
            });
            ensureClusterProjectionThen(
                    () -> submitOrFail(
                            () -> doActivatePreset(preset, purgeStaleDaemonSlots, callback),
                            callback),
                    () -> deliver(callback, false, ERR_CLUSTER_TIMEOUT));
        }, callback);
    }

    /** Executor-thread only. Cluster projection is already up when this runs. */
    private void doActivatePreset(LayoutPreset preset, boolean purgeStaleDaemonSlots,
                                  ActivationCallback callback) {
        try {
            if (mProjectionState.isProjectionActive()) {
                // Classic projection owns the cluster; stacking Layout overlays on it is the
                // conflict the orchestrator has always refused (ensureDaemon would refuse too,
                // but would report it as a missing daemon).
                post(() -> mCallbacks.onStatusMessage(null));
                deliver(callback, false, ERR_PROJECTION_CONFLICT);
                return;
            }
            if (!ensureDaemon()) {
                post(() -> mCallbacks.onStatusMessage(null));
                deliver(callback, false, ERR_NO_DAEMON);
                return;
            }
            if (purgeStaleDaemonSlots) {
                try {
                    FissionClient.deactivateLayout(mDaemonBinder);
                    FissionReleaseDebt.clearAll();
                } catch (Exception ignored) {}
            }
            // One ATTACH_SLOT per bound app — keyed BY PACKAGE in the daemon, so the slot can
            // afterwards be queried, resized and released. The batch ACTIVATE_LAYOUT this
            // replaces keyed slots "layout_<label>_<i>" and never put the package on the wire,
            // which made every slot it created unaddressable. doSwitchToLayout also launches
            // each app itself, so no separate launch pass can target a slot that never existed.
            switchActiveLayout(preset, null);
            // Commit free-zone replacement only after every bound slot has started. Removing the
            // old overlays first damages the active layout when a later app start rolls back.
            releaseFreeZones();
            attachFreeZones(preset);
            boolean allOk = publishDisplayIds(preset);
            post(() -> {
                mCallbacks.onSlotsChanged(mSlots.values());
                mCallbacks.onStatusMessage(null);
            });
            deliver(callback, allOk, null);
        } catch (Exception e) {
            AppLogger.e(TAG, "manual layout activation failed", e);
            post(() -> mCallbacks.onStatusMessage(null));
            // NEVER deliver a bare getMessage(): it is null for DeadObjectException — the very
            // exception a killed daemon throws from binder.transact — and a null error is the
            // wire value BOTH consumers read as "the activation ran to completion". That would
            // save a failed preset as the favourite, skip the auto-start re-arm, and toast
            // "partially activated" over a cluster with nothing on it. Always carry the class
            // name, which is also what makes this line greppable across the report corpus.
            String reason = e.getClass().getSimpleName();
            if (e.getMessage() != null) reason = reason + ": " + e.getMessage();
            deliver(callback, false, reason);
        }
    }

    /** Commits the logical active layout only if its physical slot switch completes. */
    private void switchActiveLayout(LayoutPreset target, SurfaceHolder surfaceHolder)
            throws Exception {
        LayoutPreset previous = mActiveLayout;
        mActiveLayout = target;
        try {
            doSwitchToLayout(target, surfaceHolder);
        } catch (Exception error) {
            mActiveLayout = previous;
            mSelectedMirrorPackage = LayoutSlotSelection.resolve(
                    mSelectedMirrorPackage, orderedSlotPackages());
            throw error;
        }
    }

    /**
     * Creates the overlay + VD of the zones with no app bound, so a manually activated layout
     * still paints every zone it painted under the batch call.
     *
     * <p>Keyed {@code "zone<i>_<label>"} and NOT by label alone: the daemon keys {@code sSlots}
     * by whatever string it receives and {@code handleAttachSlot} does
     * {@code remove(key) + release()} first, so two zones sharing a label would silently
     * destroy each other's overlay. Duplicate labels need no typing —
     * {@code LayoutPreset.nextSlotLabel()} is {@code "Zone " + (size + 1)}, so drawing 1/2/3,
     * deleting Zone 2 and drawing again yields a second "Zone 3". The index restores the
     * uniqueness the replaced batch path got from its {@code layout_<label>_<i>} keys.
     *
     * <p>Free zones hold no app, so releasing and re-creating them on each activation is
     * invisible.
     */
    private void attachFreeZones(LayoutPreset preset) {
        if (mDaemonBinder == null) return;
        for (int i = 0; i < preset.slots.size(); i++) {
            LayoutPreset.SlotDef s = preset.slots.get(i);
            if (s.packageName != null && !s.packageName.isEmpty()) continue;
            // Clear first: on a retry a stale id from a previous activation would otherwise
            // survive a failed attach and publishDisplayIds would report the layout as fully
            // up while that zone has no overlay at all.
            s.displayId = -1;
            String key = "zone" + i + "_" + s.label;
            try {
                int id = FissionClient.attachSlot(mDaemonBinder, key, s.x, s.y, s.w, s.h);
                if (id > 0) {
                    s.displayId = id;
                    mFreeZoneKeys.add(key);
                    AppLogger.i(TAG, "FISSION FREE_ZONE slot=" + key + " displayId=" + id);
                }
            } catch (Exception e) {
                AppLogger.w(TAG, "free zone attach failed for " + key + ": " + e.getMessage());
            }
        }
    }

    /** Releases the free-zone slots of the previous activation (no app to move or kill). */
    private void releaseFreeZones() {
        if (mFreeZoneKeys.isEmpty()) return;
        for (String key : new ArrayList<>(mFreeZoneKeys)) {
            if (mDaemonBinder != null) {
                try {
                    FissionClient.releaseSlot(mDaemonBinder, key);
                    FissionReleaseDebt.settled(key);
                    mFreeZoneKeys.remove(key);
                } catch (Exception error) {
                    FissionReleaseDebt.record(key);
                    AppLogger.e(TAG, "free-zone release failed for " + key + ": "
                            + error.getMessage());
                }
            } else {
                FissionReleaseDebt.record(key);
            }
        }
    }

    /**
     * Copies the live display ids back into the preset (the Layout Manager renders them) and
     * reports whether every zone came up.
     */
    private boolean publishDisplayIds(LayoutPreset preset) {
        boolean allOk = !preset.slots.isEmpty();
        for (LayoutPreset.SlotDef s : preset.slots) {
            if (s.packageName != null && !s.packageName.isEmpty()) {
                SlotState state = mSlots.get(s.packageName);
                s.displayId = (state != null) ? state.displayId : -1;
            }
            if (s.displayId <= 0) allOk = false;
        }
        return allOk;
    }

    /** Delivers an activation outcome on the main thread (never suppressed — the UI waits). */
    private void deliver(ActivationCallback callback, boolean ok, String error) {
        if (callback == null) return;
        mMainHandler.post(() -> callback.onActivationResult(ok, error));
    }

    private void markAutoStartFailed(String reason) {
        if (!mAutoStartAttempt) return;
        boolean ownedCompletion = sActivationGate.release(mActivationGuardToken);
        if (!ownedCompletion) {
            AppLogger.w(TAG, "stale auto-start failure ignored: " + reason);
            return;
        }
        synchronized (FissionOrchestrator.class) {
            if (sAutoStartOrchestrator == this) sAutoStartOrchestrator = null;
            sAutoStartFired = false;
            // The failure funnel of the auto-start path. Release the activation guard here too:
            // this method is reached from onDisplayTimeout, i.e. BEFORE activateFavoriteLayout
            // ever runs, so its finally would never fire and the guard would sit held until the
            // 60 s expiry stole it — leaving Activate answering "busy" on a car that just failed
            // to project, which is exactly when a user tries it by hand.
        }
        AppLogger.w(TAG, "auto-start re-armed after failure: " + reason);
        // mFreeZoneKeys counts too. Free zones are deliberately kept OUT of mSlots and never set
        // mProjecting, so a layout made only of unbound zones satisfies neither condition below
        // — and this method then drops the orchestrator and shuts its executor down, orphaning
        // every free-zone overlay on the cluster with nothing left that could release it.
        if (!mSlots.isEmpty() || mProjecting || !mFreeZoneKeys.isEmpty()) stopAll();
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
