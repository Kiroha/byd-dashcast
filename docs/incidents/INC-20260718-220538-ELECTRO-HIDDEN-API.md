# Electro: denied BYD hidden-API calls in INC-20260718-220538

## Purpose

This document is for the developer of `br.com.rory.electro`. It explains exactly which BYD methods Android classified as denied hidden-API accesses, how often Electro attempted them, what can and cannot be concluded from the report, and which public BYD APIs are available as replacements.

The most important correction to the original short release-note sentence is this:

> The Electro hidden-API warnings and the OEM camera errors are two independent observations from the same overloaded head unit. The report does **not** prove that Electro opened, controlled, or broke the camera pipeline.

This document therefore focuses on the actionable Electro issue: two reflection calls to blacklisted BYD framework methods.

## Executive answer: which calls were denied?

Android 10 ART logged these two exact method descriptors from Electro's process:

| # | Exact hidden method | Java-like form | ART classification |
|---|---|---|---|
| 1 | `Landroid/hardware/bydauto/light/BYDAutoLightDevice;->getTurnLightFlashState()I` | `int BYDAutoLightDevice.getTurnLightFlashState()` | `blacklist, reflection, denied` |
| 2 | `Landroid/hardware/bydauto/instrument/BYDAutoInstrumentDevice;->getMileageUnit()I` | `int BYDAutoInstrumentDevice.getMileageUnit()` | `blacklist, reflection, denied` |

Both descriptors end in `()I`, meaning no arguments and an `int` return value.

The literal warnings were:

```text
W om.rory.electr: Accessing hidden method
Landroid/hardware/bydauto/light/BYDAutoLightDevice;->getTurnLightFlashState()I
(blacklist, reflection, denied)

W om.rory.electr: Accessing hidden method
Landroid/hardware/bydauto/instrument/BYDAutoInstrumentDevice;->getMileageUnit()I
(blacklist, reflection, denied)
```

These are the only Electro hidden-method signatures visible in this incident report.

## Forensic attribution: why these warnings are assigned to Electro

This attribution is based on the Linux PID, not on a guess from the log tag.

Android `logcat -v threadtime` lines use this structure:

```text
date time PID TID level tag: message
```

The first two warnings in the report are therefore decoded as follows:

```text
07-18 22:05:36.005 24348 24784 W om.rory.electr: ...getTurnLightFlashState...
                     ^     ^
                     PID   TID

07-18 22:05:36.208 24348 24787 W om.rory.electr: ...getMileageUnit...
                     ^     ^
                     PID   TID
```

The same report's process snapshot maps that PID directly to Electro:

```text
24348 u0_a180 20 0 5.6G 97M 97M S 73.6 1.2 12:36.03 br.com.rory.electro
```

An exhaustive grouping of every `Accessing hidden method` warning in the captured log produced:

```text
54  PID=24348  tag=om.rory.electr  BYDAutoInstrumentDevice.getMileageUnit()I
46  PID=24348  tag=om.rory.electr  BYDAutoLightDevice.getTurnLightFlashState()I
```

There are exactly `100` such warning lines, all `100` use PID `24348`, and no hidden-method warning in the report uses another PID or tag. TIDs `24784` and `24787` are worker threads inside PID `24348`; they are not separate applications. SurfaceFlinger also records the Electro component `br.com.rory.electro.activity.sentry.SentryMessageActivity` in the same system snapshot.

The conclusion that the calls occurred **inside Electro's process** is therefore high-confidence evidence, not inference.

What the report cannot identify is authorship at source-code level. The executing code may be:

- Electro's own source;
- a BYD compatibility helper bundled with Electro;
- another third-party library or dynamically loaded module running inside Electro's process.

Only a source/dependency search or a stack trace captured at the reflection call can distinguish those cases. The correct wording is therefore: **code executing in the `br.com.rory.electro` process attempted these calls**.

## Evidence source and environment

