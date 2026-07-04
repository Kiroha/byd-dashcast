# DashCast — DiLink 5.1 / Cluster Projection — Full Handoff

> **Purpose.** Self-contained context dump so a **fresh, dedicated chat session** can resume the DiLink 5.x **instrument-cluster projection** work without the prior conversation. Covers the cluster architecture taxonomy, how DashCast projects, the service-name bug fixed in 1.6.100, the cross-user blocker, the reverse-engineering, the code map, and the ranked next steps. Cross-reference with the code (English) and the tester bug reports.
>
> **Author/date:** consolidated 2026-07-04, at app version **1.6.101-beta (build 542)**, branch `switch-kotlin`.
> **Repo:** `github.com/Kiroha/byd-dashcast` (package `com.byd.dashcast`).
> **Sibling doc:** `docs/HUD_DILINK3_HANDOFF.md` (the windshield-HUD / nav-content track — related but distinct).

---

## 0. TL;DR — where we are

- **GOAL:** project DashCast's content (its own mirror/dashboard, or a launched app such as a maps/nav app) onto the **instrument cluster** (the panel behind the wheel) on DiLink 5.x / recent DiLink 3 head units.
- **The master key = `ro.build.system.fission_single_os`** + AAOS detection. It decides whether projection is even *possible* (see §2). Get this right first for any car.
  - `fission_single_os=0` ("**1 for 2**" cluster) → a real Android VirtualDisplay exists (`fission_bg_…`) → **projection works** via the AutoContainer activation + SurfaceControl mirror / app launch.
  - `fission_single_os=1` (**single-OS fission**) → the cluster is rendered natively (Qt), **no Android cluster display** → **app-window projection is IMPOSSIBLE** (only CAN nav-data reaches it).
  - **DX_BYD_AUTO = full AAOS** (Bosch/ThunderSoft/Neusoft) → app-window projection **CLOSED** (SELinux + no Java HIDL stub), proven on-car.
- **★ Bug fixed in 1.6.100-beta:** on the **DiLink 50F_LC / 5.1** ("1for2") variant, DashCast asked the wrong service — it hardcoded `auto_container` (snake_case) for all DL5, but this variant registers it as **`AutoContainer` (PascalCase)** → activation returned "service does not exist" → projection never switched, even though the cluster display was present. Now `AdbLocalClient.autoContainerSvcName()` **probes** the registered casing. See §4.
- **Open blocker — cross-user:** launching a nav app on cluster display 2 fails because the app lacks `INTERACT_ACROSS_USERS` (not manifestable) / `INTERACT_ACROSS_USERS_FULL` (role-managed). **Fix = launch via the uid-2000 daemon** (shell holds the permission), not the app. See §6.
- **Current test car blocker:** on the DL5.1 unit that reported these, the **daemon itself is DOWN** (repeated `bootstrap timed out` — ADB-over-TCP not connecting), which blocks *everything* daemon-dependent (activation, bug-report shell dump, cross-user launch). Unblock ADB-TCP on that unit first.

---

## 1. Goal & scope

DashCast is a BYD cluster launcher / mirror / voice app. **Cluster projection** = making DashCast's own UI (a mirror of its screen, or a launched third-party app like Waze/Google Maps) appear on the **instrument cluster** display. This is distinct from the **windshield HUD** nav-content work (see the sibling HUD handoff).

Hard constraints on every change: **never break DL3 or DL5**, keep **lint 0 errors / 0 warnings**, ship as **beta pre-releases** (see §11). The user authorized full RE.

---

## 2. ★ Cluster architecture taxonomy — the master discriminator

**Always classify the car first.** Three mutually exclusive families, keyed off `ro.build.system.fission_single_os` + `FEATURE_AUTOMOTIVE`:

### (a) "1 for 2" fission — `fission_single_os=0` — PROJECTION WORKS
- A real Android **VirtualDisplay** is created for the cluster by `com.xdja.containerservice` (the "AutoContainer" backend).
  - DL3 1for2: display named **`fission_bg_xdjaVirtualSurface`** (Display 1).
  - DL5.1 (e.g. D50F_LC): display named **`fission_bg_XDJAScreenProjection`** (Display 2), owner `com.xdja.containerservice` (uid 1000), 1920×720, FLAG_PRESENTATION.
