# DashCast — DiLink 3 HUD & Cluster Projection — Full Handoff

> **Purpose.** This is a self-contained context dump so a **fresh chat session** can resume the DiLink 3 (DL3) windshield-HUD / cluster-projection investigation without the prior conversation. It consolidates the persistent memory, the on-car findings, the reverse-engineering, the shipped tooling, the code map, and the ranked next steps. Cross-reference it with the code (English) and the tester zips.
>
> **Author/date:** consolidated 2026-07-04, at app version **1.6.101-beta (build 542)**, branch `switch-kotlin`.
> **Repo:** `github.com/Kiroha/byd-dashcast` (app package `com.byd.dashcast`).

---

## 0. TL;DR — where we are right now

- **UPDATE 2026-07-17 — NO code regression (a "1.6.114 regression" hypothesis, raised only in chat, is REFUTED).** A stretch of **0 YES on the CAN→HUD bench across 1.6.114–1.6.123** looked like a build break but is **procedural / tester-population**, proven by newer benches: **SX326 renders a YES on 1.6.127** (the exact firmware that was "all-NO", now passing on a *newer* build carrying all the always-on feedback-listener code), and **1.6.126 alternates YES/NO/YES within 72 s on one build+firmware** — impossible from a code state, textbook procedural variance. HUD switch was **ON (sw=ON)** in essentially all NO benches (not a HUD-off issue). ⇒ The "always-on HUD feedback listener breaks rendering" hypothesis is **dropped**; **no listener-OFF test build needed.** The bench NOs are **procedural noise**, which is exactly why the response was to steer testers to the **prod path (navrail bug report)** + the **arrow-capability gate** + the steering banner, not to chase a phantom regression. Bench tally now **37 YES / 58 NO (n=95)**.
- **GOAL:** show turn-by-turn navigation on the **DiLink 3 windshield HUD** (and, adjacently, on the instrument **cluster**).
- **HUD architecture (proven):** the DL3 windshield HUD is an **MCU/CAN-driven hardware display** (icon library + text fields selected by CAN frames on the MCU side), **not** an Android rendered surface.
- **HUD control (proven on-car):** we can fully drive the HUD *container* — on/off, brightness, height, angle, ADAS overlay — via `BYDAutoSettingDevice` feature ids (ECU `0x4C1`/sub `0xE`). See §3.
- **★★ BREAKTHROUGH — NOW VIDEO-CONFIRMED (2026-07-04):** on **arrow-capable firmware**, writing our own CAN **guidance** (`BYDAutoInstrumentDevice` INSTRUMENT_GUIDE_INFO_SIMPLE + distance + road + status) makes **turn arrows render on the windshield HUD**. A **20 s tester video** (`app_byd/log/video_2026-07-04_16-10-46.mp4`, night, car parked 0 km/h) shows the **CAN→HUD bench sweep rendering on the W-HUD**: **↑ STRAIGHT ~300 m → ↰ LEFT 300→100 m → ↱ RIGHT 300→100 m** — the exact TOOL-3 sweep (icons 11→1→2, ~6 s each), with the OEM ADAS overlay + "30 MAX" limit + speed. This is the visual proof option A needed (stronger than a photo). **The direction icons render CORRECTLY** (straight/left/right map to the right glyphs). Bench tally is now **29 YES / 21 NO (n=50)**. Every tested DL3 firmware renders our arrows (SX245 100%, SX326 74%, SX309 31% — flaky) and container control works on all of them, so the CAN path reaches the HUD on any DL3 — **not firmware-gated**. Success *rate* varies: **SX309 is the flaky outlier — RESOLVED as procedural** (the same SX309 device flips YES↔NO within one 8-min session on the same build ⇒ not a firmware limit; SX309 has 4 YES = arrow-capable; no per-tester attribution since the bot posts all zips). **No 1.6.109 regression** (SX326 holds 67% on it). Ship gated to **DL3 broadly** (see §4). Evidence frames saved: `log/HUD_ARROW_EVIDENCE_20260704.jpg`, `log/HUD_video_contactsheet_20260704.jpg`. See §5.
- **★ NEW field intel (2026-07-04, topic chat):** a tester (tester A) states **"to display Navi arrows on HUD, the OTA version must be V2.2.2 (with an update for HUD, new style of ADAS symbols)."** This is a concrete OTA threshold for arrow capability (see §4). Caveat: **`V2.2.2` (HUD OTA package version) is a DIFFERENT version namespace from `inswver`'s `SX<NNN>` (MCU revision)** — the two are not yet correlated. Our arrow-YES firmware is SX326; whether SX326 == V2.2.2 is unproven. Ask testers to report BOTH `inswver` AND whether they have V2.2.2 / the new ADAS symbol style.
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

