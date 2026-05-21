package com.byd.dashcast.beta.proxy;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;

/**
 * ProxyDaemonMain — entry point for the Beta Engine Component A daemon.
 *
 * <p>Started by the app via {@code AdbLocalClient} with a command of the form:
 * <pre>
 *   APK=$(pm path com.byd.dashcast | head -1 | cut -d: -f2-)
 *   CLASSPATH="$APK" nohup app_process / com.byd.dashcast.beta.proxy.ProxyDaemonMain \
 *       &gt;/dev/null 2&gt;&amp;1 &lt;/dev/null &amp;
 * </pre>
 *
 * <p>The daemon thus inherits the {@code shell} UID (2000) of the ADB local
 * session, exposes a {@link LocalServerSocket} in the abstract namespace
 * ({@link #SOCKET_NAME}) and serves a tiny line-based protocol:
 * <ul>
 *   <li>{@code PING} → {@code OK pong &lt;epoch_ms&gt;}</li>
 *   <li>{@code WHOAMI} → {@code OK uid=&lt;n&gt; pid=&lt;n&gt; ver=&lt;v&gt;}</li>
 *   <li>{@code EXEC &lt;cmd&gt;} → {@code DAT &lt;line&gt;}* {@code OK exit=&lt;n&gt;} or {@code ERR &lt;msg&gt;}</li>
 *   <li>{@code QUIT} → closes the current client connection</li>
 * </ul>
 *
 * <p>The daemon is single-process, multi-thread (one thread per client). It does
 * not auto-restart — the client is expected to re-bootstrap if it is gone.
 */
public final class ProxyDaemonMain {

    /** Abstract socket namespace name (no leading {@code @}; {@link LocalServerSocket} adds it). */
    public static final String SOCKET_NAME = "dashcast_proxy";

    /** Protocol version returned by {@code WHOAMI}. Bump on any wire-incompatible change. */
    public static final String PROTOCOL_VERSION = "1";

    /** Process name shown in {@code ps} after {@code setArgV0}. */
    private static final String PROC_NAME = "dashcast_proxy";

    private ProxyDaemonMain() {}

    public static void main(String[] args) {
        try {
            renameProcess();
            LocalServerSocket server = new LocalServerSocket(SOCKET_NAME);
            log("listening on @" + SOCKET_NAME
                    + " uid=" + Process.myUid()
                    + " pid=" + Process.myPid()
                    + " ver=" + PROTOCOL_VERSION);
            while (true) {
                LocalSocket client = server.accept();
                Thread t = new Thread(() -> handle(client), "proxy-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (Throwable t) {
            log("FATAL: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void renameProcess() {
        try {
            Method m = Process.class.getDeclaredMethod("setArgV0", String.class);
            m.invoke(null, PROC_NAME);
        } catch (Throwable ignore) {
            // not fatal — process keeps its app_process name
        }
    }

    private static void handle(LocalSocket s) {
        try (LocalSocket sock = s;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(
                     new BufferedWriter(new OutputStreamWriter(sock.getOutputStream())),
                     true /* autoFlush */)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("PING")) {
                    out.println("OK pong " + System.currentTimeMillis());
                } else if (line.equals("WHOAMI")) {
                    out.println("OK uid=" + Process.myUid()
                            + " pid=" + Process.myPid()
                            + " ver=" + PROTOCOL_VERSION);
                } else if (line.startsWith("EXEC ")) {
                    runShell(line.substring(5), out);
                } else if (line.equals("QUIT")) {
                    out.println("OK bye");
                    return;
                } else {
                    out.println("ERR unknown command");
                }
            }
        } catch (IOException e) {
            log("client IO err: " + e);
        }
    }

    private static void runShell(String cmd, PrintWriter out) {
        try {
            java.lang.Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) {
                    out.println("DAT " + l);
                }
            }
            int code = p.waitFor();
            out.println("OK exit=" + code);
        } catch (Throwable t) {
            String msg = t.getMessage();
            out.println("ERR " + (msg == null ? t.getClass().getSimpleName() : msg));
        }
    }

    private static void log(String s) {
        System.out.println("[dashcast_proxy] " + s);
    }
}