- The **`AutoContainerNative`** native service is registered in ServiceManager (the AutoContainer Java service checks `ServiceManager.checkService("AutoContainerNative")`; null ⇒ "no AutoContainerNative" ⇒ no cluster VirtualDisplay).
- DashCast **activates** the projection via the AutoContainer service (§3), then mirrors/launches onto the display.

### (b) Single-OS fission — `fission_single_os=1` — PROJECTION IMPOSSIBLE
- The cluster is rendered **natively (Qt) by `fission_service[ivi]`** + `com.xdja.clusterdemo` (the "Freedom" app, `Freedom v1.9.apk`, code in `com.byd.windowmanager.*`).
- **No Android cluster display exists** (Display 0 only). AutoContainer creates none; `AutoContainerNative` is legitimately absent.
- **App-window projection cannot work** here — same wall as AAOS. Only **CAN nav-data** (BYDAutoInstrumentDevice, the HUD/instrument path) reaches this cluster.
- Proven by a direct working-vs-failing comparison (2026-07-01): two DL3 cars with **identical firmware** differed only by `fission_single_os` (0 works, 1 fails). The earlier "13.1.32 vs 13.1.33 OTA" theory was **wrong — retracted.** This is NOT a DashCast bug and NOT fixable by reboot/OTA.
- **DL5.1 is also single-OS by nature** in the general case — BUT the D50F_LC "1for2" variant has `fission_single_os=0` and a real display (family a). So don't assume "DL5 ⇒ single-OS"; read the prop.

### (c) DX_BYD_AUTO = full Android Automotive OS (AAOS) — PROJECTION CLOSED
- `product=DX_BYD_AUTO`, Android 11/API 30, detected `effectiveDiLink5=true`. It's a **Bosch AAOS** head unit (`vendor.bosch.display`, `com.bosch.tee`) with a **ThunderSoft** app layer (`com.ts.appservice.*`) and **Neusoft** nav (`com.neusoft.na.navigation`, `/system/app/BydMapLauncher`).
- Cluster = Display 1 "HDMI Screen", EXTERNAL, FLAG_SECURE+PRESENTATION+TRUSTED, owned by the AAOS cluster-rendering pipeline (`InstrumentClusterService` / `ClusterRenderingService` driven by the `android.frameworks.automotive.display@1.0::IAutomotiveDisplayProxyService` HAL).
- **No `auto_container`, no `fission`, no `xdja`.** DashCast's whole DL3/DL5 activation is inapplicable.
- **PROVEN CLOSED on-car (1.6.74 HAL probe):** `IAutomotiveDisplayProxyService` Java HIDL class is **ABSENT** (ClassNotFoundException) in-app AND via daemon; `lshal` shows the native HAL registered but **SELinux denies `find` to the shell domain** (`avc: denied … fwk_automotive_display_hwservice … permissive=0`). Same for bosch.display, composer, etc. App-window projection is a **platform wall**, not a DashCast gap. **Closure accepted.** The SurfaceControl mirror *preview* still works, but the physical panel only presents the OEM Neusoft nav.
- There IS a theoretical NAV-DATA lever on AAOS (not app windows): `com.ts.appservice.cluster/.service.ClusterCoreService` (exported, permission `CAR_CLUSTER_COMMUNICATION` = `normal` = grantable), AIDL `IClusterCommService.sendCommand(int, Bundle)` + SOME/IP (`NAVI=4100`, `ROAD_INFO=1`, `REQUEST_NAVI_AREA_DISPLAY`). Unvalidated; low priority; decompiled at `/home/ccarre/app_byd/log/decompiled/tscluster`.
- **Recommended product behaviour:** detect AAOS (FEATURE_AUTOMOTIVE + no fission) and single-OS DL3 (`fission_single_os=1`) and show a clear "cluster projection unsupported on this variant" message instead of looping on activation. (Gate strings `R.string.aaos_cluster_unsupported_*`, `dl3_singleos_cluster_unsupported_*` exist; gating is wired in `MainActivity.onSendToDashboard` + `ClusterService`.)

