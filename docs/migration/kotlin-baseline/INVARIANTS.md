# Kotlin migration — Batch 0 baseline and invariants

Captured on `feat/hud-2.0` at versionCode 639 / 1.9.0, **before any file is ported**.
Every later batch diffs against the artefacts in this directory.

Nothing is migrated in Batch 0. It exists because several of these gates cannot be
established retroactively.

> Related but different: `docs/KOTLIN_MIGRATION_AUDIT.md` is the historical per-lot journal
> from the `switch-kotlin` era (2026-06-12, base v1.5.1 / build 437, "123 Java, 0 Kotlin,
> 0 test"). Its figures are stale and it is narrative. This file is the mechanical gate set
> for finishing the migration. Keep both; do not merge.

---

## Why these checks exist

`app/build.gradle` sets `lint { abortOnError = false }`. **A green build does not mean a clean
lint.** The lint XML report is the only real gate.

---

## Baseline values

| Artefact | Value | File |
|---|---|---|
| Binder descriptor | `com.byd.dashcast.proxy.daemon.IProxyDaemon` | `wire-constants.txt` |
| `TXN_*` constants | **37** | `wire-constants.txt` |
| `app_process` entry points | **3** | `entrypoints.txt` |
| Unit test cases | **738** | `testcount.txt` |
| Lint issues (release) | **0** | `lint-issue-count.txt` |
| Files using raw strings | **3** | `rawstring-allowlist.txt` |

Compiled-class paths. Both are non-obvious, and the Kotlin one MOVED when the project went to
AGP 9 with built-in Kotlin:

```
JC=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
KC=app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes
```

> **Trap.** The pre-AGP-9 Kotlin output directory `app/build/tmp/kotlin-classes/<variant>` is
> still present on disk and still full of classes — they are just STALE, left by the previous
> toolchain. `javap` against that path succeeds and reports the OLD bytecode, so every invariant
> below would come back green while measuring nothing. Use `built_in_kotlinc`, or run
> `./gradlew clean` first. This is the same failure shape as the three grep traps recorded below:
> a check that cannot fail is worse than no check.

---

## Traps that produced FALSE facts in this migration

Recorded because each silently reported a wrong number, and a wrong number in a migration gate
is worse than no gate at all. Traps 1–3 were found while capturing the baseline; 4–6 were found
later, by which time two of them had been reading green over four real defects.

1. **`TXN_` count.** `grep -cE 'TXN_[A-Z_]+\s*='` reports **35**. The real count is **37** —
   the pattern drops names containing digits (`TXN_PHASE4_*`). Use `[A-Z0-9_]`.

2. **Lint issue count.** `grep -c '<issue'` reports **1** on a perfectly clean report: it matches
   the `<issues …>` ROOT element. Use `grep -c '<issue '` (trailing space).

3. **`static void main` presence.** `grep -c 'public static void main(java.lang.String\[\])'`
   reports **0** for `TaskRemover` — which is Kotlin and perfectly fine. `@JvmStatic` emits
   `public static **final** void main(...)`. The pattern MUST tolerate `final`, or the check
   breaks the moment `ProxyDaemonMain`/`SurfaceDaemon` are ported (batches 22/23) and reads as
   "entry point lost" when nothing was lost:

   ```
   grep -cE 'public static (final )?void main\(java\.lang\.String\[\]\)'
   ```

4. **A NUL byte in a source file silences the lint gate — via grep's binary mode.**
   `Phase4Probes.kt` carried a *literal* `0x00` byte inside a char literal (the
   `/proc/<pid>/cmdline` separator). Lint copies the offending source line into
   `errorLine1` of its XML report, so the NUL propagated into
   `lint-results-release.xml`; `file` reported the report as `data`, and grep, in
   binary mode, **suppressed its output entirely**. `grep -c '<issue '` then printed
   *nothing at all* — not `0` — which inside `echo "issues: $(…)"` renders as a blank
   and reads as zero to a human. Defence: `grep -a` on every generated report, and
   never let a check's "pass" be indistinguishable from its "no output".

5. **`<issue ` with a trailing space stopped matching under AGP 9.** Trap 2 above
   prescribed the trailing space to avoid matching the `<issues>` ROOT element. Lint
   9.4.0 pretty-prints each issue with its attributes on their own lines, so the tag
   is now `<issue\n        id="…"` — no space follows it, and the prescribed pattern
   matches **0** forever. Combined with trap 4, the lint invariant had been unable to
   fail across the whole toolchain upgrade, hiding 2 `UseKtx` warnings and 2
   `TrimLambda` hints. Use a pattern that excludes only the root element:

   ```bash
   grep -acE '<issue([^s]|$)' app/build/reports/lint-results-release.xml   # must be 0
   ```

   And prove it can still fail — append a synthetic `<issue id="FakeCheck" …/>` to a
   copy of the report and confirm the pattern returns 1.

6. **The raw-string check counts `"""` inside COMMENTS.** Invariant 5 flagged
   `ProxyShell.kt` as a new raw-string user. It is not: the match is a KDoc line that
   *documents* that raw strings are deliberately avoided there. A grep for a code
   construct that also appears in prose about that construct will keep producing this.
   Read the matching line before treating it as a violation.

---

## Invariants to re-run after EVERY batch

```bash
JC=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
KC=app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes
BL=docs/migration/kotlin-baseline

# 1. Wire constants frozen (ConstantValue attributes must not drift)
javap -p -constants -cp "$JC:$KC" \
  com.byd.dashcast.proxy.daemon.ProxyDaemonContract \
  com.byd.dashcast.proxy.daemon.SurfaceDaemon \
  com.byd.dashcast.proxy.daemon.CanWriteVerbs \
  com.byd.dashcast.system.CanBusController \
  com.byd.dashcast.proxy.daemon.ProxyDaemonMain | diff - "$BL/wire-constants.txt"

# 2. All THREE app_process entry points still have a static main
for c in com.byd.dashcast.proxy.daemon.ProxyDaemonMain \
         com.byd.dashcast.proxy.daemon.SurfaceDaemon \
         com.byd.dashcast.proxy.daemon.TaskRemover; do
  javap -p -cp "$JC:$KC" "$c" \
    | grep -cE 'public static (final )?void main\(java\.lang\.String\[\]\)'
done   # must print 1, 1, 1

# 3. Lint really is 0.  -a because a NUL in any flagged source line turns the report
#    binary and makes grep print NOTHING (trap 4); the pattern excludes only the
#    <issues> root, because AGP 9 puts a NEWLINE after <issue (trap 5).
grep -acE '<issue([^s]|$)' app/build/reports/lint-results-release.xml   # must be 0
#    Sanity-check the checker itself, or it will read green over real issues:
#    append a synthetic <issue id="FakeCheck" .../> to a COPY of the report and
#    confirm the same pattern then returns 1.

# 4. Test count never drops
grep -ho '<testcase' app/build/test-results/testDebugUnitTest/*.xml | wc -l   # >= 738

# 5. No new raw strings (a shell command must never become a """ string).
#    The leading [^*/]* skips KDoc/line-comment matches, which are prose ABOUT raw
#    strings rather than uses of them (trap 6). Mutation-tested: adding a real
#    `private val X = """…"""` to ProxyShell.kt is still caught.
grep -rlE '^[^*/]*"""' app/src/main/java | sort | diff - "$BL/rawstring-allowlist.txt"

# 6. Kotlin null-check intrinsics: INVESTIGATE each one, do not blanket-ban them.
#    THE systemic risk of this migration: a non-null Kotlin param behind a Java caller
#    passing a platform type compiles clean, lints clean, has no test — and throws on the
#    car, where the daemon swallows it into reply.writeException().
for FQN in <classes ported in this batch>; do
  javap -c -p -cp "$JC:$KC" "$FQN" | grep -c checkNotNullParameter
done
#
# A non-zero count is a PROMPT TO INVESTIGATE, not a failure. "Must be 0" was the original
# wording and it is wrong in both directions: it would reject a correct migration, and the
# obvious way to "fix" it — making every param nullable — is itself a behaviour change that
# deletes real guards. For each intrinsic, answer two questions with evidence:
#   (a) can a still-JAVA caller reach this member with a value that may be null?
#       (read the caller; a null-check before the call site settles it)
#   (b) what did the ORIGINAL Java do with null? If it dereferenced unconditionally it
#       already threw NPE, so a non-null Kotlin param preserves behaviour and only changes
#       the exception type.
# Only if (a) is yes AND (b) shows the Java tolerated null is it a real defect.
#
# Worked example, batch 1: ImeActionGate compiled to 2 intrinsics, on begin(Completion) and
# finish(Operation, Boolean). Both are called from the still-Java ClusterImeWatcherService.
# Safe, and here is why: begin() is passed a lambda built at the call site (never null), and
# ClusterImeWatcherService.java:430 does `if (operation == null) { ...; return; }` before
# reaching finish() at :458. The original Java finish() called operation.complete()
# unconditionally, so it would have NPE'd on null anyway. Behaviour preserved.

# 7. Java still links against the Kotlin ABI (proves nothing got name-mangled
#    or moved to a Companion-only member)
./gradlew :app:compileDebugJavaWithJavac

# 8. Files only ever rename in place — package/FQN is the contract for the manifest,
#    ServiceManager names, app_process invocations and reflection-by-name.
git diff --name-status -M <batch-base> HEAD \
  | awk '$1 ~ /^R/ { o=$2; n=$3; sub(/\.java$/,"",o); sub(/\.kt$/,"",n);
                     if (o != n) print "MOVED: " $2 " -> " $3 }'   # must print nothing
```

---

## Decisions locked in Batch 0

- **`-jvm-default=enable` pinned** in `kotlinOptions`.

  **CORRECTED.** The original rationale here was wrong and is kept visible on purpose. It claimed
  the flag protected `AdbLocalClient.Callback.onEvictionOutcome` and its "~20 anonymous Java
  implementers". It does not: `AdbLocalClient.Callback` is a **Java** interface
  (`AdbLocalClient.java:345`), and a Kotlin compiler flag cannot change javac output. Its
  implementers (17, not ~20) are unaffected either way.

  The site the flag actually governs is `ClusterManager.DisplayReadyCallback`
  (`ClusterManager.kt:48`) — a **Kotlin** interface whose `onDisplayLateReady` has a default body —
  implemented from Java at `FissionOrchestrator.java:823`. That implementer *already* overrides the
  method explicitly (`:844`, reason at `:840-843`), so **nothing in the tree would fail to compile
  today if the flag were dropped.** The flag is insurance against the first Kotlin interface default
  that a Java class does *not* override: without it the `DefaultImpls`-only encoding leaves that
  class abstract and fails as a runtime `AbstractMethodError` on the car, never at build time.

  Mode `enable` (not `no-compatibility`) keeps `DefaultImpls` for binary compatibility — verified in
  bytecode: both `ClusterManager$DisplayReadyCallback.class` and its `$DefaultImpls.class` exist.

  A guard asserting `AdbLocalClient.Callback#onEvictionOutcome` `isDefault()` would be a
  **tautology** — `Method.isDefault()` is `true` for a Java default method regardless of any Kotlin
  flag. A real guard must target the Kotlin interface AND assert that the `$DefaultImpls` class
  resolves, since that is what distinguishes `enable` from `no-compatibility`. Write it in Kotlin:
  `compileDebugUnitTestJavaWithJavac` is currently `NO-SOURCE` (all 738 tests are Kotlin).

- **`AdbLocalClient.Callback` nullability pinned now**, in `BugWizardActivity.kt`
  (`onSuccess`/`onError` → `String?`, 4 overrides). The interface is unannotated Java today, so
  both nullabilities compile and the edit is a semantic no-op. That nullability is otherwise
  decided ~17 times across batches 5–20 and only reconciled when `AdbLocalClient` is ported
  LAST — every non-null choice would become an illegal narrowing inside the riskiest commit of
  the migration. Invariant from now on:

  ```bash
  grep -rnE 'override fun on(Success|Error)\([A-Za-z_]+: String\)' app/src/main/java
  # must be EMPTY for AdbLocalClient.Callback implementers
  ```

  (`OtaProgressUi.kt:170` matches this shape but implements `UpdateChecker.ProgressListener`,
  a different interface — do not "fix" it.)

- **Kotlin → Java package-private access is PROVEN to work.** A throwaway Kotlin file in
  `com.byd.dashcast.proxy.daemon` calling the package-private statics
  `ProxyDaemonMain.log(String)` (`:1448`) and `ProxyDaemonMain.wrapThrowable(Throwable)`
  (`:1409`) compiles. Consequence: `Phase4ProcessVerbs` (2 call sites) and `Phase4TaskVerbs`
  (5 call sites) can be ported in batches 18/19 **without widening those methods**, so
  `ProxyDaemonMain.java` stays byte-stable from batch 16 through 21 and the
  `git diff --exit-code -- ProxyDaemonMain.java` proof keeps its meaning.

- **A Kotlin `object` runs its `<clinit>` BEFORE the `@JvmStatic main` bridge body.** Checking
  that `hiddenApiSelfTest()` is the first statement of `main()` is therefore NOT sufficient for
  batches 22/23. Add:

  ```bash
  javap -c -cp "$JC:$KC" <FQN> | sed -n '/void <clinit>/,/^$/p' \
    | grep -E 'Class.forName|getMethod|getDeclaredMethod'   # must be empty
  grep -nE 'by lazy|^\s*init \{' <the ported file>          # must be empty
  ```

  A reflective lookup resolved before `setHiddenApiExemptions` is cached per-ART-VM
  permanently — the 1.8.24 failure mode.

- **`byd/fbs/naviInfo/NaviInfo.java` is NOT migrated, ever.** It is machine-generated `flatc`
  output vendored from the OEM APK, its field order is a wire contract with OEM code, and it has
  zero Java callers to satisfy. Finishing the migration does not require porting it.
