package com.byd.dashcast.beta.proxy;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * ProxyDaemonMain — entry point for the Beta Engine Component A daemon (v1.1.6+).
 *
 * <p>Started by {@link com.byd.dashcast.beta.BetaProxyClient} via {@code app_process64}
 * over a local-ADB pairing session, so the JVM inherits the {@code shell} UID
 * (2000) of the ADB connection.
 *
 * <p>Since Android 10+ SELinux denies {@code untrusted_app}→{@code shell}
 * {@code unix_stream_socket connectto} (the 1.1.5 failure mode), this version
 * does NOT expose a {@link android.net.LocalServerSocket}. Instead it follows
 * the pattern used by OpenBYD and our own {@code MirrorDaemon}:
 * <ol>
 *   <li>Acquire a system {@link Context} via reflective
 *       {@code ActivityThread.systemMain().getSystemContext()}.</li>
 *   <li>Publish a {@link Binder} subclass implementing the
 *       {@code com.byd.dashcast.beta.proxy.IProxyDaemon} contract.</li>
 *   <li>Broadcast {@link #ACTION_PROXY_CONNECTED} targeted at the app package
 *       with the binder wrapped in a {@link BinderParcelable} extra.</li>
 *   <li>Enter {@link Looper#loop()} to keep the binder pool alive.</li>
 * </ol>
 *
 * <p>The wire protocol on top of {@link Binder#transact} is a tiny fixed set of
 * transactions identified by integer codes:
 * <ul>
 *   <li>{@link #TXN_PING}   — no args → {@code long} epoch_ms</li>
 *   <li>{@link #TXN_WHOAMI} — no args → {@code int uid, int pid, String ver}</li>
 *   <li>{@link #TXN_EXEC}   — {@code String cmd} → {@code int exit, String combinedOutput}</li>
 * </ul>
 */
public final class ProxyDaemonMain {

    /** AIDL-style descriptor for {@link Binder#attachInterface(android.os.IInterface, String)}. */
    public static final String DESCRIPTOR = "com.byd.dashcast.beta.proxy.IProxyDaemon";

    /** Broadcast action delivered to the app once the daemon is ready. */
    public static final String ACTION_PROXY_CONNECTED = "com.byd.dashcast.beta.PROXY_CONNECTED";

    /** Parcelable extra key carrying the daemon's {@link BinderParcelable}. */
    public static final String EXTRA_BINDER = "proxy_binder";

    /** App package that should receive the broadcast (must own the receiver). */
    public static final String TARGET_PKG = "com.byd.dashcast";

    /** Protocol version reported by {@link #TXN_WHOAMI}. Bump on any wire-incompatible change. */
    public static final String PROTOCOL_VERSION = "2";

    /** Process name shown in {@code ps} after the JVM's {@code setArgV0} runs. */
    private static final String PROC_NAME = "dashcast_proxy";

    /** Transaction: no args → {@code long} (epoch ms). */
    public static final int TXN_PING   = android.os.IBinder.FIRST_CALL_TRANSACTION;        // 1
    /** Transaction: no args → {@code int uid, int pid, String ver}. */
    public static final int TXN_WHOAMI = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;    // 2
    /** Transaction: {@code String cmd} → {@code int exit, String combinedOutput}. */
    public static final int TXN_EXEC   = android.os.IBinder.FIRST_CALL_TRANSACTION + 2;    // 3
    /** Transaction: no args → {@code String pipeSeparatedProbeResults}. Phase 4 feasibility. */
    public static final int TXN_PROBE_PHASE4 = android.os.IBinder.FIRST_CALL_TRANSACTION + 3; // 4
    /** Transaction: {@code int displayId, int l, int t, int r, int b} → nothing (or thrown exception).
     *  Phase 4a typed verb replacing {@code wm overscan L,T,R,B -d displayId}. */
    public static final int TXN_SET_OVERSCAN = android.os.IBinder.FIRST_CALL_TRANSACTION + 4; // 5
    /** Transaction: {@code String packageName} → {@code String spaceSeparatedPids}.
     *  Phase 4b typed verb replacing {@code pidof <pkg>} (state-poll hot path). */
    public static final int TXN_GET_PIDS = android.os.IBinder.FIRST_CALL_TRANSACTION + 5; // 6
    /** Transaction: {@code int type, int info, String str} → nothing (or thrown exception).
     *  Phase 4c typed verb replacing {@code service call AutoContainer 2 i32 … i32 … s16 "…"}
     *  used by {@code AdbLocalClient.sendInfo}. */
    public static final int TXN_AUTOCONTAINER_SEND_INFO = android.os.IBinder.FIRST_CALL_TRANSACTION + 6; // 7
    /** Transaction: {@code String packageName, int userId} → nothing (or thrown exception).
     *  Phase 4d typed verb replacing {@code am force-stop <pkg>} used by
     *  {@code AdbLocalClient.restoreBydOnCluster} / {@code restoreOriginCluster}
     *  (end-of-session teardown hot path). */
    public static final int TXN_FORCE_STOP_PACKAGE = android.os.IBinder.FIRST_CALL_TRANSACTION + 7; // 8

    /** Set in {@link #main(String[])} once the system context is acquired, so
     *  {@link ProxyBinder} can hand it to {@link Phase4Probes} without re-acquiring. */
    private static volatile Context sSystemContext;

    private ProxyDaemonMain() {}

    public static void main(String[] args) {
        try {
            renameProcess();
            Looper.prepareMainLooper();

            ProxyBinder binder = new ProxyBinder();
            log("binder ready uid=" + Process.myUid()
                    + " pid=" + Process.myPid()
                    + " ver=" + PROTOCOL_VERSION);

            Context systemContext = acquireSystemContext();
            if (systemContext == null) {
                log("FATAL: no system context — cannot broadcast binder");
                System.exit(2);
                return;
            }
            sSystemContext = systemContext;

            Intent intent = new Intent(ACTION_PROXY_CONNECTED)
                    .setPackage(TARGET_PKG)
                    .putExtra(EXTRA_BINDER, new BinderParcelable(binder))
                    // FLAG_INCLUDE_STOPPED_PACKAGES so the app receives the broadcast
                    // even right after a force-stop — important for the bootstrap flow
                    // where the receiver was just dynamically registered.
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            systemContext.sendBroadcast(intent);
            log("broadcast sent: " + ACTION_PROXY_CONNECTED + " → " + TARGET_PKG);

            Looper.loop();
        } catch (Throwable t) {
            log("FATAL: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    /** Reflective hop to obtain a usable system {@link Context} from inside {@code app_process}. */
    private static Context acquireSystemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Object ctx    = at.getMethod("getSystemContext").invoke(thread);
            return (Context) ctx;
        } catch (Throwable t) {
            log("acquireSystemContext failed: " + t);
            return null;
        }
    }

    private static void renameProcess() {
        try {
            Method m = Process.class.getDeclaredMethod("setArgV0", String.class);
            m.invoke(null, PROC_NAME);
        } catch (Throwable ignore) {
            // not fatal — process keeps its app_process / --nice-name argv[0]
        }
    }

    /**
     * Binder published to the app. Uses raw {@link Binder#onTransact} (no AIDL
     * codegen needed — keeps the build simple and the wire format obvious).
     */
    static final class ProxyBinder extends Binder {

        ProxyBinder() {
            attachInterface(null, DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case TXN_PING: {
                    data.enforceInterface(DESCRIPTOR);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeLong(System.currentTimeMillis());
                    }
                    return true;
                }
                case TXN_WHOAMI: {
                    data.enforceInterface(DESCRIPTOR);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(Process.myUid());
                        reply.writeInt(Process.myPid());
                        reply.writeString(PROTOCOL_VERSION);
                    }
                    return true;
                }
                case TXN_EXEC: {
                    data.enforceInterface(DESCRIPTOR);
                    String cmd = data.readString();
                    ExecResult er = runShell(cmd);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(er.exit);
                        reply.writeString(er.output);
                    }
                    return true;
                }
                case TXN_PROBE_PHASE4: {
                    data.enforceInterface(DESCRIPTOR);
                    String result;
                    try {
                        result = Phase4Probes.runAll(sSystemContext);
                    } catch (Throwable t) {
                        // Probe harness must never crash the daemon — fall back to a
                        // synthetic single-token error so the client side still parses.
                        result = "P0=FAIL_OTHER:harness " + t.getClass().getSimpleName()
                                + " " + (t.getMessage() == null ? "" : t.getMessage());
                    }
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(result);
                    }
                    return true;
                }
                case TXN_SET_OVERSCAN: {
                    data.enforceInterface(DESCRIPTOR);
                    int displayId = data.readInt();
                    int l = data.readInt();
                    int t = data.readInt();
                    int r = data.readInt();
                    int b = data.readInt();
                    try {
                        Phase4Verbs.setOverscan(displayId, l, t, r, b);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                    } catch (Throwable ex) {
                        // Surface the real cause to the client so it can fall back to
                        // the shell path with full diagnostic context.
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            // writeException needs a real Exception subclass; wrap if necessary.
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_GET_PIDS: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    String pids;
                    try {
                        pids = Phase4Verbs.getPidsByPackage(pkg);
                    } catch (Throwable t) {
                        // Pure-Java /proc scan should never throw, but guard anyway:
                        // surface as a normal exception so the client falls back to shell.
                        if (reply != null) {
                            Exception wrap = (t instanceof Exception)
                                    ? (Exception) t
                                    : new RuntimeException(t.getClass().getSimpleName() + ": " + t.getMessage());
                            reply.writeException(wrap);
                        }
                        return true;
                    }
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(pids);
                    }
                    return true;
                }
                case TXN_AUTOCONTAINER_SEND_INFO: {
                    data.enforceInterface(DESCRIPTOR);
                    int type = data.readInt();
                    int info = data.readInt();
                    String str = data.readString();
                    try {
                        Phase4Verbs.autoContainerSendInfo(type, info, str);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_FORCE_STOP_PACKAGE: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    int userId = data.readInt();
                    try {
                        Phase4Verbs.forceStopPackage(pkg, userId);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case INTERFACE_TRANSACTION: {
                    if (reply != null) reply.writeString(DESCRIPTOR);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    private static final class ExecResult {
        final int    exit;
        final String output;
        ExecResult(int exit, String output) { this.exit = exit; this.output = output; }
    }

    private static ExecResult runShell(String cmd) {
        if (cmd == null) return new ExecResult(-1, "ERR null command");
        try {
            java.lang.Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(l);
                }
            }
            int code = p.waitFor();
            return new ExecResult(code, sb.toString());
        } catch (Throwable t) {
            String msg = t.getMessage();
            return new ExecResult(-1, "ERR " + (msg == null ? t.getClass().getSimpleName() : msg));
        }
    }

    private static void log(String s) {
        System.out.println("[dashcast_proxy] " + s);
    }
}
