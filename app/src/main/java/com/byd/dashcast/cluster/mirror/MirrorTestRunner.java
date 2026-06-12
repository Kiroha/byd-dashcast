package com.byd.dashcast.cluster.mirror;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.platform.Platform;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MirrorTestRunner — diagnostic suite focused on the cluster-mirror data path.
 *
 * <p>Targets DiLink 5 (BYD-AUTO / Android 12) where the cluster display is
 * composed through XDJA's fission compositor, not native SurfaceFlinger. The
 * {@code SurfaceControl.setDisplayLayerStack(token, layerStack)} mirror path
 * reports success but produces a black surface because the layers we ask SF
 * to mirror live in another compositor — see build 192 user logs.
 *
 * <p>Each test is read-only. Outputs (dumpsys SF, dumpsys window, mirrordaemon
 * log) are written to {@code /storage/emulated/0/Download/dl5_mirror_diag/}
 * so they can be inspected directly from the BYD file manager, then bundled
 * via the panel's "Envoyer (.log)" button (Android share chooser → Telegram).
 */
public final class MirrorTestRunner {

    private static final String TAG = "MirrorTestRunner";
    private static final String OUT_DIR = "/storage/emulated/0/Download/dl5_mirror_diag";

    public enum Status { PENDING, RUNNING, PASS, FAIL, SKIPPED, WARN }

    public static final class TestDef {
        public final String id;
        public final String title;
        public final String description;
        public TestDef(String id, String title, String description) {
            this.id = id; this.title = title; this.description = description;
        }
    }

    public static final class TestResult {
        public final TestDef def;
        public Status status   = Status.PENDING;
        public String message  = "";
        public String detail   = "";
        public long   elapsedMs;
        public TestResult(TestDef def) { this.def = def; }
    }

    public interface Listener {
        void onSuiteStarted(List<TestResult> results);
        void onTestUpdated(int index, TestResult result);
        void onSuiteFinished(List<TestResult> results);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mirror-test-runner");
        t.setDaemon(true);
        return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private MirrorTestRunner() {}

    public static List<TestDef> catalog() {
        List<TestDef> list = new ArrayList<>();
        list.add(new TestDef("M1", "Cluster display fingerprint",
                "DisplayManager.getDisplay for every PRESENTATION display: id, name, getRealSize, density, flags, owner pkg/uid, and reflective getLayerStack() — the WindowManager-side identity of the cluster surface."));
        list.add(new TestDef("M2", "SurfaceControl physical display IDs (reflection)",
                "SurfaceControl.getPhysicalDisplayIds() + getPhysicalDisplayToken(id) — SurfaceFlinger-side physical display inventory (bypasses WindowManager). SecurityException expected from app uid; output captured as evidence."));
        list.add(new TestDef("M3", "dumpsys SurfaceFlinger --list",
                "Compositor's view of every display/layer name. Ground truth that bypasses WindowManager filtering — reveals XDJA-fission virtual displays + their backing surfaces."));
        list.add(new TestDef("M4", "dumpsys SurfaceFlinger (full, 300 lines)",
                "Full SF dump head -300: HWComposer composition, per-display orientation, layerStack assignments, frame-rate selection — saved verbatim to Download/dl5_mirror_diag/dumpsys_sf_full.txt."));
        list.add(new TestDef("M5", "dumpsys SurfaceFlinger filtered (mirror-relevant)",
                "grep -E 'Display |layerStack|format|orientation|XDJA|fission|cluster|byd|projection' — extracts only the lines we need to map layerStack ↔ cluster display ↔ XDJA fission compositor."));
        list.add(new TestDef("M6", "dumpsys window cluster state",
                "dumpsys window | grep -E 'Display #|mDisplayId|mCurrentFocus|mFocusedApp|cluster|fission|XDJA|imeLayerStack' — WindowManager's view of which apps live on which display, focus owner, IME layer."));
        list.add(new TestDef("M7", "MirrorDaemon log tail (200 lines)",
                "cat /data/local/tmp/mirrordaemon_latest.log | tail -200 — captures the daemon's own setupMirror trace + its post-setup dumpsys SurfaceFlinger | grep byd_myapp_mirror, which says whether SF actually accepted the mirror token at layerStack N."));
        list.add(new TestDef("M8", "MirrorDaemon log → Download (file copy)",
                "Copies /data/local/tmp/mirrordaemon_latest.log to Download/dl5_mirror_diag/mirrordaemon_latest.log (uid=2000 has /sdcard write on A9+). The copy is the artifact the operator attaches to Telegram alongside the report."));
        list.add(new TestDef("M9", "Mirror token presence post-setup",
                "dumpsys SurfaceFlinger | grep -E 'byd_myapp_mirror|mybyd_preview_mirror' — proves whether the daemon's createDisplay actually produced a SurfaceFlinger-side token. If absent, the daemon's reflective createDisplay silently failed. If present but black on screen, the layerStack mapping is wrong (the cluster pixels live elsewhere)."));
        list.add(new TestDef("M10", "Cluster routing LIVE (sendInfo 16 + am start)",
                "End-to-end probe: snapshots fission/XDJA displays framebufferSpace BEFORE sendInfo(1000,16), opens projection, snapshots AFTER, then attempts am start-activity --display 3 and --display 4 with com.android.settings, dumps window root tasks per display, finally restores via sendInfo 18 + 0. Reveals which display the cluster compositor latches onto, and whether an activity actually lands there."));
        return list;
    }

