# MyBYDApp — BYD Cluster Launcher & Mirror

Android application for **BYD Seal EU** (DiLink 3.0 — Android 10) to push any installed app
onto the instrument cluster display, control it via a real-time touch mirror, and diagnose
BYD APIs.

> **Tested on**: BYD Seal EU 2024 — DiLink 3.0 (XDJA/Qualcomm 6125F) — Android 10 (API 29)

---

## Features

| # | Feature | Description |
|---|---|---|
| 1 | **App list** | All installed apps (sorted RecyclerView) |
| 2 | **→ Cluster** | Push an app to the cluster (ADB trampoline uid=2000 + display=1 FREEFORM) |
| 3 | **→ Main screen** | Move an app from the cluster back to display 0 |
| 4 | **Touch mirror** | Real-time SurfaceView of the cluster via `SurfaceControl.createDisplay()` + touch forwarding |
| 5 | **Split 50/50** | Two apps side by side on the cluster (force-stop + relaunch with `--bounds`) |
| 6 | **Remote control** | ←/⌂/↑/↓/Vol+/Vol− buttons via `InputManager.injectInputEvent()` |
| 7 | **Restore BYD** | `sendInfo(18+0)` → Qt regains control of the cluster |
| 8 | **Origin cluster** | `sendInfo(30+18+0)` → restores correct resolution + Qt |
| 9 | **⚙ Settings** | Cluster screen size: 8.8" / 12.3" (Seal EU default) / 10.25" |
| 10 | **🔧 Diagnostic** | 4 ADB tests (permissions, cluster restore, display size, Freedom BootReceiver) |
| 11 | **📋 System report** | Displays, permissions, build tags, APK signature |
| 12 | **Live log** | LogActivity — DEBUG/INFO/WARN/ERROR levels, filters, Azure export |
| 13 | **☁ Azure Export** | Push to Azure Log Analytics (HMAC-SHA256, table `BYDAppLog_CL`) |
| 14 | **Multilingual** | French / English, selected on first launch |

---


## 🔓 Freedom v1.9 IPC Cracked & Daemon Proxy

In the latest major breakthrough, the **anti-tamper obfuscation** of the `Freedom v1.9` / `com.xdja.clusterdemo` application has been completely reverse-engineered. 

* **The Honeypot**: The original APK included a decoy `DES/ECB/NoPadding` encryption block deliberately seeded with an invalid 7-byte key (`"decrypt"`) designed to throw a native `InvalidKeyException` on Android and waste reverse-engineering efforts.
* **The Reality**: The actual strings are obfuscated through a complex character-substitution map converting back to standard Base64.
* **The Result**: We extracted all hidden root/ADB daemon commands (e.g., `--nice-name=ClusterDemoProcess CommunicationProcessKt`) and Inter-Process Communication (IPC) intents (like `ACTION_cluster_demo_process_started`).

### Diagnostic Features added:
The `DiagActivity` now features a **Pseudo-Daemon (Freedom IPC)** test button. This executes `Runtime.getRuntime().exec()` simulating the `app_process` injection exactly as Freedom does it, proving that we can bypass the third-party app entirely and run our own native code autonomously in the background without requiring root permissions on the BYD system.

For full technical details, see `FREEDOM_INJECTION_STRATEGY.md` and `FREEDOM_HIDDEN_SECRETS.md`.

---

## Code structure

```
app/src/main/java/com/byd/myapp/
├── MainActivity.java           — Main 15" screen: app list, cluster mirror, split
├── WelcomeActivity.java        — Language selection (first launch)
├── DiagActivity.java           — Tests 1–4 (ADB, restore, display size, BootReceiver)
├── SysInfoActivity.java        — System report + share
├── ClusterService.java         — Foreground service: cluster projection independent of Activity lifecycle
├── AdbLocalClient.java         — All ADB logic (dadb, localhost:5555)
├── AppListAdapter.java         — RecyclerView (→ Cluster / ← Main / → Cluster / ✕)
├── AppLogger.java              — Singleton logger (levels, 3000 entries, saveToFile, share)
├── AzureLogExporter.java       — HTTP Data Collector → Azure Log Analytics
├── LogActivity.java            — Real-time log (filters, auto-scroll, Azure)
├── FloatingLogButton.java      — Floating overlay (DEBUG builds only)
├── LocaleHelper.java           — Language persistence (SharedPreferences)
├── daemon/
│   └── MirrorDaemon.java        — Core proxy class mirroring Freedom behavior
└── dashboard/
    ├── BydVideoMirrorClient.java    — Proxy connection intercepting physical video feed
    ├── ClusterManager.java          — Cluster activation sequence (sendInfo 30+16, Freedom fallback)
    ├── DashboardDisplayHelper.java  — Cluster VirtualDisplay detection (DisplayManager + polling)
    ├── DashboardLauncher.java       — Launch app on main display (setLaunchDisplayId)
    ├── ClusterTrampolineActivity.java — Exported trampoline launched via ADB uid=2000 on display 1
    ├── ClusterMirrorManager.java    — SurfaceControl mirror (createDisplay + Transaction + touch)
    └── ClusterInputForwarder.java   — MotionEvent/KeyEvent injection to the cluster
```

