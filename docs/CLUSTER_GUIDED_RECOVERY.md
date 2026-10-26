# Guided cluster diagnostics and explicit maintenance restart

Implementation checkpoint: 2026-10-26. This change introduces a guided Android
UI and a guarded restart request; it does not validate recovery from failed QML
on a vehicle. The offline minimal/full-screen research artifacts remain
unavailable for loading. The retired full-app profile remains recovery-only.

## User flow

Open **Diag → DashCast dashboard**. The main page shows a concise current state,
a read-status action and the applicable next action. Previous load tests, raw
receipts and native diagnostic controls are under **Show details and previous
tests**. The fixed **Restore cluster** action stays available outside the scroll
area, including when an ordinary diagnostic is busy.

1. Tap **Restore cluster**. DashCast reads the currently connected daemon and
   closes its projected resources. No reboot occurs from this tap.
2. Complete the parked/recovery confirmation. Existing manual recovery is
   requested. Moving a carrier still requires the existing fresh exact-own
   selection and sealed ACK3 `RESOURCE_IS_SAME` eligibility; this change does not
   invent permission when Qt or the helper cannot answer.
3. If retirement is eligible, follow the separate preparation prompt. Only the
   journal-owned DashCast RCC is moved into a new hidden recovery directory. Its
   bytes, device/inode and hash are preserved; factory and foreign resources are
   never removed. A failed or incomplete move blocks the restart path.
4. After retirement, the wizard obtains a fresh file proof. It checks the retired
   inode/hash, absent original name, pinned directory generations and a bounded,
   successfully enumerated lookup directory with no remaining DashCast-prefixed
   entry. Another owned entry, a link under that prefix, a malformed owned name,
   unreadable directory, changed file, active helper or unresolved resource blocks
   the reboot popup. This is a bounded observation, not a lock against an external
   privileged process writing afterwards.
5. The popup separately explains interruption of the screens/ADB and requires a
   new assertion that the vehicle is immobilized in P with the parking brake on.
   **Accept restart** first authenticates a local ADB connection, then rechecks
   the exact proof/round, runtime cohort and files on the pinned daemon. Only
   after durable intent checkpoints does it send one fixed ADB service request:
   `reboot:fission-all`. Cancel sends no restart.
6. Wait for the physical restart. A timeout/disconnect is an unknown outcome,
   not failure and not permission to retry. After reconnecting explicitly, tap
   **Restore cluster** to run the existing retired-file/default-selection check,
   then answer the fresh question about the actual BYD instruments and alerts.
   Restart debt clears only after a changed boot, authoritative existing recovery
   checks and the user's physical confirmation.

The user assertions do not establish measured movement. Unknown movement is never
converted to parked by the application. Setup and recovery require a suitable
stationary test context.

## Authorization, lifecycle and failure behavior

- ProxyDaemon protocol **45** adds closed operations 27 (prepare), 28 (confirm)
  and 29 (cancel). They manipulate verification/intent only and never reboot the
  daemon or native renderer. Old daemons cannot offer the in-app restart.
- The existing authenticated local Dadb connection opens the fixed ADB service;
  this is not `shell:reboot`, `svc power reboot` or a Binder REBOOT permission
  bypass. No country-code file or MCU configuration is changed.
- Both file proofs require API 29, an admitted historical `21f7735c` carrier
  variant, and exact current `libBydCluster.so` and `fissiond` hashes from the
  researched cohort. Unreadable/mismatched identities refuse without fallback.
- A two-minute unconsumed confirmation lease excludes other live-test mutations.
  A lost preparation reply expires safely: its old round cannot dispatch, and a
  new explicit Restore can verify again. A consumed round never expires or
  cancels into permission to replay. Existing RCC debt also blocks production
  projection/mode commands throughout recovery.
- Pinned Binder continuity, foreground activation and projection ownership are
  checked again after local ADB authentication. Page exit, replacement connection,
  stale popup, repeated taps and file reappearance cannot authorize a request.
- Daemon dispatch intent is fsynced in the existing recovery journal before
  authorization returns. The app separately commits its exact filename/round/
  boot intent before ADB OPEN. Process death and uncertain output retain debt.
- A CLEAN transaction records a correlated historical confirmation receipt with
  round, previous boot and confirmed boot. If the daemon dies after that durable
  transaction but before the app clears its intent, a fresh pinned CLEAN receipt
  can reconcile only that exact intent in that same confirmed boot. It is not a
  statement that current physical pixels have been inspected again.
- Read-only status and the explicit retired-file verification/physical confirmation
  remain available during a consumed hold. Neither reconnect, a selected default,
  a successful ADB OPEN nor a boot tag alone marks restoration complete.
- Filesystem checks cannot prevent an unrelated privileged writer after the last
  check. The existing same-filesystem rename is not an atomic no-overwrite
  operation against such a writer. Post-move verification detects inconsistencies
  and blocks restart; this code does not promise guaranteed hardware recovery.

The successful historical external maintenance trial was from normal OEM
instruments. This newly integrated in-app local ADB route and its complete
failure-recovery sequence still require parked vehicle validation. No car command
was sent while implementing or testing this change.

## Verification scope

JVM/Robolectric checks exercise the actual guided controls, cancellation,
connection replacement, consent ordering, revalidation refusal and disconnects;
the production fixed-service adapter rejects second dispatch even after failure.
Temporary-filesystem tests cover residual names/links, preserved bytes/inode,
unreadable inventory and changes during enumeration. Journal/intent tests cover
strict restart metadata, persistence, boot correlation and the CLEAN crash window.
These checks establish software behavior, not OEM display recovery or road use.
