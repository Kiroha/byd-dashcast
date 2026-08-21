# Perf Patches — 2026-07-05

> ⚠️ **APPLIED — all eight are in the tree. Do not apply these diffs.**
> Checked one by one against the code, not inferred from the batch:
> P1 `Platform` private probe lock · P2 `resize-affordance-probe` off the UI thread ·
> P3 `platform-init` thread + memory shedding in `DashCastApp` · P4 `volatile mDestroyed`
> at `FloatingRemoteButton.java:112` · P5 the `ClusterCanvasView` label cache ·
> P6 the single picker executor — its target file `fission/FissionActivity.java` was deleted
> in `cac83e08`, and its successor `LayoutManagerActivity.java:66` already carries the pattern ·
> P7 the `WakeWordEngine` zero-copy ONNX read · P8 `android.enableJetifier=false`.
>
> These diffs carry frozen Git blob indices and target code that has since moved. Applying
> them fails, or worse, half-applies and duplicates the HUD coalescing or the `mDestroyed`
> guard on the most ANR-sensitive path in the project. Kept for the reasoning in each
> "Notes / verification" block.

Companion to [`2026-07-05-aaos-perf-audit.md`](2026-07-05-aaos-perf-audit.md). Full production-ready patch content for the 8 top-ranked performance targets. **Nothing here is applied to the build tree.** Several diffs were validated with `git apply --check` at generation time (noted per patch). Land in the batches recommended in the audit doc; the two HIGH ANR patches in [`../hardening/`](../hardening/) land ahead of these.

---

## Patch 1 — `platform/Platform.java` (rank #1) — one-shot resize probe + leak-free process handling

**Strategy:** Serialise the DL5 cluster-resize cache-miss under a dedicated monitor so the shell probe forks (and writes the sticky pref) at most once; rebuild the probe on `ProcessBuilder` with `redirectErrorStream` + guaranteed `destroyForcibly` and a bounded reader join so no daemon thread or pipe fd leaks on the timeout path.
**Fix risk:** LOW · **Validated:** `git apply --check` OK against HEAD blob `3060d95`.