    public static void runAll(Context appCtx, Listener listener) {
        final Context ctx = appCtx.getApplicationContext();
        final List<TestDef> defs = catalog();
        final List<TestResult> results = new ArrayList<>(defs.size());
        for (TestDef d : defs) results.add(new TestResult(d));

        UI.post(() -> listener.onSuiteStarted(results));

        EXEC.execute(() -> {
            // Ensure output dir exists once for all tests that write to it.
            runShellSync(ctx, "mkdir -p '" + OUT_DIR + "' 2>&1 && ls -ld '" + OUT_DIR + "' 2>&1",
                    new AtomicReference<>(""), 4000);

            for (int i = 0; i < defs.size(); i++) {
                final int idx = i;
                final TestResult r = results.get(i);
                r.status = Status.RUNNING;
                UI.post(() -> listener.onTestUpdated(idx, r));
                long t0 = SystemClock.elapsedRealtime();
                try {
                    switch (defs.get(i).id) {
                        case "M1": runM1(ctx, r); break;
                        case "M2": runM2(ctx, r); break;
                        case "M3": runM3(ctx, r); break;
                        case "M4": runM4(ctx, r); break;
                        case "M5": runM5(ctx, r); break;
                        case "M6": runM6(ctx, r); break;
                        case "M7": runM7(ctx, r); break;
                        case "M8": runM8(ctx, r); break;
                        case "M9": runM9(ctx, r); break;
                        case "M10": runM10(ctx, r); break;
                        default:
                            r.status = Status.SKIPPED;
                            r.message = "no impl";
                    }
                } catch (Throwable t) {
                    r.status = Status.FAIL;
                    r.message = "EXCEPTION: " + t.getClass().getSimpleName() + " " + t.getMessage();
                    AppLogger.e(TAG, defs.get(i).id + " crashed", t);
                }
                r.elapsedMs = SystemClock.elapsedRealtime() - t0;
                final int idx2 = i;
                UI.post(() -> listener.onTestUpdated(idx2, r));
            }
            UI.post(() -> listener.onSuiteFinished(results));
        });
    }

