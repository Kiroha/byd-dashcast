# Local media metadata on the BYD cluster

## Scope and compatibility

DashCast can optionally collect the title, artist and play/pause state supplied by local Android
MediaSessions, then write the separate BYD instrument music properties. This does not identify
songs from audio, inspect notification text, use accessibility heuristics or alter navigation
payloads. The option is **off by default** and independent of HUD/cluster guidance destinations,
local/Satellite navigation selection and the guidance watchdog.

The current application baseline is one native Kotlin/Views/XML app module, minSdk 28, targetSdk
29 and compileSdk 36. Notification access to DashCast's existing
`MapNotificationListenerService` must actually be granted and Android must bind that service.
Declaring a permission or enabling the option is not evidence of session access or OEM output.

The output contract is established by static analysis of Commander 1.2.2 and local DiLink 3 OEM
framework artifacts. It is not hardware validation of DashCast. Intended first test context is a
BYD Seal/Seal U DM-i running Android 10/DiLink 3; the supplied reference firmware is
`6125f_1for2_USER_SIGN_SX361_202606100404_Q2700`. Effective caller permissions, exact instrument
firmware and model-specific rendering remain unverified. No assumption that BYD runs Android
Automotive OS, no AAOS transport, new privilege, vehicle command protocol or navigation mode is
introduced. DashCast's fallback DiLink 3 classification is not positive hardware identification.

This first implementation supports **apps running on the car tablet**. A Carlinkit runs a separate
Android OS: its sessions are invisible here. DashCast Satellite media collection/transport is a
future separate change; the existing Satellite navigation protocol remains unchanged.

## Settings and parked diagnostic

Settings → Cluster music → Show music information on the cluster enables the feature. Give the
existing DashCast notification listener notification access through Android settings. Configure
and validate it only with the vehicle parked. The card is translated in all 13 supported locales
and is not disabled by navigation being switched off.

Diagnostics → HUD bench (DL3) → Cluster music test opens a translated, scrollable test
dialog. Automatic music output must be disabled. A fresh explicit parked acknowledgement is
required for each test; it is user confirmation, not movement detection. The test sends the
fixed benign `DashCast - DashCast test` title, sets playing and attempts cleanup after six seconds
from acquisition. Cancel, dismissal or Activity destruction also releases the test. Production
and diagnostics share one output ownership gate and serial writer. If automatic output is enabled
while the bench owns the channel, production waits for its release and then starts automatically.

The dialog reports sending and cleanup separately. SDK acceptance does not establish visible
rendering. No output acquired means no cleanup is sent. A missing or blocked write callback cannot
extend the ownership deadline, but an already executing Binder transaction cannot be cancelled.

## OEM contract and failure isolation

| Purpose | Instrument device | Property | Value/encoding |
| --- | --- | --- | --- |
| Source | 1007 | `871366704` / `0x33F00030` | `11` (ONLINE) |
| Text | 1007 | `1140527112` / `0x43FB1008` | Two spaces + artist/title + two spaces, UTF-16LE |
| State | 1007 | `1138753546` / `0x43E0000A` | `1` playing; `2` paused |

The media transport permits only these properties and values. It reuses the existing instrument
int/byte daemon transactions with one captured, current Binder and **no retry or reconnect**.
Existing public ProxyClient and navigation methods keep their original behavior. Successful source
selection is followed by a cancellable 50 ms delay, text, then state. Each SDK return code must be
exactly zero; a rejection aborts that sequence. The 50 ms delay reproduces Commander's observed
sequence and does not prove a firmware timing requirement.

Text is bounded to 254 bytes, preserving even UTF-16 length, surrogate pairs and padding. Control
characters are replaced, malformed surrogates are sanitized and input processing is bounded.
There is no BOM or explicit NUL terminator. Metadata is ephemeral and never written to preferences,
logs, reports or navigation frames. Only generic result enums are logged for output/cleanup failures.

MediaSession collection has its own callback HandlerThread and at most 32 registered sessions.
Selection prefers an actually playing session with a usable title, keeps its identity across list
reordering, retains its own pause and moves to another playing source when appropriate. An unrelated
paused session is never chosen on startup. Missing metadata, stopped/destroyed sessions, disconnect,
permission failure, disable and destruction invalidate pending output and release media resources.
No navigation notification is required. The existing listener keeper considers either navigation or
media opt-in eligible for its unchanged permission checks/backoff, including after process restart.

The independent writer uses one scheduled executor with at most one pending latest value or suffix;
cancelled tasks are removed. It neither blocks the navigation executor nor sleeps on the UI/listener
thread. Its idle worker can retire after 30 seconds. Duplicate data is suppressed. After an output
failure, a **new** matching session callback can retry after a five-second cooldown; there is no
periodic replay or daemon recovery loop. Session observer failures use the cancellable collection
backoff described below while the feature is enabled and listener-connected, without restarting
the guidance listener.