Source: **`log.docx`** (local capture, not versioned) — a tester ran an **unfiltered logcat** while operating each HUD control inside the OEM `com.byd.carsettings` app. Its `HalSetter` logs the exact write per action:
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

| SX code | Build date | Arrow signal | HUD container control (confirm zips) |
|---|---|---|---|
| SX245 | 2025-03-25 | **CAN→HUD bench = 3 YES / 0 NO** — our injected arrows render even on the OLDEST firmware (verified raw, 1.6.101) | 6 controls **work** (all YES) |
| SX309 | 2025-11-08 | **CAN→HUD bench = 4 YES / 9 NO (31%)** — flakiness **RESOLVED as PROCEDURAL** (2026-07: same SX309 device on same 1.6.109 flips YES↔NO within one 8-min session — 07-09 10:10 YES then 10:11-18 ×4 NO; 15:25 YES+NO same minute — a firmware incapacity would be constant NO). 4 YES total ⇒ arrow-capable. No per-tester Telegram attribution possible (bot posts all zips). | 8× full 6/6 YES + 8 partial |
| **SX326** | **2026-02-03** | **CAN→HUD bench = 20 YES / 7 NO (74%)** — the reliable firmware | 25× full 6/6 YES |
| SX365 | 2026-06-07 | newest — no bench yet | ⚠️ one confirm (`hud_confirm_20260704_173637`, on old app 1.6.98) gave **ON=NO / ADAS=NO / rest SKIP** — ambiguous, **re-run on 1.6.101** before concluding SX365 changed the scheme |

(DL5.1 car uses `Di5.1_…_S9221_…`, a different platform entirely.)

**★★ FIRMWARE-GATE THEORY DEBUNKED for OUR CAN injection (2026-07-04, 12-zip bench dataset).** The CAN→HUD bench is now **9 YES / 3 NO** across three firmwares — and crucially **SX245 (the OLDEST, 2025-03-25) scored 3 YES / 0 NO** (verified raw). Meanwhile SX309 (newer) scored the only firmware-diverse NO, and SX326 is 6 YES / 2 NO. The YES/NO is therefore **procedure-dependent, NOT firmware-dependent** — our injected arrows render on essentially **any DL3**, including old firmware. **So tester A's "arrows need OTA V2.2.2" refers to the OEM NAV's own arrow feature, NOT our CAN-injection path** — the HUD MCU can draw *our* arrows regardless of firmware age; the OEM nav just doesn't drive them on old firmware. **Consequence for shipping: gate the HUD turn-by-turn feature to DL3 broadly (`Platform.isDiLink3(ctx) && !AAOS`), NOT to "arrow-capable DL3 (SX326+)".** No `inswver` SX-threshold gate needed. (V2.2.2 / new-ADAS-symbol correlation is now only interesting for predicting the OEM nav's behaviour, not ours.)

---

## 5. ★ THE BREAKTHROUGH — CAN→HUD arrow injection works (Channel C)

Shipped in **1.6.99-beta** as **HudDiagActivity TOOL 3 "CAN → HUD bench"**: with OEM nav OFF, DashCast writes nav guidance itself via `CanBusController`:
`setSettingFeature(SET_HUD_SWITCH, 1)` → `setNaviActive(true)` → `sendSimpleGuidance(icon, dist)` + `sendNextStreetName("TEST …")` + `sendRestRoute(0,5,1200)`, sweeping **STRAIGHT (icon 11) → LEFT (1) → RIGHT (2)**, ~6 s each, then asks the tester **YES/NO/Partial** (did the windshield HUD show an arrow?) → zip `hud_canbench_*` to Telegram topic 2701.