**Detection helpers in code:** `Platform.isClusterSingleOs()` (reads `fission_single_os == "1"`), `Platform.isDiLink3/4/5(ctx)`, `Platform.isAaos`/`FEATURE_AUTOMOTIVE`. **Gate combos:** single-OS DL3 = `isDiLink3(ctx) && isClusterSingleOs()`; AAOS = automotive feature + no fission.

---

## 3. How DashCast projects on a "1for2" car (family a)

Pipeline (works on DL3 1for2 and DL5.1 D50F_LC):

1. **Detect the cluster display** — `ClusterManager.activateClusterDisplay()` / `DashboardDisplayHelper`: enumerate `DisplayManager.getDisplays()`, pick by name (`fission` / `xdjaVirtualSurface` / `XDJAScreenProjection` / `remote_dashboard`). If present already, fast-path.
2. **Activate the projection** via the AutoContainer service (§4). The `sendInfo(type, code, str)` control channel:
   - **DL3 sequence:** `sendInfo(1000, 30)` → wait ~3s → `sendInfo(1000, 16)` → ~3s → `sendInfo(1000, 35)` (prepare-surface / open-projection / size-handshake — the handshake triggers the VirtualDisplay creation).
   - **DL5 short-circuit:** the PRESENTATION display persists, so activation = a single **`sendInfo(1000, 16)`** on the AutoContainer service.
   - Command codes (from clusterdebug RE, `sendInfo(1000, code)`): **16 = cast host pixels fullscreen ON**, 17 = half, 18 = off; 0/1 = restore/disconnect cluster video; 29/30/31 = size 8.8/12.3/10.25; 39 = built-in simple-nav; 86/87 = HUD menu (Di6.0/R). ~80 codes total.
3. **Put content on the display:**
   - **Mirror** DashCast's own screen — `ClusterMirrorManager` (`SurfaceControl.createDisplay` + `Transaction.setDisplayLayerStack/Surface/Projection`, reflection; direct path needs ACCESS_SURFACE_FLINGER → falls back to the uid-2000 daemon `startMirrorViaDaemon`). Perf fix validated 1.6.44 (`stopMirrorViaDaemon` on stop, no residual SurfaceFlinger layer).
   - **Launch an app** on the display — `DashboardLauncher` (`ActivityOptions.setLaunchDisplayId` + `IActivityManager.startActivityAsUser`, reflection) or the daemon `Phase4TaskVerbs.launchAndForce` (`am start` + `moveTaskToDisplay`, uid 2000). ← this is where the **cross-user** issue bites (§6).
   - **Input** — `ClusterInputForwarder` injects touch/keys onto the cluster (`InputManager.injectInputEvent`, reflection; daemon path preferred).

**Two ways `sendInfo` reaches the service** (`AdbLocalClient.sendInfo`):
- **Typed daemon path** (non-DL5): `ProxyClient.autoContainerSendInfo` → daemon `binder.transact(2, …)` on the `android.os.IAutoContainer` binder (hardcodes name `AutoContainer`).
- **ADB shell relay** (DL5, or daemon unavailable): `service call <autoContainerSvcName> 2 i32 1000 i32 <code> s16 "<str>"` run as uid 2000. ← the casing bug (§4) lived here.

---

## 4. The AutoContainer service — the casing bug (fixed 1.6.100) + AutoContainerNative

- The projection service is `com.xdja.containerservice`, registered in ServiceManager under **one of two names depending on the firmware**:
  - **`auto_container` (snake_case)** — ONLY on literal `ro.product.name == "DiLink5.0"`.
  - **`AutoContainer` (PascalCase)** — everything else: DL3, DL4, **DiLink50F_LC / 5.1**, 6125f, etc.
  - (Some firmwares: `AutoContainerManager.init(ctx) + getAutoContainerManager()` instead of `getSystemService` — for `DiLink100f`/`Di300`/`Di150VCP`, keyed on `apps.setting.product.inswver`.)
  This mirrors the OEM's own rule (AmapService: `product.equals("DiLink5.0") ? "auto_container" : "AutoContainer"`; clusterdebug maps `Di3.0|6125f|DiLink50F_LC|Di4.0 → "AutoContainer"`).