---

## Core mechanism

### Cluster activation

```
sendInfo(1000, 30)   → switch cluster to Seal EU 12.3" mode (correct resolution, no ADAS stretching)
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
IBinder token = SurfaceControl.createDisplay("byd_cluster_mirror", false);
// fallback secure=true if null (WindowManagement v1.2 uses true on DiLink 3.0)

// 3. Project the cluster display onto the SurfaceView
SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
tx.setDisplaySurface(token, surfaceViewSurface);
tx.setDisplayLayerStack(token, displayId << 16);  // SurfaceFlinger Android 10 convention
tx.setDisplayProjection(token, ROTATION_0, srcRect, dstRect);
tx.apply();
```

Touch: `MotionEvent.setDisplayId(clusterDisplayId)` + `InputManager.injectInputEvent()`.

### Restore

```
am force-stop <app>                  → releases the Qt surface
am force-stop com.xdja.clusterdemo   → stops Freedom (prevents it from reclaiming display 1)
sendInfo(1000, 18)                   → 投屏关闭 — close projection
sendInfo(1000, 0)                    → 主机恢复仪表视频流 — Qt resumes
```

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
azure.workspace.id=<Azure Log Analytics workspace ID>
azure.primary.key=<Azure primary key>
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
cd MyBYDApp
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | Type | Usage |
|---|---|---|
| `INJECT_EVENTS` | signature | Touch/key injection to the cluster |
| `SYSTEM_ALERT_WINDOW` | dangerous | Floating overlay (FloatingLogButton) |
| `FOREGROUND_SERVICE` | normal | ClusterService |
| `INTERNET` | normal | Azure Log Analytics export |
| `BYDAUTO_*_COMMON` (×11) | dangerous | Vehicle data (speed, energy, gearbox…) |
| `BYDAUTO_*_GET` | signature | Extended read (not grantable without real BYD key) |

`dangerous` permissions are granted via `pm grant` on first launch (TEST 1 — Diagnostic).

---

## AutoContainer service (cluster)

- Binder: `ServiceManager.getService("AutoContainer")`
- Transaction `#2` = `sendInfo(int type, int infoInt, String infoStr)`
- ADB relay: `service call AutoContainer 2 i32 1000 i32 <cmd> s16 ""`

| cmd | Action | Confirmed |
|-----|--------|---------|
| 30 | Cluster 12.3" Seal EU | ✅ 16/04/2026 |
| 16 | Qt standby (enable projection) | ✅ 16/04/2026 |
| 18 | Close projection | ✅ 16/04/2026 |
| 0  | Refresh Qt video stream | ✅ |
| 1  | **⛔ DO NOT USE** — disconnects Qt entirely (destroys display 1) | — |

---

## Freedom (com.xdja.clusterdemo)

Freedom state is **checked at startup** before the cluster activation sequence.
`ClusterService.checkAndStartWithFreedom()` runs `AdbLocalClient.checkFreedomState()` and:

| State | Action |
|-------|--------|
| `ACTIVE` — VirtualDisplay `fission_*` present | Proceed directly to `sendInfo(30+16)` |
| `INACTIVE` — installed but VirtualDisplay absent | `startFreedom()` (force-stop + write `navigationType=1` + `am start`) → wait 2 s → activate |
| `NOT_INSTALLED` | Proceed anyway (fallback to display id=1 hardcoded) |

The current state is displayed in the main status bar (`tvDashboardStatus`).

