package com.byd.dashcast.domain.cluster;

/**
 * ClusterSession — immutable snapshot of the cluster projection state.
 *
 * <p><b>Problem solved:</b> {@code MainActivity} tracked session state via ~10
 * scattered mutable fields:
 * <pre>
 *   private String mCurrentDashboardApp;
 *   private String mCurrentDashboardPkg;
 *   private String mSecondDashboardApp;
 *   private String mSecondDashboardPkg;
 *   private int    mCurrentSplitSlot;
 *   private int    mDisplayId;          // (in ClusterService)
 *   ...
 * </pre>
 * These were mutated from multiple code paths (service callbacks, button handlers,
 * BroadcastReceiver), making it easy to leave them in an inconsistent state.
 *
 * <p><b>Architecture role:</b> Domain model. An immutable value object means:
 * <ul>
 *   <li>State transitions are always <em>explicit</em> — you call a {@code withX()}
 *       builder method that returns a <em>new</em> snapshot.</li>
 *   <li>Thread-safety by construction — no setter, no monitor needed.</li>
 *   <li>Testable without Android context — pure Java, no mocking required.</li>
 * </ul>
 *
 * <p>The session is managed by {@link ClusterSessionManager}, which owns the current
 * snapshot and fires change notifications. The UI only reads from {@code ClusterSession}
 * it receives in the callback.
 */
public final class ClusterSession {

    /**
     * Canonical idle state: nothing projected, display ID unknown.
     * Use as the initial value in {@link ClusterSessionManager}.
     */
    public static final ClusterSession IDLE = new ClusterSession(
            /*clusterAppName*/ null,
            /*clusterPkg*/     null,
            /*secondAppName*/  null,
            /*secondPkg*/      null,
            /*splitSlot*/      0,
            /*displayId*/      -1
    );

    /** Human-readable name of the primary app currently on the cluster display. */
    public final String clusterAppName;
    /** Package name of the primary app on the cluster display. {@code null} = cluster idle. */
    public final String clusterPkg;

    /** Human-readable name of the secondary app in split-screen mode. {@code null} = no split. */
    public final String secondAppName;
    /** Package name of the secondary app (split screen, slot 2). {@code null} = fullscreen. */
    public final String secondPkg;

    /**
     * Current layout on the cluster:
     * <ul>
     *   <li>{@code 0} — fullscreen (single app, or idle)</li>
     *   <li>{@code 1} — left half</li>
     *   <li>{@code 2} — right half</li>
     * </ul>
     */
    public final int splitSlot;

    /**
     * Android display ID of the cluster, as returned by {@code DisplayManager}.
     * {@code -1} means the cluster display has not yet been detected.
     */
    public final int displayId;

    public ClusterSession(
            String clusterAppName,
            String clusterPkg,
            String secondAppName,
            String secondPkg,
            int splitSlot,
            int displayId) {
        this.clusterAppName = clusterAppName;
        this.clusterPkg     = clusterPkg;
        this.secondAppName  = secondAppName;
        this.secondPkg      = secondPkg;
        this.splitSlot      = splitSlot;
        this.displayId      = displayId;
    }

    // ── Computed properties ──────────────────────────────────────────────────

    /** {@code true} when an app is actively projected on the cluster. */
    public boolean isActive() {
        return clusterPkg != null;
    }

    /** {@code true} when two apps share the cluster in split-screen mode. */
    public boolean isSplitActive() {
        return isActive() && secondPkg != null;
    }

    /** {@code true} when the cluster display has been detected by DisplayManager. */
    public boolean hasDisplay() {
        return displayId >= 0;
    }

    // ── Builder / withers ────────────────────────────────────────────────────
    // Each returns a NEW instance, keeping the existing session immutable.

    /** Returns a new session with the primary app updated. */
    public ClusterSession withPrimaryApp(String name, String pkg) {
        return new ClusterSession(name, pkg, secondAppName, secondPkg, splitSlot, displayId);
    }

    /** Returns a new session with the primary (and secondary) slot cleared — cluster idle. */
    public ClusterSession clearPrimaryApp() {
        return new ClusterSession(null, null, null, null, 0, displayId);
    }

    /** Returns a new session with the secondary split-screen app updated. */
    public ClusterSession withSecondaryApp(String name, String pkg, int slot) {
        return new ClusterSession(clusterAppName, clusterPkg, name, pkg, slot, displayId);
    }

    /** Returns a new session with the secondary slot cleared (back to fullscreen). */
    public ClusterSession clearSecondaryApp() {
        return new ClusterSession(clusterAppName, clusterPkg, null, null, 0, displayId);
    }

    /** Returns a new session with the given display ID. */
    public ClusterSession withDisplayId(int id) {
        return new ClusterSession(clusterAppName, clusterPkg, secondAppName, secondPkg, splitSlot, id);
    }

    // ── Object contract ──────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClusterSession)) return false;
        ClusterSession that = (ClusterSession) o;
        return splitSlot == that.splitSlot
                && displayId == that.displayId
                && eq(clusterPkg, that.clusterPkg)
                && eq(clusterAppName, that.clusterAppName)
                && eq(secondPkg, that.secondPkg)
                && eq(secondAppName, that.secondAppName);
    }

    @Override
    public int hashCode() {
        int result = clusterPkg != null ? clusterPkg.hashCode() : 0;
        result = 31 * result + (secondPkg != null ? secondPkg.hashCode() : 0);
        result = 31 * result + splitSlot;
        result = 31 * result + displayId;
        return result;
    }

    @Override
    public String toString() {
        return "ClusterSession{"
                + "cluster=" + clusterPkg
                + " second=" + secondPkg
                + " slot=" + splitSlot
                + " displayId=" + displayId
                + " active=" + isActive()
                + "}";
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
