package com.byd.dashcast.beta;

import android.content.Context;

import com.byd.dashcast.AppLogger;

/**
 * BetaEngineGateway — single entry point for code paths that may use the Beta
 * Engine. Encapsulates the {@code try beta → catch → fallback to legacy} logic.
 *
 * <p>Currently exposes a generic {@link #safeCall(Context, BetaOp, FallbackOp)}
 * helper. Cluster activation, restore-cluster, app-launch and other privileged
 * paths in {@code MainActivity} / {@code ClusterManager} will progressively
 * migrate to using this gateway.
 *
 * <p>Contract:
 * <ul>
 *   <li>If both toggles are off → always run the legacy lambda.</li>
 *   <li>If at least one toggle is on → run the beta lambda; on
 *       {@code Throwable} (including {@code RemoteException} and
 *       {@code BetaProxyException}), log a warning, surface a one-shot UI hint
 *       (via {@link com.byd.dashcast.AppLogger}) and fall back to legacy.</li>
 * </ul>
 */
public final class BetaEngineGateway {

    private BetaEngineGateway() {}

    private static final String TAG = "BetaEngineGateway";

    public interface BetaOp<T> {
        T run() throws Throwable;
    }

    public interface FallbackOp<T> {
        T run() throws Throwable;
    }

    /**
     * Runs {@code beta} if any beta flag is enabled, falling back to {@code legacy}
     * on any throwable. If no beta flag is enabled, runs {@code legacy} directly.
     *
     * @throws RuntimeException wrapping the legacy failure if BOTH paths fail.
     */
    public static <T> T safeCall(Context ctx, BetaOp<T> beta, FallbackOp<T> legacy) {
        if (!BetaConfig.anyEnabled(ctx)) {
            return runLegacy(legacy);
        }
        try {
            return beta.run();
        } catch (Throwable t) {
            AppLogger.w(TAG, "beta path failed, falling back to legacy: " + t.getMessage());
            return runLegacy(legacy);
        }
    }

    private static <T> T runLegacy(FallbackOp<T> legacy) {
        try {
            return legacy.run();
        } catch (Throwable t) {
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException("legacy path failed", t);
        }
    }
}
