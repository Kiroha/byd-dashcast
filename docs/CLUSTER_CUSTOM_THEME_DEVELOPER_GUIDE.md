# Custom BYD instrument-cluster themes: creation, loading and recovery

**Implementation/evidence checkpoint: 26 October 2026.**  
**DashCast reference: 1.9.80-beta, build 720, ProxyDaemon protocol 45.**

This guide explains the resource-theme mechanism researched for the BYD Seal
running Android 10 / DiLink 3: how to obtain the factory inputs, create QML,
package an RCC, understand its names and paths, request a custom selection,
interpret the result and restore the factory display.

It is a developer reference. It does not claim that an arbitrary RCC works on
every BYD or that a software acknowledgement guarantees physical recovery.
The new blank/full-screen prototypes remain **offline only**. The historical
full-app artifact that caused a persistent fallback remains **recovery-only**.

## How to read the examples

| Context | Meaning |
| --- | --- |
| **PC / offline** | Local source, artifact or desktop-fixture work; no vehicle communication. |
| **Read-only vehicle example** | Reads one explicitly selected ADB device without changing its theme. |
| **Guarded DashCast workflow** | Uses the actual controller's identity, ownership, journal and confirmation checks. |
| **Maintenance example** | Can interrupt displays; requires the stated stationary conditions and separate acceptance. |
| **Integration sketch** | Explains existing internal code; not a standalone replacement for its guards. |

No vehicle command is executed by this document. Command examples containing the
historical address must be adapted to the current authorized target.

## Contents

