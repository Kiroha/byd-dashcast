# Native ADAS pane control

Settings → **ADAS pane: hide / restore** offers manual fallback actions and an opt-in
**Hide the ADAS pane during projection** setting (off by default). The older **ADAS Window Fix**
changes projection geometry and remains independent. Enabling the new option requires the parked
acknowledgement; it arms a projection-coupled workflow rather than immediately sending a frame.

The control intentionally hides the **entire pane, including its distance alerts and lane
warnings**. The dialog repeats that effect and requires a new parked acknowledgement for each manual hide or initial enabling of automatic mode. Restore remains accessible
without that acknowledgement or an available proxy reader.

## Initial compatibility and evidence

The initial gate requires API 29, positive DiLink 3 detection (never the fallback), the enabled,
exported and unpermissioned OEM receiver, and this researched SEAL firmware fingerprint:

`BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1.210910.001/eng.build.20260610.082733:user/release-keys`

The maintainer identifies this car as a SEAL with Android 10 / DiLink 3 and SX361 firmware
`6125f_1for2_USER_SIGN_SX361_202606100404_Q2700`. Other fingerprints are refused. This is a
compatibility restriction based on the evidence, not certification for other vehicles or firmwares.

The fingerprint and SX361 identifier are recorded in the maintainer's bug report captured
2026-10-12 at 18:58:17 (`byd_bugreport_20261012_185817_2dc33102e7e0485c97b84d034da34fca.txt`,
lines 7644 and 7702 respectively).

Evidence retained outside the source repository:

- `Electro/analysis-rea-20261019/oem-adas-3r1v.json`: the SX361 native
  `BusinessUi1::updateAdasWindow3R1V` uses input `0x23500008 == 1` to permit its existing OEM
  power/availability/display-preset/3R1V gates; other inputs suppress its display item. Its output UI states
  `1` and `2` are layouts, not interchangeable input values.
  REA evidence: `ev_5cf7d0ce6d659a17c403836d03ad601772ee53f6c290fc16e37977ad1e3ad201`.
- `Electro/analysis-rea-20261019/hal-setString.json`: `TEST_SIMULATE_DOWN_SET` calls
  `AutoInterface::writeDevice` with the raw frame. This is an actual device downlink, not merely
  a local Qt cache update. Its result is not propagated to the app. In particular, the SDK cache
  may still read `1` after a hide.
  REA evidence: `ev_b50f338e2aa13156b42b0b4aa5d860371145183d3050ca1d0f23138edc55ec1f`.
- OEM `BroadcastReceiverCAN` is an exported manifest receiver without an additional permission.
  It decodes `wholeFrame` comma-separated hex bytes and calls `BYDAutoTestDevice.set` with
  `TEST_SIMULATE_DOWN_SET` (`0xAA00020F`). No service initialization or AutoContainer command is
  needed for explicit component delivery.
- The maintainer verified **Electro's hide** on this car, then confirmed that DashCast
  **1.9.50-beta** hides the pane on projection launch and brings it back with full restoration.
  The later explicit re-hide and Stop corrections below still require their own vehicle test.

The manual fallback permits only these two frames:

| Request | Bytes | Evidence |
| --- | --- | --- |
| Hide whole pane | `23,50,00,08,01,00` | Maintainer's successful Electro observation |
| Restore saved OEM input | `23,50,00,08,01,01` | Derived from captured input `1` and the researched native consumer; vehicle validation pending |

The frame is property ID BE32, length `01`, then one value byte. The explicit target is
`com.byd.clusterdebug/com.byd.clusterdebug.BroadcastReceiverCAN`, action `com.byd.cluster.spi`.
Only this explicit manual broadcast includes stopped packages, so the manifest receiver can run
before its app has first been opened; no service is started.
No arbitrary frame or guessed input `2` is exposed by the manual fallback.

## Manual fallback recovery and failure behavior

Before **each** hide, query exactly the original input through the already connected, pinned
ProxyDaemon, once and without reconnect. Only `1` is accepted; `0`, `2`, SDK sentinel values,
exceptions and daemon replacement refuse the hide. This getter reads an SDK cache with no
timestamp: a new invocation does not establish fresh physical-state evidence.

The captured recovery value `1` and firmware fingerprint are committed synchronously before
the hide broadcast can be emitted. A failed commit prevents that broadcast. The actual
`byd_app_prefs.xml` file is excluded by the existing backup include list, which includes only
`byd_prefs.xml`. A fingerprint mismatch invalidates recovery use.