```diff
diff --git a/app/src/main/java/com/byd/dashcast/platform/Platform.java b/app/src/main/java/com/byd/dashcast/platform/Platform.java
index 3060d95..6f9487f 100644
--- a/app/src/main/java/com/byd/dashcast/platform/Platform.java
+++ b/app/src/main/java/com/byd/dashcast/platform/Platform.java
@@ -56,6 +56,15 @@ public final class Platform {
     private static volatile Boolean sCachedIsDiLink5 = null;
     private static volatile Boolean sCachedClusterResizeSupported = null;
 
+    /**
+     * Guards the one-shot cluster-resize probe so the shell is forked (and the
+     * sticky pref written) at most once, even when the startup prime worker and a
+     * UI read race on a cold cache. A dedicated monitor — <b>not</b>
+     * {@code Platform.class} — so the sub-1.5s shell block can never stall a
+     * concurrent {@link #isDiLink5(Context)} cache-fill.
+     */
+    private static final Object sResizeProbeLock = new Object();
+
     /** Cached reflection handle for android.os.SystemProperties#get — resolved once. */
     private static volatile Method sCachedSysPropGet = null;
 
@@ -327,14 +336,22 @@ public final class Platform {
         if (!isDiLink5(ctx)) return true;
         Boolean cached = sCachedClusterResizeSupported;
         if (cached != null) return cached.booleanValue();
-        // Sticky pref takes precedence over a fresh probe (consistent across cold starts).
-        String sticky = prefs(ctx).getString(PREF_CLUSTER_RESIZE_SUPPORTED, null);
-        if ("yes".equals(sticky)) { sCachedClusterResizeSupported = Boolean.TRUE; return true; }
-        if ("no".equals(sticky))  { sCachedClusterResizeSupported = Boolean.FALSE; return false; }
-        boolean supported = probeSetTaskWindowingMode();
-        sCachedClusterResizeSupported = Boolean.valueOf(supported);
-        prefs(ctx).edit().putString(PREF_CLUSTER_RESIZE_SUPPORTED, supported ? "yes" : "no").apply();
-        return supported;
+        // Serialise the cache-miss path. Without this, two callers on a cold cache
+        // (the startup prime worker and a UI read) can BOTH fork the shell probe
+        // and BOTH write the sticky pref. Double-checked under a dedicated lock so
+        // only one shell is spawned and one prefs write occurs per process.
+        synchronized (sResizeProbeLock) {
+            cached = sCachedClusterResizeSupported;
+            if (cached != null) return cached.booleanValue();
+            // Sticky pref takes precedence over a fresh probe (consistent across cold starts).
+            String sticky = prefs(ctx).getString(PREF_CLUSTER_RESIZE_SUPPORTED, null);
+            if ("yes".equals(sticky)) { sCachedClusterResizeSupported = Boolean.TRUE; return true; }
+            if ("no".equals(sticky))  { sCachedClusterResizeSupported = Boolean.FALSE; return false; }
+            boolean supported = probeSetTaskWindowingMode();
+            sCachedClusterResizeSupported = Boolean.valueOf(supported);
+            prefs(ctx).edit().putString(PREF_CLUSTER_RESIZE_SUPPORTED, supported ? "yes" : "no").apply();
+            return supported;
+        }
     }
 
     /**
@@ -366,17 +383,24 @@ public final class Platform {
      */
     private static boolean probeSetTaskWindowingMode() {
         Process p = null;
+        Thread reader = null;
         try {
             // No taskId arg — we only care whether the verb itself is known.
             // The command will fail with "Bad arg" or similar on a healthy ROM
             // (which is exactly what we want — verb known ⇒ supported).
-            p = Runtime.getRuntime().exec(new String[]{
+            // redirectErrorStream(true) folds stderr into stdout so there is no
+            // second pipe fd to leak, and "cmd" prints "Unknown command" to stderr.
+            ProcessBuilder pb = new ProcessBuilder(
                 "sh", "-c",
-                "cmd activity set-task-windowing-mode 2>&1; echo __exit=$?"
-            });
+                "cmd activity set-task-windowing-mode 2>&1; echo __exit=$?");
+            pb.redirectErrorStream(true);
+            p = pb.start();
+            // stdin is unused — close the write-end immediately so we neither hold
+            // the fd open nor let the child block waiting on input.
+            try { p.getOutputStream().close(); } catch (Throwable ignore) {}
             final Process proc = p;
-            final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
-            Thread r = new Thread(new Runnable() {
+            final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(256);
+            reader = new Thread(new Runnable() {
                 @Override public void run() {
                     byte[] buf = new byte[1024];
                     try (java.io.InputStream is = proc.getInputStream()) {
@@ -388,14 +412,15 @@ public final class Platform {
                     } catch (Throwable ignore) {}
                 }
             }, "platform-probe-reader");
-            r.setDaemon(true);
-            r.start();
+            reader.setDaemon(true);
+            reader.start();
             boolean finished = p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
             if (!finished) {
-                try { p.destroyForcibly(); } catch (Throwable ignore) {}
-                return true;  // timeout → assume supported, don't downgrade UX
+                // timeout → assume supported, don't downgrade UX.
+                // Process kill + reader join happen in finally, so nothing leaks.
+                return true;
             }
-            r.join(200);
+            reader.join(200);
             // Decode only after join() — reader thread has stopped writing.
             String s = baos.toString();
             if (s.contains("Unknown command")) return false;
@@ -406,7 +431,18 @@ public final class Platform {
         } catch (Throwable t) {
             return true;
         } finally {
-            if (p != null) try { p.destroy(); } catch (Throwable ignore) {}
+            if (p != null) {
+                // destroyForcibly() closes the process streams, which unblocks the
+                // reader's is.read() so its bounded join() below returns promptly.
+                try { p.destroyForcibly(); } catch (Throwable ignore) {}
+            }
+            if (reader != null) {
+                // Bounded join so a wedged reader can never outlive the probe; it is
+                // a daemon, so even a missed join cannot keep the process alive.
+                try { reader.join(200); } catch (InterruptedException ie) {
+                    Thread.currentThread().interrupt();
+                }
+            }
         }
     }
```

**Notes / verification:** Uses a *private* lock (not `Platform.class`) so a concurrent first-ever `isDiLink5(Context)` cold-fill is never stalled. `isClusterTaskResizeSupported` must still not be called on the UI thread on a truly cold cache (unchanged contract — Patch 4 enforces the caller side). No R8 keep-rule needed. On-car (DL5): wipe `byd_app_prefs` → cold-launch → confirm exactly one `platform-probe-reader` thread appears and terminates, exactly one `platform_cluster_resize_supported` write lands, and `ps -A | grep set-task-windowing` shows no lingering child after ~2 s; relaunch and confirm the volatile+sticky cache short-circuits.

---

## Patch 2 — `MainActivity.kt` (rank #1/#5) — resize probe off-UI + deferred overlay start

**Strategy:** Resolve the DL5 resize probe on a short-lived daemon (UI defaults to SHOWN, hidden via a posted callback only if confirmed unsupported); defer the `FloatingRemoteButton` overlay `startService` to the decor view's post-first-traversal run queue using the application context.
**Fix risk:** LOW.

