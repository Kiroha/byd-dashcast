<!-- rtk-instructions v2 -->
# RTK — Token-Optimized CLI

**rtk** is a CLI proxy that filters and compresses command outputs, saving 60-90% tokens.

## Rule

Always prefix shell commands with `rtk`:

```bash
# Instead of:              Use:
git status                 rtk git status
git log -10                rtk git log -10
cargo test                 rtk cargo test
docker ps                  rtk docker ps
kubectl get pods           rtk kubectl pods
```

## Meta commands (use directly)

```bash
rtk gain              # Token savings dashboard
rtk gain --history    # Per-command savings history
rtk discover          # Find missed rtk opportunities
rtk proxy <cmd>       # Run raw (no filtering) but track usage
```
<!-- /rtk-instructions -->
## Subagent shell command rule

Every subagent prompt **must** include the following block verbatim to enforce the `rtk` rule:

```
### Shell command rule (mandatory)
All shell commands — including git, gh, grep, ls, pytest, python, and any other CLI tool —
must always be prefixed with `rtk`. This rule is mandatory and cannot be bypassed.
Examples: `rtk git status`, `rtk grep -R`, `rtk gh pr create`, `rtk pytest tests/`.
```

---

# Dashboard Playwright Tests

**IMPORTANT:** Always ask the user to start the dashboard before running Playwright tests.
The app must already be running before opening the browser page.
- Live mode: `python dashboard/app.py --live` -> `http://localhost:8050`
- Static mode: `python dashboard/app.py --port 8051` -> `http://localhost:8051`

Do NOT start the app automatically - ask the user to launch it first, then wait for them to confirm it's running.

**Preferred test method:** curl POSTs to `/_dash-update-component` (reliable, no browser needed).
Playwright/browser tests only for visual/interaction checks that curl can't cover.

**Credential files** (project root - required for the app to start):
- `devices.json`
- `hub.json`
- `thresholds.json`

## Known issues
- VS Code embedded browser aborts concurrent XHR with `ERR_ABORTED` - always test in a real browser, not the VS Code tab.
- Live mode "stuck on Updating...": fixed via `threaded=True`, startup `_initial_agg` computation, Gantt cap at `5min` bucket, and dropping all-NaN columns in `resample_devices()`.

---

# RTK - Token-Optimized CLI

**rtk** is a CLI proxy that filters and compresses command outputs, saving 60-90% tokens.

## Rule

All shell commands, including `ls`, `grep`, `gh`, and others, must always be prefixed with `rtk`. This behavior is mandatory and cannot be bypassed. For example:

```bash
# Instead of:              Use:
git status                 rtk git status
git log -10                rtk git log -10
cargo test                 rtk cargo test
docker ps                  rtk docker ps
kubectl get pods           rtk kubectl pods
```

Additionally, ensure that every shell command executed adheres to this rule. Use `rtk` to optimize token usage and track command efficiency.

## Meta commands (use directly)

```bash
rtk gain              # Token savings dashboard
rtk gain --history    # Per-command savings history
rtk discover          # Find missed rtk opportunities
rtk proxy <cmd>       # Run raw (no filtering) but track usage
```
````