# Waze fission late-rebound incident — 2026-07-18

## Scope

Primary report: `INC-20260718-095242`, DashCast 1.6.136-beta on DiLink 3 / Android 10.

Comparison report: `INC-20260718-102309`, DashCast 1.6.138-beta after a hard restart on the same car. Only its successful Waze launch timeline is used here; its separate reported bug is intentionally out of scope.

## Proven failure

The incident is the automatic two-app Layout (`Waze + NewPipe`), not classic single-app projection.

In `095242`:

- 09:51:31.471 — Waze slot attached as display 4.
- 09:51:34.362 — `launchAndForce` found task 44 on display 4 and reported the watchdog started.
- 09:51:40.599 — Waze first created display-4 resources.
- 09:51:47.912 — Waze internally ran `launchToSide` and created `MainActivity` in task 44.
- 09:51:48.112 — `MainActivity` resources were created for display 0.
- No subsequent `setFocusedTask` / stack move returned task 44 to display 4.

The internal rebound therefore occurred about 13.55 seconds after the old watchdog was armed.

The old implementation nominally polled for ten seconds, but it could stop after only three target-display observations from T+2 seconds — approximately T+3.5 seconds. It also used one process-global `AtomicBoolean`, so only one app in a multi-slot Layout could actually own the watchdog even though every `launchAndForce` result claimed `WATCHDOG started`.

## Hard-restart comparison

In `102309`:

- 10:22:23.542 — Waze slot attached as display 2.
- 10:22:27.618 — `launchAndForce` found task 10 and armed the watchdog.
- 10:22:29.282 — Waze ran the same `launchToSide` transition.
- 10:22:29.637 — Waze temporarily created display-0 resources.
- 10:22:30.377 — `setFocusedTask` appeared.
- 10:22:31.239 — Waze resources were recreated for display 2.

The rebound occurred after only 1.67 seconds and was caught. No fission/watchdog implementation changed between 1.6.136 and 1.6.138; the hard restart changed process/ATM timing and made the transition fit inside the old window.

Display IDs 4/5 before restart versus 2/3 afterward are monotonic Android display allocation, not evidence of live leaked slots. The previous slots had been released.

## Fix

- Replaced the single global watchdog gate with generation tracking per package.
- Waze and NewPipe can now be guarded concurrently.
- Relaunching the same package supersedes only its previous guardian.
- The watchdog resolves the package on every poll and adopts a recreated task ID.
- When multiple tasks match one package, a known wrong-display task is selected before a task already in the slot.
- Unknown/temporarily absent task state cannot produce a premature stable verdict.
- The initial startup guard is 30 seconds, with 500 ms polls.
- Every rebound extends verification by 15 seconds, up to a hard 90-second bound.
- Re-anchoring no longer ends the guardian; repeated rebounds are corrected and rechecked.
- Re-anchor logs now include package, task, source/target display, poll, and each move/mode/resize/focus result.
- The result reports whether the watchdog actually started instead of unconditionally claiming it did.

## Teardown safety

A longer guardian must not fight intentional teardown. Proxy protocol v21 therefore adds the additive `TXN_CANCEL_FISSION_WATCHDOG` verb.

- `MOVE_TO_DISPLAY0` cancels the package guardian before moving the task.
- Direct `RELEASE_SLOT` also cancels it.
- Full `DEACTIVATE_LAYOUT` cancels every active guardian.
- Cancellation does not reconnect or delay teardown when an old daemon is active.
- Clients skip the transaction for protocol versions below 21; those daemons still run the historical short watchdog.
- Binder transaction codes have a reflection-based uniqueness regression test.

## Validation

- The exact incident sequence (27 correct-display polls followed by a T+14-second wrong-display observation) produces `REANCHOR`.
- Stable tasks remain guarded for the full startup horizon.
- Late and repeated rebounds extend verification.
- Transient ABSENT/UNKNOWN states do not stop the guardian early.
- Multi-task package selection prioritizes the wrong-display task.
- Waze and NewPipe registry generations coexist independently.
- Same-package relaunch supersession, per-package cancellation, and cancel-all are covered.
- Proxy Binder transaction codes are unique.
- Full JVM suite including the confirmed-ADB diagnostic fix: 139 tests in 44 suites, 0 failures, 0 errors, 0 skipped.
- Android lint: 0 errors, 0 warnings, 17 pre-existing informational layout hints.
- Java/Kotlin compilation, desugaring, D8, and debug APK assembly: successful.
- Release candidate: `1.6.139-beta` / versionCode 580.

## Field acceptance

1. Start the `Waze + NewPipe` automatic Layout under cold and long-uptime conditions.
2. Verify both apps receive independent `WATCHDOG started pkg=...` entries.
3. If Waze creates `MainActivity` on display 0, verify a `WATCHDOG re-anchor pkg=com.waze` entry follows and Waze returns to its slot.
4. Repeat several launches without a hard restart, including deliberately loading the head unit during startup.
5. Stop one slot, stop all slots, and deactivate the Layout; verify logs show guardian cancellation and no task returns to a released display.

This removes every identified software timing gap, but it cannot override an OEM Binder denial, a task removed by Android, or a dead privileged daemon. Those conditions must remain explicit failures rather than be described as a 100% platform guarantee.