- Incident: `INC-20260718-220538`
- Capture time: 2026-07-18 22:05:38 CEST
- Vehicle system: BYD DiLink 3.0
- Android: 10 / API 29
- Build fingerprint: `BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1.210910.001/eng.build.20260204.025317:user/release-keys`
- Security patch reported by the ROM: `2023-02-05`
- Electro package: `br.com.rory.electro`
- Electro PID during capture: `24348`
- Electro Linux UID: `u0_a180` / application UID `10180`
- Electro resident memory in the CPU snapshot: approximately `97 MiB`
- Electro CPU in that one snapshot: `73.6%`
- Electro accumulated process CPU time in that snapshot: `12:36.03`
- Visible component found in SurfaceFlinger statistics: `br.com.rory.electro.activity.sentry.SentryMessageActivity`

The report did not include Electro's `versionName`, `versionCode`, `targetSdkVersion`, manifest, granted permissions, stack traces, or source code. Those must be collected separately before attributing the warning to one exact source line or dependency.

## Quantified call pattern

### `getTurnLightFlashState()`

- Process: PID `24348`
- Thread: TID `24784`
- First warning: `22:05:36.005`
- Last warning: `22:05:49.504`
- Observed duration: `13.499 s`
- Warning count: `46`
- Median interval: `300 ms`
- Minimum observed interval: `294 ms`
- Maximum observed interval: `310 ms`
- Effective rate: `(46 - 1) / 13.499 = 3.33 calls/s`
- Vendor result logs: `46`
- Every vendor result visible in this window: `1`

This is an extremely regular 300 ms polling loop on one stable worker thread.

Representative sequence:

```text
22:05:40.503 24348 24784 W om.rory.electr:
  Accessing hidden method
  Landroid/hardware/bydauto/light/BYDAutoLightDevice;->getTurnLightFlashState()I
  (blacklist, reflection, denied)

22:05:40.503 24348 24784 D BYDAutoLightDevice:
  getTurnLightFlashState is: 1
```

### `getMileageUnit()`

- Process: PID `24348`
- Thread: TID `24787`
- First warning: `22:05:36.208`
- Last warning: `22:05:49.219`
- Observed duration: `13.011 s`
- Warning count: `54`
- Calls are grouped into exactly `27` bursts
- Every burst contains exactly `2` calls
- Median delay between burst starts: `500 ms`
- Burst-start interval range: approximately `483-522 ms`
- Intra-burst spacing: commonly `1-21 ms`, sometimes both calls share the same millisecond timestamp
- Effective burst rate: approximately `2 bursts/s`
- Effective call rate: approximately `4 calls/s`
- Vendor result logs: `54`
- Every vendor result visible in this window: `1`

Representative sequence:

```text
22:05:48.713 24348 24787 W om.rory.electr:
  Accessing hidden method
  Landroid/hardware/bydauto/instrument/BYDAutoInstrumentDevice;->getMileageUnit()I
  (blacklist, reflection, denied)
22:05:48.714 24348 24787 D BYDAutoInstrumentDevice: getMileageUnit: 1

22:05:48.714 24348 24787 W om.rory.electr:
  Accessing hidden method
  Landroid/hardware/bydauto/instrument/BYDAutoInstrumentDevice;->getMileageUnit()I
  (blacklist, reflection, denied)
22:05:48.715 24348 24787 D BYDAutoInstrumentDevice: getMileageUnit: 1
```

The exact two-calls-per-500-ms pattern strongly suggests duplicate reads inside one update pass. Common source patterns that produce this are:

```kotlin
// Example of a pattern to remove:
if (device.getMileageUnit() == 1) {
    // ...
} else if (device.getMileageUnit() == 2) {
    // ...
}
```

or two UI/computed properties independently evaluating the same getter during one refresh.

Read once, store once, and branch on the stored value:

```kotlin
val unit = readDistanceUnit()
when (unit) {
    1 -> { /* ... */ }
    2 -> { /* ... */ }
}
```

## Log amplification

The captured window contains:

- `100` ART hidden-API warnings: `46 + 54`
- `100` BYD getter result logs: `46 + 54`
- approximately `200` directly related log lines over `13.5 s`
- approximately `14.8` directly related lines per second

