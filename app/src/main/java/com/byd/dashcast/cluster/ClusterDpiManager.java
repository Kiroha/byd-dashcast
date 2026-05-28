package com.byd.dashcast.cluster;

import android.content.Context;
import android.os.SystemClock;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.beta.ShellGateway;

import java.util.HashMap;
import java.util.Map;

/**
 * ClusterDpiManager — applies / restores per-app DPI overrides on the cluster
 * display via {@code wm density N -d <displayId>}.
 *
 * <p><b>SAFETY — display 0 (head unit) is NEVER touched.</b> Every public
 * entry point bails out unless {@code displayId > 0}. As an additional defence
 * in depth, {@link ShellGateway} itself blocks any {@code wm density ... -d 0}
 * via {@code WM_DISPLAY_ZERO}, so even a programming mistake here cannot
 * reach the head unit.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@link #applyForLaunch(Context, String, int)} — looks up the override
 *       for {@code pkg}, compares with the per-display cache, and only issues
 *       {@code wm density …} when the value actually changes. A short
 *       150&nbsp;ms settle delay is observed after a change so the next
 *       {@code am start} sees the new density.</li>
 *   <li>{@link #restore(Context, int)} — issues {@code wm density reset -d N}
 *       and clears the cache (no-op if cache already says "default").</li>
 * </ul>
 *
 * <p>Failures are caught and logged: a DPI tweak must NEVER prevent the app
 * from launching or projection from stopping.
 *
 * @since v1.2.81-beta
 */
public final class ClusterDpiManager {

    private static final String TAG = "ClusterDpiMgr";

    /** Settle delay after a wm density change before issuing am start. */
    private static final long SETTLE_MS = 150L;

    /** displayId → last applied DPI (0 == system default / reset). */
    private static final Map<Integer, Integer> sCache = new HashMap<>();

    private ClusterDpiManager() {}

    /**
     * Apply the override (if any) for {@code pkg} on {@code displayId} before
     * launching it. Pure no-op when:
     * <ul>
     *   <li>{@code displayId <= 0} (SAFETY: never touch display 0),</li>
     *   <li>the desired density equals the currently cached one for that display.</li>
     * </ul>
     * Sleeps {@value #SETTLE_MS}&nbsp;ms after a real change so the launch
     * inherits the new density.
     */
    public static void applyForLaunch(Context ctx, String pkg, int displayId) {
        if (ctx == null || displayId <= 0) return; // SAFETY: never touch main screen
        try {
            int desired = (pkg == null || pkg.isEmpty())
                    ? 0
                    : ClusterDpiPrefs.getDpi(ctx, pkg);
            Integer current;
            synchronized (sCache) {
                current = sCache.get(displayId);
            }
            int currentVal = (current == null) ? 0 : current;
            if (currentVal == desired) {
                return; // already in the right state — no shell call needed
            }
            String cmd = (desired <= 0)
                    ? ("wm density reset -d " + displayId)
                    : ("wm density " + desired + " -d " + displayId);
            AppLogger.i(TAG, "applyForLaunch pkg=" + pkg + " display=" + displayId
                    + " " + (currentVal == 0 ? "default" : currentVal + "dpi")
                    + " → " + (desired == 0 ? "default" : desired + "dpi"));
            ShellGateway.execShell(ctx, cmd);
            synchronized (sCache) {
                sCache.put(displayId, desired);
            }
            SystemClock.sleep(SETTLE_MS);
        } catch (Throwable t) {
            // Never let a DPI tweak block the launch.
            AppLogger.w(TAG, "applyForLaunch failed: " + t.getMessage());
        }
    }

    /**
     * Restore {@code displayId} to the system default density. Safe to call
     * even when no override was ever applied (becomes a no-op via the cache).
     * Always guarded by {@code displayId > 0}.
     */
    public static void restore(Context ctx, int displayId) {
        if (ctx == null || displayId <= 0) return; // SAFETY: never touch main screen
        try {
            Integer current;
            synchronized (sCache) {
                current = sCache.get(displayId);
            }
            int currentVal = (current == null) ? 0 : current;
            if (currentVal == 0) {
                return; // already at default
            }
            AppLogger.i(TAG, "restore display=" + displayId
                    + " (was " + currentVal + "dpi)");
            ShellGateway.execShell(ctx, "wm density reset -d " + displayId);
            synchronized (sCache) {
                sCache.remove(displayId);
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "restore failed: " + t.getMessage());
        }
    }

    /** @return current cache snapshot (displayId → applied DPI). For diagnostics. */
    public static Map<Integer, Integer> cacheSnapshot() {
        synchronized (sCache) {
            return new HashMap<>(sCache);
        }
    }
}
