# D50F_LC self-ADB / proxy bootstrap incident — 2026-07-18

## Evidence

Device report: `D50F_LC for BYD AUTO`, product `trinket`, Android 13 / API 33, build `TKQ1.240913.001`.

The report proves four separate facts:

1. Android exposes display #2 as `fission_bg_XDJAScreenProjection`, PRESENTATION, ON, 720x1920, rotation 3.
2. TCP connect to `127.0.0.1:5555` succeeds.
3. Commands sent through the in-app dadb client do not complete: the uid-2000 display probe times out and every proxy bootstrap reports `ERR bootstrap timed out`.
4. Calling AutoContainer directly from the app uid is rejected with `SecurityException: Not allowed package: com.byd.dashcast`.

`DaemonBinderResolver ... retrieved from ServiceManager` refers to the separate MirrorDaemon binder, not the ProxyDaemon expected by `ProxyClient`; it does not contradict the proxy failure.

## Proven failure sequence

The old dadb 1.2.7 transport had no socket read timeout (`Timeout.NONE` in its shell stream). `ProxyClient.bootstrap()` stopped waiting after 8 seconds, but the underlying `dadb.shell(BOOTSTRAP_CMD)` continued to occupy an `adb-local-*` worker indefinitely.

The keeper then followed this loop:

- 8 seconds waiting for the bootstrap callback;
- 15 seconds waiting for a Binder that could not be published;
- 10 seconds before the next heartbeat retry.

Because a raw TCP probe succeeded, the old transport classifier never recorded the outage. Four indefinitely blocked commands could exhaust the fixed four-thread AdbLocal pool, which explains both:

- proxy daemon bootstrap never completing;
- SysInfo / Bug Report shell probes reporting `AdbLocalClient unavailable` or appearing stuck.

## Fix

### dadb 2.0.0

DashCast now uses dadb 2.0.0. Its API remains source-compatible for every API used by DashCast (`create`, `supportsFeature`, `shell`, `close`, `AdbKeyPair`, `AdbShellResponse`). DashCast does not use the 2.0-breaking install/push/pull/root result APIs.

dadb 2.0 adds:

- connect and socket read timeouts;
- a bounded socket write timeout;
- typed `AdbException` failures;
- wake-up of readers parked in `MessageQueue.take()` after connection failure;
- sequential stream IDs instead of collision-prone random IDs.

### Explicit timeout budgets

- TCP connect: 1.5 seconds.
- First real command in a process: 15 seconds, because `Dadb.create()` is lazy and the first command performs ADB authentication.
- Freshly generated key: 30 seconds for the system authorization dialog.
- Proven-healthy proxy bootstrap / AutoContainer command: 8 seconds idle, matching the historical caller budget.
- Small diagnostic probe: 7 seconds idle.
- Ordinary shell operation: 60 seconds idle.
- Large A13 bug-report dump: 120 seconds idle.

These are idle/progress timeouts, not total-duration caps: a long operation that continues producing ADB packets is not aborted.

### Typed transport state

The transport now distinguishes:

- `PORT_CLOSED`;
- `NO_LISTENER`;
- `KEY_UNAUTHORIZED`;
- `ADB_UNRESPONSIVE` (TCP accepted, but handshake or shell stopped answering).

The unresponsive state is the direct match for this D50F report. It is shown in SysInfo and in a localized one-time Toast.

### Retry ownership

- `KEY_UNAUTHORIZED` rechecks after 2 seconds, so accepting the system dialog recovers quickly.
- Closed/unresponsive transport rechecks once per 60 seconds to avoid the observed bootstrap storm.
- `ProxyKeeperService` owns those recovery probes.
- Production ShellGateway and sendInfo callers fail fast while a classified outage is active instead of filling the shell queue.
- The first classified failure now arms the 60-second recheck timestamp immediately; a second full attempt no longer occurs on the next 10-second heartbeat.

### Binder grace

A timed-out shell may theoretically have started the detached daemon just before transport failure. DashCast therefore keeps a two-second Binder grace after a classified bootstrap failure. Normal cold spawns retain 15 seconds; daemon rebroadcast retains 5 seconds.

### Bug report recovery

- The Bug Wizard skips shell-based app detection when ADB is already classified down.
- Its cluster display lookup is dynamic: PRESENTATION first, so D50F display #2 replaces the old hard-coded display #1 assumption.
- BugReportCapture immediately creates the guaranteed journal-only report on a background thread when ADB is classified unreachable.
- SysInfo prints the typed transport state and diagnosis instead of only `5555 OPEN` plus an unexplained timeout.

## What this does and does not fix

This patch fixes:

- permanently occupied AdbLocal workers;
- repeated 8+15+10-second proxy bootstrap cycles;
- missing sendInfo error callback (ClusterManager can now execute its existing display-resolution fallback);
- Bug Wizard app-detection stall;
- Bug Report failure to reach its journal-only fallback;
- misleading `port OPEN` diagnostics with no ADB protocol state.

This patch cannot make an OEM adbd execute commands if it remains unresponsive. On D50F:

- AutoContainer rejects the app uid;
- cross-display launch and privileged SurfaceControl need uid 2000;
- if dadb reports `ADB_UNRESPONSIVE`, physical projection may still be unavailable until Android debugging/adbd is restarted or reauthorized.

The D50F `1000/18 -> wait 6 s -> 16/35` sequence remains diagnostic-only. Its production use is deliberately deferred until an on-car run proves that the physical instrument panel changes; Android display #2 or a screenshot is not sufficient proof.

## Required field check

1. Install the test build and cold-start DashCast.
2. If an ADB authorization dialog appears, accept it with “always allow”.
3. Open SysInfo and export the report.
4. Confirm section 7 contains a transport state, not an unexplained timeout.
5. Try cluster activation once.
6. If transport is healthy, confirm `ProxyClient daemon ready` and a successful uid-2000 probe.
7. If transport is `ADB_UNRESPONSIVE`, restart Android debugging/adbd on the head unit, reopen DashCast, and repeat.
8. Open Bug Report while transport is intentionally unavailable; the wizard must remain usable and produce a journal-only report promptly.

## Local validation

- dadb upgraded from 1.2.7 to 2.0.0.
- Java 17 compilation, Android desugaring, D8, and APK packaging: successful.
- Full JVM suite: 113 tests in 36 suites, 0 failures, 0 errors, 0 skipped.
- Silent TCP peer integration test: dadb exits within the configured socket timeout and is classified `ADB_UNRESPONSIVE`.
- Focused transport, timeout-budget, proxy Binder-wait, retry-cooldown, Binder-versus-ADB routing, and display-selection tests: successful.
- Android lint: 0 errors, 0 warnings, 17 pre-existing informational layout hints.
- All translatable keys are present in the 12 alternate locale folders.
- Release candidate uses `1.6.137-beta` / versionCode 578.
- Clean-build APK: `DashCast-v1.6.137-beta-debug.apk`, 21,225,173 bytes, SHA-256 `3020775cdcf2f6e2ce3a83aff2f6918d2377bac473f0cbc80f6efed23aabd894`.