Restore **always sends the saved `1`**, even if the SDK cache still reads `1`. It never reads the
SDK or depends on the proxy connection. The value is retained after successful or uncertain
delivery so the user can manually retry; it is recovery evidence, not an enabled preference or
proof the pane is hidden. Restoring the input returns control to OEM conditions and does not
force the pane visible in circumstances where the OEM would hide it.

A process-wide request slot serializes settings screens. The original-state probe has its own
single-worker, nonqueued lane. Cancelling/timing out a probe retires its request, and its late
answer cannot emit a hide or clear a newer request's slot. Manual recovery has an independent
bounded dispatch lane, so a wedged proxy query cannot prevent it. Already started broadcasts
cannot be recalled. A dialog timeout or dismissal does not automatically issue any vehicle command.

All UI results describe a **request sent**, never SDK acceptance, a device acknowledgement or
the rendered state. Android accepting a broadcast does not propagate either the OEM SDK result
or the physical downlink result. No secret, notification or route text is shown or logged.

## Integer reader compatibility correction

The 2026-10-19 report captured at 21:23 records four hide requests ending in `SEND_FAILED`,
followed by a restore request ending in `RECOVERY_MISSING`. Projection was already confirmed
before the first hide request. No hide success was reported and no usable recovery value was
found; these results do not establish a requirement to delay the request until projection starts.

The researched SDK exposes inherited `AbsBYDAutoDevice.get(int[], Class<?>)`, while the daemon
previously resolved only `get(int[])`. This concrete signature mismatch predicts a lookup
failure on that SDK. The report did not retain the exception, so the exact runtime failure
cannot be proved retrospectively. Both instrument and setting integer reads now prefer the
existing public `get(int[])` where available, otherwise resolving the public inherited typed
getter and passing primitive `int.class`, as required by the SDK. They invoke the selected
method once: SDK errors, exceptions and malformed results never trigger another overload.

An original-value query exception now records only `stage=original_read` and its exception
class, without its message, stack trace or property value. It still prevents recovery capture
and every hide frame. The manual actions, fingerprint binding, original-value gate, recovery
journal and downlink contract are unchanged. Vehicle validation is still required.

## Projection-coupled sequence

The recovered Electro 1.14.0-lab-80 path starts only after an app is resumed on its target
projection display. DashCast records a requested target before asynchronous launch work, then
independently checks the resumed activity on that exact display. Task placement, a launch callback,
a saved profile, and foreground activity on display 0 are insufficient. Ambiguous/truncated dumps
are refused. Proof expires after four seconds: the nominal one-second polling gap plus the bounded
2,500-ms read leaves 500 ms of scheduling margin. This is one additional second of maximum
staleness compared with the earlier lease; every negative result or read timeout still invalidates
immediately, and each write checks the monotonic deadline. A replacement session, manual recovery, display
loss, source loss or daemon replacement invalidates queued frames and commands.

The typed power gate and raw maintenance monitor are **different SDK channels** on SX361:
`BYDAutoBodyworkDevice.getPowerLevel()` uses device **1001**, and must equal **2**; Electro's raw
`BYDAutoManager.getInt(1000, 0x12d0002a)` maintenance monitor must equal **3**. Neither proves parked
status. The platform monitor is `getInt(1000, 0x40d00010)`, with maintenance IDs 102/115/141/181.
Daemon protocol 27 adds only these closed read operations. Typed state is checked continuously,
not replaced by a constant after activation. Invalid results never become a success value.

Before the first mutating command, commit a firmware-bound recovery journal. Then:

1. AutoContainer 13; allowlist `com.byd.clusterdebug` for device idle and start its
   `.ClusterDebugService`; wait 1,000 ms.
2. Three `23,50,00,08,01,00` frames.
3. Ordered phases: 60 × `26,F0,00,28,01,06`; 50 × `43,20,00,00,01,01`;
   50 × `43,20,00,21,01,01`; 40 × `26,F0,00,28,01,06`; 60 × `23,50,00,08,01,00`.
   Each batch contains at most ten frames. One complete pass contains 263 frames.
4. One guarded confirmation pass starts five seconds after the complete initial pass succeeds.
   Failure or uncertain delivery does not schedule a replay. There is no recursive confirmation.
   A later, newly observed qualified power transition may request a new pass; it is not a retry of
   the failed batch, and still requires the current projection proof and owner.
5. The masking plan sends no layout commands. The existing projection activation owns 16/35 and
   teardown owns 18/0; masking cannot close and reopen its own verified projection. Slow passes run
   to completion without being superseded by confirmation or raw-power callbacks. Pending full
   passes coalesce; a transition during initial confirmation joins that confirmation.
