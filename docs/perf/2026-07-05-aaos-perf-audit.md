# AOSP-Automotive Performance Audit — MyBYDApp / DashCast

> ⚠️ **HISTORICAL SNAPSHOT — 2026-07-05. Do not apply.**
> R8 has been enabled for release builds since **1.8.3-beta** (commit `269e2533`, 2026-07-26),
> and `app/proguard-rules.pro` exists — it is the source of truth for keep rules, not this
> document. `shrinkResources true` followed in **1.8.32-beta**. Every statement below about
> minification being off, or about `proguard-rules.pro` being absent, was true when written and
> is false now. Read this file for its reasoning, never for its state of the world.

**Date:** 2026-07-05 · **Branch:** `switch-kotlin` · **Scope:** 137 source files, ~46k LOC
**Method:** graphify-grounded multi-agent workflow — 8 perf finders (1 per AOSP pillar) → per-finding adversarial verification (re-anchored to current code) → dedup/rank by drain × fix-safety → full patch generation.
**Verification tally:** 18 findings adversarially verified → **11 CONFIRMED, 5 ADJUSTED, 2 REFUTED**. 16 survive into the ranked matrix below.
**Delivery mode:** DRAFT ONLY. No change is applied to the build tree. Patches live in [`2026-07-05-perf-patches.md`](2026-07-05-perf-patches.md).

## Relationship to the 2026-07-05 hardening audit

This pass is **complementary, not a re-run**. The hardening audit (same branch, same day — see the published "DashCast Hardening Audit" Artifact + [`../hardening/`](../hardening/)) already confirmed 30 reliability/ANR findings and drafted two ANR patches:

- **HIGH-1** `MapNotificationListenerService` nav → ~9 sync proxy verbs on the listener main thread — patch drafted at [`../hardening/2026-07-05-patch-1-hud-nav-offload.md`](../hardening/2026-07-05-patch-1-hud-nav-offload.md).
- **HIGH-2** `ClusterService` launch cascade (`cleanFissionStacks` + `launchOnDashboardWithBounds`) sync proxy calls on `mMainHandler` — patch drafted at [`../hardening/2026-07-05-patch-2-cluster-launch-offload.md`](../hardening/2026-07-05-patch-2-cluster-launch-offload.md).
- **HIGH-root** `ProxyClient.callWithRetry` pre-flight `attemptReconnect()` up to ~23 s block on the caller thread — intentionally staged (High fix-risk), land after 1–2.

Those are the top of the combined severity list and are **not regenerated here**. This performance pass adds the lens the hardening audit under-covered: **rendering/FPS & per-frame allocation, cold-start init ordering, GC/allocation churn in real-time loops, and Binder transaction payload sizing.**

---

## Phase 1 — Full-App Automotive Resource & Bottleneck Map

### Critical hotspots (by pillar)

**Pillar 2 · Cold-start / boot latency** — the DL5 launcher critical path
- `Platform.isClusterTaskResizeSupported()` forks a shell + `Process.waitFor(1500ms)` on a cold cache, and `MainActivity.setupCoordinators()` (`:1483`) calls it **synchronously on the UI thread** during `onCreate`. On a first-launch / prefs-wipe cold start it can block the launcher's first frame for up to 1.5 s while it races the startup prime worker (no synchronization between them). **HIGHEST cold-start hotspot.**
- `DashCastApp.onCreate` (`:27`) evaluates `isDiLink5(this)` just to interpolate `effectiveDiLink5=` into a log line — forcing the **first `byd_app_prefs` XML parse synchronously on the main thread** before any Activity exists (DL5 only; DL3/DL4 short-circuit earlier).
- `MainActivity.onCreate` (`:249`) unconditionally `startService(FloatingRemoteButton)`, which inflates a `WindowManager` overlay (`addView`) on the main looper — competing with the launcher's first traversal even though the badge starts `GONE`.
- `app/build.gradle` release build ships with **R8/resource-shrinking OFF** — the full un-tree-shaken dex + resource table are loaded/verified on *every* process spawn (launcher, BootReceiver, notification-listener delivery). `proguardFiles` references a `proguard-rules.pro` **that does not exist** — zero keep-rule infrastructure.

