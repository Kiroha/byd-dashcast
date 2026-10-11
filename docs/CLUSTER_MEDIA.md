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
periodic replay or daemon recovery loop. Session observer failures wait for a new listener connection
or an explicit preference change rather than restarting the guidance listener.

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
