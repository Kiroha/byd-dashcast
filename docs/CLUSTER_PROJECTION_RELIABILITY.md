# Cluster application projection: ownership and activation readiness

This investigation concerns an application projected onto the instrument cluster, not navigation
arrows on the windshield HUD. The known vehicle context is a BYD SEAL, Android 10 / DiLink 3,
SX361 firmware `6125f_1for2_USER_SIGN_SX361_202606100404_Q2700`. Analysis was offline; no commands,
application installations or projection experiments were performed on a vehicle.

## REA evidence

The retained evidence bundle is `analysis/projection-reliability-20261023-72mt4d1k/` in the external
research directory, outside this source repository. It contains `findings.json` and the complete
REA responses. Decompiled OEM code is not copied into this repository.

- Container APK SHA-256: `1b9eef499db47d19a847b77aeaebf823f47449e9d0bc047793aa4433da9a0be9`.
  REA evidence `ev_f9c9508c28ddf82a2e1c91626feef449da385cd22876c0205ad66bafad5a2593` inspects
  `com.xdja.containerservice.AutoDisplayService.updateDisplay`. The service creates, reuses or
  resizes a VirtualDisplay from the native producer Surface and geometry, without itself checking
  Qt projection mode or completion of command 16. Missing display information releases the display; an invalid
  Surface is rejected. The existence of a VirtualDisplay therefore does not establish that Qt is
  displaying the projected application. The upstream producer-to-render chain remains unproven.
- `libBydDataSource.so` SHA-256:
  `02dfc7f5aa046167a3d829c69ef8ee97fd301cf9081450b86366319566c10ae8`.
  REA evidence `ev_8fded3880b347307f2465603a590dea4e98173a5ce8dc26869825193102c7f33` inspects
  `BusinessUi1::clusterDebug`, Ghidra address `0x248090`, ELF offset `0x148090`. Its shared
  one-second debug-command debounce does not prove that projection commands `16/35/18/0` use
  this dispatcher. No new projection delay is inferred from this debug function.

## Corrected host behavior

The DL3 slow path previously polled for a display every 500 ms, while its first activation command
was scheduled after two seconds. An independently published display could be announced as ready,
and mark the cached Qt state active, before command 16 was even sent. DL3 readiness now requires a
successful command-16 callback for the current activation as well as discovery of its display.
Polling continues when a display arrives too early. Both late-arrival paths use the same guard;
success of command 35 cannot override a failed command 16. Cancellation removes queued success
delivery, and its main-looper generation check prevents an old result authorizing a new attempt.
The established fast path for an unchanged active Qt session remains available. DL2, DL4 and DL5
retain their existing activation branches and readiness behavior.

Queued commands belong to their projection session, including Stop. A superseded Stop that has
not entered transport is discarded instead of closing a replacement projection. An already
entered operation remains serialized: a newer command cannot overtake it, and the existing typed
transaction watchdog still waits for confirmed daemon recovery before advancing the queue.
Current Stop preserves `18 -> 0` and the existing full ADAS restoration sequence. Stop clears the
cached Qt mode immediately so an immediate restart cannot mistake a pending close for an active
projection.

The display helper captures session ownership for ready, timeout, late-ready and display-removal
callbacks. Repeated Start replaces its disconnect subscription; old callbacks and duplicate ready
delivery cannot change a newer projection. Display geometry is cleared only by its current owner,
atomically with session replacement; a never-started or retired manager cannot erase a newer
owner's daemon-discovered geometry.

Service Stop and destruction use the helper's captured owner for shared geometry and ADAS cleanup.
Delayed teardown of a retired Classic service cannot cancel a replacement layout's ADAS target.
The previous manual recovery fallback without a helper session remains available only when the
command bus atomically has no projection or diagnostic owner. A retired helper also reports no
usable display ID, preventing its service from launching or restoring DPI against a replacement
owner's display.

These changes add no OEM command, retry delay, transport, assumed display ID or vehicle permission.
ProxyDaemon continues executing commands; SurfaceDaemon retains ownership of its persistent
surfaces. ADAS masking policy, warning visibility and recovery frames are unchanged.

## Validation boundary

Host regressions cover early display publication, failed and late command-16 completion,
cancellation before queued success delivery, warm/fast paths, obsolete queued Stop, current
`18 -> 0`, duplicate completions, start-stop-start callbacks and geometry ownership. Real service
teardown regressions preserve a replacement owner's geometry and ADAS state, while current Stop
and destruction retain the full existing 40-frame restoration followed by commands 12 and 53. Existing
diagnostic reservation, typed-watchdog and ADAS lifecycle tests remain applicable.

A successful command callback and display discovery are host readiness requirements, not a
physical rendering acknowledgement. They do not establish vehicle movement or permission to
bypass OEM driving restrictions. On the researched firmware, parked-vehicle checks are still
required for repeated launch, immediate stop/restart, application replacement, suspend/resume,
native cluster return and existing ADAS recovery. Other firmware compatibility is unproven.

Existing service `stopMirror` and `release` behavior is unchanged. Service regression fixtures use
an inactive mirror manager, so they do not establish isolation between consecutive live preview
sessions in SurfaceDaemon. This change fences projection sessions, geometry and ADAS cleanup;
it does not establish that every preview lifecycle race is resolved.
