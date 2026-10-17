# Passive OEM bootstrap evidence export

DashCast 1.9.45-beta adds **DIAG → Relevés locaux OEM → Démarrage et identité du service OEM**. Use it while parked and keep DIAG visible. This is a targeted follow-up to the verified init-source corpus: a restart reference to `strategyservice` does not identify its native executable, active bootstrap configuration or Binder publisher.

Sources are read only. DashCast writes app-owned staging files, manifests and a ZIP on primary emulated storage. The existing EXEC transport may also create its temporary PID file. No source is executed, no OEM activity is opened and no OEM authentication/policy RPC, backend request, privilege change, setting change, reconnect or daemon bootstrap is performed. Runtime permissions and compatibility remain dependent on the vehicle ROM; JVM fixtures do not establish hardware compatibility.

## Evidence collected

The collector requires the already-connected ProxyDaemon to report UID 2000, protocol 26 or later and strict-read schema 1. Its Binder reference is pinned for the session. WHOAMI, capabilities, each EXEC metadata batch and each strict source chunk share `DiLinkContextDevice.executeScoped` and its physical-call quarantine, with exactly one Binder transaction per closure. A 35-second client timeout or malformed reply terminates acquisition; a late physical call can remain outstanding and keeps other diagnostics blocked. The late closure only fills private result holders and never writes files, updates UI or initiates another operation.

A bounded `ps -A -o PID,NAME` result selects namespace PID 1, the negotiated daemon PID, up to four exact `init` names and up to four exact `strategyservice` names. PIDs are numeric, sorted within name groups and deduplicated; suffixed names and executable-path lookalikes are excluded. Missing/truncated process inventory leaves only PID 1 and the negotiated daemon PID. There are at most ten selected PIDs. Names alone do not attribute a Binder publisher, and namespace PID 1 is not proof of host PID 1.

Each selected PID receives one fixed metadata batch. Raw `/proc/PID/stat` before and after the batch provides PID, command name and starttime brackets; changed, missing or malformed identity prevents attribution of the batch to a stable process. Bounded raw `cmdline`, `status`, `mountinfo`, and `readlink` observations of `root`, `exe`, `ns/mnt` and `ns/pid` are preserved as Base64, including NULs and non-UTF8 bytes. Proc files may have size zero and are read with bounded EXEC, never through the regular-file protocol. Links are recorded as metadata and are not traversed to discover sources. `/proc/self` observations belong to the individual command utility, not the pinned Binder daemon.

Fixed properties include hardware, boot init selector, fingerprint/API/device/board, slot suffix, boot mode and `init.svc.strategyservice`. Property values remain evidence; they never generate source paths.

## Closed regular-file scope

The exact 28 literal paths are also recorded in `plan.json`:

- `/init` and `/system/bin/init`;
- `/system/bin/strategyservice`, `/vendor/bin/strategyservice`, `/odm/bin/strategyservice`, `/product/bin/strategyservice`, `/system_ext/bin/strategyservice`;
- `/init.rc`, `/init.qcom.rc`, `/init.fission_host.rc`, `/init.fission_cells.rc`, `/init.environ.rc`, `/init.qcom.fission_host.rc`, `/init.qcom.fission_cells.rc`;
- `/system/etc/init/hw/init.rc`, `/system/etc/init/hw/init.qcom.rc`, `/system/etc/init/hw/init.fission_host.rc`, `/system/etc/init/hw/init.fission_cells.rc`;
- `/vendor/etc/init/hw/init.qcom.test.rc`, `/vendor/etc/init/hw/init.target.rc`;
- `/vendor/bin/init.qcom.class_core.sh`, `/vendor/bin/init.class_main.sh`, `/vendor/bin/init.qcom.sh`, `/vendor/bin/init.qcom.post_boot.sh`;
- conditional Fission candidates `/dev/cells/cell2/init`, `/dev/cells/cell2/init.rc`, `/dev/cells/.cellconf/cell2.conf`, `/data/cells/.cellconf/cell2.conf`.

