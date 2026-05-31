package com.byd.dashcast.domain.cluster;

import android.content.Context;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.data.prefs.ClusterPrefs;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ClusterSessionManager — owns and transitions the mutable cluster session state.
 *
 * <p><b>Problem solved:</b> Previously the session state was scattered as ~10 private
 * fields in {@code MainActivity} (mCurrentDashboardApp, mCurrentDashboardPkg,
 * mSecondDashboardApp, mCurrentSplitSlot, mSessionClusterPackages, …). They were
 * mutated from service callbacks, button handlers, and BroadcastReceivers, with no
 * single place to audit what state transitions were possible.
 *
 * <p><b>Architecture role:</b> Domain layer orchestrator. It:
 * <ul>
 *   <li>Keeps the current {@link ClusterSession} snapshot.</li>
 *   <li>Performs every state transition through a named method (explicit intent).</li>
 *   <li>Persists relevant state to SharedPreferences via {@link ClusterPrefs}.</li>
 *   <li>Notifies a single {@link Listener} on every change (UI observes).</li>
 * </ul>
 *
 * <p><b>Threading:</b> All methods must be called from the <em>main thread</em>.
 * The immutable {@link ClusterSession} snapshot returned by {@link #getSession()} is
 * safe to read from any thread.
 */
public final class ClusterSessionManager {

    private static final String TAG = "ClusterSessionMgr";

    // ── Listener ─────────────────────────────────────────────────────────────

    /**
     * Notified synchronously (on the main thread) after every state transition.
     * The UI should re-render from the new {@link ClusterSession} snapshot rather than
     * maintaining its own parallel state copy.
     */
    public interface Listener {
        void onSessionChanged(ClusterSession session);
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private ClusterSession mSession = ClusterSession.IDLE;

    /**
     * All packages launched on the cluster during the current or previous session.
     * Persisted so {@code BootReceiver} / {@code MainActivity} can move them back to
     * display 0 after a projection stop or a process restart.
     */
    private final Set<String> mSessionClusterPkgs = new LinkedHashSet<>();

    private Listener mListener;

    // ── Public API ───────────────────────────────────────────────────────────

    /** Returns the current session snapshot. Safe to call from any thread. */
    public ClusterSession getSession() {
        return mSession;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    // ── Display lifecycle ────────────────────────────────────────────────────

    /**
     * Called when the cluster display becomes available (DisplayManager callback).
     * Records the display ID but does not affect the app-level state (the previous
     * session may still be valid if the display bounced).
     */
    public void onDisplayConnected(int displayId) {
        mSession = mSession.withDisplayId(displayId);
        AppLogger.i(TAG, "Display connected: id=" + displayId + " → " + mSession);
        notifyListener();
    }

    /**
     * Called when the cluster display disappears. Clears the full session so the
     * UI can update the status bar and disable cluster action buttons.
     */
    public void onDisplayDisconnected() {
        AppLogger.i(TAG, "Display disconnected — clearing session (was " + mSession + ")");
        mSession = ClusterSession.IDLE;
        notifyListener();
    }

    // ── Primary app ──────────────────────────────────────────────────────────

    /**
     * Records that {@code pkg} was successfully launched on the cluster display.
     * Persists both the active state and the "last used" hint for reconnect scenarios.
     */
    public void onAppLaunchedOnCluster(Context ctx, String pkg, String name) {
        mSession = mSession.withPrimaryApp(name, pkg);
        mSessionClusterPkgs.add(pkg);
        ClusterPrefs.setClusterPkg(ctx, pkg);
        ClusterPrefs.setClusterName(ctx, name);
        ClusterPrefs.setLastCluster(ctx, pkg, name);
        persistSessionPkgs(ctx);
        AppLogger.i(TAG, "App launched on cluster: " + pkg + " → " + mSession);
        notifyListener();
    }

    /**
     * Clears the primary app slot (e.g., the user pressed "Restore BYD" or the app
     * crashed). Does not disconnect the cluster display.
     */
    public void onPrimaryAppCleared(Context ctx) {
        AppLogger.i(TAG, "Primary cluster app cleared (was " + mSession.clusterPkg + ")");
        mSession = mSession.clearPrimaryApp();
        ClusterPrefs.setClusterPkg(ctx, null);
        ClusterPrefs.setClusterName(ctx, null);
        notifyListener();
    }

    // ── Secondary app (split screen) ─────────────────────────────────────────

    /**
     * Records that a second app was launched in the given split-screen slot.
     *
     * @param slot 1 = left, 2 = right (matches BYD split-stack convention)
     */
    public void onSecondaryAppLaunched(Context ctx, String pkg, String name, int slot) {
        mSession = mSession.withSecondaryApp(name, pkg, slot);
        mSessionClusterPkgs.add(pkg);
        persistSessionPkgs(ctx);
        AppLogger.i(TAG, "Secondary app launched: " + pkg + " slot=" + slot);
        notifyListener();
    }

    /** Clears the secondary slot (back to fullscreen). */
    public void onSecondaryAppCleared() {
        if (mSession.isSplitActive()) {
            mSession = mSession.clearSecondaryApp();
            notifyListener();
        }
    }

    // ── Session package tracking ─────────────────────────────────────────────

    /**
     * Returns a snapshot of all packages that were launched on the cluster during
     * this session. Used by the "stop projection" path to restore apps to display 0.
     */
    public Set<String> getSessionClusterPkgs() {
        return new LinkedHashSet<>(mSessionClusterPkgs);
    }

    /** Adds a package to the session-tracking set without changing the active app. */
    public void trackClusterPackage(Context ctx, String pkg) {
        if (mSessionClusterPkgs.add(pkg)) {
            persistSessionPkgs(ctx);
        }
    }

    /** Clears the session tracking set (called after projection fully stopped). */
    public void clearSessionPkgs(Context ctx) {
        mSessionClusterPkgs.clear();
        ClusterPrefs.clearSessionClusterPkgs(ctx);
        AppLogger.d(TAG, "Session packages cleared");
    }

    // ── Persistence / restore ────────────────────────────────────────────────

    /**
     * Restores session state from SharedPreferences on cold start.
     * Should be called in {@code MainActivity.onCreate()} before the service binds.
     */
    public void restoreFromPrefs(Context ctx) {
        mSessionClusterPkgs.addAll(ClusterPrefs.getSessionClusterPkgs(ctx));
        String pkg  = ClusterPrefs.getClusterPkg(ctx);
        String name = ClusterPrefs.getClusterName(ctx);
        if (pkg != null) {
            // Restore the active-app hint. The display ID is not yet known; it will
            // be filled in by onDisplayConnected() once ClusterService connects.
            mSession = new ClusterSession(name, pkg, null, null, 0, -1);
        }
        AppLogger.d(TAG, "Restored from prefs: pkg=" + pkg
                + " sessionPkgs=" + mSessionClusterPkgs.size());
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void persistSessionPkgs(Context ctx) {
        ClusterPrefs.setSessionClusterPkgs(ctx, new LinkedHashSet<>(mSessionClusterPkgs));
    }

    private void notifyListener() {
        if (mListener != null) {
            mListener.onSessionChanged(mSession);
        }
    }
}
