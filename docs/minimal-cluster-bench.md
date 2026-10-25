# Minimal 1920×720 cluster bench prototype

This is an **offline PC prototype** of one application rectangle covering the
whole 1920×720 canvas. The intended QML result has no OEM pictograms, text, bands,
frame or dashboard. Missing, failed or removed application content leaves opaque
black. The supplied PNGs are Qt-rendered PC simulations with synthetic application
content. They are not Waze images or photographs of a vehicle.

The prototype is separate from `tools/cluster/full_app/`, which preserves the OEM
visuals. It does not change that candidate, any APK asset, app code, Binder/daemon
operation, diagnostic load path, version or ADAS command. The retired resource
with SHA-256 `b121e6622f560a04a583c352977813ebc69047635f28cc0dee77ffeec78d007b`
remains recovery-only and must never be modified or reactivated.

## Quarantine and inputs

`build_minimal_app.py` produces files only under an explicitly chosen offline
output directory, by default ignored `artifacts/cluster_minimal_app_bench/`:

- `OFFLINE_ONLY_Minimal1920x720_<content-hash>.rcc`;
- `OFFLINE_ONLY_minimal_1920x720_manifest.json`;
- `factory_translation_input.rcc`, an exact local input copy for reproducibility.

The candidate filename has no OEM selection prefix. More importantly, it contains
**neither canonical cluster root alias**: no `qml/cluster.qml` and no
`qml10_25/cluster.qml`. Both roots are stored under
`offline/minimal-bench/qml*/cluster.qml`. Registering or renaming this RCC cannot
replace those OEM root URLs. The generator refuses output under `app/`.

All 84 `common/language/` entries retain their original locale fields, flags,
compressed bytes and decoded bytes. The factory Presentation source is stored
as inert evidence at
`offline/minimal-bench/dependencies/FactoryPresentation.qml.source`; that resource
is never loaded. Its canonical runtime URL is not supplied by the candidate.

The inputs must match these SHA-256 values:

| Input | SHA-256 |
| --- | --- |
| Stock translations | `1dfee5a0ad746166cb28d79bddfe9352dc510df6cbcd0001f4751af1a6dbf5e4` |
| Factory `qml/cluster.qml` | `76a2ad22efe281a6d73362d3a3c4e5f998d33e9dff210934f11e4e6071336fb3` |
| Factory `qml10_25/cluster.qml` | `8c9cb017658a904dd34e5b2b1ff0654576031ff4f31d0a0dedd259da52a09631` |
| Factory Presentation source | `72406fdda39b5eef7242d9da7dfba84a540eb801ed6919719a46c0015bcf33b8` |

These identify the existing `21f7735c` source cohort. Similarly named firmware
extractions can have different hashes and are rejected. Exact model, ROM and
firmware Qt patch compatibility are not established by these files.

## Encoded visual behavior

The small theme root remains byte-identical to factory under its research alias;
it is retained as source evidence and is not the minimal visual prototype. The
large theme root retains its original imports, root type and ID, properties,
functions, handlers, other IDs and load-complete message. Three bounded marker
blocks contain every change: root declarations, an initial containment call at
the start of the existing `Component.onCompleted`, and a replacement of the
existing `presentationLoaderId` object. Removing those exact changes restores
the complete factory bytes, including the BOM and line endings.

One permanently invisible Item contains the 11 other direct visual groups.
Those groups include warnings, instruments, ADAS backgrounds, text and debug
output. A snapshot of `rootItemId.children` is visually reparented to that host,
excluding exactly the hidden host, opaque black canvas and existing presentation
Loader by object identity. This list contains direct QQuickItems, not the root's
Connections, Timers or other nonvisual QObjects. `onChildrenChanged` also catches
dynamic navigation, media and animation visuals created under the root.

The containment pass is guarded until hosts exist, has a reentrancy guard with
`finally`, reports exceptions or escaped direct children, and schedules no retry.
Repeated passes are idempotent. Destruction disables the handler. OEM writes to
a child's opacity or visibility cannot make its invisible ancestor visible.

The opaque black canvas is independent of application Loader success. The
existing presentation Loader loads `MinimalApp.qml` only in the large-panel,
DiLink context. This is a rendering context, not proof that a vehicle is parked.
`MinimalApp.qml` adds no decoration and wraps one stock Presentation URL. The
stock source declares one `AndroidNativeWindowItem`, `xdjaVirtualSurface`, mode 1
and `multi_view: true`. No second sibling native consumer is introduced.

