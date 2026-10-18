# Local firmware collection

The current top-level **DIAG** export action is **Export complet accessible**, which collects
accessible files from internal storage through the existing daemon and excludes SD/USB storage.
The old separate APK and SYSTEM export buttons are no longer offered. Existing exports and their
profile readers remain available; the legacy SYSTEM scope is documented below for interpreting
those files. A previous global export does not need to be repeated for the cluster evidence below.

## Targeted cluster context

While parked, open **DIAG → Relevés locaux OEM → Contexte cluster — bibliothèque et processus**,
then choose **Lancer le relevé** and keep DIAG visible until local finalization finishes.
This passive collection uses the already-connected ProxyDaemon. It does not connect/bootstrap
another daemon, start an OEM activity, activate projection, restart a process or change a setting.
Vehicle movement is not inferred or enforced by this action.

The closed `CLUSTER_CONTEXT_V1` profile copies exactly
`/system/lib64/libwindowsurface_android.so`, at most 8 MiB. It requires a canonical regular file,
uses existing strict 64 KiB reads that recheck the opened FD, and finalizes the local copy only
when pre/post stat identity, pre/post source SHA-256 and copied SHA-256 all agree. These observations
do not prove authenticity, an atomic filesystem snapshot or that this exact file is loaded.
Only stat/canonical-path/SHA metadata is collected for `/system/lib64/libutils.so`,
`/system/lib64/libc.so`, `/apex/com.android.runtime/lib64/bionic/libc.so` and
`/system/bin/qtandroidnative`. Those four files are not copied again.
The 8 MiB byte limit covers the strict provider copy. Remote `sha256sum` reads the complete
five fixed literal files and can follow their existing firmware aliases; those reads use the
existing command timeout and soft acquisition budget, without an additional byte cap. No path
discovered from a link observation is submitted as another target.

Selected fingerprint, Android API, product/version and `ro.build.system.fission_single_os`
properties are captured before and after. Empty, unavailable or changed values remain explicit.
A bounded `ps -A -o PID,NAME` observation selects at most two numeric PIDs for each exact name
`qtandroidnative`, `com.xdja.containerservice` and `surfaceflinger`, six PIDs in total. Each selected
PID gets stat before/after (including starttime), the first NUL-terminated full argv[0], bounded
status and maps, and the literal text of exe/root/mount-namespace/PID-namespace links. Later command
arguments are excluded. Native argv[0] accepts only the exact basename or the explicit
`/system/bin/…` literal; the Java process requires its complete name. The 15-byte stat `comm`
field is never treated as the complete Java process name. Reuse, disappearance, mismatch, denied
reads and unsupported shell behavior leave process identity unverified.

Maps are a prefix from offset zero, at most 64 KiB per PID and 384 KiB total raw maps. Their
truncation is recorded and does not establish that a library is absent. Other per-PID caps are
4 KiB per stat, 1 KiB argv[0], 8 KiB status and 1 KiB per link. The existing 192 KiB EXEC character
cap remains unchanged; Base64/framing is bounded before transport and charged against a 1 MiB
aggregate metadata wire budget, including failed calls. No path discovered in properties, maps
or links becomes another read target, command, executable, or `/proc/<pid>/root` traversal.

Acquisition has a three-minute soft monotonic budget, with a four-minute soft total session budget
reserving up to one further minute for local finalization. A physical Binder call can outlast the
35-second client timeout; the shared transport then remains quarantined until it returns, without
retry or overlapping diagnostics. Cancellation takes effect between reads. Partial results remain
useful; missing or refused evidence is not global absence or a compatibility result.

The source paths are read only. DashCast writes its local copies, manifests and ZIP on primary
internal shared storage; the existing EXEC transport can also create its bounded PID temporary.
The profile does not promise zero writes anywhere on the device. No collected content is executed.
Use **Fichiers du relevé → Lister / partager** to transfer `cluster-context-001.zip` manually,
including when collection is partial. `integrite.json` outside the ZIP attests its final local hash.
The directory is:

```text
/storage/emulated/<Android-user>/Android/data/com.byd.dashcast/files/cluster-context-exports/cluster-context-<timestamp>/
```