The values did not change during the captured window: both getters logged only `1`.

This matters even if the getter eventually returns a usable value. Repeated reflection lookup, hidden-API policy checks, string formatting, log writes, possible exception construction/catching, Binder/CAN queries, and UI invalidation all consume CPU. Automotive Android systems also commonly run persistent log collectors, so one application log can cause additional formatting, copying, file I/O, and rotation work elsewhere.

The `73.6%` Electro CPU figure is a single `top` snapshot. It proves Electro was consuming substantial CPU at that instant, but it does **not** prove these two loops alone consumed all of it. The report lacks per-thread CPU samples and a profile. These loops are nevertheless concrete, independently actionable waste.

## What `blacklist, reflection, denied` means

Android 9 introduced non-SDK interface restrictions. On this Android 10 ROM, ART identified both methods as:

- non-SDK/hidden framework methods;
- in the legacy `blacklist` category;
- reached through reflection;
- denied by the hidden-API access policy.

Both methods exist in the vehicle's runtime framework, which is why their class and descriptor are known to ART. They are absent from the public BYD compile stubs used by DashCast. In contrast, the supported replacement methods described below are present in those stubs.

The public signatures and numeric constants below were extracted with `javap -public -constants` from `byd-auto-api-stubs.jar`, the compile stub used by DashCast and derived from the BYD automotive API. The permission descriptions come from the BYD SDK 1.0.5 `android.Manifest.permission` reference. Runtime APIs can still differ between DiLink firmware versions, so capability and permission checks remain necessary on each supported generation.

### Important nuance: the report does not prove every read returned no value

In this incident, almost every ART warning is immediately followed by a BYD SDK debug line containing a result, and no matching `NoSuchMethodException`, `IllegalAccessException`, `NoSuchMethodError`, or crash is visible.

Therefore the precise conclusion is:

> Electro repeatedly attempts reflection access that ART classifies as blacklisted and denied. The report does not establish whether Electro then receives the logged value through that same call, through a vendor exemption, or through a fallback path.

OEM ART exemptions can also make hidden-interface behavior differ from AOSP while warnings are still emitted. Only call-site instrumentation on this exact firmware can distinguish direct success, exemption, and fallback.

The developer should instrument the call site once to record:

- whether reflection lookup returns a `Method`;
- whether invocation succeeds;
- the exact exception class and cause when it fails;
- whether a fallback executes after the denied attempt;
- whether the vendor result log comes from the attempted hidden method or from a public fallback.

That instrumentation should be temporary and rate-limited. The shipping fix should remove the hidden access, not hide the warning.

## Supported replacement for `getMileageUnit()`

The public BYD stub exposes:

```java
BYDAutoInstrumentDevice.getInstance(Context)
int BYDAutoInstrumentDevice.getUnit(int unitType)
```

The relevant public selector is:

```java
BYDAutoInstrumentDevice.FUEL_CONSUMPTION_AND_DISTANCE_UNIT == 3
```

Use:

```kotlin
val instrument = BYDAutoInstrumentDevice.getInstance(applicationContext)
val rawUnit = instrument.getUnit(
    BYDAutoInstrumentDevice.FUEL_CONSUMPTION_AND_DISTANCE_UNIT
)
```

The public stub defines these possible values:

| Value | BYD constant | Distance family |
|---:|---|---|
| `1` | `L_P_100KM_AND_KM` | kilometers |
| `2` | `KM_P_L_AND_KM` | kilometers |
| `3` | `MPG_GB_AND_MILE` | miles |
| `4` | `MPG_US_AND_MILE` | miles |
| `5` | `KWH_P_100KM_AND_KM` | kilometers |
| `6` | `KWH_P_100MI_AND_MILE` | miles |

This is a stronger API than a binary mileage-unit getter because it preserves both distance and consumption-display semantics.

Suggested mapping:

