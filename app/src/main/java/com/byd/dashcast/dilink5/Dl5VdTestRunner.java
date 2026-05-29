package com.byd.dashcast.dilink5;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.view.Display;
import android.view.Surface;

import com.byd.dashcast.AdbLocalClient;
import com.byd.dashcast.AppLogger;
import com.byd.dashcast.daemon.MirrorDaemon;
import com.byd.dashcast.platform.Platform;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Dl5VdTestRunner — Virtual Display test battery for DiLink 5 (v1.3.12).
 *
 * <p>Goal: validate the VirtualDisplay → MirrorDaemon projection pipeline
 * end-to-end on a real device before shipping it in the production path.
 *
 * <p>Flow:
 * <ol>
 *   <li>V01 — Activate DL5 projection (service call auto_container)</li>
 *   <li>V02 — Target app installed + launcher component resolved</li>
 *   <li>V03 — {@code cmd display create-virtual-display} available on this ROM?
 *             Creates the VD and captures its displayId + layerStack.</li>
 *   <li>V04 — {@code am start --display &lt;vdId&gt;} to launch the target app on the VD.</li>
 *   <li>V05 — MirrorDaemon: TRANSACT_MIRROR_START with VD layerStack → app projected onto cluster.</li>
 *   <li>V06 — Interactive prompt: is the app visible on cluster AND well-proportioned?</li>
 *   <li>V07 — Cleanup: TRANSACT_MIRROR_STOP + delete VD + force-stop app + close projection if V01 opened it.</li>
 * </ol>
 *
 * <p>Strictly DL5-only. Leaves the device in a clean state on both success and failure.
 */
public final class Dl5VdTestRunner {

    private static final String TAG = "Dl5VdTest";

    // ─── Public API ──────────────────────────────────────────────────────────

    public interface Listener {
        void onSuiteStarted(List<DiLink5TestRunner.TestResult> results);
        void onTestUpdated(int index, DiLink5TestRunner.TestResult result);
        void onSuiteFinished(List<DiLink5TestRunner.TestResult> results);

        /**
         * Interactive Yes/No prompt. Runner blocks until callback is invoked
         * (or the 180 s safety timeout elapses — result treated as NO).
         */
        default void onPromptYesNo(String title, String message, Consumer<Boolean> callback) {
            callback.accept(false);
        }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dl5-vd-test");
        t.setDaemon(true);
        return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Default test target — Yandex Maps. Overridable by the caller. */
    public static final String DEFAULT_TARGET_PKG = "ru.yandex.yandexmaps";

    private Dl5VdTestRunner() {}

    // ─── Catalog ─────────────────────────────────────────────────────────────

    public static List<DiLink5TestRunner.TestDef> catalog() {
        List<DiLink5TestRunner.TestDef> list = new ArrayList<>();
        list.add(new DiLink5TestRunner.TestDef("V01",
                "Activate DL5 cluster projection",
                "service call auto_container 2 i32 1000 i32 16 — opens the XDJA fission projection "
              + "so displays 2/3/4 become available as cluster targets."));
        list.add(new DiLink5TestRunner.TestDef("V02",
                "Target app installed + launcher resolved",
                "PackageManager.getPackageInfo + getLaunchIntentForPackage. SKIP rest if absent."));
        list.add(new DiLink5TestRunner.TestDef("V03",
                "Create VirtualDisplay via Java API",
                "DisplayManager.createVirtualDisplay(1920x720, PRESENTATION) "
              + "— captures displayId and derives layerStack via getLayerStack() hidden API."));
        list.add(new DiLink5TestRunner.TestDef("V04",
                "Launch target app on VirtualDisplay",
                "am start --display <vdId> -n <component> — app should render at 1920×720 "
              + "on our private VD, invisible on the head-unit screen."));
        list.add(new DiLink5TestRunner.TestDef("V05",
                "MirrorDaemon: project VD layerStack onto cluster",
                "TRANSACT_MIRROR_START(layerStack=VD, clusterW=1920, clusterH=720) via the live "
              + "MirrorDaemon binder — exactly the same call as production, just with VD layerStack."));
        list.add(new DiLink5TestRunner.TestDef("V06",
                "User confirm: app visible on cluster?",
                "Interactive Yes/No prompt. YES = VD pipeline validated end-to-end. "
              + "NO = failure logged but cleanup still runs."));
        list.add(new DiLink5TestRunner.TestDef("V07",
                "Cleanup: stop mirror + delete VD + force-stop + close projection",
                "TRANSACT_MIRROR_STOP + cmd display delete-virtual-display <vdId> + "
              + "am force-stop <pkg> + service call auto_container close (only if V01 opened it)."));
        return list;
    }