The collector itself never activates the cluster. A separate parked check of Layout drawing,
holder recreation, resize and teardown is described in [CLUSTER_SLOT_LIFECYCLE.md](CLUSTER_SLOT_LIFECYCLE.md).
API-29 JVM/Robolectric checks do not establish behavior on the actual BYD firmware.

## Legacy SYSTEM profile

This section describes retained SYSTEM exports and their original selection semantics, not a
current top-level DIAG button. Use while parked and keep DashCast in the foreground. No movement
state is inferred or enforced.

The pinned panel explicitly identifies APK or SYSTEM and shows stage/current path, file counts,
MiB streamed, elapsed time and time since actual byte/stage progress. After 15 seconds without
progress it reports a possible wait/stall. Cancellation is cooperative after an outstanding
synchronous read/command returns. Existing shell commands time out after 30 seconds; Binder calls
can take longer. For a collection that was not cancelled, a final selected-build-context command runs while the
session remains visibly running, before completion and release of the DIAG busy gate. Cancellation
skips starting that command and records after-copy provenance as unavailable; inventory cleanup
finishes before the gate is released. Other DIAG buttons are disabled.

Scope is fixed in `SystemExportPolicy`; runtime text is treated as data, never executable input:

- `/system/framework`, `/system_ext/framework`, `/product/framework`, `/vendor/framework`: JAR,
  ART, OAT, ODEX and VDEX, including ARM/ARM64 companions where visible.
- Those partitions' `/etc/permissions` and `/etc/sysconfig`: XML only.
- Those partitions' `/etc/init`: RC fragments whose content refers to AutoContainerNative,
  container, fission, autoservice, diagnosticsrv, diagmgr or strategyservice. Canonical firmware scope
  is checked before inspecting content. Shell inspection reads only the first 64 KiB and records
  truncation; app fallback bounds inspection at 65,536 characters. Imports outside these fixed roots
  are not followed, and root-level init files are not collected.
- `/system/etc/container_comm_cfg.json` exactly.
- Those partitions' `/lib` and `/lib64`: the original `libxdjacontainerservice_jni.so`,
  `libfission_services.so`, `libfission_event.so`, `libbydauto.so`, `libbydautoservice.so`, plus the 22
  DT_NEEDED names missing from P4: `libEGL.so`, `libGLESv2.so`, `libandroid_runtime.so`, `libbinder.so`,
  `libc++.so`, `libc.so`, `libcrypto.so`, `libcutils.so`, `libdl.so`, `libfission_cutils.so`, `libgui.so`,
  `libhardware.so`, `libhardware_legacy.so`, `liblog.so`, `libm.so`, `libnativeloader.so`, `libnetutils.so`,
  `libselinux.so`, `libsync.so`, `libui.so`, `libutils.so`, `libzmq.so`.
  Version 1.9.28-beta adds the 52 `libandroid_runtime.so` DT_NEEDED basenames absent from the P7
  manifest across both ABIs (79 selected native basenames in total):
  `libETC1.so`, `libGLESv1_CM.so`, `libGLESv3.so`, `libacquisition.so`, `libandroidfw.so`, `libandroidicu.so`, `libappfuse.so`, `libaudioclient.so`, `libaudiopolicy.so`, `libbase.so`, `libbpf_android.so`, `libcamera_client.so`, `libcamera_metadata.so`, `libdebuggerd_client.so`, `libdiagnostic.so`, `libdl_android.so`, `libfission_utils.so`, `libgraphicsenv.so`, `libharfbuzz_ng.so`, `libhidlbase.so`, `libhidltransport.so`, `libhwbinder.so`, `libhwui.so`, `libimg_utils.so`, `libinput.so`, `libjpeg.so`, `libmedia.so`, `libmedia_helper.so`, `libmediametrics.so`, `libmeminfo.so`, `libmemtrack.so`, `libmemunreachable.so`, `libminikin.so`, `libnativebridge_lazy.so`, `libnativehelper.so`, `libnativeloader_lazy.so`, `libnativewindow.so`, `libnetd_client.so`, `libnetdbpf.so`, `libnetdutils.so`, `libpdfium.so`, `libprocessgroup.so`, `libsensor.so`, `libsoundtrigger.so`, `libsqlite.so`, `libstatslog.so`, `libusbhost.so`, `libvintf.so`, `libvulkan.so`, `libz.so`, `libziparchive.so`, `server_configurable_flags.so`.
  `libacquisition.so`, `libdiagnostic.so`, and `libfission_utils.so` are probed first within each
  native root. These are grouped exact-name probes per root; unrelated neighboring libraries are neither traversed nor queried for metadata.
  Exact native/Bionic probes require directory traversal rights, not directory-listing rights; each
  selected file still requires its existing read permissions.
