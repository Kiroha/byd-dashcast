# False ADB-unresponsive Toast incident — 2026-07-18

## Scope

Report: `INC-20260718-102309`, DashCast 1.6.138-beta, DiLink 3 / Android 10.

The user saw the localized warning asking to restart Android debugging/adbd, even though the automatic Layout, Waze, NewPipe, MirrorDaemon, and ProxyDaemon continued to work.

## Proven false positive

- 10:22:21.946 — FissionOrchestrator requested MirrorDaemon startup.
- 10:22:23.455 — the registered MirrorDaemon Binder was acquired after 1.5 seconds.
- 10:22:24.369 — an independent AdbLocal screenshot-prune command completed successfully.
- 10:22:27.618 — Waze `launchAndForce` completed.
- 10:22:27.707 — Layout mirror started successfully.
- 10:22:29.372 — AdbLocalClient marked the entire transport `ADB_UNRESPONSIVE` and displayed the Toast.
- 10:22:29.373 — the underlying error was `MirrorDaemon startup error [AdbTimeoutException: Read timed out on stream 3]`.
- 10:22:30.533 — NewPipe `launchAndForce` completed.
- 10:22:34.366 and 10:22:54.372 — ProxyDaemon shell commands succeeded.
- 10:22:54.418 and 10:23:01.597 — direct AdbLocal shell commands succeeded again.
- Android's own adbd log reported the key as `alwaysAllow=true` and `adb client authorized`.

This is incompatible with a dead or unauthorized adbd.

## Root cause

`startMirrorDaemon()` used one dadb connection for sequential streams:

1. process lookup;
2. old-log pruning;
3. detached `setsid ... app_process64 ... &` launch.

The daemon process and Binder were created, but the third shell stream did not close before the seven-second read timeout. The catch path treated every `AdbTimeoutException` as proof that the complete ADB transport was unresponsive and immediately displayed the global warning.

A stuck command stream is not equivalent to a dead ADB protocol. The former can coexist with successful independent streams, exactly as this report proves.

## Fix

### Deterministic MirrorDaemon launch

- MirrorDaemon startup is single-flight process-wide; concurrent callers join the existing attempt instead of opening competing dadb sessions.
- The `setsid` launcher now redirects the launcher's own stdin/stdout/stderr, not only the inner `app_process64` command.
- The outer shell emits an explicit `STARTED` marker and can close its ADB stream immediately.
- APK/log/symlink paths are POSIX single-quote escaped.
- The generated command has a non-executing `/bin/sh -n` syntax test.

### Binder-aware startup result

- If the launch stream times out but `ServiceManager.getService("byd_mirror_daemon")` returns a live Binder, the requested daemon startup is considered operationally successful.
- That isolated timeout is logged, but it does not classify ADB globally and does not display a Toast.
- Authentication/refused/listener failures are not suppressed by a live MirrorDaemon Binder.

### Independent transport confirmation

- `PORT_CLOSED`, `NO_LISTENER`, and `KEY_UNAUTHORIZED` remain immediate diagnoses.
- A first `ADB_UNRESPONSIVE` candidate starts a new dadb connection with a three-second idle budget and runs `echo __DASHCAST_ADB_HEALTHY__`.
- If the independent echo succeeds, the original failure is classified as a stream-local timeout, transport state is cleared, and no Toast is displayed.
- If the independent probe also fails, its typed result confirms or refines the global state and the existing circuit-breaker/Toast behavior remains.
- The confirmation path calls Dadb directly and cannot recursively enter transport classification.
- Only one confirmation probe may run at a time.
- A monotonic success generation prevents an older failed probe from overwriting a newer command that already proved the transport healthy.

### Symmetric legacy helpers

Overlay grant, cluster restore, origin restore, signature dump, force-stop, normal shell, report shell, and sendInfo now all:

- clear sticky transport state after a successful ADB command;
- route failures through the same independent timeout confirmation.

## Genuine D50F preservation

The D50F silent-peer case remains classified:

- TCP can accept the connection;
- the first command times out;
- the independent echo on a fresh connection also times out;
- `ADB_UNRESPONSIVE` is therefore confirmed and the 60-second recovery circuit-breaker remains active.

The existing silent TCP peer integration test continues to prove dadb workers cannot block forever.

## Validation

- Healthy independent echo suppresses a stream-timeout outage.
- A second timeout confirms `ADB_UNRESPONSIVE`.
- An authentication response refines the state to `KEY_UNAUTHORIZED`.
- Live MirrorDaemon Binder suppresses only timeout classification, not auth/refused failures.
- A newer successful command invalidates an older failed confirmation.
- Detached launcher descriptors and explicit marker are tested.
- Generated launcher parses with `/bin/sh -n` without execution.
- Full JVM suite with the Waze guardian fix: 139 tests in 44 suites, 0 failures, 0 errors, 0 skipped.
- Android lint: 0 errors, 0 warnings, 17 pre-existing informational layout hints.
- Java/Kotlin compilation, desugaring, D8, and debug APK assembly: successful.
- Release candidate: `1.6.139-beta` / versionCode 580.
- Clean-build APK: `DashCast-v1.6.139-beta-debug.apk`, 21,240,817 bytes, SHA-256 `fc9792bc23e71927369c5ddc8f83a09dd642975393c33ae5b9359b1aa146759d`.

## Field acceptance

1. Start the automatic Waze + NewPipe Layout after a hard restart and after long uptime.
2. Confirm MirrorDaemon Binder acquisition does not later produce the restart-adbd Toast.
3. If the launch shell stream times out after the Binder appears, verify the journal contains `suppressing global ADB outage`.
4. Trigger a long/failed ordinary shell while another shell remains healthy; verify the independent echo suppresses the global warning.
5. Disable or wedge local adbd deliberately; verify both the original operation and independent echo fail, then confirm the actionable `ADB_UNRESPONSIVE` warning still appears once.

No software change can guarantee transport health when the OEM daemon itself is dead, but a user-facing ADB outage is now emitted only after an independent protocol-level confirmation.