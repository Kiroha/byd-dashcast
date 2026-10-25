# Existing ADAS Loader: offline lifecycle experiment

This separate PC experiment prevents creation of one selected ADAS subtree while
an explicit synthetic projection input is active. It also unloads an existing
instance through its **existing OEM Loader**, then restores that Loader from its
current source binding on exit. The original minimal visual bench and its
artifacts are unchanged.

The result is deliberately partial: the dynamic ADAS children of
`CenterContainer`, warning/status services and their work remain retained.
Actual vehicle CPU, memory, GPU, heat, external rendering and physical restoration
are **UNKNOWN**. There is no installable profile, activation operation, APK asset
or vehicle command in this change.

## Scope and source evidence

The root is the same hash-pinned theme source used by the minimal bench:

| Input | SHA-256 |
| --- | --- |
| Stock translations | `1dfee5a0ad746166cb28d79bddfe9352dc510df6cbcd0001f4751af1a6dbf5e4` |
| Factory small root | `76a2ad22efe281a6d73362d3a3c4e5f998d33e9dff210934f11e4e6071336fb3` |
| Factory large root | `8c9cb017658a904dd34e5b2b1ff0654576031ff4f31d0a0dedd259da52a09631` |
| Factory theme 2 RCC | `0041e6b81f5f2b57c9ee112f4f196a16563345214e1e9d6d7bc75625383812b8` |

The complete factory large root contains `adasLoaderId` at lines 2050–2064,
inside `adasContainerParentId`, a Rectangle. Its source binding selects
`adas2d/Adas.qml`, `adas2d/Adas3R1V.qml` or an empty URL. The only other references
to this Loader are its own guarded Ready handler. There are no other references
to its `item` in that root. The experiment inserts one `active` binding into this
object; it adds no replacement Loader and leaves its source and Ready handler
unchanged.

Read-only resource decoding confirmed these complete extracted files are
byte-identical to entries of the hash-pinned theme 2 RCC:

| Resource | SHA-256 | Relevant source behavior |
| --- | --- | --- |
| `qml10_25/Center.qml` | `b284c991e474a8b2a0369a4151d15b2ee5ecfeaa19fa01a5f617b4eb1293b4d1` | Retained; dynamic child factories, native selection, warnings and menu handling |
| `qml10_25/adas2d/Adas.qml` | `07357aeadc06acdada72641bdc723e863f67acbbf25c0f0376bbadb1617cf135` | Starts a repeating 100 ms radar Timer at completion; 11 DataSource signal handlers |
| `qml10_25/adas2d/Adas3R1V.qml` | `2b9f0e933dee8ec13e6379666dbf3bce2761a02d1dcc796de3b1d4d72b477e10` | Four DataSource signal handlers; no QML Timer |

Neither complete 2D payload contains a DataSource write or method call. Both
inherit the native `CenterChild` type, so that observation does not establish
absence of native side effects. The compiled native cohort has SHA-256
`21f7735cad4408f7baf7086b0e0d7b4ed3d1d2818a35bea6b8ad026102d9f4b1`.
Exact model, ROM, firmware runtime and Qt patch compatibility remain unknown.

The 3D declarations in this root and Center source are commented out. The
presence of 3D QML files is not proof that they are instantiated by this scene;
this experiment makes no 3D workload or savings claim. The supplied fullscreen
reference moves Center ADAS children offscreen while retaining their original
creation predicates. It provides no evidence of unload or timer cancellation.

## Why the native Center children stay

Static native analysis finds a raw current-child pointer in `CenterContainer`.
Its children-change scan clears a current pointer absent from the live child
list; this helps explain the existing QML destroy/null paths, but does not prove
every delayed caller is safe during new churn.

There is a separate unresolved lifetime concern: the native opacity handler can
allocate a parentless `QPropertyAnimation`, and the observed start calls use
KeepWhenStopped. The inspected child destructor body contains no explicit
animation deletion. Native target cleanup and unobserved ownership paths have
not been established; no leak has been measured. The handler does not itself
check `needAnimate` before allocation. Default `needAnimate == false` therefore
cannot independently prove that animated deletion is harmless.

Consequently no Center factory predicate, child pointer, native animation,
warning, DOW, DMS, menu, charge or navigation state machine is modified. The
selected root Loader can also destroy a `CenterChild` payload; that native risk
remains unresolved even though this Loader already owns the QML lifecycle.
Passing the PC fixture cannot remove that hardware admission blocker.

## Encoded bench lifecycle

`dashcastBenchProjectionActive` is an owned input, default false. On this bench,
suspension additionally requires `dilinkName == 2` and `screenSize == 2`. These
are rendering-context checks, not Android-frame readiness or parked proof.
Neither navigation mode, power gear nor speed activates suspension.

Three bounded marker blocks are the complete root change: owned declarations,
an initial reconciliation call, and the existing ADAS Loader's `active` binding.
Removing these blocks restores every original byte, including BOM and line
endings. Imports, IDs, functions, properties and the QML load-complete message
remain present. The small root remains byte-identical under its research alias.

