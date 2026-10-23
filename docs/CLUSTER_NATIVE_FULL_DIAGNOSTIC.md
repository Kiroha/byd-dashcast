# Manual native BYD Full-mode diagnostic

Firmware evidence: 13.1.33.2602030.1, `libBydDataSource.so` SHA-256
`1bda5155149d5131af36a8ee2c5e1b4f944738a10b5b7bbafcf4a3945d6ff91d`.
This is a mode request for the existing OEM QML, separate from RCC loading.

## Evidence and meaning of a result

REA decompilation `BusinessUi1::clusterDebug` (`ev_01f79aa1df442b7faf3fe7ee7920d2c7a719210dc5fc97ca8df738433f57d3db`)
shows decimal 16 setting internal navigation state 4 and calling `updateNaviDisplay`;
decimal 18 sets internal state 0 and calls the same method. These selected branches
do not write the ADAS mask or shape preset. Normal `updateNaviType` has an early
return when `g_productIndex == 1`; the debug branch calls `updateNaviDisplay`
directly. The installed product index remains unknown.

`clusterDebug` rejects calls less than one second apart and refreshes its shared
timestamp even on rejection. The diagnostic waits at least 1.5 seconds before its
single send. Other OEM processes can still affect that shared timer.

`updateNaviDisplay` sends plugin message 15/value 1. `msgDataUpdate` plugin 41/value 1
applies the public navigation type; value 2 completes animation and resumes
updates. The diagnostic does not manufacture these QML acknowledgments. A Binder
integer, including zero, does not prove Qt delivery, debug-branch execution,
animation completion or rendered pixels.

The exact Java AutoContainer APK relays `sendInfo(type,int,string)` unchanged
after OEM admission. The native endpoint requires UID <= 1000, so the UID 2000
helper uses the Java `AutoContainer` service, descriptor `android.os.IAutoContainer`.
It never transacts directly with the Fission/native service. A callback can also
return zero without proving that the intended Qt receiver ran.

Retained source: `REA-Cluster-NaviGate-20261023/verification.json` and
`transport-verification.json` under the firmware extraction workspace. The native
transport REA evidence is `ev_11f1f7e15c17eaede964ef739060450ddecef96d0f31d7badac4824f3206e4a5`.

## Closed implementation

ProxyDaemon protocol 34 extends transaction 44 with closed operations:

| Operation | Payload | Effect |
| --- | --- | --- |
| 8 | fixed 16, nonce, parked assertion, firmware assertion, reservation Binder | One native Full request |
| 9 | fixed 18, nonce, same assertions and reservation | One explicitly requested normal-mode return |
| 10 | nonce | Fresh user observation after return completion; no OEM call |

No arbitrary debug payload, QML acknowledgment, logging-level change, service
bootstrap, RCC load, CAN mask frame, shape preset or refresh command 0 is added.
The two assertions are user declarations; movement is not measured. Positive
DiLink 3 identification, API 29 and the exact installed system Java APK hash are
required. Installed host native binaries and physical compatibility remain unknown.

The helper hashes the installed APK, pins the Java Binder and death recipient,
then announces readiness. The parent durably records its own helper PID generation
and native operational debt before admitting GO. The helper sends once, checks the
strict native integer reply, and rechecks identity. Its eight-second self deadline
and the parent's bounded process wait limit local lifetime. Killing/reaping a helper
does not cancel an already admitted OEM operation.

Native requests share the existing serialized command worker but have a separate
private journal. RCC fields and recovery journal retain their meaning. Full requests
refuse RCC operations/recovery, existing projection ownership, queued or in-flight
cleanup/ADAS work and previously admitted production mode calls. Manual return
cancels a Full request still waiting before GO and follows any already admitted
local request; it never overtakes that request. Unknown completion is not replayed.

The app retains an idle projection reservation throughout operational debt.
A dedicated durable, negative-only app flag blocks legacy, in-process and broad
restore egress across app restarts; the daemon independently gates typed type 1000
calls. Recording failure prevents the initial IPC. A flag left before an initial
IPC can be recovered by an explicit normal request, without replaying 16. A server
refusal proven before admission can clear that exact new app nonce.

After the manual 18 request has completed and all local helpers are reaped, a fresh
nonce-bound user observation may close operational debt. It remains an observation,
not a verified QML/render acknowledgment or proof against a delayed OEM effect.
Normal mode is requested; the exact prior mode is not restored. Restarting the app
or daemon never automatically sends either command or discards outstanding debt.

## Logs and tester path

Open **Diag → Cluster Full Screen → Tester le mode Full natif BYD** while parked.
The distinct pinned **Demander le retour BYD** requests normal mode. Once a request
has finished, a popup asks what is visible on the physical cluster. The native Full
question explicitly describes projection with the OEM top and bottom bands. After
18 finishes and the local helpers are reaped, the normal-layout question appears
automatically; only its positive answer submits the existing nonce-bound operation
10. No / not yet, unknown, Back or dismissal preserve the operational debt. A fresh
CLOSED daemon receipt with the same remaining app nonce can also reconcile an
interrupted local flag clear through the existing idempotent operation 10.

The cluster page shows at most one visual question, prioritizing normal-mode return.
Repeated passive status polls do not repeat answered questions or dismiss an open
one. **Vérifier de nouveau l’affichage** reopens an unanswered/negative observation.
Leaving the page or pausing dismisses questions; returning reads only status from an
already connected daemon, then offers any currently eligible observation. A new
nonce, phase, profile, filename or replaced Binder invalidates stale acceptance.
Positive answers are rechecked against fresh status on the exact pinned connection.

RCC marker, test-theme and original-layout observations are guided in the same way.
An RCC attempt blocked by native recovery explains the required normal return or
visual confirmation in a popup. Confirmation never automatically starts another
RCC load or sends 16/18. Prompt context, shown/cancel/negative/unknown/positive and
invalidation events are retained in the bounded diagnostic history.

**Envoyer un rapport de diagnostic** is at the top of the cluster page and opens
the existing report wizard. No copy/paste or automatic send is required. Results
also remain in the app-private historical cache, separate from admission flags.

Reports retain a bounded cumulative trace (32 steps / 6 KiB), separate essential
16/18 receipts, nonce, epoch and monotonic times, quiet window, reservation check,
owned helper identity, durable GO admission, installed Java identity verification,
descriptor, attempted transaction, native integer/exception phase and child reap.
Partial helper STEP output is retained when a final result is missing. No unrelated
process arguments, arbitrary Parcel payloads or broad vehicle log collector are added.

Useful OEM strings to correlate if an existing host log is available are
`clusterDebug intervalMs < 1000`, `Navi power NAVI_TYPE_FULL_SCREEN`,
`NAVI_ANIM_START` and the animation completion path. Host log visibility is not
assumed. Runtime product index, debounce acceptance, Qt delivery, QML ACK and
physical pixels remain UNKNOWN unless independent runtime evidence establishes them.

No vehicle execution was performed during development. Static/JVM checks cannot
establish cluster compatibility, telltale legibility or successful physical return.
