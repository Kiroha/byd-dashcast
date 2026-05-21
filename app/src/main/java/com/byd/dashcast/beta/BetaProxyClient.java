package com.byd.dashcast.beta;

import android.content.Context;

import com.byd.dashcast.AppLogger;

/**
 * BetaProxyClient — Component A of the Beta Engine (CLIENT-SIDE STUB).
 *
 * <p>Phase-1 stub. The full implementation will:
 * <ol>
 *   <li>Spawn a long-lived {@code app_process} via {@code AdbLocalClient}
 *       executing {@code com.byd.dashcast.beta.proxy.EntryPoint} with the
 *       UID of the {@code shell} user (2000), so the daemon inherits ADB-level
 *       privileges (including {@code WRITE_SECURE_SETTINGS} and most
 *       {@code signature|privileged} permissions granted to UID 2000 on BYD
 *       Atto3 / Seal images).</li>
 *   <li>Expose an {@code ICarProxy} {@link android.os.IBinder} (defined in
 *       {@code ICarProxy.aidl}) and publish it via a {@code LocalServerSocket}
 *       in the abstract namespace (e.g. {@code @openbyd_proxy}) — the app
 *       connects, reads a single fd via {@code Os.recvmsg} ancillary data,
 *       wraps it into a {@code Binder} via reflection on
 *       {@code Parcel.readStrongBinder} after writing the fd.</li>
 *   <li>Surface {@link #runShell(String)}, {@link #ping()},
 *       {@link #getCallerUid()} and the privileged car-API helpers needed by
 *       cluster activation / restoration paths.</li>
 * </ol>
 *
 * <p>For Phase-1 / 1.1.0-beta this class is intentionally a NO-OP that always
 * reports "not connected". The Diag tests A1–A6 read this state and surface it
 * to the user. The gateway always falls back to {@link com.byd.dashcast.AdbLocalClient}
 * when {@link #isConnected()} is false, so enabling the toggle while the daemon
 * is unimplemented is harmless — the user simply runs the legacy path.
 */
public final class BetaProxyClient {

    private BetaProxyClient() {}

    private static final String TAG = "BetaProxyClient";

    /** Phase-1: not implemented. Always {@code false}. */
    public static boolean isConnected() {
        return false;
    }

    /**
     * Try to establish the daemon connection. Currently a no-op that logs and
     * returns immediately. Phase-2 will spawn the daemon and connect to its
     * socket.
     *
     * @return immediate connection state ({@code false} for Phase-1).
     */
    public static boolean connect(Context ctx) {
        AppLogger.w(TAG, "connect() called but daemon is not implemented yet (Phase-2)");
        return false;
    }

    /**
     * Drop the current connection. No-op in Phase-1.
     */
    public static void disconnect() {
        // no-op
    }

    /**
     * Stub: always returns {@code -1} (not connected). Phase-2 will return the
     * round-trip time in milliseconds.
     */
    public static long ping() {
        return -1L;
    }

    /**
     * Stub: always returns {@code -1}. Phase-2 will return the UID of the
     * daemon process (expected: {@code 2000} for the {@code shell} user).
     */
    public static int getCallerUid() {
        return -1;
    }

    /**
     * Stub: always throws. Phase-2 will forward the shell command to the
     * daemon and return its stdout.
     */
    public static String runShell(String cmd) throws BetaProxyException {
        throw new BetaProxyException("proxy daemon not implemented yet (Phase-2)");
    }

    /** Thrown by Phase-2 calls when the proxy daemon is unreachable. */
    public static class BetaProxyException extends Exception {
        public BetaProxyException(String msg) { super(msg); }
        public BetaProxyException(String msg, Throwable cause) { super(msg, cause); }
    }
}
