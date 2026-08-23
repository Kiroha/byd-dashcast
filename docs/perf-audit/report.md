# DashCast — Performance Audit Report

**Date:** 2026-08-23 · **Branch:** `audit/integr-1828` · **Head:** `7aa64f25` (1.8.38-beta / build 628)
**Scope:** `app/` — 184 sources, 48 031 LOC. Read-only audit. **No code was modified.**
**Companion docs:** [`architecture.md`](architecture.md) (system map) · [`measure.md`](measure.md) (measurement protocol) · [`backlog.md`](backlog.md)

---

## Executive summary

1. **DashCast has no frame pipeline** — no `MediaCodec`, no `MediaProjection`, no per-frame app code. Projection relocates a third-party task onto the OEM cluster display; SurfaceFlinger does all per-frame work. There is no encoder to tune, and that is an architectural strength: zero encode latency, zero encode power.
2. **The app's real failure mode is stealing headroom from the app that owns those frames** — via idle wakeups, shell forks, Binder chatter and thermal load on a passively-cooled SoC that runs for days.
3. **Top finding, derived independently by three lanes:** a rolling screenshot recorder is **ON by default** and captures + JPEG-encodes **two full displays every ~20 s for the entire duration of every projection** — ~4 320 captures and ~8 640 encodes per driving day, with no change-detection.
4. **Second:** its prune tick opens a **full ADB TCP + RSA handshake every 30 s forever**, including on a car that has never projected — ~2 880 handshakes/day of pure waste.
5. **Third:** every cluster launch shells `dumpsys SurfaceFlinger`, taking SurfaceFlinger's **global lock** 1.5 s into the projected app's cold start. Its own javadoc says *"diagnostic only (no behaviour change)."*
6. **Fourth:** the uid-2000 daemon runs a `Thread.sleep(1_000L)` loop **forever** — 86 400 wakeups/day on a process that outlives the app.
7. **30 distinct findings** after dedupe (38 raw across 5 lanes). **5 quick wins are S-effort with inline patches below.**
8. **Prior audit work genuinely holds.** `AppLogger`'s byte budget, executor shutdowns, receiver unregisters, `SurfaceControl` token releases and the `PointerProperties[16]` touch pre-allocation were all re-verified in current code, not trusted from comments.
9. **Everything is tagged `ESTIMATED`.** No car was profiled. `measure.md` names the one measurement that confirms or refutes each finding.
10. **My own Phase 1 claim was partially refuted by Lane D** and is corrected in `architecture.md` §0.1 rather than quietly amended. See §7.

---

## 1. Method and confidence

Five parallel read-only lanes (UI/render, memory/GC, concurrency/IPC, codec/transport, lifecycle/power/build), each pre-loaded with verified ground truth so no lane re-derived the platform envelope or assumed AAOS. Lane D was re-tasked adversarially to **falsify** the Phase 1 mechanism claim rather than confirm it.

Every finding below carries `file:line`. I independently re-verified at source every finding that entered the Top 5, plus every finding where a lane's claim contradicted another lane's. Corrections are logged in §7 — including two cases where a lane's *proposed fix* was wrong in a way that would have made the patch a silent no-op or a behavioural regression.

**Confidence tags:** `HIGH` = read directly, mechanism unambiguous · `MED` = mechanism clear, magnitude uncertain · `HYPOTHESIS` = no line-level proof.
**All findings are `ESTIMATED` until traces land.**

---

## 2. Ranked findings

Sorted by impact ÷ effort. Quick wins first.

| # | Finding | Lane IDs | Class | Conf. | Effort |
|---|---|---|---|---|---|
| **P1** | **Screenshot recorder: 2 full-display captures + 2 JPEG encodes every ~20 s, default ON, no change-detection** | D1·C5·E1 | power HIGH | HIGH | **S** |
| **P2** | **Prune tick opens a full ADB TCP+RSA handshake every 30 s forever, including when idle** | D2 | power HIGH | HIGH | **S** |
| **P3** | **`dumpsys SurfaceFlinger` on every launch takes SF's global lock during cluster app cold start** | C2 | frame HIGH | HIGH | **S** |
| **P4** | **uid-2000 daemon self-heal loop wakes 60×/min forever (86 400/day)** | C1 | power HIGH | HIGH | **S** |
| **P5** | **`AppLogger.get()` under-sizes its StringBuilder ~5×, peaking ~12 MB per call** | B4 | memory MED | HIGH | **S** |
| P6 | a11y service subscribes to `typeWindowStateChanged` system-wide and never handles it | E3 | power MED | HIGH | S |
| P7 | Keeper FGS heartbeat: real `pingBinder` IPC every 10 s, never gated by projection or screen | C7·E2 | power MED | HIGH | S |
| P8 | `DisplayStatePollCoordinator`: 2× `pidof` every 5 s while foreground, unconditional | C10 | power LOW-MED | HIGH | S |
| P9 | 13 always-alive workers at default priority; exactly one thread in the codebase sets priority | C4 | frame MED | HIGH | S |
| P10 | Fission watchdog polls task location 2×/s for up to 90 s per launch | C9 | power LOW | HIGH | S |
| P11 | Two unbounded executors on the launch path; one sleeps 1.5 s per queued item | C12 | latency LOW | HIGH | S |
| P12 | `MainActivity.onDestroy`: unguarded `unregisterReceiver` can abort all remaining teardown | B7 | memory LOW | MED | S |
| P13 | `CanFeedbackListener.toHex`: ~250 allocations/event, formatted even when the buffer is full | B5 | memory LOW | HIGH | S |
| P14 | Nav hot path builds 2 discarded log strings + 3 lowercase copies per guidance frame | B6 | frame LOW | HIGH | S |
| P15 | Fullscreen mirror toggle freezes preview for a hardcoded 250 ms not tied to layout | A2 | latency MED | HIGH | S |
| P16 | `DaemonBinderResolver`: uncached reflection + servicemanager IPC per lookup; thread per `fetch()` | C11 | latency LOW | MED | S |
| P17 | Capture frame-wait sleeps 50 ms *before* first acquire — 100 ms/round floor, 3 s on failure | D4 | latency MED | HIGH | S |
| P18 | `FloatingRemoteButton` creates then tears down notification + overlay on every cold start | E5 | startup LOW | HIGH | S |
| P19 | `unlockHiddenApis()` runs sync on main thread at cold start for a path with 0 field successes | E6 | startup LOW | MED | S |
| P20 | `FissionOrchestrator.sAutoStartOrchestrator` retains an idle executor for the process lifetime | B9 | memory LOW | HIGH | S |
| P21 | `AdbLocalClient` 4-thread pool has no core-thread timeout; persists for days after one use | B8 | memory LOW | HIGH | S |
| **P22** | **Redactor rewrites the whole bug-report body 16× — 20–30 MB transient peak per send** | B1 | memory HIGH | HIGH | M |
| **P23** | **Capture pipeline: ~21 MB of ARGB_8888 per round; ImageReader/token/bitmaps never reused** | B3·D3·D5 | memory HIGH | HIGH | M |
| P24 | HUD guidance frame costs 7 serial synchronous Binder RTTs; a batch verb already exists | C3 | latency MED | HIGH | M |
| P25 | Cluster touch injection has no drop/coalesce policy — unbounded oneway Binder from UI thread | C8·A3 | latency MED | MED | M |
| P26 | Every shell command forks 3 processes, creates a temp file and spawns a thread | C6 | latency MED | HIGH | M |
| P27 | `AdbLocalClient` re-authenticates a new ADB connection for every single shell command | D7 | latency MED | HIGH | M |
| P28 | Preview mirror kept alive while the Activity is stopped, compositing into an unseen surface | D6 | power MED | MED | S–M |
| P29 | `proguard-rules.pro` keeps all of `proxy.**`; ~1/3 of it needs no keep | E4 | startup MED | HIGH | M |
| P30 | App icons retained at full launcher density for every installed app, drawn at 56dp | B2 | memory HIGH | MED | M |
| P31 | Cluster mirror sinks into a `TextureView`, adding a GPU composition pass on an IVI-shared GPU | A1 | frame MED-HIGH | MED | M–L |
| — | `AppLogger` has no `BuildConfig.DEBUG` gate (informational — no fix recommended) | E7 | — | MED | — |