After a previously successful write, identical metadata stays deduplicated even if the daemon restarts
without a listener/source event. It then waits for changed metadata/playback or a new media lifecycle;
the module does not poll or resurrect cached titles against a replacement daemon.

Accepted SOURCE selection counts as partial acquisition even before text succeeds. Failed replacement
text/state triggers one best-effort clear, so an old title/new title with stale state is not knowingly
left indefinitely. Source loss/disable clears four spaces and paused state only against the same
still-current acquired Binder. The writer never reconnects just to clear, restores a guessed OEM
source, or writes navigation/HUD enable/display-mode properties. A replaced/dead daemon or rejected
cleanup is reported as unconfirmed; this cannot be fixed by pretending to know current OEM ownership.

## Remaining vehicle checks

Music and navigation are separate properties but still share the OEM SDK/display. Another application,
OEM radio, Bluetooth or CarPlay can take over the music properties, and DashCast cannot prove that its
previous accepted write still owns them. Do not enable concurrent third-party cluster music writers.
Disable this option if it interferes with an OEM source. Cleanup does not restore the previous source;
reselect the desired OEM audio source if necessary. No safety alerts/telltales or OEM driver restrictions
are bypassed.

With the vehicle parked, record model/ROM/API, actual grants/caller UID, return codes and visible results:

1. Run the fixed bench with automatic output off. Confirm text, then cleanup visually.
2. Enable local output and test YouTube Music/Spotify/local playback: initial song, track change,
   pause, resume, app change, metadata loss, stop and source destruction.
3. Test notification-access revoke/regrant, disable during the source delay, app/process restart and
   ACC off/on without opening the guidance UI.
4. Confirm simultaneous music + Maps left/right/roundabout arrows and distances, plus navigation off
   with music still enabled. Navigation expiry must not stop healthy music; media loss must not stop
   healthy guidance.
5. Check OEM radio/Bluetooth/CarPlay source priority, recovery after disable and behavior after daemon
   replacement. Confirm OEM alerts and telltales remain visible.

JVM tests exercise Unicode/selection, default-off lifecycle, late-callback invalidation, ownership
arbitration, bounded scheduling, rejection/cleanup, Binder replacement and one-attempt instrument
transactions. Full repository tests, lint and builds are required; none substitutes for these physical
checks. A release build under the existing published version must exclude `copyMappingRelease` so the
published version's archived mapping is preserved.

## Publication candidate

The media feature and its recovery changes are committed separately from release preparation.
The candidate is now **1.9.17-beta / versionCode 656**, documented in
`docs/releases/1.9.17-beta.md`, together with the Android 10 Satellite ART startup and
AndroidKeyStore TLS corrections. The release checks cover the full JVM suite, release lint,
signed R8 assembly, relay/scanner tests and the trusted release-asset scanner. The stock
Android 29 check uses the exact minified APK and matching mapping to verify real
AndroidKeyStore migration, pinned TLS/WSS authentication and reconnection. It does not
validate the vehicle's hardware-backed provider, hotspot routing or OEM music display.
The new version uses its own mapping archive; published version archives remain unchanged.
Physical OEM music rendering, radio/Bluetooth/CarPlay source priority and recovery remain
unverified.

### Recovery and report diagnostics

An observer failure releases its music ownership and clears acquired output before retrying
collection at 1, 2, 5, 15 and then 30 second intervals while enabled and listener-connected.
Successful collection, including an empty session list, resets this backoff. Disable,
disconnect and destruction cancel retries. This does not restart the navigation listener,
reconnect the daemon, retry SDK commands, or replay cached metadata.

Bug reports include a passive scalar snapshot: opt-in and listener status, observer and
authorization classification, bounded session/usable-session counts, selected-data presence,
retry status, last output stage/result and integer SDK return. Reading this snapshot performs
no session query, Binder transaction or restart. It contains no title, artist, package,
session identity or exception message. SDK acceptance remains distinct from vehicle rendering.

Fields prefixed `last` are historical: they preserve the last observer failure or rejected
output across successful recovery and cleanup. `outputStage`, `outputResult` and `sdkReturn`
describe the most recent writer operation; `cleanupResult` is reported separately.


### Shared cluster text area: positioning remains unverified

A vehicle report on 2026-10-11 confirmed music rendering but showed the title overlapping
the maneuver distance. DashCast continues to send music independently of cluster/HUD guidance;
it does not automatically hide music while guiding. The user requested simultaneous display
with music farther to the right, rather than suppression. No supported music-position command
has yet been established in the inspected OEM contract. This shared-area overlap remains
unresolved; do not claim that concurrent rendering or a right-aligned layout is validated.
Further OEM layout research and parked vehicle validation are required before adding a
positioning command.

