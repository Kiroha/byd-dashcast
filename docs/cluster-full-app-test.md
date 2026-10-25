# Isolated full-screen presentation prototype

The rewritten candidate is an **offline development artifact**. It has no runtime
variant, Binder operation or Diag load path, and is not packaged in the APK. A
successful PC test is not admission to a vehicle test. The persistent OEM loader
still has no demonstrated independent recovery after a root-construction failure.

This source change keeps versionCode 715 and daemon protocol 44 unchanged. Any
future APK release must increase the version code and verify that the actual
running daemon has been replaced by this build. App-side guards alone cannot
establish that an older, already running daemon enforces the new refusal.

## Retired profile and recovery

The released `full-app-21f7735c` profile is recovery-only. Its operation 24, profile
ID, SHA-256 `b121e6622f560a04a583c352977813ebc69047635f28cc0dee77ffeec78d007b`,
asset bytes, dashboard flags and ADAS debt schema remain unchanged. This preserves
recognition of existing 1.9.75 journals and the identity of a carrier already on a
vehicle. It does **not** make the old carrier safe to load.

The UI, app-side session and daemon reject a new legacy load. The daemon refuses
before staging, baseline queries, native bootstrap or a new journal is created.
Its refusal can read an existing journal through `status()`; it does not erase
recovery debt. Reopening, recording a new dashboard success and starting ADAS
masking are disabled. Closing, read-only status, factory restoration, existing
ADAS recovery and explicitly admitted carrier retirement remain available.

An authenticated negative restoration result previously lost its
`retirementEligible` metadata on the dashboard path. The result now reaches
recovery accounting without sending native close/apply commands. It retains
QUARANTINE and debt; it can authorize only the separate, bounded preservation
operation after its existing exact-own-file/selection checks. It never becomes a
successful graphical restoration. A restart discards the temporary eligibility
and requires a new authenticated result.

## Candidate architecture

`tools/cluster/build_full_app.py` writes a separately identified candidate under
`artifacts/cluster_full_app_candidate/`. Its content-derived filename retains the
OEM-required `Di3F_DynastyOceanTranslate_V0001_` prefix. The generator refuses an
output location under `app/`, pins the original inputs and preserves all 84
translation payloads and metadata. Both original root imports are retained;
`qml/cluster.qml` is byte-identical to stock.

In `qml10_25/cluster.qml`, the only replacement is the existing
`presentationLoaderId` object. Every added declaration is enclosed in a single
marker block and comes from `PresentationLoader.qml.inc`. There are no instrument,
warning, gear, odometer, battery or media opacity changes, no new Binding type and
no ADAS command. The existing layer ordering is retained. A logical 1920×720
presentation canvas does not establish the actual composition of a vehicle's
native layers.

The existing presentation branch loads an external owned `FullApp.qml` only for
the pinned large-panel/full-mode/DiLink context. `powerGear == 3` means power ON,
not parked. The child wraps exactly one stock Presentation URL; it does not add a
second sibling native-window consumer. That original prefab supplies
`AndroidNativeWindowItem`, `xdjaVirtualSurface`, mode 1 and multi_view. Its source
hash is pinned; no new Fission API or resource owner is introduced.

The parent verifies the child's construction contract and required notifications,
handles missing/invalid/null children, and observes the inner Loader's state.
On failure it latches back to the original stock Presentation URL for the life of
that parent. An error in the fallback does not create a retry loop. Selection
intent is tracked separately because Qt may clear `Loader.source` after an
incubation error. Returning to the candidate requires a new parent, not a timer.

`componentConstructed` and `presentationCreated` are only QML construction facts.
`androidFrameState` remains `UNVERIFIED`. A frozen, missing or failed Android app
can coexist with a ready QML wrapper. This prototype neither claims to detect
those pixels nor hides OEM indications in response to a ready Loader. A callback
exception after the construction marker likewise cannot authorize a pixel claim.

## Verification

The committed candidate can be inspected and tested without firmware inputs:

```sh
python3 -m unittest discover -s tools/cluster -p test_full_app.py -v
/path/to/qt5-python tools/cluster/validate_full_app_qt5.py --output /tmp/full-app-qt-check
```

The Qt validator reads all added declarations from the final candidate RCC and
uses its actual unchanged standard import header. It executes the owned child
bytes. A test-only owned Item stands in for the stock Presentation prefab; no
complete OEM root, proprietary plugin, native command or vehicle connection is
executed. Optional `--presentation-source /path/to/Presentation.qml` additionally
checks the exact factory source hash and its known native-window fields.

Coverage includes a valid candidate, missing/invalid children, required-property
creation failure, incomplete construction contracts and notifications, callback
exceptions before/after construction, stock-prefab failure, source loss after
success, context changes/cancellation, bounded fallback and logical single-fixture
ownership. A mutation adding an invalid declaration must fail; the original b121
carrier remains a negative control for the Binding.restoreMode failure. Structural
checks ensure no extra declaration outside the tested block can escape fidelity
comparison with the original root.

Rebuilding requires explicitly supplied, hash-matched local firmware inputs:

```sh
python3 tools/cluster/build_full_app.py \
  --stock /path/to/commonTranslate.rcc --roots /path/to/extracted
python3 tools/cluster/build_full_app.py \
  --stock /path/to/commonTranslate.rcc --roots /path/to/extracted --check
```

`extracted` contains `theme1/qml/cluster.qml`,
`theme2/qml10_25/cluster.qml` and `theme2/qml10_25/alwaysDisplay/Presentation.qml`.
The generator is source-only and never loads these OEM components.

Before future vehicle admission, independent recovery, native surface lifecycle
and actual composition/visibility must be established, including failure before
root creation, loss after a first frame and controller-process termination. No
country change or arbitrary native command is established as a universal recovery
procedure. The Qt 5.15.14 PC environment and Qt 5.15.19 documentation do not establish
the exact Qt patch loaded on the vehicle.
