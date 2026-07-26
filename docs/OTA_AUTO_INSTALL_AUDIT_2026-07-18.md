# OTA automatic install and relaunch audit — 2026-07-18

## Reported behavior

The OTA download completes, Android displays its package-installer confirmation, the user taps **Update**, and DashCast does not reopen automatically.

That behavior proves the preferred uid-2000 path did not complete. The Android confirmation UI is only entered through DashCast's `PackageInstaller` fallback.

## Root causes in the pre-fix implementation

1. `UpdateChecker` used the silent path only when `ProxyClient.isConnected()` was already true at the exact install instant. It never attempted `ProxyClient.connect()`. A cold, reconnecting, or temporarily absent Binder therefore forced the interactive fallback even when local ADB was healthy.
2. The daemon passed the APK's app-specific external-storage path directly to `pm install`. Shell visibility of `Android/data/<package>` differs across Android/FUSE implementations.
3. The `PackageInstaller` session left API 31+'s user-action policy unspecified. For an installer using `REQUEST_INSTALL_PACKAGES`, Android treats that as requiring user action.
4. `InstallResultReceiver` only logged `STATUS_SUCCESS`; it did not launch DashCast.
5. `BootReceiver` handled `MY_PACKAGE_REPLACED` by reviving the proxy/keeper and optional projection, but it did not reopen the launcher UI.
6. Declaring `INSTALL_PACKAGES` is not proof that a particular OEM ROM grants it. The runtime grant and package-installer policy are authoritative.

## Implemented behavior

### Preferred uid-2000 path

- The OTA background worker attempts `ProxyClient.connect()` when no Binder is currently cached.
- The connected daemon stages the downloaded APK under `/data/local/tmp` before installation.
- Staging first tries a direct shell read, then `run-as com.byd.dashcast` for debuggable builds. The staged byte count must exactly match the downloaded file before `pm install` can run.
- Every error path removes the temporary APK.
- `pm install -r` remains the only install mutation. Automatic downgrade (`-d`) is deliberately forbidden.
- The daemon survives replacement of the app process, waits for package installation, and starts the launcher. It skips a duplicate shell launch when DashCast is already the resumed activity.

### PackageInstaller fallback

- API 31+ sessions explicitly request `USER_ACTION_NOT_REQUIRED` and declare `UPDATE_PACKAGES_WITHOUT_USER_ACTION`.
- DashCast logs whether `INSTALL_PACKAGES` is actually granted at runtime.
- `STATUS_PENDING_USER_ACTION` remains supported and launches the OEM confirmation screen when Android requires it.
- Before either install path starts, DashCast durably records a time-limited relaunch marker with synchronous `SharedPreferences.commit()` so package replacement cannot erase the request.
- `InstallResultReceiver` consumes the marker after `STATUS_SUCCESS`.
- `BootReceiver` independently consumes it after `MY_PACKAGE_REPLACED`, covering ROMs that do not deliver the final session callback to the replacement process.
- Marker claiming is synchronous and process-local serialized, preventing the two receivers from opening duplicate Activity tasks.
- A failed background launch restores the marker so the other receiver can retry.

## Platform feasibility matrix

| Platform | Silent install | Automatic reopen |
|---|---|---|
| DiLink 3/4 with healthy uid-2000 proxy | Expected through staged `pm install -r`; no Android confirmation | Expected through replacement receiver and daemon launcher fallback |
| DiLink 5 / Android 12 with healthy proxy | Expected through daemon; `PackageInstaller` may also qualify for no-user-action self-update | Expected |
| Android 13 with effective `INSTALL_PACKAGES` | PackageInstaller may be silent because the privileged permission is authoritative | Expected |
| Android 13, targetSdk 29, no effective `INSTALL_PACKAGES`, but healthy proxy | Expected through daemon `pm install` | Expected |
| D50F with `ADB_UNRESPONSIVE`, no ProxyDaemon, and no effective `INSTALL_PACKAGES` | **Impossible to guarantee silently.** Android 13 requires a target SDK of at least 30 for the normal `USER_ACTION_NOT_REQUIRED` self-update exemption; DashCast intentionally remains targetSdk 29 for DiLink compatibility. The OEM confirmation tap remains required. | Expected after the user's confirmation |

The target SDK is not raised as part of this fix because moving from 29 to 30 changes Android storage, package visibility, background execution, and other runtime compatibility behavior across every supported DiLink generation.

## Deployment caveat

An OTA update is executed by the version currently installed, not by the APK being downloaded. Therefore, the first update **to** a release containing this fix still runs the previous updater and may require the historical confirmation/reopen flow. Once the fixed release is installed, subsequent OTA updates exercise the new installer and relaunch paths.

## Validation

- Focused tests cover APK staging order, exact-size validation, shell quoting, POSIX shell syntax, launcher fallback, marker expiry, and stable/beta/build version comparisons.
- Full JVM suite: 122 tests in 39 suites, 0 failures, 0 errors, 0 skipped.
- Android lint returned to 0 errors and 0 warnings after documenting the intentional synchronous marker commit.
- Kotlin/Java compilation, manifest merge, desugaring, D8, and debug APK assembly succeed with minSdk 28.
- The merged manifest contains `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, `InstallResultReceiver`, and `MY_PACKAGE_REPLACED`.
- Release candidate: `1.6.138-beta` / versionCode 579.
- Clean-build APK: `DashCast-v1.6.138-beta-debug.apk`, 21,229,677 bytes, SHA-256 `17c42c183694d6fa06e63bfcbd865a8c326f611c0b7548f4b3a569f56a19beac`.

## Required vehicle validation

1. Install the first build containing this fix manually if necessary.
2. Publish/install a higher-version test APK through the OTA channel.
3. On DiLink 3/4 with a green Proxy ADB Daemon, tap **Update now** and verify no OEM installer UI appears.
4. Confirm DashCast closes during replacement and reopens on its own.
5. Check the journal for `daemon not connected — attempting OTA reconnect`, `daemon silent install: staging`, and the replacement relaunch source.
6. Repeat with the daemon deliberately unavailable. Confirm the OEM installer remains available and DashCast reopens automatically after the user confirms.
7. On D50F with `ADB_UNRESPONSIVE`, expect the OEM confirmation unless `INSTALL_PACKAGES=true` is logged; verify only the automatic reopen guarantee.