The inspected `new_apk_extract/Dilink3/x2/native/` archive contains two OEM theme examples:
`cluster_theme1.rcc` places `MediaInfo.qml` at x=780, y=148, width=386 and
`Navigation.qml` at x=730, y=138; theme 2 uses x=788, y=92, width=370 for media
and x=744, y=78 for navigation. The archive identifies BYD DiLink 3 / Android API 29,
but its firmware is unknown; these are not established SX361 coordinates.
Both examples use clipped music text and a looping marquee. Leading spaces would shift
characters only temporarily and cannot reliably relocate the icon or reserve a separate
area. The inspected music datasource exposes text/icon/play state/language, without a
position handler. These extracted examples do not prove layout behavior on every vehicle.

### Parked layout comparison: a shorter centered group

Further inspection found a useful distinction: `MediaInfo.qml` centers the *combined icon
and text group*, rather than giving the icon a fixed x coordinate. Reducing the measured
text width can move the entire group, including its icon, to the right. This is an indirect
layout effect, not a new OEM positioning command or a validated overlap fix.

For the two extracted themes, the icon is 44 pixels wide with a 5-pixel gap. If the
measured text width `C` fits below the clipping limit, both layouts yield:

```text
music icon left = 948.5 - C / 2
music text left = 997.5 - C / 2
```

| Measured text width C, pixels | Icon left, pixels | Text left, pixels |
| ---: | ---: | ---: |
| 300 | 798.5 | 847.5 |
| 150 | 873.5 | 922.5 |
| 80 | 908.5 | 957.5 |
| 40 | 928.5 | 977.5 |

These values are QML coordinates, not coordinates measured in the user's photograph.
The navigation arrow is 72 pixels wide; the distance starts at x=813 or x=827 depending
on the theme. Consequently even a very short music title may still collide with a wide
distance. For example, an 80-pixel distance starting at x=827 and a 12-pixel margin would
require `C <= 59`; a 120-pixel distance leaves no positive text-width solution. Character
counts cannot substitute for measured Qt font widths, and long-text marquee behavior is
outside this unclipped-width formula.

**Diagnostics → HUD Bench → Test music on cluster** now offers three benign comparisons:

| Comparison | Title | Artist | Text sent by the existing encoder |
| --- | --- | --- | --- |
| Artist + title, reference | DashCast test | DashCast | `  DashCast - DashCast test  ` |
| Short title | DashCast | Empty | `  DashCast  ` |
| Minimal text | DC | Empty | `  DC  ` |

Compare them while parked with the same existing guidance. Disable automatic music output
before running this bench; the bench does not pause, replace or reconfigure navigation.
Each run requires fresh parked acknowledgement, uses the unchanged three allowlisted music
properties and encoder, and releases its ownership after at most six seconds. The choices
are locked until cleanup acknowledgement. Closing the dialog invalidates queued output.
SDK acceptance and cleanup acceptance still do not prove physical rendering or restoration
of the preceding OEM source. Production session selection, metadata formatting and guidance
remain unchanged; no automatic short-title mode or padding workaround is enabled.

A second, conditional avenue is to reduce the width of the distance itself without changing
its numeric value or unit: for example, `500 m` versus `500m`, or `2.0 km` versus `2.0km`.
The inspected theme 1 `Navigation.qml` Latin branch (lines 53–59) displays the supplied
distance text directly at a 48-pixel font size. Removing the space could reduce the required
distance width and complement the shorter centered music group. This is only a future
presentation hypothesis: this bench changes no navigation formatting, and any such experiment
needs separate legibility and vehicle validation. The existing road-name field must continue
to carry actual guidance rather than repurposed music metadata.

Research provenance: `new_apk_extract/Dilink3/x2/00_header.txt` identifies BYD DiLink 3 /
Android API 29 and the exporting DashCast build, but no exact model or firmware. The RCC
archives are `cluster_theme1.rcc` (SHA-256
`1096a4404e0f6f9ea7d62e07935a9b1eb9e4428eb805260f8bb88a370a62f7bb`) and
`cluster_theme2.rcc` (SHA-256
`42940e7c72530d45c0525556e440d640f5f08241e3b2bbd8cd6ff72c6e1451b4`). The relevant
relations are in `MediaInfo.qml` lines 29–30 and 39–59 and `Navigation.qml` lines 192–196
of the extracted themes. This is not positive identification of the user's SX361 layout.

The current DL3 `com.byd.clusterdebug.apk`, also present in the 2026-10-06 and 2026-10-07
exports (SHA-256 `27f1892efeda90358bb8653cd03b42ea4a23a46198fa8687dd5c6e283376b7d1`),
was separately decompiled for `MainActivity` and `SecondActivity`. Their button dispatches
send `sendInfo(1000, commandId, "")`; the inspected command lists contain no music x/y or
position command. No factory/debug command, CAN frame, theme replacement or additional
instrument property is sent by this comparison bench.