**Pillar 4 · Rendering / FPS / overdraw**
- `ClusterCanvasView.onDraw` (`:169`, `:181`) builds fresh label `String`s (via `+` concat → `StringBuilder`+`char[]`+`String`) **per slot per frame** during an active zone drag (`invalidate()` on every `ACTION_MOVE`) → ~6 throwaway Strings/frame (~360–540 objects/s) on the editor canvas.
- `LogActivity.refreshLog` (`:211`) deep-copies the **full 5000-entry** buffer + allocates a second filtered `ArrayList` and walks it twice, every 500 ms while the Journal is foreground and streaming → ~80 KB/s of short-lived arrays on the main thread.

**Pillar 3 · CPU / RAM footprint & GC churn**
- `WakeWordEngine` real-time audio worker: (a) ONNX mel/embedding outputs materialised via `out.get(0).value as Array<Array<Array<FloatArray>>>` **every ~160 ms tick** (nested-array object graph, ~350 short-lived objects/s) — ironic given every *input* buffer was already pre-allocated; (b) per-sample int16→float divide + modulo **inside `mRingLock`** in `advanceMel` (the true streaming path), stalling the 20 Hz mic-thread ring writes; (c) up-to-40k raw copy-under-lock + 40k normalize per tick used, in steady state, only to derive one silence-gate peak.
- `VoskTranscriber` allocates a fresh `ShortArray` per 50 ms frame during the post-wake listen window (~20 alloc/s, bounded to ~5 s command windows).

**Pillar 3 · Binder / IPC payload**
- `CanBusController` fans each nav update into **up to ~10 separate synchronous CAN binder round-trips** (`setNaviActive(false)` = 9 in a row), each its own `Parcel.obtain`/`transact`/`readInt` through `callWithRetry`, and each re-invokes the BYD SDK daemon-side. Runs continuously during active navigation.
- `Phase4TaskVerbs.moveAndResize` / `cleanFissionStacks` marshal a **~13-line human-readable diagnostic transcript** back across the binder reply Parcel on every cluster resize / dashboard launch — consumed only by `AppLogger`.

**Pillar 1 · Leaks & lifecycle**
- `FloatingRemoteButton.createOverlay` (`:168`) — a late ADB-grant `onSuccess` callback (background executor, seconds-long round-trip) can re-enter `createOverlay()` and `addView()` a `TYPE_APPLICATION_OVERLAY` window on a **destroyed** Service (no `mDestroyed` guard) → one leaked window + retained Service until process death.
- `DashCastApp` implements **no `onTrimMemory`/`onLowMemory`** — on a weeks-uptime head unit the AppRepository icon cache, voice state, and logger buffer grow until LMK kills the process → forced cold restart that can evict the very cluster app the user launched.
- `FissionActivity.pickApp` (`:214`) mints a fresh single-thread `ExecutorService` (+ OS thread) on **every** picker-button tap (siblings reuse one field-scoped `mExec`).

### Binder & leak audit summary

| Vector | Component | Nature |
|---|---|---|
| Un-throttled IPC | `CanBusController` → `ProxyClient` CAN verbs | ~10 sync binder RTT/nav frame, no batching |
| Oversized payload | `Phase4TaskVerbs` resize/launch verbs | multi-line String in reply Parcel per call |
| Overlay leak | `FloatingRemoteButton` | `addView` on destroyed Service via ADB callback race |
| Unbounded cache growth | `DashCastApp` (no `onTrimMemory`) | no memory-pressure shedding → LMK kill cycle |
| Executor churn | `FissionActivity.pickApp` | new executor+thread per tap |

