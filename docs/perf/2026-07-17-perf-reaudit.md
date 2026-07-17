# DashCast performance re-audit — 2026-07-17

**Baseline:** `switch-kotlin` at `v1.6.132-beta` (`9f76fc7`)  
**Scope:** current production startup, proxy monitoring, Journal rendering, voice streaming, process-state polling, HUD dispatch, and build footprint.

## Executive summary

The high-value items from the 2026-07-05 audit are largely implemented. Startup no longer performs the DL5 resize probe on the UI thread, the floating overlay starts after the first traversal, Application preference work runs off-main, memory-pressure shedding exists, CAN writes are batched, Jetifier is disabled, translated resource configurations are restricted, Fission canvas labels/executors are reused, and the steady-state wake-word mel/embedding outputs use flat `FloatBuffer` access.

One low-risk duplicate remained in production: `ProxyKeeperService` already performs a real Binder heartbeat every 10 seconds while `ProxyWatchdog` retained a second foreground `HandlerThread` and scheduled a weaker local check every 30 seconds. The current patch makes the keeper authoritative while alive, releases the redundant watchdog thread, and restores the watchdog automatically if the keeper stops while an Activity is foreground.

## Applied optimization

### Proxy monitor consolidation

**Files:**
- `proxy/ProxyWatchdog.java`
- `proxy/ProxyKeeperService.java`
- `test/.../ProxyWatchdogPolicyTest.kt`

**Before:**
- always-on `ProxyKeeperService`: one `proxy-keeper` HandlerThread, real `pingBinder()` every 10 seconds, reconnect, HUD listener maintenance, hotspot keep-alive, and screenshot scheduling;
- foreground `ProxyWatchdog`: a second `proxy-watchdog` HandlerThread and local `isBinderAlive()` check every 30 seconds.

**After:**
- a fully started keeper marks itself authoritative;
- the watchdog removes callbacks, releases its HandlerThread, and remains installed only as a fallback;
- if the keeper stops, foreground-only watchdog polling resumes immediately when an Activity is active;
- if the keeper never starts, the existing foreground fallback remains unchanged.

**Expected effect:** removes one persistent thread and one redundant scheduled wakeup stream in the normal process lifetime. Recovery strength is unchanged because the keeper runs three times more frequently and uses the stronger real Binder ping.

**Risk:** low. No wire protocol, command interval, reconnect code, projection path, or platform gate changed.

### Low-risk layout measurement cleanup

**Files:**
- `res/layout/activity_hotspot.xml`
- `res/layout/activity_layout_manager.xml`
- `res/layout/activity_cluster_resize.xml`
- `res/values/styles.xml`
- `AndroidManifest.xml`

- Disabled unused baseline alignment on the three weighted horizontal rows identified by lint. This removes baseline measurement work without changing dimensions, weights, gravity, or rendering.
- Removed the full-screen Cluster Resize root background layer.
- Added a dedicated `ClusterResizeTheme` whose window background is the same `md_background` color, preserving the exact light/night appearance while painting that color only once.
- Kept the two semi-transparent Bug Report/Wizard root backgrounds: they provide intentional modal dimming and removing them would change the UI.

**Lint result:** 21 informational hints became 17. `DisableBaselineAlignment` is now zero and Cluster Resize no longer appears under `Overdraw`. The remaining hints are 9 nested weights, 2 intentional translucent overdraws, 3 deep-layout metrics, and 3 view-count metrics.

## Revalidated prior findings

| Prior candidate | Current status |
|---|---|
| DL5 resize capability probe blocking Main | Closed: primed off-main and UI affordance resolved on a worker. |
| Floating overlay competing with first frame | Closed: service start deferred through `decorView.post`. |
| Application prefs read on main | Closed: effective platform state and resize prime run on `platform-init`. |
| No memory-pressure shedding | Closed: `DashCastApp.onTrimMemory/onLowMemory` clears the process-wide log cache. |
| Floating overlay late-callback leak | Closed: `mDestroyed` gates callback and `createOverlay`. |
| Fission canvas per-frame labels | Closed: per-slot label arrays and a reused `StringBuilder`. |
| Fission picker executor per tap | Closed: Activity-field executor, shut down with lifecycle. |
| CAN navigation write fan-out | Closed: additive protocol v19 CAN batching. |
| Wake-word mel/embedding nested output graphs | Closed on the streaming production path: flat ONNX `FloatBuffer` reads. |
| Jetifier rewrite pass | Closed: `android.enableJetifier=false`. |
| Dead translated locales in APK | Closed: exact `resConfigs` list is present. |
| HUD notification writes on listener main thread | Closed: `LatestValueDispatcher` owns a single writer and lifecycle close. |
| Cluster launch Binder work on main | Closed by the launch/offload hardening series. |