The whole proprietary root and its plugins are **not executed in PC validation**.
The resource aliases deliberately do not provide a complete deployable factory
tree; original relative imports under the research root are not resolved here.
This artifact is not an installable vehicle theme.

Keeping the source inventory does **not** prove the native contract is preserved.
Visual reparenting changes parent and anchor relationships, child enumeration and
potential lifecycle behavior. The C++ consumer's complete assumptions remain
unknown. A native surface or ADAS layer composed outside QML may ignore QML
visibility, clipping or the black canvas. Physical blankness and application
pixels are therefore `UNKNOWN`, even with a ready QML Loader.

## Build and checks

Build with explicit local, hash-matched firmware source files:

```sh
python3 tools/cluster/build_minimal_app.py \
  --stock /path/to/exact/commonTranslate.rcc \
  --small-root /path/to/factory-qml-cluster.qml \
  --large-root /path/to/factory-qml10_25-cluster.qml \
  --presentation-source /path/to/factory/Presentation.qml
```

Repeat the same command with `--check` to verify byte-for-byte reproducibility.
The check does not create the output directory or change its files.

```sh
python3 -m unittest discover -s tools/cluster -p test_minimal_app.py -v
/path/to/qt5-python tools/cluster/validate_minimal_app_qt5.py \
  --output /path/to/private/qt5-results
```

The Qt Python environment needs PyQt5 and the original standard Qt import
modules. The measured environment is Qt 5.15.14. No proprietary plugin, native
binary or vehicle connection is required or executed. On the available offscreen
software backend, window `grabWindow()` returns null; the validator instead uses
Qt's asynchronous `grabToImage()` on the **whole bench root**, not only its app
subtree.

The source tests always run. Artifact tests explicitly skip when the ignored
candidate has not been built; build it first for a complete run. A complete run
checks source reversal, original source inventory, every translation's metadata
and stored bytes, all final resource aliases, hash rejection and reproducibility.

The Qt validator reads every final RCC path through Qt, then executes every added
declaration and the actual owned child bytes from that carrier. Standard Qt Items
stand in for proprietary components. Eleven full-screen magenta visual siblings
and dynamically created white visuals expose containment failures above the app
in the whole-root image. The synthetic app has known corners and all four edges;
an independent analytic RGBA expectation checks all 1,382,400 pixels.

Cases cover full content, empty/missing/invalid content, missing/invalid/required-
property child failures, callbacks, source loss after a rendered frame, context
changes, destruction/recreation, dynamic children created during completion and
handlers, direct reattachment, removal, repeated passes and OEM visibility,
opacity and z writes. Owned consumer counters check creation, destruction and a
maximum of one live consumer. Mutation controls must expose an invalid added
declaration, escaped visuals, reparent exceptions and a failed initial call. The
retired carrier's actual `Binding.restoreMode` failure is an expected negative
control, including the captured original standard import header.

The rendered examples are `PC_SIMULATION_full_application.png` and
`PC_SIMULATION_no_application_black.png`. They prove the owned PC fixture's pixel
contract. Black on failure is a bench result, not successful OEM restoration.

## Before any separate vehicle proposal

`runtime_activation_allowed` and `road_use_admitted` remain false. Native pixels,
native contract semantics, parked proof and OEM restoration remain `UNKNOWN`.
There is no runtime profile or operation to activate this research resource.

An installable derivative needs a separate review and verified independent
recovery before root creation. The retained common loader destroys the previous
theme before asynchronously creating the next root; a new root's own fallback
cannot recover from its parse or construction failure. No arbitrary native
command or country change is established here as universal recovery.

Admission also requires the actual C++ root/child contract, native surface and
daemon ownership, external layer composition, physical telltale visibility,
first-frame and stale-frame behavior, content loss, controller death and physical
restoration to be validated in a suitable parked test context. This prototype
intentionally suppresses all QML indications and is unsuitable for road use.

`powerGear == 3` means power ON. The pinned QML displays P for `gearMode == 1`,
but that display mapping does not prove valid, fresh physical P state or its
native data source. No state in this prototype establishes movement or parked
status. A future proposal must obtain a proven P source with validity and
freshness evidence; absent or stale evidence remains unknown and inactive.
