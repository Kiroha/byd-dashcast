> **About the author** — I am not a professional developer, but I work in IT with a solid understanding of software development. This project was built through **vibe coding** with AI assistance (**Claude Sonnet 4.6** and **Gemini Pro**), which allowed me to ship this app despite having no prior native Android experience. The code reflects that approach: functional and goal-oriented, but with room for improvement. **Expert contributions are very welcome** — whether it's bug fixes, code review, or broader improvements to the app. Version history is available in [GitHub Releases](https://github.com/Kiroha/byd-dashcast/releases).

---

# DashCast — BYD Cluster Launcher & Mirror

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![API 29](https://img.shields.io/badge/API-29%20(Android%2010)-green.svg)](https://developer.android.com/about/versions/10)
[![Latest Release](https://img.shields.io/github/v/release/Kiroha/byd-dashcast?label=stable&color=brightgreen)](https://github.com/Kiroha/byd-dashcast/releases/latest)
[![Pre-release](https://img.shields.io/github/v/release/Kiroha/byd-dashcast?include_prereleases&label=beta&color=blue)](https://github.com/Kiroha/byd-dashcast/releases)
[![Docs](https://img.shields.io/badge/docs-kiroha.github.io-blue)](https://kiroha.github.io/byd-dashcast/)
[![Telegram](https://img.shields.io/badge/Telegram-community-2CA5E0?logo=telegram)](https://t.me/+QPk_dmTVaNkxMjFk)

Android application for **BYD vehicles (DiLink 3 and DiLink 5)** to push any installed app onto the instrument cluster display, control it via a real-time touch mirror, run several apps at once on the cluster with the **Layouts** engine (internally *Fission*), draw turn-by-turn arrows on the **DiLink 3 windshield HUD**, and report problems in one tap with a keyboard-free bug wizard.

> **v1.7.0 — first stable release since 1.5.4.** Milestone: the codebase has crossed **>50% Kotlin** (Kotlin now outnumbers Java). See [Migration status](#code-structure).

> **Tested on**: BYD Seal EU 2024 — DiLink 3.0 (XDJA/Qualcomm 6125F, Android 10). Also runs on **DiLink 5** head units (Android 13); the privileged daemon path adapts to each ROM.

- **Documentation**: https://kiroha.github.io/byd-dashcast/
- **Community (Telegram)**: https://t.me/+QPk_dmTVaNkxMjFk
- **Releases**: https://github.com/Kiroha/byd-dashcast/releases

> [!WARNING]
> The authors are not responsible for any damage to your vehicle's infotainment system. Use at your own risk.

> [!IMPORTANT]
> **v0.2.0 breaking change — uninstall required (historical, still relevant for old installs)**:
> If you have any version prior to v0.2.0 installed (any alpha, including v0.1.44),
> you **must uninstall it first** before installing a modern DashCast build.
> Two reasons:
> 1. The package was renamed from `com.byd.myapp` → `com.byd.dashcast` — Android treats them as separate apps.
> 2. Android blocks downgrades and cross-package upgrades without uninstall.
>
> ```bash
> adb uninstall com.byd.myapp     # remove old alpha
> adb uninstall com.byd.dashcast  # remove any previous beta
> adb install DashCast-vX.Y.Z-release.apk
> ```

---

## Features

| # | Feature | Description |
|---|---|---|
| 1 | **App list** | All installed apps (sorted RecyclerView, category filters) |
| 2 | **→ Cluster** | Push any app to the cluster display (uid=2000 ADB trampoline + FREEFORM) |
| 3 | **→ Main screen** | Move an app from the cluster back to display 0 |
| 4 | **Touch mirror** | Real-time TextureView of the cluster via `SurfaceControl` + full touch & key forwarding |
| 5 | **Fission** | Multi-app layout engine: run several apps simultaneously on the cluster, with layout presets, per-slot app binding, auto-activation on open, and a visual editor |
| 6 | **Voice commands** *(experimental)* | Wake-word → offline speech recognition (Vosk) → LLM → TTS. A proof-of-concept that lived in the diagnostics surface; not exposed in the main UI while diagnostics are being rebuilt in Kotlin |
| 7 | **HUD turn-by-turn** *(DiLink 3)* | Forwards Google Maps / Waze guidance (maneuver arrow + distance) to the **windshield HUD over CAN** on arrow-capable firmware. Also renders a nav-data overlay on the cluster |
| 8 | **Per-app DPI override** | Adjustable cluster display DPI per package — corrects apps that render incorrectly at 320 dpi |
| 9 | **Restore BYD** | `sendInfo(18+0)` → Qt regains control of the cluster |
| 10 | **Origin cluster** | `sendInfo(30+18+0)` → restores correct resolution + Qt |
| 11 | **Settings** | Cluster screen size, auto-launch, Fission auto-layout, beta OTA channel, per-app insets |
| 12 | **Diagnostics** *(rebuilding)* | The Java diagnostics screen + test runners were **emptied in v1.7.0** to be rebuilt cleanly in Kotlin; a Kotlin stub keeps the menu entry. The HUD/AAOS diagnostic tools remain |
| 13 | **System report** | Displays, system properties, BYD packages, permissions, proxy metrics, DiLink probe results |
| 14 | **Live log** | LogActivity — DEBUG/INFO/WARN/ERROR levels, filters, auto-scroll, share |
| 15 | **Multilingual** | French / English / German / Italian / Spanish / Polish / Turkish / Russian / Ukrainian / Arabic / Uzbek / Kazakh / Belarusian (13 languages), selected on first launch |
| 16 | **Floating overlay** | Persistent 📺 button: tap opens mirror, long-press opens quick-switch (recent cluster apps) |
| 17 | **Hotspot control** | Toggle and monitor Wi-Fi hotspot from within the app |
| 18 | **Display affinity safeguards** | Moves session apps back to Display 0 when projection stops or app is killed |
| 19 | **OTA update** | Auto-check against GitHub Releases; **silent auto-install + app relaunch** via the uid-2000 daemon (`pm install -r … && am start`), with fallback to `PackageInstaller` / the system dialog |
| 20 | **Bug reporter** | Keyboard-free 3-step wizard (category → app → issue) that captures a bounded diagnostic snapshot and sends it to the support channel in one tap; reachable from the nav rail and the floating button |
| 21 | **DiLink 5 support** | Cluster projection on DiLink 5 head units, with signing-wall hardening and a ROM-adaptive uid-2000 daemon path |

---

## Architecture overview

DashCast is organized around three runtime layers:

**App layer** (`uid=10080`) — MainActivity and all UI coordinators. Handles user interaction, app list, settings, mirror rendering, Fission layout UI, and voice commands.

**ClusterService** (`uid=10080`, foreground service) — Manages cluster projection independently of the Activity lifecycle. Owns the display connection, mirror pipeline, touch forwarding, and task resize.

**Beta Proxy Daemon** (`uid=2000` / shell) — A background `app_process64` daemon that runs with shell-level permissions. Handles operations that require elevated access: `sendInfo` calls to `AutoContainer`, `SurfaceControl` mirror transactions, task windowing mode changes, FREEFORM stack management, and CAN bus writes. Kept alive by `ProxyKeeperService`. The app falls back gracefully if the daemon is unavailable.

---

## Code structure

> **Migration status (v1.7.0):** the codebase is **majority Kotlin** — `.kt` files now outnumber `.java`. The tree below is organised by responsibility; some entries keep their historical `.java` name even where the file is already Kotlin. Converted so far: the voice package, the bug reporter (`report/`), `ui/nav`, `cluster/{dpi,display,mirror}`, most of `infrastructure/`, the app lifecycle, and a batch of unit-tested pure-logic policy classes. Still Java (deliberately last): the `proxy` / `proxy/daemon` binder-contract core, `ClusterService`, `AdbLocalClient`, `Platform`, and the large activities.

```
app/src/main/java/com/byd/dashcast/
│
├── MainActivity.java              — Orchestrator: nav rail, mirror, app list, cluster control
├── DashCastApp.java               — Application class, startup hooks
│
├── cluster/                       — Cluster projection & mirror pipeline
│   ├── ClusterService.java        — Foreground service: owns projection lifecycle
│   ├── ClusterSessionTracker.java — Per-session app tracking
│   ├── display/                   — Display detection & app launching
│   │   ├── ClusterManager.java    — sendInfo activation sequence (30→16→35)
│   │   ├── DashboardDisplayHelper.java
│   │   └── DashboardLauncher.java
│   ├── mirror/                    — SurfaceControl mirror + touch injection
│   │   ├── ClusterMirrorManager.java
│   │   └── ClusterInputForwarder.java
│   └── dpi/                       — Per-app DPI override
│       ├── ClusterDpiManager.java
│       ├── ClusterResizeActivity.java
│       └── ResizeFrameView.java
│
├── fission/                       — Multi-app cluster layout engine
│   ├── FissionActivity.java       — Main multi-slot UI
│   ├── FissionOrchestrator.java   — Slot lifecycle, conflict resolution
│   ├── FissionLayoutEditorActivity.java
│   ├── LayoutManagerActivity.java
│   ├── LayoutPreset.java / LayoutPrefs.java
│   ├── LayoutPresetAdapter.java
│   ├── FissionClient.java         — Proxy client for fission verbs
│   └── ClusterCanvasView.java     — Visual layout canvas
│
├── voice/                         — Offline voice command pipeline
│   ├── VoiceService.java          — Foreground service, audio capture loop
│   ├── wakeword/WakeWordEngine.java — Wake-word detection
│   ├── VoskTranscriber.java       — Vosk offline speech recognition
│   ├── LlmVoiceEngine.java        — LLM intent resolution
│   ├── VoiceCommandRouter.java    — Routes intents to app actions
│   └── VoiceLibsManager.java      — Runtime model download & management
│
├── proxy/                         — Clients of the two uid-2000 daemons (see note below)
│   ├── ProxyClient.java           — Binder calls to the PROXY daemon (getProxyDaemonBinder)
│   ├── DaemonBinderResolver.kt    — Looks up the SURFACE daemon binder (surfaceDaemonBinder)
│   ├── ProxyKeeperService.java    — Keeps the proxy daemon alive (10 s heartbeat)
│   ├── ProxyWatchdog.java         — Periodic connectivity check
│   ├── ShellGateway.java          — Fire-and-forget / result shell dispatcher
│   ├── ProxyFissionVerbs.java     — launchAndForce, moveAndResize, cleanStacks
│   ├── ProxyDisplayVerbs.java     — Overscan, display size
│   ├── ProxyCanVerbs.java         — CAN bus write verbs
│   └── daemon/                    — TWO separate uid=2000 processes (see note below)
│       ├── ProxyDaemonMain.java   — PROXY daemon entry point, Binder onTransact()
│       ├── ProxyDaemonContract.java — TXN constants
│       ├── SurfaceDaemon.java     — SURFACE daemon entry point, SurfaceControl / slot windows
│       ├── Phase4TaskVerbs.java   — FREEFORM mode, task resize, move
│       ├── Phase4DisplayVerbs.java
│       ├── Phase4ProcessVerbs.java
│       ├── CanWriteVerbs.java     — CAN bus writes
│       └── TaskRemover.java
│
├── hud/                           — Navigation HUD overlay
│   ├── HudController.java
│   ├── HudNavigationData.java
│   └── MapNotificationListenerService.java
│
├── system/                        — System-level services
│   ├── CanBusController.java
│   └── FloatingRemoteButton.java  — Persistent overlay (📺 button)
│
├── infrastructure/                — Platform-adaptive strategy layer
│   ├── AdbLocalClient.java        — ADB TCP/IP (dadb, localhost:5555)
│   ├── launch/                    — App launcher strategies (IAM, shell, fallback)
│   └── task/                      — Task finder & resizer strategies (reflection, shell, daemon)
│
├── ui/                            — All UI code
│   ├── main/                      — MainActivity coordinators
│   │   ├── ClusterControlCoordinator.java
│   │   ├── InsetAutoApplicator.java
│   │   ├── AppActionSheet.java
│   │   ├── FissionCoordinator.java
│   │   └── …
│   ├── settings/SettingsActivity.java
│   ├── diag/                      — Diagnostics & system info
│   ├── log/LogActivity.java       — Live log viewer
│   ├── hotspot/HotspotActivity.java
│   └── welcome/WelcomeActivity.java
│
├── platform/Platform.java         — DiLink version detection (DL2/3/4/5)
├── update/UpdateChecker.java      — GitHub Releases OTA
└── util/
    ├── AppLogger.java             — Circular log buffer (3000 entries, share)
    └── LocaleHelper.java
```

### Two uid-2000 daemons, not one

`proxy/daemon/` builds **two separate processes**, each with its own ServiceManager name, its own
binder and its own interface DESCRIPTOR:

| | **ProxyDaemon** (`ProxyDaemonMain`) | **SurfaceDaemon** (`SurfaceDaemon`) |
|---|---|---|
| Role | **DOES** things — stateless command executor: shell + one-shot verbs | **HOLDS** things — stateful owner of surfaces, cluster slot overlay windows, trusted VirtualDisplays, touch injection |
| ServiceManager | `byd_proxy_daemon` | `byd_mirror_daemon` |
| Get its binder | `ProxyClient.getProxyDaemonBinder()` | `DaemonBinderResolver.surfaceDaemonBinder()` (alias: `FissionClient.getBinderFromServiceManager()`) |
| If it dies | retry the command | the graphical state is lost and must be rebuilt |

Never pair one daemon's DESCRIPTOR with the other's binder: the receiving `enforceInterface`
rejects the transaction, which then **silently does nothing**. Triage rule: a failed *command* →
ProxyDaemon; a black or frozen *surface* → SurfaceDaemon.

`SurfaceDaemon` was named `MirrorDaemon` until 1.8.x; only the Java class was renamed — the wire
name, the DESCRIPTOR, the runtime process names and the log TAG still say "mirror" on purpose.

---

## Core mechanism

### VirtualDisplay cluster creation — CONFIRMED (03/05/2026)

> Source: live logcat captured on BYD Seal EU (DiLink 3.0, API 29)

**The cluster VirtualDisplay does NOT exist at boot.** It is created on demand by the
following sequence, captured to the millisecond:

```
sendInfo(1000, 30)                  → switch to 12.3" Qt profile (ADAS workaround)
sleep 6s
sendInfo(1000, 16)                  → 全屏投屏开启 — Qt enters projection mode
sleep 6s
sendInfo(1000, 35)                  → Di4.0 mode — triggers VirtualDisplay creation
  │  +132ms  FissionGenerayService (Qt native) handles sendInfo(35)
  │  +219ms  Qt calls getQtProjectionDispInfoNative() via JNI
  │  +251ms  Qt returns: name="fission_bg_xdjaVirtualSurface", bufferProducer ≠ null
  │  +274ms  DisplayManagerService: Display device ADDED
  └  +278ms  AutoDisplayService.createVirtualDisplay() → id=1, 1920×720, FLAG_PRESENTATION
```

The VirtualDisplay is ready **~280ms after sendInfo(35)**. It is owned by
`com.xdja.containerservice` (uid=1000) and has `FLAG_OWN_CONTENT_ONLY`.

### Cluster activation

```
sendInfo(1000, 30)   → switch the cluster to the Qt surface reserved for 12.3" screens
wait ~1 s
sendInfo(1000, 16)   → Qt standby (全屏投屏开启) — releases the surface for our app
wait ~2 s
am start --display 1 --windowingMode 5 <pkg>
```

`sendInfo` is sent via the **Beta Proxy Daemon** (uid=2000) because our app (uid=10080) is
blocked by `AutoContainerService.checkSendPermissionAndAllowType()`.

### Launching an app on the cluster

`ClusterService` calls `startActivityViaIAM()`, which invokes
`IActivityManager.startActivityAsUser()` via reflection with
`ActivityOptions.setLaunchDisplayId(clusterDisplayId)`.
A `Context.startActivity()` fallback is used if the IAM call fails.

The `fission_bg_xdjaVirtualSurface` display does **not** have
`FLAG_SUPPORTS_FREEFORM_WINDOW_MANAGEMENT`. ActivityOptions FREEFORM requests are silently
ignored by the ROM. After launch, `ClusterService.resizeActiveTask()` routes through the
Beta Proxy Daemon (`ProxyClient.moveAndResize`) which calls
`IActivityTaskManager.setTaskWindowingMode(FREEFORM)` with uid=2000 permissions before
resizing — the only sequence confirmed to work on this platform.

### Real-time mirror

```java
// 1. Unlock @hide Android APIs (same mechanism as WindowManagement v1.2)
VMRuntime.setHiddenApiExemptions(["Landroid/", "Lcom/android/", "Ljava/lang/"]);

// 2. Create a virtual mirror display
IBinder token = SurfaceControl.createDisplay("byd_cluster_mirror", true);
// secure=true required on DiLink 3.0

// 3. Project the cluster display onto the TextureView surface
SurfaceControl.openTransaction();
SurfaceControl.setDisplaySurface(token, new Surface(textureView.getSurfaceTexture()));
SurfaceControl.setDisplayLayerStack(token, clusterLayerStack);
SurfaceControl.setDisplayProjection(token, 0, srcRect, dstRect);
SurfaceControl.closeTransaction();
```

### Beta Proxy Daemon

The daemon is a long-running `app_process64` process loaded from the app's own APK:

```bash
CLASSPATH=<apk> exec /system/bin/app_process64 /system/bin \
  --nice-name=dashcast_proxy \
  com.byd.dashcast.proxy.daemon.ProxyDaemonMain
```

It exposes a Binder interface (registered in `ServiceManager` as `dashcast_proxy`) with
19 transactions. Key verbs:

| TXN | Method | Purpose |
|-----|--------|---------|
| 3 | `exec` | Run arbitrary shell command |
| 7 | `autocontainerSendInfo` | sendInfo to AutoContainer (uid=2000 bypasses permission check) |
| 11 | `launchAndForce` | Launch app on VirtualDisplay with FREEFORM placement |
| 12 | `moveAndResize` | Move existing task to display + force FREEFORM + resize |
| 13 | `cleanFissionStacks` | Remove zombie split-screen stacks |
| 16 | `canNaviStatus` | Write CAN navigation status |

`ProxyKeeperService` reconnects to the daemon every 10 s if the Binder is dead.
`ProxyWatchdog` polls every 30 s during active sessions.

### Restore

```
am force-stop <app>                  → releases the Qt surface
sendInfo(1000, 18)                   → 投屏关闭 — close projection
sendInfo(1000, 0)                    → 主机恢复仪表视频流 — Qt resumes
```

---

## WindowManagement v1.2 — Reverse Engineering

`WindowManagement v1.2` is a third-party app used on DiLink systems to control the cluster
display surface. Its internal Binder API was reverse-engineered to identify the hidden method
names used to interact with `SurfaceControl`:
`openTransaction`, `setDisplaySurface`, `setDisplayProjection`,
`setDisplayLayerStack`, `closeTransaction`.

DashCast uses the same static `SurfaceControl` API directly, confirming compatibility
with DiLink 3.0.

---

## Prerequisites

### 1. ADB over network (TCP/IP)

The app communicates with the car via **ADB TCP/IP on port 5555** (localhost, tunneled from the infotainment unit itself). This requires ADB to be enabled on the DiLink system.

On BYD Seal EU (DiLink 3.0), ADB TCP is available at `localhost:5555` from within the infotainment Android environment — no USB cable needed at runtime. The app uses the [dadb](https://github.com/mobile-dev-inc/dadb) library to connect.

### 2. Platform keystore

To obtain `signature`-level permissions (`INJECT_EVENTS`, `BYDAUTO_*`) the APK must be signed with the platform key — the public AOSP test key, as [SECURITY.md](SECURITY.md) explains. Place it at `app/keystore/platform.keystore`.

Only needed to **run** the privileged features. The project builds without it; see [Build requirements](#build-requirements).

---

## Installation

1. Download the latest APK from [GitHub Releases](https://github.com/Kiroha/byd-dashcast/releases/latest):
  - **Stable** (recommended): latest non-pre-release asset on the Releases page
  - **Beta** (bleeding edge): [all releases](https://github.com/Kiroha/byd-dashcast/releases)

2. **Uninstall any previous version first** (see breaking change notice above):
```bash
adb uninstall com.byd.myapp     # if coming from any alpha
adb uninstall com.byd.dashcast  # if coming from a previous beta
```

3. Sideload onto the infotainment unit:
```bash
adb connect <car-ip>:5555
adb install DashCast-vX.Y.Z-release.apk
```

4. Launch the app. On first launch, an **"Allow USB debugging?"** popup will appear **on the car's screen** — press **ALLOW**.
5. The app should be functional immediately.

   > On DiLink 3.0 with `platform.keystore` signing, the BYD permissions are typically pre-granted by the ROM at install time.

> If you don't have the car's IP, the app can also be installed via USB when ADB USB debugging is enabled (developer options).

### OTA updates

Once DashCast is installed, future updates are automatic:
- On every launch, DashCast checks GitHub Releases for a newer version
- A download progress dialog appears, then the system install prompt
- Enable **Settings → Beta channel** to also receive pre-release builds between stable releases

---

## Known issues

- **BYD vehicle data permissions**: On some units, `BYDAUTO_*` permissions are denied at the platform level regardless of signing. Speed, energy, and instrument data will be unavailable; all other features are unaffected.
- **resizeTask on first install**: On a fresh install, the cluster task may not resize to fill the display on the first launch. This resolves automatically after the first successful `moveAndResize` cycle through the daemon.
- **App persistence**: Apps launched on the cluster may return to the main display after a phone call or ADAS event (Qt reclaims the surface).
- **Voice models**: Vosk and LLM models are several hundred MB and are downloaded on first voice activation. Requires an active internet connection during the initial setup.

---

## Build requirements

| Tool | Version | Where it is pinned |
|---|---|---|
| JDK | **17** — a full JDK, not a JRE (AGP 8 runs `jlink`) | `app/build.gradle` (`JavaVersion.VERSION_17`) |
| AGP | **8.13.2** | `build.gradle` |
| Kotlin | **2.4.0** | `build.gradle` |
| Gradle wrapper | **8.14.5** | `gradle/wrapper/gradle-wrapper.properties` |
| compileSdk | **33** | `app/build.gradle` |
| targetSdk | **29** — deliberately frozen for DiLink compatibility, do not raise it | `app/build.gradle` |
| minSdk | **28** | `app/build.gradle` |

A stock Android SDK is enough. Point `sdk.dir` in `local.properties` at it, or set
`ANDROID_HOME`, as with any Android project.

### The BYD SDK is not required to build

Older revisions of this file said it was, and that instruction outlived the fact.
The proprietary SDK's distinguishing artefact is a modified `android.jar` carrying the
`android.hardware.bydauto.*` classes, and it exists only in that SDK's `platforms/android-25`.
This project compiles against **API 33**, whose `android.jar` — the BYD SDK's own copy included —
contains none of those classes.

They come from `app/libs/byd-auto-api-stubs.jar` instead, which **is** in this repository:
interface declarations only, no implementation, enough to compile against and useless to run.
Move it aside and `compileDebugJavaWithJavac` fails on the six references in
`CanFeedbackListener.java`; put it back and the build is green. That is the whole of the
dependency.

### Signing

`app/keystore/platform.keystore` is not in the repository, and the build no longer requires it:
if it is absent, Gradle falls back to its own debug signing and says so. See the note under
[Build](#build) for what that costs at runtime.

```
app/keystore/platform.keystore
  alias: androiddebugkey | storepass/keypass: android
```

The key itself is the **public AOSP platform test key** — see [SECURITY.md](SECURITY.md), which
explains why that is both intentional and unavoidable on this hardware. The copy shipped in the
BYD SDK is byte-for-byte the same file. It is not a secret, and it is not proof of authorship.

---

## Build

```bash
cd MyBYDApp   # repo folder name
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/DashCast-v<versionName>-release.apk

# Debug build — for a development machine, NOT for a car (see the warning below):
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/DashCast-v<versionName>-debug.apk
```

> **Building without the platform key.** `app/keystore/platform.keystore` is not in the repository —
> it is what grants the BYD system permissions, and an APK signed with it is a privileged APK. If it
> is absent the build still works: Gradle falls back to its default debug signing and prints a line
> saying so. The APK installs and runs, and the bydauto features — cluster projection, HUD, the
> uid-2000 daemon — are denied at runtime. That is enough to read, compile and review the code, which
> is what SECURITY.md means by "build from source".

> **Do not leave a debug build on a vehicle.** It carries the same platform signature as the
> release — that part is unavoidable, the BYD permissions depend on it — but it is also
> `debuggable`, which the release is not. On a head unit, ADB over TCP has to be enabled for
> DashCast to work at all, so anyone who can reach that port can attach a debugger to a process
> holding platform permissions. The release build is the one to install; use debug builds on the
> bench and uninstall them when you are done.

---

## Permissions

| Permission | Type | Usage |
|---|---|---|
| `INJECT_EVENTS` | signature | Touch/key injection to the cluster |
| `SYSTEM_ALERT_WINDOW` | dangerous | Floating overlay (FloatingRemoteButton) |
| `FOREGROUND_SERVICE` | normal | ClusterService, VoiceService |
| `RECORD_AUDIO` | dangerous | Voice command pipeline |
| `INTERNET` | normal | OTA update check, voice model download |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | signature | Navigation HUD (map notification parsing) |
| `BYDAUTO_*_COMMON` (×11) | dangerous | BYD vehicle APIs |
| `BYDAUTO_*_GET` | signature | Extended read (not grantable without real BYD key) |

`dangerous` permissions are typically pre-granted by the ROM at install time on DiLink 3.0 (platform-signed APK).

---

## AutoContainer service (cluster)

- Binder: `ServiceManager.getService("AutoContainer")`
- Transaction `#2` = `sendInfo(int type, int infoInt, String infoStr)`
- ADB relay: `service call AutoContainer 2 i32 1000 i32 <cmd> s16 ""`

### Projection control

| cmd | Action | Confirmed |
|-----|--------|---------|
| 16 | Qt standby — releases the cluster surface for our app (全屏投屏开启) | ✅ 16/04/2026 |
| 18 | Close projection — re-enables Qt stream (投屏关闭) | ✅ 16/04/2026 |
| 0  | Refresh Qt video stream — Qt resumes (主机恢复仪表视频流) | ✅ |
| 1  | **⛔ DO NOT USE** — disconnects Qt entirely (destroys display 1) | — |

### Cluster display size

The instrument cluster size mode must be set before launching any app.
The Seal EU has a **10.25" physical screen** (cmd 31), but this mode causes ADAS widget
stretching. Using cmd 30 (12.3" Seal U-DMI rounded screen profile) fixes the aspect ratio.

| cmd | Screen size | Model | Notes |
|-----|------------|-------|-------|
| 29  | 8.8"  | BYD Atto 3 | — |
| 30  | 12.3" | BYD Seal U-DMI (rounded cluster) | **Use this on Seal EU** — fixes ADAS stretching |
| 31  | 10.25" | BYD Seal EU | Native size but causes ADAS window distortion |

```bash
# Force 12.3" mode (recommended for Seal EU):
adb shell service call AutoContainer 2 i32 1000 i32 30 s16 ""
```

> This command is sent automatically at the start of the cluster activation sequence (`sendInfo(1000, 30)`).

---

## Retrieve logs without USB cable

```bash
adb pull /sdcard/Android/data/com.byd.dashcast/files/
```

---

## License

This project is licensed under the [MIT License](LICENSE).

> **Note on dependencies**: the `android.hardware.bydauto.*` APIs originate in the proprietary
> **BYD SDK v1.0.5**, which is NOT included here and is NOT covered by the MIT license.
> Building does not require it — see [Build requirements](#build-requirements).
>
> The file `app/libs/byd-auto-api-stubs.jar` is a stub-only extract of the BYD SDK v1.0.5
> (interface declarations, no implementation). It is included solely to allow the project
> to compile without requiring the full SDK. All rights over this file remain with BYD Auto
> Co., Ltd. If you are the rights holder and wish it removed, please open an issue.
>
> WindowManagement is a third-party application (not BYD) whose behavior has been
> analyzed for interoperability purposes only.
