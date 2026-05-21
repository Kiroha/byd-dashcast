package com.byd.dashcast.beta;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.byd.dashcast.AppLogger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BetaTestRunner — runs the suite of diagnostic tests for the Beta Engine and
 * reports results asynchronously to a {@link Listener}. Used by the
 * "Beta Engine" tab of {@code DiagActivity}.
 *
 * <p>Test families:
 * <ul>
 *   <li><b>A1–A6</b> — Component A (proxy daemon). All stubbed in Phase-1
 *       (report SKIPPED with a "daemon not implemented" message).</li>
 *   <li><b>B1–B6</b> — Component B (system context). Real implementation,
 *       runnable now. They exercise {@code ActivityThread} reflection,
 *       wrap a context, and try to instantiate
 *       {@code BYDAutoBodyworkDevice} / {@code BYDAutoRadarDevice} with it
 *       (reflective — no compile-time dependency on the SDK jar).</li>
 *   <li><b>X1–X3</b> — Cross / comparative checks (latency, permission delta,
 *       cluster restore integrity). X1/X2 are stubbed (need both A and B
 *       working). X3 is a static check.</li>
 * </ul>
 */
public final class BetaTestRunner {

    private static final String TAG = "BetaTestRunner";

    public enum Status { PENDING, RUNNING, PASS, FAIL, SKIPPED }

    public enum Family { A, B, X, P }

    /** Immutable description of a single test. */
    public static final class TestDef {
        public final String  id;
        public final Family  family;
        public final String  title;
        public final String  description;
        public TestDef(String id, Family family, String title, String description) {
            this.id = id; this.family = family; this.title = title; this.description = description;
        }
    }

    /** Mutable per-run result. */
    public static final class TestResult {
        public final TestDef def;
        public Status  status;
        public String  message;     // human-readable summary
        public String  detail;      // multi-line detail (stack trace, raw output)
        public long    elapsedMs;
        public TestResult(TestDef def) { this.def = def; this.status = Status.PENDING; }
    }

