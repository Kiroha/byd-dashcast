# DashCast full application audit — 2026-07-17

> ⚠️ **HISTORICAL SNAPSHOT — 2026-07-17. Do not apply.**
> R8 has been enabled for release builds since **1.8.3-beta** (commit `269e2533`, 2026-07-26),
> and `app/proguard-rules.pro` exists — it is the source of truth for keep rules, not this
> document. `shrinkResources true` followed in **1.8.32-beta**. Every statement below about
> minification being off, or about `proguard-rules.pro` being absent, was true when written and
> is false now. Read this file for its reasoning, never for its state of the world.

**Baseline:** `switch-kotlin` at `v1.6.133-beta` (`ffb9455`)  
**Scope:** approximately 51k production Java/Kotlin lines, 162 source files, 125 XML resources, startup, lifecycle, UI, projection, Binder/daemon IPC, shell fallbacks, voice, storage, reports, build footprint, and tests.  
**Release target:** `1.6.134-beta` / versionCode 575 on `switch-kotlin`, without merging `main` or changing the repository's Latest stable release.

## Method

The audit combined the Graphify runtime map with direct source review and global pattern passes for:

- Activity, Service, receiver, listener, Handler, executor, and static-instance lifecycle symmetry;
- main-thread PackageManager, filesystem, network, shell, Binder, sleep, and latch work;
- `MotionEvent`, `Parcel`, `Surface`, `ImageReader`, bitmap, stream, process, and cursor ownership;
- recurring pollers, duplicate monitoring, task lookup, package discovery, and screenshot capture;
- per-frame/per-sample allocation in mirror input, custom views, Vosk, and openWakeWord;
- logger retention, filtering, adapter updates, and concurrent mutation;
- APK/Dex composition, resource configurations, lint, translation parity, and unit coverage.

Every applied change has a local falsification check. High-risk changes without a credible vehicle-independent check were not mixed into this batch.

## Applied optimizations

### 1. Process-once startup hygiene

`AppStartupTasks` now uses a process-wide `AtomicBoolean`. Storage pruning and orphan-sniffer inspection no longer create another thread and rescan storage whenever `MainActivity` is recreated.

**Gain:** one storage-hygiene run per process instead of one per Activity instance.  
**Risk:** low; these jobs are explicitly process-start hygiene and remain off the main thread.

### 2. Launcher shortcut discovery

`AppRepository` now requests all accessible shortcuts once and groups them by package locally. The former implementation made one `LauncherApps.getShortcuts()` Binder call per launchable app.

An exception, null result, or empty global result activates the exact per-package fallback. Treating an empty result as unsupported preserves shortcuts on vendor launchers that silently require `setPackage()`.

**Gain:** approximately N Binder calls become one on compliant launchers.  
**Risk:** low; restrictive BYD/vendor implementations retain the old path.

### 3. Typed task lookup before dumpsys

The daemon already exposed an `IActivityTaskManager`-based package lookup, but the general `ClusterService` strategy did not use it. `TypedProxyTaskFinder` is now ordered as:

`app ActivityManager -> typed daemon ATM -> daemon dumpsys -> local ADB dumpsys`

**Gain:** normal privileged lookup avoids a shell process and text parsing.  
**Risk:** low; non-positive results and any Binder/ROM failure continue through both existing fallbacks.

### 4. Incremental Journal updates

`AppLogger.Entry` now has a monotonic source sequence and the logger tracks a generation across `clear()`. `getEntryUpdate()` returns either a verified suffix or a full snapshot. A suffix is allowed only when generation and retained head are unchanged, so count/character-budget eviction cannot leave stale rows.

`LogActivity` filters only the suffix and `LogAdapter` appends only verified deltas. Filter changes, clears, and eviction always perform a full replacement.

**Gain:** steady streaming changes from copying/filtering up to 5000 entries every 500 ms to O(delta), while level totals remain O(1).  
**Risk:** low-to-medium; generation, clear, eviction, empty-buffer, and cursor-ahead cases have pure JVM coverage.

### 5. Wake-word PCM and ONNX hot paths

Circular PCM reads and writes now use at most two `System.arraycopy` calls. The 40,000-sample wake window no longer performs a modulo operation per sample while holding `mRingLock`, and int16-to-float normalization runs after releasing that lock.

Reference embedding and wake scalar outputs use `OnnxTensor.floatBuffer`, avoiding nested output-array materialization. The existing streaming self-check/reference architecture remains intact.

**Gain:** shorter AudioRecord lock contention and substantially less inference allocation.  
**Risk:** medium until exercised with real microphone input; contiguous, wrapped, full-window, and writer wrap behavior are unit-tested.

### 6. Demand-gated voice diagnostics

The Voice diagnostic tab is the only subscriber to live level and wake-score telemetry. A reference-counted process gate now follows receiver registration:

- with the tab hidden, `VoiceService` skips RMS/peak/clip sample scanning and its 20 Hz local broadcasts;
- ordinary wake scores no longer allocate/broadcast at roughly 6 Hz without a subscriber;
- detections, state changes, errors, transcripts, and command results are preserved.

**Gain:** removes roughly 26 diagnostic `Intent` publications per second plus per-sample metric work during unattended voice operation.  
**Risk:** low; overlapping Activity subscriptions and excess release calls are unit-tested.

### 7. Screenshot prune consolidation

The app no longer launches an ADB shell prune after every 15-second capture round. The uid-2000 daemon still applies a hard global count/age bound immediately after every JPEG, and the app retains its 30-second per-tag prune.

**Gain:** removes one shell round trip per capture round while preserving the hard storage bound.  
**Risk:** low; no capture format, cadence, consent, or send-time behavior changed.

### 8. Allocation-free resize gesture exclusion