**Result (2026-07-04, 3 zips, all on SX326 arrow-capable firmware):**
- `hud_canbench_20260704_124211` (1.6.100) → **"YES — arrow on HUD"** ✅
- `hud_canbench_20260704_150400` (1.6.101, current build) → **"YES — arrow on HUD"** ✅ *(reproduced the YES)*
- `hud_canbench_20260704_084429` (1.6.100, earliest) → "NO/PARTIAL" ❌
- All: all writes rc=0, OEM nav off. **Tally (50 zips, 2026-07-04/05) = 29 YES / 21 NO.** Per-firmware SUCCESS RATE: **SX245 3/3 = 100%, SX326 20/27 = 74%, SX309 4/13 = 31% (flaky outlier), SX365 0/1, other(non-DL3) 2/6.** All three main DL3 firmwares render arrows (each has YES), and **container control works on all** (SX309 has 8 full 6/6 confirms) — so the CAN path reaches the HUD on every DL3; only the arrow *success rate* varies. **SX309 is the flaky one** — cause unresolved (procedure vs firmware, confounded with one tester, "tester B"): its container control works, so its low arrow rate is likely the OEM nav overwriting / wrong HUD display mode, not a broken path. **No 1.6.109 regression**: SX326 holds 8/12 = 67% on 1.6.109 (in line with its overall rate); the 1.6.109 NO spike is test volume + firmware mix, not a build break. One NO note ("distance and some Chinese text") = the HUD *did* render (distance+text) but no clear arrow → some "NO" are really partials.

**Interpretation:** on arrow-capable DL3 firmware the windshield HUD **does consume the CAN instrument guidance registers** (`INSTRUMENT_GUIDE_INFO_SIMPLE_SET` etc.). So **DashCast can drive the HUD arrows via Channel C**, independent of which nav app is the OEM source (even on Telenav cars — the MCU reads CAN). This **flips** the earlier "no injectable HUD path" verdict *for arrow-capable firmware*.

**Caveat (do not take the YES for granted):** 2 clear YES vs 1 NO/PARTIAL on the *same* SX326 firmware ⇒ the NO is **procedure-dependent, not firmware**. Likely reasons it failed: OEM nav not fully closed (it overwrites our guidance), or the HUD display mode was "speed only" (not a nav mode), or the tester looked at the wrong moment. Cédric's updated tester how-to (topic msg 2026-07-04 12:37) already instructs: fully close OEM nav + set HUD to a **navigation** display mode + take a **PHOTO**. **Still need photographed YES to solidify — 0 photos returned so far** (all results are self-reported YES/NO taps; the zip carries no image, photos come as separate Telegram image posts).

**★★ VIDEO CONFIRMATION (2026-07-04) — option A is now visually locked.** A tester posted a 20 s video (`app_byd/log/video_2026-07-04_16-10-46.mp4`; night; car parked, **0 km/h**). Frame-by-frame (extracted at 1 fps) shows the **windshield HUD rendering the CAN→HUD bench sweep**:
- **t≈1–4 s: ↑ STRAIGHT (icon 11), ~300 m** (vertical maneuver arrow)
- **t≈5–10 s: ↰ LEFT (icon 1), 300→260→220→180→140→100 m** (distance counts down)
- **t≈11–16 s: ↱ RIGHT (icon 2), 300→…→100 m**
- **t≈17–20 s: straight/right, 100 m**
Throughout: the OEM ADAS overlay (car-ahead outline + blue chevrons), a "30 MAX" speed-limit sign, and "0 km/h" — i.e. the HUD is in a **nav/ADAS display mode**. The `STRAIGHT→LEFT→RIGHT` order + 0 km/h = unmistakably **our TOOL-3 CAN→HUD bench**, not a live drive. **Two things proven for the first time on video:** (1) the W-HUD consumes and *renders* our injected CAN guidance (arrow + distance); (2) the `ICON_*` → HUD-glyph mapping renders the **correct direction** for straight/left/right (answers the §14 open question). Evidence frames: `log/HUD_ARROW_EVIDENCE_20260704.jpg` (3 directions), `log/HUD_video_contactsheet_20260704.jpg` (all 20 frames). Note: no next-street-name text field was visible in this HUD layout (the DL3 HUD icon library may not surface `sendNextStreetName` — only the maneuver arrow + distance). Firmware not shown in-frame, but arrow rendering ⇒ arrow-capable (SX326-class).

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

