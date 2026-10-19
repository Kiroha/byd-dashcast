# Manual native ADAS pane requests

Settings → **ADAS pane: hide / restore** opens two manual actions. This is independent of
the older **ADAS Window Fix**, which changes projection geometry. No projection, navigation,
music, boot, resume, reconnect or periodic task calls this control.

The control intentionally hides the **entire pane, including its distance alerts and lane
warnings**. The dialog repeats that effect and requires a new parked acknowledgement for each hide. Restore remains accessible
without that acknowledgement or an available proxy reader.

## Initial compatibility and evidence

The initial gate requires API 29, positive DiLink 3 detection (never the fallback), the enabled,
exported and unpermissioned OEM receiver, and this researched SEAL firmware fingerprint:

`BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1.210910.001/eng.build.20260610.082733:user/release-keys`

The maintainer identifies this car as a SEAL with Android 10 / DiLink 3 and SX361 firmware
`6125f_1for2_USER_SIGN_SX361_202606100404_Q2700`. Other fingerprints are refused. This is a
compatibility restriction based on the evidence, not certification that DashCast's implementation
has passed a vehicle test.

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
- The maintainer has verified **Electro's hide** on this car. Neither an Electro restore nor the
  DashCast hide/restore pair has been validated on the car.

The only permitted frames are:

| Request | Bytes | Evidence |
| --- | --- | --- |
| Hide whole pane | `23,50,00,08,01,00` | Maintainer's successful Electro observation |
| Restore saved OEM input | `23,50,00,08,01,01` | Derived from captured input `1` and the researched native consumer; vehicle validation pending |

The frame is property ID BE32, length `01`, then one value byte. The explicit target is
`com.byd.clusterdebug/com.byd.clusterdebug.BroadcastReceiverCAN`, action `com.byd.cluster.spi`.
Only this explicit manual broadcast includes stopped packages, so the manifest receiver can run
before its app has first been opened; no service is started.
No arbitrary frame, AutoContainer `13/88/89`, geometry change or guessed input `2` is exposed.

## Recovery and failure behavior

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

## Required vehicle validation

While parked on the researched firmware, check hide → restore, repeated manual restore, activity
recreation and app process restart. Confirm the pane returns under its normal OEM conditions,
and document the full-pane warning effect. Other firmwares remain unavailable until their
consumer and restoration contract are researched and tested.
