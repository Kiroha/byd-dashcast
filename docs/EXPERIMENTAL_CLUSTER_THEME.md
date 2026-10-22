# Experimental cluster theme: local fixture only

Diag → **Verify experimental theme (local only)** reads the packaged files on a worker.
It checks bounded asset sizes, SHA-256 against packaged metadata and the one-file,
uncompressed RCC v1 tree/entry/payload. Failures remain local diagnostic results.
It neither installs nor registers resources, opens a Qt engine, connects a daemon,
nor sends any OEM command. The result separates file integrity from rendering,
OEM access and recovery, which remain unestablished. Metadata hashes do not prove
origin or grant access. Android build and positive DiLink detection are passive
context, not proof of the cluster firmware. PackageManager reports only this app’s
Android signature comparison and declared/effective display/instrument permissions
from the manifest. GRANTED or matching signatures do not establish OEM RCC admission;
unavailable checks report UNKNOWN.

`app/src/main/assets/experimental_theme/` contains source `Main.qml`, `theme.qrc`,
compiled `theme.rcc` and `manifest.properties`. The QML uses only QtQuick 2.0:
a dark background, TEST, DEMO - NO VEHICLE DATA, and v0.1.0. No mock telemetry,
OEM imports, callbacks or activation action. The 1920×720 canvas is a fixture,
not validated hardware geometry. It is not the requested final cluster design.

Generate offline with a standard Qt compiler (current artifact: Qt rcc 5.15.13):

```sh
python3 tools/cluster/build_experimental_theme.py --rcc /path/to/qt5/bin/rcc
```

The script forces RCC format 1 and disables compression, and regenerates sizes
and hashes deterministically. No Qt dependency is added to Android. The current
verifier deliberately supports this fixture only, not arbitrary RCC packages.
On source/version/layout changes, update the manifest generator and verifier
expectations together and run the focused tests.

REA target: firmware 13.1.33.2602030.1. Reading bundled assets cannot establish
installed cluster firmware, Qt runtime compatibility, an authorized IVI→RCC
loading path, or reliable restoration. Those require further REA evidence and
bench validation preserving OEM alerts before any vehicle loading work.

The separate [physical marker experiment](CLUSTER_LIVE_MARKER_TEST.md) now implements a gated real OEM-root reload. It uses a different identity-bound carrier and preserves the OEM root code. The local fixture above remains a file verifier and is not its payload or a physical test.
