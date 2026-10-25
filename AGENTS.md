## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user invokes `$graphify` in Codex (or `/graphify` in another assistant), use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Project-local Automotive specialists

These rules extend the global multi-agent routing instructions for this project only. Keep all six global agents (triage, quick, build, deep, escalate, verifier), their models/reasoning levels and the ROUTING-LESSONS.md mechanism unchanged. Project specialists are discovered from `.codex/agents/*.toml`; do not add a global registry or overwrite global configuration.

### Routing and ownership

1. The main Codex agent calls global triage first for every coding task. Triage reads the existing global ROUTING-LESSONS.md and returns its unchanged routing JSON; the main agent selects quick, build or deep.
2. Assign exactly one implementation owner, with an explicit change scope and files. Only that owner edits the assigned change. Never allow concurrent edits or overlapping ownership of a modification.
3. The main agent alone selects and calls relevant Automotive specialists for advice before implementation and review after implementation. Do not invoke all five by default. Specialists never spawn peers, replace triage/verifier, edit files or take implementation ownership.
4. Reviews return evidence (file/line and runtime/firmware context), uncertainties, severity/blockers, recommendations and required checks. The owner implements fixes; reviewers never apply them. The main agent consolidates findings. Hardware compatibility and safety must not be claimed from static/JVM review alone.
5. Use global verifier when checks or risk justify independent verification. It runs appropriate checks and never edits files. Resolve failures before reporting successful completion; specialists do not replace it.
6. For unresolved or exceptional problems, stop the current owner before an explicit ownership transfer to global escalate. No overlapping writers during escalation.
7. Preserve the existing global four-subagent concurrency limit; sequence specialist reviews when needed. The main agent alone compares triage with actual work/escalation/verifier feedback and follows the unchanged global ROUTING_FEEDBACK_JSON/ROUTING-LESSONS.md learning process. Specialists never write the lessons file.

### Specialist responsibilities and selection

| Specialist | Review responsibility | Select when |
| --- | --- | --- |
| `byd-platform` | DiLink/model/ROM/firmware identification, effective permissions/signatures, OEM/Android/ADB boundaries, runtime services/transports and display/daemon contracts | Platform, manifest/SDK, privileged/OEM APIs, projection/display routing, CAN/VHAL/SOME/IP or daemon contract changes |
| `automotive-hmi` | Legibility, interaction density, touch/focus/IME/accessibility, orientation and navigation presentation on central display, cluster and HUD | UI/resources, mirror coordinates, multi-app layout or navigation presentation changes |
| `automotive-connectivity` | Satellite pairing/trust, pinned WSS/TLS, WebRTC/WebView, reconnect/timeouts, navigation freshness/source selection and IPC contracts | Pairing, network/protocol, remote navigation/video/control or IPC changes |
| `automotive-performance` | Main-thread work, concurrency, polling, captures/composition, latency, memory/heat and resource/daemon lifecycle | Hot paths, persistent services, mirroring/video, polling, wake/sleep or lifecycle changes |
| `automotive-safety` | Distraction, unknown movement state, stale guidance, wrong display/input, OEM visibility/recovery, remote control and privileged vehicle effects | Driver restrictions, cluster/HUD output, failure/recovery behavior, remote control, privileges or CAN/VHAL/SOME/IP changes |

Examples: presentation-only changes use HMI; WSS reconnection uses connectivity (add performance for concurrency/resource effects); polling uses performance; privilege changes use platform and safety; cluster/HUD projection uses platform, HMI and safety. Documentation with no Automotive effect needs no specialist. Select additional reviews only when the actual change warrants them.

### Runtime compatibility and vehicle constraints

- Recheck the current DashCast baseline: native Kotlin/Views/XML, one app module, minSdk 28, targetSdk 29, compileSdk 36. Distinguish code/API presence, permissions actually granted, runtime availability and hardware validation. Cite model/ROM/firmware/API context or explicitly mark it unknown.
- Never assume BYD Android is Android Automotive OS. Optional `android.car`, diagnostic probes, compileSdk and BYD compile-only stubs are not runtime proof. Effective DL3 fallback in `Platform.kt` is not positive platform identification. Current HUD production output selects legacy DL3 outside AAOS; SOME/IP foundations are inactive. Recheck these baselines against the code when they change.
- Preserve daemon ownership: SurfaceDaemon holds persistent surfaces/resources; ProxyDaemon executes commands. Review the actual endpoint/descriptor and release/recovery lifecycle.
- WSS control, navigation freshness and WebRTC video are separate states; none establishes vehicle movement. `ProjectionSafetyPolicy` HOME exclusion is not a driving-distraction lock. Missing, unreadable or stale movement state means unknown, never parked; zero speed alone is insufficient.
- Respect OEM restrictions, alerts and telltales. Never bypass vehicle/driver restrictions, mask safety indications or add arbitrary `distractionOptimized` declarations. Complex setup and sensitive validation require a parked vehicle and suitable test context. Do not execute unverified CAN/VHAL/SOME/IP effects or activate experimental transports merely because code exists.
- All five specialists are advisers/reviewers in read-only mode: no file edits, shell writes, patching, installs, graphify update, builds/tests producing files or commands affecting a vehicle. Effective parent/environment permissions may override sandbox settings, so these behavioral restrictions remain mandatory. Implementation and checks belong to the assigned global owner/verifier. Follow the existing Graphify rules; a configuration-only integration does not require graphify update.

## Open Code Review with Codex

- The native `open-code-review-codex@open-code-review` plugin provides OCR review skills. In this project use `$open-code-review-delegate` by default: OCR selects files and resolves rules; Codex performs the review using its current model, with no separate OCR API key. Use `ocr review` only when the user explicitly requests OCR-managed LLM execution with a configured provider.
- Run `ocr delegate preview --format json` from the repository root, then `ocr delegate rule --format json <paths...>` for the selected files. Project rules and filters load automatically from `.opencodereview/rule.json`; keep the built-in language rules via `merge_system_rule`. Tests are explicitly included because upstream excludes many test files by default.
- Follow the existing triage/ownership/specialist/verifier instructions. Delegation does not authorize extra implementation owners, vehicle commands or hardware compatibility claims. Account for every selected file; report skipped files and reasons. Read relevant tests as context even when filters exclude fixtures.
- Keep reports under ignored `.opencodereview/results/` if saving them. Filters select review targets; they do not sandbox Codex or prevent contextual file access. Never read or transmit credential/diagnostic files during reviews. See `docs/OPEN_CODE_REVIEW.md` for installation, usage and checks.
