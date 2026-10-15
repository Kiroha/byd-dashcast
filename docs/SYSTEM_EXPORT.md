# Targeted local firmware collection

In **DIAG**, choose **Export system files — framework, libraries & configs**. This SYSTEM profile
collects original firmware files for offline analysis. It complements **Export ALL accessible APKs**;
the APK profile, its `apk-exports` directory and its APK selection semantics are preserved.
Use while parked and keep DashCast in the foreground. No movement state is inferred or enforced.

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
  `libselinux.so`, `libsync.so`, `libui.so`, `libutils.so`, `libzmq.so`. These are grouped exact-name
  probes per root; unrelated neighboring libraries are neither traversed nor queried for metadata.
  Exact native/Bionic probes require directory traversal rights, not directory-listing rights; each
  selected file still requires its existing read permissions.
- Those partitions' `/lib/hw` and `/lib64/hw`: direct-child `auto.*.so` and `auto.so` candidates only,
  with name filtering before type/size queries. Collecting a candidate does not identify the HAL
  selected on this ROM, and no HAL or shared library is loaded or invoked.
- The three exact init-referenced executable paths `/system/bin/fission_service`,
  `/system/bin/fissiond`, `/system/bin/qtandroidnative`, and two exact shared-library paths
  `/system/lib64/libBydCluster.so`, `/system/lib64/libAdas.so`. Their contents are read, never executed.
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
snapshots; an unknown/over-budget size stops further snapshots. Source and diagnostic counts are bounded. App-only fallback retains strict
scope and reports that shell context/classpath discovery is unavailable. Init content inspection
in that fallback is bounded at 65,536 characters and reports truncation.

Selected shell `getprop` values are captured before discovery and after payload copying:
`ro.build.fingerprint`, `ro.build.version.release`, `ro.build.version.sdk`, `ro.product.cpu.abi`,
`ro.build.display.id`, `ro.build.system.fission_single_os`, `sys.car.protocol`,
`persist.sys.protocol.record`, `sys.byd.countrycode`, `ro.hardware.auto`, `ro.hardware`,
`ro.product.board`, `ro.board.platform`, `ro.arch`. These are build/protocol/HAL selectors; VIN,
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

Choose **SYSTEM export files** to see the directory and manually share a finished ZIP or manifest.
An already-authorized ADB connection may pull the displayed directory. For example:

```sh
adb pull /storage/emulated/0/Android/data/com.byd.dashcast/files/system-exports/
```

Scope comes from the P2 targeted system collection manifest and corpus provenance: current APK
corpus reports Android 10/API 29, build dated 2026-06-10; the prior framework corpus is dated
2026-02-04. They are not assumed identical. This export collects current provenance for comparison.
P4 supplied the 22 DT_NEEDED names and five exact init-referenced paths for the 1.9.26-beta extension.
A candidate's location does not establish linker namespace resolution, ABI compatibility, an effective
permission grant or a runtime-selected HAL. Recursive dependency closure and the diagnostic service
implementation still require offline analysis of the additional files. This is not a full firmware image
or a guarantee that stripped/protected code has been recovered. Static/JVM checks do not replace
validation of file visibility, shell utilities, storage and latency on the actual ROM/vehicle.
