# DashCast — DiLink 3 HUD & Cluster Projection — Full Handoff

> **Purpose.** This is a self-contained context dump so a **fresh chat session** can resume the DiLink 3 (DL3) windshield-HUD / cluster-projection investigation without the prior conversation. It consolidates the persistent memory, the on-car findings, the reverse-engineering, the shipped tooling, the code map, and the ranked next steps. Cross-reference it with the code (English) and the tester zips.
>
> **Author/date:** consolidated 2026-07-04, at app version **1.6.101-beta (build 542)**, branch `switch-kotlin`.
> **Repo:** `github.com/Kiroha/byd-dashcast` (app package `com.byd.dashcast`).

---

## 0. TL;DR — where we are right now

- **GOAL:** show turn-by-turn navigation on the **DiLink 3 windshield HUD** (and, adjacently, on the instrument **cluster**).
- **HUD architecture (proven):** the DL3 windshield HUD is an **MCU/CAN-driven hardware display** (icon library + text fields selected by CAN frames on the MCU side), **not** an Android rendered surface.
- **HUD control (proven on-car):** we can fully drive the HUD *container* — on/off, brightness, height, angle, ADAS overlay — via `BYDAutoSettingDevice` feature ids (ECU `0x4C1`/sub `0xE`). See §3.
- **★ BREAKTHROUGH (2026-07-04):** on **arrow-capable firmware**, writing our own CAN **guidance** (`BYDAutoInstrumentDevice` INSTRUMENT_GUIDE_INFO_SIMPLE + distance + road + status) made a **turn arrow appear on the windshield HUD** (1 clear YES). So DashCast **can** drive the HUD arrows itself via CAN — regardless of the OEM nav app. **Not yet solid: 1 YES vs 1 NO/PARTIAL on the same firmware → procedure-dependent; needs 2-3 photographed confirmations.** See §5.
- **Firmware split (proven):** two DL3 HUD MCU firmwares — an **older one that cannot draw arrows** and a **newer one that can**. Discriminator = system property `apps.setting.product.inswver` (free to read). See §4.
- **Immediate next step chosen by the user: option (A)** = solidify the CAN→HUD bench with **photos** + OEM-nav-fully-off + noting the HUD display mode. A tester how-to was written (English). Then (B) test the real feature: run Google Maps nav through DashCast (existing `MapNotificationListenerService` already writes CAN guidance) and see if the HUD shows real turn-by-turn.
- **Separate but related (DL5.1 cluster projection):** fixed the AutoContainer service-name casing bug (`AutoContainer` vs `auto_container`) in 1.6.100; the cross-user (`INTERACT_ACROSS_USERS`) blocker for launching apps on display 2 must be solved via the uid-2000 daemon, not the app. See §9.

---

## 1. The goal & scope

The user (Cédric) runs **DashCast**, a BYD cluster launcher / mirror / voice app, on BYD DiLink head units. He has multiple **testers** with different cars/firmwares who upload diagnostic zips to a Telegram channel.

Two related targets:
1. **Windshield HUD (W-HUD)** turn-by-turn — the primary current goal.
2. **Instrument cluster** nav/projection — adjacent; largely solved for the nav-strip on AMap cars (see §6), and a separate DL5.1 projection fix in §9.

The user authorized **full RE** (APK extraction, decompilation, unfiltered logging): *"on ne s'interdit rien."* Hard constraint on every change: **never break DL3 or DL5**, keep **lint 0 errors / 0 warnings**, ship as **beta pre-releases**.

---

## 2. Architecture verdict — the HUD is MCU/CAN-driven, NOT a rendered surface

Established from a 6.4 MB unfiltered driving logcat (`hud_rawcap_20260703_202054`) + prior work:

- Only `displayId=0` appears in the whole log — **no second display / no HUD surface**. A rendered HUD would show SurfaceFlinger + a display.
- The native MCU logger (`mcu_log_collection`, pid 162; `mcu : [mculog]…`) emits **0 lines during driving** — it only runs when the OEM `com.byd.carsettings` diagnostic app is active (that is why the "gold" `log.docx` was full of `[mculog]` but a driving capture is not).
- HUD config = CAN settings on ECU `0x4C1` (proven, §3).
- Both the raw logcat **and** our unfiltered CAN listener (older builds) **never surfaced an arrow feature** during driving — only telemetry + HUD-mode.