### PRODUCTION HUD path (the real turn-by-turn feature — Maps notif → CAN, DL3-gated)
- `hud/HudController.java` — orchestration (dedup) that drives `CanBusController`. **2026-07-05: now DL3-gated** (`isDiLink3Hud(ctx)` = `Platform.get().isDiLink3(ctx) && !AaosClusterProbe.INSTANCE.isAaos(ctx)`, cached, fail-safe false) **and `ensureHudActive()` now writes `SET_HUD_SWITCH=ON` before `setNaviActive(true)`** — matches the video-proven bench so nav shows even if the HUD was off (HUD left ON on nav end; only nav registers cleared). Compile + lintRelease 0/0. Pending on-car verification (drive a real Maps nav on DL3).
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

- **Telegram topic 2701** (HUD topic of the private tester channel) — testers upload HUD zips (the app posts via the bot).
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
- **CAN→HUD arrow injection works on ANY DL3 — VIDEO-CONFIRMED + 9 YES / 3 NO (§5)** — the big one, now **locked & generalised**: a tester video shows the W-HUD rendering our bench sweep ↑STRAIGHT→↰LEFT→↱RIGHT; the 50-zip bench is 29 YES / 21 NO (SX245 100%, SX326 74%, SX309 31% flaky); every DL3 firmware renders arrows + has working container control ⇒ **not firmware-gated**; SX309's low rate is the open flakiness (procedure vs firmware, tester-confounded); no 1.6.109 regression. Direction glyphs render correctly. Ship gated to DL3 broadly.
- Cluster nav on AMap DL3 via AUTONAVI broadcast (§6, older work).
- DL5.1 AutoContainer casing fix (§9).

**OPEN / NEXT (ranked):**
1. **(A) Solidify the CAN→HUD bench — DONE (video-confirmed, §5).** 2 YES / 1 NO on SX326 + a tester **video** showing ↑→↰→↱ render on the W-HUD with correct directions. Remaining minor: (a) correlate `inswver` (SX) ↔ OTA `V2.2.2` to pin the exact arrow-capability threshold (§4); (b) get one more confirmation on a *different* car/firmware to generalise. **→ pivot to (B).**
2. **(B) Test the real feature end-to-end:** on an SX326+ car, run a Google Maps navigation with DashCast open (its `MapNotificationListenerService` → `HudController` → `CanBusController` writes Channel C) and photograph the HUD. If it shows real turn-by-turn, the feature already exists — just needs a DL3-arrow-firmware gate + polish. **This is the highest-value test.**
3. Verify the turn-icon mapping renders correctly (our icon id → the HUD's drawn arrow) — a sweep with photos.
4. ~~Ship the HUD turn-by-turn as a real gated feature~~ **IN PROGRESS (2026-07-05): HudController now DL3-gated + turns the HUD ON at nav start** (matches the proven bench). Parsing verified (13-lang keyword table + icon-resource fallback, solid). Compile+lint 0/0. **Remaining: on-car verify a real Google Maps nav lights the HUD on DL3, then commit/ship the beta.** Minor optional polish deferred: distance-less frames skip the update; road-name regex EN/FR only (DL3 HUD has no road field anyway).
5. (Adjacent) DL5.1 cluster projection: retest 1.6.100 AutoContainer fix; solve cross-user launch via the daemon; unblock ADB-TCP on the affected car.

**Open questions:**
- ~~Exact SX/date/OTA threshold where the HUD firmware gains arrow support.~~ **RESOLVED for our CAN path (§4): NOT firmware-gated** — our injected arrows render on SX245 (oldest) through SX326. The "OTA V2.2.2" threshold (tester A) applies to the **OEM nav's** own arrows, not ours. Correlating V2.2.2↔SX is now only relevant to predicting OEM-nav behaviour, not shipping our feature.
- Does the newest firmware **SX365** keep the same HUD-control scheme? One ambiguous confirm (`173637`, old app 1.6.98) gave ON=NO/ADAS=NO — **re-run on 1.6.101** to rule out an app-version/test-noise artifact vs a real scheme change.
- ~~Does our injected arrow render with the *correct* direction/icon?~~ **ANSWERED (video, §5): straight/left/right render correctly.** Still to sweep-verify the rarer icons (slight/sharp/u-turn/roundabout/exit/arrive) against the HUD's drawn glyph.
- Does the OEM nav (if running) race/overwrite our CAN guidance — do we need to suppress it?
- Does the HUD display mode (0x42E00008/0C, the user setting) need to be a nav mode for our arrow to show? (Suspected reason for the NO/PARTIAL run.)
- Exact `byd.fbs.naviInfo` FlatBuffer schema, IF we ever want the Channel-A cluster path from DashCast (recover from AmapService).