```diff
diff --git a/app/src/main/java/com/byd/dashcast/MainActivity.kt b/app/src/main/java/com/byd/dashcast/MainActivity.kt
index f482813..6428824 100644
--- a/app/src/main/java/com/byd/dashcast/MainActivity.kt
+++ b/app/src/main/java/com/byd/dashcast/MainActivity.kt
@@ -245,8 +245,18 @@ class MainActivity : AppCompatActivity(),
         // Receiver to retrieve the MirrorDaemon Binder (uid=2000)
         registerReceiver(mDaemonReadyReceiver, IntentFilter(MirrorDaemon.ACTION_DAEMON_READY))
 
-        // Floating 📺 mirror button — started once, visibility controlled by show()/hide()
-        startService(Intent(this, FloatingRemoteButton::class.java))
+        // Floating mirror button — started once, visibility controlled by show()/hide().
+        // Deferred to post-first-frame: FloatingRemoteButton.onStartCommand inflates a
+        // WindowManager overlay (addView) on the main looper and the badge starts View.GONE
+        // (revealed later through the sShouldBeVisible latch), so that overlay work must not
+        // compete with the launcher's first traversal. Posting on the decor view's run queue
+        // runs it after the first layout pass. The application context is used so the service
+        // is independent of this Activity's lifecycle, and onStartCommand is idempotent
+        // (early-returns once mFloatView is created), so the deferral cannot double-start it.
+        val floatingButtonCtx = applicationContext
+        window.decorView.post {
+            floatingButtonCtx.startService(Intent(floatingButtonCtx, FloatingRemoteButton::class.java))
+        }
 
         // Handle a tap on the floating button when the Activity is already alive.
         handleShowMirrorIntent(intent)
@@ -1479,11 +1489,31 @@ class MainActivity : AppCompatActivity(),
             this
         )
 
-        // DL5 guard: hide resize affordance if task resize is unsupported on this ROM.
-        if (!Platform.get().isClusterTaskResizeSupported(this)) {
-            mClusterControlCoordinator?.hideResizeIfUnsupported()
-            AppLogger.i(TAG, "Resize UI hidden: cluster task resize not supported on this ROM (DL5)")
-        }
+        // DL5 guard: hide the resize affordance if task resize is unsupported on this ROM.
+        // isClusterTaskResizeSupported forks a shell on a cold cache (Platform.probeSetTaskWindowingMode,
+        // ~200 ms typical, bounded at 1500 ms) and must NEVER be resolved on the UI thread — on a
+        // first-launch / prefs-wipe cold start that would block the launcher's first frame while it
+        // races the app-startup prime worker. Default the affordance to SHOWN and resolve the
+        // (normally already-primed) cached value on a short-lived daemon worker; post the hide back
+        // to the main thread only if the ROM is confirmed unsupported. isActivityAlive() guards
+        // against a config-change recreate/destroy landing before the post runs.
+        val resizeProbeCtx = applicationContext
+        Thread({
+            val supported = try {
+                Platform.get().isClusterTaskResizeSupported(resizeProbeCtx)
+            } catch (t: Throwable) {
+                // Never downgrade UX on an unclassified probe failure — leave resize shown.
+                AppLogger.w(TAG, "cluster-resize probe failed; leaving resize UI shown", t)
+                true
+            }
+            if (!supported) {
+                runOnUiThread {
+                    if (!isActivityAlive()) return@runOnUiThread
+                    mClusterControlCoordinator?.hideResizeIfUnsupported()
+                    AppLogger.i(TAG, "Resize UI hidden: cluster task resize not supported on this ROM (DL5)")
+                }
+            }
+        }, "resize-affordance-probe").apply { isDaemon = true }.start()
 
         mSplitController = SplitController(this)
```

**Notes / verification:** The probe daemon captures the Activity transiently via `runOnUiThread`/`isActivityAlive`; it self-clears on thread exit (µs on a warm cache, ≤1.5 s only on a true first-launch), so it is a bounded window, not a leak. The resize UI defaults to SHOWN and is hidden ~1 frame later on confirmed-unsupported DL5 ROMs (cosmetic flicker on that ROM class only). `decorView.post` + `applicationContext` keep `FloatingRemoteButton` lifecycle-independent; `onStartCommand`'s `mFloatView != null` early-return keeps it idempotent. No R8 rule needed. On-car (DL5): `pm clear` → cold-start under `dumpsys gfxinfo … framestats` — confirm no main-thread stall near the 1500 ms budget at first frame; confirm the `resize-affordance-probe` thread runs off-main and the "Resize UI hidden…" log appears *after* first frame; confirm the badge appears when a mirror/quick-return is requested. *(Depends on Patch 1 for the leak-free probe internals; the two are designed to land together.)*

---

## Patch 3 — `DashCastApp.java` (rank #4/#6) — memory shedding + off-main platform init  ·  FULL FILE

**Strategy:** Add `onTrimMemory`/`onLowMemory` that clears the one process-wide cache the Application owns (the AppLogger ring buffer) at `TRIM_MEMORY_COMPLETE`; move every SharedPreferences-touching platform step off the main thread onto a short-lived daemon so `Application.onCreate` does no disk I/O.
**Fix risk:** LOW · **Delivery:** full-file replacement (39→~110 lines; a rewrite is safer to review than hunks). One new import (`android.content.Context`).

**Deliberate deviation from the naive finding fix:** `AppRepository` is instantiated per-Activity (`MainActivity.kt:110`), so the Application holds no reference to it — forcing a static handle just to shed it from `onTrimMemory` would be architecturally wrong and risk clearing state in flight. Each Activity/Service already gets its own `ComponentCallbacks2.onTrimMemory` and should shed there; that belongs in `MainActivity`/voice-owner overrides (out of scope for this file). The Application legitimately owns and sheds the `AppLogger` buffer.