`ResizeFrameView` now reuses its fixed 12 exclusion `Rect` objects. A drag event no longer creates eight center arrays, their outer array, an `ArrayList`, and 12 rectangles.

**Gain:** about 22 temporary objects removed per resize move event.  
**Risk:** low; rectangle geometry and Android API calls are unchanged.

### 9. Build warning hygiene

One Kotlin override parameter now matches its interface name. Three intentional API 28/29 PackageManager overloads have local deprecation suppressions instead of adding API 33 branches with no runtime benefit.

## Findings intentionally not changed

### R8 and resource shrinking

This is the largest remaining build-footprint opportunity. The unminified debug Dex is about 8.37 MB; `com.google` contributes about 2.34 MB and Tink alone about 1.27 MB through `security-crypto`.

It is also the highest-risk change. DashCast depends on hidden APIs, BYD reflection, JNI (ONNX/Vosk), Parcelable Binder contracts, and `app_process` entry points. Removing `security-crypto` could also make stored API credentials unreadable. R8 requires a dedicated build, keep rules, migration checks, and a full DL3/DL4/DL5/D50F vehicle matrix.

### Main, Settings, and Hotspot layout trees

Lint retains 9 nested-weight hints, 3 depth hints, and 3 view-count hints. Flattening or lazy-inflating these screens can improve cold inflation, but it changes high-density UI structure and focus/touch behavior. The two remaining overdraw hints are intentional translucent Bug Report/Wizard dim layers.

Measure inflation with `FrameMetrics` or Perfetto first. Refactor one screen per release with screenshot and interaction checks rather than chasing informational lint counts.

### Hotspot presence probes

While `HotspotActivity` is visible, stats probe TetherFi every 5 seconds and the local watchdog checks the same service every 20 seconds. The process-wide `HotspotKeeper` is separately authoritative outside the screen.

Sharing results safely needs a single-flight probe with callback fan-out while preserving watchdog restart counters, cooldown, labels, and keeper behavior. The saved shell call is too small and screen-local to justify mixing that lifecycle change into this batch.

### App list cache across Activity recreation

`AppRepository` owns an in-memory cache per Activity/coordinator lifetime. A process cache could make rotation instant, but Drawables, locale changes, package add/remove/change, user changes, and memory pressure all require explicit invalidation. The one-query shortcut improvement addresses the dominant reload IPC without adding process-lifetime UI state.

### Screenshot cadence

Diagnostics still capture main and cluster JPEGs every 15 seconds during projection. This is intentional beta incident evidence. Alternating displays or adapting cadence would save CPU but can remove the exact frame needed for a black-cluster report. Change only with telemetry showing capture cost is material.

### Voice label index and Vosk pooling

Voice app-label resolution scans installed apps only for an explicit launch command and already runs off-main. Vosk frame copies are bounded to command windows and isolate AudioRecord from decoder backpressure. Both remain profiling-led candidates.

## Refuted concerns

- No production shell, task search, package inventory, network request, sleep, or latch wait remains on the main thread in the reviewed paths.
- Mirror/input `MotionEvent`, `Parcel`, `Surface`, SurfaceControl transaction, display token, image, and bitmap ownership is balanced.
- Proxy monitoring is not duplicated in normal operation: the keeper is authoritative and the watchdog is a fallback, as implemented in 1.6.133.
- Display-state polling uses typed daemon operations in steady state; diagnostic sleeps and inventories are user-triggered and off-main.
- The task-manager typed verb did not need a new daemon protocol. The missing optimization was strategy wiring, now fixed.

## Validation

- Full Kotlin recompilation with `--rerun-tasks`: success, no Kotlin source warnings.
- Full JVM suite: 85 tests in 25 suites, 0 failures, 0 errors, 0 skipped.
- Android lint: 0 errors, 0 warnings, 17 informational hints.
- Debug APK assembly: success at `1.6.134-beta` / versionCode 575.
- Locale structure: all 680 translatable French keys exist in `en,de,es,it,ru,uk,be,kk,uz,tr,ar,pl`; the eight omitted technical keys are explicitly `translatable="false"`.
- APK signature: Android APK Signature Scheme v2; signing certificate SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.
- APK file: `DashCast-v1.6.134-beta-debug.apk`, 23,151,190 bytes, SHA-256 `ea59b26135a146ae87012e1e74cc33d9252180665a4f4fc13c3c9e152f636ad9`.
- `git diff --check`: clean.
- Added Java/Kotlin literals: internal diagnostics, exceptions, and annotations only; no new user-facing hardcoded string.

## Vehicle validation matrix before release

| Area | Minimum check |
|---|---|
| DL3 classic projection | Start, move/resize, preview touch, restore, and stop Waze/NewPipe. Confirm typed lookup or fallback in Journal. |
| DL4/DL5 classic projection | Launch and resize one app; verify no task lookup regression when daemon is reconnecting. |
| D50F | Re-run projection diagnostics; no physical-routing conclusion changes in this audit. |
| Layout mode | Launch favorite layout, switch tactile mirror target, stop all, and verify apps return to display 0 and are force-stopped. |
| App list | Verify shortcuts on a compliant launcher and on a BYD launcher that requires per-package queries. |
| Journal | Stream logs, change text/level filters, clear, and let the buffer evict while visible. |
| Voice | Open/close the Voice tab during capture; verify meters resume, wake detection still arrives, and commands/transcripts work. |
| Cluster resize | Drag all eight handles and full-frame edges; verify gestures remain excluded and frame commits correctly. |
| Bug reports | Leave projection active long enough for several rounds, then confirm shot count/age bounds and opt-in attachment. |

## Recommendation

Ship these changes as one performance beta only after the matrix above. Keep R8, layout flattening, and any capture-cadence reduction in separate measurable releases; their blast radius is much larger than the optimizations applied here.