---

## 3. Top 5 quick wins — proposed patches

> **These are patches to review, NOT applied.** Nothing in the working tree was modified.
> Each needs an explicit per-item GO. Every one is `S` effort and independently source-verified.

### P1 — Screenshot recorder cadence ramp

**Class:** power HIGH · **Confidence:** HIGH · **Effort:** S · **Lanes:** D1, C5, E1 (three independent derivations)

**Evidence**
- [`ProxyKeeperService.java:56`](../../app/src/main/java/com/byd/dashcast/proxy/ProxyKeeperService.java#L56) — `HEARTBEAT_MS = 10_000L`
- [`ProxyKeeperService.java:160`](../../app/src/main/java/com/byd/dashcast/proxy/ProxyKeeperService.java#L160) — `ClusterShotRecorder.maybeCapture(ctx)` on every heartbeat
- [`ClusterShotRecorder.kt:52`](../../app/src/main/java/com/byd/dashcast/report/ClusterShotRecorder.kt#L52) — `INTERVAL_MS = 15_000L`
- [`ClusterShotRecorder.kt:72`](../../app/src/main/java/com/byd/dashcast/report/ClusterShotRecorder.kt#L72) — `getBoolean(PREF_ENABLED, true)`, and the `catch` also returns `true`

**Root cause.** The recorder rides the always-on keeper heartbeat and is enabled by default. A 15 s threshold sampled on a 10 s heartbeat yields an effective **~20 s cadence**, sustained for the whole projection. Each round captures **both** display 0 and the cluster — two `SurfaceControl.createDisplay` → `ImageReader` → `Bitmap` → JPEG → disk → `destroyDisplay` cycles. Nothing checks whether the screen changed, so a car stopped at a light re-encodes an identical frame indefinitely.

**Fix.** Ramp the cadence: keep 15 s for the first 2 minutes of a projection — the window launch-time incidents occur in — then fall back to 90 s. `MAX_AGE_MIN = 5` retention is unchanged, so the "user notices, then reports" flow stays covered.

```diff
--- a/app/src/main/java/com/byd/dashcast/report/ClusterShotRecorder.kt
+++ b/app/src/main/java/com/byd/dashcast/report/ClusterShotRecorder.kt
-    private const val INTERVAL_MS = 15_000L
+    // AUD-PERF-P1 — cadence ramp. This recorder rides the 10 s keeper heartbeat and is ON by
+    // default, so a flat 15 s threshold meant ~3 capture rounds/min — six full-display captures
+    // and six JPEG encodes every minute — for the entire duration of every projection, on a
+    // passively-cooled SoC whose GPU is shared with the IVI stack. Launch-time incidents (the
+    // ones these shots exist for) happen in the first couple of minutes; a car parked at a light
+    // for an hour was re-encoding an unchanged frame 180 times.
+    private const val INTERVAL_MS        = 15_000L   // during the post-launch ramp window
+    private const val INTERVAL_STEADY_MS = 90_000L   // steady state
+    private const val RAMP_WINDOW_MS     = 120_000L
     private const val PRUNE_INTERVAL_MS = 30_000L

+    // Latched when projection comes up; reset when it goes away. Drives the ramp above.
+    @Volatile private var sProjectionStartMs = 0L
+
     @JvmStatic
     fun maybeCapture(ctx: Context) {
         if (!isEnabled(ctx)) return
         val app = ctx.applicationContext
         val now = SystemClock.elapsedRealtime()

         val clusterId = ClusterService.getInstance()?.displayId ?: -1
+        // Latch the projection-start instant so the ramp is measured from when the cluster
+        // actually came up, not from process start.
+        if (clusterId > 0) {
+            if (sProjectionStartMs == 0L) sProjectionStartMs = now
+        } else {
+            sProjectionStartMs = 0L
+        }
+        val intervalMs =
+            if (sProjectionStartMs != 0L && now - sProjectionStartMs < RAMP_WINDOW_MS) INTERVAL_MS
+            else INTERVAL_STEADY_MS
         if (ClusterShotSchedulePolicy.shouldCapture(
-            clusterId, now, sLastCaptureMs, INTERVAL_MS)) {
+            clusterId, now, sLastCaptureMs, intervalMs)) {
             sLastCaptureMs = now
             sExecutor.execute { captureRound(app, clusterId) }
```

**Expected gain (ESTIMATED).** Steady state 3 rounds/min → 0.67/min. Over a 1 h drive: 180 → ~44 rounds, i.e. **~270 fewer full-display captures and ~270 fewer JPEG encodes per hour**. Absolute mW needs a trace.

**Regression risk.** A post-incident shot may be up to 90 s stale instead of 15 s outside the ramp window. Retention and the ring bound are untouched. No projection, daemon, binder or consent path changes.

**Verify.** `ls -l /data/local/tmp/dashcast_shots` timestamps over a 10 min projection; cross-check daemon CPU in `dumpsys batterystats --charged com.byd.dashcast`.

> **Separate product decision, deliberately NOT in this patch:** flipping `PREF_ENABLED`'s default to `false` for release builds (keeping `true` for beta) would remove the cost entirely for most users. That is a support-tooling trade-off, not a perf call — it is yours to make. Flag it and I'll patch it separately.

---

### P2 — Stop the idle ADB handshake

**Class:** power HIGH · **Confidence:** HIGH · **Effort:** S · **Lane:** D2

**Evidence**
- [`ClusterShotSchedulePolicy.kt:14-15`](../../app/src/main/java/com/byd/dashcast/report/ClusterShotSchedulePolicy.kt#L14-L15) — `return clusterId <= 0 || …`
- [`ClusterShotRecorder.kt:101-104`](../../app/src/main/java/com/byd/dashcast/report/ClusterShotRecorder.kt#L101-L104) — prune branch
- [`AdbLocalClient.java:736`](../../app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.java#L736) — TCP probe + `Dadb.create()` RSA handshake per call
- [`SurfaceDaemon.java:696`](../../app/src/main/java/com/byd/dashcast/proxy/daemon/SurfaceDaemon.java#L696) — the daemon already enforces the ring bound in-process

**Root cause.** When **not** projecting (`clusterId <= 0`), `shouldAppPrune` returns `true` unconditionally every 30 s, forever. Each prune issues a shell command, and every `AdbLocalClient` call is a fresh TCP reachability probe plus a full `A_CNXN`/`A_AUTH` RSA handshake that is then closed — to run a `cd … || exit 0` against a directory that is usually empty or absent. The daemon already bounds the ring itself, so this app-side prune is belt-and-braces.

**Fix.** Skip the prune when this process has never captured. An idle car then issues **zero** handshakes.

```diff
--- a/app/src/main/java/com/byd/dashcast/report/ClusterShotSchedulePolicy.kt
+++ b/app/src/main/java/com/byd/dashcast/report/ClusterShotSchedulePolicy.kt
     fun shouldAppPrune(
         clusterId: Int,
         nowMs: Long,
         lastAppPruneMs: Long,
         lastDaemonPruneMs: Long,
-        intervalMs: Long
+        intervalMs: Long,
+        everCaptured: Boolean
     ): Boolean {
         if (nowMs - lastAppPruneMs < intervalMs) return false
+        // AUD-PERF-P2 — a process that has never captured has nothing to prune, and every prune
+        // costs a full ADB TCP probe + RSA handshake (AdbLocalClient.connect). This branch used
+        // to fire unconditionally every 30 s for the entire life of a car that never projects:
+        // ~2 880 handshakes/day to delete files that were never created. The daemon enforces the
+        // ring bound in-process anyway (SurfaceDaemon.pruneShotDir), so this is belt-and-braces.
+        if (!everCaptured) return false
         return clusterId <= 0 || nowMs - lastDaemonPruneMs >= intervalMs
     }
```

```diff
--- a/app/src/main/java/com/byd/dashcast/report/ClusterShotRecorder.kt
+++ b/app/src/main/java/com/byd/dashcast/report/ClusterShotRecorder.kt
         } else if (ClusterShotSchedulePolicy.shouldAppPrune(
-            clusterId, now, sLastPruneMs, sLastDaemonPruneMs, PRUNE_INTERVAL_MS)) {
+            clusterId, now, sLastPruneMs, sLastDaemonPruneMs, PRUNE_INTERVAL_MS,
+            /* everCaptured = */ sLastCaptureMs != 0L)) {
             sLastPruneMs = now
             sExecutor.execute { prune(app) }
         }
```

**Expected gain (ESTIMATED).** **~2 880 ADB TCP+RSA handshakes/day eliminated** on a car that never projects; unchanged behaviour once a capture has happened.

**Regression risk.** Shots from a session that ended before a process restart are then aged out by the daemon's own bound or at next capture rather than by the app-side tick. `ClusterShotSchedulePolicy` has existing unit tests — extend them for the new parameter. No consent or redaction path is touched.

**Verify.** `adb logcat -s ClusterShotRecorder:* AdbLocalClient:*` for 10 min with projection stopped; count prune round-trips before/after.

---

### P3 — Gate the launch-path `dumpsys SurfaceFlinger`

**Class:** frame HIGH · **Confidence:** HIGH · **Effort:** S · **Lane:** C2

**Evidence**
- [`ClusterService.java:1301`](../../app/src/main/java/com/byd/dashcast/cluster/ClusterService.java#L1301) and [`:1243`](../../app/src/main/java/com/byd/dashcast/cluster/ClusterService.java#L1243) — **two** unconditional `sDiagExecutor.execute(…)` sites
- [`ClusterService.java:1311-1313`](../../app/src/main/java/com/byd/dashcast/cluster/ClusterService.java#L1311-L1313) — javadoc: *"Diagnostic only (no behaviour change)"*, then `Thread.sleep(1500)`
- [`ClusterService.java:1327-1334`](../../app/src/main/java/com/byd/dashcast/cluster/ClusterService.java#L1327-L1334) — the shell pipeline containing `dumpsys SurfaceFlinger`

**Root cause.** After **every** cluster launch, a diagnostic sleeps 1.5 s and then shells a pipeline of ~8 forked processes including `dumpsys SurfaceFlinger`, which serialises SF's entire layer tree **under SF's global lock** — 1.5 s into the projected app's cold start, when SF is at its busiest. Nothing consumes the output except a log line.

**Fix.** Make it opt-in, default off, reachable from Settings for field triage.

> **Correction to the lane's proposed fix.** Lane C proposed gating `tryClusterFixedActivityExperiment` alongside it. **Do not.** Despite the name, that call invokes `cmd car_service start-fixed-activity`, which has a real effect on AAOS units — gating it risks regressing DX_BYD_AUTO cluster launch. Only `verifyClusterDisplayState` is documented as behaviour-free, so only that is gated here. The lane also cited one call site; there are two.

```diff
--- a/app/src/main/java/com/byd/dashcast/cluster/ClusterService.java
+++ b/app/src/main/java/com/byd/dashcast/cluster/ClusterService.java
+    /**
+     * AUD-PERF-P3 — is the post-launch state dump enabled?
+     *
+     * {@link #verifyClusterDisplayState} shells a pipeline containing `dumpsys SurfaceFlinger`,
+     * which serialises SurfaceFlinger's whole layer tree under SF's global lock — 1.5 s into the
+     * projected app's cold start, i.e. exactly when SF is busiest and the driver-facing panel is
+     * most sensitive to a stall. Its own javadoc calls it "diagnostic only (no behaviour change)",
+     * and nothing consumes the output but a log line, so it is now opt-in.
+     *
+     * NOTE: tryClusterFixedActivityExperiment is deliberately NOT gated by this. Despite being
+     * described as an experiment it calls `cmd car_service start-fixed-activity`, which has a real
+     * effect on AAOS units; gating it would risk regressing cluster launch on DX_BYD_AUTO.
+     */
+    private boolean isLaunchDiagnosticsEnabled() {
+        try {
+            return getSharedPreferences(ClusterPrefs.PREFS_NAME, MODE_PRIVATE)
+                    .getBoolean("launch_diagnostics_enabled", false);
+        } catch (Throwable t) {
+            return false;
+        }
+    }

@@ site 1 (DL5 daemon path, ~line 1243)
             tryClusterFixedActivityExperiment(displayId, packageName, operation);
-                sDiagExecutor.execute(() -> verifyClusterDisplayState(
-                    displayId, packageName, operation));
+            if (isLaunchDiagnosticsEnabled()) {
+                sDiagExecutor.execute(() -> verifyClusterDisplayState(
+                        displayId, packageName, operation));
+            }

@@ site 2 (launchAndForce path, ~line 1301)
         tryClusterFixedActivityExperiment(displayId, packageName, operation);
-        sDiagExecutor.execute(() -> verifyClusterDisplayState(displayId, packageName, operation));
+        if (isLaunchDiagnosticsEnabled()) {
+            sDiagExecutor.execute(() -> verifyClusterDisplayState(
+                    displayId, packageName, operation));
+        }
```

Add a Settings switch alongside the existing `reconnect_popup_enabled` / `quick_stop_enabled` / `hotspot_watchdog_enabled` toggles, default off. (Reminder from project convention: the switch label needs an `R.string` in **all 12 locales**.)

**Expected gain (ESTIMATED).** Removes one SF-global-lock hold (typically 30–150 ms on a DL3-class SoC) and ~10 process forks from **every** projection start — the most safety-visible moment in the app.

**Regression risk.** Routine bug reports lose the pump-vs-placement evidence unless the tester enables the switch. No code path depends on the output — the callbacks only log. `tryClusterFixedActivityExperiment` is untouched, so AAOS behaviour is unchanged.

**Verify.** Perfetto `gfx`+`sched` during a projection start; look for the SF main-thread block on `dumpsys` and count SF frame misses in the following 2 s, with the switch off vs on.

---

### P4 — Ramp the daemon self-heal loop

**Class:** power HIGH · **Confidence:** HIGH · **Effort:** S · **Lane:** C1

**Evidence**
- [`ProxyDaemonMain.java:519-522`](../../app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java#L519-L522) — `while (true) { Thread.sleep(1_000L); … }`
- [`ProxyDaemonMain.java:526-536`](../../app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java#L526-L536) — `File.lastModified()` stat every tick
- [`ProxyDaemonMain.java:538-541`](../../app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java#L538-L541) — `healTriggerFile()` + `healPidLock()` every 10th tick

**Root cause.** The uid-2000 daemon runs an unconditional 1 Hz loop for its entire lifetime, stat-ing a trigger file every tick. That daemon survives app kills and head-unit uptime measured in **days** — so this is **86 400 wakeups and 86 400 `stat()` calls per day**, permanently.

> **Correction to the lane's framing.** Lane C called this "a backup for an already-armed FileObserver." The in-code comment says the opposite — it is *"the primary recovery path when FileObserver silently stops delivering events on DL5."* That raises the stakes, so the fix below is a **ramp**, not a flat backoff: the DL5 inotify failure this guards against manifests at bootstrap, so full 1 Hz sensitivity is preserved for the first minute.

```diff
--- a/app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java
+++ b/app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java
                 long lastTriggerMtime = new File(TRIGGER_FILE).lastModified();
                 int tick = 0;
+                // AUD-PERF-P4 — cadence ramp. This poll is the primary recovery path when the
+                // FileObserver silently stops delivering on DL5, and that failure shows up during
+                // bootstrap — so keep the full 1 Hz sensitivity for the first RAMP_TICKS seconds
+                // and fall to 10 s afterwards. Previously this woke the uid-2000 daemon 60x/min
+                // for the life of the head unit: ~86 400 wakeups and ~86 400 stat() calls per day
+                // on a process that outlives the app and survives its kills.
+                final int RAMP_TICKS = 60;
                 while (true) {
                     try {
-                        Thread.sleep(1_000L);
+                        Thread.sleep(tick < RAMP_TICKS ? 1_000L : 10_000L);
                     } catch (InterruptedException ignore) {
                         return;
                     }
@@
-                    // Every 10 ticks (10s): full self-heal (file + pid lock).
-                    if (++tick % 10 == 0) {
+                    // Full self-heal (file + pid lock). Held at ~10 s during the ramp and ~30 s
+                    // after it, independent of the poll interval above.
+                    ++tick;
+                    if (tick < RAMP_TICKS ? (tick % 10 == 0) : (tick % 3 == 0)) {
                         try { healTriggerFile(); } catch (Throwable th) { log("heal trigger: " + th); }
                         try { healPidLock();     } catch (Throwable th) { log("heal pid: " + th); }
                     }
```

**Expected gain (ESTIMATED).** Daemon wakeups **60/min → 20/min** after the first minute — **~57 600 fewer wakeups and stat() calls per day**. (Originally specified as 6/min via a 10 s poll; corrected to 3 s — see the regression-risk note below.)

**Regression risk.** ⚠️ **THIS PARAGRAPH WAS WRONG AND THE SHIPPED 1.8.39-beta PATCH WAS DEFECTIVE BECAUSE OF IT.** It claimed the ramp preserved 1 Hz through the window the DL5 inotify failure appears in. It does not: the ramp is anchored on *daemon* start, but that failure surfaces on *app* restart against a daemon alive for days — always far past the ramp. Worse, [`ProxyClient.java:502-512`](../../app/src/main/java/com/byd/dashcast/proxy/ProxyClient.java#L502-L512) waits only **5 000 ms** for the daemon's rebroadcast, and its comment names *this poll's 1 s period* as the reason that budget is safe. A 10 s post-ramp poll is unconditionally larger, so the cheap recovery path would time out **every time it was needed** and fall through to a ~31 s kill-and-respawn. **Corrected: the post-ramp poll is 3 s, not 10 s** — the invariant holds with 2 s of margin and `ProxyClient` is untouched. See §9.

**Verify.** Perfetto `sched` filtered to `dashcast-self-heal` — count `sched_wakeup`/min before/after. Confirm the FileObserver path still works by touching the trigger file and watching `binder_driver` for the rebroadcast.

---

### P5 — Pre-size the log StringBuilder

**Class:** memory MED · **Confidence:** HIGH · **Effort:** S · **Lane:** B4 · **Behavioural risk: none**

**Evidence**
- [`AppLogger.kt:268`](../../app/src/main/java/com/byd/dashcast/util/AppLogger.kt#L268) — `StringBuilder(snapshot.size * 80)`
- [`AppLogger.kt:49`](../../app/src/main/java/com/byd/dashcast/util/AppLogger.kt#L49) — `MAX_ENTRIES = 5000`
- [`AppLogger.kt:55`](../../app/src/main/java/com/byd/dashcast/util/AppLogger.kt#L55) — `MAX_TOTAL_CHARS = 2_000_000L`
- [`AppLogger.kt:73`](../../app/src/main/java/com/byd/dashcast/util/AppLogger.kt#L73) — `sTotalChars`, already maintained under `LOCK`

**Root cause.** Lane B re-verified that the byte budget **genuinely holds** — `addEntry` is the only writer, `sTotalChars` is incremented on add and decremented on evict, and the eviction loop honours both bounds. What does *not* hold is `get()`: it sizes the builder at `snapshot.size * 80` = 400 000 chars while the retained content can be ~2 000 000. The builder therefore doubles 400 K → 800 K → 1.6 M → 3.2 M, copying the whole `char[]` each time, peaking around **12 MB** (new array + old array + the `toString()` copy). This runs on every bug report and every log share — precisely when a user is already in trouble.

The exact number needed is already tracked one field away.

```diff
--- a/app/src/main/java/com/byd/dashcast/util/AppLogger.kt
+++ b/app/src/main/java/com/byd/dashcast/util/AppLogger.kt
         val snapshot: Array<Entry>
+        val totalChars: Long
         synchronized(LOCK) {
             snapshot = sEntries.toTypedArray()
+            totalChars = sTotalChars
         }
-        val sb = StringBuilder(snapshot.size * 80)
+        // AUD-PERF-P5 — size from the REAL retained char count, not a guess. `size * 80` gives
+        // 400 K for a full buffer whose content can be ~2 M chars, so the builder doubled four
+        // times, copying the whole char[] each time and peaking around 12 MB — on every bug
+        // report and every log share, i.e. exactly when the user is already in trouble.
+        // sTotalChars is already maintained under LOCK for the eviction budget; ~40 chars/entry
+        // covers the "[timestamp][LEVEL][tag] " prefix and the trailing newline.
+        val hint = (totalChars + snapshot.size * 40L)
+            .coerceIn(256L, Int.MAX_VALUE.toLong())
+            .toInt()
+        val sb = StringBuilder(hint)
```

**Expected gain (ESTIMATED).** **~8 MB off the peak** of every `AppLogger.get()`, and ~5 M chars of array-copy garbage removed per call. Steady-state retention unchanged.

**Regression risk.** **None.** This is a capacity hint only; the produced String is byte-identical.

**Verify.** Allocation tracker (`adb shell am profile start`) around one "share log" action; compare total `char[]` bytes allocated.

---

## 4. Tier 2 — further S-effort wins (P6–P21)

Full detail is in the lane records; each is source-cited and ready to expand into a patch on request.

- **P6** [`accessibility_ime_watcher.xml:15`](../../app/src/main/res/xml/accessibility_ime_watcher.xml#L15) + [`ClusterImeWatcherService.java:148-149`](../../app/src/main/java/com/byd/dashcast/ime/ClusterImeWatcherService.java#L148-L149) — `typeWindowStateChanged` is subscribed in **both** the XML and the runtime `setServiceInfo`, and handled **nowhere** ([`:185`](../../app/src/main/java/com/byd/dashcast/ime/ClusterImeWatcherService.java#L185) drops it). Every activity/dialog transition on the entire head unit crosses a Binder boundary into this process to be discarded. **Lane E's proposed XML-only fix would have been a silent no-op** — the runtime override re-adds it. Both sites must change. Also reorder the gates so `ClusterService.isRunning()` is checked before `mIsDiLink5`.
- **P7** [`ProxyKeeperService.java:148`](../../app/src/main/java/com/byd/dashcast/proxy/ProxyKeeperService.java#L148) — a real `pingBinder()` IPC every 10 s regardless of screen or projection state; 8 640/day. The prior audit's `pingBinder`→`isBinderAlive` migration landed in `ProxyClient.isConnected()` but **not** here. Alternate: `isBinderAlive()` on 5 of 6 ticks.
- **P8** [`DisplayStatePollCoordinator.kt:70`](../../app/src/main/java/com/byd/dashcast/ui/main/DisplayStatePollCoordinator.kt#L70) — 2× `pidof` every 5 s = 24 IPC/min while foreground, unconditional. Widen to 15 s and skip when nothing changed.
- **P9** [`ClusterImeWatcherService.java:132`](../../app/src/main/java/com/byd/dashcast/ime/ClusterImeWatcherService.java#L132) is **the only thread in 48 031 LOC that sets a priority**. Add `THREAD_PRIORITY_BACKGROUND` to `cluster-shot-recorder`, `shell-gateway`, `cluster-diag-thread`, `adb-local-*`, `app-repo-loader`, `proxy-keeper`. Deliberately **not** `move-task-thread`, `hud-nav-writer` or `proxy-reconnect` — those are user-visible latency paths.
- **P10** [`Phase4TaskVerbs.java:1034`](../../app/src/main/java/com/byd/dashcast/proxy/daemon/Phase4TaskVerbs.java#L1034) — per-launch thread polling 2×/s for up to 90 s. Ramp to 2 s after the first ~10 polls.
- **P11** [`ClusterService.java:150,161`](../../app/src/main/java/com/byd/dashcast/cluster/ClusterService.java#L150) — two `Executors.newSingleThreadExecutor` (unbounded queues) on the launch path; the diag one sleeps 1.5 s per item. `BoundedSerialExecutor` already exists next door. Cap the diag executor at 1 (a superseded diagnostic is worthless); wire `move-task` rejections to the launch-failure path, never a silent drop.
- **P12** [`MainActivity.kt:624`](../../app/src/main/java/com/byd/dashcast/MainActivity.kt#L624) — the only unguarded line in an otherwise individually-guarded teardown. If it throws, `unbindService` never runs and the Activity leaks via a live `ServiceConnection`.
- **P13** [`CanFeedbackListener.java:113`](../../app/src/main/java/com/byd/dashcast/proxy/daemon/CanFeedbackListener.java#L113) — `String.format("%02x")` per byte (~250 allocations/event), and the line is built **before** the `size() < CAP` check, so it is formatted-then-discarded once the buffer fills, which is the normal state.
- **P14** [`MapNotificationListenerService.java:776`](../../app/src/main/java/com/byd/dashcast/hud/MapNotificationListenerService.java#L776) — unconditional `Log.d` with two boxed ints per guidance frame, plus three separate `toLowerCase` copies.
- **P15** [`FullscreenMirrorCoordinator.java:141,200`](../../app/src/main/java/com/byd/dashcast/ui/main/FullscreenMirrorCoordinator.java#L141) — hardcoded `postDelayed(…, 250)` instead of `doOnNextLayout`. Wastes up to ~230 ms of frozen preview on fast hardware and races on slow.
- **P16** [`DaemonBinderResolver.kt:172`](../../app/src/main/java/com/byd/dashcast/proxy/DaemonBinderResolver.kt#L172) — `Class.forName` + `getDeclaredMethod` + `setAccessible` on every lookup, plus a servicemanager IPC; `fetch()` spawns a thread per call. Cache the `Method`; guard a cached binder with `isBinderAlive()` as `Phase4DisplayVerbs` already does.
- **P17** [`SurfaceDaemon.java:675-679`](../../app/src/main/java/com/byd/dashcast/proxy/daemon/SurfaceDaemon.java#L675-L679) — the frame-wait loop sleeps **before** its first `acquireLatestImage()`, guaranteeing a 50 ms floor per capture (100 ms/round) and burning 3 s/round in the known black-frame failure mode, with the extra SF display live throughout.
- **P18** [`FloatingRemoteButton.java:133-140`](../../app/src/main/java/com/byd/dashcast/system/FloatingRemoteButton.java#L133-L140) — creates the notification + overlay window on every cold start, then immediately demotes if the badge should be hidden.
- **P19** [`MainActivity.kt:252`](../../app/src/main/java/com/byd/dashcast/MainActivity.kt#L252) — `unlockHiddenApis()` sync on the main thread at every cold start, for an in-app mirror path the code's own field evidence records as *"235 × FAILED and 0 successes"*. Make it lazy on first `startMirror()`.
- **P20** [`FissionOrchestrator.java:385`](../../app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java#L385) — `sAutoStartOrchestrator` is never cleared, and `sAutoStartFired` guarantees the only teardown path never runs. Retains an idle `fission-exec` thread for the process lifetime.
- **P21** [`AdbLocalClient.java:58`](../../app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.java#L58) — fixed pool of 4, no `allowCoreThreadTimeOut`; threads persist for days after a single one-shot use.

---

## 5. Tier 3 — structural (P22–P31)

- **P22** — `Redactor` rewrites the whole body 16× via `regex.replace` ([`Redactor.kt:365,450`](../../app/src/main/java/com/byd/dashcast/report/Redactor.kt#L365)); on a 4 MB cap that is ~60 MB of churn and a 20–30 MB live peak. Fix: single alternation `Pattern` with named groups + `appendReplacement`, or chunk on line boundaries. **Privacy choke point — keep every per-rule test green and keep the fail-open catch.**
- **P23** — Capture pipeline efficiency (`B3`+`D3`+`D5`): ~21 MB of ARGB_8888 per round; `ImageReader`, display token and `Transaction` rebuilt per capture; `imageToBitmap` allocates **two** full-resolution bitmaps whenever the gralloc stride is padded (the common case). Cache per `(layerStack,w,h)`, drop the double allocation. Compounds with P1 — do P1 first, it is free.
- **P24** — HUD guidance costs 7 serial synchronous Binder RTTs per frame ([`HudController.java:144`](../../app/src/main/java/com/byd/dashcast/hud/HudController.java#L144)) though `CanBusController.sendBatch` is documented as one RTT. Merge to ~3. Preserve per-section failure semantics.
- **P25** — Touch injection has **no drop or coalesce policy** ([`ClusterInputForwarder.kt:253`](../../app/src/main/java/com/byd/dashcast/cluster/mirror/ClusterInputForwarder.kt#L253)); oneway transactions accumulate in the shared 1 MB async binder buffer if the daemon falls behind. **This domain explicitly prefers a dropped touch to a late one.** Coalesce `ACTION_MOVE` to one per vsync; never drop DOWN/UP/CANCEL/POINTER_*.
- **P26** — Every shell command forks **3** processes + a temp file + a thread ([`ProxyShell.java:97`](../../app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyShell.java#L97)). Add a short-timeout fast path for `pidof`/`am`/`wm`.
- **P27** — `AdbLocalClient` re-authenticates a **new** ADB connection per command ([`:213,250,299`](../../app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.java#L213)). Pool it — but ship P2 first: it removes most of the volume with none of the risk.
- **P28** — Preview mirror survives `onStop` when a cluster app is active ([`MainActivity.kt:592-601`](../../app/src/main/java/com/byd/dashcast/MainActivity.kt#L592-L601)). **Magnitude downgraded from the lane's claim** — see §7.
- **P29** — `-keep class com.byd.dashcast.proxy.** { *; }` retains ~3 855 lines that R8 could shrink; only `proxy.daemon.**` is launched by name. Needs the documented on-car matrix.
- **P30** — Full-density launcher icons for every installed app held for MainActivity's lifetime, drawn at 56dp ([`AppRepository.kt:251`](../../app/src/main/java/com/byd/dashcast/data/apps/AppRepository.kt#L251)). Pre-raster into an `LruCache`, or load lazily in `onBindViewHolder`.
- **P31** — Mirror sinks into a `TextureView` ([`activity_main.xml:820`](../../app/src/main/res/layout/activity_main.xml#L820)), so SF's output must be re-sampled and redrawn by the app's RenderThread — a full extra GPU blit that a `SurfaceView` avoids. The team already tuned around one symptom of this ([`MainActivity.kt:329-335`](../../app/src/main/java/com/byd/dashcast/MainActivity.kt#L329-L335) documents dropping `LAYER_TYPE_HARDWARE` because a second hardware layer *"pushed frames past the 16 ms vsync budget"*). **Highest structural upside, highest regression risk** — the mirror lifecycle is threaded through three coordinators across three DiLink generations.

---

## 6. What the audit did NOT find

Negative results, recorded so they are not re-investigated:

- **No encoder, no frame streaming, no per-frame readback.** Exhaustively verified across ~20 symbols (§0 of `architecture.md`). Zero media dependencies in `app/build.gradle`.
- **No leaked receiver, listener, `linkToDeath`, `Parcel`, `Surface` or `SurfaceControl` token** on any normal path. Lane B checked all 15 `Parcel`-using files for `obtain`/`recycle` balance.
- **`AppLogger`'s byte budget genuinely holds** — single writer, correct increment/decrement, eviction honours both bounds.
- **The touch path's `PointerProperties[16]`/`PointerCoords[16]` pre-allocation still holds** with no regression since it was introduced.
- **Executor shutdowns, receiver unregisters and `SurfaceControl` token releases from prior audits all hold** in current code — verified, not trusted from comments.
- **Backpressure is correct in 5 of 7 producer/consumer pairs**: nav→HUD coalesces to latest-value; `ShellGateway` rejects at 64; log ingestion drops oldest; the SF audit worker rejects at 1; capture is time-throttled. The two exceptions are P11 and P25.
- **RecyclerView binding, `PackageManager` calls, `SharedPreferences` commits and the mirror's double-scaling math** were all examined by Lane A and produced **no finding** — prior passes did their job.
- **Thread count is not a memory problem**: ~20 app-owned threads at peak, ~20 MB VA / ~1 MB RSS, no runaway pool.

---

## 7. Corrections and refutations log

Recorded rather than quietly amended.

1. **My Phase 1 claim was partially refuted.** I asserted `ImageReader` was "only in the bug-report path, not a hot path." Lane D refuted it: the recorder is default-ON and captures every ~20 s while projecting. I independently re-verified the refutation at source. `architecture.md` §0.1 now carries the correction. The architectural core — no encode, no streaming, no per-frame readback — survived.
2. **Lane E's P6 fix would have been a silent no-op.** It claimed the runtime `setServiceInfo` omits `TYPE_WINDOW_STATE_CHANGED`; the runtime explicitly **adds** it. An XML-only patch would have been undone at service connect. Both sites must change.
3. **Lane C's P3 fix would have risked an AAOS regression.** It proposed gating `tryClusterFixedActivityExperiment` with the diagnostic. That call invokes `cmd car_service start-fixed-activity`, which has a real effect. Only `verifyClusterDisplayState` is gated. The lane also cited one call site; there are two.
4. **Lane C mischaracterised the daemon self-heal loop** as a backup for the FileObserver; the in-code comment calls the poll the *primary* DL5 recovery path. The fix became a ramp rather than a flat backoff.
5. **Lane C's timing on P3 was overstated** — the diagnostic fires at +1.5 s after launch, not concurrently with it. Still a frame hazard during cold start; claim tempered, finding retained.
6. **Lane D's P28 magnitude was downgraded.** It called the backgrounded mirror *"likely the single largest steady-state GPU cost."* That does not follow: the sink is a `TextureView`'s `SurfaceTexture`, a BufferQueue consumer that only drains when the RenderThread draws. With the Activity stopped, nothing consumes, so after ~2–3 buffers SF cannot dequeue and drops rather than compositing indefinitely. The waste — a display token and SF virtual display held open — is real but **bounded**. Gain is now measurement-gated, not asserted.
7. **I under-called the a11y retrieval flags.** I wrote that `flagRetrieveInteractiveWindows` / `canRetrieveWindowContent` were "justified" because `getWindows()` needs them. True only *while projecting*: the flag is held permanently, and in AOSP it makes WindowManager recompute and Binder-push the full cross-display window list on **every** window or z-order change system-wide. On a unit whose OEM re-fronts its own activity every 1–2 s that is continuous idle work done by `system_server` on our behalf. It can be set and cleared at runtime on the same condition the handler already tests. Recorded as R2-2.
8. **The a11y config's own comments contradict its config**

---

## 8. Status

All findings are **`ESTIMATED`**. Run `measure.md` on a head unit and paste the artefacts; each finding's `CONFIDENCE` and tag are then rewritten to `MEASURED` with observed numbers — **including any the data refutes, which will be struck rather than quietly dropped.**

**No code has been modified. Awaiting explicit per-item GO.**

---

## 9. Second pass — verification of the shipped fixes, and the packages nobody owned

Ran **after v1.8.39-beta was already published**: one lane tasked adversarially with breaking the
five shipped fixes, one on the packages no first-pass lane owned, one on the gaps the first-pass
lanes declared in their own summaries.

### 9.1 Three defects in my own shipped patches

| ID | Severity | What |
|---|---|---|
| **R1-2** | **SHIPPED REGRESSION** | P1 stretched captures to 90 s but left the prune's daemon-staleness bound at a fixed 30 s. That stamp is refreshed only by a successful capture, so it went stale *between* captures and the app-side prune fired at t=30 s and t=60 s of every cycle — **~80 ADB TCP+RSA handshakes per hour of projection, where 1.8.38 had zero.** It handed back a slice of P2's saving during the exact window P1 set out to protect. |
| **R1-5** | **SHIPPED REGRESSION** | P4's 10 s post-ramp poll silently invalidated a documented cross-file timing contract: `ProxyClient` waits 5 000 ms for the daemon rebroadcast *because* that poll was 1 s. REBROADCAST only ever runs against an already-alive daemon, i.e. always post-ramp — so on DL5 units where inotify has died, the cheap recovery would time out **deterministically** and fall through to a ~31 s kill-and-respawn. |
| **R1-3** | Latent | `clear()` reset the latches synchronously but deleted asynchronously and swallowed failures. With the transport down — the common state when a report is filed — up to 12 screenshots of both driver-facing screens survived on disk while `everCaptured` went permanently false, so the max-age sweep never ran again. |

Clean under attack: **P3** (both call sites gated, no third caller, no state mutated, runs
off-main-thread, `tryClusterFixedActivityExperiment` confirmed still unconditional), **P5**
(snapshot and `sTotalChars` read under the same lock, accounting symmetric, no overflow, output
byte-identical), **P1's latch** (single-threaded caller; no path reports ≤0 then returns to the same
projection), and the release mechanics.

**All three are fixed**, with regression tests pinning both the projecting and the idle cadence. The
root cause of R1-2 was one parameter serving two different concepts in `shouldAppPrune`; it is now
two. R1-5 is fixed with a **3 s** post-ramp poll rather than by raising `ProxyClient`'s budget —
preserving the invariant with margin and touching no critical file — and the constant now carries an
explicit warning naming the coupling.

### 9.2 The packages nobody audited

No first-pass lane owned `update/`, `system/`, `model/`, `domain/`, `platform/`, most of `util/`, or
`app/`. **Zero periodic work exists in any of them** — every `postDelayed` there is one-shot and
user- or boot-triggered. The cost is elsewhere:

- **R2-1** (power HIGH) — the OTA check runs on **every** fresh `MainActivity` creation with **no
  interval throttle, no connectivity check anywhere in the app** (repo-wide grep for
  `ConnectivityManager`/`NetworkCapabilities` returns **0**) and no auto-check opt-out. On a dead
  link it burns a 10 s connect timeout. `MainActivity.kt:384`, `update/UpdateChecker.java:107`.
- **R2-2** (power MED) — `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` held permanently; see §7.7.
- **R2-3** (power MED) — the check fetches `per_page=10` full release objects, each with its complete
  markdown changelog, then `break`s on the first match; ~9 are parsed for nothing, into an uncapped
  `ByteArrayOutputStream`. `update/UpdateChecker.java:54`.
- **R2-4** (startup MED) — `DashCastApp.onCreate` moves every prefs touch to a background thread
  *"so the cold-start critical path is free of disk I/O"*, then calls `clearLegacyOverscanPrefs()`
  on the main thread at `:77`, forcing a synchronous parse of the largest prefs file in the app.
- **R2-5** (memory MED) — the downloaded OTA APK is **never deleted**; `grep` confirms no `.delete()`
  anywhere in `update/`. It sits in the external files dir, not `cacheDir`, so the framework never
  reclaims it: tens of MB resident forever on any unit that has taken an OTA.
- **R2-6**, **R2-7** (LOW) — a 500 ms sleep-before-read in the daemon binder poll; the TetherFi check
  re-hitting the API on every `HotspotActivity.onResume` with no cache.

### 9.3 The declared blind spots

`DashboardDisplayHelper` and `AppStartupTasks` came back **clean**. `ClusterManager`'s 21
interval-bearing sites — flagged in `architecture.md` as the repo's largest concentration — are
**almost all activation-scoped and self-terminating**; only the late-arrival watch's 2 s poll (R3-2)
has an idle-adjacent tail, and only after a failed activation. Also found: a mirror stop/restart per
cold-start resize pass (R3-1), `commit()` on the shared 4-thread ADB executor in `ReportChannel`
(R3-3), the 57-container Settings layout (R3-4), and unreleased View listeners in
`MirrorCoordinator.destroy()` (R3-5).

### 9.4 What this pass says about the first one

The first pass produced 30 findings and five patches. **Three of those five carried a defect that
only an adversarial re-read found, and two of them shipped.** Two of the three share one failure
mode: **a timing constant was changed without auditing what else had been calibrated against it.**
P1's cadence was coupled to a prune bound in another file; P4's poll period was coupled to a timeout
in another *process*. Neither coupling was visible at the patch site, and both were documented in a
comment elsewhere in the tree.

The rule this yields, recorded for next time: **when a patch changes an interval, grep for every
other constant that mentions it before shipping.**

