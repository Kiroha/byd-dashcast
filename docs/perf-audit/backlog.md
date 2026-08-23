# DashCast — Performance Backlog

Derived from [`report.md`](report.md). Ordered for execution, not for reading.
**Nothing here is applied.** Each item needs an explicit GO.

---

## Wave 0 — Top 5 quick wins

All `S` effort, all source-verified, patches inline in [`report.md` §3](report.md#3-top-5-quick-wins--proposed-patches).
Do them in this order — P1 and P2 are the same subsystem, and P1 makes P23 smaller.

| # | Item | Class | Files | Risk | Gain (ESTIMATED) |
|---|---|---|---|---|---|
| **P1** | Screenshot recorder cadence ramp (15 s → 90 s after a 2 min window) | power HIGH | `ClusterShotRecorder.kt` | Low — shot may be ≤90 s stale outside the ramp | ~270 fewer captures + encodes **per hour of driving** |
| **P2** | Skip the prune tick when the process has never captured | power HIGH | `ClusterShotSchedulePolicy.kt`, `ClusterShotRecorder.kt` | Low — extend the existing policy unit tests | **~2 880 ADB TCP+RSA handshakes/day** on an idle car |
| **P3** | Gate the launch-path `dumpsys SurfaceFlinger` behind an opt-in switch | frame HIGH | `ClusterService.java` (**2 sites**), `SettingsActivity.kt`, `values*/strings.xml` ×12 | Low — output is log-only; AAOS probe deliberately untouched | One SF **global-lock** hold (~30–150 ms) + ~10 forks off **every** launch |
| **P4** | Ramp the daemon self-heal loop (1 s → 10 s after 60 s) | power HIGH | `ProxyDaemonMain.java` | Low–Med — lengthens cold bootstrap only if inotify has *also* failed | **~77 700 fewer wakeups + stat() calls/day** |
| **P5** | Pre-size `AppLogger.get()`'s StringBuilder from `sTotalChars` | memory MED | `AppLogger.kt` | **None** — capacity hint only, output byte-identical | **~8 MB** off peak per bug report / log share |

**Wave 0 acceptance:** `./gradlew :app:assembleRelease` clean, lint 0/0, existing unit tests green (`ClusterShotSchedulePolicyTest` extended for P2), then one on-car projection cycle on DL3 and DL5 confirming projection, mirror, HUD and bug-report send all still work.

**Open product decision, not a perf call:** whether `PREF_ENABLED` should default to `false` on release builds and `true` on beta ([`ClusterShotRecorder.kt:72`](../../app/src/main/java/com/byd/dashcast/report/ClusterShotRecorder.kt#L72)). That removes P1's cost entirely for most users at the price of support-tooling reach. Yours to decide — say the word and it is a two-line patch.

---

## Wave 1 — remaining S-effort items

Group by file to keep the review surface small.

**1a · Idle-cost cluster** (same theme as Wave 0, no new subsystems)
- **P7** Keeper heartbeat: `isBinderAlive()` on 5 of 6 ticks, real `pingBinder()` on the 6th — `ProxyKeeperService.java:148`
- **P8** `DisplayStatePollCoordinator` 5 s → 15 s + skip unchanged ticks — `DisplayStatePollCoordinator.kt:70`
- **P10** Fission watchdog: 500 ms for ~10 polls, then 2 s — `Phase4TaskVerbs.java:1034`
- **P6** Drop `typeWindowStateChanged` from **both** the XML **and** the runtime `setServiceInfo`; reorder the `isRunning()` gate ahead of `mIsDiLink5` — `accessibility_ime_watcher.xml:15`, `ClusterImeWatcherService.java:148-149,184-191`
  > An XML-only change is a **no-op** — the runtime override re-adds the event type.

**1b · Allocation hygiene** (zero behavioural surface)
- **P13** `toHex` nibble table + move the `size() < CAP` check above the format — `CanFeedbackListener.java:113,65`
- **P14** `Log.isLoggable` gate + single lowercase — `MapNotificationListenerService.java:776,659-672`
- **P19** Make `unlockHiddenApis()` lazy on first `startMirror()` — `MainActivity.kt:252`

**1c · Lifecycle correctness**
- **P12** Guard `unregisterReceiver` and move it above the `mServiceBound` block — `MainActivity.kt:624`
- **P20** Shut down + null `sAutoStartOrchestrator` on terminal outcomes — `FissionOrchestrator.java:385`
- **P18** Check `sShouldBeVisible` before `startForegroundCompat()`/`createOverlay()` — `FloatingRemoteButton.java:133-140`
- **P21** `allowCoreThreadTimeOut(true)` on the ADB pool — `AdbLocalClient.java:58`

**1d · Latency**
- **P15** `doOnNextLayout` instead of `postDelayed(…, 250)` — `FullscreenMirrorCoordinator.java:141,200`
- **P17** Acquire before sleeping; cut the failure budget 1 500 → ~400 ms — `SurfaceDaemon.java:675-679`
- **P16** Cache the reflected `Method`; guard a cached binder with `isBinderAlive()` — `DaemonBinderResolver.kt:172`
- **P11** Move both launch-path executors to `BoundedSerialExecutor` (diag cap 1) — `ClusterService.java:150,161`

**1e · Thread priority — do this one alone**
- **P9** `THREAD_PRIORITY_BACKGROUND` on `cluster-shot-recorder`, `shell-gateway`, `cluster-diag-thread`, `adb-local-*`, `app-repo-loader`, `proxy-keeper`.
  > **Not** on `move-task-thread`, `hud-nav-writer` or `proxy-reconnect` — those are user-visible latency paths. `THREAD_PRIORITY_BACKGROUND` moves a thread to the little-cores cpuset on most BYD SoCs; verify launch and bug-report timings did not regress.

---

## Wave 2 — structural

Sequenced by dependency, not by size.

1. **P23 — capture pipeline efficiency** (`M`). Cache `ImageReader` + display token per `(layerStack,w,h)`; remove the double bitmap allocation in `imageToBitmap`. **Do after P1** — P1 cuts the call rate first, for free, which shrinks the payoff and lets you decide whether P23 is still worth the daemon-side risk.
2. **P22 — Redactor single-pass** (`M`). Privacy choke point. Keep every per-rule test green; keep the fail-open catch so a redaction bug never sinks a report.
3. **P24 — HUD batch verb** (`M`). 7 → ~3 Binder RTTs per guidance frame. Preserve per-section failure semantics: check the batch's `applied` count rather than throwing.
4. **P26 + P27 — shell/ADB transport** (`M` each). Fast path for `pidof`/`am`/`wm`; pool the ADB connection. **P2 first** — it removes most of the volume with none of the risk, and may make P27 not worth doing.
5. **P25 — touch coalescing** (`M`). One `ACTION_MOVE` per vsync; **never** drop DOWN/UP/CANCEL/POINTER_*. This is the one finding that needs a trace to *confirm* rather than refute — measure before building.
6. **P28 — stop the mirror on `onStop`** (`S–M`). Mechanism confirmed, magnitude uncertain. **Measure first** (§7.6 of the report) — if SF is already dropping frames into the stalled BufferQueue, this is cheap correctness rather than a win.
7. **P30 — icon cache** (`M`). Pre-raster to grid size into an `LruCache`, or load lazily in `onBindViewHolder`.
8. **P29 — narrow the proguard keep** (`M`). Needs the documented DL3+DL5 on-car matrix; a wrong keep rule fails only on-car.
9. **P31 — `TextureView` → `SurfaceView`** (`M–L`). **Highest structural upside, highest risk.** Do last, alone, with the full three-generation on-car matrix. Do not attempt before traces confirm the mirror actually costs what A1 estimates.

---

## Blocked on measurement

Everything is `ESTIMATED`. These specifically should not be built before a trace:

| Item | Why it needs data first |
|---|---|
| **P31** | The entire premise is a per-frame GPU blit. Measure `gfxinfo`/GPU time with the mirror on vs off. If it is small, the risk is unjustified. |
| **P25** | Confirms rather than refutes — needs `binder_transaction_async` count vs `MotionEvent` count during a drag. |
| **P28** | Magnitude downgraded; measure before spending the regression risk. |
| **P23** | P1 changes the call rate, so measure *after* P1 lands. |

**Prerequisite for all of the above:** run [`measure.md`](measure.md) — 90 s Perfetto capture with the 6-segment scenario (three segments deliberately idle), plus the `dumpsys` set and a 30 min thermal delta.

---

## Answers still needed

From the Phase 1 close-out — none block Wave 0.

1. **Target fleet** — DL3 (API 29) only, or DL3 + DL5.0/5.1 (A13) + DL4? Decides whether API-30+ APIs are available.
2. **Reference cluster geometry** — the mirror defaults to 1920×720 ([`ClusterMirrorManager.kt:75-76`](../../app/src/main/java/com/byd/dashcast/cluster/mirror/ClusterMirrorManager.kt#L75-L76)) but presets imply 1280×480 / 12.3″ / 10.25″ panels. Sets the reference for P31's scaling analysis.
3. **SoC + thermal data** — without it, every CPU/thermal figure stays a ratio rather than an absolute.
4. **Car access for `measure.md`** — determines when findings leave `ESTIMATED`.