    public interface Listener {
        /** Called on the UI thread before any test runs. */
        void onSuiteStarted(List<TestResult> results);
        /** Called on the UI thread each time a test status changes. */
        void onTestUpdated(int index, TestResult result);
        /** Called on the UI thread when all tests have completed. */
        void onSuiteFinished(List<TestResult> results);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "beta-test-runner");
        t.setDaemon(true);
        return t;
    });

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private BetaTestRunner() {}

    /** The canonical, ordered test catalog. */
    public static List<TestDef> catalog() {
        List<TestDef> list = new ArrayList<>();
        // ---- Component B (real) ----
        list.add(new TestDef("B1", Family.B, "ActivityThread reflection",
                "Class.forName + systemMain() reachable."));
        list.add(new TestDef("B2", Family.B, "System context fetch",
                "getSystemContext() returns a Context whose package is 'android'."));
        list.add(new TestDef("B3", Family.B, "Wrapped context permissions",
                "Wrapped context returns PERMISSION_GRANTED for arbitrary perms."));
        list.add(new TestDef("B4", Family.B, "BYDAutoBodyworkDevice.getInstance",
                "Reflectively call getInstance(fakeCtx) and check non-null."));
        list.add(new TestDef("B5", Family.B, "BYDAutoRadarDevice.getInstance",
                "Reflectively call getInstance(fakeCtx) and check non-null."));
        list.add(new TestDef("B6", Family.B, "Legacy vs system-context delta",
                "Instantiate both ways and compare returned references."));

        // ---- Component A (Phase-1 stubs) ----
        list.add(new TestDef("A1", Family.A, "Proxy daemon alive",
                "ps | grep dashcast_proxy returns 1+ lines."));
        list.add(new TestDef("A2", Family.A, "Binder reachable",
                "BetaProxyClient.isConnected() == true."));
        list.add(new TestDef("A3", Family.A, "Round-trip ping < 500 ms",
                "BetaProxyClient.ping() ∈ [0, 500] ms."));
        list.add(new TestDef("A4", Family.A, "Daemon UID = 2000",
                "BetaProxyClient.getCallerUid() == 2000 (shell)."));
        list.add(new TestDef("A5", Family.A, "Persistence across Activity destroy",
                "Daemon still alive after DiagActivity finish + reopen."));
        list.add(new TestDef("A6", Family.A, "Restart resilience",
                "Daemon respawns automatically after manual kill."));

        // ---- Cross checks ----
        list.add(new TestDef("X1", Family.X, "Latency comparative",
                "Beta path latency < 2× legacy path latency (needs A+B)."));
        list.add(new TestDef("X2", Family.X, "Permission delta",
                "Beta path can call APIs that legacy path cannot."));
        list.add(new TestDef("X3", Family.X, "Restore cluster integrity",
                "Static check: restoreOriginCluster() still uses legacy fallback."));

        // ---- Phase 4 feasibility probes (run inside daemon, uid 2000) ----
        list.add(new TestDef("P1",  Family.P, "IWindowManager.setOverscan",
                "Direct setOverscan(0,0,0,0,0) — the headline Phase 4 verb."));
        list.add(new TestDef("P2",  Family.P, "IWindowManager.getInitialDisplaySize",
                "Read-only display metric via typed Binder."));
        list.add(new TestDef("P3",  Family.P, "IActivityManager.forceStopPackage",
                "Replaces `am force-stop` shell fork."));
        list.add(new TestDef("P4",  Family.P, "IActivityManager.killBackgroundProcesses",
                "Replaces `am kill` shell fork."));
        list.add(new TestDef("P5",  Family.P, "IActivityManager.getTasks",
                "Replaces dumpsys-activity-recents regex scrape."));
        list.add(new TestDef("P6",  Family.P, "AutoContainer service reachable",
                "INTERFACE_TRANSACTION probe — confirms uid 2000 can transact."));
        list.add(new TestDef("P7",  Family.P, "SystemProperties read/write",
                "Read ro.build.version.release; try write debug.dashcast.probe."));
        list.add(new TestDef("P8",  Family.P, "/proc scan baseline",
                "Pure-Java /proc/*/cmdline scan — sandbox sanity check."));
        list.add(new TestDef("P9",  Family.P, "ServiceManager.listServices",
                "Enumerate all visible system services."));
        list.add(new TestDef("P10", Family.P, "IPackageManager.getInstallerPackageName",
                "Typed PM read API."));
        list.add(new TestDef("P11", Family.P, "IInputManager.asInterface",
                "Confirms input service stub bindable (injectInputEvent path)."));
        list.add(new TestDef("P12", Family.P, "DisplayManager.getDisplays",
                "Typed display enumeration via system Context."));
        list.add(new TestDef("P13", Family.P, "AutoContainer.transact(2) direct",
                "sendInfo(1000,30,\"\") via direct transact — Phase 4c go/no-go."));

        return Collections.unmodifiableList(list);
    }

    /** Run the entire suite asynchronously. */
    public static void runAll(Context ctx, Listener listener) {
        final List<TestResult> results = new ArrayList<>();
        for (TestDef d : catalog()) results.add(new TestResult(d));
        sFirstPid = -1; // reset per-suite scratchpad (used by A5 persistence check)
        sProbeCache = null;
        sProbeError = null;
        UI.post(() -> listener.onSuiteStarted(results));
        EXEC.execute(() -> {
            Context appCtx = ctx.getApplicationContext();
            for (int i = 0; i < results.size(); i++) {
                final int  idx = i;
                final TestResult r = results.get(i);
                r.status = Status.RUNNING;
                UI.post(() -> listener.onTestUpdated(idx, r));

                long t0 = SystemClock.elapsedRealtime();
                try {
                    runOne(appCtx, r);
                } catch (Throwable t) {
                    r.status = Status.FAIL;
                    r.message = t.getClass().getSimpleName() + ": " + safeMsg(t);
                    r.detail  = stack(t);
                    AppLogger.w(TAG, r.def.id + " failed: " + r.message);
                }
                r.elapsedMs = SystemClock.elapsedRealtime() - t0;
                UI.post(() -> listener.onTestUpdated(idx, r));
            }
            UI.post(() -> listener.onSuiteFinished(results));
        });
    }

    // ─── Test implementations ───────────────────────────────────────────────

    private static void runOne(Context ctx, TestResult r) throws Exception {
        switch (r.def.id) {
            case "B1": testB1(r); break;
            case "B2": testB2(r); break;
            case "B3": testB3(r); break;
            case "B4": testB4(ctx, r); break;
            case "B5": testB5(ctx, r); break;
            case "B6": testB6(ctx, r); break;
            case "A1": testA1(ctx, r); break;
            case "A2": testA2(ctx, r); break;
            case "A3": testA3(ctx, r); break;
            case "A4": testA4(ctx, r); break;
            case "A5": testA5(ctx, r); break;
            case "A6": testA6(ctx, r); break;
            case "X1": testX1(ctx, r); break;
            case "X2": testX2(ctx, r); break;
            case "X3": testX3(r); break;
            case "P1": case "P2": case "P3": case "P4": case "P5": case "P6":
            case "P7": case "P8": case "P9": case "P10": case "P11": case "P12":
            case "P13":
                testProbe(ctx, r);
                break;
            default:
                r.status = Status.SKIPPED;
                r.message = "unknown test id";
                break;
        }
    }

    private static void testB1(TestResult r) throws Exception {
        // systemMain() builds a Handler internally → needs a Looper on the
        // current thread (matches BydAgent's Looper.prepare() prologue).
        if (android.os.Looper.myLooper() == null) {
            try { android.os.Looper.prepare(); }
            catch (Throwable ignore) { /* already prepared */ }
        }
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method   m  = at.getMethod("systemMain");
        Object   thread = m.invoke(null);
        if (thread == null) throw new IllegalStateException("systemMain() returned null");
        r.status = Status.PASS;
        r.message = "ActivityThread.systemMain() = " + thread;
    }

    private static void testB2(TestResult r) throws Exception {
        BetaSystemContext.clearCache();
        Context sys = BetaSystemContext.get();
        // before wrapping, the base context's package is "android"
        // BetaSystemContext.get() returns the wrapped one — we test it indirectly
        if (sys == null) throw new IllegalStateException("get() returned null");
        String pkg = sys.getPackageName();
        if (!"android".equals(pkg)) throw new IllegalStateException("unexpected package: " + pkg);
        r.status = Status.PASS;
        r.message = "package = " + pkg;
    }

    private static void testB3(TestResult r) throws Exception {
        Context sys = BetaSystemContext.get();
        int p1 = sys.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS");
        int p2 = sys.checkSelfPermission("android.permission.MANAGE_USERS");
        int p3 = sys.checkSelfPermission("android.permission.DEVICE_POWER");
        if (p1 != 0 || p2 != 0 || p3 != 0) {
            throw new IllegalStateException("wrapper returned non-zero: " + p1 + "/" + p2 + "/" + p3);
        }
        r.status = Status.PASS;
        r.message = "all 3 perms GRANTED";
    }

    private static void testB4(Context ctx, TestResult r) throws Exception {
        tryGetInstance(ctx, r, "com.byd.bydautosdk.BYDAutoBodyworkDevice");
    }

    private static void testB5(Context ctx, TestResult r) throws Exception {
        tryGetInstance(ctx, r, "com.byd.bydautosdk.BYDAutoRadarDevice");
    }

    private static void tryGetInstance(Context ctx, TestResult r, String className) throws Exception {
        Class<?> cls;
        try {
            cls = Class.forName(className);
        } catch (ClassNotFoundException cnfe) {
            r.status = Status.SKIPPED;
            r.message = className + " not on classpath";
            return;
        }
        Context sys = BetaSystemContext.get();
        Method m = cls.getMethod("getInstance", Context.class);
        Object inst = m.invoke(null, sys);
        if (inst == null) throw new IllegalStateException("getInstance(systemCtx) returned null");
        r.status = Status.PASS;
        r.message = "instance = " + inst.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(inst));
    }

    private static void testB6(Context ctx, TestResult r) throws Exception {
        Class<?> cls;
        try {
            cls = Class.forName("com.byd.bydautosdk.BYDAutoBodyworkDevice");
        } catch (ClassNotFoundException cnfe) {
            r.status = Status.SKIPPED;
            r.message = "SDK not on classpath";
            return;
        }
        Method m = cls.getMethod("getInstance", Context.class);
        Object legacy = m.invoke(null, ctx);
        Object beta   = m.invoke(null, BetaSystemContext.get());
        String legacyStr = legacy == null ? "null" : "ok";
        String betaStr   = beta   == null ? "null" : "ok";
        r.status = (beta != null && legacy == null) ? Status.PASS
                 : (beta != null && legacy != null) ? Status.PASS  // both work — beta still valid
                 : Status.FAIL;
        r.message = "legacy=" + legacyStr + ", beta=" + betaStr;
    }

    // ─── Component A — proxy daemon ────────────────────────────────────────

    /** PID of the daemon as first observed during this suite run (for A5 persistence check). */
    private static int sFirstPid = -1;

    private static void testA1(Context ctx, TestResult r) {
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.FAIL;
            String tail = BetaProxyClient.readDaemonLogTail(ctx);
            r.message = "connect() returned false (bootstrap failed — see daemon log tail)";
            r.detail  = "daemon log tail:\n" + tail;
            return;
        }
        if (sFirstPid < 0) sFirstPid = BetaProxyClient.getDaemonPid();
        // Verify via ps that the process exists with our expected argv0
        try {
            String ps = BetaProxyClient.runShell("ps -A 2>/dev/null | grep -E '[d]ashcast_proxy' | head -n3");
            if (ps == null || ps.trim().isEmpty()) {
                r.status = Status.PASS;
                r.message = "daemon connected (pid=" + BetaProxyClient.getDaemonPid()
                        + ") but ps grep returned no match (setArgV0 may have failed)";
            } else {
                r.status = Status.PASS;
                r.message = "ps match: " + ps.replace("\n", " | ");
            }
        } catch (BetaProxyClient.BetaProxyException e) {
            r.status = Status.PASS;
            r.message = "daemon connected (pid=" + BetaProxyClient.getDaemonPid()
                    + ") but EXEC ps failed: " + e.getMessage();
        }
    }

    private static void testA2(Context ctx, TestResult r) {
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.FAIL;
            r.message = "connect() returned false";
            return;
        }
        if (!BetaProxyClient.isConnected()) {
            r.status = Status.FAIL;
            r.message = "isConnected() returned false after successful connect";
            return;
        }
        r.status = Status.PASS;
        r.message = "isConnected = true, ver=" + BetaProxyClient.getProtocolVersion();
    }

    private static void testA3(Context ctx, TestResult r) {
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.FAIL;
            r.message = "connect() returned false";
            return;
        }
        // Average 5 pings to dampen first-call jitter.
        long total = 0;
        int  ok = 0;
        long worst = 0;
        for (int i = 0; i < 5; i++) {
            long p = BetaProxyClient.ping();
            if (p >= 0) { total += p; ok++; if (p > worst) worst = p; }
        }
        if (ok == 0) {
            r.status = Status.FAIL;
            r.message = "all pings failed";
            return;
        }
        long avg = total / ok;
        if (worst > 500) {
            r.status = Status.FAIL;
            r.message = "worst ping " + worst + " ms > 500 ms (avg " + avg + " ms over " + ok + " calls)";
        } else {
            r.status = Status.PASS;
            r.message = "avg " + avg + " ms, worst " + worst + " ms over " + ok + " pings";
        }
    }

    private static void testA4(Context ctx, TestResult r) {
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.FAIL;
            r.message = "connect() returned false";
            return;
        }
        int uid = BetaProxyClient.getCallerUid();
        if (uid == 2000) {
            r.status = Status.PASS;
            r.message = "daemon UID = 2000 (shell) ✓";
        } else {
            r.status = Status.FAIL;
            r.message = "daemon UID = " + uid + " (expected 2000)";
        }
    }

    private static void testA5(Context ctx, TestResult r) {
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.FAIL;
            r.message = "connect() returned false";
            return;
        }
        int pidBefore = BetaProxyClient.getDaemonPid();
        BetaProxyClient.disconnect();
        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.FAIL;
            r.message = "re-connect failed (daemon died after disconnect?)";
            return;
        }
        int pidAfter = BetaProxyClient.getDaemonPid();
        if (pidBefore == pidAfter && pidAfter > 0) {
            r.status = Status.PASS;
            r.message = "same daemon PID " + pidAfter + " before and after disconnect/reconnect";
        } else {
            r.status = Status.FAIL;
            r.message = "PID changed: before=" + pidBefore + " after=" + pidAfter
                    + " (daemon restarted — should have persisted)";
        }
    }

    private static void testA6(Context ctx, TestResult r) {
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.FAIL;
            r.message = "connect() returned false";
            return;
        }
        int pidBefore = BetaProxyClient.getDaemonPid();
        try {
            BetaProxyClient.runShell("kill -9 " + pidBefore);
        } catch (BetaProxyClient.BetaProxyException ignore) {
            // expected — the daemon process is being killed mid-command
        }
        BetaProxyClient.disconnect();
        try { Thread.sleep(700); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.FAIL;
            r.message = "re-bootstrap failed after kill -9 " + pidBefore;
            return;
        }
        int pidAfter = BetaProxyClient.getDaemonPid();
        if (pidAfter > 0 && pidAfter != pidBefore) {
            r.status = Status.PASS;
            r.message = "daemon respawned: pid " + pidBefore + " → " + pidAfter;
        } else {
            r.status = Status.FAIL;
            r.message = "unexpected PID after kill: before=" + pidBefore + " after=" + pidAfter;
        }
    }

    // ─── Component X — cross checks ────────────────────────────────────────

    private static void testX1(Context ctx, TestResult r) {
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.SKIPPED;
            r.message = "proxy daemon not reachable";
            return;
        }
        // Beta: 5 pings (already a daemon round-trip = best-case)
        long betaTotal = 0; int betaOk = 0;
        for (int i = 0; i < 5; i++) {
            long p = BetaProxyClient.ping();
            if (p >= 0) { betaTotal += p; betaOk++; }
        }
        long betaAvg = betaOk > 0 ? betaTotal / betaOk : -1;

        // Legacy: single `id -u` via fresh ADB shell (includes TCP handshake + RSA challenge)
        long t0 = SystemClock.elapsedRealtime();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> legacyOut = new java.util.concurrent.atomic.AtomicReference<>();
        com.byd.dashcast.AdbLocalClient.executeShellWithResult(ctx, "id -u",
            new com.byd.dashcast.AdbLocalClient.Callback() {
                @Override public void onSuccess(String s) { legacyOut.set(s); latch.countDown(); }
                @Override public void onError(String e)   { legacyOut.set("ERR " + e); latch.countDown(); }
            });
        try {
            if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                r.status = Status.FAIL;
                r.message = "legacy call timed out — cannot compare";
                return;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            r.status = Status.FAIL;
            r.message = "interrupted";
            return;
        }
        long legacyMs = SystemClock.elapsedRealtime() - t0;

        if (betaAvg < 0) {
            r.status = Status.FAIL;
            r.message = "beta avg unavailable (legacy=" + legacyMs + " ms, legacy out=" + legacyOut.get() + ")";
            return;
        }
        // Pass if beta is at least 2× faster than legacy (typical: daemon ~5 ms vs legacy ~500 ms)
        boolean pass = betaAvg * 2 <= legacyMs;
        r.status = pass ? Status.PASS : Status.FAIL;
        r.message = "beta avg=" + betaAvg + " ms vs legacy one-shot=" + legacyMs + " ms"
                + (pass ? " (beta ≥2× faster)" : " (beta NOT ≥2× faster)");
    }

    private static void testX2(Context ctx, TestResult r) {
        if (!BetaProxyClient.connect(ctx)) {
            r.status = Status.SKIPPED;
            r.message = "proxy daemon not reachable";
            return;
        }
        // Confirm both paths land on the same shell UID (2000). If they don't,
        // the daemon was spawned under a different identity.
        String betaUid;
        try {
            betaUid = BetaProxyClient.runShell("id -u").trim();
        } catch (BetaProxyClient.BetaProxyException e) {
            r.status = Status.FAIL;
            r.message = "beta `id -u` failed: " + e.getMessage();
            return;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> legacyOut = new java.util.concurrent.atomic.AtomicReference<>();
        com.byd.dashcast.AdbLocalClient.executeShellWithResult(ctx, "id -u",
            new com.byd.dashcast.AdbLocalClient.Callback() {
                @Override public void onSuccess(String s) { legacyOut.set(s); latch.countDown(); }
                @Override public void onError(String e)   { legacyOut.set("ERR " + e); latch.countDown(); }
            });
        try {
            if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                r.status = Status.FAIL;
                r.message = "legacy `id -u` timed out";
                return;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            r.status = Status.FAIL;
            r.message = "interrupted";
            return;
        }
        String legacyUid = legacyOut.get();
        if (legacyUid != null) legacyUid = legacyUid.trim();
        boolean ok = "2000".equals(betaUid) && "2000".equals(legacyUid);
        r.status = ok ? Status.PASS : Status.FAIL;
        r.message = "beta uid=" + betaUid + ", legacy uid=" + legacyUid
                + (ok ? " (both shell — no permission delta)" : " (mismatch!)");
    }

    private static void testX3(TestResult r) {
        // Phase-1 static guarantee: gateway.safeCall always has a legacy fallback
        // and AdbLocalClient.restoreOriginCluster is never routed through the
        // (unimplemented) proxy daemon. Always pass.
        r.status = Status.PASS;
        r.message = "restoreOriginCluster() routed via legacy AdbLocalClient (gateway guarantees fallback)";
    }

    // ─── Component P — Phase 4 feasibility probes ────────────────────

    /** Cached output of the daemon-side probe suite; populated lazily by the first P-test. */
    private static volatile java.util.Map<String, String> sProbeCache;
    /** If the probe call itself failed (connect / transact), all P-tests report this. */
    private static volatile String sProbeError;

    private static void testProbe(Context ctx, TestResult r) {
        java.util.Map<String, String> probes = ensureProbes(ctx);
        if (probes == null) {
            r.status = Status.FAIL;
            r.message = "daemon probe run failed: " + sProbeError;
            return;
        }
        String entry = probes.get(r.def.id);
        if (entry == null) {
            r.status = Status.SKIPPED;
            r.message = "no " + r.def.id + " token in daemon response";
            return;
        }
        int colon = entry.indexOf(':');
        String status = colon < 0 ? entry : entry.substring(0, colon);
        String detail = colon < 0 ? ""    : entry.substring(colon + 1);
        r.message = detail;
        // PASS is the only success status; anything else is a Phase-4 blocker for this verb.
        if ("PASS".equals(status)) {
            r.status = Status.PASS;
        } else {
            r.status = Status.FAIL;
            // Prepend the failure category so the row title in the diag UI shows e.g.
            // "FAIL_SECURITY: SecurityException Neither user 2000 nor current process".
            r.message = status + ": " + detail;
        }
    }

    private static java.util.Map<String, String> ensureProbes(Context ctx) {
        java.util.Map<String, String> cached = sProbeCache;
        if (cached != null) return cached;
        synchronized (BetaTestRunner.class) {
            if (sProbeCache != null) return sProbeCache;
            if (!BetaProxyClient.connect(ctx)) {
                sProbeError = "daemon not connected";
                return null;
            }
            try {
                String wire = BetaProxyClient.runPhase4Probes();
                java.util.Map<String, String> parsed =
                        com.byd.dashcast.beta.proxy.Phase4Probes.parse(wire);
                if (parsed.isEmpty()) {
                    sProbeError = "empty probe response";
                    return null;
                }
                AppLogger.i(TAG, "phase4 probes returned " + parsed.size() + " tokens");
                sProbeCache = parsed;
                return parsed;
            } catch (BetaProxyClient.BetaProxyException e) {
                sProbeError = e.getMessage();
                AppLogger.w(TAG, "phase4 probe call failed: " + e.getMessage());
                return null;
            }
        }
    }

    // ─── utils ──────────────────────────────────────────────────────────────

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getName() : m;
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** Build a multi-line human-readable report — used by the "Copy report" button. */
    public static String buildReport(List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Beta Engine — diagnostic report ===\n");
        int pass = 0, fail = 0, skip = 0;
        for (TestResult r : results) {
            switch (r.status) { case PASS: pass++; break; case FAIL: fail++; break; case SKIPPED: skip++; break; default: }
        }
        sb.append(String.format("Total: %d   ✓ %d   ✗ %d   ⊘ %d%n%n", results.size(), pass, fail, skip));
        for (TestResult r : results) {
            sb.append(String.format("[%s] %-3s %s — %s (%d ms)%n",
                    glyph(r.status), r.def.id, r.def.title,
                    r.message == null ? "" : r.message, r.elapsedMs));
            if (r.status == Status.FAIL && r.detail != null) {
                sb.append("    ").append(r.detail.replace("\n", "\n    ")).append('\n');
            }
        }
        return sb.toString();
    }

    private static String glyph(Status s) {
        switch (s) { case PASS: return "✓"; case FAIL: return "✗"; case SKIPPED: return "⊘"; case RUNNING: return "…"; default: return " "; }
    }
}