`AutoDisplayService` (com.xdja.containerservice) creates the VirtualDisplay at boot:
```
createVirtualDisplay("fission_testVirtualSurface", 1920, 1080, 320, qtSurface, 11)
flags 11 = PUBLIC | PRESENTATION | OWN_CONTENT_ONLY
```

Freedom config file:
```
/sdcard/Android/data/com.xdja.clusterdemo/data/properties.xml
```
Java-serialized HashMap (ObjectOutputStream): `{"navigationType": Integer(1)}`  
→ `navigationType=1` = 全屏导航 (full-screen). Default (file absent) = 0 →
Freedom returns immediately without creating the VirtualDisplay.

---

## Field diagnostic

1. **TEST 1** → ADB connection + `pm grant` `_COMMON` permissions
2. **TEST 2** → cluster restore (sendInfo 30→16→18→0)
3. **TEST 3** → cluster display size change (cmd 29/30/31)
4. **TEST 4** → BOOT_COMPLETED broadcast to Freedom BootReceiver (headless)

### Retrieve logs without USB cable

```bash
adb pull /sdcard/Android/data/com.byd.myapp/files/
```

### Azure KQL queries (workspace `law-byd-app`, francecentral)

```kql
BYDAppLog_CL | order by TimeGenerated desc | take 200
BYDAppLog_CL | where Level_s in ("WARN","ERROR") | order by TimeGenerated desc
BYDAppLog_CL | where Tag_s in ("ClusterMirrorManager","AdbLocalClient","ClusterManager")
```

---

## Version history

| Version | versionCode | Summary |
|---------|-------------|---------|
| **2.16** | 121 | Added Headless System Sniffer (logcat, am monitor, dumpsys via ADB) with Export button |
| **2.15** | 120 | Fix native SurfaceControl mirror restoration (uncomment disabled block + daemon delegation) |
| **2.14** | 119 | Freedom proxy injected: `app_process` pseudo-daemon to completely bypass Freedom APK + diagnostics IPC testing |
| **2.09** | 114 | Perf & Sanity checks: `AppLogger` O(1) ArrayDeque allocation, replaced `new Thread()` with `ExecutorService` in `AdbLocalClient`, cleaned up dead logic |
| **2.08** | 113 | Fix double `startFreedom()` race condition: `freedomJustStarted` flag propagated `ClusterService→DashboardDisplayHelper→ClusterManager`; `sendActivationSequence()` extracted as class-level method (was illegally nested in anonymous Callback); `DashboardDisplayHelper.start(boolean)` + `start()` no-arg overload |
| **2.07** | 112 | Sanity fixes: `AsyncTask`→`Executors`, adapter O(1) HashMap index, `ThreadLocal` `SimpleDateFormat`, `WeakReference` screenshot loop guard |
| **2.06** | 111 | Freedom state check at startup (`checkFreedomState` — NOT_INSTALLED / INACTIVE / ACTIVE), status bar feedback, `startFreedom(skipDisplayCheck)` to avoid redundant ADB round-trip |
| **2.05** | 110 | Mirror screencap fallback (`captureClusterDisplay` via ADB shell), remove `savedItem`/`PREF_LAST_APP`, persist `PREF_MAIN_PKG` across recreations, split bounds via `--ei` extras + `ActivityOptions.setLaunchBounds()` |
| **2.04** | 109 | Sanity check: dead code removal + `resolveLayerStack()` fix (`displayId<<16`) |
| **2.03** | 108 | `unlockHiddenApis()` VMRuntime + `createDisplay` fallback `secure=true` |
| **2.02** | 107 | Fix Freedom (activity name + fission check), split relaunch bounds, → Cluster btn, mirror placeholder |
| **2.01** | 106 | `startFreedom()`: write `navigationType=1` via ObjectOutputStream |
| **2.00** | 105 | Freedom headless (auto foreground return), ClusterService foreground refactor |
| 1.94 | 99 | Cluster split 50/50 (`launchTrampolineWithBounds`) |
| 1.91 | 96 | Real-time SurfaceControl mirror (replaces bitmap screenshot), Freedom reset 全屏 |
| 1.73 | 74 | Exported trampoline + ADB uid=2000 launch (bypasses INTERNAL_SYSTEM_WINDOW) |
| 1.46 | 47 | cmd30 before cmd16 sequence — fixes ADAS stretching |
| 1.34 | 35 | TEST 10 validated in vehicle ✅ |