## Remaining candidates

### 1. Incremental Journal snapshots

**Value:** medium when the Journal is open during a busy capture.  
**Risk:** medium.

`LogActivity.refreshLog()` still copies up to 5000 entry references, filters the full snapshot, clears/repopulates the adapter list, and updates the RecyclerView whenever the logger mutation stamp changes. Adaptive 500 ms/2 s scheduling and incremental level counters already removed the worst idle cost, but a streaming log keeps the full O(N) path active twice per second.

A correct incremental implementation must track:
- a monotonic entry sequence;
- logger generation across `clear()`;
- the first retained sequence, because eviction can occur by entry count or by total message-character budget;
- the last source sequence independently from the last filtered row.

Only when generation and first-retained sequence are unchanged may the adapter append filtered deltas. Filter changes, clears, or any eviction require a full snapshot. A simple size-based append is unsafe and can retain evicted rows.

**Cheapest validation:** pure JVM tests covering append, text/level filter changes, clear, count eviction, and character-budget eviction; then verify RecyclerView rows under a synthetic 5000-entry stream.

### 2. Wake-word lock shortening

**Value:** medium while always-listening wake word is enabled.  
**Risk:** medium because detection quality needs on-car validation.

`WakeWordEngine.advanceMel()` still performs per-sample ring modulo and int16-to-float division while holding `mRingLock`. The audio capture thread needs the same lock to append frames. A safe optimization is a two-segment `System.arraycopy` into a preallocated `ShortArray` under the lock, followed by float normalization outside the lock. The wake head also still materializes a tiny `Array<FloatArray>` for its scalar output and can use `OnnxTensor.floatBuffer.get(0)`.

**Cheapest validation:** unit-test wrapped-ring copying for contiguous and split windows, compare reference and optimized mel input bit-for-bit, then run the existing wake-word self-check and a real vehicle listen session before shipping.

### 3. Incremental app-label index for voice launch

**Value:** low; only affects explicit “launch app” voice commands.  
**Risk:** low-to-medium due package install/uninstall invalidation.

`VoiceCommandRouter.resolvePackage()` scans every installed application and calls label/launch-intent APIs for each request. It is correctly off-main and low-frequency. A cached normalized label index could make repeated voice launches instant, but it must invalidate on package add/change/remove and locale changes. Reusing `AppRepository` data would avoid another package inventory, but introduces ownership coupling.

**Recommendation:** defer until voice launch latency is measured as user-visible.

### 4. Vosk frame pooling

**Value:** low.  
**Risk:** medium.

The Vosk handoff copies each reused microphone frame into a bounded queue, creating roughly 20 short arrays per second during a command window. This is intentionally bounded to a few seconds and prevents the decoder from blocking `AudioRecord`. Pooling needs length-aware ownership and careful return-after-decode semantics. The current allocation is preferable until profiling shows GC during recognition.

### 5. R8/resource shrinking

**Value:** potentially high APK reduction and lower dex verification cost.  
**Risk:** high because DashCast relies heavily on hidden-API reflection, BYD SDK reflection, JNI (ONNX/Vosk), Parcelable Binder contracts, and multiple `app_process` entry points.

`minifyEnabled` remains intentionally false. Enable only in an isolated release with explicit keep rules and full DL3/DL4/DL5 on-car validation. This is not suitable for a mixed functional release.

## Intentional paths not to optimize

- `ProxyKeeperService` 10-second heartbeat: it multiplexes daemon recovery, HUD-listener arming, hotspot keep-alive, and screenshot scheduling; changing its cadence alters behavior.
- `DisplayStatePollCoordinator`: steady-state probes are typed `pidof` through the daemon, not shell forks, and run only for tracked packages.
- Diagnostic test-runner sleeps and shell inventories: user-triggered, off-main, and intentionally preserve deterministic observation windows.
- Vosk frame copying without profiling: protects the microphone thread from decoder backpressure.
- Reference wake-word path allocations: the steady-state production path is streaming; reference inference remains useful for self-check comparison.

## Validation

- Focused policy tests: `ProxyWatchdogPolicyTest`.
- Required regression checks: full `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and Java/Kotlin compilation.
- On-device confirmation: with the keeper active and Main foreground, `dumpsys`/thread dump should show `proxy-keeper` but no persistent `proxy-watchdog`; stopping the keeper should recreate `proxy-watchdog` while the Activity remains foreground.