```java
package com.byd.dashcast;

import android.app.Application;
import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;

import com.byd.dashcast.platform.Platform;
import com.byd.dashcast.util.AppLogger;

public class DashCastApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // appcompat:1.1.0 defaults to MODE_NIGHT_UNSPECIFIED (= always light).
        // Explicitly follow the system dark/light setting so DayNight theme works.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        // Initialise platform detection once. Reads ro.product.name, Build.* etc.
        // Snapshot is process-wide and immutable. The DiLink-5 override (auto /
        // force-on / force-off) is read from SharedPreferences on demand.
        final Platform p = Platform.get();
        // Build/SystemProperties-derived fields only — none of these touch
        // SharedPreferences, so the line is safe to build synchronously here.
        // effectiveDiLink5 is logged from the worker below instead: resolving it
        // (Platform.isDiLink5 -> readOverride -> prefs().getString) triggers the
        // first byd_app_prefs XML parse, which must not block Application.onCreate.
        AppLogger.i("Platform",
                "product=" + p.rawProductName()
                + " model="   + p.rawModel()
                + " api="     + p.androidApi()
                + " autoDiLink5=" + p.isAutoDetectedDiLink5()
                + " autoDiLink3=" + p.isAutoDetectedDiLink3());

        // Move every SharedPreferences-touching platform step off the main thread.
        // Both isDiLink5(app) (first byd_app_prefs read) and the gate inside
        // primeClusterResizeProbe(app) (isDiLink5 + prefs.contains, Platform.java
        // ~L345-347) would otherwise parse byd_app_prefs.xml synchronously in
        // Application.onCreate. Running them on a short-lived daemon thread keeps
        // the cold-start critical path free of disk I/O. primeClusterResizeProbe
        // is idempotent, forks its own shell worker, and no onCreate step below
        // depends on its result, so the deferral is contract-safe. It early-returns
        // (still off-main) on DL2/DL3/DL4.
        final Context app = getApplicationContext();
        Thread platformInit = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AppLogger.i("Platform", "effectiveDiLink5=" + p.isDiLink5(app));
                    p.primeClusterResizeProbe(app);
                } catch (Throwable t) {
                    // Never let a prefs/probe failure crash the process via the
                    // default uncaught-exception handler on this daemon thread.
                    AppLogger.e("Platform", "platform-init worker failed", t);
                }
            }
        }, "platform-init");
        platformInit.setDaemon(true);
        platformInit.start();

        // Foreground liveness ping for the proxy daemon.
        com.byd.dashcast.proxy.ProxyWatchdog.install(this);
        // Always-on foreground service that monitors the daemon every 10 s.
        com.byd.dashcast.proxy.ProxyKeeperService.ensureRunning(this);
    }

    /**
     * Cooperative memory shedding. The Application owns exactly one process-wide
     * cache — the AppLogger diagnostic ring buffer (up to ~2M chars / ~4 MB of
     * UTF-16 once MAX_TOTAL_CHARS saturates). Per-Activity caches (the
     * AppRepository app-list/icon cache, held on the live MainActivity instance)
     * and lazily-held voice-engine state live on their owners, which each receive
     * their own ComponentCallbacks2.onTrimMemory from the framework and must shed
     * there; the Application holds no reference to those live instances and
     * deliberately does not forward to avoid clearing state in flight.
     *
     * <p>Only TRIM_MEMORY_COMPLETE triggers the shed: at that level the process is
     * already backgrounded and next in line for the low-memory killer, so
     * reclaiming the buffer can keep the resident set below the LMK threshold and
     * avoid a kill -> cold-restart cycle. The buffer would be lost on the kill
     * anyway, and COMPLETE is never delivered while the app is foreground, so this
     * never destroys a diagnostic capture the user is about to share.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) {
            shedProcessWideCaches();
        }
    }

    /**
     * Legacy pre-API-14 low-memory signal. Equivalent to
     * onTrimMemory(TRIM_MEMORY_COMPLETE); both may fire and AppLogger.clear() is
     * idempotent, so routing them through the same shed path is safe.
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        shedProcessWideCaches();
    }

    /** Releases the only process-wide cache the Application owns. */
    private void shedProcessWideCaches() {
        AppLogger.clear();
    }
}
```

**Notes / verification:** `primeClusterResizeProbe` completes slightly later (on the worker) but the probe was already async (forks a nested shell worker), and `MainActivity`'s read path is self-healing on a cold cache — no new race. The platform log now emits two "Platform" INFO lines (log-format change only). No R8 rule needed. On-car: (1) `am force-stop` + relaunch under `logcat -s Platform StrictMode` — the "effectiveDiLink5=" line runs on thread `platform-init`, StrictMode shows no disk-read violation in `Application.onCreate`; (2) background the app, `am send-trim-memory … COMPLETE` → the log buffer empties; `RUNNING_CRITICAL` leaves it untouched.

---

## Patch 4 — `system/FloatingRemoteButton.java` (rank #8) — destroyed-Service overlay-leak guard

**Strategy:** `volatile mDestroyed` set first in `onDestroy()`; short-circuit the ADB grant `onSuccess` re-entry and the top of `createOverlay()` so a late background grant callback can never rebuild the badge and `addView()` a window on a dead Service.
**Fix risk:** LOW.