```kotlin
enum class DistanceFamily { KILOMETERS, MILES, UNKNOWN }

fun mapDistanceFamily(raw: Int): DistanceFamily = when (raw) {
    BYDAutoInstrumentDevice.L_P_100KM_AND_KM,
    BYDAutoInstrumentDevice.KM_P_L_AND_KM,
    BYDAutoInstrumentDevice.KWH_P_100KM_AND_KM -> DistanceFamily.KILOMETERS

    BYDAutoInstrumentDevice.MPG_GB_AND_MILE,
    BYDAutoInstrumentDevice.MPG_US_AND_MILE,
    BYDAutoInstrumentDevice.KWH_P_100MI_AND_MILE -> DistanceFamily.MILES

    else -> DistanceFamily.UNKNOWN
}
```

### Do not poll this every 500 ms

The unit is configuration state, not fast vehicle telemetry. A safe initial design is:

1. read once when the feature starts;
2. cache the raw value;
3. refresh on Activity/Service resume or when the relevant BYD settings event is received;
4. if no settings event exists on a target ROM, use a slow fallback refresh such as 30-60 seconds while the feature is visible;
5. keep at most one read in flight;
6. read once per refresh, never twice inside the same branch chain.

The incident's 27 duplicate bursts read the same value `1` fifty-four times in thirteen seconds. That provides no useful freshness.

## Supported replacement for `getTurnLightFlashState()`

The public BYD stub does not expose `getTurnLightFlashState()`. It does expose direction-aware light state:

```java
BYDAutoLightDevice.getInstance(Context)
int BYDAutoLightDevice.getLightStatus(int lightType)
```

Relevant public constants:

```java
BYDAutoLightDevice.LIGHT_LEFT_TURN_SIGNAL  == 4
BYDAutoLightDevice.LIGHT_RIGHT_TURN_SIGNAL == 5
BYDAutoLightDevice.LIGHT_OFF               == 0
BYDAutoLightDevice.LIGHT_ON                == 1
```

One-shot state read:

```kotlin
val lights = BYDAutoLightDevice.getInstance(applicationContext)

val leftOn = lights.getLightStatus(
    BYDAutoLightDevice.LIGHT_LEFT_TURN_SIGNAL
) == BYDAutoLightDevice.LIGHT_ON

val rightOn = lights.getLightStatus(
    BYDAutoLightDevice.LIGHT_RIGHT_TURN_SIGNAL
) == BYDAutoLightDevice.LIGHT_ON
```

### Prefer the public listener

The public stub also exposes:

```java
void registerListener(AbsBYDAutoLightListener listener)
void unregisterListener(AbsBYDAutoLightListener listener)

AbsBYDAutoLightListener.onLightOn(int lightType)
AbsBYDAutoLightListener.onLightOff(int lightType)
```

A Looper-safe Kotlin outline that dispatches consumer callbacks on the main thread:

```kotlin
class TurnSignalMonitor(
    private val appContext: Context,
    private val onState: (left: Boolean, right: Boolean) -> Unit,
) : Closeable {
    private val thread = HandlerThread("electro-byd-lights").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var left = false
    @Volatile private var right = false

    private var device: BYDAutoLightDevice? = null
    private var listener: AbsBYDAutoLightListener? = null

    private fun publishState() {
        val snapshotLeft = left
        val snapshotRight = right
        mainHandler.post { onState(snapshotLeft, snapshotRight) }
    }

    fun start() {
        handler.post {
            if (listener != null) return@post

            val currentDevice = BYDAutoLightDevice.getInstance(appContext)
            val currentListener = object : AbsBYDAutoLightListener() {
                override fun onLightOn(lightType: Int) {
                    when (lightType) {
                        BYDAutoLightDevice.LIGHT_LEFT_TURN_SIGNAL -> left = true
                        BYDAutoLightDevice.LIGHT_RIGHT_TURN_SIGNAL -> right = true
                        else -> return
                    }
                    publishState()
                }

                override fun onLightOff(lightType: Int) {
                    when (lightType) {
                        BYDAutoLightDevice.LIGHT_LEFT_TURN_SIGNAL -> left = false
                        BYDAutoLightDevice.LIGHT_RIGHT_TURN_SIGNAL -> right = false
                        else -> return
                    }
                    publishState()
                }
            }

            // Seed state before waiting for changes.
            left = currentDevice.getLightStatus(
                BYDAutoLightDevice.LIGHT_LEFT_TURN_SIGNAL
            ) == BYDAutoLightDevice.LIGHT_ON
            right = currentDevice.getLightStatus(
                BYDAutoLightDevice.LIGHT_RIGHT_TURN_SIGNAL
            ) == BYDAutoLightDevice.LIGHT_ON

            currentDevice.registerListener(currentListener)
            device = currentDevice
            listener = currentListener
            publishState()
        }
    }

    override fun close() {
        handler.post {
            val currentDevice = device
            val currentListener = listener
            if (currentDevice != null && currentListener != null) {
                runCatching { currentDevice.unregisterListener(currentListener) }
            }
            listener = null
            device = null
            thread.quitSafely()
        }
    }
}
```

