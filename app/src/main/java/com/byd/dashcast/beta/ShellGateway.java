package com.byd.dashcast.beta;

import android.content.Context;
import android.os.SystemClock;

import com.byd.dashcast.AdbLocalClient;
import com.byd.dashcast.AppLogger;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
 *       {@link BetaProxyClient#runShell(String)} first; on any throwable falls
 *       back to {@link AdbLocalClient#executeShellWithResult}.</li>
 * </ul>
 *
 * <p>Migration target: any production call site of {@code AdbLocalClient.executeShell*}
 * that runs on the cluster hot path (overscan, pidof polling, app launch helpers).
 * Diagnostic / test / settings call sites must keep using {@link AdbLocalClient}
 * directly because they need to exercise the legacy code path.
 *
 * @since v1.1.9 build 172 — phase 3 (call-site migration).
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
}