```diff
--- a/app/src/main/java/com/byd/dashcast/system/FloatingRemoteButton.java
+++ b/app/src/main/java/com/byd/dashcast/system/FloatingRemoteButton.java
@@ -105,5 +105,10 @@
     private WindowManager mWindowManager;
     private View          mFloatView;
     private boolean       mGrantAttempted = false;
+    // F29: set in onDestroy() so a late ADB grant onSuccess (running on the
+    // AdbLocalClient background executor) short-circuits instead of re-posting
+    // createOverlay() and addView()-ing a TYPE_APPLICATION_OVERLAY window on a
+    // dead Service. volatile: written on the main thread, read on sExecutor.
+    private volatile boolean mDestroyed = false;
     // v1.2.74 — track FG status so we can toggle the notification along with the badge.
     private boolean       mIsForeground = false;
@@ -137,4 +137,5 @@
     @Override
     public void onDestroy() {
+        mDestroyed = true;
         if (mSnapAnimator != null) { mSnapAnimator.cancel(); mSnapAnimator = null; }
         mDimHandler.removeCallbacksAndMessages(null);
@@ -160,3 +160,4 @@
     private void createOverlay() {
+        if (mDestroyed) return;
         if (!android.provider.Settings.canDrawOverlays(this)) {
             if (mGrantAttempted) {
@@ -169,6 +169,9 @@
                 @Override
                 public void onSuccess(String report) {
+                    if (mDestroyed) return;
                     AppLogger.i(TAG, "SYSTEM_ALERT_WINDOW granted via ADB ✓");
                     mDimHandler.post(new Runnable() {
-                        @Override public void run() { createOverlay(); }
+                        @Override public void run() {
+                            if (mDestroyed) return;
+                            createOverlay();
+                        }
                     });
                 }
```

**Notes / verification:** `mDestroyed` is written on the main thread and read on the `AdbLocalClient` background pool thread → `volatile` required. Set first in `onDestroy()` so a racing `onSuccess` observes it before posting; the re-posted `createOverlay()` also runs on the main looper strictly after `onDestroy()`, giving a second guard. Happy path (grant already held) unaffected. No R8 rule needed. On-car: fresh install with SYSTEM_ALERT_WINDOW denied so the ADB auto-grant fires; start then immediately stop the service while the round-trip is in flight; `dumpsys window windows | grep -i overlay` shows no orphan `TYPE_APPLICATION_OVERLAY` window and logcat shows no second "Overlay created" after destroy.

---

## Patch 5 — `fission/ClusterCanvasView.java` (rank #7) — zero-alloc onDraw label cache

**Strategy:** Hoist each slot's label String into a parallel `String[]` cache keyed on (label ref, w, h, displayId); the draw loop rebuilds a label only when one of those changes (during a RESIZE drag, just the resized slot). The transient rubber-band W×H readout is composed into a reused `StringBuilder` and drawn via a new `CharSequence`-based single-line helper.
**Fix risk:** LOW · **Validated:** `git apply --check` OK.

