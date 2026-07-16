package com.byd.dashcast.proxy.daemon;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * ProxyShell — shell execution utility for the daemon process.
 *
 * <p>Provides a single {@link #exec} entry point that forks a {@code sh -c}
 * subprocess, captures combined stdout+stderr, and enforces a 30-second timeout.
 * All methods are package-private and daemon-only.
 */
final class ProxyShell {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;
    private static final String TRUNCATION_MARKER = "[output truncated]";

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
        return exec(cmd, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_OUTPUT_BYTES);
    }

    static Result exec(String cmd, long timeoutMs, int maxOutputBytes) {
        if (cmd == null) return new Result(-1, "ERR null command");
        if (timeoutMs <= 0L) return new Result(-1, "ERR invalid timeout");
        if (maxOutputBytes <= 0) return new Result(-1, "ERR invalid output limit");
        ProcessExecution execution = null;
        Process process = null;
        InputStream processOutput = null;
        Thread reader = null;
        try {
                execution = startCommand(cmd);
                process = execution.process;
            try { process.getOutputStream().close(); } catch (Throwable ignored) {}

            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            processOutput = process.getInputStream();
            OutputCapture capture = new OutputCapture(processOutput, maxOutputBytes);
            reader = new Thread(capture, "proxy-shell-reader");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                terminate(execution, processOutput, reader);
                return new Result(-1, timeoutMessage(timeoutMs));
            }

            reader.join(remainingMillis(deadlineNanos));
            if (reader.isAlive()) {
                terminate(execution, processOutput, reader);
                return new Result(-1, timeoutMessage(timeoutMs));
            }
            return new Result(process.exitValue(), capture.result());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "ERR interrupted");
        } catch (Throwable t) {
            String msg = t.getMessage();
            return new Result(-1, "ERR " + (msg == null ? t.getClass().getSimpleName() : msg));
        } finally {
            if (processOutput != null) try { processOutput.close(); } catch (Throwable ignored) {}
            if (process != null) try { process.destroy(); } catch (Throwable ignored) {}
            if (execution != null) execution.pidFile.delete();
        }
    }

    private static ProcessExecution startCommand(String cmd) throws java.io.IOException {
        File tempDir = new File("/data/local/tmp");
        if (!tempDir.isDirectory() || !tempDir.canWrite()) {
            tempDir = new File(System.getProperty("java.io.tmpdir", "."));
        }
        File pidFile = File.createTempFile("dashcast_proxy_shell_", ".pid", tempDir);
        // Pass cmd as $1 rather than interpolating it into the wrapper. The child shell receives
        // exactly the original command string; the wrapper owns only process-group lifecycle.
        String wrapper = "if command -v setsid >/dev/null 2>&1; then "
                + "setsid sh -c \"$1\" & child=$!; echo G:$child > \"$2\"; "
                + "else sh -c \"$1\" & child=$!; echo P:$child > \"$2\"; fi; wait $child";
        try {
            Process process = new ProcessBuilder(
                    "sh", "-c", wrapper, "dashcast-proxy-shell", cmd, pidFile.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            return new ProcessExecution(process, pidFile);
        } catch (java.io.IOException startError) {
            pidFile.delete();
            throw startError;
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) return 1L;
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private static String timeoutMessage(long timeoutMs) {
        return timeoutMs % 1000L == 0L
                ? "ERR timeout " + (timeoutMs / 1000L) + "s"
                : "ERR timeout " + timeoutMs + "ms";
    }

    private static void terminate(ProcessExecution execution, InputStream output, Thread reader) {
        Process process = execution.process;
        ChildIdentity child = readChildIdentity(execution.pidFile);
        try { process.destroy(); } catch (Throwable ignored) {}
        if (child == null) child = readChildIdentity(execution.pidFile);
        if (child != null) killChild(child);
        try {
            if (!process.waitFor(100L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (Throwable ignored) {
            try { process.destroyForcibly(); } catch (Throwable ignoredAgain) {}
        }
        try { output.close(); } catch (Throwable ignored) {}
        try { reader.join(500L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static ChildIdentity readChildIdentity(File pidFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(pidFile))) {
            String line = reader.readLine();
            if (line == null || line.length() < 3 || line.charAt(1) != ':') return null;
            long pid = Long.parseLong(line.substring(2).trim());
            return pid > 0 ? new ChildIdentity(pid, line.charAt(0) == 'G') : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void killChild(ChildIdentity child) {
        String target = child.processGroup ? "-" + child.pid : Long.toString(child.pid);
        Process killer = null;
        try {
            String script = "kill -9 -- \"$1\" 2>/dev/null || kill -9 \"$1\" 2>/dev/null || true";
            killer = new ProcessBuilder("sh", "-c", script, "dashcast-proxy-kill", target).start();
            killer.waitFor(500L, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // The direct Process.destroy path below still handles the supervisor/legacy child.
        } finally {
            if (killer != null) try { killer.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static final class ProcessExecution {
        final Process process;
        final File pidFile;

        ProcessExecution(Process process, File pidFile) {
            this.process = process;
            this.pidFile = pidFile;
        }
    }

    private static final class ChildIdentity {
        final long pid;
        final boolean processGroup;

        ChildIdentity(long pid, boolean processGroup) {
            this.pid = pid;
            this.processGroup = processGroup;
        }
    }

    private static final class OutputCapture implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream output;
        private boolean truncated;

        OutputCapture(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
            this.output = new ByteArrayOutputStream(Math.min(limit, 2048));
        }

        @Override public void run() {
            byte[] chunk = new byte[4096];
            try {
                int count;
                while ((count = input.read(chunk)) > 0) {
                    int remaining = limit - output.size();
                    if (remaining > 0) output.write(chunk, 0, Math.min(count, remaining));
                    if (count > remaining) truncated = true;
                }
            } catch (Throwable ignored) {
                // Closing the stream is how the timeout path unblocks this reader.
            }
        }

        String result() {
            String value = new String(output.toByteArray(), StandardCharsets.UTF_8);
            int end = value.length();
            while (end > 0) {
                char c = value.charAt(end - 1);
                if (c != '\n' && c != '\r') break;
                end--;
            }
            value = end == value.length() ? value : value.substring(0, end);
            if (!truncated) return value;
            return value.isEmpty() ? TRUNCATION_MARKER : value + "\n" + TRUNCATION_MARKER;
        }
    }
}
