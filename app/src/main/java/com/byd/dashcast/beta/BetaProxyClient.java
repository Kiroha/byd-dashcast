package com.byd.dashcast.beta;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import com.byd.dashcast.AdbLocalClient;
import com.byd.dashcast.AppLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BetaProxyClient — Component A client. Talks to the proxy daemon
 * (see {@link com.byd.dashcast.beta.proxy.ProxyDaemonMain}) over a
 * {@link LocalSocket} in the abstract namespace.
 *
 * <p>If no daemon is running, {@link #connect(Context)} attempts to bootstrap
 * one by issuing an {@code app_process} command through
 * {@link AdbLocalClient} (which triggers the standard ADB-pairing flow). The
 * spawned daemon inherits the {@code shell} UID (2000) and outlives the app.
 *
 * <p>Thread-safety: all public methods synchronize on a static lock, and the
 * underlying socket I/O is serialised. Concurrent callers will queue.
 */
public final class BetaProxyClient {

    private static final String TAG = "BetaProxyClient";

    private static final String SOCKET_NAME = "dashcast_proxy";

    /** App package whose APK hosts the daemon main class. Must match the installed package. */
    private static final String DAEMON_PKG = "com.byd.dashcast";

    /** Fully-qualified main class of the daemon. */
    private static final String DAEMON_MAIN = "com.byd.dashcast.beta.proxy.ProxyDaemonMain";

    /** Path of the daemon's stdout/stderr capture on the device (overwritten each bootstrap). */
    private static final String DAEMON_LOG = "/data/local/tmp/dashcast_proxy.log";

    /**
     * Bootstrap script run via local ADB. Mirrors the proven
     * {@code MirrorDaemon} recipe used elsewhere in the app:
     * <ul>
     *   <li>{@code setsid} detaches from the ADB session group (survives SIGHUP);</li>
     *   <li>explicit {@code /system/bin/app_process64} (bare {@code app_process}
     *       symlink is not always present / SELinux-accessible on BYD images);</li>
     *   <li>{@code -Xnoimage-dex2oat} avoids an AOT crash at startup;</li>
     *   <li>{@code /system/bin} as the parent directory (not {@code /});</li>
     *   <li>{@code --nice-name=dashcast_proxy} sets argv[0] before our own
     *       {@code setArgV0} call gets a chance;</li>
     *   <li>stdout/stderr redirected to {@link #DAEMON_LOG} so cold-start
     *       failures are diagnosable from the host.</li>
     * </ul>
     */
    private static final String BOOTSTRAP_CMD =
            "APK=$(pm path " + DAEMON_PKG + " 2>/dev/null | head -n1 | cut -d: -f2-); "
            + "if [ -z \"$APK\" ]; then echo ERR_NO_APK; exit 1; fi; "
            + "LOG=" + DAEMON_LOG + "; "
            // Wipe + instrument: makes future failures self-diagnostic.
            + "{ echo \"[boot] $(date) apk=$APK\"; "
            +   "echo \"[boot] id=$(id)\"; "
            +   "echo \"[boot] getenforce=$(getenforce 2>/dev/null)\"; "
            +   "ls -la \"$APK\" 2>&1; "
            +   "echo \"[boot] exec app_process64...\"; } > \"$LOG\" 2>&1; "
            // Critical: double quotes around `sh -c \"...\"` so the OUTER shell expands
            // $APK before handing it to setsid. With single quotes (the bug in 1.1.3),
            // the inner shell saw CLASSPATH=$APK literally → app_process64 SIGABRT.
            + "setsid sh -c \"CLASSPATH='$APK' exec /system/bin/app_process64"
            +     " -Xnoimage-dex2oat /system/bin"
            +     " --nice-name=dashcast_proxy"
            +     " " + DAEMON_MAIN
            +     " </dev/null >>'$LOG' 2>&1\" & "
            + "echo OK $APK";

    /** Fetched after a connect() failure to surface the daemon's first error line(s) in test messages. */
    private static final String READ_LOG_CMD = "tail -n 20 " + DAEMON_LOG + " 2>/dev/null";

    private static final int  SOCKET_TIMEOUT_MS    = 3000;
    private static final int  BOOTSTRAP_TIMEOUT_MS = 8000;
    private static final long RETRY_DELAY_MS       = 300L;
    private static final int  RETRY_COUNT          = 8;

    private static final Object LOCK = new Object();

    private static LocalSocket    sSocket;
    private static BufferedReader sIn;
    private static PrintWriter    sOut;
    private static int            sDaemonUid = -1;
    private static int            sDaemonPid = -1;
    private static String         sDaemonVer;

    private BetaProxyClient() {}

    /** @return {@code true} if a socket connection to the daemon is currently open. */
    public static boolean isConnected() {
        synchronized (LOCK) {
            return sSocket != null && sSocket.isConnected() && !sSocket.isClosed();
        }
    }

    /**
     * Ensure the daemon is reachable. If a socket is already open, returns immediately.
     * Otherwise: (1) tries to connect to the existing daemon; (2) on failure, spawns one
     * via {@link AdbLocalClient}; (3) retries the connect a few times with backoff.
     *
     * @return {@code true} on success.
     */
    public static boolean connect(Context ctx) {
        synchronized (LOCK) {
            if (isConnected()) return true;

            // 1. fast path: try connecting to a daemon that's already alive
            if (tryOpenSocket()) {
                handshake();
                if (isConnected()) return true;
            }

            // 2. cold start: bootstrap via local ADB
            AppLogger.i(TAG, "no daemon found, bootstrapping via AdbLocalClient");
            String bootMsg = bootstrap(ctx);
            AppLogger.d(TAG, "bootstrap result: " + bootMsg);

            // 3. retry the connect with backoff
            for (int i = 0; i < RETRY_COUNT; i++) {
                try { Thread.sleep(RETRY_DELAY_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                if (tryOpenSocket()) {
                    handshake();
                    if (isConnected()) {
                        AppLogger.i(TAG, "daemon ready after " + (i + 1) + " retries (uid="
                                + sDaemonUid + " pid=" + sDaemonPid + " ver=" + sDaemonVer + ")");
                        return true;
                    }
                }
            }

            AppLogger.w(TAG, "daemon unreachable after bootstrap");
            return false;
        }
    }

    /**
     * Read the tail of the daemon's stdout/stderr capture file via legacy ADB.
     * Useful to surface the real cold-start error (class not found, SELinux,
     * dex2oat failure, etc.) in the Diag test message when {@link #connect(Context)}
     * has returned {@code false}.
     *
     * @return the (trimmed) tail content, or a short marker if unavailable.
     */
    public static String readDaemonLogTail(Context ctx) {
        final java.util.concurrent.atomic.AtomicReference<String> out = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        AdbLocalClient.executeShellWithResult(ctx, READ_LOG_CMD, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String s) { out.set(s); latch.countDown(); }
            @Override public void onError(String e)   { out.set("<log read failed: " + e + ">"); latch.countDown(); }
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) return "<log read timed out>";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "<interrupted>";
        }
        String s = out.get();
        return (s == null || s.isEmpty()) ? "<empty>" : s;
    }

    /** Close the socket. The daemon keeps running. */
    public static void disconnect() {
        synchronized (LOCK) {
            closeQuietly();
        }
    }

    /** Round-trip latency in ms, or {@code -1} on error / not connected. */
    public static long ping() {
        synchronized (LOCK) {
            if (!isConnected()) return -1L;
            try {
                long t0 = SystemClock.elapsedRealtime();
                String reply = sendRecvSingle("PING");
                long t1 = SystemClock.elapsedRealtime();
                if (reply == null || !reply.startsWith("OK ")) return -1L;
                return t1 - t0;
            } catch (IOException e) {
                AppLogger.w(TAG, "ping failed: " + e.getMessage());
                closeQuietly();
                return -1L;
            }
        }
    }

    /** UID of the daemon process as reported by its last {@code WHOAMI}. */
    public static int getCallerUid() {
        synchronized (LOCK) { return sDaemonUid; }
    }

    /** PID of the daemon process as reported by its last {@code WHOAMI}. */
    public static int getDaemonPid() {
        synchronized (LOCK) { return sDaemonPid; }
    }

    /** Protocol version reported by the daemon, or {@code null} if never handshook. */
    public static String getProtocolVersion() {
        synchronized (LOCK) { return sDaemonVer; }
    }

    /**
     * Run a shell command on the daemon and return its combined stdout/stderr.
     * Blocks until the daemon sends an {@code OK exit=N} or {@code ERR ...} terminator.
     */
    public static String runShell(String cmd) throws BetaProxyException {
        synchronized (LOCK) {
            if (!isConnected()) throw new BetaProxyException("not connected");
            try {
                sOut.println("EXEC " + cmd);
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = sIn.readLine()) != null) {
                    if (line.startsWith("DAT ")) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(line, 4, line.length());
                    } else if (line.startsWith("OK ")) {
                        return sb.toString();
                    } else if (line.startsWith("ERR ")) {
                        throw new BetaProxyException(line.substring(4));
                    } else {
                        // unknown frame — be tolerant
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(line);
                    }
                }
                throw new BetaProxyException("connection closed mid-command");
            } catch (IOException e) {
                closeQuietly();
                throw new BetaProxyException("I/O: " + e.getMessage(), e);
            }
        }
    }

    // ─── internals ─────────────────────────────────────────────────────────

    private static boolean tryOpenSocket() {
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            sSocket = s;
            sIn  = new BufferedReader(new InputStreamReader(s.getInputStream()));
            sOut = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(s.getOutputStream())),
                    true /* autoFlush */);
            return true;
        } catch (IOException e) {
            return false; // expected when daemon not yet running
        }
    }

    private static void handshake() {
        try {
            String reply = sendRecvSingle("WHOAMI");
            if (reply != null && reply.startsWith("OK ")) {
                String body = reply.substring(3);
                sDaemonUid = parseInt(body, "uid=", -1);
                sDaemonPid = parseInt(body, "pid=", -1);
                sDaemonVer = parseStr(body, "ver=");
            } else {
                AppLogger.w(TAG, "unexpected WHOAMI reply: " + reply);
                closeQuietly();
            }
        } catch (IOException e) {
            AppLogger.w(TAG, "handshake I/O failed: " + e.getMessage());
            closeQuietly();
        }
    }

    private static String sendRecvSingle(String cmd) throws IOException {
        sOut.println(cmd);
        return sIn.readLine();
    }

    /** Issue the bootstrap script via legacy ADB and wait (briefly) for it to finish. */
    private static String bootstrap(Context ctx) {
        final AtomicReference<String> out = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        AdbLocalClient.executeShellWithResult(ctx, BOOTSTRAP_CMD, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) { out.set(report); latch.countDown(); }
            @Override public void onError(String error)    { out.set("ERR " + error); latch.countDown(); }
        });
        try {
            if (!latch.await(BOOTSTRAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "ERR bootstrap timed out";
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "ERR interrupted";
        }
        return out.get();
    }

    private static void closeQuietly() {
        try { if (sOut    != null) sOut.close();    } catch (Throwable ignore) {}
        try { if (sIn     != null) sIn.close();     } catch (Throwable ignore) {}
        try { if (sSocket != null) sSocket.close(); } catch (Throwable ignore) {}
        sSocket = null;
        sIn     = null;
        sOut    = null;
    }

    private static int parseInt(String body, String key, int fallback) {
        String v = parseStr(body, key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return fallback; }
    }

    private static String parseStr(String body, String key) {
        int i = body.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = body.indexOf(' ', start);
        return end < 0 ? body.substring(start) : body.substring(start, end);
    }

    /** Thrown when the proxy daemon path fails — caller should fall back to legacy. */
    public static class BetaProxyException extends Exception {
        public BetaProxyException(String msg) { super(msg); }
        public BetaProxyException(String msg, Throwable cause) { super(msg, cause); }
    }
}
