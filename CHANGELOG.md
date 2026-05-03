# Changelog — Internal Development History

This file documents the internal development iterations prior to the public `0.1.0-alpha` release.
Public releases are tracked in [README.md](README.md#version-history).

---

## Public releases

| Version | versionCode | Summary |
|---------|-------------|---------|
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