BYD listener implementations on some DiLink ROMs construct an internal `Handler`. Create and register the listener on a thread that has a `Looper`, retain a strong reference for as long as it is registered, and unregister it during teardown. Avoid registering a new anonymous listener on every resume without releasing the previous one.

`onState` is posted to the main looper in this outline so a UI consumer may use it safely. If Electro routes state into a thread-safe repository instead, make that threading contract explicit rather than updating views directly from the BYD callback thread.

### Flash phase versus logical turn direction

The hidden name `getTurnLightFlashState()` may represent instantaneous blink phase, while the public `getLightStatus(LEFT/RIGHT)` API is direction-aware logical state. They should not be assumed byte-for-byte equivalent without an on-car test.

Recommended behavior:

- use `onLightOn`/`onLightOff` to determine whether left, right, or both signals are active;
- test whether callbacks arrive on each physical flash edge or only when the logical turn state changes;
- if only logical state changes are reported, animate Electro's UI locally while that state is active;
- do not read a hidden framework method every 300 ms merely to synchronize an icon's blink phase;
- if a listener is unavailable on one ROM, poll the two **public** `getLightStatus()` selectors only while the relevant UI is visible, stop polling in the background, and keep one poll in flight.

## Required permissions

The BYD SDK documents these read permissions:

```xml
<uses-permission android:name="android.permission.BYDAUTO_LIGHT_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_LIGHT_GET" />
<uses-permission android:name="android.permission.BYDAUTO_INSTRUMENT_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_INSTRUMENT_GET" />
```

The SDK descriptions are:

- `BYDAUTO_LIGHT_COMMON`: allows an application to use the BYD LIGHT device;
- `BYDAUTO_LIGHT_GET`: allows an application to get all status from the BYD LIGHT device;
- `BYDAUTO_INSTRUMENT_COMMON`: allows an application to use the BYD INSTRUMENT device;
- `BYDAUTO_INSTRUMENT_GET`: allows an application to get all status from the BYD INSTRUMENT device.

Declaring a permission does not prove the ROM grants it. The incident report does not contain Electro's grant list. Check the actual installed package:

```bash
adb shell dumpsys package br.com.rory.electro \
  | grep -E 'versionName|versionCode|targetSdk|BYDAUTO_(LIGHT|INSTRUMENT)_(COMMON|GET)'
```

Inspect the requested-permission and granted/install-permission sections, not just the presence of the permission name. AOSP Android 10's `cmd package` frontend does not provide a portable `check-permission` command, and OEM command syntax may differ, so the app itself should also record the authoritative result:

```kotlin
private fun isGranted(context: Context, permission: String): Boolean =
        context.packageManager.checkPermission(
                permission,
                context.packageName,
        ) == PackageManager.PERMISSION_GRANTED

val bydGrants = mapOf(
        "lightCommon" to isGranted(
                applicationContext,
                "android.permission.BYDAUTO_LIGHT_COMMON",
        ),
        "lightGet" to isGranted(
                applicationContext,
                "android.permission.BYDAUTO_LIGHT_GET",
        ),
        "instrumentCommon" to isGranted(
                applicationContext,
                "android.permission.BYDAUTO_INSTRUMENT_COMMON",
        ),
        "instrumentGet" to isGranted(
                applicationContext,
                "android.permission.BYDAUTO_INSTRUMENT_GET",
        ),
)
```