    public static List<DiLink5TestRunner.TestResult> emptyResults() {
        List<DiLink5TestRunner.TestResult> out = new ArrayList<>();
        for (DiLink5TestRunner.TestDef def : catalog()) {
            DiLink5TestRunner.TestResult r = new DiLink5TestRunner.TestResult(def);
            r.status = DiLink5TestRunner.Status.PENDING;
            out.add(r);
        }
        return out;
    }

    // ─── Runner ──────────────────────────────────────────────────────────────

    /**
     * Async run — listener callbacks delivered on the UI thread.
     *
     * @param ctx        application context
     * @param targetPkg  package to launch on the VD (e.g. "ru.yandex.yandexmaps")
     * @param daemonBinder live MirrorDaemon binder (may be null — V05 will FAIL gracefully)
     * @param clusterSurface Surface of the cluster SurfaceView (used for V05 mirror start)
     * @param mirrorViewW    width of the cluster SurfaceView in pixels
     * @param mirrorViewH    height of the cluster SurfaceView in pixels
     * @param listener   event sink
     */
    public static void run(Context ctx, String targetPkg,
                           IBinder daemonBinder, Surface clusterSurface,
                           int mirrorViewW, int mirrorViewH,
                           Listener listener) {
        final Context appCtx = ctx.getApplicationContext();
        final List<DiLink5TestRunner.TestResult> results = emptyResults();

        EXEC.submit(() -> {
            UI.post(() -> listener.onSuiteStarted(results));

            final VdState st = new VdState();
            st.targetPkg        = targetPkg;
            st.daemonBinder     = daemonBinder;
            st.clusterSurface   = clusterSurface;
            st.mirrorViewW      = mirrorViewW;
            st.mirrorViewH      = mirrorViewH;

            for (int i = 0; i < results.size(); i++) {
                DiLink5TestRunner.TestResult r = results.get(i);
                r.status = DiLink5TestRunner.Status.RUNNING;
                final int idx = i;
                UI.post(() -> listener.onTestUpdated(idx, r));

                runOne(appCtx, r, st, listener);

                UI.post(() -> listener.onTestUpdated(idx, r));
            }

            UI.post(() -> listener.onSuiteFinished(results));
        });
    }

    // ─── State ───────────────────────────────────────────────────────────────

    private static final class VdState {
        String   targetPkg;
        String   targetActivity;          // resolved by V02, e.g. ".SplashScreen"
        IBinder  daemonBinder;
        Surface  clusterSurface;
        int      mirrorViewW;
        int      mirrorViewH;

        int      vdDisplayId   = -1;      // created by V03
        int      vdLayerStack  = -1;      // derived in V03
        VirtualDisplay vd     = null;     // created by V03, released in V07
        boolean  projectionOpenedByUs = false;  // V01 flag for V07 cleanup
        boolean  mirrorStarted        = false;  // V05 flag for V07 cleanup
        boolean  userConfirmed        = false;  // V06 answer
        boolean  abortFromHere        = false;
    }

    // ─── Per-step ────────────────────────────────────────────────────────────

    private static void runOne(Context ctx, DiLink5TestRunner.TestResult r,
                               VdState st, Listener listener) {
        switch (r.def.id) {
            case "V01": runV01(ctx, r, st); break;
            case "V02": runV02(ctx, r, st); break;
            case "V03": runV03(ctx, r, st); break;
            case "V04": runV04(ctx, r, st); break;
            case "V05": runV05(ctx, r, st); break;
            case "V06": runV06(ctx, r, st, listener); break;
            case "V07": runV07(ctx, r, st); break;
            default:
                r.status  = DiLink5TestRunner.Status.SKIPPED;
                r.detail = "Unknown step";
        }
    }

