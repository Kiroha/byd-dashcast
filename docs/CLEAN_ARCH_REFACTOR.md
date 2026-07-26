# DashCast — Clean-Architecture Refactor Plan

**Scope:** decouple the two residual monoliths — `ui/diag/DiagActivity.java` (3,308 L) and
`MainActivity.kt` (1,658 L) — into the architecture the codebase is *already* 70 % converged on,
under an **absolute functional-parity** constraint (zero behavioral change on deployed DiLink 2/3/4/5
and DX_BYD_AUTO cars).

Source of truth for every contract/risk referenced here: `scratchpad/monolith-map.md` (the Phase-1
survey — 6 parallel readers, 94 mapped sections, 30 ranked regression risks).

> **Framing decision (read this first).** This is not a greenfield rewrite. `domain/`,
> `infrastructure/` (a complete Strategy+Chain adapter layer), `data/` facades, `platform/`, and
> **14 already-extracted `ui/main` coordinators** are the target architecture *in flight*. A big-bang
> "regenerate the whole codebase" would manufacture a **false parity guarantee** over 30 documented
> firmware landmines. We therefore refactor by **verified vertical slices**: each seam is moved
> *verbatim* behind a port, compiled, and adversarially parity-audited before the next. Parity wins
> over volume, every time.

---

## 1. Multi-Path Architectural Analysis

The choice hinges on **where this app's complexity actually lives**. It is *not* rich business rules
(the DDD sweet spot — banking, insurance, invariant-dense aggregates). It is **hardware/OS
orchestration**: projecting third-party apps onto a car instrument cluster across five mutually
incompatible firmware stacks, each with a *different privileged mechanism*
(`IActivityTaskManager` reflection, dadb uid-2000 shell, a proxy daemon, the fission service,
AAOS `android.car`). The variability axis is **the platform adapter**, not the entity model.

### Path A — Domain-Driven Design (bounded contexts + aggregates + domain events)

Model bounded contexts (Projection, Diagnostics, Voice, Update), aggregates (`ClusterSession`,
`ProjectionTarget`) with enforced invariants, domain events (`AppProjected`, `ProjectionRestored`),
repositories, and application services orchestrating them under a ubiquitous language.

| Axis | Assessment |
|---|---|
| **Horizontal scalability** | Strong *for teams*: bounded contexts let independent squads own Projection vs Voice vs Diagnostics. But this is **one APK, effectively one maintainer** — context boundaries become ceremony, not parallelism. The scaling axis that *actually* varies (new firmware = DiLink 6, a new OEM) is **orthogonal** to DDD contexts; DDD does not help you add a platform. |
| **Cognitive load** | **High and largely accidental here.** Wrapping "call the right privileged API for this firmware" in aggregates + value objects + domain events is heavier machinery than the thin rule-set warrants. The 30 risks are timing/ordering/hidden-API facts, *not* domain invariants — DDD's invariant enforcement has little to bite on. |
| **Parity risk** | **Highest.** Introducing an event bus / aggregate boundaries reshapes control flow and thread hops — exactly what risks #4 (main-thread runner delivery), #13 (eager-clear-before-async), #15 (deferred enforcement) forbid touching. |

### Path B — Ports & Adapters (Hexagonal), thin tactical-DDD core

An application core defined by **ports** (interfaces) with **adapters** on both sides: *driving*
adapters (Activities/coordinators/receivers that call in) and *driven* adapters (per-platform
privileged implementations that fulfil the ports). A **thin** domain layer holds only the handful of
genuine state machines/invariants (cluster-app state, projection lifecycle, split slot).

