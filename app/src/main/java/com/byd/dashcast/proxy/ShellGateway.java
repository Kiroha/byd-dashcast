package com.byd.dashcast.proxy;

import android.content.Context;
import android.os.SystemClock;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.util.concurrent.BoundedSerialExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShellGateway — drop-in replacement for {@link AdbLocalClient#executeShell(Context, String)}
 * and {@link AdbLocalClient#executeShellWithResult(Context, String, AdbLocalClient.Callback)}
 * that routes a shell command through the uid-2000 proxy daemon when one is usable.
 *
 * <p>This header described a different program until the audit's second-opinion pass. It named a
 * {@code beta_proxy_enabled} flag that exists nowhere in this codebase, pointed at a
 * {@code DaemonEngineGateway#safeCall} that was deleted in 4a36a748 ("promote proxy daemon to main
 * path — de-beta"), and promised a flag-off branch "bit-for-bit identical to the v1.0.1
 * behaviour". A reader therefore believed in a kill switch, on the layer every shell command in
 * this app passes through. Nothing in the code answers to that name.
 *
 * <p>Routing is decided by {@link ShellGatewayRoutingPolicy#select}, per call, twice — once before
 * queueing and again on the worker, because the daemon can connect or die in between:
 * <ul>
 *   <li><b>PROXY</b> — the legacy path is not forced AND {@link ProxyClient#isConnected()}. Tries
 *       a {@linkplain #tryTypedVerb typed Phase-4 verb} first when the command matches a known
 *       pattern ({@code wm overscan L,T,R,B -d N} or {@code wm overscan reset -d N}), which
 *       returns in single-digit ms; otherwise {@link ProxyClient#runShell(String)}; and
 *       {@link AdbLocalClient} as the last-resort fallback inside the same worker.</li>
 *   <li><b>LEGACY</b> — {@link AdbLocalClient} directly. Selected when
 *       {@link DaemonConfig#isLegacyPathEnabled} forces it, or simply when no daemon is
 *       connected. This is the branch the old text called "flag OFF"; the flag it named is not
 *       what chooses it.</li>
 *   <li><b>FAIL_FAST</b> — no daemon AND the ADB transport is already classified unreachable. The
 *       callback gets {@link AdbLocalClient#adbTransportDiagnosis()} instead of queueing behind a
 *       wedged worker. The old text did not mention this state at all.</li>
 * </ul>
 *
 * <p>Two guards run BEFORE any routing and refuse the command outright — neither was documented
 * here either: {@link AdbLocalClient#blockDiLink2Resize} (a {@code wm} resize that would shrink
 * the head unit's own UI on DiLink 2), and a {@code wm} command explicitly targeting display 0.
 *
 * <p>Migration target: any production call site of {@code AdbLocalClient.executeShell*} that runs
 * on the cluster hot path (overscan, pidof polling, app launch helpers). Diagnostic / test /
 * settings call sites keep using {@link AdbLocalClient} directly, because they need to exercise
 * the legacy code path.
 *
 * @since v1.1.9 build 172 — phase 3 (call-site migration).
 * @since v1.1.9 build 174 — phase 4a (typed {@code wm overscan} interception).
 */
public final class ShellGateway {

    private ShellGateway() {}

    private static final String TAG = "ShellGateway";

    /** Dedicated bounded serial executor: preserves order without retaining unlimited stale work. */
    private static final int SHELL_QUEUE_CAPACITY = 64;
    private static final BoundedSerialExecutor sExecutor = new BoundedSerialExecutor(
            SHELL_QUEUE_CAPACITY, r -> {
        Thread t = new Thread(r, "shell-gateway");
        t.setDaemon(true);
        return t;
    });

    /**
     * Matches {@code wm overscan L,T,R,B -d N} with optional spaces. Capture
     * groups: 1=L, 2=T, 3=R, 4=B, 5=displayId. Anchored on both ends so a
     * compound command (e.g. {@code wm overscan … && wm size …}) falls through
     * to the generic shell path.
     */
    private static final Pattern WM_OVERSCAN = Pattern.compile(
            "^\\s*wm\\s+overscan\\s+(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s+-d\\s+(\\d+)\\s*$");

    /** Matches {@code wm overscan reset -d N}. Capture group 1 = displayId. */
    private static final Pattern WM_OVERSCAN_RESET = Pattern.compile(
            "^\\s*wm\\s+overscan\\s+reset\\s+-d\\s+(\\d+)\\s*$");

    /**
     * HARD GUARD — matches any {@code wm overscan|size|density ... -d 0} (or
     * {@code -d 0} anywhere in a {@code wm} command). Display 0 is the head
     * unit and must NEVER be resized by this app (would shrink the main UI,
     * field-reported on DL2 22/05/2026, mirror risk on DL3/DL5 if a caller
     * accidentally passes the wrong displayId). Blocked at the gateway so
     * neither the proxy path nor the legacy path can reach the system.
     */
    private static final Pattern WM_DISPLAY_ZERO = Pattern.compile(
            "^\\s*wm\\s+(?:overscan|size|density)\\b.*\\s-d\\s+0\\b.*$");

    /**
     * Matches {@code pidof <packageName>} — capture group 1 = package. Anchored
     * so multi-word invocations (e.g. {@code pidof a b c}) fall through to shell.
     * The package character class matches Android package names and binary names
     * (letters, digits, dot, underscore, colon, dash).
     */
    private static final Pattern PIDOF = Pattern.compile(
            "^\\s*pidof\\s+([A-Za-z0-9._:\\-]+)\\s*$");

    /** Fire-and-forget shell. Mirrors {@link AdbLocalClient#executeShell}. */
    public static void execShell(final Context ctx, final String cmd) {
        execShellWithResult(ctx, cmd, null);
    }

    /**
     * Shell with callback. Mirrors {@link AdbLocalClient#executeShellWithResult}.
     * The callback is invoked on a background thread (same as the legacy method).
     */
    public static void execShellWithResult(final Context ctx, final String cmd,
                                           final AdbLocalClient.Callback cb) {
        if (ctx == null || cmd == null) {
            if (cb != null) cb.onError("null ctx/cmd");
            return;
        }
        // DL2 SAFETY GUARD — must be checked BEFORE the proxy path, which bypasses
        // AdbLocalClient.executeShell* and would otherwise let a `wm overscan/size/density`
        // through to the BYD MTK ROM (which silently applies it to display 0 and
        // shrinks the main UI). See AdbLocalClient.blockDiLink2Resize.
        if (AdbLocalClient.blockDiLink2Resize(ctx, cmd)) {
            if (cb != null) cb.onError(
                    "blocked on DiLink 2: no cluster display (would shrink main screen)");
            return;
        }
        // HARD GUARD — refuse any wm verb explicitly targeting display 0 (head
        // unit). Defence in depth on top of the call-site `clusterId > 0` checks.
        if (WM_DISPLAY_ZERO.matcher(cmd).matches()) {
            AppLogger.e(TAG, "BLOCKED wm verb on display 0 (head unit): " + cmd);
            if (cb != null) cb.onError("blocked: wm command targets display 0 (head unit)");
            return;
        }
        // The legacy path still runs on the same bounded worker. Handing it to AdbLocalClient's
        // async pool here would drain this queue into that pool's unbounded work queue.
        final boolean legacyPath = DaemonConfig.isLegacyPathEnabled(ctx);
        ShellGatewayRoutingPolicy.Route initialRoute = ShellGatewayRoutingPolicy.select(
            legacyPath,
            !legacyPath && ProxyClient.isConnected(),
            AdbLocalClient.isAdbTransportUnreachable());
        if (initialRoute == ShellGatewayRoutingPolicy.Route.FAIL_FAST) {
            deliverError(cb, AdbLocalClient.adbTransportDiagnosis());
            return;
        }
        Runnable operation = () -> {
            ShellGatewayRoutingPolicy.Route route = ShellGatewayRoutingPolicy.select(
                    legacyPath,
                    !legacyPath && ProxyClient.isConnected(),
                    AdbLocalClient.isAdbTransportUnreachable());
            if (route != ShellGatewayRoutingPolicy.Route.PROXY) {
                runLegacyOrFailFast(ctx, cmd, cb);
                return;
            }
            // This dedicated single thread has its own legacy fallback (below), so it must
            // never pay the ~23s blocking daemon bootstrap inside callWithRetry when a binder
            // dies mid-transact — that would stall every queued overscan/pidof op. Opt out:
            // the reconnect is kicked async and the verb fails fast into the legacy fallback.
            ProxyClient.setNonBlockingReconnect(true);
            final long t0 = SystemClock.elapsedRealtime();
            try {
                // Phase 4a/4b: try typed verb first. If it matches AND succeeds,
                // we skip the shell entirely. If the parse fails OR the typed
                // call throws, we fall through to runShell (then legacy).
                String typed = tryTypedVerb(cmd, t0);
                if (typed != null) {
                    deliverSuccess(cb, typed);
                    return;
                }
                String out = ProxyClient.runShell(cmd);
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.d(TAG, "beta runShell ok (" + dt + "ms): " + cmd);
                deliverSuccess(cb, out == null ? "" : out.trim());
            } catch (Throwable t) {
                if (t instanceof InterruptedException) Thread.currentThread().interrupt();
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.w(TAG, "beta runShell failed after " + dt + "ms, fallback legacy: "
                        + t.getMessage() + " [cmd=" + cmd + "]");
                runLegacyOrFailFast(ctx, cmd, cb);
            }
        };
        try {
            sExecutor.execute(operation);
        } catch (RejectedExecutionException queueFull) {
            AppLogger.e(TAG, "shell queue full (capacity=" + SHELL_QUEUE_CAPACITY
                    + "), rejecting cmd=" + cmd);
            deliverError(cb, "shell queue full");
        }
    }

    private static void runLegacyOrFailFast(Context ctx, String cmd,
                                            AdbLocalClient.Callback cb) {
        // A healthy Binder does not depend on the local ADB socket after startup. Only stop at
        // the point where a command would actually fall back to that classified-dead transport.
        // ProxyKeeper owns periodic rechecks and clears the classification after recovery.
        if (AdbLocalClient.isAdbTransportUnreachable()) {
            deliverError(cb, AdbLocalClient.adbTransportDiagnosis());
            return;
        }
        runLegacyBlocking(ctx, cmd, cb);
    }

    private static void runLegacyBlocking(Context ctx, String cmd, AdbLocalClient.Callback cb) {
        try {
            deliverSuccess(cb, AdbLocalClient.executeShellWithResultBlocking(ctx, cmd));
        } catch (Throwable t) {
            if (t instanceof InterruptedException) Thread.currentThread().interrupt();
            String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            AppLogger.e(TAG, "legacy shell failed: " + message + " [cmd=" + cmd + "]");
            deliverError(cb, message);
        }
    }

    private static void deliverSuccess(AdbLocalClient.Callback cb, String output) {
        if (cb == null) return;
        try { cb.onSuccess(output); }
        catch (Throwable callbackError) {
            AppLogger.e(TAG, "shell success callback failed", callbackError);
        }
    }

    private static void deliverError(AdbLocalClient.Callback cb, String error) {
        if (cb == null) return;
        try { cb.onError(error); }
        catch (Throwable callbackError) {
            AppLogger.e(TAG, "shell error callback failed", callbackError);
        }
    }

    /**
     * Inspect {@code cmd} and, if it matches a Phase 4 verb pattern, route it
     * through the typed binder call instead of the shell. Returns the stdout
     * payload to hand to the caller's callback on success, or {@code null} when
     * the command didn't match any pattern OR the typed call failed — callers
     * fall through to the generic {@code runShell} path on {@code null}.
     *
     * <p>Currently handles:
     * <ul>
     *   <li>{@code wm overscan L,T,R,B -d N} → {@link ProxyClient#setOverscan} (payload: {@code ""})</li>
     *   <li>{@code wm overscan reset -d N}   → {@link ProxyClient#setOverscan}(N,0,0,0,0) (payload: {@code ""})</li>
     *   <li>{@code pidof <pkg>}              → {@link ProxyClient#getPidsByPackage} (payload: space-separated PIDs)</li>
     * </ul>
     */
    private static String tryTypedVerb(String cmd, long t0) {
        Matcher m = WM_OVERSCAN.matcher(cmd);
        if (m.matches()) {
            try {
                int l = Integer.parseInt(m.group(1));
                int t = Integer.parseInt(m.group(2));
                int r = Integer.parseInt(m.group(3));
                int b = Integer.parseInt(m.group(4));
                int d = Integer.parseInt(m.group(5));
                ProxyClient.setOverscan(d, l, t, r, b);
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.d(TAG, "beta setOverscan typed ok (" + dt + "ms): d=" + d
                        + " " + l + "," + t + "," + r + "," + b);
                return "";
            } catch (Throwable th) {
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.w(TAG, "beta setOverscan typed failed after " + dt
                        + "ms, falling through to runShell: " + th.getMessage());
                return null;
            }
        }
        Matcher mr = WM_OVERSCAN_RESET.matcher(cmd);
        if (mr.matches()) {
            try {
                int d = Integer.parseInt(mr.group(1));
                // `wm overscan reset` clears overscan = setOverscan(d, 0, 0, 0, 0).
                ProxyClient.setOverscan(d, 0, 0, 0, 0);
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.d(TAG, "beta setOverscan(reset) typed ok (" + dt + "ms): d=" + d);
                return "";
            } catch (Throwable th) {
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.w(TAG, "beta setOverscan(reset) typed failed after " + dt
                        + "ms, falling through to runShell: " + th.getMessage());
                return null;
            }
        }
        Matcher mp = PIDOF.matcher(cmd);
        if (mp.matches()) {
            try {
                String pkg = mp.group(1);
                // Success is not logged: pidof is fired by the 5 s display-state
                // poll, and a log line per call kept the AppLogger buffer dirty
                // (see DisplayStatePollCoordinator). Failures below stay logged.
                return ProxyClient.getPidsByPackage(pkg);
            } catch (Throwable th) {
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.w(TAG, "beta pidof typed failed after " + dt
                        + "ms, falling through to runShell: " + th.getMessage());
                return null;
            }
        }
        return null;
    }
}