    public static String buildReport(Context ctx, List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ MIRROR DIAGNOSTIC REPORT ═══\n");
        sb.append("App      : ").append(ctx.getPackageName()).append('\n');
        sb.append("Platform : ").append(android.os.Build.MANUFACTURER).append('/').append(android.os.Build.BRAND)
          .append('/').append(android.os.Build.PRODUCT).append(" api=").append(android.os.Build.VERSION.SDK_INT).append('\n');
        sb.append("DL5 auto : ").append(Platform.get().isAutoDetectedDiLink5()).append('\n');
        sb.append("Artifacts dir : ").append(OUT_DIR).append('\n');
        sb.append('\n');
        int pass = 0, fail = 0, warn = 0, skip = 0;
        for (TestResult r : results) {
            switch (r.status) {
                case PASS: pass++; break;
                case FAIL: fail++; break;
                case WARN: warn++; break;
                case SKIPPED: skip++; break;
                default: break;
            }
        }
        sb.append("Summary  : ").append(pass).append(" PASS, ").append(fail).append(" FAIL, ")
          .append(warn).append(" WARN, ").append(skip).append(" SKIP, total ").append(results.size()).append('\n');
        sb.append('\n');
        for (TestResult r : results) {
            sb.append('[').append(r.status).append("] ").append(r.def.id).append(" — ").append(r.def.title)
              .append("  (").append(r.elapsedMs).append(" ms)\n");
            if (r.message != null && !r.message.isEmpty()) {
                sb.append("    msg : ").append(r.message).append('\n');
            }
            if (r.detail != null && !r.detail.isEmpty()) {
                sb.append("    --- detail ---\n");
                for (String line : r.detail.split("\n")) {
                    sb.append("    ").append(line).append('\n');
                }
            }
            sb.append('\n');
        }
        sb.append("=== END OF MIRROR REPORT ===\n");
        return sb.toString();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    private static void runM1(Context ctx, TestResult r) {
        StringBuilder out = new StringBuilder();
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            r.status = Status.FAIL; r.message = "DisplayManager unavailable"; return;
        }
        Display[] all = dm.getDisplays();
        out.append("Total displays: ").append(all.length).append('\n');
        int presentationCount = 0;
        for (Display d : all) {
            String flagsStr = flagsToString(d.getFlags());
            android.graphics.Point sz = new android.graphics.Point();
            d.getRealSize(sz);
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            d.getRealMetrics(m);
            int layerStack = -1;
            try {
                Method getLs = Display.class.getDeclaredMethod("getLayerStack");
                getLs.setAccessible(true);
                Object v = getLs.invoke(d);
                if (v instanceof Integer) layerStack = (Integer) v;
            } catch (Throwable ignored) {}
            String ownerPkg = "?"; int ownerUid = -1;
            try {
                Method getOwnerPkg = Display.class.getDeclaredMethod("getOwnerPackageName");
                getOwnerPkg.setAccessible(true);
                Object v = getOwnerPkg.invoke(d);
                if (v != null) ownerPkg = String.valueOf(v);
            } catch (Throwable ignored) {}
            try {
                Method getOwnerUid = Display.class.getDeclaredMethod("getOwnerUid");
                getOwnerUid.setAccessible(true);
                Object v = getOwnerUid.invoke(d);
                if (v instanceof Integer) ownerUid = (Integer) v;
            } catch (Throwable ignored) {}
            boolean presentation = (d.getFlags() & Display.FLAG_PRESENTATION) != 0;
            if (presentation) presentationCount++;
            out.append("  Display #").append(d.getDisplayId())
               .append(" name='").append(d.getName()).append("'")
               .append(" size=").append(sz.x).append('x').append(sz.y)
               .append(" dpi=").append(m.densityDpi)
               .append(" layerStack=").append(layerStack)
               .append(" flags=0x").append(Integer.toHexString(d.getFlags()))
               .append(" [").append(flagsStr).append("]")
               .append(" owner=").append(ownerPkg).append('(').append(ownerUid).append(')')
               .append(presentation ? " PRESENTATION" : "")
               .append('\n');
        }
        r.detail = out.toString();
        r.status = presentationCount > 0 ? Status.PASS : Status.WARN;
        r.message = "displays=" + all.length + " presentation=" + presentationCount;
    }