Log this map once in a diagnostic build, not on every polling iteration.

Do not assume that changing `targetSdkVersion`, adding a manifest line, or requesting a runtime permission is enough. BYD permissions may be signature, privileged, installer-granted, or OEM-whitelisted depending on firmware. Verify the grant on every supported DiLink generation.

## Recommended source audit inside Electro

Search Electro and all included libraries for:

```bash
rg -n 'getTurnLightFlashState|getMileageUnit|getDeclaredMethod|getMethod|invoke'
```

For each match, answer:

1. Is the hidden method name hardcoded in Electro or in a bundled BYD helper library?
2. Is `Class.getDeclaredMethod()` executed on every timer tick?
3. Is the returned `Method` cached?
4. Is an exception thrown and caught on every tick?
5. Does a fallback public API execute after the hidden attempt?
6. Why is `getMileageUnit()` called twice per 500 ms refresh?
7. Do the loops continue while `SentryMessageActivity` is stopped, hidden, or the screen is off?
8. Does each result trigger a UI invalidation even when the value did not change?
9. Are multiple collectors/Flows/LiveData observers launching duplicate polling jobs?
10. Are jobs cancelled when the owning lifecycle is destroyed?

Likely Kotlin patterns to inspect include:

- two `map`/`combine` chains independently reading the unit;
- a composable getter invoked more than once per recomposition;
- a `while (isActive)` loop plus a second observer;
- repeated `Handler.postDelayed()` calls without `removeCallbacks()`;
- multiple `launchWhenStarted` or `repeatOnLifecycle` collectors;
- a repository poller and an Activity poller both querying the same device;
- retry-on-failure code with no backoff or permanent unsupported-state cache.

## Compatibility strategy without hidden APIs

If Electro supports multiple BYD firmware families with different public surfaces:

1. Prefer direct calls compiled against the matching public BYD SDK.
2. If binary compatibility requires reflection, reflect only public API names such as `getUnit(int)` or `getLightStatus(int)`.
3. Resolve and cache capability once per process, not on every update.
4. Cache an explicit `UNSUPPORTED` result after `ClassNotFoundException`, `NoSuchMethodException`, or `SecurityException`.
5. Do not retry an unsupported capability until process restart or an intentional compatibility re-probe.
6. Keep firmware adapters behind an interface so one bad ROM path cannot start several timers.
7. Record one rate-limited diagnostic with device fingerprint, Electro version, adapter name, and exception class.

Example capability shape:

```kotlin
sealed interface BydUnitCapability {
    data class Available(val read: () -> Int) : BydUnitCapability
    data class Unsupported(val reason: String) : BydUnitCapability
}
```

Never use `VMRuntime.setHiddenApiExemptions`, `--no-hidden-api-checks`, target-SDK downgrades, or warning suppression as the product fix. Those approaches are firmware-dependent, weaken platform boundaries, and can stop working after an OTA update.

## Reproduction and A/B validation plan

### 1. Capture package identity and grants

```bash
adb shell dumpsys package br.com.rory.electro > electro-package.txt
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.sdk
```

Record Electro version, target SDK, installer, code path, requested permissions, and granted permissions.

### 2. Capture only the relevant logs

```bash
adb logcat -c
adb logcat -v threadtime \
  | grep -E 'om.rory.electr|BYDAutoLightDevice|BYDAutoInstrumentDevice|hidden method'
```

Exercise the Electro screen for at least 60 seconds, including:

- no turn signal;
- left turn signal;
- right turn signal;
- hazard lights;
- changing the vehicle's unit setting if safe and available;
- putting Electro in the background and returning;
- turning the display off/on if the feature is expected to survive it.