*(The pre-existing HIGH binder/ANR family — `MapNotificationListenerService`, `ClusterService` launch cascade, `ProxyClient.callWithRetry` — is carried from the hardening audit and remains #1–3 of the combined severity list.)*

---

## Phase 2 — Parity Matrix (most-severe first)

Ranked by **impact × fix-safety**. `Big-O / footprint` states the complexity or allocation change. "re-anchor" = a prior-audit item re-verified at a current line; "NEW" = surfaced by this perf pass.

| Rank | File:Line | Pillar | Drain | Mechanic | Big-O / footprint change | Fix Risk |
|---|---|---|---|---|---|---|
| 1 | `platform/Platform.java:334-393` ← `MainActivity.kt:1483` | Startup / main-thread block | DL5 cold start: sync resize probe forks a shell + `waitFor(1500ms)` when cache loses race with unsynced prime worker | Latch/gate the probe; default UI shown, hide via async prime callback; non-blocking cache read at 1483 | removes up to **1500 ms** (typ ~200 ms) UI-thread block + 1 shell fork; first-launch/prefs-wipe only | LOW |
| 2 | `voice/wakeword/WakeWordEngine.kt:504` (+548) | GC / alloc | Per-tick ONNX output boxed via `Array<Array<Array<FloatArray>>>` `.value` | Read `(out as OnnxTensor).floatBuffer` flat into mel/emb rings | ~(time×32 + 96) boxed floats + nested graph / 160 ms → **O(1) alloc** | MED |
| 3 | `system/CanBusController.java:116` | IPC / binder | Up to ~10 sync CAN binder RTTs per nav frame, each re-invokes BYD SDK | Batched `TXN_CAN_BATCH` verb: array of {featureId,value} in 1 Parcel/transact | ~**10→1** binder RTT/frame (≈9× fewer); off-main hudExecutor | MED (daemon proto + version bump) |
| 4 | `DashCastApp.java:9` | Memory / LMK | No `onTrimMemory`/`onLowMemory` → caches never shed → LMK kill → cold restart | Override `onTrimMemory`: drop process-wide caches by level | caps resident set < LMK threshold; stops kill→cold-restart cycle | MED |
| 5 | `MainActivity.kt:249` | Startup | Overlay service started every `onCreate`; inflates WM overlay + `addView` before first frame | Defer `startService` to post-first-frame (`decorView.post`) | removes `addView`+`startForeground` from first-frame path; first-cold-start only | LOW |
| 6 | `DashCastApp.java:27` (+31) | Startup | First `byd_app_prefs` XML parse runs sync on main thread in `Application.onCreate` | Move `isDiLink5`/`contains()` gate onto a startup worker | ~1 small XML disk read off main-thread cold path | LOW |
| 7 | `fission/ClusterCanvasView.java:169` (181) | GC / alloc | Per-slot label String built in `onDraw` every drag frame | Cache labels keyed on (label,w,h,vd); reused SB/`char[]` for live w×h only | ~**6→1** String/frame during drag; editor-only path | LOW |
| 8 | `system/FloatingRemoteButton.java:168` | Leak | Re-entrant `createOverlay` from ADB grant callback `addView` on destroyed Service | `volatile mDestroyed` set in `onDestroy`; short-circuit `onSuccess` re-entry | stops 1 leaked `TYPE_APPLICATION_OVERLAY` window until process death | LOW |
| 9 | `fission/FissionActivity.java:214` | Concurrency / alloc | New single-thread `ExecutorService` + OS thread per picker tap | Reuse activity-field `mExec` (`onCreate`→`onDestroy`) | N taps → **1** thread (from N create/start/teardown) | LOW |
| 10 | `voice/wakeword/WakeWordEngine.kt:496` | Lock / concurrency | Per-sample int16→float divide + modulo held inside `mRingLock`, stalls `onFrame` writes | Copy raw `ShortArray` tail under lock, divide outside; precompute wrap | critical section −~12k FP ops/tick; background threads | LOW |
| 11 | `gradle.properties:3` | Build | `enableJetifier=true` with all-AndroidX/native deps | `android.enableJetifier=false` (verify clean `assembleRelease`) | removes per-artifact rewrite pass; build-time only | LOW |
| 12 | `voice/wakeword/WakeWordEngine.kt:231` | Lock / alloc | Up-to-40k raw copy-under-lock + 40k normalize/tick used only for silence-gate peak after SELF_CHECK | Derive peak from raw int16 tail / running sliding-max; skip `audioWindow` copy | eliminates 40k copy+normalize / 160 ms steady state | MED (sliding-window max) |
| 13 | `voice/VoskTranscriber.kt:382` | GC / alloc | Fresh `ShortArray` per 50 ms frame in post-wake listen window | Recycle a ring of pooled `ShortArray(FRAME_SAMPLES)` | ~20 alloc/s → 0 during bounded listen window | MED (cross-thread reuse-before-consume) |
| 14 | `ui/log/LogActivity.kt:211` | GC / alloc | Full 5000-entry buffer deep-copied + re-filtered every 500 ms | Append-only delta adapter / reusable snapshot, re-filter tail | 2×O(5000) walk / 500 ms → O(delta); foreground-only | MED (circular eviction + filter change) |
| 15 | `proxy/daemon/Phase4TaskVerbs.java:1046` | IPC / binder | ~13-line diagnostic transcript shipped back over binder per resize/launch verb | Return compact status int; gate verbose log behind debug flag/dump verb | sub-1KB String → a few bytes/call; user-initiated | MED (debug aid; caller contract ripple) |
| 16 | `app/build.gradle:34` | Footprint | No `resConfigs` → APK bundles ~70 AndroidX/Material locales never shipped | `resConfigs` = exact 12 shipped locales | strips ~58 dead locale folders from resources.arsc | MED (must match exact shipped set) |

---

## Phase 3 — Refactoring delivery

Full production-ready patch content (unified diffs + one full-file rewrite) for the **8 top-ranked targets** is in **[`2026-07-05-perf-patches.md`](2026-07-05-perf-patches.md)**. Each patch carries its strategy, honest fix-risk, threading caveats, required R8 keep-rules, and a concrete on-car verification step. Several were validated with `git apply --check` at generation time.

Recommended landing order (batched, matching the hardening-audit cadence):
- **Batch A — Low-risk, self-contained (land first):** #5, #6, #7, #8, #9, #11 + the `Platform`/`MainActivity` cold-start pair (#1) and `DashCastApp` (#4/#6). All LOW fix-risk, all `git apply`-clean.
- **Batch B — Medium soak:** #2 (WakeWordEngine output de-boxing) + #14 (LogActivity delta) + #16 (`resConfigs`) + #15 (Phase4 payload trim). Validate on the DL3+DL5 on-car matrix.
- **Batch C — Staged / cross-cutting:** #3 (CanBus batching — needs a daemon proto + version bump), #10/#12 (WakeWordEngine lock + steady-state skip — gate on the existing on-car self-check step, see `[[perf-audit-voice-streaming]]`), and R8 enablement (below).
- **Prior HIGH ANR patches** (`../hardening/patch-1`, `patch-2`) remain the true #1–2 and land ahead of everything here.

---

## Phase 4 — Predicted resource impact

All figures are **ESTIMATES** on a DL3/DL5 head-unit class SoC (A55/A53-tier, eMMC, ART).

### Startup / main-thread (findings #1, #5, #6)
- **#1 Platform probe:** removes up to **1500 ms** (hard cap), ~**200 ms typical**, of UI-thread block + 1 avoided `Runtime.exec()` on the affected first-launch/prefs-wipe path (~10–30 % of a ~1.5–2 s launcher TTI). This is a **tail-latency / first-run** fix — the sticky `.apply()` pref makes every later cold start already fast.
- **#6 prefs XML off-main:** ~**2–8 ms** main-thread disk read removed from every DL5 `Application.onCreate`.
- **#5 deferred overlay:** ~**5–15 ms** of `WindowManager.addView` + `startForegroundCompat` moved off the first-frame path.
- **Combined:** first-launch UI-thread block cut by up to ~1.5 s (typ ~215 ms); every DL5 cold start ~7–23 ms lighter on the main thread pre-first-frame.

### GC / allocation (findings #2, #7, #13, #14 — worker/mic/foreground threads, no direct UI-frame impact)
- **#2 WakeWordEngine floatBuffer:** eliminates ~(time×32 + 96) boxed `Float`s + the nested Array graph per ~160 ms tick ≈ **6–24 KB/s + hundreds of short-lived objects/s** on the wakeword worker while listening → measurably lower young-gen churn on that thread.
- **#13 Vosk pooling:** removes ~20 `ShortArray`/s (~1.6 KB each) = **~32 KB/s** during bounded ~5 s command windows.
- **#14 LogActivity delta:** removes two O(5000-ref) walks + **~80 KB/s** short-lived ArrayLists at 2 Hz while the log tab is foregrounded.
- **#7 ClusterCanvasView:** ~6 Strings + SB/`char[]` per drag frame eliminated; negligible except during an active editor drag.
- **Aggregate GC note:** these are **young-gen only** — expect fewer/shorter minor GCs on the voice + log threads, not a measurable main-thread jank reduction. No full-GC pauses are implicated.

### Memory / LMK & leaks (findings #4, #8)
- **#4 `onTrimMemory`:** converts monotonic resident-set growth into a bounded working set on weeks-uptime units; each avoided LMK kill saves a full cold restart (~1.5–2 s TTI + lost voice/mirror state). AppRepository's decoded-bitmap icon cache is the dominant shed target.
- **#8 FloatingRemoteButton guard:** stops leaking 1 `TYPE_APPLICATION_OVERLAY` window (+ its Surface/View tree, tens–hundreds of KB) per post-destroy grant race.

### IPC / Binder (findings #3, #15 — staged)
- **#3 CanBus batching:** cuts binder transacts on the HUD write path by **~80–90 %** during active nav (~10→1 per frame), with a matching drop in BYD-SDK re-invocations. Effect is HUD update latency/throughput, not UI frames (runs off-main).
- **#15 Phase4 payload trim:** sub-1KB → a few bytes per user-initiated verb; negligible aggregate, correctness/clarity trade only.

### Build / footprint (findings #11, #16 — no runtime frame/boot cost)
- **#16 `resConfigs`:** strips ~58 dead AndroidX/Material locale folders from `resources.arsc` → tens-to-low-hundreds of KB out of the APK — matters for the bandwidth-limited on-car daemon OTA.
- **#11 `enableJetifier=false`:** removes the per-artifact bytecode-rewrite pass → faster incremental + CI builds; zero runtime delta.

---

## Two AOSP config / runtime tuning recommendations

### TUNING 1 — R8/ProGuard keep-rules to safely unblock `minifyEnabled true`

> **SUPERSEDED — do not follow the instruction below.** `app/proguard-rules.pro` now exists (4 420 bytes) and `minifyEnabled true` shipped in 1.8.3-beta. Creating the file from this template would overwrite validated keep rules. The reflection/JNI inventory in this section is still worth reading; the action is not.

Reflection/JNI surfaces here: `ReflectionTaskResizer` + `IamAppLauncher` (framework/BYD IAM reflection), ONNX Runtime (`ai.onnxruntime` JNI), Vosk (`org.vosk` JNI), Tink via `security-crypto`, and the daemon Parcelable verbs. Create `app/proguard-rules.pro`:

```proguard
# ---- attributes needed for JNI / reflection / generics ----
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions
# ---- all JNI entry points (ONNX/Vosk/native) ----
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# ---- ONNX Runtime (reflection + JNI) ----
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
# ---- Vosk / Kaldi (JNI) ----
-keep class org.vosk.** { *; }
-keep class org.kaldi.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn org.vosk.**
# ---- Tink (security-crypto registers primitives reflectively) ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
# ---- BYD SDK / hidden-API reflection targets ----
-keep class android.car.** { *; }
-keep class android.hardware.bydauto.** { *; }
-keep class * extends **.AbsBYDAutoSettingListener { *; }
-keep class * extends **.AbsBYDAutoInstrumentListener { *; }
-keep class com.byd.** { *; }          # confirm exact SDK package(s): jar tf app/libs/<bydsdk>.jar | sed 's#/[^/]*$##' | sort -u
-dontwarn android.car.**
-dontwarn com.byd.**
# ---- enums referenced by name (valueOf) & Parcelable daemon IPC ----
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
-keep class * implements android.os.Parcelable { public static final ** CREATOR; }
-keepnames class * implements java.io.Serializable
```

Then set `minifyEnabled true` (keep `shrinkResources false` for the first pass). **Both are done: minification in 1.8.3-beta, resource shrinking in 1.8.32-beta.** **SHIP SEPARATELY:** validate a signed `assembleRelease` on the full DL3 + DL5 on-car matrix (voice wake→Vosk, IAM launch, cluster resize, daemon verbs) before release — the platform-cert / signing-wall (`[[dl5-signing-wall]]`) makes a bad shrink hard to hotfix.

### TUNING 2 — Build-config footprint levers (zero runtime risk, verified inputs)

**Confirmed shipped locales on disk** (`ls res/values-*`): `ar be de en es it kk pl ru tr uk uz` (12). In `app/build.gradle` `defaultConfig` (line ~34):

```gradle
resConfigs 'ar','be','de','en','es','it','kk','pl','ru','tr','uk','uz'
```

⚠️ Use **exactly these** — the raw finding-#16 draft wrongly added `nl`/`pt`/`zh-rCN` and dropped `be`/`kk`/`uk`; that mismatch would strip real translations. (Verified against `res/values-*` this session.) And in `gradle.properties` (line 3):

```properties
android.enableJetifier=false
```

Every dependency is already AndroidX/Kotlin/native (no `android.support.*` consumer — the only legacy-looking token is the `android.support.FILE_PROVIDER_PATHS` meta-data key, which Jetifier never rewrites), so the rewrite pass is pure build-time waste. Validate both with one clean `assembleRelease`.

---

## Refuted / intentional (do not re-flag)

`ProxyDaemonMain` 1 Hz self-heal poll (documented DL5 inotify workaround), `Phase4TaskVerbs` 500 ms reflection watchdog, `HotspotActivity` client-list rebuild, `FissionCoordinator` onResume rebuild, `SysInfoActivity` report re-set, `ResizeFrameView` gesture-exclusion rects, `WakeWordEngine` 12 s self-check (deliberate, pending on-car validation). Two finder candidates were **REFUTED** during verification and are excluded from the matrix.