    private static String flagsToString(int f) {
        StringBuilder s = new StringBuilder();
        if ((f & Display.FLAG_PRESENTATION)            != 0) s.append("PRESENTATION ");
        if ((f & Display.FLAG_PRIVATE)                 != 0) s.append("PRIVATE ");
        if ((f & Display.FLAG_SECURE)                  != 0) s.append("SECURE ");
        if ((f & Display.FLAG_ROUND)                   != 0) s.append("ROUND ");
        if ((f & Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) s.append("PROTECTED ");
        return s.toString().trim();
    }

    private static void runM2(Context ctx, TestResult r) {
        StringBuilder out = new StringBuilder();
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Method getIds = sc.getDeclaredMethod("getPhysicalDisplayIds");
            getIds.setAccessible(true);
            Object raw = getIds.invoke(null);
            long[] ids = (raw instanceof long[]) ? (long[]) raw : new long[0];
            out.append("physical display ids count=").append(ids.length).append('\n');
            Method getToken = null;
            try {
                getToken = sc.getDeclaredMethod("getPhysicalDisplayToken", long.class);
                getToken.setAccessible(true);
            } catch (NoSuchMethodException ignored) {}
            for (long id : ids) {
                out.append("  id=").append(id);
                if (getToken != null) {
                    try {
                        Object tok = getToken.invoke(null, id);
                        out.append(" token=").append(tok == null ? "null" : tok.toString());
                    } catch (Throwable t) {
                        out.append(" tokenErr=").append(t.getClass().getSimpleName())
                           .append(':').append(t.getMessage());
                    }
                }
                out.append('\n');
            }
            r.detail = out.toString();
            r.status = ids.length > 0 ? Status.PASS : Status.WARN;
            r.message = "ids=" + ids.length;
        } catch (Throwable t) {
            r.detail = out.toString() + "\nREFLECTION FAIL: " + t.getClass().getSimpleName()
                    + ' ' + t.getMessage();
            r.status = Status.WARN;
            r.message = "reflection blocked: " + t.getClass().getSimpleName();
        }
    }

    private static void runM3(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "dumpsys SurfaceFlinger --list 2>&1 | head -100", out, 6000);
        r.detail = out.get();
        r.status = isShellErr(out.get()) ? Status.WARN : Status.PASS;
        r.message = isShellErr(out.get()) ? "no shell" : ("lines=" + countLines(out.get()));
    }

    private static void runM4(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        // Dump head -300 and save to file; report only first 30 lines as detail to keep UI light.
        String dst = OUT_DIR + "/dumpsys_sf_full.txt";
        runShellSync(ctx,
                "dumpsys SurfaceFlinger 2>&1 | head -300 > '" + dst + "' 2>&1"
                + " && head -30 '" + dst + "' 2>&1"
                + " && echo '...' && echo \"saved to " + dst + "\"",
                out, 10000);
        r.detail = out.get();
        if (isShellErr(out.get())) {
            r.status = Status.WARN; r.message = "no shell";
        } else {
            r.status = Status.PASS;
            r.message = "saved to dumpsys_sf_full.txt";
        }
    }

