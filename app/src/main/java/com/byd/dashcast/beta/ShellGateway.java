package com.byd.dashcast.beta;

import android.content.Context;
import android.os.SystemClock;

import com.byd.dashcast.AdbLocalClient;
import com.byd.dashcast.AppLogger;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShellGateway — drop-in replacement for {@link AdbLocalClient#executeShell(Context, String)}
 * and {@link AdbLocalClient#executeShellWithResult(Context, String, AdbLocalClient.Callback)}
 * that transparently routes through the Beta Engine proxy daemon when the
 * {@code beta_proxy_enabled} flag is on.
 *
 * <p>Contract — see {@link BetaEngineGateway#safeCall}:
 * <ul>
 *   <li>If the proxy flag is OFF → delegates to {@link AdbLocalClient} directly
 *       (bit-for-bit identical to the v1.0.1 behaviour).</li>
 *   <li>If the proxy flag is ON → posts to its own background executor, tries
 *       (in order):
 *       <ol>
 *         <li><b>Phase 4 typed verb</b> when the command matches a known
 *             pattern ({@code wm overscan L,T,R,B -d N} or
 *             {@code wm overscan reset -d N}) — see {@link #tryTypedVerb}.
 *             Typically returns in single-digit ms.</li>
 *         <li>Generic {@link BetaProxyClient#runShell(String)} otherwise (or
 *             on typed-verb failure).</li>
 *         <li>{@link AdbLocalClient#executeShellWithResult} as the last-resort
 *             fallback.</li>
 *       </ol>
 *   </li>
 * </ul>
 *
 * <p>Migration target: any production call site of {@code AdbLocalClient.executeShell*}
 * that runs on the cluster hot path (overscan, pidof polling, app launch helpers).
 * Diagnostic / test / settings call sites must keep using {@link AdbLocalClient}
 * directly because they need to exercise the legacy code path.
 *
 * @since v1.1.9 build 172 — phase 3 (call-site migration).
 * @since v1.1.9 build 174 — phase 4a (typed {@code wm overscan} interception).
 */
public final class ShellGateway {

    private ShellGateway() {}

    private static final String TAG = "ShellGateway";

    /** Dedicated single-threaded executor so order of calls is preserved per-process. */
    private static final Executor sExecutor = Executors.newSingleThreadExecutor(r -> {
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
        // Fast path: proxy disabled → pure legacy, zero overhead.
        if (!BetaConfig.isProxyDaemonEnabled(ctx)) {
            AdbLocalClient.executeShellWithResult(ctx, cmd, cb);
            return;
        }
        sExecutor.execute(() -> {
            final long t0 = SystemClock.elapsedRealtime();
            try {
                if (!BetaProxyClient.isConnected()) {
                    BetaProxyClient.connect(ctx);
                }
                // Phase 4a/4b: try typed verb first. If it matches AND succeeds,
                // we skip the shell entirely. If the parse fails OR the typed
                // call throws, we fall through to runShell (then legacy).
                String typed = tryTypedVerb(cmd, t0);
                if (typed != null) {
                    if (cb != null) cb.onSuccess(typed);
                    return;
                }
                String out = BetaProxyClient.runShell(cmd);
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.d(TAG, "beta runShell ok (" + dt + "ms): " + cmd);
                if (cb != null) cb.onSuccess(out == null ? "" : out.trim());
            } catch (Throwable t) {
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.w(TAG, "beta runShell failed after " + dt + "ms, fallback legacy: "
                        + t.getMessage() + " [cmd=" + cmd + "]");
                // Fallback: pure legacy with the original callback.
                AdbLocalClient.executeShellWithResult(ctx, cmd, cb);
            }
        });
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
     *   <li>{@code wm overscan L,T,R,B -d N} → {@link BetaProxyClient#setOverscan} (payload: {@code ""})</li>
     *   <li>{@code wm overscan reset -d N}   → {@link BetaProxyClient#setOverscan}(N,0,0,0,0) (payload: {@code ""})</li>
     *   <li>{@code pidof <pkg>}              → {@link BetaProxyClient#getPidsByPackage} (payload: space-separated PIDs)</li>
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
                BetaProxyClient.setOverscan(d, l, t, r, b);
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
                BetaProxyClient.setOverscan(d, 0, 0, 0, 0);
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
                String pids = BetaProxyClient.getPidsByPackage(pkg);
                long dt = SystemClock.elapsedRealtime() - t0;
                AppLogger.d(TAG, "beta pidof typed ok (" + dt + "ms): " + pkg
                        + " → \"" + pids + "\"");
                return pids;
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
