# Read the OEM RCC selection across the host boundary

The instrument renderer runs in the firmware's host environment; the Android
head unit can have a different process namespace. A working cluster therefore
does not imply that the shell daemon can enumerate `qtandroidnative` or read its
`/proc/PID/root`. The renderer-first experiment still refuses this inaccessible
identity; it has not established that the OEM rejected an RCC.

Protocol 33 adds a separate **Read OEM RCC selection (read only)** action in
**Diagnostic → Cluster Full Screen**. It asks the researched Android Java service
for the selected translation resource without enumerating either the Qt or Java
process. This fixes the unnecessary renderer visibility dependency for the query.
It does not remove any identity requirement from loading/restoring a theme or
establish the shared-storage mapping required to stage a replacement RCC.

The existing explicitly connected daemon is required. There is no reconnect,
daemon bootstrap, UID/namespace switch, permission change or native-service
fallback. Registration and the single type-8 `cmd=1/sub=4` query are submitted
through the pinned Java `AutoContainer` Binder. OEM admission remains effective.
No resource, selection or recovery request is changed; no carrier is created.

## Identity and response scope

The short-lived query helper verifies the installed
`com.xdja.containerservice` package with PackageManager: UID 1000, system install,
absolute source path, no splits, stable package source/update metadata, stable
regular-file device/inode/size/mtime, and the exact researched APK SHA-256:

`1b9eef499db47d19a847b77aeaebf823f47449e9d0bc047793aa4433da9a0be9`.

It reads the APK directly at its PackageManager source path. This proves the
installed package bytes inspected here, not which native libraries the remote
host renderer mapped. The Binder descriptor must be `android.os.IAutoContainer`;
the helper pins that handle and watches its death without replacing it. Callbacks
must have UID 1000, descriptor `android.os.IContainerCallback`, bounded type-8
payloads and a supported `1001/4` selection shape. The APK is verified again
before reporting received selection. Native renderer identity remains **UNKNOWN**.

Registration return, query native return and selection callback are distinct
fields. A type-8 callback is broadcast to registered consumers and this protocol
contains no query nonce. A callback received within the local helper window is
a selection observation; it is not proof that our request
exclusively caused that response. The receipt's epoch timestamp is local receipt
time, not source freshness. No reply establishes physical pixels or permission
to load our theme. An unknown UI version is reported as observed, not accepted as
the identity required by the mutating experiment.

The helper owns a private lease and fresh callback Binder, with atomic ready
publication, a parent GO pipe and an eight-second self deadline. The daemon
bounds startup/output/wait, reaps only its owned Process handle and never retries
a query. Only the fresh private lease files are cleaned. Native FDs use direct
`Os.read`/`Os.write` and `Os.close` in `finally`. An unconfirmed child cleanup
blocks further query/test admission. Deadlines can expire after suspend; they
are not a hardware watchdog.

Queries share the existing serialized daemon command lane, refuse active tests,
recovery or quarantine, and never alter the test journal, filename, mutation,
load settlement, default selection or observation flags. Protocols 31/32 and their
strict mutating helper remain unchanged. The query receipt is independent of the
older physical-test status shown underneath it.

## Reports and user workflow

The new result is included in the existing bounded historical cluster cache and
normal report export, including the separate native/registration results, query
phase, selection, local receipt timestamp and unknown native identity. Reports
read this cache; opening the report assistant sends no new cluster query.

**Send a diagnostic report** is available in Cluster and Reporting. It opens the
existing report wizard, preserving its consent, configured reporting channel and
file-sharing fallback. It does not silently send Telegram messages. The new
query/report labels and explanations are translated in all 13 app locales.

## Static firmware evidence

Target firmware: user-declared `13.1.33.2602030.1`, analyzed SX326 corpus.
The Java APK identity above is exact; no other corpus is substituted.

- Registration keeps an `ArrayMap<IBinder, AppCallback>` keyed by the fresh
  `callback.asBinder()`, then installs that token's death recipient. It does not
  replace another consumer's callback. Historical REA:
  `ev_020a8e38f192a387331101eb31ee6db2f5fc91b2b4f0d81a057937943b79d204`.
- `dispatchReceivedInfo2` iterates all registered callback values and filters
  their allowed type. Fresh REA:
  `ev_58225b0504a1bc5767bab9a6827692b66aa375383a9f55ab4434c7e563f35193`.
- `AppCallback.binderDied` passes only its own `mAppToken`:
  `ev_c201cc2f4ef51aaa9462d459c776821949a4ba804c5d972cbaaf570dd9f6de90`.
- `handleCallbackBinderDied` removes only that token under the lock:
  `ev_7af7eb773ca3c5dbbecfa2e9e5b7b86f011339508fe109d2da26a88a9698547f`.
- Native selection-query semantics (`cmd=1/sub=4`, response `1001/4`):
  `ev_96c25d56ea8d291732657097204f0a652adde66e8153a40e13e57a8075da1d43`.

Fresh callback evidence is retained in
`REA-Cluster-ReadOnlyQuery-20261023/rea-java-callback-evidence.json` beside the
existing firmware analysis. The older `REA-Cluster-LiveTest-20261022` dossier
documents the Java-to-native relay and query body. These are static observations;
no Binder call, vehicle, APK target or firmware code was executed during analysis.

## Validation boundaries

Local tests cover the fixed query payload/no replay, registration/native errors,
Binder death, malformed/wrong-UID/early/late callbacks, identity failure/recheck,
real PackageManager UID/source/split/SHA refusal with native FD cleanup on API 29,
query/test isolation and historical report retention. UI tests cover the report
wizard shortcuts. Existing R8 `proxy.**` keep rules preserve the helper entry point;
the optimized APK must retain its static `main(String[])` declaration.

These checks do not validate the actual Binder service owner, OEM authorization,
native compatibility, selected RCC rendering, physical alerts or vehicle recovery.