- **The bug:** DashCast assumed "DL5 ⇒ snake_case", so on D50F_LC it called `service call auto_container …` → **"Service auto_container does not exist"** → activation failed silently, even though the display was there.
- **The fix (1.6.100-beta):** `AdbLocalClient.autoContainerSvcName(ctx)` now **probes** `ServiceManager.getService` for both casings (a non-null handle = trusted "registered"; a null = "not visible to this uid", NOT "absent"), caches the resolved name, and prefers the DL5/DL3 heuristic only as a tiebreak. Plus a self-correcting fallback: `ClusterManager` DL5 activation calls `noteAutoContainerMissing()` and retries once if `service call` still reports the name absent (covers a SELinux-blocked probe). Real DiLink5.0 (snake) is unaffected. `noteAutoContainerMissing` is public; `autoContainerSvcName` is now public.
- **`AutoContainerNative`** = a **separate native service** the Java AutoContainer checks via `ServiceManager.checkService("AutoContainerNative")`. If null → "no AutoContainerNative" → no cluster VirtualDisplay. It is present on `fission_single_os=0` cars and absent on `=1` cars (that's the single-OS wall, §2b — NOT a bug). On the D50F_LC bugreport, `AutoContainer` + `AutoContainerNative` + `FissionHostSvc` were all registered (so the native backend is fine — the only problem was the wrong name).

---

## 5. RE channels & the pixel path (bydhud)

Full RE (decompiled sources at `/home/ccarre/app_byd/re_hud/src/`: `com.example.amapservice`, `com.byd.clusterdebug`, `com.example.bydhud`; APKs in `re_hud/apks/`; jadx at `/home/ccarre/app_byd/jadx`). Four channels put content on the cluster — for **projection** the relevant ones are **B (control)** and **D (pixels)**:

- **Channel B — `sendInfo(1000, code)`** = the control surface DashCast already uses to activate (§3). clusterdebug is a BYD engineering app exercising the full code table via `AutoContainerManager.sendInfo(1000, code, "")` (wrapped in `catch(SecurityException)` — privileged; but DashCast's uid-2000 daemon already calls `sendInfo` successfully for activation).
- **Channel D — PIXELS = `com.example.bydhud`'s whole approach** (a DashCast-like sibling that projects its own dashboard onto the cluster). It uses **the exact uid-2000 primitives DashCast has**:
  - self-ADB `AdbPuppet` → `127.0.0.1:5555` (uid 2000 shell);
  - OEM helper jars run via `app_process`: `CLASSPATH=/data/local/tmp/switch_dashboard.jar app_process / SwitchDashboard 16 35 ""` (fullscreen unlock; 17 35 small; 1000 18 reset) and `CLASSPATH=/data/local/tmp/fission_unlock.jar app_process / FissionUnlock 1 1920x720` (success token "SUCCESS! Fission display unlocked");
  - then `android.app.Presentation` on the cluster Display (found by name), OR `DisplayManager.createVirtualDisplay("cluster_app_host", …, flags=10)` + `am start --display <id>`, OR **MediaProjection AUTO_MIRROR** of display 0, OR a `CommunicationProcessKt` (`one_screen_helper.dex`) Binder daemon (transact 22=createDisplay, 2=mirror, 13=moveTaskToDisplay, 3/4=inject motion/key, 7=init).
  - **Reusable by DashCast:** the whole approach maps to what the DashCast daemon can do. NOTE the OEM helper jars (`switch_dashboard.jar`, `fission_unlock.jar`, `one_screen_helper.dex`) are proprietary and NOT DashCast's code — Channel D as-is depends on obtaining them + self-ADB working.
- Channels A (`sendInfo2(4, NaviInfo flatbuffer)`) and C (BYDAutoInstrumentDevice CAN) are nav-CONTENT, covered in the HUD handoff.

---

## 6. Cross-user permission — the app-launch blocker (OPEN)

A tester found (via ADB) that on **DiLink 5.1 / Android 13**, launching a nav app on cluster **display 2** fails:
- `INTERACT_ACROSS_USERS` — DashCast doesn't request it in the manifest (and it isn't grantable to an untrusted app anyway).
- `INTERACT_ACROSS_USERS_FULL` — **role-managed**, cannot be held by a third-party app.

The cluster display is owned by another uid/user (`com.xdja.containerservice` uid 1000), so `ActivityOptions.setLaunchDisplayId(2)` from the **app** process hits the cross-user check.

**Correct fix (NOT a manifest change):** launch via the **uid-2000 daemon**, which (as the shell domain) **holds the cross-user permission** — exactly what bydhud does (`am start --display <id>` via `app_process`). DashCast already has the primitive: `Phase4TaskVerbs.launchAndForce` (daemon `am start` + `moveTaskToDisplay`). So the "launch nav on cluster" feature must route through the daemon, not the app-side `setLaunchDisplayId`.

**Caveat:** on the reporting car the **daemon is DOWN** (ADB-TCP), so even the daemon route can't run yet. Unblock ADB-over-TCP first.

---

## 7. Case study — the DL5.1 D50F_LC bugreport

`byd_bugreport_20260702_190502` + `byd_log_20260702_190721` (in `/home/ccarre/app_byd/log/`):
- Device: `model=D50F_LC for BYD AUTO`, `product=trinket`, API 33, `inswver=DiLink50F_LC-1for2_USER_SIGN_S2285_202512102113_Q2700`, `fission_single_os=0` (family a).
- App log: `Platform … autoDiLink5=true effectiveDiLink5=true`; `ClusterMirrorManager unlockHiddenApis OK`; `ClusterManager DL5 activation path: sendInfo(16) only on auto_container`; **`sendInfo ADB(1000,16) → service: Service auto_container does not exist`** ← the casing bug; yet `ClusterManager PRESENTATION candidate: id=2 name=fission_bg_XDJAScreenProjection` + `Cluster display connected: id=2` + `Cluster dimensions: 1920x720 displayId=2` → **the display is present and registered**.
- Bugreport: service list has `1 AutoContainer: [android.os.IAutoContainer]`, `2 AutoContainerNative: []`, `6 FissionHostSvc`. Display dump: `fission_bg_XDJAScreenProjection` id=2, VIRTUAL, owner `com.xdja.containerservice`, FLAG_PRESENTATION + FLAG_OWN_CONTENT_ONLY.
- **Diagnosis:** everything is in place — DashCast just called the wrong service name. → the 1.6.100 fix. Also visible: the **daemon bootstrap loops** (`ERR bootstrap timed out`, `no live binder`, `Activate cluster timeout`) = ADB-TCP down on this unit (separate blocker).
- Also on this car: the DL5.1/Android 13 **diagnostic crash + Bug Report regression** — `getExternalFilesDir("vosk")` throws `SecurityException: callingPackage does not match UID` (its mkdirs/AppOps check) → crashed DiagActivity via the voice panel; `BugReportCapture.newFile` aborted. **Fixed in 1.6.101** (safe accessor + `bindVoicePanel` try/catch + canonical external-path fallback). See the HUD handoff §11 for detail.

---

## 8. Code map (cluster-projection relevant)

- `cluster/ClusterService.java` (~1066 l, **still Java**, foreground service, the core orchestrator) — starts projection, holds the display, gates AAOS / single-OS.
- `cluster/display/ClusterManager.kt` — activation engine (`sendInfo 30/16/35`, DL5 single `sendInfo(16)`, fast/warm/slow path, DL5 AutoContainer self-correcting retry from §4). Constants `CLUSTER_TYPE`/`CMD_*`/`SERVICE_NAME` in the companion.
- `cluster/display/DashboardDisplayHelper.kt` — cluster display detection by name.
- `cluster/display/DashboardLauncher.kt` — launch an app on a display (reflection: setLaunchDisplayId + startActivityAsUser).
- `cluster/mirror/ClusterMirrorManager.kt` — SurfaceControl mirror (+ daemon fallback). Perf fix 1.6.44.
- `cluster/mirror/ClusterInputForwarder.kt` — touch/key injection.
- `cluster/mirror/MirrorTestRunner.java` (still Java).
- `cluster/ClusterSessionTracker.kt` — launched-app set + eviction (restore cluster).
- `cluster/dpi/*` (Kotlin) — per-app DPI on the cluster (`wm density … -d <id>`).
- `infrastructure/AdbLocalClient.java` (~921 l, **still Java**) — local-ADB socket; `sendInfo` (typed daemon + shell relay); **`autoContainerSvcName()`** (the casing probe, §4). I just modified this in 1.6.100.
- `proxy/daemon/Phase4TaskVerbs.java` — `launchAndForce` (daemon uid-2000 `am start` + `moveTaskToDisplay`) — the cross-user-capable launch path (§6). Also `moveAndResize`, `cleanFissionStacks`.
- `proxy/daemon/ProxyDaemonMain.java` — the daemon (`dashcast_proxy`, uid 2000, `app_process64`, PROTOCOL_VERSION "17"). Respawns on versionCode bump.
- `proxy/ProxyClient.java` — client verbs (`autoContainerSendInfo`, `launchAndForce`, `createVirtualDisplay`, `moveAndResize`, `findTaskIdForPackage`, `removeTask`, …).
- `platform/Platform.java` — `isDiLink3/4/5`, `isClusterSingleOs()`, AAOS detection, `readProp()`.
- `MainActivity.kt` — `onSendToDashboard` (the "send to cluster" entry) + AAOS / single-OS gate dialogs.

---

## 9. Version history (cluster-projection relevant)

- **≤1.2.x–1.4.x** — cluster activation (`sendInfo 30/16/35`), SurfaceControl mirror, VirtualDisplay POC, fission stack cleanup, resize editor.
- **1.6.44** — mirror perf fix (`stopMirrorViaDaemon`, no residual layer). Validated.
- **1.6.46–1.6.48** — AAOS (DX_BYD_AUTO) investigation → proven app-window projection impossible; AAOS gate message.
- **1.6.74** — `AaosDisplayHalProbe` (TXN_AAOS_HAL_PROBE) → definitively CLOSED AAOS projection (SELinux + no Java stub).
- **1.6.79** — single-OS DL3 (`fission_single_os=1`) proven = app projection impossible (same wall); retracted the "13.1.32/33 OTA" theory; single-OS gate message wired.
- **1.6.100** — **AutoContainer service-name casing fix** (D50F_LC / 5.1 uses PascalCase). §4.
- **1.6.101** — DL5.1/Android 13 diagnostic crash + Bug Report fix (§7).

---

## 10. Diagnostics workflow & data locations

- **Projection bug reports arrive on Telegram TOPIC 4** (HUD zips are topic **2701** — different topic). Fetched via the Telethon session in `/home/ccarre/app_byd/hud_reports` (there are `fetch_inc*.py` scripts for INC reports).
- Bug reports are named `byd_bugreport_*` (full: `getExternalFilesDir` + shell dump via daemon/ADB — see `report/BugReportCapture.java`) and `byd_report_*` (app-log export). The tester triggers them from `BugReportActivity` / `BugWizardActivity`.
- **DL5.1 sample logs:** `/home/ccarre/app_byd/log/byd_bugreport_20260702_190502*.txt` + `byd_log_20260702_190721*.log` (the D50F_LC case, §7).
- **Decompiled OEM apps:** `re_hud/src/` (amapservice/clusterdebug/bydhud); `decompiled/containerservice` (AutoContainerService.java — the `AutoContainerNative` check); `decompiled/freedom` + `freedom_debug` (com.xdja.clusterdemo / Byd Dashboard / WindowManagement, single-OS renderer); `log/decompiled/tscluster` (AAOS ClusterCoreService).
- **Build:** Gradle JDK `/home/ccarre/.jdks/jdk-17.0.19+10`; `./gradlew :app:assembleRelease :app:lintRelease`; BYD platform signing (`bydPlatform`).
- **Persistent memory (this session's notes):** `/home/ccarre/.claude/projects/-home-ccarre-app-byd-MyBYDApp/memory/` — `dl3-firmware-autocontainer.md` (the fission_single_os discriminator + AutoContainerNative), `dx-byd-auto-cluster-topology.md` (AAOS closed), `project_architecture.md`, `release-and-build-conventions.md`.
- **Codebase knowledge graph:** `graphify-out/` — query with `graphify query "<question>"` for cluster code structure.

---

## 11. Conventions (must-follow)

- **Release flow (verbatim):** *"commit, push puis publie la 1.6.X-beta en pre-release. Ne merge pas en main et ne tag pas cette version en latest. Joins l'APK et le changelog en Anglais."* → `gh release create v1.6.X-beta <apk> --target switch-kotlin --title "…" --notes "<English>" --prerelease --latest=false`.
- **Never break DL3/DL5;** **lint 0/0** (`grep -c 'severity="Warning"' app/build/reports/lint-results-release.xml` = 0). Gate projection by cluster family (§2) with a clear message rather than silent failure.
- **versionCode bump respawns the daemon** (needed for new TXN verbs). On cars with broken ADB-TCP the respawn fails — that is itself a symptom to surface, not a regression.
- **Note (migration):** the branch `switch-kotlin` is mid Java→Kotlin migration. `ClusterService.java` and `AdbLocalClient.java` are still Java (planned lots 5e-3 and 5f); the rest of `cluster/` is Kotlin. When editing, match the file's language.

---

## 12. Status board & ranked next steps

**PROVEN / DONE:**
- Cluster family taxonomy (§2): 1for2 works, single-OS impossible, AAOS closed — all proven on-car.
- AutoContainer casing fix (1.6.100) for D50F_LC / 5.1 (§4) — **awaiting on-car retest**.
- DL5.1/A13 crash + Bug Report fix (1.6.101).
- Mirror + activation pipeline works on 1for2 (long-standing).

**OPEN / NEXT (ranked):**
1. **Retest the 1.6.100 AutoContainer fix on a D50F_LC / 5.1 car** — does the cluster now actually switch to DashCast's projection? (Needs the daemon UP → ADB-TCP working on that unit.) **Highest priority — closes the loop on the shipped fix.**
2. **Unblock the daemon on the DL5.1 test car** — the repeated `bootstrap timed out` means ADB-over-TCP isn't reachable at `127.0.0.1:5555`. Everything (activation, bug reports, cross-user launch) depends on it. Investigate why (ADB-TCP disabled? port? pairing? self-key not authorized?).
3. **Route the cluster app-launch through the uid-2000 daemon** (`Phase4TaskVerbs.launchAndForce` / `am start --display <id>`) so the cross-user permission is satisfied (§6) — for the "launch a nav app on the cluster" feature.
4. **Gate messaging polish:** ensure single-OS DL3 (`fission_single_os=1`) and AAOS both show the "unsupported on this variant" dialog cleanly instead of looping on activation.
5. (Optional/low-odds) Channel D pixel path (bydhud-style Presentation/VirtualDisplay) — only if the OEM helper jars can be obtained and self-ADB works.

**Open questions:**
- After the casing fix, does `service call AutoContainer 2 i32 1000 i32 16 s16 ""` actually make the D50F_LC cluster show DashCast? (bench needs the daemon up.)
- Is the DL5.1 daemon-down purely an ADB-TCP config issue on that unit, or a broader DL5.1 bootstrap problem?
- For launching apps on the cluster: does routing through the daemon's `am start --display 2` bypass the cross-user check in practice on DL5.1/A13?
- Do any other DL5.x variants use `AutoContainerManager.init()+getAutoContainerManager()` (the third acquisition style) rather than a named service? (Handle if a report shows it.)
