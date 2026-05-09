# Changelog — Internal Development History

This file documents all public and internal development iterations of DashCast.
See [README.md](README.md) for the project overview and installation instructions.

---

## Public releases

| Version | versionCode | Summary |
|---------|-------------|---------|
| **0.2.1-beta** | 52 | **Bugfixes & Optimizations** — Resources leak fix (`try-with-resources` on MirrorDaemon), `UnspecifiedImmutableFlag` intent fix on UpdateChecker, Manifest `android:exported=true` compliance, `StaticFieldLeak` suppression. Huge storage cleanup removing old 800MB orphaned `PackageInstaller` sessions. App strings purged. |
| **0.2.0-beta** | 51 | **OTA Update System** — Addition of the self-updating OTA mechanism (Silent update or Prompts). Transitioned project to Beta. |
| **0.1.49-alpha** | 49 | **Consolidation (Breaking Change)** — Package ID renamed. Heavy README update (badges, ota, compatibility). Diagnostics log crashes fixed. Refined Watchdog reliability. End of the Alpha cycle. |
| **0.1.39-alpha** | 39 | **Slavic/Central Asian i18n & Core Fixes** — Added ES, RU, UK, AR, BE, KK, UZ languages. Enhanced `restoreOriginCluster` safety with delays. Fixed PID resolution via `/proc` directly. |
| **0.1.31-alpha** | 32 | **DiLink5 resize test (Section 7)** — Isolated IATM test in DiagActivity: WM:4→5 + `resizeTask(RESIZE_MODE_FORCED=3)` via raw Binder on `activity_task`. Sanity: lint fixes (WrongConstant, DefaultLocale, MissingTranslation ×4 locales, RedundantLabel). |
| **0.1.30-alpha** | 31 | **Strings + UI cleanup** — Remove cluster keyboard button. Remove ADAS diagnostic buttons (cmd 12/13 confirmed without effect on 2D cluster Seal EU). String cleanup ×5 locales. |
| **0.1.29-alpha** | 30 | **Settings: overscan tuning** — New SettingsActivity screen: H/V inset tuning with immediate apply. Rename main screen buttons for clarity (Activate / Stop / Restore Original). |
| **0.1.28-alpha** | 29 | **ADAS diagnostic buttons** — Diag section 3: ADAS ON/OFF buttons (cmd 12/13) for 3D cluster testing. |
| **0.1.27-alpha** | 28 | **Bounds via `wm overscan`** — Replace dead IATM bounds approach with `wm overscan -d <displayId>`. Safety guard: restrict overscan to cluster display only + reset button for display 0. |
| **0.1.26-alpha** | 27 | **State persistence + storage cleanup** — Persist active cluster app to survive Activity recreation (SharedPreferences). Diag section 6: storage cleanup (logs, reports, sniffer files, log buffer). FREEFORM bounds: retry via IATM after launch. |
| **0.1.25-alpha** | 26 | **Logs menu + i18n refactor** — Overflow menu: new "📜 Logs" entry → LogActivity. Remove 69 dead strings. Migrate DiagActivity to fully localized resources. |
| **0.1.24-alpha** | 25 | **Sanity #1+#2** — Dead code and dead fields removed. `ClusterMirrorManager` blank line fix. DiagActivity: remove obsolete Freedom tests, unify comment numbering [1]-[5]. |
| **0.1.23-alpha** | 24 | **`moveTaskToDisplay` + kill detection** — Move tasks between displays without force-stop + relaunch. `OnUidImportanceListener` detects external app kill and clears stale cluster state. |
| **0.1.22-alpha** | 23 | **Mirror shortcut + overlay refactor** — Replace GPS floating button with 📺 mirror shortcut. New 📺 status bar button (visible when an app is on the cluster). Remove floating log overlay button. |
| **0.1.21-alpha** | 22 | **Security audit + Telegram share** — Full display-operation audit: guard `wm overscan`, `am display`, ADB commands to cluster display only. Long-press on any result TextView → share to Telegram. |
| **0.1.20-alpha** | 21 | **RE Sniffer fix + FREEFORM bounds** — RE Sniffer: `setsid` prefix, header sync, logcat without process filter. FREEFORM inset bounds: prevent app clipping at cluster screen edges. Fix: stop unintended `AppStartManagement` in fallback activation path. |
| **0.1.19-alpha** | 20 | **AutoDisplayService control + sanity** — Binder control of AutoDisplayService. Resource leak fixes. Sanity checks #5→#8 complete. README updated. |
| **0.1.18-alpha** | 19 | **DiagActivity refactor + DashCastDaemon** — Remove Freedom diagnostic tests. Add Headless System RE Sniffer (deep dumpsys + broadcast tracking). Add DashCastDaemon (`app_process` VirtualDisplay probe) and TEST 14-16. |
| **0.1.17-alpha** | 18 | **Complete Freedom removal + sanity** — Remove `checkAndStartWithFreedom()` and all `FreedomStatus` code from ClusterService/MainActivity. BYD native cluster spawn via `AppStartManagement`. Sanity #5-#7: empty catches logged, dead code, JMM fixes. |
| **0.1.16-alpha** | 17 | **Sanity + test build 04/05/2026** — Dead code removal, duplicate `setEnabled` fix, DiagActivity comment cleanup. Internal test build bump versionCode 17. |
| **0.1.15-alpha** | 16 | **Overlay cleanup + DashCastDaemon fix** — Remove floating log overlay button. Fix DashCastDaemon: bypass packageName UID mismatch preventing `app_process` daemon start. |
| **0.1.14-alpha** | 15 | **Display 0 security** — Full audit: all display-targeted operations (`wm overscan`, ADB shell commands) restricted to cluster display. Prevent accidental main screen modification. |
| **0.1.13-alpha** | 14 | **Mirror shortcut in status bar** — 📺 button appears in the cluster control panel when an app is active on the cluster. Long-press result TextViews → share to Telegram. |
| **0.1.12-alpha** | 13 | **FREEFORM inset bounds + RE Sniffer** — Inset margins applied via `setLaunchBounds` to prevent app clipping on cluster edges. RE Sniffer: `setsid`, header sync, raw logcat mode. |
| **0.1.11-alpha** | 12 | **AutoDisplayService + sanity #8** — Binder control of `AutoDisplayService` added. Resource leaks fixed. All sanity checks #1→#8 complete (versionCode bump). |
| **0.1.10-alpha** | 11 | **DiagActivity refactor** — Remove Freedom-dependent tests. Add Headless System Sniffer (logcat, `am monitor`, `dumpsys` via ADB). Sanity check #7: orphan imports, dead strings. |
| **0.1.9-alpha** | 10 | **Freedom removal** — Complete removal of Freedom (com.xdja.clusterdemo) from codebase. BYD native cluster spawn via `com.byd.appstartmanagement`. Sanity #6: JMM correctness, dead code cleanup. |
| **0.1.8-alpha** | 9 | **Sanity checks #1–#5** — Empty catches now logged (no more silent failures), dead methods removed, orphan imports cleaned. |
| **0.1.7-alpha** | 8 | **Freedom-free** — VirtualDisplay cluster creation confirmed via live logcat RE (sendInfo 30→16→35). Full autonomous activation sequence. Sanity fixes (duplicate setEnabled, color reset). README cleaned. |
| **0.1.6-alpha** | 7 | DiLink 5 Dashboard RE — deep dumpsys sniffer, broadcast tracking, DiagActivity refactor |
| **0.1.5-alpha** | — | Stability improvements — last release with Freedom dependency |
| **0.1.4-alpha** | — | Fix touch offset bug in cluster mirror input forwarding |
| **0.1.3-alpha** | — | Full i18n (FR/EN/DE/IT/TR), string externalization |
| **0.1.2-alpha** | — | EN translation, DashCast rename, new icon — device-validated |
| **0.1.1-alpha** | — | Bug fixes, code sanity, README improvements |
| **0.1.0-alpha** | — | First public release — cluster mirror working (image + touch) |