The four startup wrappers come from the verified init catalog. The cell2 candidates follow static Fission path construction and an existing cell2 reference; their presence, readability, active-cell selection and actual loading are unproven. The `.conf` candidates follow two statically observed configuration helpers. The `init.rc` candidate is a bootstrap hypothesis, not an established loaded path. These few literal paths under `/dev/cells` are regular-file-only candidates; this does not relax global firmware device/mount policy.

Source metadata is requested in batches of at most four paths. Each batch is persisted and its readable sources are copied before the next batch. A late metadata timeout therefore preserves earlier completed copies. Symlinks, nonregular nodes and noncanonical paths are refused. Strict source reads use the existing `O_RDONLY | O_NOFOLLOW | O_NONBLOCK` regular-FD contract and six-field expected identity, checked before and after each read. No proc-root/executable link, import or directory is followed, and no recursion, chroot, setns, ptrace, root or SELinux workaround exists.

## Bounds and failure semantics

- Per PID: 4 KiB each for before/after stat and cmdline, 8 KiB status, 16 KiB mountinfo and 1 KiB for each of four links. Total raw cap is 40 KiB; encoded/framed metadata stays below 64 KiB per PID. Prefix bounds apply before Base64 encoding.
- Metadata has a conservative 1 MiB aggregate wire charge, precharged before each operation; failed/late operations retain their charge. Source metadata batches contain at most four literal paths. Denied, not-found, other failure, truncated and unobserved-exit states remain distinct, with the bounded original diagnostic bytes and exit status when available. An unavailable path does not prove global absence.
- At most 40 configured source paths (currently 28), 8 MiB per file, 32 MiB aggregate requested source bytes including failed reads, and 64 KiB strict chunks. Empty sources still require a real strict open/stat.
- Local documents are bounded to 4 MiB. A 128 MiB free-space reserve is required in addition to pending writes and the local ZIP allowance. Acquisition has an 18-minute soft monotonic budget and local finalization a 20-minute soft session budget. Non-cancellable physical calls may extend wall-clock duration.

**Arrêter les lectures** requests a stop after the current physical operation returns. Cancellation/deadline is checked again before consuming the returned metadata or chunk, so that result is not staged or finalized after the stop boundary. Previously completed copies and metadata remain available. Local ZIP/hash finalization is separate and its stop button is disabled. A failed source fragment stays outside the ZIP; cancelled `.part` fragments remain explicitly incomplete.

Completed source files use safe numbered IDs, with original paths, strict identity, byte size and SHA-256 in the manifest. ZIP construction streams and rehashes staged files. Failure to create a ZIP preserves staged evidence; a finalized ZIP survives a later integrity-sidecar failure. `integrite.json`, if present, hashes the final ZIP and records collection outcome separately from delivery. This is a live, non-atomic collection and does not establish source authenticity, boot selection, complete vehicle coverage, native publisher identity, OEM authorization or QR approval.

## Delivery and recovery

Output is under `/storage/emulated/<app-user>/Android/data/com.byd.dashcast/files/oem-bootstrap-exports/oem-bootstrap-<timestamp>/`. The primary path is selected explicitly; SD/USB/external-storage fallbacks are not used. **Fichiers du relevé → Lister / partager** offers `oem-bootstrap-001.zip` and sidecars after acquisition/finalization stops. Share the ZIP even if the collection is partial. Sharing is manual through the existing FileProvider. Earlier sessions are available in **Relevés précédents** without rereading the vehicle. Interrupted markers and `.part` files remain visible to recovery; an integrity failure retains the completed ZIP and is shown as a failure.

Validation: 101 focused JVM/Robolectric tests passed, including real Linux shell/proc fixtures (29 new bootstrap checks and 72 existing regressions). The signed minified release build and fresh vital lint passed with all 47 tasks executed and no vital lint issues. Checks cover scope, byte preservation, process reuse, truncation and access failures, quotas, quarantine, cancellation, local retention and primary-path sharing. Independent review and publication checks are recorded separately. Target-vehicle execution is still required.