- Those partitions' `/lib/hw` and `/lib64/hw`, plus `/odm/lib/hw` and `/odm/lib64/hw`: direct-child
  `auto.*.so` and `auto.so` candidates only, with name filtering before type/size queries and canonical
  parent/name checks. The five fixed names `auto.so`, `auto.qcom.so`, `auto.QCM6125.so`,
  `auto.trinket.so`, `auto.default.so` are probed even when directory traversal is permitted but
  listing is denied; denied enumeration is recorded. These are hypotheses from this corpus, not
  platform identification. Other variants use depth-one enumeration when listing is permitted;
  fixed names are excluded from enumeration to avoid duplicate probes. ODM is not added to general
  framework, configuration or native-library scans. Collecting a candidate does not identify the
  HAL selected on this ROM, and no HAL or shared library is loaded or invoked.
- The four exact init-referenced executable paths `/system/bin/fission_service`,
  `/system/bin/fissiond`, `/system/bin/qtandroidnative`, `/system/bin/diagmgrsrv`, and two exact
  shared-library paths `/system/lib64/libBydCluster.so`, `/system/lib64/libAdas.so`.
  Their contents are read, never executed.
- The two exact Qt resource paths `/system/lib64/cluster_theme1.rcc` and
  `/system/lib64/cluster_theme2.rcc`. Other RCC files and resource directories are not scanned.
- `libc.so`, `libm.so`, `libdl.so` only under `/apex/com.android.runtime/lib/bionic` and
  `/apex/com.android.runtime/lib64/bionic`, including canonical numeric-version module aliases.
  This also permits the selected system-library symlinks to resolve to these exact Bionic files;
  it does not scan other APEX libraries or modules.
- Allowed BOOTCLASSPATH/SYSTEMSERVERCLASSPATH framework entries and their exposed companions.
  APEX collection is restricted to referenced filenames and matching ART/OAT/ODEX/VDEX names under
  `javalib` of `com.android.runtime`, `com.android.art`, `com.android.conscrypt`, `com.android.i18n`,
  `com.android.media` (present in the collected P4 BOOTCLASSPATH).
  Canonical module aliases may have a numeric `@version`; arbitrary modules and paths are rejected.

The collector accepts the standard `/system/product`, `/system/system_ext`, `/system/vendor`
aliases only when their canonical destinations remain in this fixed firmware scope. Canonical
paths are checked again before payload reads. Data directories, shared storage, partition images,
private keys, proc/sysfs, arbitrary native libraries and APKs are not in this profile. It neither
invokes vehicle services nor requests new privileges or changes manifest permissions. No CAN,
VHAL, SOME/IP or SurfaceDaemon operation is added.

Original system files are **read only**. Output ZIPs/manifests and bounded inventory text snapshots
are written. Connecting the existing ProxyDaemon can use its normal bootstrap and temporary PID
files. The action therefore does not promise zero writes anywhere on the device. Temporary
inventory files under a generated `/data/local/tmp/dashcast-system-inventory-*` directory are
removed after discovery; original firmware paths are never used as write/delete destinations.

Filesystem inventory is taken once per selected root into a shell text snapshot (child-shell file
limit, at most 8 MiB accepted per snapshot, 32 MiB accepted in aggregate). Inventory is streamed
under the existing 256 KiB daemon reply cap, with explicit completion markers. Missing/denied
roots, unsupported tools, truncated replies, timeouts, unknown snapshot lengths and malformed
rows are recorded. Each root snapshot is deleted in its own cleanup block, including rejected
snapshots; an unknown/over-budget size stops further snapshots. Source and diagnostic counts are bounded. Up to 2,048 discovery issues of at most 800 characters
are retained; exceeding that budget adds an explicit omission marker and keeps collection partial. App-only fallback retains strict
scope and reports that shell context/classpath discovery is unavailable. Init content inspection
in that fallback is bounded at 65,536 characters and reports truncation.

