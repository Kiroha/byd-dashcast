# ADAS target diagnostic

Diag → **ADAS targets (read-only snapshot)** opens a central-display bench. Opening or resuming
the screen and generating a bug report perform no OEM reads. A stationary-test acknowledgment
and **Read one snapshot** are required for every capture. The acknowledgment is not evidence
that the vehicle is parked; movement remains unknown.
Capture also requires an attached window whose observed Android display is `DEFAULT_DISPLAY`.
Unknown/secondary placement cancels further admission and clears the schematic, checked again
on resume, window focus/attachment, UI updates and immediately before a manual capture. The
schematic also checks the attached display on every draw, including idle-window migration.
Android display zero alone does
not identify the physical central screen on every firmware.

The fixed plan contains 33 properties; one capture attempts at most 33: 32 candidate keys for the four front groups
under the two compiled decoder variants, plus LKS mode `0x3160000e`. The variants describe the
same four groups, not eight objects. Decoder hypothesis defaults to unknown, so the first
capture can inspect raw values without drawing inferred positions. Selecting `0x295` or
`0x374` explicitly chooses a decoding hypothesis for the next snapshot; it does not identify
the active vehicle configuration.

The firmware evidence is OEM SX326 / OTA `13.1.33.2602030.1`: the `BusinessUi1` front-target
decoders in `libBydDataSource.so` (SHA-256
`1bda5155149d5131af36a8ee2c5e1b4f944738a10b5b7bbafcf4a3945d6ff91d`).
The seven-target crosswalk and exact formulas were retained in the REA investigation. Target
keys are internal cache keys, not CAN arbitration IDs or proof of registration in Android's
cache. The framework identifies device 1038 as ADAS. Runtime model, API, display/fingerprint,
app UID, daemon UID/PID/protocol and the decoding hypothesis accompany each capture. They do
not establish compatibility with this firmware or permission to use a different OEM API.

The additive proxy protocol 28 verb accepts only this fixed allowlist, validates it in both
processes, and invokes `BYDAutoManager.getInt(1038, property)` with the original system context.
It initializes one manager per daemon, reusing the reflected method and manager, since the
OEM constructor initializes native state and listeners. Initialization failures remain
diagnostic failures and are not repeatedly initialized per key. This public raw manager
path retains its existing OEM API, blacklist, native and service admission checks. It does
not assert that the distinct typed `BYDAUTO_ADAS_GET` permission was granted. No permission
wrapper, new permission declaration, setter, calibration, SPI reader, explicit target-data
subscription, continuous sampling or camera access is added. The OEM constructor's own
listener/native initialization is handled once. The existing bodywork reader and device 1000
paths are unchanged.

The capture pins the already connected proxy, never reconnects/replays and requires protocol
28. An older daemon produces an actionable error; use the existing daemon update/restart
controls. The diagnostic never kills or refreshes a daemon that may own active projection
work. Local development builds with the same app version can still have a prior daemon alive.

The sequence runs off the UI thread, with a 15-second logical deadline and one process-wide
slot shared with other Diag probes. Canceling, leaving the screen or reaching the deadline
stops subsequent read admission. One already admitted synchronous call may still complete.
A blocked call retains the slot until physical return, and its late answer cannot revive the
plot or retire a newer capture. There is no continuous/background sampling or capture on resume.

Each property retains raw integer or exception/root cause, outcome, start/receipt epoch times
and duration, including unattempted and pending rows. `-10011` means the examined HAL returned
an absent/null cache key; `-10013` means integer type mismatch. Other negative results remain
unclassified negative OEM results. A completed sequence is not API/data success. Zero is
not evidence of clear surroundings. Receipt time is not the last sensor update time.

Points require a completed snapshot, an explicit hypothesis, all four required successful
nonnegative raw values, availability 1, type 1..4, and the exact firmware ranges. Negative
values are never masked into unsigned coordinates. Each new attempt clears previous points;
cancellation and leaving the screen clear them. Raw history remains available on reentry.
This schematic has no metric units, calibrated physical axes, verified freshness, rear/side
coverage, camera image or 360-degree claim, and is not a driving aid.

The latest 33-row snapshot is retained in process memory (including after screen reentry),
with bounded context/error strings; it is replaced on the next accepted capture and does not
survive process death. There is no accumulated snapshot history or additional payload archive.
Capture events and individual outcomes also enter the existing bounded AppLogger journal.
Both normal and fallback bug reports append the retained snapshot; report generation performs
no new probe and uses the existing report redaction and user-directed delivery flow.

JVM/Robolectric checks cover allowlist/protocol errors, unchanged raw sentinels, no replay,
single manager initialization, decoder completeness/ranges, timeout/cancellation/late returns,
stale watchdog identity, no automatic reads, large-font short-screen layout and normal/fallback
report inclusion. Static and JVM checks do not validate OEM access, hardware behavior or the
source/freshness of values on a vehicle. No vehicle command, installation or ADB test was run.