1. [Architecture and evidence](#1-architecture-and-evidence)
2. [Identify the exact firmware and Qt context](#2-identify-the-exact-firmware-and-qt-context)
3. [Tools and permission boundaries](#3-tools-and-permission-boundaries)
4. [Capture the factory baseline](#4-capture-the-factory-baseline)
5. [Names, resource aliases and system paths](#5-names-resource-aliases-and-system-paths)
6. [Create and preview QML](#6-create-and-preview-qml)
7. [Patch the factory root reversibly](#7-patch-the-factory-root-reversibly)
8. [Package an RCC and preserve translations](#8-package-an-rcc-and-preserve-translations)
9. [Validate and integrate a candidate](#9-validate-and-integrate-a-candidate)
10. [The real loading protocol](#10-the-real-loading-protocol)
11. [Load an admitted profile](#11-load-an-admitted-profile)
12. [Interpret results correctly](#12-interpret-results-correctly)
13. [Restore the default theme](#13-restore-the-default-theme)
14. [Retire the exact custom carrier](#14-retire-the-exact-custom-carrier)
15. [Restart with fission-all](#15-restart-with-fission-all)
16. [Simple mode and interrupted recovery](#16-simple-mode-and-interrupted-recovery)
17. [Performance and native lifetime](#17-performance-and-native-lifetime)
18. [Troubleshooting and acceptance records](#18-troubleshooting-and-acceptance-records)
19. [Implementation and primary references](#19-implementation-and-primary-references)

---

## 1. Architecture and evidence

### 1.1 What an RCC changes

A binary Qt RCC is a container of named resources: QML, images, translations and
other data. Its disk filename is distinct from the aliases inside it.

The researched mechanism uses a resource/translation carrier to provide selected
Qt aliases. It is not an Android launcher theme or an APK resource overlay.
Do not replace a system APK or overwrite a factory RCC to experiment.

Duplicate aliases do not prove that the newly registered file wins lookup.
Registration order, the OEM's application path and existing component/resource
caches matter. Validate the selected resource and physical result separately.

### 1.2 The renderer is not simply another visible Android process

The researched native Qt cluster consumer lives outside the Android IVI cell.
An Android shell can fail to see its PID while the physical cluster is working.
A missing Android-visible `qtandroidnative` is not evidence that the native
renderer is stopped.

~~~mermaid
flowchart LR
    UI[DashCast Android UI] --> P[ProxyDaemon and bounded helper]
    P -->|type 8 FlatBuffer| A[Android AutoContainer]
    A --> N[Native cluster and data source]
    N --> S[Persisted resource selection]
    S -. separate application event .-> Q[Qt resources and QML]
    Q --> D[Physical cluster pixels]
    F[Shared media RCC file] --> Q
~~~

Selection and application are separate. Native analysis of the matched cohort
shows a configuration path that can acknowledge the change without performing
an immediate XML reread or QML reload. There is no universal reload opcode or
delay established by that observation.

### 1.3 Source compatibility is not only visual compatibility

The original root contains C++-facing IDs, properties, signals, startup
notifications and native children. Its visible rectangles are only part of the
contract. Preserving the ID inventory does not prove that altered parents,
anchors or destruction order remain valid.

Start with a small reversible addition to the complete factory root. A bare
black `Rectangle` is a desktop demonstration, not a replacement vehicle root.

---

## 2. Identify the exact firmware and Qt context

### 2.1 Researched target

| Item | Observed/source-established context |
| --- | --- |
| Main recovery-investigation vehicle | BYD Seal |
| Android | Android 10 / API 29 |
| DiLink | Positively identified DiLink 3 |
| Reported software family | SX361, June 2026 |
| Observed incremental build | `eng.build.20260610.082733` |
| Large instrument cluster | 1920 × 720 |
| Resource UI contract | `Di3F_DynastyOceanTranslate_V0001` |
| Current DashCast min/target/compile SDK | 28 / 29 / 36 |
| Current recovery protocol | ProxyDaemon 45 |

Measure another panel rather than guessing its geometry. A desktop fixture
running at another resolution is not physical panel identification.

A model name, firmware label, compile-only OEM SDK, optional `android.car`
library or fallback DiLink detection is insufficient compatibility evidence.
BYD Android is not assumed to be Android Automotive OS.

### 2.2 Matched installed artifacts

| Android path | SHA-256 |
| --- | --- |
| `/system/lib64/libBydCluster.so` | `21f7735cad4408f7baf7086b0e0d7b4ed3d1d2818a35bea6b8ad026102d9f4b1` |
| `/system/lib64/libBydDataSource.so` | `02dfc7f5aa046167a3d829c69ef8ee97fd301cf9081450b86366319566c10ae8` |
| `/system/lib64/cluster_theme1.rcc` | `83dcdb5bf80aa1b0641622b913cb4f0dc13d3bf7a1fe510f0d10603446faee5d` |
| `/system/lib64/cluster_theme2.rcc` | `0041e6b81f5f2b57c9ee112f4f196a16563345214e1e9d6d7bc75625383812b8` |
| `/system/lib64/commonTranslate.rcc` | `1dfee5a0ad746166cb28d79bddfe9352dc510df6cbcd0001f4751af1a6dbf5e4` |

The maintenance restart additionally checks:

~~~text
/system/bin/fissiond
31d39dfc63ffdae993ae5003fc6d392409a1a4c482609cb36f2fcbd8343fd93e
~~~

These hashes establish installed artifact identity in the Android namespace.
They do not attest the generation or mappings of the running native consumer.

### 2.3 QtQuick 2.15 is not “Qt 2.15”

`import QtQuick 2.15` names a QML module API version. Qt 5.15 is the framework
family referenced by the tooling/source investigation. Desktop validation here
used Qt 5.15.14; that does not establish the car's exact patch level or plugins.

Check each type/property in its actual module import and registered native-type
context. One supported module version does not establish all other modules.
A property failing in one import context is not proof that it is unsupported
in every Qt 5.15 context.

---

## 3. Tools and permission boundaries

### 3.1 PC tools

Use Git, Python 3, SHA-256 tools and Android platform-tools. Desktop previews also
need a compatible Qt 5 environment, `rcc` and `qmlscene`, or the already available
PyQt5 environment. Android build tools are needed only when integrating a profile.

~~~bash
python3 --version
git --version
adb version
rcc --version
rcc --help
~~~

Record the actual versions. The repository codec and matched stock file use
**binary RCC format 3**. This is different from `<RCC version="1.0">` in QRC XML.

### 3.2 Permission boundaries

File access, Binder authorization and host restart authority are different:

- Shell and DashCast do not automatically have system-UID permissions.
- A declared permission does not establish an effective grant.
- An OEM SDK class does not prove runtime service availability.
- DashCast's signer is not assumed to match every OEM signer.
- Android paths and native-host paths are not interchangeable.
- ADB protocol reboot is distinct from Android's Binder reboot API.

Do not respond to a refusal by remounting `/system`, changing global permissions,
writing reboot properties or sending guessed vehicle diagnostic numbers.

### 3.3 Suggested PC workspace

~~~text
cluster-theme-work/
  evidence/identity.txt
  evidence/factory-selection.txt
  evidence/SHA256SUMS
  stock/cluster_theme1.rcc
  stock/cluster_theme2.rcc
  stock/commonTranslate.rcc
  extracted/theme1/qml/cluster.qml
  extracted/theme2/qml10_25/cluster.qml
  owned/Preview.qml
  patched/qml10_25/cluster.qml
  build/OFFLINE_ONLY_DeveloperExample.rcc
  build/manifest.json
  recovery/exact-carrier-copy.rcc
  recovery/recovery-record.json
~~~

Keep original captures immutable and private. Do not publish proprietary full
source, raw device reports, keys or the operational recovery journal.

---

## 4. Capture the factory baseline

### 4.1 Explicit target selection

**Read-only vehicle examples.** The address is historical, not a discovery
mechanism. Port 5038 is the PC ADB server port; 5555 is the device TCP port.

~~~bash
SERIAL='100.70.244.124:5555'
device_adb() { adb -P 5038 -s "$SERIAL" "$@"; }

device_adb get-state
device_adb shell getprop ro.build.version.sdk
device_adb shell getprop ro.build.version.incremental
device_adb shell getprop ro.build.system.fission_single_os
device_adb shell wm size
~~~

`wm size` alone does not enumerate or prove the native cluster's geometry.
Use the existing display diagnostics and physical observation too.

### 4.2 Read-only backup of the factory resources

~~~bash
mkdir -p cluster-theme-work/stock cluster-theme-work/evidence

device_adb shell sha256sum /system/lib64/cluster_theme1.rcc
device_adb shell sha256sum /system/lib64/cluster_theme2.rcc
device_adb shell sha256sum /system/lib64/commonTranslate.rcc
device_adb shell sha256sum /system/lib64/libBydCluster.so
device_adb shell sha256sum /system/lib64/libBydDataSource.so

device_adb pull /system/lib64/cluster_theme1.rcc cluster-theme-work/stock/
device_adb pull /system/lib64/cluster_theme2.rcc cluster-theme-work/stock/
device_adb pull /system/lib64/commonTranslate.rcc cluster-theme-work/stock/
~~~

Compare the PC hashes with the device outputs. An unreadable input is not an
invitation to guess its version.

~~~bash
cd cluster-theme-work
sha256sum stock/cluster_theme1.rcc stock/cluster_theme2.rcc \
  stock/commonTranslate.rcc > evidence/SHA256SUMS
sha256sum --check evidence/SHA256SUMS
~~~

Run subsequent repository-relative examples from the DashCast repository root.

### 4.3 Record the current selection separately

In DashCast's expanded cluster technical details, use
**Read OEM RCC selection (read only)** when eligible. Record the bounded result:

- UI contract.
- Source integer.
- IVI path and resource label.
- Missing/conflicting/malformed-response state.
- App build and connected daemon protocol.
- What the person actually sees on the physical cluster.

Do not equate that selection with registration or physical pixels.
Do not share the raw journal, which contains internal helper identity material.

### 4.4 Extract trusted local root aliases

**PC / offline.** `rcc --list` lists a QRC project; it is not a general extractor
for arbitrary binary RCCs. This repository has a format-specific codec for the
known inputs.

~~~python
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, "tools/cluster")
import build_live_marker as codec

work = Path("cluster-theme-work")
inputs = [
    ("cluster_theme1.rcc", "theme1", "qml/cluster.qml",
     "83dcdb5bf80aa1b0641622b913cb4f0dc13d3bf7a1fe510f0d10603446faee5d"),
    ("cluster_theme2.rcc", "theme2", "qml10_25/cluster.qml",
     "0041e6b81f5f2b57c9ee112f4f196a16563345214e1e9d6d7bc75625383812b8"),
]
for filename, theme, alias, expected in inputs:
    blob = (work / "stock" / filename).read_bytes()
    if hashlib.sha256(blob).hexdigest() != expected:
        raise RuntimeError("Unknown factory input: " + filename)
    rows = codec.decode(blob)
    matches = [row for row in rows if row[0] == alias]
    if len(matches) != 1:
        raise RuntimeError("Missing or duplicate root: " + alias)
    target = work / "extracted" / theme / alias
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(matches[0][5])
    print(alias, hashlib.sha256(matches[0][5]).hexdigest())
~~~

Expected root hashes:

~~~text
qml/cluster.qml:
76a2ad22efe281a6d73362d3a3c4e5f998d33e9dff210934f11e4e6071336fb3

qml10_25/cluster.qml:
8c9cb017658a904dd34e5b2b1ff0654576031ff4f31d0a0dedd259da52a09631
~~~

The small codec is not a hardened parser for untrusted RCCs. Here its input is
restricted by a full known-file hash before parsing.

---

## 5. Names, resource aliases and system paths

### 5.1 Four different identities

| Identity | Example | Purpose |
| --- | --- | --- |
| PC artifact | `OFFLINE_ONLY_DeveloperExample.rcc` | A non-admitted research output. |
| Runtime basename | `Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_<nonce>.rcc` | Exclusively staged file owned by one session. |
| Qt alias | `qml10_25/cluster.qml` | Resource name resolved within registered RCC trees. |
| Compiled profile ID | `corner-marker-21f7735c` | Chooses an exact asset and expected content hash. |

Renaming a file does not make a compatible profile. Copying it under a
DashCast-looking name does not create trusted recovery ownership.

### 5.2 Exact user-0 storage mapping

| View | Directory |
| --- | --- |
| Android alias | `/sdcard/.cluster/translate/` |
| Android canonical media | `/storage/emulated/0/.cluster/translate/` |
| Native-host media view | `/data/cells/cell2-rw/data/media/0/.cluster/translate/` |
| Private daemon operations | `/data/local/tmp/dashcast-cluster-live/` |
| Factory inputs | `/system/lib64/cluster_theme1.rcc`, `cluster_theme2.rcc`, `commonTranslate.rcc` |

The host path is a reported native view, not a directory an Android shell should
create. DashCast requires primary media user 0 and pins directory generations;
child symlinks or a different storage arrangement refuse.

### 5.3 Exact DashCast basename rule

~~~text
Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_[0-9a-f]{32}.rcc
~~~

Illustrative concrete name:

~~~text
Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_0123456789abcdef0123456789abcdef.rcc
~~~

The suffix is a session nonce, not MD5/SHA-256. The full content SHA-256 is
validated independently against the compiled profile.

The load payload contains **only the basename**. Absolute Android/host paths,
URLs, parent traversal, uppercase substitutions and foreign names are refused.

### 5.4 Exact relevant aliases

~~~text
qrc:/qml/cluster.qml
qrc:/qml10_25/cluster.qml
qrc:/qml10_25/alwaysDisplay/Presentation.qml
~~~

The inspected large Presentation source has SHA-256:

~~~text
72406fdda39b5eef7242d9da7dfba84a540eb801ed6919719a46c0015bcf33b8
~~~

The quarantined minimal bench uses research aliases under
`offline/minimal-bench/` and supplies neither canonical root alias.
Registering or renaming that research file cannot replace the factory root URLs.

---

## 6. Create and preview QML

### 6.1 Desktop-only owned component

**PC / offline.** From the repository root, save this as
`cluster-theme-work/owned/Preview.qml`:

~~~qml
import QtQuick 2.15

Rectangle {
    width: 1920
    height: 720
    color: "#101820"

    Rectangle {
        x: 24
        y: 24
        width: 280
        height: 72
        radius: 8
        color: "#173743"
        border.width: 2
        border.color: "#00cddd"

        Text {
            anchors.centerIn: parent
            text: "DESKTOP THEME PREVIEW"
            color: "#ffffff"
            font.pixelSize: 24
        }
    }
}
~~~

~~~bash
qmlscene cluster-theme-work/owned/Preview.qml
~~~

This contains no BYD native contract. **Do not install it as the vehicle root.**
A working desktop preview establishes only behavior in that desktop environment.

### 6.2 Desktop QRC example

Save this as `cluster-theme-work/preview.qrc`; file paths are relative to that
QRC file's directory:

~~~xml
<RCC version="1.0">
  <qresource prefix="/offline/developer-example">
    <file alias="Preview.qml">owned/Preview.qml</file>
  </qresource>
</RCC>
~~~

If the chosen Qt 5 tool's help confirms these options:

~~~bash
mkdir -p cluster-theme-work/build
rcc --binary --format-version 3 --compress-algo zlib \
  --output cluster-theme-work/build/OFFLINE_ONLY_Preview.rcc \
  cluster-theme-work/preview.qrc
~~~

This builds a desktop resource container. It does not preserve stock metadata
automatically. Use the raw-record preservation approach in section 8 for the
matched carrier.

### 6.3 Dependencies desktop Qt does not establish

The car additionally supplies OEM C++ QML types, `DataSource`, native surface
services, startup/self-check messages and vehicle/state signals. Some warning or
ADAS layers can also be composed outside the QML you changed.

If using substitutes, say exactly which fragment executed and which objects were
mocked. Do not label a fully mocked picture as vehicle validation.

---

## 7. Patch the factory root reversibly

### 7.1 Preserve the native-facing contract

Start from the complete hash-matched root and preserve:

- Imports, root type and `rootItemId`.
- Other IDs, properties, functions and native signal handlers.
- Startup/load-complete notification ordering.
- Existing Presentation owner and source URLs.
- Warning/telltale, ADAS, charge/menu and other state machines.

Preserve the existing notification:

~~~qml
DataSource.sendPluginMsgId(DataSource.RECV_MSG_ID_QML_LOAD_COMPLETE)
~~~

Retaining that line does not prove that every other native assumption survives.

### 7.2 Additive patch with exact reversal

**PC / offline.** This owned diagnostic addition is initially invisible and
replaces no factory object:

~~~python
from pathlib import Path
import hashlib

source = Path("cluster-theme-work/extracted/theme2/qml10_25/cluster.qml")
target = Path("cluster-theme-work/patched/qml10_25/cluster.qml")
original = source.read_bytes()
expected = "8c9cb017658a904dd34e5b2b1ff0654576031ff4f31d0a0dedd259da52a09631"
if hashlib.sha256(original).hexdigest() != expected:
    raise RuntimeError("Unknown factory root")

anchor = b"    id:rootItemId\r\n"
if original.count(anchor) != 1:
    raise RuntimeError("Factory anchor is not unique")

patch = (
    b"\r\n    // DEVELOPER_EXAMPLE_BEGIN\r\n"
    b"    Rectangle {\r\n"
    b"        id: developerExampleMarkerId\r\n"
    b"        x: 8; y: 8; width: 24; height: 4\r\n"
    b"        color: \"#00cddd\"\r\n"
    b"        visible: false\r\n"
    b"    }\r\n"
    b"    // DEVELOPER_EXAMPLE_END\r\n"
)
patched = original.replace(anchor, anchor + patch, 1)
if patched.replace(patch, b"", 1) != original:
    raise RuntimeError("Patch is not exactly reversible")

target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(patched)
print("SHA-256:", hashlib.sha256(patched).hexdigest())
~~~

The invisible addition intentionally does not prove visible loading.
The repository's corner-marker profile provides a separate admitted visible
experiment; its rendering guard is not a fresh movement sensor.

A visible change needs its own bounded experiment and reversal. Do not
progressively uncomment unknown OEM functionality in a live car.

### 7.3 Visibility and lifecycle are different

Moving an item to `x: -5000` does not unload it. Setting `visible: false` can
avoid rendering while objects, Connections, bindings and Timers remain alive.

Changing an existing `Loader.active` can release its loaded subtree. That can
invoke native destructors, and is therefore not a harmless equivalent to hiding
it. The current performance experiment validates owned desktop fixtures only.

---

## 8. Package an RCC and preserve translations

### 8.1 Preserve all 84 stock translation entries

The matched `commonTranslate.rcc` has 84 entries under `common/language/`:
42 compiled `.qm` entries and 42 `.ts` source entries.

The `.ts` files are not another 42 runtime languages. Their removal would still
change the exact input carrier. Preserve each resource's path, country, language,
flags, stored bytes and decoded bytes.

A different `.qm` hash is not automatically corruption, but it cannot be claimed
to be a preserved copy of the matched input.

### 8.2 Container details used here

| Property | Value |
| --- | --- |
| Magic | `qres` |
| Binary format | 3 |
| Container integer byte order | Big-endian |
| Tree record size | 22 bytes |
| Resource names | UTF-16 big-endian plus Qt name hash |
| Compressed file flag used here | 1 |
| Directory flag | 2 |
| Compressed file bytes | Big-endian uncompressed length followed by zlib |

XML QRC version, binary format, QML import version and firmware label are
different version concepts.

### 8.3 Deterministic offline build example

This preserves the 84 raw locale records, keeps the small root unchanged and
adds the patched large root. The output is not an admitted DashCast profile.

~~~python
import hashlib
import json
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, "tools/cluster")
import build_live_marker as codec

work = Path("cluster-theme-work")
stock = (work / "stock/commonTranslate.rcc").read_bytes()
stock_sha = "1dfee5a0ad746166cb28d79bddfe9352dc510df6cbcd0001f4751af1a6dbf5e4"
if hashlib.sha256(stock).hexdigest() != stock_sha:
    raise RuntimeError("Unknown translation carrier")
rows = codec.decode(stock)
if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
    raise RuntimeError("Unexpected locale inventory")

original_locales = list(rows)
small = (work / "extracted/theme1/qml/cluster.qml").read_bytes()
large = (work / "patched/qml10_25/cluster.qml").read_bytes()
if hashlib.sha256(small).hexdigest() != (
    "76a2ad22efe281a6d73362d3a3c4e5f998d33e9dff210934f11e4e6071336fb3"
):
    raise RuntimeError("Unknown small-panel root")

for alias, data in [("qml/cluster.qml", small), ("qml10_25/cluster.qml", large)]:
    stored = struct.pack(">I", len(data)) + zlib.compress(data, 9)
    rows.append((alias, 0, 1, 1, stored, data))
carrier = codec.build(rows)
if not 0 < len(carrier) < 4 * 1024 * 1024:
    raise RuntimeError("Carrier exceeds the current experiment bound")
decoded = codec.decode(carrier)
locales = [row for row in decoded if row[0].startswith("common/language/")]
if sorted(locales) != sorted(original_locales):
    raise RuntimeError("Stock bytes or metadata changed")

out = work / "build"
out.mkdir(parents=True, exist_ok=True)
(out / "OFFLINE_ONLY_DeveloperExample.rcc").write_bytes(carrier)
(out / "manifest.json").write_text(json.dumps({
    "runtime_activation_allowed": False,
    "vehicle_validated": False,
    "stock_sha256": stock_sha,
    "carrier_sha256": hashlib.sha256(carrier).hexdigest(),
    "carrier_size": len(carrier),
    "resources": len(decoded),
    "canonical_root_aliases": True,
    "roots": {
        "qml/cluster.qml": hashlib.sha256(small).hexdigest(),
        "qml10_25/cluster.qml": hashlib.sha256(large).hexdigest(),
    },
}, indent=2) + "\n")
print("84 preserved locale entries + 2 roots =", len(decoded))
~~~

Unlike the quarantined minimal bench, this educational artifact has canonical
root aliases. Its filename alone is not a safety boundary. Keep it offline:
do not rename it into a selectable carrier and load it without integration,
review, compatibility checks and recovery preparation.

### 8.4 Why not recompile the complete extraction?

A broad recompile can alter ordering, compression, metadata, translations and
unrelated source. Preserve untouched records and prove exactly which bytes changed.

Never update a historical compiled asset in place: its content hash and journal
identity must continue to identify the old bytes for recovery.

---

## 9. Validate and integrate a candidate

### 9.1 Offline checks

~~~bash
sha256sum cluster-theme-work/build/OFFLINE_ONLY_DeveloperExample.rcc
python3 -m json.tool cluster-theme-work/build/manifest.json
python3 -m unittest discover -s tools/cluster -p 'test_*.py'
~~~

A clean archive may omit generated research artifacts. Report artifact-only
skips rather than claiming those artifacts were tested.

Check input/root hashes; exact aliases; translation stored/decoded bytes and
metadata; compression; unique patch anchors; exact reverse patch; preserved
startup/warning handlers; one Presentation consumer; bounded size; and recovery
records for the exact output.

Existing desktop scripts include:

~~~bash
python3 tools/cluster/validate_corner_marker_qt5.py
python3 tools/cluster/validate_minimal_app_qt5.py --help
python3 tools/cluster/validate_minimal_perf_qt5.py --help
~~~

Use a compatible existing PyQt5 environment. Those tests execute owned fragments
or fixtures, not the complete proprietary root/native plugins.

### 9.2 DashCast uses closed compiled profiles

The application is not an arbitrary RCC picker. Its variant binds an ID, asset,
exact SHA-256, closed operation and minimum protocol. The current corner profile is:

~~~text
ID: corner-marker-21f7735c
Asset: cluster_live/corner_marker_21f7735c.rcc
SHA-256: fe069081f1d5c8374276aad2513b8862de61008f67fe6162fb2a4a351e00b734
Android-IVI operation: 18
Historical minimum protocol: 39
~~~

Current page capabilities require later protocols too; do not interpret that
historical minimum as the whole current UI contract.

A new candidate requires a new immutable asset/manifest and profile identity,
installed-cohort checks, a closed operation/capability, staging/helper validation,
recovery recognition, UI eligibility, cancellation, evidence states and tests.
It also needs native-contract review and separate stationary vehicle validation.

Do not edit an existing expected hash merely to accept a locally modified file.

### 9.3 Typed controller integration sketch

~~~kotlin
// Controller worker only, after its complete existing admission checks.
val pinned = ClusterThemeSession.pin(applicationContext)
val variant = ClusterThemeVariant.CORNER_MARKER_21F7735C

// Both descriptors come from the existing controller, not arbitrary caller input.
val statusJson = pinned.startIvi(
    sourceDescriptor,
    variant,
    reservationBinder
) {
    check(requestStillCurrent())
}
~~~

The real controller also reserves ownership, writes app debt before IPC, pins
helper identity and handles ambiguous completion. This sketch omits that
surrounding machinery and must not be used as a standalone loader.

### 9.4 Transfer is not staging, selection or activation

The daemon owns runtime staging: exclusive creation, content verification,
inode/device/parent recording and durable debt before helper admission.

A manual `adb push` into the lookup directory does not recreate that ownership.
For a file-transfer review, use a non-lookup directory:

~~~bash
# Vehicle write example: transfer only; no theme selection or registration.
device_adb shell mkdir -p /sdcard/Download/cluster-theme-review
device_adb push cluster-theme-work/build/OFFLINE_ONLY_DeveloperExample.rcc \
  /sdcard/Download/cluster-theme-review/OFFLINE_ONLY_DeveloperExample.rcc
device_adb shell sha256sum \
  /sdcard/Download/cluster-theme-review/OFFLINE_ONLY_DeveloperExample.rcc
~~~

Do not move that example into live lookup to bypass the application's admission.

---

## 10. The real loading protocol

### 10.1 Observed Android Binder contract

| Item | Researched value |
| --- | --- |
| Service | `AutoContainer` |
| Service descriptor | `android.os.IAutoContainer` |
| Callback registration | Transaction 4, callback strong Binder |
| Send data | Transaction 3, integer type 8, byte array |
| Callback descriptor | `android.os.IContainerCallback` |
| Relevant callback transaction | 4, integer type 8, byte array |
| Expected Java callback UID | 1000 |

The helper pins the service, checks its descriptor and installed Java identity,
registers before sending, bounds callbacks and never reconnects to replay a
mutation. The researched Java package is `com.xdja.containerservice`, with pinned
APK SHA-256:

~~~text
1b9eef499db47d19a847b77aeaebf823f47449e9d0bc047793aa4433da9a0be9
~~~

Another package/ABI/permission environment requires new evidence.

### 10.2 FlatBuffer request slots

The request declares 13 table slots:

| Slot | Meaning in this subset |
| --- | --- |
| 0 | Command |
| 1 | Subcommand |
| 2 | Mode / first integer |
| 3–6 | Other integer slots, default zero here |
| 7 | First string: load basename |
| 8–11 | Other strings, unused in these requests |
| 12 | Reserved/unused in this subset |

| Request | Command | Subcommand | Mode | Name |
| --- | --- | --- | --- | --- |
| Selection query | 1 | 4 | 0 | Empty |
| Custom selection | 5 | 0 | 0 | Exact admitted basename |
| Default restoration | 5 | 0 | 1 | Empty |

FlatBuffers omit default-valued fields. An omitted zero is not an invalid mode;
the wrong slot ordering is invalid.

### 10.3 Construct bytes offline

This Kotlin example constructs bytes only; it performs no IPC:

~~~kotlin
import com.google.flatbuffers.FlatBufferBuilder

fun buildRccRequest(
    command: Int,
    subcommand: Int,
    mode: Int,
    basename: String
): ByteArray {
    val builder = FlatBufferBuilder(256)
    val name = if (basename.isEmpty()) 0 else builder.createString(basename)
    builder.startTable(13)
    builder.addInt(0, command, 0)
    builder.addInt(1, subcommand, 0)
    builder.addInt(2, mode, 0)
    if (name != 0) builder.addOffset(7, name, 0)
    builder.finish(builder.endTable())
    return builder.sizedByteArray()
}

val queryBytes = buildRccRequest(1, 4, 0, "")
val restoreBytes = buildRccRequest(5, 0, 1, "")
~~~

For production load bytes, use `ClusterThemeProtocol.load` instead: it enforces
the exact owned-basename policy. Offline layout regression tests are available:

~~~bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.byd.dashcast.clusterlive.ClusterThemeProtocolTest'
~~~

### 10.4 Why integer service calls are not the RCC protocol

This shape uses a different operation:

~~~text
service call AutoContainer 2 i32 1000 i32 SOME_INTEGER
~~~

It does not encode the type-8 byte-array payload. Do not replace the placeholder
with a guessed number to load, reload or restore a theme.

A complete exchange needs callback registration, proper marshalling, baseline
query, identity/ownership checks and recovery handling. There is no documented
supported one-line `adb shell load-rcc` command.

### 10.5 Why the helper must not be launched by hand

The child helper's arguments are only part of its contract. The controller also
owns its unique helper record, verified PID/start generation, carrier inode/device,
parent identity, durable debt and `GO` admission, plus an eight-second deadline
and positive reaping.

A pasted `app_process` command with guessed arguments bypasses or fails that
protocol. Do not publish internal journal/helper identity material to make a
standalone command appear to work.

---

## 11. Load an admitted profile

### 11.1 Launch the actual exported activity

**Vehicle UI example.** The exported launcher is `WelcomeActivity`, not
`MainActivity` or `DiagActivity`:

~~~bash
device_adb shell am start -W \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER \
  -n com.byd.dashcast/.ui.welcome.WelcomeActivity
~~~

Then navigate **Settings → Diag → DashCast dashboard**, completing any initial
language selection normally.

The following is **not** a supported external shortcut:

~~~text
adb shell am start -n com.byd.dashcast/.ui.diag.DiagActivity
~~~

That activity is non-exported. This guide does not create a public theme API.

### 11.2 Establish a normal baseline

1. Immobilize the vehicle in P, with parking brake applied.
2. Confirm normal factory instruments and required alerts are visible.
3. Install the verified release in place, retaining data.
4. Confirm app build and connected daemon protocol.
5. Resolve existing trusted recovery debt before another experiment.
6. Read the current status and, when eligible, selection.
7. Have the independent recovery authority available before any mutation.

A user assertion is not a movement sensor. Unknown/stale state must remain
unknown; zero speed alone does not establish parked.

### 11.3 Use the smallest admitted experiment

Expand **Show details and previous tests**. The corner-marker resource test is
the bounded initial experiment. Follow its subsequent visual question.

The new blank/full-screen candidate is unavailable for loading. Do not reactivate
a disabled historical profile by changing its filename, enum or recovery record.

### 11.4 Actual guarded load sequence

The variant-specific branches differ, but the controller's essential ordering is:

1. Pin the existing daemon and reserve command/projection ownership.
2. Exclude conflicting recovery and helpers.
3. Verify the closed asset and installed artifact cohort.
4. Exclusively stage the carrier and record its identities.
5. Persist recovery debt.
6. Start the bounded helper and persist its verified identity.
7. Send `GO` after durable admission.
8. Register the OEM callback and query a compatible default baseline.
9. Recheck installed cohort and staged content inside the helper.
10. Send one type-8 custom request.
11. Collect bounded acknowledgements and a post-request selection.
12. Keep uncertainty/debt until the required evidence and observation exist.

Do not run another loader alongside it. An unrelated request makes the broadcast
callback evidence and ownership assumptions harder to establish.

### 11.5 Selection is not an invented reload step

In the matched REA cohort, the configuration path stores a resource choice and
sends a notification. Its ACK handler does not secretly perform the XML resource
reread or QML reload. Registration occurs when the corresponding resource-change
application path actually runs.

Therefore this guide does not prescribe an unverified “reload now” integer,
a guessed delay or a reboot with a newly unvalidated persistent root.
Treat application/physical observation as an additional admitted transition,
not as a consequence guaranteed by the selection request.

---

## 12. Interpret results correctly

### 12.1 Evidence layers

| Observation | Supports | Does not prove |
| --- | --- | --- |
| Binder handled | Transaction shape accepted | RCC accepted or pixels changed |
| Native result 0 | Bridge returned success | QML constructed or display restored |
| Matching ACK2 | Registration broadcast evidence | Unique request correlation or pixels |
| ACK4 | Configuration acknowledgement | Immediate QML reload/application |
| Our host path selected | Persisted own selection | Current registration or visible result |
| Source 1 | Native translation-path field empty | IVI selection is default |
| Fixture `Loader.Ready` | Component loaded there | Native car frames |
| ADB reconnect | Android transport available | Native factory display restored |
| Strict default receipt plus human inspection | Separate selection/physical evidence | Immunity from later failure |

The callbacks have no request ID. A time window constrains observations but does
not correlate an unsolicited broadcast uniquely to your request.

### 12.2 Source 1 is not sufficient

Source 1 can coexist with the IVI path naming our custom carrier. These fields
describe separate native/IVI configuration.

~~~kotlin
// Incorrect: source alone is not restoration proof.
val restored = response.ints[3] == 1
~~~

Use the strict existing UI/default-field predicate and separate physical
confirmation, not this shortcut.

### 12.3 Recognized resource errors

| Code | Label |
| --- | --- |
| `0x232c` | `NOT_RCC` |
| `0x232d` | `UI_VERSION_MISMATCH` |
| `0x232e` | `RESOURCE_REGISTER_FAILED` |
| `0x2330` | `RESOURCE_IS_SAME` |
| `0x2331` | `RESOURCE_NOT_FOUND` |

Preserve bounded slots/context. Merely finding `0x2330` anywhere in a response is
not the exact supported retirement eligibility proof.

---

## 13. Restore the default theme

### 13.1 Preferred v1.9.80 user flow

The fixed **Restore cluster** button remains available outside the technical
scroll area:

1. It reads the pinned session and closes DashCast's projected resources.
2. Follow the separate stationary/recovery confirmation.
3. The controller requests default selection only if its baseline and ownership
   permit it.
4. If strict default selection is confirmed, inspect the real instruments/alerts
   before answering the visual question.
5. If a supported `RESOURCE_IS_SAME` failure still selects the exact owned RCC,
   follow the separate preserved-file preparation prompt.
6. Fresh verification checks the moved file and lookup inventory.
7. A separate popup offers restart.
8. Acceptance authenticates local ADB, repeats checks and persists intent.
9. One fixed `reboot:fission-all` request can be sent.
10. After restart/reconnection, use Restore for remaining verification and
    physical confirmation.

Refusal does not authorize clearing preferences, deleting the journal or running
another loader.

### 13.2 Default payload

~~~text
command = 5
subcommand = 0
mode = 1
name = empty
~~~

Existing byte construction:

~~~kotlin
// The admitted exchange, not this line alone, owns transmission.
val request: ByteArray = ClusterThemeProtocol.restoreDefault()
~~~

Default restoration is not loading `cluster_theme2.rcc` as another custom file.
It uses the OEM default-selection state.

### 13.3 Accepted request, failed restoration

This combination is not a success:

~~~text
Native result: 0
ACK3 integers: [3, 0, 9008, 0, 0]
Error: RESOURCE_IS_SAME (0x2330)
Selection: still the exact owned host-path carrier
~~~

It can enable the constrained retirement option; it does not prove factory return.
Likewise, moving a file does not revoke bytes already mapped or referenced by Qt.

---

## 14. Retire the exact custom carrier

### 14.1 Preserved location

The integrated path moves only the journal-owned carrier to a new directory:

~~~text
/sdcard/.cluster/translate/
  .dashcast-retired-<32 lowercase hexadecimal characters>/
    Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_<session nonce>.rcc
~~~

Its original basename is absent from direct lookup; the preserved file retains
its bytes and inode on the same filesystem. The hidden-directory suffix is
independent of the carrier nonce. Do not invent it when reconciling a journal.

### 14.2 Verification order

The real implementation does more than `File.exists()`:

~~~text
1. Require trusted session and supported retirement eligibility.
2. Exclude active helpers, queries and conflicting operations.
3. Pin parent directories without following child symlinks.
4. Verify exact original type, device, inode and SHA-256.
5. Create an exclusively owned recovery directory.
6. Persist/fsync intent before rename.
7. Recheck original identity and destination absence.
8. Rename only that carrier.
9. Verify original-name absence.
10. Verify preserved target type, device, inode and SHA-256.
11. Synchronize affected directories.
12. Persist completion.
13. Before restart, repeat proof and enumerate direct lookup entries.
14. Refuse remaining DashCast-prefixed entries or failed enumeration.
~~~

A failed rename, hash, journal, directory sync or inventory blocks restart.
The last checks and Android rename are not an atomic no-overwrite operation
against an unrelated privileged writer. The guarantee is verification at defined
checkpoints, not permanent exclusion of every external actor.

### 14.3 Existing internal-code sketch

**Integration sketch.** All arguments must come from the trusted controller:

~~~kotlin
val retired = ClusterThemeRetiredStorage.move(
    name = ownedFilename,
    parentIdentity = pinnedParentIdentity,
    device = createdDevice,
    inode = createdInode,
    expectedHash = compiledVariant.carrierSha256,
    prior = priorRetirement,
    hash = ::boundedCarrierHash
) { checkpoint ->
    persistRecoveryJournal(checkpoint)
}

ClusterThemeRetiredStorage.verifyForRestart(
    ownedFilename,
    pinnedParentIdentity,
    createdDevice,
    createdInode,
    compiledVariant.carrierSha256,
    retired,
    ::boundedCarrierHash
)
~~~

These are internal classes, not a public external API. The actual caller also
checks runtime cohort, reservation and operation state.

### 14.4 Why a blind shell move is not a recovery recipe

A wildcard move cannot prove ownership, inode/hash, parent generation, durable
intent or target absence. Manual movement can invalidate DashCast's recorded
state. If guarded preparation refuses, inspect its exact reason and prepare a
separately reviewed maintenance plan rather than moving more files.

Never delete or broadly move factory RCCs, foreign carriers, the daemon journal,
helper records or preferences. Do not erase operational debt to reopen loading.

---

## 15. Restart with fission-all

### 15.1 External command

**Maintenance example.** Only after the exact owned carrier was moved and
verified, with the vehicle immobilized in P and parking brake applied, a
separately accepted external attempt is:

~~~bash
adb -P 5038 -s "$SERIAL" reboot fission-all
~~~

With the historical address written explicitly:

~~~bash
adb -P 5038 -s 100.70.244.124:5555 reboot fission-all
~~~

Send one attempt. A client timeout/disconnection is unknown, not permission to
retry. Do not create a reboot loop, boot-time job or background retry watchdog.

### 15.2 In-app protocol service

DashCast authenticates the local ADB connection and opens the fixed service:

~~~text
reboot:fission-all
~~~

The production adapter's shape is:

~~~kotlin
class FixedRestart(
    private val open: (String) -> java.io.Closeable
) {
    private val sent = java.util.concurrent.atomic.AtomicBoolean(false)

    fun dispatch() {
        check(sent.compareAndSet(false, true)) { "No automatic replay" }
        open("reboot:fission-all").close()
    }
}
~~~

The actual workflow additionally persists daemon/app intent before this call,
revalidates after consent/authentication and retains a consumed recovery hold.

Do not substitute a shell reboot, `svc power reboot`, `PowerManager.reboot` or
direct property write. The earlier investigation did not establish Binder REBOOT
permission for DashCast or shell; ADB protocol requests use a different authority.

### 15.3 What was actually tested

The earlier persistent fallback recovered after the bad owned RCC was moved
aside and the user triggered a visible “Restarting” cycle through Country Code
Tool. A plain Android reboot had not restored that state.

Later, one external `adb reboot fission-all` was tested from a **normal OEM
baseline**. Android/IVI boot identity and uptime changed; the user confirmed
Restarting followed by normal BYD instruments.

This is not proof of recovery from every failed QML load, native consumer death
or controller crash. The new integrated local-ADB sequence still needs stationary
vehicle validation.

Country Code Tool also writes country configuration and calls an MCU-facing
configuration API. Those operations are not copied into DashCast recovery.

### 15.4 Read-only checks after reconnection

~~~bash
device_adb get-state
device_adb shell cat /proc/sys/kernel/random/boot_id
device_adb shell cat /proc/uptime
device_adb shell getprop ro.boot.bootreason
~~~

Compare with recorded pre-restart observations. Keep raw boot identities private.
An ADB connection, uptime reset or boot tag alone does not prove native recovery.

Use the current selection check and physically inspect factory instruments,
gear/speed/status, required telltales and warning presentation. Confirm the
normal layout, not merely the absence of a custom rectangle.

---

## 16. Simple mode and interrupted recovery

### 16.1 “Simple mode running”

1. Remain stationary.
2. Do not load the same failed carrier again.
3. Open DashCast on the central display if available.
4. Use **Restore cluster** and its current next step.
5. If eligible, preserve/move and verify the exact owned RCC.
6. If required evidence is missing, stop that path and export bounded diagnostics.
7. Do not keep pressing Power, repeating reboots or changing country settings.
8. Use only a separately accepted maintenance attempt with its prerequisites.
9. Inspect the actual factory display before declaring recovery complete.

If the application or independent ADB authority is unavailable, this guide
cannot promise recovery. Use appropriate qualified service support rather than
unverified vehicle-interface commands.

### 16.2 Why restart must follow verified retirement

A persistent custom selection can point to the same bad carrier after Android
restarts. Restarting first can repeat its loading failure.

The sequence is **identify → preserve/move → verify → accept restart → verify
factory selection and physical instruments**.

Qt resource mappings/references can remain alive after a file moves, so an
unchanged current frame does not by itself mean that the move failed.

### 16.3 Pending intent is deliberate

After dispatch admission, a double click, recreated page, replaced daemon,
exception or same-boot reconnect must not authorize another restart.

An unused confirmation expires after two minutes. A consumed intention does not
expire into permission to resend. A matching historical completion receipt covers
the crash between durable daemon completion and application acknowledgement;
it does not pretend the current pixels were inspected again.

---

## 17. Performance and native lifetime

### 17.1 Preserve the existing Presentation owner

The inspected large Presentation source contains one `AndroidNativeWindowItem`
associated with `xdjaVirtualSurface`, mode 1, `multi_view: true` and 1920 × 720
geometry. Its plugin and native surface contract are OEM dependencies.

Do not add a sibling native consumer or assume that opening an Android window
proves that the cluster consumed its frames.

### 17.2 Known native uncertainty

REA found CenterContainer retaining a current-child raw pointer and clearing it
when rescanning shows that it is absent. This alone did not establish a
use-after-free.

The CenterChild opacity path can allocate a parentless `QPropertyAnimation`.
Observed start calls use KeepWhenStopped; complete native cleanup remains
unresolved. Destroying arbitrary native children to save resources is therefore
not an established safe optimization.

The desktop performance bench suspends an existing Loader in owned fixtures.
It proves bounded fixture object/Timer behavior, not measured vehicle CPU, RAM,
GPU or heat reduction.

### 17.3 Resource lifetime

Registration, current QML objects, resource references and lookup-file lifetime
are separate. A moved file can still have mapped/referenced contents.
Unregistering a resource does not necessarily invalidate existing references.

This is why selection change, file retirement and native reinitialization need
separate evidence.

### 17.4 Retired artifact

~~~text
Profile: full-app-21f7735c
SHA-256:
b121e6622f560a04a583c352977813ebc69047635f28cc0dee77ffeec78d007b
Purpose: recovery recognition only; never load again
~~~

Do not modify those bytes, map that ID to a new artifact or remove its refusal.

---

## 18. Troubleshooting and acceptance records

### 18.1 Record facts, not inferred success

Keep app/daemon versions, exact cohort and candidate hashes, input preservation,
patch reversal, static/desktop results, runtime admission, bounded receipts,
physical observation, retirement verification, restart attempt and final return.

A useful minimal record is:

~~~json
{
  "checkpoint": "2026-10-26",
  "candidate_sha256": "record-the-full-hash",
  "runtime_profile_admitted": false,
  "selection": "UNKNOWN",
  "registration": "UNKNOWN",
  "physical_candidate_observed": false,
  "retirement_verified": false,
  "restart_requested_once": false,
  "boot_generation_changed": false,
  "factory_selection_observed": false,
  "physical_factory_return_confirmed": false
}
~~~

| Symptom | Next investigation |
| --- | --- |
| UI version mismatch | Recheck exact source/cohort; do not rename incompatible bytes. |
| Resource not found | Compare basename, direct lookup, host mapping and preserved state. |
| Registration failure | Inspect binary format, compression, aliases and matched inputs. |
| Selection changed, pixels did not | Inspect separate resource-application evidence. |
| Source 1 but own IVI path | Treat selection as custom, not restored. |
| Moved carrier, old picture remains | Consider retained native mapping; do not move more files blindly. |
| Retirement unavailable | Inspect baseline/ACK3/ownership eligibility; do not manufacture a journal. |
| No restart popup | Inspect proof, residual names, helpers, resources, cohort and protocol. |
| Restart timeout | Keep intent; observe after transport returns without auto-repeat. |
| Default selected, fallback still visible | Physical recovery remains unresolved. |
| Desktop succeeds, vehicle fails | Desktop API support is not OEM contract compatibility. |

Share bounded stage/refusal details and what was visible. Keep tokens, pairing
codes, private keys, raw journals, route text, notifications and unrelated personal
data out of reports. Preserve the exact failed artifact privately.

---

## 19. Implementation and primary references

### Repository source

- [Protocol fields and response interpretation](../app/src/main/java/com/byd/dashcast/clusterlive/ClusterThemeProtocol.kt)
- [Names, paths and limits](../app/src/main/java/com/byd/dashcast/clusterlive/ClusterThemePolicy.kt)
- [Compiled profile identities](../app/src/main/java/com/byd/dashcast/clusterlive/ClusterThemeVariant.kt)
- [Pinned Android media directory](../app/src/main/java/com/byd/dashcast/proxy/daemon/ClusterThemeAndroidStorage.kt)
- [Bounded Binder helper](../app/src/main/java/com/byd/dashcast/proxy/daemon/ClusterThemeIviHelper.kt)
- [Query/load/default exchange](../app/src/main/java/com/byd/dashcast/clusterlive/ClusterThemeIviExchange.kt)
- [Pinned application session](../app/src/main/java/com/byd/dashcast/proxy/ClusterThemeSession.kt)
- [Daemon and durable recovery](../app/src/main/java/com/byd/dashcast/proxy/daemon/ClusterThemeLiveTest.kt)
- [Retirement and verification](../app/src/main/java/com/byd/dashcast/proxy/daemon/ClusterThemeRetiredStorage.kt)
- [Restart file inventory](../app/src/main/java/com/byd/dashcast/clusterlive/ClusterRestartFiles.kt)
- [Restart cohort identity](../app/src/main/java/com/byd/dashcast/clusterlive/ClusterRestartCohort.kt)
- [Fixed ADB service adapter](../app/src/main/java/com/byd/dashcast/infrastructure/FixedClusterRestartConnection.kt)
- [RCC codec](../tools/cluster/build_live_marker.py)
- [Matched-cohort builder](../tools/cluster/build_live_cohort_marker.py)
- [Activity export declarations](../app/src/main/AndroidManifest.xml)

### Investigation and workflow

- [Android IVI RCC investigation](CLUSTER_ANDROID_IVI_RCC_TEST.md)
- [Current guided recovery](CLUSTER_GUIDED_RECOVERY.md)
- [Independent restart investigation](CLUSTER_INDEPENDENT_RECOVERY.md)
- [Read-only selection query](CLUSTER_READ_ONLY_RCC_QUERY.md)
- [Native dashboard](CLUSTER_NATIVE_DASHBOARD.md)
- [Offline minimal bench](minimal-cluster-bench.md)
- [Offline performance bench](minimal-cluster-performance-bench.md)
- [1.9.80 notes](releases/1.9.80-beta.md)

Those investigations retain source addresses and REA evidence anchors where
available. This guide does not embed private decompilation or raw vehicle records.

### Primary upstream source

- [Qt 5.15 RCC command-line implementation](https://raw.githubusercontent.com/qt/qtbase/5.15/src/tools/rcc/main.cpp)
- [Qt 5.15 resource lifetime](https://raw.githubusercontent.com/qt/qtbase/5.15/src/corelib/io/qresource.cpp)
- [Qt 5.15 Loader implementation](https://raw.githubusercontent.com/qt/qtdeclarative/5.15/src/quick/items/qquickloader.cpp)
- [Android 10 ADB command handling](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-10.0.0_r1/adb/client/commandline.cpp)

These references explain upstream API/tool behavior. They do not establish
the proprietary plugin ABI, exact OEM Qt build or BYD-specific restart outcome.