```diff
diff --git a/app/src/main/java/com/byd/dashcast/fission/ClusterCanvasView.java b/app/src/main/java/com/byd/dashcast/fission/ClusterCanvasView.java
index b04c028..8b398a0 100644
--- a/app/src/main/java/com/byd/dashcast/fission/ClusterCanvasView.java
+++ b/app/src/main/java/com/byd/dashcast/fission/ClusterCanvasView.java
@@ -32,6 +32,17 @@ public class ClusterCanvasView extends View {
     private final Paint mPaintDrawStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
     private final RectF mBgRect          = new RectF();
 
+    // Per-slot label cache — keeps onDraw allocation-free in the steady state.
+    // A cached label is keyed on the exact inputs that compose it (label ref,
+    // w, h, displayId); onDraw rebuilds an entry only when one of those changes,
+    // which during a RESIZE drag is just the single slot being resized.
+    private String[]            mLabelCache = new String[0];
+    private String[]            mLabelName  = new String[0];
+    private int[]               mLabelW     = new int[0];
+    private int[]               mLabelH     = new int[0];
+    private int[]               mLabelVd    = new int[0];
+    private final StringBuilder mLabelSb    = new StringBuilder(24);
+
     private Bitmap mBg;
 
     private int mTop, mBottom, mLeft, mRight;
@@ -152,7 +163,9 @@ public class ClusterCanvasView extends View {
 
         List<LayoutPreset.SlotDef> slots = mSlots;
         if (slots != null) {
-            for (int i = 0; i < slots.size(); i++) {
+            int n = slots.size();
+            ensureLabelCache(n);
+            for (int i = 0; i < n; i++) {
                 LayoutPreset.SlotDef s   = slots.get(i);
                 int col = ZONE_COLORS[i % ZONE_COLORS.length];
                 mPaintFill.setColor(col);
@@ -166,8 +179,17 @@ public class ClusterCanvasView extends View {
                 c.drawCircle(r, t, hr, mPaintHandle);
                 c.drawCircle(r, b, hr, mPaintHandle);
                 c.drawCircle(l, b, hr, mPaintHandle);
-                String lbl = s.label + "\n" + s.w + "×" + s.h
-                        + (s.displayId >= 0 ? "\nVD:" + s.displayId : "");
+                String lbl = mLabelCache[i];
+                if (lbl == null || mLabelName[i] != s.label
+                        || mLabelW[i] != s.w || mLabelH[i] != s.h
+                        || mLabelVd[i] != s.displayId) {
+                    lbl            = buildLabel(s);
+                    mLabelCache[i] = lbl;
+                    mLabelName[i]  = s.label;
+                    mLabelW[i]     = s.w;
+                    mLabelH[i]     = s.h;
+                    mLabelVd[i]    = s.displayId;
+                }
                 drawCenteredText(c, lbl, (l + r) / 2f, (t + b) / 2f);
             }
         }
@@ -178,12 +200,18 @@ public class ClusterCanvasView extends View {
             c.drawRect(mCurrentRect, mPaintDrawStroke);
             int cw = (int) (mCurrentRect.width()  / mScaleX);
             int ch = (int) (mCurrentRect.height() / mScaleY);
-            drawCenteredText(c, cw + "×" + ch, mCurrentRect.centerX(), mCurrentRect.centerY());
+            StringBuilder sb = mLabelSb;
+            sb.setLength(0);
+            sb.append(cw).append('×').append(ch);
+            drawCenteredLine(c, sb, 0, sb.length(),
+                    mCurrentRect.centerX(), mCurrentRect.centerY());
         }
     }
 
-    // Runs once per slot per onDraw, i.e. every frame while a zone is dragged —
-    // lines are scanned by index instead of regex-split into a fresh String[].
+    // Draws a cached multi-line slot label. The String itself is built once in
+    // buildLabel() and reused across frames (see mLabelCache), so this path does
+    // not allocate per frame; lines are scanned by index instead of regex-split
+    // into a fresh String[].
     private void drawCenteredText(Canvas c, String text, float cx, float cy) {
         int lineCount = 1;
         for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) lineCount++;
@@ -201,6 +229,36 @@ public class ClusterCanvasView extends View {
         }
     }
 
+    // Single-line, allocation-free centered draw for the transient rubber-band
+    // label: measures and draws straight from a CharSequence (the reused
+    // mLabelSb) so no per-frame String is created while a zone is being drawn.
+    private void drawCenteredLine(Canvas c, CharSequence text, int start, int end, float cx, float cy) {
+        float tw = mPaintLabel.measureText(text, start, end);
+        c.drawText(text, start, end, cx - tw / 2f, cy, mPaintLabel);
+    }
+
+    // Grows the parallel label-cache arrays to hold at least n entries, keeping
+    // existing entries. New tail slots start null, forcing a one-time rebuild.
+    private void ensureLabelCache(int n) {
+        if (mLabelCache.length >= n) return;
+        mLabelCache = java.util.Arrays.copyOf(mLabelCache, n);
+        mLabelName  = java.util.Arrays.copyOf(mLabelName,  n);
+        mLabelW     = java.util.Arrays.copyOf(mLabelW,     n);
+        mLabelH     = java.util.Arrays.copyOf(mLabelH,     n);
+        mLabelVd    = java.util.Arrays.copyOf(mLabelVd,    n);
+    }
+
+    // Composes a slot's multi-line label into the reused StringBuilder, then
+    // snapshots it to a String for caching. append(int)/append(char) write the
+    // digits straight into the builder's buffer with no intermediate Strings.
+    private String buildLabel(LayoutPreset.SlotDef s) {
+        StringBuilder sb = mLabelSb;
+        sb.setLength(0);
+        sb.append(s.label).append('\n').append(s.w).append('×').append(s.h);
+        if (s.displayId >= 0) sb.append('\n').append("VD:").append(s.displayId);
+        return sb.toString();
+    }
+
     @Override
     public boolean onTouchEvent(MotionEvent event) {
         mGesture.onTouchEvent(event);
```

**Notes / verification:** `onDraw`/`onTouchEvent` both run on the UI thread → no sync needed. Cache is keyed purely on the four label inputs, so a stale entry (displayId mutated, slot renamed, new list via `setSlots`) is detected and rebuilt next frame — no mutation site needs to notify the view. `ensureLabelCache` only grows (`Arrays.copyOf` preserves entries); indices ≥ n are never read. Honest allocation profile: steady state and MOVE drags allocate nothing; during an active RESIZE the one resized slot rebuilds its label each frame (unavoidable — `drawCenteredText` consumes a `String`), other slots stay cache hits; the rubber-band no longer allocates. `measureText(CharSequence,int,int)` / `drawText(CharSequence,…)` are API-1. No R8 rule needed (LayoutPreset untouched). On-car: open the Fission editor, drag+resize a zone ~10 s under the Memory profiler — labels render identically, no per-frame String churn attributed to `ClusterCanvasView.onDraw` except the single resized slot.

---

## Patch 6 — `fission/FissionActivity.java` (rank #9) — reuse a single picker executor

**Strategy:** Replace the per-tap `Executors.newSingleThreadExecutor()` in `pickApp()` with a single `final` activity-field `mExec` (matching the sibling fission activities), execute the picker work on it, shut it down once in `onDestroy()`.
**Fix risk:** LOW.