| Axis | Assessment |
|---|---|
| **Horizontal scalability** | Excellent **along the axis that varies**: a new firmware is a new set of driven adapters behind existing ports — the core and the other adapters are untouched (`AppLauncher`/`TaskFinder`/`TaskResizer` already prove this: `Iam` / `Shell` / `Proxy` / `AdbLocal` / `PlatformAdaptive` selector). A new capability is one new port + N adapters. This is *linear* growth against real requirements. |
| **Cognitive load** | **Lowest incrementally** — the team already writes this pattern (§3.2 conventions: Strategy+Chain, `Chained*` swallow-per-step, `PlatformAdaptive*` selector, no DI framework). Zero new mental model. Risk: interface proliferation / anemic core — mitigated by keeping the domain layer *thin* and only promoting real invariants. |
| **Parity risk** | **Lowest.** "Move code verbatim behind a port" preserves control flow, thread hops, and ordering by construction — the extraction is mechanical, not a redesign. |

### Path C (rejected, for completeness) — MVVM/MVI + Repository only

Would tidy the UI but leaves the *actual* complexity (privileged platform mechanics) unstructured.
The coordinators already give us the UI decoupling MVVM would; MVI's reducer/state-stream would
fight the imperative, timing-sensitive launch state machine (risks #7, #13, #14, #15). Not a fit.

### ✅ Selection — **Ports & Adapters (Hexagonal) + thin tactical-DDD domain core**

**Justification, specific to this domain:**

1. **The domain is integration-heavy, not rule-heavy.** Complexity lives in *how to talk to each
   platform*, which is precisely what adapters isolate. DDD aggregates would be anemic wrappers.
2. **The codebase is already hexagonal.** `infrastructure/` = driven adapters + chain;
   `domain/cluster/ProjectionStateProvider` = a port (ClusterService is its adapter); `ui/main`
   coordinators = driving adapters. We **complete** an in-flight architecture instead of importing a
   foreign one — the single biggest parity lever.
3. **It scales on the real axis.** Firmware fragmentation (2/3/4/5/AAOS) is permanent and growing;
   hexagonal makes "add a platform" a localized adapter change. DDD scales teams we don't have.
4. **Lowest cognitive load + lowest parity risk** — the two constraints that dominate a
   zero-regression refactor of deployed car software.

We adopt **tactical DDD selectively**: value objects / small state machines only where invariants are
real — `ClusterAppState`, `ProjectionState`, the existing `SplitSlot` — and nowhere else.

---

## 2. Target Folder Structure

Package root `com.byd.dashcast`. **Bold = new**; the rest exists today and is the target we extend.
Layout follows the Hexagonal rings: `domain` (core, no Android deps beyond value types) →
`app/port` (interfaces) → `data`/`infrastructure`/platform adapters (driven) → `ui`/services
(driving). Manifest component *class names stay put* (parity §2.3/§2.10) — extraction happens
*behind* the registered Activities/Services, never by renaming them.

```
com/byd/dashcast/
├── DashCastApp.java                      # composition root (manual DI; see §3)
│
├── domain/                               # ── CORE (thin, framework-light) ──
│   ├── cluster/
│   │   ├── ProjectionStateProvider.kt        # (exists) port: is/stopProjection
│   │   ├── SplitSlot.kt                       # (exists) value object — ordinal = wire protocol
│   │   ├── ClusterAppState.kt            #  value object: {dashboardPkg,name,mainPkg,lastLaunchTs}
│   │   └── ProjectionLifecycle.kt        #  pure state machine: activate/restore/origin transitions
│   └── diag/
│       └── TestOutcome.kt               #  shared PASS/FAIL/WARN/SKIP vocabulary (adapter of runner Status)
│
├── app/                                  # ── APPLICATION / PORTS ──
│   ├── port/
│   │   ├── ShellGateway.java                 # (exists as infra) driven port — uid-2000 shell
│   │   ├── ClusterLauncher.kt            #  driven port: sendToDashboard/toMain/kill/relaunch
│   │   ├── ProjectionController.kt       #  driven port: activate/restore/originCluster
│   │   ├── AutoLaunchPort.kt            #  driven port: pending/session-resume replay
│   │   └── SharePort.kt                 #  driven port: FileProvider ACTION_SEND (+Telegram probe)
│   ├── DashCastApp glue (exists): BootReceiver, AppStartupTasks, BootDisplayCleanup, InstallResultReceiver
│   └── di/
│       └── ServiceGraph.kt              #  manual composition (no framework) — wires ports→adapters
│
├── data/                                 # ── DRIVEN ADAPTERS: state/persistence ──
│   ├── prefs/ClusterPrefs.kt                 # (exists) sole owner of byd_app_prefs
│   ├── prefs/DiagPrefs.kt               #  sole owner of byd_diag_prefs (sniffer path) — new facade
│   ├── apps/AppRepository.kt                 # (exists) cached PackageManager query
│   └── apps/LaunchableAppScanner.kt     #  de-duped ACTION_MAIN/LAUNCHER scan (was ×2 in Diag)
│
├── infrastructure/                       # ── DRIVEN ADAPTERS: privileged platform mechanics ──
│   ├── AdbLocalClient.java                   # (exists) dadb uid-2000 gateway, 4-thread pool
│   ├── launch/  AppLauncher(Iam|Shell|PlatformAdaptive) + LaunchException          # (exists)
│   ├── task/    TaskFinder(Am|Proxy|AdbLocal|Chained) + TaskResizer(Reflection|Shell|Chained)  # (exists)
│   └── proxy/   ProxyResilienceTester.java  #  daemon kill/recovery/storm/rebroadcast (was Diag D5–D9)
│
├── platform/Platform.java                    # (exists) DCL singleton, DiLink 2/3/4/5 + AAOS detection
│
├── ui/                                   # ── DRIVING ADAPTERS ──
│   ├── main/
│   │   ├── MainActivity.kt                    # (shrinks to a thin driving adapter; class name frozen)
│   │   ├── <14 existing coordinators>        # AppList/Navigation/Mirror/Fullscreen/Control/Split/…
│   │   ├── ClusterLaunchController.kt    #  ← onSendToDashboard/toMain/kill/relaunch state machine
│   │   ├── ProjectionCoordinator.kt     #  ← activate/restore/origin (adapter over ProjectionLifecycle)
│   │   └── AutoLaunchCoordinator.kt     #  ← pending + session-resume replay (dual-trigger preserved)
│   └── diag/
│       ├── DiagActivity.java                  # (shrinks to tab host + wiring; class name frozen)
│       ├── DiagNavHost.java             #  tab/panel switch + swipe + nav rail (Group A)
│       ├── panel/
│       │   ├── TestSuitePanel.java      #  generic bench (collapses Beta/DL5/DL2/DL4/Mirror ≈600 L)
│       │   ├── TestSuiteSpec.java       #  per-bench deltas: WARN?, counters fmt, report fn, D8Params
│       │   ├── ClusterDl5Panel.java     #  recon+VD+counters (stays whole — shared views/state)
│       │   ├── ClusterPocPanel.java     #  POC + sliders (delegates resilience to infra/proxy)
│       │   ├── SnifferController.java    # ★ reference slice (§5) — self-contained, survives death
│       │   ├── AdasPanel.java           #  show/hide service-call
│       │   ├── ExportApkPanel.java      #  scan/copy/share/auto-delete
│       │   └── VoicePanelController.java#  receiver + 3 engine slots + strict teardown order
│       └── SysInfoActivity.kt / (log) LogActivity.kt   # (exist) unchanged
│
├── voice/  (exists) VoiceService, wakeword/WakeWordEngine, VoskTranscriber, LlmVoiceEngine, VoiceCommandRouter
├── util/   AppLogger.kt, LocaleHelper.kt (exist)  +  ShareHelper.kt  +  UiThread.kt (safeRunOnUiThread)
└── report/ BugReportCapture.java, BugWizardActivity.java (exist)
```

**Boundary rule:** driving adapters (Activities, coordinators, receivers) may depend on ports and
domain; driven adapters implement ports and may touch Android/privileged APIs; **domain depends on
nothing Android-specific**. `AppLogger`/`Platform`/`ClusterPrefs` are cross-cutting singletons reached
by every ring (unchanged — §3.2 conventions).

---

## 3. Dependency Injection & Error Boundaries (how modules talk)

**DI — manual composition root, no framework** (§3.2 rule #1 — Dagger/Hilt/Koin explicitly out).
`DashCastApp.onCreate` already bootstraps in a fixed order; we add a single `ServiceGraph` that
constructs the driven adapters once and hands ports to driving adapters via constructor injection:

```
DashCastApp
  └─ ServiceGraph(appContext)
       ├─ ClusterLauncher      ← ClusterLaunchController(prefs, sessionTracker, adb, proxyChain, …)
       ├─ ProjectionController ← ProjectionCoordinator(ProjectionLifecycle, navCoord, adb.restore*)
       ├─ SharePort            ← ShareHelper(appContext)                 # folds 4 duplicate senders
       └─ ports handed to MainActivity/DiagActivity in setupCoordinators()/bind*()
```

Activities receive **ports, never concretes** → each controller is unit-testable with a fake port
(e.g. a fake `ShellGateway` returning canned output — the Strategy+Chain layer already supports this).

**Error boundaries — fail-open, never crash the car UI** (§3.2 rule #9), made explicit per ring:

- **Driven adapters** catch `Throwable`, `AppLogger.w(TAG, …)`, return a *safe default*
  (`Platform` probes → `true` on uncertainty; hidden-API misses → `null`, never throw — risk #23).
- **Chain adapters** (`Chained*`) log-and-swallow **per step**, advancing to the next strategy.
- **Driving adapters** wrap each async callback in `UiThread.run{ }` — the promoted `safeRunOnUiThread`
  with its **double `mDestroyed` check** (risk #12) — so a late callback can never touch a dead view.
- **Runners** keep their existing contract: deliver on main via their static
  `Handler(Looper.getMainLooper())`, gated by `mDestroyed` set *first* in teardown (risk #4).

No boundary is *added* on a hot path: the touch forwarder (risk #1) and TextureView tuning (risk #2)
are explicitly excluded from any wrapping.

---

## 4. Code-Splitting & Inter-Module Communication Matrix

| Monolith region (lines) | → Extracted module | Comms mechanism | Parity anchors preserved |
|---|---|---|---|
| Diag A1–A9 tab mgmt (L90–412) | `DiagNavHost` | direct calls; `showPanelForTab` switch retained | 16 tab consts, default-tab **DL2>DL4>DL5>Beta** (risk #30), lazy-prep flags |
| Diag B1–B5 benches (≈L416–1504) | `TestSuitePanel` + `TestSuiteSpec×5` | spec object (Strategy) parameterizes deltas | glyph/color map, counters fmt (Mirror reuses `diag_dl5`), WARN-absent for Beta, main-thread delivery (risk #4) |
| Diag C1–C3 cluster-DL5 (L774–1077) | `ClusterDl5Panel` (whole) | owns shared views/state | HARD-RULE DL5-only ×2 (risk #9), VD surface orphan quirk (risk #21), shared `TestDef/Result/Status` |
| Diag D1–D9 POC+daemon (L1903–2559) | `ClusterPocPanel` + `infrastructure/proxy/ProxyResilienceTester` | port call into infra | **dadb-not-verb kill** (risk #5), 10 s cooldown/3 s latch/200 ms settle timings |
| Diag E1–E2 sniffer (L1506–1895) | **`SnifferController`** (reference §5) | `DiagPrefs` facade + tmpfs contract | kill+touch atomicity (risk #11), `Locale.US` SDF (risk #22), restore-on-entry, survives process death |
| Diag F1 ADAS (L1543–1591) | `AdasPanel` | `ShellGateway` port | `service call … s16 "" 2>&1`, svc-name `auto_container`/`AutoContainer` branch |
| Diag G1–G3 export APK (L2561–2832) | `ExportApkPanel` | `SharePort` + PackageManager | needle set, 300 s auto-delete, Telegram fork order, `Locale.ROOT` match |
| Diag H1–H5 voice (L2834–3306) | `VoicePanelController` | LBM receiver + engine ports | strict enable/teardown order (risk #3), `voicePanelBound` gate (risk #18), Vosk ctor side-effects (risk #19), switch-chaining (risk #17) |
| Main M13–M14 launch SM (L747–1031) | `ClusterLaunchController` | `ClusterLauncher` port + `ClusterAppState` | INC-20260621 stop-before-launch (risk #7), grace `mLastLaunchTime` (risk #14), 2500 ms enforce (risk #15), eager-clear (risk #13) |
| Main M16 activate/restore (L1136–1359) | `ProjectionCoordinator` + `ProjectionLifecycle` | `ProjectionController` port | restore 6 s protocol timing (risk #26), eager-clear-before-evict, DL5-only guards |
| Main M15 mirror/binder (L1035–1134) | fold into existing `MirrorCoordinator` | direct | removes duplicated binder push, DL3 keepalive (risk #8), 250 ms teardown-first (risk #16) |
| Main M18/M4 auto-launch (L199–227,1389–1400) | `AutoLaunchCoordinator` | direct | **dual-trigger** replay (risk #20), read-before-cleanup ordering (risk #6) |
| Cross-cutting: `safeRunOnUiThread`, share×4, `copyFile`/`formatBytes`, launchable-scan×2 | `util/UiThread`, `util/ShareHelper`, `data/apps/LaunchableAppScanner` | promoted singletons | double `mDestroyed` check, ClipData-raw-uri vs Telegram-probe variants both kept |

**Inter-module communication is deliberately *not* an event bus.** It stays: (a) **constructor-injected
port calls** for command flow, (b) the **existing LocalBroadcastManager** (cached-instance pattern,
§3.2 #11) for the async voice pipeline — because those 5 actions + extras are a frozen contract
(§2.1) and LBM's *synchronous* delivery is what makes the pre-allocated-Intent optimization safe
(risk, map §2.5), and (c) **`Callback{onSuccess/onError}` on the 4-thread pool** for shell results,
posted to main by the caller. Introducing a new bus would violate parity, so we don't.

---

## 5. Regression Risk Matrix — Top-2 Vectors & Engineered Safety Mechanisms

The full ranked 30 are in `monolith-map.md §5`. The **two vectors that dominate** this migration:

### 🔴 Vector 1 — Silent behavioral drift in extracted timing/ordering-sensitive code

The monoliths encode *incident-driven* ordering and timing that no type system protects:
enable-before-consumer / detach-before-release (risk #3), dadb-not-verb daemon kill (risk #5),
eager-clear-before-async (risk #13), 250/2500/6000 ms load-bearing delays (#16/#15/#26),
kill+touch atomicity (#11). Moving these lines "cleanly" is exactly how they silently break.

**Engineered safety mechanisms:**
1. **Verbatim move, not rewrite** — each seam is `git mv`-in-spirit: method bodies transplanted
   character-for-character behind the port; `migrated verbatim` markers + incident IDs preserved
   (§3.2 #10). The diff is *relocation*, reviewable line-by-line.
2. **Characterization tests written *first*** (per risk, map §5 mandate) — before extracting a seam,
   a JUnit/Robolectric test pins its observable contract (e.g. `SnifferController` emits
   `kill … ; touch …` as **one** shell string; formats filenames with `Locale.US`). The test is
   green on the monolith, must stay green on the extraction.
3. **Compile gate + adversarial parity audit per slice** — `./gradlew :app:compileDebugJavaWithJavac`
   must stay green (baseline: green, deprecation-only), then an independent reviewer diffs the
   extraction against the §2 contract list and the §5 risk for that seam. No slice merges on a single
   pair of eyes.

### 🔴 Vector 2 — Broken external contracts (strings/IDs the compiler can't see)

Prefs keys, intent actions + extras, broadcast actions, view IDs, R.string keys, shell strings, file
paths (§2.1–2.11). A renamed key **discards deployed-device state**; a changed action **silently
drops** a broadcast; a dropped view ID **NPEs at inflate**. None are compiler-visible.

**Engineered safety mechanisms:**
1. **Facade ownership freeze** — every contract string lives behind exactly one owner
   (`ClusterPrefs`→`byd_app_prefs`, new `DiagPrefs`→`byd_diag_prefs`, `LocaleHelper`→`byd_prefs`,
   `LlmVoiceEngine`→`dashcast_llm_prefs`). Extraction *references* the constant, never re-literals it,
   so a typo can't fork the key.
2. **Contract-lock test** — a `ParityContractTest` asserts the frozen literals
   (`"byd_app_prefs"`, `"com.byd.dashcast.voice.COMMAND"`, `EXTRA_QUICK_SWITCH_PKG`, the view-ID set
   per layout, FileProvider authority `com.byd.dashcast.fileprovider`, port `5555`) so any rename
   fails CI, not a car.
3. **Manifest immutability** — registered component *class names* (`.ui.diag.DiagActivity`,
   `.MainActivity`, services, receivers) are frozen; all splitting happens *behind* them. Zero
   manifest edits in the extraction phases.

---

## 6. Reference Implementation — `SnifferController` (the ★ vertical slice)

Chosen as the first real extraction because it is **self-contained yet non-trivial** (Diag E1–E2,
≈300 L), it **survives process death** (so it exercises the persistence port), and it concentrates two
top risks — **#11 kill+touch atomicity** and **#22 `Locale.US` filename formatting** — making it the
ideal proof that "extract behind a port with true parity" holds. It is produced as a **fully-realized,
compiled file on disk** (isolated worktree), not pasted-and-hoped. Its shape:

```
ui/diag/panel/SnifferController.java     (driving adapter)
  ctor(Activity host, View panelSniffer, DiagPrefs prefs)   // constructor DI — testable in isolation
  onEnter()                        // restoreSnifferState() — probes ACTIVE/STOPPED every entry
  start()/stop()/snapshot()/export()/cleanup()   // verbatim bodies, one merged kill+touch shell cmd
  private buildSnifferFile()       // SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)  ← frozen
  private final AtomicReference<File> mSnifferFile   // was `volatile File`
  contracts referenced, never re-literalled:
     DiagPrefs.RE_SNIFFER_PATH  ("re_sniffer_file_path" in "byd_diag_prefs")
     TMP tag "/data/local/tmp/.re_sniffer_run", pids "/data/local/tmp/.re_sniffer_pids"
     prefix "BYD_RE_Sniffer_"
  error boundary: all shell callbacks → UiThread.run{}(double mDestroyed check); fail-open + AppLogger.w
```

`DiagActivity` shrinks to: `mSniffer = new SnifferController(this, panelSniffer, DiagPrefs.get(this));`
in `bindSnifferPanel`, `mSniffer.onEnter()` in `showPanelForTab`, and lifecycle forwarding. Net Diag
delta ≈ −290 L. **The other seams follow this exact template** — that is the deliverable, not a 45 k-line
paste.

---

## 7. Staged Rollout (each stage = its own verified PR)

Ordered by ascending risk so the machinery is proven on safe seams before the dangerous ones:

1. **Cross-cutting promotions** — `UiThread`, `ShareHelper`, `LaunchableAppScanner`, `DiagPrefs`
   (pure additions; callers rewired one at a time).
2. **`SnifferController`** (reference slice — §6) · **`AdasPanel`** · **`ExportApkPanel`**
   (self-contained, low risk).
3. **`TestSuitePanel` + specs** (the ≈600-line collapse — high value, medium risk; the 5 benches
   migrate one at a time, each parity-audited against its counters/report/WARN deltas).
4. **`ClusterDl5Panel`**, **`ClusterPocPanel` + `ProxyResilienceTester`** (whole units; destructive/
   DL5-only guards + dadb-kill timing are the audit focus).
5. **`VoicePanelController`** (strict engine ordering — characterization tests on enable/teardown).
6. **MainActivity**: `AutoLaunchCoordinator` → `ProjectionCoordinator`/`ProjectionLifecycle` →
   `ClusterLaunchController` (the core state machine — last, most incident-laden, most tests).

**Gate for every stage:** characterization test green on monolith → extract verbatim → compile green
(`:app:compileDebugJavaWithJavac`) → adversarial parity audit vs §2/§5 → merge. Never touch the touch
hot path (#1) or TextureView tuning (#2).
