> **About the author** — I am not a professional developer, but I work in IT with a solid understanding of software development. This project was built through **vibe coding** with AI assistance (GitHub Copilot / Claude), which allowed me to ship this app despite having no prior native Android experience. The code reflects that approach: functional and goal-oriented, but with room for improvement. **Expert contributions are very welcome** — whether it's bug fixes, code review, or broader improvements to the app.

---

# DashCast — BYD Cluster Launcher & Mirror

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![API 29](https://img.shields.io/badge/API-29%20(Android%2010)-green.svg)](https://developer.android.com/about/versions/10)
[![Status: Alpha](https://img.shields.io/badge/Status-Alpha-red.svg)](CHANGELOG.md)

Android application for **BYD vehicles with DiLink 3.0** (Android 10) to push any installed app
onto the instrument cluster display, control it via a real-time touch mirror, and diagnose
BYD APIs.

> **Tested on**: BYD Seal EU 2024 — DiLink 3.0 (XDJA/Qualcomm 6125F) — Android 10 (API 29)

> [!WARNING]
> **Alpha software** — This project is in early alpha. Expect bugs, incomplete features,
> and breaking changes between releases. Use at your own risk.
> The authors are not responsible for any damage to your vehicle's infotainment system.

---

## Features

| # | Feature | Description |
|---|---|---|
| 1 | **App list** | All installed apps (sorted RecyclerView) |
| 2 | **→ Cluster** | Push an app to the cluster (ADB trampoline uid=2000 + display=1 FREEFORM) |
| 3 | **→ Main screen** | Move an app from the cluster back to display 0 |
| 4 | **Touch mirror** | Real-time TextureView of the cluster via `SurfaceControl.createDisplay()` + touch forwarding |
| 5 | **Split 50/50** | Two apps side by side on the cluster (force-stop + relaunch with `--bounds`) |
| 6 | **Restore BYD** | `sendInfo(18+0)` → Qt regains control of the cluster |
| 7 | **Origin cluster** | `sendInfo(30+18+0)` → restores correct resolution + Qt |
| 8 | **⚙ Settings** | Cluster screen size: 8.8" / 12.3" (Seal EU default) / 10.25" |
| 9 | **🔧 Diagnostic** | 7 sections: ADB/permissions, cluster restore, display size, MirrorDaemon, Sniffer, cluster orientation, DiLink5 resize test (WM:4→5 + resizeTask FORCED) |
| 10 | **📋 System report** | Displays, permissions, build tags, APK signature |
| 11 | **Live log** | LogActivity — DEBUG/INFO/WARN/ERROR levels, filters, share |
| 12 | **Multilingual** | French / English / German / Italian / Turkish / Russian / Uzbek / Kazakh / Belarusian / Ukrainian, selected on first launch |
| 13 | **Floating overlay** | Two floating buttons: LOG (opens log) and 📺 Mirror (opens cluster mirror / returns to main screen) |

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

## Code structure

```
app/src/main/java/com/byd/myapp/
├── MainActivity.java           — Main 15.6" screen: app list, cluster mirror, split
├── WelcomeActivity.java        — Language selection (first launch)
├── DiagActivity.java           — Sections 1–7 (ADB, restore, display size, MirrorDaemon, Sniffer, cluster orientation, DiLink5 resize test)
├── SysInfoActivity.java        — System report + share
├── ClusterService.java         — Foreground service: cluster projection independent of Activity lifecycle
├── AdbLocalClient.java         — All ADB logic (dadb, localhost:5555)
├── AppListAdapter.java         — RecyclerView (→ Cluster / ← Main / → Cluster / ✕)
├── AppLogger.java              — Singleton logger (levels, 3000 entries, saveToFile, share)
├── LogActivity.java            — Real-time log (filters, auto-scroll, share)
├── FloatingLogButton.java      — Floating overlay: tap=LogActivity, long press=clear log
├── FloatingRemoteButton.java   — Floating overlay: tap=MainActivity, long press=close
├── LocaleHelper.java           — Language persistence (SharedPreferences)
├── daemon/
│   └── MirrorDaemon.java        — Core proxy class mirroring cluster display
└── dashboard/
    ├── ClusterManager.java          — Cluster activation sequence (sendInfo 30→16→35, autonomous)
    ├── DashboardDisplayHelper.java  — Cluster VirtualDisplay detection (DisplayManager + polling)
    ├── DashboardLauncher.java       — Launch app on main display (setLaunchDisplayId)
    ├── ClusterTrampolineActivity.java — Exported trampoline launched via ADB uid=2000 on display 1
    ├── ClusterMirrorManager.java    — SurfaceControl mirror (createDisplay + Transaction + touch)
    └── ClusterInputForwarder.java   — MotionEvent/KeyEvent injection to the cluster
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
`com.xdja.containerservice` (uid=1000) and has `FLAG_OWN_CONTENT_ONLY`, meaning only its
owner can write to it. Apps are launched on it via `am start --display 1`.

### Cluster activation

```
sendInfo(1000, 30)   → switch the cluster to the Qt surface reserved for 12.3" screens —
                       this fixes the ADAS window layout issue that occurs on 10.25" panels
wait ~1 s
sendInfo(1000, 16)   → Qt standby (全屏投屏开启) — releases the surface for our app
wait ~2 s
am start --display 1 --windowingMode 5 ClusterTrampolineActivity --es target_package <pkg>
```

`sendInfo` is sent via **ADB relay** (uid=2000) because our app (uid=10xxx) is blocked
by `AutoContainerService.checkSendPermissionAndAllowType()`.

### Launching an app on the cluster

`ClusterTrampolineActivity` is **exported** in the Manifest. ADB shell (uid=2000) launches
it on `display=1` with `--windowingMode 5` (FREEFORM). Once on display 1, the trampoline
starts the target app via `startActivity()` without `setLaunchDisplayId` — the task
inherits the source display.

> **Why a trampoline?** Our APK is signed with the BYD SDK `platform.keystore`
> (CN=Android — AOSP testkey), not with the real BYD `auto_api` key (CN=auto_api, O=比亚迪).
> `INTERNAL_SYSTEM_WINDOW` is therefore not granted to our app (uid=10xxx). ADB shell
> (uid=2000) holds it on this ROM → it launches our exported trampoline.

### Real-time mirror

```java
// 1. Unlock @hide Android APIs (same mechanism as WindowManagement v1.2)
VMRuntime.setHiddenApiExemptions(["Landroid/", "Lcom/android/", "Ljava/lang/"]);

// 2. Create a virtual mirror display
IBinder token = SurfaceControl.createDisplay("byd_cluster_mirror", true);
// secure=true required on DiLink 3.0 (same as WindowManagement v1.2)

// 3. Project the cluster display onto the TextureView surface
SurfaceControl.openTransaction();
SurfaceControl.setDisplaySurface(token, new Surface(textureView.getSurfaceTexture()));
SurfaceControl.setDisplayLayerStack(token, clusterLayerStack);
SurfaceControl.setDisplayProjection(token, 0, srcRect, dstRect);
SurfaceControl.closeTransaction();
```

### Restore

```
am force-stop <app>                  → releases the Qt surface
sendInfo(1000, 18)                   → 投屏关闭 — close projection
sendInfo(1000, 0)                    → 主机恢复仪表视频流 — Qt resumes
```

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

1. Build the APK (see [Build](#build))
2. Sideload onto the infotainment unit:
```bash
adb connect <car-ip>:5555
adb install -r app/build/outputs/apk/debug/DashCast-v0.1.31-alpha-debug.apk
```
3. Launch the app. On first launch, an **"Allow USB debugging?"** popup will appear **on the car's screen** — press **ALLOW**.
4. The app should be functional immediately. If permissions are missing, open **⋮ menu → Diagnostic → TEST 1** to force-grant `BYDAUTO_*_COMMON` permissions via `pm grant`.

   > On DiLink 3.0 with `platform.keystore` signing, these permissions are typically pre-granted by the ROM at install time and TEST 1 is not required.

> If you don't have the car's IP, the app can also be installed via USB when ADB USB debugging is enabled (developer options).

---

## Known issues (alpha)

- **Reliability**: The cluster activation sequence may fail on the first attempt — retry
- **Mirror touch (first launch)**: Touch input on the mirror does not work on the very first run. Force-stop the app and relaunch it — touch will work correctly from the second start onwards
- **App persistence**: Apps launched on the cluster may return to the main display after a phone call or ADAS event (Qt reclaims the surface)
- **Split 50/50**: Experimental — may fail depending on target app window mode
- **Language**: The UI has been translated to English, but some messages (toasts, logs) may still appear in French

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

The `app/build.gradle` signing config applies this keystore for both debug and release.

---

## Build

```bash
cd MyBYDApp   # repo folder name
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/DashCast-v0.1.31-alpha-debug.apk
```

---

## Permissions

| Permission | Type | Usage |
|---|---|---|
| `INJECT_EVENTS` | signature | Touch/key injection to the cluster |
| `SYSTEM_ALERT_WINDOW` | dangerous | Floating overlay (FloatingLogButton) |
| `FOREGROUND_SERVICE` | normal | ClusterService |
| `INTERNET` | normal | Network access (reserved for future use) |
| `BYDAUTO_*_COMMON` (×11) | dangerous | BYD vehicle APIs (declared, not yet used) |
| `BYDAUTO_*_GET` | signature | Extended read (not grantable without real BYD key) |

`dangerous` permissions are granted via `pm grant` on first launch (TEST 1 — Diagnostic).

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
> Use **TEST 3** in DiagActivity to cycle through sizes manually.

---

---

## Field diagnostic

1. **TEST 1** → ADB connection + `pm grant` `_COMMON` permissions
2. **TEST 2** → cluster restore (sendInfo 30→16→18→0)
3. **TEST 3** → cluster display size change (cmd 29/30/31)
4. **TEST 4** → MirrorDaemon: scan / kill / kill+restart / export logs / SurfaceFlinger dump
5. **TEST 5** → Sniffer: scan / start / stop / export logcat report / clean logs
6. **TEST 6** → Cluster orientation: freeze LANDSCAPE/PORTRAIT / unfreeze / read rotation (`IWindowManager.freezeDisplayRotation` — no `wm size`)
7. **TEST 7** → DiLink5 resize test: `WM:4→5` + `resizeTask(RESIZE_MODE_FORCED=3)` via raw Binder + task inspection

### Retrieve logs without USB cable

```bash
adb pull /sdcard/Android/data/com.byd.myapp/files/  # package ID unchanged
```

---

## Version history

| Version | versionCode | Summary |
|---------|-------------|--------|
| **0.1.31-alpha** | 32 | **DiLink5 resize test (Section 7)** — WM:4→5 + resizeTask FORCED=3 via raw Binder; sanity lint fixes; CHANGELOG 0.1.8→0.1.31; docs/ HTML manuals (5 locales) for GitHub Pages — **current** |
| **0.1.16-alpha** | 17 | Watchdog /proc fallback + PID cache + overscan tuning screen + misc UI renames |
| **0.1.15-alpha** | 16 | Fix DashCastDaemon UID mismatch bypass |
| **0.1.14-alpha** | 15 | Display 0 guard-rails — full security audit |
| **0.1.13-alpha** | 14 | Long-press any result TextView → share via Telegram |
| **0.1.12-alpha** | 13 | Mirror shortcut floating button (replaces GPS), LogActivity overflow menu entry, dead strings cleanup |
| **0.1.11-alpha** | 12 | **Sanity checks #5→#8** — JMM volatile, dead code (~120 lines), empty catches logged, orphan imports/strings |
| **0.1.7-alpha** | 8 | **Freedom-free** — all Freedom (com.xdja.clusterdemo) references removed, VirtualDisplay creation confirmed (sendInfo 30→16→35), fully autonomous |
| **0.1.6-alpha** | — | DiLink 5 RE sniffer, deep dumpsys, broadcast tracking |
| **0.1.5-alpha** | — | Stability improvements (last release with Freedom dependency) |
| **0.1.4-alpha** | — | Fix touch offset bug in cluster mirror input forwarding |
| **0.1.3-alpha** | — | Full i18n (FR/EN/DE/IT/TR), string externalization |
| **0.1.2-alpha** | — | EN translation, DashCast rename, new icon — device-validated |
| **0.1.1-alpha** | — | Bug fixes, code sanity, README improvements |
| **0.1.0-alpha** | — | First public release — cluster mirror working (image + touch) |

Full internal development history: [CHANGELOG.md](CHANGELOG.md)

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
