> **About the author** — I am not a professional developer, but I work in IT with a solid understanding of software development. This project was built through **vibe coding** with AI assistance (**Claude Sonnet 4.6** and **Gemini Pro**), which allowed me to ship this app despite having no prior native Android experience. The code reflects that approach: functional and goal-oriented, but with room for improvement. **Expert contributions are very welcome** — whether it's bug fixes, code review, or broader improvements to the app. Version history is available in [GitHub Releases](https://github.com/Kiroha/byd-dashcast/releases).

---

# DashCast — BYD Cluster Launcher & Mirror

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![API 29](https://img.shields.io/badge/API-29%20(Android%2010)-green.svg)](https://developer.android.com/about/versions/10)
[![Latest Release](https://img.shields.io/github/v/release/Kiroha/byd-dashcast?label=stable&color=brightgreen)](https://github.com/Kiroha/byd-dashcast/releases/latest)
[![Pre-release](https://img.shields.io/github/v/release/Kiroha/byd-dashcast?include_prereleases&label=beta&color=blue)](https://github.com/Kiroha/byd-dashcast/releases)
[![Docs](https://img.shields.io/badge/docs-kiroha.github.io-blue)](https://kiroha.github.io/byd-dashcast/)
[![Telegram](https://img.shields.io/badge/Telegram-community-2CA5E0?logo=telegram)](https://t.me/+QPk_dmTVaNkxMjFk)

Android application for **BYD vehicles with DiLink 3.0** (Android 10) to push any installed app onto the instrument cluster display, control it via a real-time touch mirror, run multiple apps simultaneously on the cluster with the **Fission** layout engine, and interact with the car using **voice commands**.

> **Tested on**: BYD Seal EU 2024 — DiLink 3.0 (XDJA/Qualcomm 6125F) — Android 10 (API 29)

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
| 6 | **Voice commands** | Wake-word detection → offline speech recognition (Vosk) → LLM processing → TTS response. Models downloaded at first use via `VoiceLibsManager` |
| 7 | **HUD overlay** | Navigation data extracted from active map notifications and rendered on the cluster (speed, direction, next instruction, ETA) |
| 8 | **Per-app DPI override** | Adjustable cluster display DPI per package — corrects apps that render incorrectly at 320 dpi |
| 9 | **Restore BYD** | `sendInfo(18+0)` → Qt regains control of the cluster |
| 10 | **Origin cluster** | `sendInfo(30+18+0)` → restores correct resolution + Qt |
| 11 | **Settings** | Cluster screen size, auto-launch, Fission auto-layout, beta OTA channel, per-app insets |
| 12 | **Diagnostics** | Shell probes, BYD API instantiation tests, live CAN data, display enumeration |
| 13 | **System report** | Displays, system properties, BYD packages, permissions, proxy metrics, DiLink probe results |
| 14 | **Live log** | LogActivity — DEBUG/INFO/WARN/ERROR levels, filters, auto-scroll, share |
| 15 | **Multilingual** | French / English / German / Italian / Spanish / Polish / Turkish / Russian / Ukrainian / Arabic / Uzbek / Kazakh / Belarusian (13 languages), selected on first launch |
| 16 | **Floating overlay** | Persistent 📺 button: tap opens mirror, long-press opens quick-switch (recent cluster apps) |
| 17 | **Hotspot control** | Toggle and monitor Wi-Fi hotspot from within the app |
| 18 | **Display affinity safeguards** | Moves session apps back to Display 0 when projection stops or app is killed |
| 19 | **OTA update** | Auto-check against GitHub Releases API, silent install via `PackageInstaller` (platform key), fallback to system dialog |

---

## Architecture overview

DashCast is organized around three runtime layers:

**App layer** (`uid=10080`) — MainActivity and all UI coordinators. Handles user interaction, app list, settings, mirror rendering, Fission layout UI, and voice commands.

**ClusterService** (`uid=10080`, foreground service) — Manages cluster projection independently of the Activity lifecycle. Owns the display connection, mirror pipeline, touch forwarding, and task resize.

**Beta Proxy Daemon** (`uid=2000` / shell) — A background `app_process64` daemon that runs with shell-level permissions. Handles operations that require elevated access: `sendInfo` calls to `AutoContainer`, `SurfaceControl` mirror transactions, task windowing mode changes, FREEFORM stack management, and CAN bus writes. Kept alive by `ProxyKeeperService`. The app falls back gracefully if the daemon is unavailable.

---

## Code structure

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
├── proxy/                         — Beta Proxy Daemon client interface
│   ├── ProxyClient.java           — All Binder calls to the daemon
│   ├── ProxyKeeperService.java    — Keeps the daemon alive (10 s heartbeat)
│   ├── ProxyWatchdog.java         — Periodic connectivity check
│   ├── ShellGateway.java          — Fire-and-forget / result shell dispatcher
│   ├── ProxyFissionVerbs.java     — launchAndForce, moveAndResize, cleanStacks
│   ├── ProxyDisplayVerbs.java     — Overscan, display size
│   ├── ProxyCanVerbs.java         — CAN bus write verbs
│   └── daemon/                    — Daemon process (runs as uid=2000)
│       ├── ProxyDaemonMain.java   — Entry point, Binder onTransact()
│       ├── ProxyDaemonContract.java — TXN constants
│       ├── MirrorDaemon.java      — SurfaceControl mirror transactions
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

The APK must be signed with `platform.keystore` (included in the BYD SDK v1.0.5) to obtain `signature`-level permissions (`INJECT_EVENTS`, `BYDAUTO_*`).

Place it at `app/keystore/platform.keystore` before building.

### 3. BYD SDK

See [Build requirements](#build-requirements) below.

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

| Tool | Version |
|---|---|
| JDK | 11 (Temurin recommended) |
| Android SDK | API 29 compileSdk, **BYD SDK v1.0.5** as sdk.dir |
| AGP | 7.4.2 |
| Gradle wrapper | 7.6 |

### BYD SDK

This project requires BYD SDK v1.0.5 (modified `android.jar` with `android.hardware.bydauto.*`).

> The SDK is **not included** in this repository (proprietary).  
> Extract to: `../sdk/SDK_v1.0.5/byd-auto_sdk_windows/`  
> Configure `local.properties`:

```properties
sdk.dir=/path/to/sdk/SDK_v1.0.5/byd-auto_sdk_windows
```

### Signing

The APK must be signed with `platform.keystore` (BYD SDK) for `signature` permissions
(`INJECT_EVENTS`, `BYDAUTO_*_COMMON`).

```
app/keystore/platform.keystore
  alias: androiddebugkey | storepass/keypass: android
```

The `app/build.gradle` signing config applies this keystore for both debug and release builds.

---

## Build

```bash
cd MyBYDApp   # repo folder name
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/DashCast-v<versionName>-release.apk

# Debug build (same platform-signed APK, useful for development):
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/DashCast-v<versionName>-debug.apk
```

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

> **Note on dependencies**: This project requires **BYD SDK v1.0.5** (proprietary) which
> is NOT included in this repository and is NOT covered by the MIT license.
> The BYD SDK contains a modified `android.jar` with `android.hardware.bydauto.*` APIs.
> You must obtain it separately.
>
> The file `app/libs/byd-auto-api-stubs.jar` is a stub-only extract of the BYD SDK v1.0.5
> (interface declarations, no implementation). It is included solely to allow the project
> to compile without requiring the full SDK. All rights over this file remain with BYD Auto
> Co., Ltd. If you are the rights holder and wish it removed, please open an issue.
>
> WindowManagement is a third-party application (not BYD) whose behavior has been
> analyzed for interoperability purposes only.