**Conclusion:** the HUD is a hardware display driven by the MCU from CAN frames. The arrow "codes" live on the MCU/CAN bus; they are **not visible to app-level logcat during normal driving** and are **not observable as a BYDAuto feature event**. Decoding them passively would need CAN-bus sniffing (root/hardware; no `can0` interface visible to uid 2000). **BUT** — see §5 — we can *write* the CAN guidance registers ourselves and the MCU *does* consume them on recent firmware.

---

## 3. PROVEN HUD-control ground truth (feature ids)

Source: **`log.docx`** (at `\\wsl.localhost\Ubuntu-24.04\home\ccarre\app_byd\log\log.docx`) — a tester ran an **unfiltered logcat** while operating each HUD control inside the OEM `com.byd.carsettings` app. Its `HalSetter` logs the exact write per action:
`CarSettings-HalSetter: sendEcu2BYDAuto IBYDAutoDevice : BYDAutoSettingDevice FeatureId : 4C10E0xx … intValue : N` and `AbsBYDAutoDevice: set featureID is 4c10e0xx …`. ECU `Id=0x4C1 SubId=0xE`.

Action → feature id → value (order matched the tester's action sequence exactly):

| HUD control | Feature id | Value semantics | Type |
|---|---|---|---|
| **HUD on/off** | `SET_HUD_SWITCH` = **0x4C10E023** | **1 = ON, 2 = OFF** (not 0/1!) | int |
| **ADAS / option-display overlay** | `SET_HUD_OPTION_DISPLAY` = **0x4C10E030** | 1 = on | int |
| **Brightness** | `SET_HUD_BRIGHTNESS` = **0x4C10E018** | level (saw 11) | int |
| **Height (vertical pos)** | `SET_HUD_HEIGHT` = **0x4C10E010** | level (saw 11) | int |
| **Angle / tilt** | `SET_HUD_ANGLE` = **0x4C10E02C** | degrees, each detent ≈ 0.4° | **double** |
| HUD display mode | `SET_HUD_MODE` = 0x4C10E025 | speed / speed+nav / map… (a *user* setting) | int |
| HUD state request | `SETTING_HUD_REQUEST_COMMAND` = 0x32B0A044 | (a request, not a set) | int |

Feedback (push) ids: `SET_HUD_MODE_FEEDBACK` = 0x38B0000D, `SET_HUD_SWITCH_STATUS_FEEDBACK` = 0x38B0001C (2=off/1=on). Feedback is pushed on the **`BYDAutoLightDevice`** (systemui `Con--HUDItem: AbsBYDAutoLightListener`) — a third listener channel.

**On-car confirmation:** ~10 `hud_confirm_*` zips across DL3 testers (1.6.98). HUD on/off + ADAS + brightness + height = **solid YES** everywhere. Angle = flaky (YES ~5, NO 2, SKIP others) — a perceptual/test-design issue (the ramp ends back at 0° so the net change is invisible), **not** a real failure (rc=0 always; the `double` write path works on-car).

**★ These ids are DL3-SPECIFIC.** On a **DiLink 5.1** (`hud_confirm_20260704_000325`, inswver `Di5.1_…`) the same writes gave rc=0 but the tester answered HUD_ON=NO / HEIGHT=NO / OFF=NO — DL5.1 uses a **different** HUD scheme. Any shipped HUD-control feature MUST be gated to DL3 (`Platform.isDiLink3(ctx) && !AAOS`).

In code: all ids are in `CanWriteVerbs.java` (`SET_HUD_SWITCH`, `SET_HUD_OPTION_DISPLAY`, `SET_HUD_BRIGHTNESS`, `SET_HUD_HEIGHT`, `SET_HUD_ANGLE`, `HUD_SWITCH_ON=1`, `HUD_SWITCH_OFF=2`). The angle uses a daemon `settingSetDouble` (TXN_CAN_SETTING_DOUBLE) that reflects the `doubleValue`/`floatValue` field on `BYDAutoEventValue`.

---

## 4. Two HUD MCU firmwares — the `inswver` discriminator

User tip (2026-07-04, corroborated): **there is an older DL3 HUD MCU firmware that does NOT draw nav arrows, and a newer one that DOES.** This fits the MCU-driven verdict (arrows are a firmware capability).

**Discriminator = system property `apps.setting.product.inswver`** — readable by any app with zero permission (`SystemProperties.get` / `getprop`). Exposed in code as `Platform.hudFirmwareVersion()`; the HUD page shows a parsed `SX<NNN> (date)` label and **every zip carries it** (`02_props.txt`).

DL3 format: `6125f_1for2_USER_SIGN_SX<NNN>_<YYYYMMDDHHMM>_Q2700`. The `SX<NNN>` revision + build date discriminate the firmware.

Firmwares seen across testers:

| SX code | Build date | Arrow signal |
|---|---|---|
| SX245 | 2025-03-25 | oldest — barely tapped (unclear) |
| SX309 | 2025-11-08 | tester used "changed-other (?)" (ambiguous) |
| **SX326** | **2026-02-03** | **clear arrows; CAN→HUD bench got a YES here** |
| SX365 | 2026-06-07 | newest (only a confirm run so far) |

(DL5.1 car uses `Di5.1_…_S9221_…`, a different platform entirely.)

**Actionable:** DashCast can read `inswver` to predict/gate arrow capability; stop chasing arrows on old firmware; run the CAN→HUD bench only on arrow-capable firmware (SX326+). The exact SX/date threshold where arrows appear is **not yet pinned** — needs tester confirmation.

---

## 5. ★ THE BREAKTHROUGH — CAN→HUD arrow injection works (Channel C)

Shipped in **1.6.99-beta** as **HudDiagActivity TOOL 3 "CAN → HUD bench"**: with OEM nav OFF, DashCast writes nav guidance itself via `CanBusController`:
`setSettingFeature(SET_HUD_SWITCH, 1)` → `setNaviActive(true)` → `sendSimpleGuidance(icon, dist)` + `sendNextStreetName("TEST …")` + `sendRestRoute(0,5,1200)`, sweeping **STRAIGHT (icon 11) → LEFT (1) → RIGHT (2)**, ~6 s each, then asks the tester **YES/NO/Partial** (did the windshield HUD show an arrow?) → zip `hud_canbench_*` to Telegram topic 2701.

**Result (2026-07-04, 2 zips, both on SX326 arrow-capable firmware):**
- `hud_canbench_20260704_124211` → **"YES — arrow on HUD"** ✅
- `hud_canbench_20260704_084429` (earlier) → "NO/PARTIAL" ❌
- Both: all writes rc=0, OEM nav off.

**Interpretation:** on arrow-capable DL3 firmware the windshield HUD **does consume the CAN instrument guidance registers** (`INSTRUMENT_GUIDE_INFO_SIMPLE_SET` etc.). So **DashCast can drive the HUD arrows via Channel C**, independent of which nav app is the OEM source (even on Telenav cars — the MCU reads CAN). This **flips** the earlier "no injectable HUD path" verdict *for arrow-capable firmware*.

**Caveat (do not take the YES for granted):** 1 clear YES vs 1 NO/PARTIAL on the *same* firmware ⇒ procedure-dependent. Likely reasons the first failed: OEM nav not fully closed (it overwrites our guidance), or the HUD display mode was "speed only" (not a nav mode), or the tester looked at the wrong moment. **Need 2-3 photographed YES to solidify.**

**Huge implication:** DashCast's **existing production path** — `HudController` + `MapNotificationListenerService` — already parses Google Maps/Waze notifications and writes these **same** CAN guidance frames (today for the cluster). So **running a real nav through DashCast on arrow-capable firmware probably already lights the HUD** with turn-by-turn. That is the real feature to verify next.

The CAN guidance registers (in `CanWriteVerbs.java`, decimal / hex):
- `INSTRUMENT_SEND_NAVI_STATUS` = 0x43E0003A (values `NAVI_STATUS_ACTIVE=2`, `NAVI_STATUS_STOPPED=4`)
- `INSTRUMENT_GUIDE_SIMPLE` = 0x43F01010, `INSTRUMENT_GUIDE_ROAD_DISTANCE` = 0x43F01030, `INSTRUMENT_FRONT_CROSSING_DIST` = 0x43F01018
- `INSTRUMENT_NEXT_PATHNAME` = 0x43FA1008 (UTF-8/UTF-16 bytes)
- `INSTRUMENT_NAVI_MILEAGE` = 0x43F02028, `_NAVI_HOUR` = 0x43F02010, `_NAVI_MINUTE` = 0x43F02018, `_NAVI_REMAINING_SEC` = 0x43F0201E
- `INSTRUMENT_NAVI_LEAD_MSG` = 0x43F08010, `INSTRUMENT_DISTANCE_TARGET_AHEAD` = 0x43F08018
- `SETTING_NAVI_SCREEN_STATUS` = 0x4C10E015 (BYDAutoSettingDevice; set to 3 on nav start)

Turn icon ids (CanBusController `ICON_*`, 49 values): LEFT=1, RIGHT=2, SLIGHT_LEFT=3/4, SLIGHT_RIGHT=5/6, SHARP_LEFT=7, SHARP_RIGHT=8, U_TURN_LEFT=9, U_TURN_RIGHT=10, STRAIGHT_SOLID=11, STRAIGHT_DOTTED=12, roundabouts 15-44, DESTINATION=48, TUNNEL=49… These match the AmapService `TurnIdMapToCAN[]` output (§6).

---

## 6. Cluster projection — the 4 channels + RE of 3 OEM APKs

RE done via a multi-agent workflow. **Decompiled sources persisted at `/home/ccarre/app_byd/re_hud/src/`** (`com.example.amapservice`, `com.byd.clusterdebug`, `com.example.bydhud`); APKs in `re_hud/apks/` (pulled from old `hud_diag_*` zips); jadx at `/home/ccarre/app_byd/jadx`.

**FOUR distinct channels put content on the DiLink CLUSTER** (the HUD is separate — but the HUD MCU consumes Channel C, per §5):

- **Channel A — nav CONTENT flatbuffer (DL3 "1for2" only):** `AutoContainerManager.sendInfo2(4, <byd.fbs.naviInfo FlatBuffer>)` — the only nav-content push to the 1for2 cluster, in `AmapService.sendNaviInfoTo1for2Clster()`. Gated `ro.build.system.fission_single_os != "1"`. The 18-field FlatBuffer (`byd.fbs.naviInfo.NaviInfo`: naviState, nextRouteName, curToSegmentDist, forwardState, nextTurnIcon, routeRemainTime/Dist, eta, exit name/dir, …). Turn icon mapped from the AMap broadcast `NEW_ICON` via `TurnIdMapToCAN[]` = `{0,0,1,2,3,5,7,8,9,11,45,13,24,46,47,48,49,14,23,10,12,15,18,20,22,16,17,19,21}` (index = AMap icon 0..28, value = CAN icon id; e.g. left=1, right=2, straight=11, arrive/destination=48).
- **Channel B — CONTROL `sendInfo(1000, code, "")`** (clusterdebug's ~80-code table): **16 = cast host pixels fullscreen ON**, 17 = half, 18 = off; 0/1 = restore/disconnect cluster video; **39 = 简易导航 (built-in simple-nav trigger)**; 29/30/31 = size 8.8"/12.3"/10.25"; 86/87 = HUD menu toggle (Di6.0/R). Manager acquired keyed on `inswver`. DashCast's proven cluster **activation** (`sendInfo 30/16/35`) hits this same surface.
- **Channel C — CAN** `BYDAutoInstrumentDevice.set(INSTRUMENT_GUIDE_INFO_SIMPLE_SET / _FRONT_CROSSING_DISTANCE_SET / _TARGET_NEXT_PATHNAME_INFO_SET / _NAVI_TRIP_INFO_* / _SEND_NAVI_STATUS_SET)` — nav content over CAN, in `AmapService.sendNavigateInfoToCAN()`. Only nav path on DL5.1; runs **also** on DL3 alongside A. **This is the channel the HUD MCU consumes (§5).** clusterdebug raw escape hatch: `BYDAutoTestDevice.set(0xAA00020F, bytes)` via broadcast `com.byd.cluster.spi` (needs a test permission).
- **Channel D — PIXELS (bydhud's approach):** `com.example.bydhud` is a **DashCast-like sibling** that projects its own dashboard onto the CLUSTER with the same uid-2000 primitives DashCast has: self-ADB (`AdbPuppet`→127.0.0.1:5555), OEM helper jars `SwitchDashboard 16 35` / `FissionUnlock 1 1920x720` via `app_process`, then `android.app.Presentation` on the cluster Display (found by name `fission`/`xdjaVirtualSurface`/`remote_dashboard`), or `VirtualDisplay "cluster_app_host"` + `am start --display`, or MediaProjection AUTO_MIRROR of display 0, or a `CommunicationProcessKt` (one_screen_helper.dex) Binder daemon (transact 22=createDisplay, 2=mirror, 13=moveTaskToDisplay, 3/4=inject). **No AutoContainer/sendInfo2/CAN nav in bydhud.**

**AmapService full flow (the OEM nav→cluster reference):** listens for broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` (extras `IS_BYD_MAP`, `KEY_TYPE` = 10001 guide / 10019 stop, **`TYPE` = 0 or 1** = naviState (NOT 8!), `NEW_ICON`, `SEG_REMAIN_DIS`, `NEXT_ROAD_NAME`, `ROUTE_REMAIN_DIS/TIME`, `ROUNG_ABOUT_NUM`, `ETA_TEXT`). It also has a **`DEBUG_CASE` broadcast test hook** that animates icons 2→28 on the cluster (great for a "which icon renders what" demo). Note the OEM path is AMap/BYD-map only.

**AmapService cluster-nav was VISUALLY CONFIRMED on the DL3 CLUSTER (2026-06-26, photo "↰ 300 m TEST" in the nav strip)** via emitting `AUTONAVI_STANDARD_BROADCAST_SEND` with `TYPE=1` (this was the old `HudAutoNaviBroadcast`, since deleted — see §8/§11; it's in git history). Sustained frames required; the 1for2 cluster turn-icon scheme differs from AMap's.

---

## 7. Telenav vs AMap — why the arrows were invisible

The **EU cars are Telenav**, not AMap. On a Telenav DL3 (`com.telenav.tg2` / `com.telenav.app.external`, middleware v2.1.1), guidance is delivered as **binary `NaviInfo` AIDL objects** (`onNaviInfoReceived` / `onTrafficIndicatorInfoUpdated`) to Telenav's own widget `com.telenav.tg2.widget.a`. The maneuver is a field **inside** that Parcelable — not logged as text, not written to a greppable CAN feature. So **logcat cannot reveal the arrow on a Telenav car**, and there are no CAN nav writes during Telenav navigation.

**But §5 shows this doesn't matter for injection:** even on a Telenav car with arrow-capable firmware, the HUD MCU still reads the CAN guidance registers, so *we* can write them and the HUD renders our arrow. Telenav feeds the HUD its own way, but the CAN path remains available to us.

---

## 8. Debunked / dead ends (don't re-chase these)

- **`0x4A5xxxx` is NOT the arrow.** In the raw driving log these are `BYDAutoInstrumentDevice/StatisticDevice: postEvent device_type 1007/1014, event_type=4a50XXXX` = **vehicle telemetry** (battery% 0x4A501024=99.9, odometer 0x4A502010, speed/temp 0x4A505020=27.0…), pushed continuously regardless of maneuvers. The earlier "fires at turns" was coincidence.
- **Reading the HUD mode via the CAN listener** — a long dead-end (builds 1.6.69→1.6.97): `get()` on BYDAuto devices is push-only (returns 0); the push comes via `onDataEventChanged(int, BYDAutoEventValue)` on a daemon-registered `AbsBYDAutoSettingListener` (needed a `BYDAutoEventValue` stub in `app/libs/byd-auto-api-stubs.jar`; `onDataChanged` is `final` on-device → LinkageError). The HUD display mode selector was found = **`0x42E00008` + `0x42E0000C` (change together, values 1..6)** = a *user* setting, only pushed when the user changes it. This whole listener path is **no longer wired into the diag UI** (replaced by the raw-logcat recorder) but `CanFeedbackListener` + `TXN_CAN_LISTEN_*` still exist in the daemon.
- **Decoding arrow codes passively from logcat during driving** — impossible (they're MCU-side, §2). The active bench (§5) is the way, not passive decoding.

---

## 9. DL5.1 cluster projection (separate track, related)

From a DL5.1 bugreport (`byd_bugreport_20260702_190502`, model `D50F_LC`, product `trinket`, API 33, inswver `DiLink50F_LC-1for2_…`, `fission_single_os=0`):

- The cluster display **exists**: `fission_bg_XDJAScreenProjection` id=2, 1920×720, owner `com.xdja.containerservice`, FLAG_PRESENTATION.
- **Bug fixed in 1.6.100-beta:** DashCast hardcoded `auto_container` (snake_case) for all DL5; the **DiLink50F_LC / 5.1 variant registers the service as `AutoContainer` (PascalCase)**, so `service call auto_container …` returned "does not exist" → activation failed. Matches the OEM rule (AmapService: snake only when `ro.product.name == "DiLink5.0"`, else Pascal). Fix: `AdbLocalClient.autoContainerSvcName()` now **probes** `ServiceManager.getService` for the registered casing (positive-only trust) + caches; `ClusterManager` DL5 activation self-corrects (`noteAutoContainerMissing`) + retries once if the shell still reports the name absent. Real DiLink5.0 (snake) unaffected.
- **Cross-user blocker (open):** launching a nav app on display 2 fails because the app lacks `INTERACT_ACROSS_USERS` (not manifestable) / `INTERACT_ACROSS_USERS_FULL` (role-managed). **Fix = launch via the uid-2000 daemon** (shell holds the cross-user permission), exactly like bydhud's `am start --display <id>`. DashCast already has the primitive: `Phase4TaskVerbs.launchAndForce` (daemon). **On the tested car the daemon itself is DOWN** (byd_log shows repeated `bootstrap timed out` — ADB-over-TCP not connecting), which blocks *everything* daemon-dependent (activation, bug-report shell dump, cross-user launch). That ADB-TCP connectivity is the first thing to unblock on that unit.

---

## 10. DashCast tooling & code map (HUD-relevant)

### The HUD DL3 diag page — `app/src/main/java/com/byd/dashcast/hud/HudDiagActivity.kt`
Rebuilt in 1.6.98, extended since. **Three tools** (dev-only screen, built programmatically, no i18n):
1. **① Confirm the discoveries** — sends the 6 HUD control commands, asks OK/KO after each (popup), zips answers+rc + `inswver` → Telegram topic 2701 (`hud_confirm_*`).
2. **② Raw logcat recorder** — opens `HudRawCaptureActivity.kt`: START launches `logcat -b all -v threadtime` (unfiltered, all buffers) into `/data/local/tmp/dashcast_hudcap.log` via the daemon; an arrow-grid; each tap is injected INTO the log via `log -t DASHCAST_MARK "TAP <arrow> #n"` (same clock → correlation); STOP kills logcat, pulls the file via `TXN_READ_FILE_CHUNK`, zips → Telegram (`hud_rawcap_*`).
3. **③ CAN → HUD bench** — §5. Writes CAN guidance, asks YES/NO/Partial, zips → Telegram (`hud_canbench_*`).
- Firmware label shown at top (`Platform.hudFirmwareVersion()` parsed to `SX<NNN> (date)`).

### Shared helpers
- `hud/HudCaptureSupport.kt` — `zipDir()` + `pullRemoteFile()` (chunked pull via daemon) + `HUD_TEST_THREAD = "2701"`.
- `proxy/ProxyFileVerbs.java` — `readFileChunk(path, offset, len)` client verb.

### PRODUCTION HUD path (UNTOUCHED by the diag work — do not confuse with the diag tools)
- `hud/HudController.java` — orchestration (dedup) that drives `CanBusController`.
- `hud/MapNotificationListenerService.java` — parses Google Maps/Waze notifications → guidance.
- `hud/HudNavigationData.java` — the nav data model.
- These already write the CAN guidance frames (Channel C) that the HUD MCU consumes (§5) — the reason the real feature may already work on arrow-capable firmware.

### CAN write layer
- `system/CanBusController.java` — high-level API: `setNaviActive(bool)`, `sendSimpleGuidance(icon,dist)`, `sendSecondaryGuidance`, `sendNextStreetName`, `sendRestRoute`, `setFeatureInt`, `setFeatureBytes`, `setSettingFeature`. Turn `ICON_*` constants (49). All route through the daemon.
- `proxy/daemon/CanWriteVerbs.java` — raw feature-id constants (§3, §5) + reflection into `BYDAutoInstrumentDevice.set(int[], BYDAutoEventValue)` / `BYDAutoSettingDevice.set` / `settingSetDouble` / `getInt`/`settingGetInt`.

### The daemon (`dashcast_proxy`, uid 2000)
- `proxy/daemon/ProxyDaemonMain.java` — `app_process64` process launched over local-ADB pairing (inherits shell uid 2000); publishes a `Binder`; `PROTOCOL_VERSION = "17"`. **Respawns on every versionCode bump.** Adopts the app package identity via `SystemContextHelper.adoptIdentity` so BYD SDK permission checks pass (uid-2000 privileged context).
- `proxy/daemon/ProxyDaemonContract.java` — TXN codes. HUD-relevant: `TXN_CAN_NAVI_STATUS`, `TXN_CAN_INSTRUMENT_INT/BYTES/GET`, `TXN_CAN_SETTING_INT/GET`, **`TXN_CAN_SETTING_DOUBLE`** (angle), `TXN_CAN_LISTEN_START/DRAIN/CLEAR/MARK` (push listener), **`TXN_READ_FILE_CHUNK`** (pull large logcat past SELinux), `TXN_AAOS_HAL_PROBE`.
- `proxy/ProxyClient.java` + `proxy/ProxyCanVerbs.java` — app-side client verbs (`canInstrumentInt`, `canSettingInt`, `canSettingDouble`, `canListenStart/Drain/Clear/Mark`, `readFileChunk`, …).
- `proxy/daemon/CanFeedbackListener.java` — the push-feedback listener (setting + instrument devices, `onDataEventChanged`); still present, no longer in the diag UI.

### Firmware / platform
- `platform/Platform.java` — `isDiLink3/4/5(ctx)`, `isClusterSingleOs()`, **`hudFirmwareVersion()`** (reads `apps.setting.product.inswver`), `readProp()` (SystemProperties reflection).
- `infrastructure/AdbLocalClient.java` — local ADB socket + `autoContainerSvcName()` (probes AutoContainer vs auto_container, §9) + `sendInfo`.
- `cluster/display/ClusterManager.kt` — cluster activation (`sendInfo 30/16/35`), DL5 self-correcting AutoContainer retry (§9).

---

## 11. Version history (HUD-relevant betas)

- **1.6.56–1.6.64** — DL3 cluster nav via `AUTONAVI_STANDARD_BROADCAST_SEND` (`HudAutoNaviBroadcast`); found **TYPE must be 1 not 8**; visually confirmed on the CLUSTER nav strip.
- **1.6.69–1.6.97** — long "read the HUD mode / decode the arrow via CAN listener" arc (mostly dead-end, §8). Built the daemon push-listener; found `0x42E00008/0C` HUD-mode selector; debunked `0x4A5`; built the guidance recorder (canListenMark).
- **1.6.98** — **rebuild**: emptied the HUD page to the Confirm + Raw-logcat tools; added the proven HUD-control ids; daemon PROTOCOL_VERSION 17 (`settingSetDouble` + `readFileChunk`); **deleted** `HudDiagnosticBundle`, `HudInstrumentSdk`, `HudStateReader`, `HudFeatureScraper`, `HudAutoNaviBroadcast` (all in git history).
- **1.6.99** — firmware detection (`inswver`) + **TOOL 3 CAN→HUD bench**.
- **1.6.100** — DL5.1 AutoContainer service-name casing fix (§9).
- **1.6.101** — DL5.1/Android 13 crash + Bug Report fix: `getExternalFilesDir("vosk")` throws `SecurityException: callingPackage does not match UID` on some ROMs (its mkdirs/AppOps check) → crashed DiagActivity via the voice panel, and `BugReportCapture.newFile` aborted. Fixed with safe accessors + `bindVoicePanel` try/catch + canonical external-path fallback.

---

## 12. Tester workflow & data locations

- **Telegram topic 2701** (`t.me/c/3712642112/2701`) — testers upload HUD zips (the app posts via the bot).
- **Fetch:** `/home/ccarre/app_byd/hud_reports/fetch_hud.py` (Telethon; downloads new zips to `hud_reports/zips/`, then `analyze_hud.py`). State in `hud_reports/state.json`. Config `hud_reports/config.ini`.
- **Zip types:** `hud_confirm_*` (HUD control confirmation), `hud_rawcap_*` (raw logcat + arrow taps), `hud_canbench_*` (CAN→HUD bench). Each has `02_props.txt` with `inswver`.
- **Gold source:** `/home/ccarre/app_byd/log/log.docx` (OEM CarSettings HalSetter logcat) — the HUD-control ground truth (§3).
- **Decompiled OEM APKs:** `/home/ccarre/app_byd/re_hud/src/` (amapservice, clusterdebug, bydhud); jadx at `/home/ccarre/app_byd/jadx`.
- **Build:** Gradle JDK `/home/ccarre/.jdks/jdk-17.0.19+10`; `./gradlew :app:assembleRelease :app:lintRelease`; APK `app/build/outputs/apk/release/DashCast-v<ver>-release.apk`. BYD platform signing (`bydPlatform`). `app/libs/byd-auto-api-stubs.jar` = the BYD SDK compile stubs (incl. an added `BYDAutoEventValue` stub).
- **Persistent memory (this session's notes):** `/home/ccarre/.claude/projects/-home-ccarre-app-byd-MyBYDApp/memory/` — `dl3-hud-investigation.md` (the richest), `dl3-firmware-autocontainer.md`, `dx-byd-auto-cluster-topology.md`, `project_architecture.md`, `release-and-build-conventions.md`.

---

## 13. Conventions & methodology (must-follow)

- **Release flow (verbatim from the user):** *"commit, push puis publie la 1.6.X-beta en pre-release. Ne merge pas en main et ne tag pas cette version en latest. N'oublie pas de joindre l'APK et le changelog en Anglais."* → `gh release create v1.6.X-beta <apk> --target switch-kotlin --title "…" --notes "<English>" --prerelease --latest=false`. Changelogs **always in English** (public-facing).
- **Never break DL3/DL5;** keep **lint 0 errors / 0 warnings** (`grep -c 'severity="Warning"' app/build/reports/lint-results-release.xml` must be 0). Gate HUD-control writes to DL3.
- **versionCode bump respawns the daemon** — needed so new TXN verbs load; but on cars with broken ADB-TCP the respawn fails (§9).
- **OTA updater** compares `versionName` numerically per dotted segment (`1.6.100 > 1.6.99` works — parses "100" as int; verified in `UpdateChecker.isNewer`). versionCode is the PackageInstaller gate.
- **Methodology (user-endorsed):** *"ne prend pas le YES pour acquis"* (don't take a YES on faith — corroborate) and *"tout capter sans filtre et ensuite on ressere l'entonnoir"* (capture everything unfiltered, then narrow the funnel). This is why the raw-logcat recorder + photographed benches matter.
- **Tooling note:** `rtk` is a token-optimizing CLI proxy; use `rtk proxy <cmd>` for raw/unfiltered output when filters might drop lines. A `graphify` knowledge graph exists at `graphify-out/` for codebase questions.

---

## 14. Status board & ranked next steps

**SOLVED / PROVEN:**
- HUD architecture = MCU/CAN-driven (§2).
- HUD container control (on/off/brightness/height/angle/ADAS) on DL3 (§3), DL3-only.
- Firmware discriminator `inswver` + two-firmware split (§4).
- **CAN→HUD arrow injection works on arrow-capable firmware (1 YES) (§5)** — the big one, needs solidifying.
- Cluster nav on AMap DL3 via AUTONAVI broadcast (§6, older work).
- DL5.1 AutoContainer casing fix (§9).

**OPEN / NEXT (ranked):**
1. **(A — user's current choice) Solidify the CAN→HUD bench:** get 2-3 **photographed** YES on arrow-capable firmware, with OEM nav fully closed + HUD in a nav display mode. Tester how-to already written (English). Confirm the exact SX/date threshold for arrow capability.
2. **(B) Test the real feature end-to-end:** on an SX326+ car, run a Google Maps navigation with DashCast open (its `MapNotificationListenerService` → `HudController` → `CanBusController` writes Channel C) and photograph the HUD. If it shows real turn-by-turn, the feature already exists — just needs a DL3-arrow-firmware gate + polish. **This is the highest-value test.**
3. Verify the turn-icon mapping renders correctly (our icon id → the HUD's drawn arrow) — a sweep with photos.
4. Ship the HUD turn-by-turn as a real gated feature (arrow-capable DL3), reusing the prod path.
5. (Adjacent) DL5.1 cluster projection: retest 1.6.100 AutoContainer fix; solve cross-user launch via the daemon; unblock ADB-TCP on the affected car.

**Open questions:**
- Exact SX/date threshold where the HUD firmware gains arrow support (SX309 vs SX326?).
- Does our injected arrow render with the *correct* direction/icon (map our `ICON_*` to the HUD's library)?
- Does the OEM nav (if running) race/overwrite our CAN guidance — do we need to suppress it?
- Does the HUD display mode (0x42E00008/0C, the user setting) need to be a nav mode for our arrow to show? (Suspected reason for the NO/PARTIAL run.)
- Exact `byd.fbs.naviInfo` FlatBuffer schema, IF we ever want the Channel-A cluster path from DashCast (recover from AmapService).