---

## Internal versions (pre-public)

| Version | versionCode | Summary |
|---------|-------------|---------|
| 2.52 | 155 | **Mirror fully working** — TextureView (SurfaceFlinger as producer), `hardwareAccelerated=true` in manifest |
| 2.51 | 154 | SurfaceView → TextureView migration (`new Surface(SurfaceTexture)`) |
| 2.17 | 122 | Bugfix: Attach click listeners to sniffer & daemon buttons in DiagActivity |
| 2.16 | 121 | Added Headless System Sniffer (logcat, am monitor, dumpsys via ADB) with Export button |
| 2.15 | 120 | Fix native SurfaceControl mirror restoration |
| 2.14 | 119 | Freedom proxy injected: `app_process` pseudo-daemon + diagnostics IPC testing |
| 2.09 | 114 | Perf & sanity: `AppLogger` O(1) ArrayDeque, `ExecutorService` in `AdbLocalClient` |
| 2.08 | 113 | Fix double `startFreedom()` race condition, `freedomJustStarted` flag |
| 2.07 | 112 | Sanity fixes: `AsyncTask`→`Executors`, adapter O(1) HashMap index |
| 2.06 | 111 | Freedom state check at startup (NOT_INSTALLED / INACTIVE / ACTIVE) |
| 2.05 | 110 | Mirror screencap fallback, persist `PREF_MAIN_PKG`, split bounds via extras |
| 2.04 | 109 | Dead code removal + `resolveLayerStack()` fix (`displayId<<16`) |
| 2.03 | 108 | `unlockHiddenApis()` VMRuntime + `createDisplay` fallback `secure=true` |
| 2.02 | 107 | Fix Freedom (activity name + fission check), split relaunch bounds |
| 2.01 | 106 | `startFreedom()`: write `navigationType=1` via ObjectOutputStream |
| 2.00 | 105 | Freedom headless (auto foreground return), ClusterService foreground refactor |
| 1.94 | 99  | Cluster split 50/50 (`launchTrampolineWithBounds`) |
| 1.91 | 96  | Real-time SurfaceControl mirror (replaces bitmap screenshot), Freedom reset 全屏 |
| 1.73 | 74  | Exported trampoline + ADB uid=2000 launch (bypasses INTERNAL_SYSTEM_WINDOW) |
| 1.46 | 47  | cmd30 before cmd16 sequence — fixes ADAS stretching |
