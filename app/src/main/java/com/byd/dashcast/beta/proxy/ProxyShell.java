package com.byd.dashcast.beta.proxy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * ProxyShell — shell execution utility for the daemon process.
 *
 * <p>Provides a single {@link #exec} entry point that forks a {@code sh -c}
 * subprocess, captures combined stdout+stderr, and enforces a 30-second timeout.
 * All methods are package-private and daemon-only.
 */
final class ProxyShell {

    private ProxyShell() {}

    /** Result of a {@link #exec} call. */
    static final class Result {
        final int    exit;
        final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
    }

    /**
     * Execute {@code cmd} via {@code sh -c}, capturing combined stdout + stderr.
     * Strips trailing newlines to preserve the exact semantics of the original
     * line-by-line collector. Bounded by a 30-second timeout.
     *
     * <p>Build 195 / P4: reads the stream into a single {@link ByteArrayOutputStream}
     * then decodes once — replaces per-line {@code BufferedReader + append('\n')}
     * which on a 300-line {@code dumpsys} ate ~1000 allocations.
     */
    static Result exec(String cmd) {
        if (cmd == null) return new Result(-1, "ERR null command");
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            byte[] chunk = new byte[4096];
            try (InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(chunk)) > 0) baos.write(chunk, 0, n);
            }
            // Bounded waitFor: a hung child (e.g. dumpsys on a wedged service) used to
            // block the binder thread forever. Pool has ~15 threads; N hangs = daemon unresponsive.
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
                return new Result(-1, "ERR timeout 30s");
            }
            int code = p.exitValue();
            String s = baos.toString("UTF-8");
            int end = s.length();
            while (end > 0) {
                char c = s.charAt(end - 1);
                if (c != '\n' && c != '\r') break;
                end--;
            }
            return new Result(code, end == s.length() ? s : s.substring(0, end));
        } catch (Throwable t) {
            String msg = t.getMessage();
            return new Result(-1, "ERR " + (msg == null ? t.getClass().getSimpleName() : msg));
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }
}