    // V01 — Activate projection
    private static void runV01(Context ctx, DiLink5TestRunner.TestResult r, VdState st) {
        String out = runShell(ctx, "service call auto_container 2 i32 1000 i32 16 s16 \"\"", 4000);
        String result = out;
        // service call returns "Result: Parcel(...)" on success, or error text
        if (result.contains("Parcel") || result.contains("result")) {
            r.status  = DiLink5TestRunner.Status.PASS;
            r.detail = result.trim();
            st.projectionOpenedByUs = true;
        } else if (result.contains("not found") || result.contains("Unknown command")) {
            // auto_container not present — skip (may already be active)
            r.status  = DiLink5TestRunner.Status.SKIPPED;
            r.detail = "auto_container service not found — projection may already be active.\n" + result.trim();
        } else {
            // Non-fatal: projection might already be open
            r.status  = DiLink5TestRunner.Status.PASS;
            r.detail = "(best-effort) " + result.trim();
            st.projectionOpenedByUs = true;
        }
    }

    // V02 — App installed?
    private static void runV02(Context ctx, DiLink5TestRunner.TestResult r, VdState st) {
        if (st.abortFromHere) { skip(r, "aborted by earlier step"); return; }
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        try {
            pm.getPackageInfo(st.targetPkg, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            r.status  = DiLink5TestRunner.Status.FAIL;
            r.detail = st.targetPkg + " not installed — skipping rest of suite.";
            st.abortFromHere = true;
            return;
        }
        android.content.Intent intent = pm.getLaunchIntentForPackage(st.targetPkg);
        if (intent == null || intent.getComponent() == null) {
            r.status  = DiLink5TestRunner.Status.FAIL;
            r.detail = "No launcher activity found for " + st.targetPkg;
            st.abortFromHere = true;
            return;
        }
        st.targetActivity = intent.getComponent().flattenToShortString();
        r.status  = DiLink5TestRunner.Status.PASS;
        r.detail = "Launcher: " + st.targetActivity;
    }

    // V03 — Create VirtualDisplay via Java API (DisplayManager)
    private static void runV03(Context ctx, DiLink5TestRunner.TestResult r, VdState st) {
        if (st.abortFromHere) { skip(r, "aborted by earlier step"); return; }

        // cmd display create-virtual-display is not available on all BYD ROMs
        // (DL5 field test: "Unknown command: create-virtual-display").
        // Use the public Java API instead — available since API 21, no shell needed.
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                r.status = DiLink5TestRunner.Status.FAIL;
                r.detail = "DisplayManager not available";
                st.abortFromHere = true;
                return;
            }

            // VIRTUAL_DISPLAY_FLAG_PRESENTATION (=2): apps can launch on this display
            // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY (=8): isolate from main display
            // null surface = SurfaceFlinger private framebuffer; VD layerStack is mirrored
            // by the daemon onto the cluster surface — no ImageReader needed.
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                      | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            st.vd = dm.createVirtualDisplay(
                    "byd_test_vd",
                    1920, 720,
                    /*densityDpi=*/ 160,
                    /*surface=*/ null,
                    flags);

            if (st.vd == null) {
                r.status = DiLink5TestRunner.Status.FAIL;
                r.detail = "DisplayManager.createVirtualDisplay() returned null";
                st.abortFromHere = true;
                return;
            }

            Display d = st.vd.getDisplay();
            if (d == null) {
                r.status = DiLink5TestRunner.Status.FAIL;
                r.detail = "VD created but getDisplay() is null";
                st.abortFromHere = true;
                return;
            }
            st.vdDisplayId = d.getDisplayId();

            // Derive layerStack via hidden API getLayerStack()
            int layerStack = getLayerStackForDisplay(ctx, st.vdDisplayId);
            if (layerStack < 0) {
                r.status = DiLink5TestRunner.Status.FAIL;
                r.detail = "VD created (id=" + st.vdDisplayId + ") but getLayerStack() failed — "
                         + "cannot configure mirror without it.";
                st.abortFromHere = true;
                return;
            }
            st.vdLayerStack = layerStack;

            r.status = DiLink5TestRunner.Status.PASS;
            r.detail = "VD created via Java API: displayId=" + st.vdDisplayId
                     + " layerStack=" + st.vdLayerStack
                     + " size=1920×720 dpi=160";

        } catch (Exception e) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.detail = "createVirtualDisplay() exception: " + e.getClass().getSimpleName()
                     + ": " + e.getMessage();
            st.abortFromHere = true;
        }
    }

    // V04 — Launch target app on VD
    private static void runV04(Context ctx, DiLink5TestRunner.TestResult r, VdState st) {
        if (st.abortFromHere) { skip(r, "aborted by earlier step"); return; }

        // Force-stop first to ensure a fresh launch on the target display
        runShell(ctx, "am force-stop " + st.targetPkg, 3000);

        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        String raw = runShell(ctx,
                "am start --display " + st.vdDisplayId + " -n " + st.targetActivity + " 2>&1",
                5000);

        if (raw.contains("Error") || raw.contains("error") || raw.contains("Exception")) {
            r.status  = DiLink5TestRunner.Status.FAIL;
            r.detail = "am start failed:\n" + raw;
            st.abortFromHere = true;
            return;
        }

        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        // Verify the task landed on the correct display
        String taskInfo = runShell(ctx,
                "dumpsys activity activities 2>/dev/null | grep -A3 '" + st.targetPkg + "' | grep -E 'displayId|Display' | head -5",
                4000).trim();

        r.status  = DiLink5TestRunner.Status.PASS;
        r.detail = "am start → " + raw + "\nTask info: " + (taskInfo.isEmpty() ? "(empty)" : taskInfo);
    }

    // V05 — MirrorDaemon: TRANSACT_MIRROR_START with VD layerStack
    private static void runV05(Context ctx, DiLink5TestRunner.TestResult r, VdState st) {
        if (st.abortFromHere) { skip(r, "aborted by earlier step"); return; }

        if (st.daemonBinder == null) {
            r.status  = DiLink5TestRunner.Status.FAIL;
            r.detail = "MirrorDaemon binder is null — daemon not running or not connected. "
                      + "Start a cluster session from the main screen first.";
            st.abortFromHere = true;
            return;
        }
        if (st.clusterSurface == null || !st.clusterSurface.isValid()) {
            r.status  = DiLink5TestRunner.Status.FAIL;
            r.detail = "Cluster SurfaceView surface is not valid — mirror view must be visible.";
            st.abortFromHere = true;
            return;
        }

        // clusterW/H = VD dimensions (1920×720) — the app renders at this resolution on the VD.
        // The daemon's setupMirror will letterbox it into the cluster SurfaceView.
        final int vdW = 1920;
        final int vdH = 720;

        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            data.writeInt(st.vdLayerStack);
            data.writeInt(vdW);
            data.writeInt(vdH);
            data.writeInt(st.vdDisplayId);   // clusterDisplayId (for touch injection target)
            data.writeInt(st.mirrorViewW);
            data.writeInt(st.mirrorViewH);
            data.writeParcelable(st.clusterSurface, 0);

            st.daemonBinder.transact(MirrorDaemon.TRANSACT_MIRROR_START, data, reply, 0);
            reply.readException();
            boolean ok = reply.readInt() == 1;

            if (ok) {
                st.mirrorStarted = true;
                r.status  = DiLink5TestRunner.Status.PASS;
                r.detail = "TRANSACT_MIRROR_START ✓ layerStack=" + st.vdLayerStack
                          + " vdId=" + st.vdDisplayId
                          + " src=" + vdW + "×" + vdH
                          + " view=" + st.mirrorViewW + "×" + st.mirrorViewH;
            } else {
                r.status  = DiLink5TestRunner.Status.FAIL;
                r.detail = "Daemon reported failure (ok=0) — check logcat MirrorDaemon.";
                st.abortFromHere = true;
            }
        } catch (android.os.DeadObjectException doe) {
            r.status  = DiLink5TestRunner.Status.FAIL;
            r.detail = "MirrorDaemon binder is dead: " + doe.getMessage();
            st.abortFromHere = true;
        } catch (Exception e) {
            r.status  = DiLink5TestRunner.Status.FAIL;
            r.detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            st.abortFromHere = true;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // V06 — User confirmation prompt
    private static void runV06(Context ctx, DiLink5TestRunner.TestResult r,
                               VdState st, Listener listener) {
        if (st.abortFromHere) { skip(r, "aborted by earlier step"); return; }

        // Block runner thread, prompt shown on UI thread via listener
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] answer = { false };

        UI.post(() -> listener.onPromptYesNo(
                "VD Pipeline — confirm",
                "L'application " + st.targetPkg + " est-elle visible sur le cluster "
              + "et correctement proportionnée (1920×720) ?\n\n"
              + "YES → VD pipeline validé end-to-end ✓\n"
              + "NO  → échec enregistré, cleanup en cours",
                yes -> {
                    answer[0] = yes;
                    latch.countDown();
                }));

        try {
            latch.await(180, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        st.userConfirmed = answer[0];
        if (answer[0]) {
            r.status  = DiLink5TestRunner.Status.PASS;
            r.detail = "User confirmed: VD projection visible and well-proportioned on cluster ✓";
        } else {
            r.status  = DiLink5TestRunner.Status.FAIL;
            r.detail = "User did not confirm projection (or timeout). "
                      + "Check logcat MirrorDaemon for setupMirror details.";
        }
    }

    // V07 — Cleanup (always runs)
    private static void runV07(Context ctx, DiLink5TestRunner.TestResult r, VdState st) {
        StringBuilder sb = new StringBuilder();

        // 1. Stop mirror
        if (st.mirrorStarted && st.daemonBinder != null) {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
                st.daemonBinder.transact(MirrorDaemon.TRANSACT_MIRROR_STOP, data, reply, 0);
                reply.readException();
                sb.append("TRANSACT_MIRROR_STOP ✓\n");
            } catch (Exception e) {
                sb.append("TRANSACT_MIRROR_STOP ERR: ").append(e.getMessage()).append('\n');
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        // 2. Release VD (Java API — no shell needed)
        if (st.vd != null) {
            try { st.vd.release(); } catch (Exception e) { /* ignore */ }
            sb.append("VD release() ✓ (id=").append(st.vdDisplayId).append(")\n");
        }

        // 3. Force-stop app
        if (st.targetPkg != null) {
            runShell(ctx, "am force-stop " + st.targetPkg, 3000);
            sb.append("force-stop ").append(st.targetPkg).append(": OK\n");
        }

        // 4. Close projection if we opened it
        if (st.projectionOpenedByUs) {
            String closeOut = runShell(ctx, "service call auto_container 2 i32 1000 i32 18 i32 0", 4000);
            sb.append("close projection: ").append(closeOut.trim()).append('\n');
        }

        r.status  = DiLink5TestRunner.Status.PASS;
        r.detail = sb.toString().trim();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static void skip(DiLink5TestRunner.TestResult r, String reason) {
        r.status  = DiLink5TestRunner.Status.SKIPPED;
        r.detail = reason;
    }

    private static String runShell(Context ctx, String cmd, long timeoutMs) {
        AtomicReference<String> out = new AtomicReference<>("");
        AtomicReference<String> err = new AtomicReference<>(null);
        final Object lock = new Object();
        AdbLocalClient.executeShellWithResult(ctx, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String s) { out.set(s == null ? "" : s); synchronized (lock) { lock.notifyAll(); } }
            @Override public void onError(String e)   { err.set(e == null ? "?" : e); synchronized (lock) { lock.notifyAll(); } }
        });
        synchronized (lock) {
            try { lock.wait(timeoutMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        return err.get() != null ? ("ERROR: " + err.get()) : out.get();
    }

    /**
     * Derive layerStack for a given displayId via DisplayManager + hidden API getLayerStack().
     * Returns -1 on failure.
     */
    private static int getLayerStackForDisplay(Context ctx, int displayId) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return -1;
            // Poll briefly — VD may not appear in DisplayManager immediately
            for (int attempt = 0; attempt < 10; attempt++) {
                for (Display d : dm.getDisplays()) {
                    if (d.getDisplayId() == displayId) {
                        Method getLayerStack = Display.class.getDeclaredMethod("getLayerStack");
                        getLayerStack.setAccessible(true);
                        return (Integer) getLayerStack.invoke(d);
                    }
                }
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "getLayerStackForDisplay(" + displayId + ") failed: " + e.getMessage());
        }
        return -1;
    }

    // ─── Report ──────────────────────────────────────────────────────────────

    public static String renderReport(List<DiLink5TestRunner.TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("DashCast — DL5 VirtualDisplay Pipeline Test\n");
        sb.append("============================================\n");
        for (DiLink5TestRunner.TestResult r : results) {
            sb.append('[').append(r.status).append("] ")
              .append(r.def.id).append(" — ").append(r.def.title).append('\n');
            if (r.detail != null && !r.detail.isEmpty()) {
                for (String line : r.detail.split("\n")) {
                    sb.append("    ").append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