When active, the eleven other root visual groups move to an invisible owned
host. Their native/nonvisual objects remain alive. An opaque black canvas sits
below the unchanged Presentation Loader. New direct visual children are also
contained. When inactive or the rendering context changes, those visual groups
return to the root; the existing ADAS Loader becomes active and evaluates its
current factory source predicate. This is a PC parent/fixture restoration
contract, not verified physical OEM recovery. Reconciliation is guarded against
reentrancy, uses a snapshot, reports errors and schedules no retry.

The factory Presentation Loader, canonical source and consumer ownership remain
unchanged throughout. This change does not control AndroidNativeWindowItem,
SurfaceDaemon, ProxyDaemon, a producer or decoder. Startup, self-check, theme,
terrain, clock and ADAS-distance timers/animations are unchanged. Some completion
callbacks send native messages; blanket stopping would alter OEM protocols.

## Reproduction and checks

```sh
python3 tools/cluster/build_minimal_perf.py \
  --stock /path/to/exact/commonTranslate.rcc \
  --small-root /path/to/factory-small-root.qml \
  --large-root /path/to/factory-large-root.qml
python3 -m unittest discover -s tools/cluster -p test_minimal_perf.py -v
/path/to/qt5-python tools/cluster/validate_minimal_perf_qt5.py \
  --output /path/to/private/perf-results
```

Repeat the builder command with `--check` for byte-for-byte reproducibility.
The separate ignored output is `artifacts/cluster_minimal_perf_bench/`.
The candidate filename starts `OFFLINE_ONLY_MinimalPerf_`, has no OEM selection
prefix and exposes neither canonical cluster root alias. Renaming/registering
it cannot replace the OEM roots. Output below `app/` is rejected. All 84 stock
translation entries retain their locale metadata and original stored bytes.

Source tests always run; artifact tests explicitly skip until an exact-input
candidate exists. A complete run checks every final resource, root reversal,
source inventory, input rejection, aliases, manifest and reproducibility. Qt
reads all 86 final resources through QResource/QFile. It extracts the actual
final owned declarations and modified existing Loader and executes them with
the factory standard Qt import header. The complete proprietary root, payloads
and plugins are never executed. Invalid declaration and Loader-property controls
must fail component construction.

The instrumented payload is an **owned Qt stand-in**: one 100 ms repeating Timer,
one synthetic source-signal handler and lifecycle counters. It is not the actual
ADAS renderer or a measured model of its cost. In particular, the real 3R1V
source has no QML Timer. The fixture's Presentation is synthetic application
pixels with a consumer counter. The report records Qt version, backend, render
loop, resolution, fixture/carrier hashes, completed/live/destroyed fixture
payloads, their maximum concurrent completed count, running fixture timers,
timer firings and handler invocations. Lifecycle counters start at
`Component.onCompleted`: they exclude partially incubated objects and those
cancelled before completion. The same limit applies to the Presentation
consumer counters; these counters do not bound all in-flight allocations.

Identical eight-event inputs and nominal 350 ms observation windows compare the
factory fixture, visual mask and exact optimized declaration. On Qt 5.15.14,
offscreen/software/basic, the first two each completed one payload, retained one
running Timer, delivered eight handlers and observed three timer firings. The
optimized startup recorded zero completed payloads, running timers, firings and
handler invocations, with `Loader.item == null`. Separately, its initially false
`Loader.active` binding is the mechanism that prevents instantiation. The
completion counter alone does not prove absence of partial incubation. Timer
firing counts can vary with host scheduling; these are work counts, not a CPU
speedup measurement.

Warm cycles observe completed payloads reaching zero after deferred deletion,
zero fixture timer/handler work while source events continue, restoration
without a new ADAS event, current source selection, dynamic visual
containment/restoration, rapid synchronous and asynchronous-mode toggles, context
loss, construction failure, explicit recovery, queued source callbacks and
teardown. The asynchronous-mode toggle case does not assert a Loading
transition; the separate teardown case records its actual Loader status (Loading
in the reported run). Every stand-in whose completion was counted receives a
destruction callback. The maximum concurrent completed payload count and
completed synthetic Presentation consumer count are each one. These bounds
exclude partial incubation and concern the fixture, not native animation
allocation or external resources.

Pixel capture runs in separate cases after work-count observations. It checks
the entire 1920×720 synthetic application, opaque black with empty content, and
changed pixels when OEM stand-in parents return. Images are clearly named
`PC_SIMULATION_*`; they are not vehicle photographs or Waze screenshots. An
injected stuck-restoration mutation must produce a bounded reported error.

Unloading a QML object need not evict compiled components, images, process RSS
or driver caches. No memory, VRAM, frame latency, heat or animation savings are
claimed. Center-owned ADAS work and independent native rendering can continue.
All runtime activation, vehicle validation and road admission fields remain
false; native pixels, native contracts, parked proof, vehicle performance and
physical restoration remain UNKNOWN. `native_animation_cleanup` also remains
UNKNOWN. The retired `b121e662…` resource stays
recovery-only. A later vehicle proposal requires independent creation-failure
recovery, native lifetime proof and suitable parked hardware validation.
