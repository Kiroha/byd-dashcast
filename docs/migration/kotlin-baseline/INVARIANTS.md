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

Compiled-class paths (AGP 8 nests javac output under the task name — the obvious path is wrong):

```
JC=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
KC=app/build/tmp/kotlin-classes/debug
```

---

## Three traps that produced FALSE facts while capturing this baseline

Recorded because each silently reported a wrong number, and a wrong number in a migration gate
is worse than no gate at all.

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

---

## Invariants to re-run after EVERY batch

```bash
JC=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
KC=app/build/tmp/kotlin-classes/debug
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

# 3. Lint really is 0
grep -c '<issue ' app/build/reports/lint-results-release.xml   # must be 0

# 4. Test count never drops
grep -ho '<testcase' app/build/test-results/testDebugUnitTest/*.xml | wc -l   # >= 738

# 5. No new raw strings (a shell command must never become a """ string)
grep -rl '"""' app/src/main/java | sort | diff - "$BL/rawstring-allowlist.txt"

# 6. NO Kotlin null-check intrinsic on any member a still-Java caller can reach.
#    THE systemic risk of this migration: a non-null Kotlin param behind a Java caller
#    passing a platform type compiles clean, lints clean, has no test — and throws on the
#    car, where the daemon swallows it into reply.writeException().
for FQN in <classes ported in this batch>; do
  javap -c -cp "$JC:$KC" "$FQN" | grep -c checkNotNullParameter   # must be 0
done

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
  `AdbLocalClient.Callback.onEvictionOutcome` is a Java `default` method
  (`AdbLocalClient.java:345`) that ~20 anonymous Java implementers omit. When that interface is
  ported (last batch) its default body must compile to a real JVM default method. Kotlin 2.x
  happens to default to `enable`, but nothing pinned it. Mode `enable` (not `no-compatibility`)
  keeps `DefaultImpls` for binary compatibility.

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