```diff
--- a/app/src/main/java/com/byd/dashcast/fission/FissionActivity.java
+++ b/app/src/main/java/com/byd/dashcast/fission/FissionActivity.java
@@ -81,6 +81,9 @@ public class FissionActivity extends Activity implements FissionOrchestrator.Cal
     // ── State ──────────────────────────────────────────────────────────────
     private FissionOrchestrator mOrchestrator;
     private boolean             mDestroyed;
     private final Handler       mUiHandler = new Handler(Looper.getMainLooper());
+    /** Shared single-thread executor for the off-main-thread app-picker query.
+     *  Created once (matching the sibling fission activities) and shut down in {@link #onDestroy()}. */
+    private final ExecutorService mExec = Executors.newSingleThreadExecutor();
 
     // ── Lifecycle ──────────────────────────────────────────────────────────
 
@@ -160,6 +163,7 @@ public class FissionActivity extends Activity implements FissionOrchestrator.Cal
     @Override
     protected void onDestroy() {
         super.onDestroy();
         mDestroyed = true;
         mUiHandler.removeCallbacksAndMessages(null);
+        mExec.shutdown();
         if (mOrchestrator != null) mOrchestrator.destroy(isFinishing());
     }
 
@@ -211,8 +215,7 @@ public class FissionActivity extends Activity implements FissionOrchestrator.Cal
     private void pickApp() {
         if (!mSurfaceReady) return;
         btnAdd.setEnabled(false);
-        ExecutorService tmpExec = Executors.newSingleThreadExecutor();
-        tmpExec.execute(() -> {
+        mExec.execute(() -> {
             PackageManager pm = getPackageManager();
             Intent main = new Intent(Intent.ACTION_MAIN);
             main.addCategory(Intent.CATEGORY_LAUNCHER);
@@ -278,7 +281,6 @@ public class FissionActivity extends Activity implements FissionOrchestrator.Cal
                         .setNegativeButton(android.R.string.cancel, null)
                         .show();
             });
         });
-        tmpExec.shutdown();
     }
```

**Notes / verification:** Imports already present (`ExecutorService`, `Executors`). `final` field initializer matches the two sibling activities exactly (`FissionLayoutEditorActivity:39`, `LayoutManagerActivity:65`). `pickApp()` is reachable only via the `btnAdd` listener and gated by `mSurfaceReady` (cleared in `surfaceDestroyed`), so `mExec.execute()` cannot run after `shutdown()` — same guarantee as the siblings. No R8 rule needed. On-car: rapidly tap "Add app" 8–10× — the picker opens every time, no ANR; `adb shell ps -T -p $(pidof com.byd.dashcast) | grep -c pool-` stays flat at 1 (was +1 lingering thread per tap), returns to 0 after finishing the activity.

---

## Patch 7 — `voice/wakeword/WakeWordEngine.kt` (rank #2) — zero-copy ONNX output read

**Strategy:** Replace the per-tick `out.get(0).value as Array<Array<Array<FloatArray>>>` materialisation (mel + embedding outputs) with a flat `(out.get(0) as OnnxTensor).floatBuffer` read straight into `mMelRing` / `mEmbRing`, eliminating the nested-array object graph per tick.
**Fix risk:** LOW (read-path only, no locking change) · **Hand-authored** (8th workflow agent was interrupted mid-`Write`; diff derived from the read hot-path at `advanceMel` L503–516 and `embedWindow` L547–550). `OnnxTensor` and `java.nio.FloatBuffer` are already imported (used at L471/L501). `git apply --check` NOT yet run — validate before landing.

```diff
--- a/app/src/main/java/com/byd/dashcast/voice/wakeword/WakeWordEngine.kt
+++ b/app/src/main/java/com/byd/dashcast/voice/wakeword/WakeWordEngine.kt
@@ -501,15 +501,19 @@ class WakeWordEngine(
             mMelMap.put(mMelInputName!!, input)
             mSessMel!!.run(mMelMap).use { out ->
-                val raw = out.get(0).value as Array<Array<Array<FloatArray>>> // [1,1,time,32]
-                val nf = raw[0][0].size
+                // Zero-copy read: the mel output is a flat [1,1,time,32] float tensor.
+                // Reading it as a FloatBuffer avoids materialising the nested
+                // Array<Array<Array<FloatArray>>> object graph (~time FloatArrays + wrappers)
+                // every ~160 ms tick. Row-major C order: frame f, channel k → f*32 + k.
+                val melBuf = (out.get(0) as OnnxTensor).floatBuffer
+                val nf = melBuf.remaining() / 32
                 val lastCommit = feedStartFrame + nf - MEL_GUARD // exclusive
                 for (f in mMelTotalFrames until lastCommit) {
                     val localIdx = (f - feedStartFrame).toInt()
                     if (localIdx < 0 || localIdx >= nf) continue
-                    val src = raw[0][0][localIdx]                   // (32,) raw log-mel dB
+                    val base = localIdx * 32                        // (32,) raw log-mel dB row
                     val dst = mMelRing[Math.floorMod(f, MEL_RING_FRAMES.toLong()).toInt()]
-                    for (k in 0 until 32) dst[k] = src[k] / 10f + 2f // openWakeWord normalization
+                    for (k in 0 until 32) dst[k] = melBuf.get(base + k) / 10f + 2f // openWakeWord normalization
                 }
                 if (lastCommit > mMelTotalFrames) mMelTotalFrames = lastCommit
             }
@@ -545,8 +549,10 @@ class WakeWordEngine(
             mEmbMap.put(mEmbInputName!!, embT)
             mSessEmb!!.run(mEmbMap).use { out ->
-                val raw = out.get(0).value as Array<Array<Array<FloatArray>>> // [1,1,1,96]
-                System.arraycopy(raw[0][0][0], 0, mEmbRing[Math.floorMod(k, EMB_CACHE.toLong()).toInt()], 0, 96)
+                // Zero-copy read of the [1,1,1,96] embedding output straight into the
+                // ring slot — no nested-array materialisation per stride window.
+                (out.get(0) as OnnxTensor).floatBuffer
+                    .get(mEmbRing[Math.floorMod(k, EMB_CACHE.toLong()).toInt()], 0, 96)
             }
```