6. A raw power transition to 3 schedules a guarded full pass after four seconds. While qualified
   and not busy, maintenance sends three hide frames every three polling ticks. Service initialization
   refresh is limited to once per 60 seconds. Initial raw state does not manufacture a transition.

Frames use package-targeted **ordered** broadcasts with the foreground flag, allowing the receiver
registered by the service to participate. The framework callback orders delivery but carries no OEM
SDK/MCU/display acknowledgement. Only a `SecurityException` before the first callback permits the
compatibility shell path. Partial security failures, runtime errors, timeout and interruption never
replay an uncertain prefix. Every batch has a five-second deadline and checks cancellation per frame.

Disable, explicit full recovery, or known projection teardown first initializes
`ClusterDebugService` on the independent recovery lane and waits 1,000 ms, then requests 40 restore
frames in batches of ten, command 12, a 1,200 ms wait, then command 53. Initialization, the readiness
delay, every frame and both commands retain the cleanup ticket and pinned-daemon guard; a newer
projection owner cancels the entire old recovery before it can emit another write. Repeated teardown coalesces an ongoing recovery.
Manual fallback recovery remains available if the service or proxy is unavailable. No software result
is described as proof the pane is visible. The recovery journal is retained even after delivery.

### Re-hide after full restoration and projection Stop

The 2026-10-20 reports captured at 00:15:41 and 00:19:11 show repeated manual hides ending in
`ORIGINAL_UNKNOWN`. The latter records a verified projected app at 00:18:14, full restoration
delivery at 00:18:42, and the failed manual hide at 00:18:50. The button was still using the
independent one-frame fallback, whose SDK cache was not `1`; full restoration had also retired
the active projection target. The reports do not retain the returned cache value.

With projection masking enabled, **Hide the entire pane** now reuses the full existing sequence.
It keeps the existing projection identity only as an inert candidate, rechecks its current owner,
exact resumed display, typed/raw power, supported firmware, protocol and pinned daemon, and commits
the recovery journal before writes. It never relaxes the legacy fallback's original-value gate or
falls back to one frame after an uncertain full-sequence failure. The automatic preference is not
changed by a manual request.

Full restoration suspends automatic hiding for that projection owner. Late launch callbacks,
same-owner Fission app/slot changes and preference notifications cannot re-hide the pane. A fresh
manual Hide can rearm after proof; a genuinely new projection owner can use the enabled automatic
setting. The first three ordered hide deliveries complete the UI request, not the full pass or any
physical-state acknowledgement. Before that delivery, dismissal or the 15-second UI deadline cancels
the request; afterwards, dismissal leaves the remaining sequence running. Recovery stays accessible
while a hide is pending. A rapid re-hide also waits for a one-second admission quiet period after
full restoration, without changing the recovered sequence's internal delays.

**Stop projection** immediately revokes hide authority and remembers whether this projection used
the masking workflow, including after a manual full restore. It captures the old cleanup ticket
before asynchronous teardown, then waits for the final native-close command completion (success or
error) and a one-second quiet period before starting service initialization and its 1,000-ms readiness delay, followed by the full
40-frame / 12 / 1,200-ms / 53 restoration.
Classic, Fission and service teardown share this boundary; duplicate stops coalesce. An explicit
full restore during that wait joins the same recovery. One-frame hiding is refused while recovery
is pending, but saved-value one-frame restoration remains available on its independent lane without
interrupting the pending full recovery, including when native teardown IPC is blocked.
A newer projection owner retires the old pending completion. A historical journal
alone never authorizes automatic restoration on an unrelated Stop. Known, in-process masking debt
does survive an owner replacement until a full restoration succeeds, so Stop can recover a previous
mask even if the replacement app never verifies or starts its own masking pass.

The original-cluster Stop path includes its final size/theme command (29/30/31) in
that native completion boundary. Its UI remains in the restoring state through
18 → 2 seconds → 0 → 3 seconds → size; only the terminal callback releases the
eviction launch fence and starts the existing recovery quiet period. Previously
the callback fired immediately after 0, leaving a background size command racing
the recovery's 12/53 commands ([20:21 incident](incidents/INC-20261021-202126-ADAS-STOP-RESTORE.md)).
Stop revokes masking before asynchronous eviction. Final-size error or interruption
still completes cleanup once, retaining the journal and manual recovery. Old delayed
native writes and old UI teardown are rejected after a new projection owner begins.
An already admitted Binder/ADB operation cannot be recalled atomically.