Selected shell `getprop` values are captured before discovery and after payload copying:
`ro.build.fingerprint`, `ro.build.version.release`, `ro.build.version.sdk`, `ro.product.cpu.abi`,
`ro.build.display.id`, `ro.build.system.fission_single_os`, `sys.car.protocol`,
`persist.sys.protocol.record`, `sys.byd.countrycode`, `ro.hardware.auto`, `ro.hardware`,
`ro.product.board`, `ro.board.platform`, `ro.arch`, `apps.setting.product.inswver`,
`ro.build.region`, `sys.which.rse`. These are build/protocol/HAL/product-region selectors; empty
values remain empty and mean unknown, with no inferred default or platform identification. VIN,
serial and arbitrary property dumps are not requested. The initial context also records
BOOTCLASSPATH, SYSTEMSERVERCLASSPATH, bounded `pm list libraries`, app/daemon UIDs and daemon
protocol. Shell classpath environment can differ from the running system-server environment.
Missing or changed before/after fingerprints make the result partial. Equal fingerprints do not
establish an atomic snapshot, effective permission grants, hardware availability or compatibility.

Results are retained independently from report cleanup, with no automatic upload:

```text
/storage/emulated/0/Android/data/com.byd.dashcast/files/system-exports/system-export-<timestamp>/
```

Each `system-NNN.zip` is independently readable, around 1 GiB of source payload; a larger single
file gets its own volume. Payloads stream without staging, with a 128 MiB storage reserve. Every
SYSTEM volume includes `manifest-volume.json`; the separate final session `manifest.json` beside
the ZIPs adds after-copy context and overall gaps. This distinct volume name starts in 1.9.26-beta;
older SYSTEM volumes used `manifest.json` too. APK volume names remain unchanged. Volume manifests
say `contextCheckComplete:false`; consult the final session manifest for the completed check.
Copy **all ZIPs and the final session `manifest.json` beside them**, preserving their session folder;
extracting only a ZIP does not recover the final context check. Each file records source/canonical path, expected/copied bytes,
SHA-256 and errors. Only `exported` entries are complete files; failed entries can be truncated.
Cancelled/destination-failed volumes remain `.part`; finished ZIPs are retained. RUNNING/RESULT
markers include the profile. Rotation/reopening observes the process session. After process death,
the latest timestamp across APK and SYSTEM roots is shown as interrupted/previous, never resumed.

Older builds called the associated file action **SYSTEM export files**. Current file dialogs
retain the originating profile; open the available export's files to share a finished ZIP or manifest.
An already-authorized ADB connection may pull the displayed directory. For example:

```sh
adb pull /storage/emulated/0/Android/data/com.byd.dashcast/files/system-exports/
```

Scope comes from the P2 targeted system collection manifest and corpus provenance: current APK
corpus reports Android 10/API 29, build dated 2026-06-10; the prior framework corpus is dated
2026-02-04. They are not assumed identical. This export collects current provenance for comparison.
P4 supplied the 22 DT_NEEDED names and five exact init-referenced paths for the 1.9.26-beta extension.
P6 established the diagnostics executable path from an init fragment, the two RCC paths from the
cluster library, and the ODM HAL search roots/variant selectors from `libhardware.so`. The
1.9.27-beta extension collects these candidates with existing filesystem rights only; prior read
denials remain coverage gaps and are not bypassed. P10 acquisition JNI analysis identified imports
whose implementations were absent from the P7 corpus. The 1.9.28-beta extension derives its 52 new
native basenames from the 71 DT_NEEDED entries of the collected `libandroid_runtime.so`, excluding
all exported P7 basenames across both ABIs. It adds no scan roots, dependency recursion or APEX
native scope. The three additional property values are captured before and after copying through
the same selected `getprop` path.
A candidate's location does not establish linker namespace resolution, ABI compatibility, an effective
permission grant or a runtime-selected HAL. Recursive dependency closure and the diagnostic service
implementation still require offline analysis of the additional files. This is not a full firmware image
or a guarantee that stripped/protected code has been recovered. Static/JVM checks do not replace
validation of file visibility, shell utilities, storage and latency on the actual ROM/vehicle.
