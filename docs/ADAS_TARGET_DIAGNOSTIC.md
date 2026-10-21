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

## Existing OEM log inspection

The same bench has a separate **Inspect existing OEM ADAS logs** button. It requires the
stationary-test acknowledgment and attached Android default display. Opening/resuming the
screen or creating a report never starts it. Both manual buttons share the diagnostic slot;
canceling, leaving the screen or the 8-second logical deadline retires the inspection, while
an already admitted synchronous call keeps the slot until physical return. Late results are
retained as historical diagnostics, never plotted. This is a visibility test, not target access
or a working 360-degree view.

Protocol **30**, transaction 43, adds a no-argument read-only request on the already connected
proxy daemon. It never reconnects/retries, starts a daemon, enables logging, changes properties,
permissions or permission contexts, restarts services, subscribes to OEM data or opens an SPI
device. The raw-property diagnostic still accepts protocol 28. An older daemon returns an
explicit update/restart instruction; app version is unchanged, so an existing daemon can remain
alive after installation.

The fixed paths follow the analyzed SX326 firmware's `xdja.fission_host.rc` bind mounts:
host `/data/logs` is exposed to cell2 at `/data/logs/host/logs`, and host cluster/SPI log
directories at `/data/logs/host/clusterlogs` and `/data/logs/host/spilogs`. Only directory metadata
is inspected for the last two, whose filename/producer contracts are unproved. The first is
scanned for exact `aplog` and numeric rotations 1..10 (including zero-padded two-digit suffixes),
matching `apk_logfs`'s `-f /data/logs/aplog -r5000 -n10`. This logging service is disabled unless
the OEM property enables it; DashCast does not enable it. Paths and effective shell/SELinux
access remain runtime questions.

Collection streams at most 128 directory entries, chooses at most six files by observed mtime,
and reads at most 256 KiB from each tail (1.5 MiB total). Directory ancestors must be actual
directories, with identity checked around collection. Entries must be regular files; open uses
`O_RDONLY|O_NOFOLLOW|O_NONBLOCK|O_CLOEXEC`, with descriptor type/identity checks before/after bounded reads
and path/parent rechecks. Device nodes, FIFOs and symlinks are rejected. Append is allowed and
marked non-atomic; detected truncation/rotation discards that file's records. Concurrent in-place
rewrites or truncate/regrow cannot be ruled out by metadata, so this is not a filesystem snapshot.
The five-second I/O budget is checked between operations; it cannot interrupt a blocked native
filesystem operation. Metadata and up to 64 exact target callback records fit in 24 KiB. No
unrelated log payload is retained. Missing/denied/empty/nonregular/changed/unsupported/budget
outcomes are distinct; missing matches in bounded tails do not prove global absence.

`libBydDataSource.so`'s `CLUSTER_LOG` INFO callbacks use `[callbackInt] id=%d(%s), value=%d` and
`[callbackReal] id=%d(%s), value=%f`. The filter requires exact IDs **499..519**, exact names and
callback kind: seven declared groups (`leftFrontTarget`, `rightFrontTarget`, `leftRearTarget`,
`rightRearTarget`, `frontTargetOne`, `frontTargetTwo`, `rearTarget`), each with `State`, `PosX` and
`PosY`. Values are already decoded DataItems: raw `0x295`/`0x374` conversion is never reapplied.
Callbacks emit changes, not a heartbeat; state 0 can leave cached coordinates. Rear declarations
do not establish active rear feeds. Inspection receipt times are not log or sensor timestamps;
the excerpt removes unrelated prefixes and does not reconstruct atomic target sets or freshness.

The 21 mappings, log formats and mount/logfs evidence are recorded in the firmware investigation
`REA-ADAS-Bridge-20261021` (`ipc/target_callback_format.json`, `ipc/FINDINGS.txt`). Kernel routing
and HAL dispatch evidence in `ingress/FINDINGS.txt` also correct an earlier hypothesis: MCU source
1 routes to both IVI and cluster, and the 32 target keys plus LKS appear in the cluster B-key
table. Android cache misses alone do not prove the raw data stays exclusively in the cluster;
unrecognized HAL keys can be discarded. No supported Android target-data endpoint is established.

The latest result/error is bounded process memory and is replaced by the next accepted manual
inspection. Normal and fallback bug reports include it without another read, through the existing
redaction/delivery flow; events enter `AdasLogDiag`. Tests cover closed scope, exact parsing,
rejected types, empty/denied access, mutation, resource bounds, cancellation/late returns, pinned
protocol behavior, explicit UI admission and both report paths. Static/JVM checks do not establish
vehicle log visibility, active feeds, permissions or hardware compatibility.