### BYDcontrol orchestration alignment (2026-10-23)

The source comparison uses BYDcontrol commit `0a1237f5217963893187b6f8abad397a0888712a`,
`ClusterController.hideAdas()` / `showAdas()` and `AutoStartService` automatic activation. Its own
comments identify the 263-frame core as the DashCast sequence. DashCast now follows its useful
orchestration differences: finish verifying the existing projection first, complete each masking
pass, confirm once after five seconds, and initialize the OEM service before restoration. Tests
cover a pass lasting beyond the old layout timer, queued power transitions, failed initialization,
source/read timeout and owner replacement during the restore readiness delay.

This is deliberately not a byte-for-byte transport clone. DashCast retains its 263 ordered frames
and five-second bounded batch deadline, without BYDcontrol's additional explicit manifest-receiver
hide or `FLAG_INCLUDE_STOPPED_PACKAGES` on every phase. It does not continue after an uncertain
250-ms callback timeout, switch transports after partial delivery, or report rendered success from
software dispatch. The existing one-frame manual fallback retains its separately researched
manifest-receiver contract. No firmware allowlist, automatic opt-in, movement assumptions or
vehicle command allowlist is broadened.

### Evidence and deliberate host adaptations

Research root: `tiers_apk/Electro/analysis-rea-20261019/` (outside this source repository).

- `projection-order/findings.json` and `projection-order/parity-gaps/findings.json` retain the
  actual protected-method paths, literal frames, phases, timers, callbacks and exception handlers.
  `verify_reconstruction.py` passed: 1,568 wrappers, 1,526 completely decoded methods, all 175
  projection-manager methods decoded; no target execution.
- `native-parity-contract/clusterDebug.json`, REA
  `ev_8fded3880b347307f2465603a590dea4e98173a5ce8dc26869825193102c7f33`, confirms current-firmware
  command 12/13 set test-mode item `0x2f` to 1/0; command 53 forces ADAS type item `0x1dc` to 0.
- `native-parity-contract/adasSelfStudy.json`, REA
  `ev_36cc41d918b24df4119d74af34de24ea660c26ec5c8d5cbcac1da5f8316f6992`, identifies `0x43200000`
  as CAN432 presence and `0x43200021` as ADAS type. Type 1 suppresses the legacy 2D pane in the
  available theme corpus; `0x23500008=0` suppresses the 3R1V pane. These are more than a text/UI offset.
- `projection-order/parity-gaps/acc-input-contract.json` and `sdk-power-route-excerpts.json`
  distinguish typed device1001 power callbacks from raw device1000 queries.

DashCast reproduces the fixed masking/recovery sequence, with these explicit host adaptations:

- Its existing projection owner/session and command sequencer retain ownership. The coordinator
  never begins a competing projection. A guarded cleanup ticket queues restore commands behind
  already-started owner commands, survives that owner ending, and is revoked by any newer owner. Fission adopts a logical session only when reusing native
  projection with no current owner token.
- Typed ACC callbacks are replaced by bounded read polling alongside resumed-activity proof.
  Power is sampled each polling iteration; platform every third iteration. IPC latency adds to the
  nominal one-second delay. Failed/expired evidence cancels sends; it does not invent a restore on
  an unknown power transition. Known projection teardown and explicit recovery remain the recovery paths.
- Shell compatibility sends one frame per call so cancellation can be checked between frames,
  preserving the fixed order/count. Every mutating IPC is single-attempt and pinned to one daemon.
  Separate bounded read, write and recovery lanes prevent a blocked SDK query from starving recovery.
- The journal is retained, unlike Electro's marker deletion after software success. No unrequested
  boot/ACC_OFF marker cleanup is installed. Opening Settings or reconnecting a daemon does not replay
  a previous request. The existing manual one-frame recovery is preserved as a fallback.

This is **not** a claim that all of Electro is reproduced. Exact active-theme identity, complete MCU
side effects, and recovery of all ancillary state remain unverified. In particular, command 53 forces type 0;
it does not restore a captured prior ancillary type. Android source analysis cannot prove all effects
of raw `TEST_SIMULATE_DOWN_SET` beyond the researched consumer.

## Required vehicle validation

While parked on the researched firmware, check projection launch → full restore → Hide again,
then Stop after a hide and after manual full restoration. Also check repeated manual restoration,
activity recreation and app process restart. Confirm the pane returns under its normal OEM
conditions, and document the full-pane warning effect. Other firmwares remain unavailable until
their consumer and restoration contract are researched and tested.