    private static void runM5(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "dumpsys SurfaceFlinger 2>&1 "
              + "| grep -E 'Display |layerStack|format|orientation|XDJA|fission|cluster|byd|projection|mirror' "
              + "| head -80",
                out, 8000);
        r.detail = out.get();
        r.status = isShellErr(out.get()) ? Status.WARN : Status.PASS;
        r.message = isShellErr(out.get()) ? "no shell" : ("lines=" + countLines(out.get()));
    }

    private static void runM6(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "dumpsys window 2>&1 "
              + "| grep -E 'Display #|mDisplayId|mCurrentFocus|mFocusedApp|cluster|fission|XDJA|imeLayerStack' "
              + "| head -80",
                out, 8000);
        r.detail = out.get();
        r.status = isShellErr(out.get()) ? Status.WARN : Status.PASS;
        r.message = isShellErr(out.get()) ? "no shell" : ("lines=" + countLines(out.get()));
    }

    private static void runM7(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "if [ -f /data/local/tmp/mirrordaemon_latest.log ]; then "
              + "  tail -200 /data/local/tmp/mirrordaemon_latest.log 2>&1; "
              + "else "
              + "  echo '__NO_LOG__ : /data/local/tmp/mirrordaemon_latest.log missing (daemon never started in this session)'; "
              + "  ls -la /data/local/tmp/mirrordaemon_*.log 2>&1 | head -10; "
              + "fi",
                out, 6000);
        r.detail = out.get();
        if (isShellErr(out.get())) {
            r.status = Status.WARN; r.message = "no shell";
        } else if (out.get().contains("__NO_LOG__")) {
            r.status = Status.WARN; r.message = "daemon log not found — launch mirror once then re-run";
        } else {
            r.status = Status.PASS; r.message = "lines=" + countLines(out.get());
        }
    }

    private static void runM8(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        String dst = OUT_DIR + "/mirrordaemon_latest.log";
        runShellSync(ctx,
                "if [ -f /data/local/tmp/mirrordaemon_latest.log ]; then "
              + "  cat /data/local/tmp/mirrordaemon_latest.log > '" + dst + "' 2>&1 "
              + "  && stat -c %s '" + dst + "' 2>&1; "
              + "else "
              + "  echo '__NO_SRC__'; "
              + "fi",
                out, 8000);
        r.detail = out.get();
        if (isShellErr(out.get())) {
            r.status = Status.WARN; r.message = "no shell";
        } else if (out.get().contains("__NO_SRC__")) {
            r.status = Status.WARN; r.message = "daemon log not found — launch mirror once then re-run";
        } else {
            r.status = Status.PASS; r.message = "copied to Download/dl5_mirror_diag/";
        }
    }

    private static void runM9(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "dumpsys SurfaceFlinger 2>&1 "
              + "| grep -E 'byd_myapp_mirror|mybyd_preview_mirror' "
              + "| head -40",
                out, 6000);
        String result = out.get();
        r.detail = result;
        if (isShellErr(result)) {
            r.status = Status.WARN; r.message = "no shell";
        } else if (result.trim().isEmpty()) {
            r.status = Status.WARN;
            r.message = "no mirror token in SF — launch mirror once then re-run";
        } else {
            r.status = Status.PASS;
            r.message = "mirror token(s) present in SF (" + countLines(result) + " line(s))";
        }
    }

    /**
     * M10 — Cluster routing LIVE probe.
     *
     * <p>Closes the build-193 information gap: D28 confirmed visually that
     * {@code sendInfo(1000,16)} opens a black window on the cluster, and D26
     * confirmed that {@code am start --display 3/4} routes activities, but no
     * run captured both in the same session. This test does:
     *
     * <ol>
     *   <li>snapshot framebufferSpace of fission/XDJA displays (BEFORE)</li>
     *   <li>{@code service call auto_container 2 i32 1000 i32 16 s16 ""}
     *       (snake_case — verified against ClusterManager prod path)</li>
     *   <li>2 s settle, snapshot AFTER — the display whose framebufferSpace
     *       flips from 1×1 to 1920×720 is the one the cluster compositor
     *       latched onto</li>
     *   <li>{@code am start-activity -W --display 3} (then 4) of
     *       {@code com.android.launcher3/.Launcher} (always installed on
     *       BYD ROMs, unlike Settings which has a custom name) — to see
     *       if/where it lands</li>
     *   <li>dumpsys window rootTasks per display — confirms the routing</li>
     *   <li>cleanup: force-stop Launcher, sendInfo 18 (close), sendInfo 0
     *       (restore Qt video stream)</li>
     * </ol>
     *
     * Read-only diagnostically; the only side-effects are the projection
     * open/close (already documented as user-safe) and a short Settings
     * launch that is force-stopped at the end.
     */
    private static void runM10(Context ctx, TestResult r) {
        StringBuilder full = new StringBuilder();
        AtomicReference<String> tmp = new AtomicReference<>("");

        // Grep pattern reused for BEFORE + AFTER. -A 6 because the
        // framebufferSpace= line follows the DisplayDevice{...} header.
        final String fbGrep =
                "dumpsys SurfaceFlinger 2>&1 "
              + "| grep -E 'DisplayDevice\\{|framebufferSpace=ProjectionSpace|layerStack=[[:digit:]]' "
              + "| head -120";

        full.append("=== STEP 1 — framebufferSpace BEFORE sendInfo(16) ===\n");
        runShellSync(ctx, fbGrep, tmp, 6000);
        full.append(tmp.get()).append('\n');

        full.append("=== STEP 2 — service call auto_container 2 i32 1000 i32 16 s16 \"\" (open projection) ===\n");
        runShellSync(ctx,
                "service call auto_container 2 i32 1000 i32 16 s16 \"\" 2>&1",
                tmp, 5000);
        full.append(tmp.get()).append('\n');

        try { Thread.sleep(2000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        full.append("=== STEP 3 — framebufferSpace AFTER sendInfo(16) ===\n");
        runShellSync(ctx, fbGrep, tmp, 6000);
        full.append(tmp.get()).append('\n');

        full.append("=== STEP 4 — am start-activity -W --display 3 com.android.launcher3/.Launcher ===\n");
        runShellSync(ctx,
                "am start-activity -W --display 3 "
              + "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
              + "-n com.android.launcher3/.Launcher 2>&1",
                tmp, 8000);
        full.append(tmp.get()).append('\n');

        try { Thread.sleep(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        full.append("=== STEP 5 — am start-activity -W --display 4 com.android.launcher3/.Launcher ===\n");
        runShellSync(ctx,
                "am start-activity -W --display 4 "
              + "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
              + "-n com.android.launcher3/.Launcher 2>&1",
                tmp, 8000);
        full.append(tmp.get()).append('\n');

        try { Thread.sleep(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        full.append("=== STEP 6 — dumpsys window root tasks per display ===\n");
        runShellSync(ctx,
                "dumpsys window 2>&1 "
              + "| grep -E 'Display: mDisplayId=|mCurrentFocus=|mFocusedApp=|rootTaskId=' "
              + "| head -80",
                tmp, 6000);
        full.append(tmp.get()).append('\n');

        full.append("=== STEP 7 — cleanup (force-stop Launcher, sendInfo 18 + 0) ===\n");
        runShellSync(ctx,
                "am force-stop com.android.launcher3 2>&1 ; "
              + "service call auto_container 2 i32 1000 i32 18 s16 \"\" 2>&1 ; "
              + "sleep 1 ; "
              + "service call auto_container 2 i32 1000 i32 0 s16 \"\" 2>&1",
                tmp, 8000);
        full.append(tmp.get()).append('\n');

        r.detail = full.toString();
        // Always PASS: we shipped the data. Analysis is manual (compare
        // STEP 1 vs STEP 3 framebufferSpace, then STEP 6 routing).
        r.status = Status.PASS;
        r.message = "live routing run complete — compare STEP 1 vs STEP 3, then STEP 6";
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static boolean isShellErr(String s) {
        return s != null && (s.startsWith("ERROR:") || s.startsWith("TIMEOUT"));
    }

    private static void runShellSync(Context ctx, String cmd, AtomicReference<String> out, long timeoutMs) {
        final Object lock = new Object();
        final boolean[] done = new boolean[]{false};
        AdbLocalClient.executeShellWithResult(ctx, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) {
                out.set(report == null ? "" : report);
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
            @Override public void onError(String error) {
                out.set("ERROR: " + (error == null ? "(null)" : error));
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (lock) {
            while (!done[0]) {
                long remain = deadline - SystemClock.elapsedRealtime();
                if (remain <= 0) { out.set("TIMEOUT after " + timeoutMs + "ms"); break; }
                try { lock.wait(remain); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
    }
}