### 3. Capture repeated CPU samples, not one snapshot

```bash
PID=$(adb shell pidof -s br.com.rory.electro | tr -d '\r')
adb shell top -H -b -n 10 -d 1 -p "$PID" > electro-top-threads.txt
```

If the vehicle provides `simpleperf`:

```bash
adb shell simpleperf stat -p "$PID" --duration 30
adb shell simpleperf record -p "$PID" --duration 30 -g -o /data/local/tmp/electro.data
adb pull /data/local/tmp/electro.data
```

### 4. Compare three builds

| Build | Behavior | Purpose |
|---|---|---|
| A | Current Electro | baseline |
| B | Hidden polling disabled by feature flag | measure how much CPU/logging those loops contribute |
| C | Public listener/getter implementation | validate functionality and final performance |

Use the same car state and approximately the same test duration for each build.

### 5. Success criteria

- Zero occurrences of `getTurnLightFlashState()I (blacklist, reflection, denied)`.
- Zero occurrences of `getMileageUnit()I (blacklist, reflection, denied)`.
- No reflection lookup of either hidden method in source or bundled libraries.
- `getUnit(FUEL_CONSUMPTION_AND_DISTANCE_UNIT)` called once per intentional refresh, not twice per 500 ms.
- Unit state cached because it changes rarely.
- Light listener registered at most once per owner and always unregistered.
- No light polling while the feature is backgrounded unless explicitly required.
- UI updates only when the logical state changes.
- CPU assessed from repeated samples and thread profiles, not one `top` row.
- Left, right, hazards, metric, imperial, pause/resume, and process restart all remain correct.

## Camera clarification

The camera lines in the same report came from different Linux processes and threads:

- `mm-qcamera-daemon`: PID `423`, UID `camera`, CPU `55.2%`;
- `bmmcameraserver`: PID `445`, UID `cameraserver`, CPU `39.4%`;
- camera worker TIDs in the logs: `24762` (`CAM_iface_poll`) and `24755` (`CAM_c2d`);
- sensor name: `pano`;
- camera session: `3`;
- frame cadence: approximately `30 frames/s`.

Electro was PID `24348`, with hidden-API polling TIDs `24784` and `24787`. The report contains no `openCamera`, `CameraService::connect`, camera client attribution, or camera stack trace linked to Electro. It also does not include Electro's camera permission state.

Therefore:

- the camera load was real;
- the Electro CPU load was real;
- the Electro hidden-API polling was real;
- the report does not establish that Electro caused the camera load.

If Electro has a sentry-camera feature, test that relationship separately with an A/B run where only that feature is disabled. Do not treat temporal overlap as causation.

## Questions for the Electro developer

The following answers would make the next analysis decisive:

1. Which Electro version and target SDK produced PID `24348`?
2. Which source file or dependency contains the two hidden method names?
3. Is reflection performed once and cached, or performed on every poll?
4. Why are there exactly two `getMileageUnit()` calls per 500 ms update?
5. Does the turn loop intentionally run every 300 ms to reproduce blink phase?
6. Does `AbsBYDAutoLightListener` fire for turn-signal events on this DiLink 3 ROM?
7. Are the four BYD `COMMON`/`GET` permissions granted to Electro on the vehicle?
8. Do the loops stop when `SentryMessageActivity` is not visible?
9. Does Electro use any camera or panorama API in this process?
10. Can a test build expose per-poller counters and elapsed execution time for a 60-second A/B capture?

## Recommended priority

1. Remove both hidden method names from the shipping code path.
2. Replace `getMileageUnit()` with one cached public `getUnit(3)` read.
3. Replace flash-phase polling with the public light listener and direction-aware status; animate locally if necessary.
4. Verify real BYD permission grants on each supported firmware.
5. Stop all pollers with lifecycle/background state and deduplicate observers.
6. Profile Electro after the warning count reaches zero.
7. Investigate the OEM camera stack only as a separate A/B question.

That sequence resolves the proven Electro issue first and prevents the independent camera symptoms from distracting the investigation.