**Notes / verification:** `getFloatBuffer()` returns a `FloatBuffer` view over the tensor's float32 data positioned at 0 (`remaining()` = total elements), so `melBuf.get(base+k)` (absolute) and `.get(dst,0,96)` (relative) read row-major without allocating the nested Java arrays. The `@Suppress("UNCHECKED_CAST")` on `advanceMel`/`embedWindow` becomes redundant (the `as OnnxTensor` is a checked cast) but is harmless — leave or remove. The wake-head read at L474 (`out.get(0).value as Array<FloatArray>`, [1,1] → 2 floats, once/tick) is left as-is: negligible. **On-car self-check:** with `SELF_CHECK_MS > 0`, the engine already logs `selfcheck stream=… ref=… Δ=…`; after this change confirm Δ stays ~0.000 for the first 12 s of active audio (byte-identical mel/emb path) and wake detection still fires. Land in Batch B (medium soak) on the DL3+DL5 voice matrix.

> **Staged follow-ups in this file (NOT in this diff — Batch C, MED risk):**
> - **#10 (`advanceMel` L496):** the per-sample `/32768f` divide + `% AUDIO_BUFFER_LEN` still runs *inside* `mRingLock`, stalling the 20 Hz `onFrame` writes. Fix = under the lock copy only the raw `ShortArray` tail into a reused `mMelRawFeed` (two `System.arraycopy` segments for the ring wrap, no FP), release the lock, then divide into `mMelFeed` outside. Needs a new `mMelRawFeed = ShortArray(mMelFeed.size)` field.
> - **#12 (`workerLoop` L231):** the up-to-40k raw copy-under-lock + 40k normalize per tick is, after `SELF_CHECK_MS`, consumed only to derive `windowPeak`. Fix = track the peak from the raw int16 tail (or a running sliding-max in `onFrame`) and skip the `audioWindow` copy+normalize once self-check has elapsed. **Gate this on the existing on-car self-check step (`SELF_CHECK_MS=0`) from `[[perf-audit-voice-streaming]]`** — do not land it before streaming parity is confirmed on the unit.

---

## Patch 8 — `gradle.properties` (rank #11) — drop the Jetifier rewrite pass

**Strategy:** Disable Jetifier — the whole dependency graph is already AndroidX/Kotlin/native, so the artifact-rewrite pass is pure build-time waste.
**Fix risk:** LOW (build-time only; no APK/runtime/boot/frame change).

```diff
--- a/gradle.properties
+++ b/gradle.properties
@@ -1,6 +1,6 @@
 org.gradle.jvmargs=-Xmx1536m --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED
 android.useAndroidX=true
-android.enableJetifier=true
+android.enableJetifier=false
 
 # Optimisations de performances Gradle
 org.gradle.parallel=true
```

**Notes / verification:** `android.useAndroidX=true` is deliberately kept — only the legacy-support rewrite is dropped. The only `android.support.*` token anywhere is the `android.support.FILE_PROVIDER_PATHS` meta-data key at `AndroidManifest.xml:339`, which `androidx.core.content.FileProvider` reads verbatim and Jetifier never rewrites — so the offline bug-report / share FileProvider flow is unaffected. Verification: `./gradlew clean assembleRelease` → BUILD SUCCESSFUL with no Jetifier/`android.support` resolution errors, then smoke-test the Diag → offline bug report → share path so the `content://` URIs still resolve.

---

### Also proposed (Phase 4 tuning — not a code patch)
- **`app/proguard-rules.pro`** (new file) + `minifyEnabled true` — full keep-rules in the [audit doc, TUNING 1](2026-07-05-aaos-perf-audit.md#tuning-1--r8proguard-keep-rules-to-safely-unblock-minifyenabled-true). Ship separately after the DL3+DL5 on-car reflection matrix passes.
- **`resConfigs 'ar','be','de','en','es','it','kk','pl','ru','tr','uk','uz'`** in `defaultConfig` — see [TUNING 2](2026-07-05-aaos-perf-audit.md#tuning-2--build-config-footprint-levers-zero-runtime-risk-verified-inputs). Locale list verified against `res/values-*` this session.
