package com.byd.dashcast.ui.diag

import java.util.Locale

/**
 * Decides which packages and native files the BYD extraction copies, and how much of them fits.
 *
 * WHY THIS EXISTS. On DiLink 5.1 ("trinket" / D50F_LC) the cluster has never displayed anything,
 * and INC-20260731-214358 showed the Android side is flawless: the app IS composited into
 * `fission_bg_XDJAScreenProjection`, a VIRTUAL display owned by `com.xdja.containerservice` which
 * the OEM never routes to the panel. RE of the extracted OEM APKs proved the OEM nav instead
 * launches its map onto a NEW `shared_fission_bg_XDJAScreenProjection_0` display, and that the -1
 * from `AutoContainer` is decided in the NATIVE fission stack. Studying this is interoperability
 * analysis of software running on the tester's own vehicle.
 *
 * SCOPE — deliberately narrowed to the cluster/projection surface. Earlier builds swept every
 * `com.byd.*` package; RE showed the rest (acquisitioncontrol, xcall, filemanager, androidauto, …)
 * has nothing to do with projection, so it is no longer pulled. What is kept: the container service
 * (`AutoContainer`), the OEM nav (amap and its projection-manager derivatives), `clusterdebug` (the
 * reference client of the type=1000 channel) and its derivatives, and anything whose name/path
 * contains a cluster/projection term. Plus the NATIVE binaries and .so libraries where the real
 * routing lives.
 */
object ApkExtractionPolicy {

    /** Whole-bundle ceiling (APKs + native). Telegram's bot API refuses documents over 50 MB. */
    const val BUDGET_TOTAL = 42L * 1024 * 1024

    /**
     * Bytes reserved for the native binaries/.so libraries — the APK planning cannot spend into
     * this, so a fat APK set can never starve the native pull (that starvation is exactly why 1.8.9
     * came back with 40 MB of APKs and no room for the .so where the -1 actually lives).
     */
    const val NATIVE_RESERVE = 16L * 1024 * 1024

    /** APKs are budgeted below the reserve; native draws from the rest up to [BUDGET_TOTAL]. */
    const val APK_BUDGET = BUDGET_TOTAL - NATIVE_RESERVE

    /** No single APK may eat the whole APK budget and starve the named targets. */
    const val BUDGET_FILE = 22L * 1024 * 1024

    /** Backstop against a firmware with many matching packages. */
    const val MAX_COUNT = 14

    /** Read-only firmware partitions — used only to LABEL provenance in the manifest. */
    private val SYSTEM_PREFIXES = listOf(
        "/system/", "/system_ext/", "/vendor/", "/product/", "/odm/", "/apex/"
    )

    /**
     * Named from hard evidence, highest value first (a pattern-only filter would miss the
     * container services — their names have neither "byd-generic" nor "cluster"):
     *  - `com.xdja.containerservice` — the container service on DiLink 3 / trinket-DL5.1; registers
     *    `AutoContainer`, owns the cluster VD, and returns the -1. Native `libxdjacontainerservice_jni`.
     *  - `com.byd.containerservice` — the container service on **DiLink 5.0** (a DIFFERENT
     *    implementation: package `BydContainerService`, native `libcontainerservice_jni`). DL5.0 is
     *    the working DL5 platform, so its container service is a prime comparison target — and the
     *    1.8.10 narrowing (dropping "byd") accidentally stopped pulling it. Fixed here.
     *  - `com.example.amapservice` — the OEM nav; carries `com.byd.cluster.projectionmanager` and
     *    the `setLaunchDisplayId(shared_fission_bg_XDJAScreenProjection_0)` path.
     *  - `com.byd.automap` — the running OEM nav process on trinket (`ps` shows `com.byd.automap`).
     *  - `com.byd.clusterdebug` — the reference client of the type=1000 "clusterdebug" channel on
     *    DiLink 3 (the platform where it works); does ONLY `sendInfo(1000, cmd, "")`.
     *  - `com.byd.launchermap` — hosts `com.byd.automap.meter.MeterActivity`, the OEM cluster map.
     */
    val TIER1 = listOf(
        "com.xdja.containerservice",
        "com.byd.containerservice",
        "com.example.amapservice",
        "com.byd.automap",
        "com.byd.clusterdebug",
        "com.byd.launchermap"
    )

    /**
     * Name/path sweep for the OEM cluster/projection surface. Deliberately does NOT include the
     * generic "byd" / "dilink" — those matched dozens of unrelated system apps. Every term here is
     * cluster/projection-specific. "container" (subsumes the old "autocontainer") catches BOTH
     * container-service implementations wherever they are, belt-and-suspenders to the named list.
     */
    private val TIER2_PATTERNS = listOf(
        "cluster", "xdja", "fission", "container", "automap", "amap",
        "projection", "instrument", "meter"
    )

    enum class Tier { TIER1, TIER2, EXCLUDED }

    /** Why a candidate was not copied. Every skip is recorded — silent truncation reads as "absent". */
    enum class Skip { NONE, TOO_BIG, OVER_BUDGET, MAX_COUNT }

    /** Labels an APK's provenance for the manifest — informational only, never an exclusion. */
    @JvmStatic
    fun partitionLabel(apkPath: String?): String {
        if (apkPath.isNullOrBlank()) return "?"
        return if (SYSTEM_PREFIXES.any { apkPath.startsWith(it) }) "system" else "data"
    }

    /**
     * Classifies a package by name and APK path.
     *
     * [TIER1] is the named list. [TIER2] is any package whose name OR path contains a
     * [TIER2_PATTERNS] term. Everything else is [EXCLUDED] — unrelated apps are not swept.
     */
    @JvmStatic
    fun classify(pkg: String?, apkPath: String?): Tier {
        if (pkg.isNullOrBlank()) return Tier.EXCLUDED
        if (TIER1.contains(pkg)) return Tier.TIER1
        val hay = (pkg + " " + (apkPath ?: "")).lowercase(Locale.US)
        return if (TIER2_PATTERNS.any { hay.contains(it) }) Tier.TIER2 else Tier.EXCLUDED
    }

    /**
     * Budget check for one APK. APKs are capped at [APK_BUDGET] so they cannot spend into the
     * [NATIVE_RESERVE].
     */
    @JvmStatic
    fun admit(sizeBytes: Long, acceptedBytes: Long, acceptedCount: Int): Skip = when {
        acceptedCount >= MAX_COUNT -> Skip.MAX_COUNT
        sizeBytes > BUDGET_FILE -> Skip.TOO_BIG
        acceptedBytes + sizeBytes > APK_BUDGET -> Skip.OVER_BUDGET
        else -> Skip.NONE
    }

    /** Tier-1 first, then tier 2, so the named targets claim the budget before the sweep. */
    @JvmStatic
    fun order(tier: Tier): Int = when (tier) {
        Tier.TIER1 -> 0
        Tier.TIER2 -> 1
        Tier.EXCLUDED -> 2
    }

    // ── Native binaries and .so libraries ───────────────────────────────────────
    //
    // The -1 our activation gets from AutoContainer is decided in NATIVE code, not any APK: the
    // running `fission_service[ivi]` registers the `AutoContainerNative` binder service. RE of the
    // DL3 native pull showed the /system/bin `fission_*` files are only thin CLI front-ends — the
    // real logic is in the .so libraries they load (libfission_services.so, and the JNI bridge
    // libxdjacontainerservice_jni.so). Both bins and libs are pulled through the uid-2000 daemon;
    // the app process cannot read system_file under SELinux.

    /** Firmware bin dirs (native executables). */
    val NATIVE_BIN_DIRS = listOf("/system/bin", "/vendor/bin", "/system_ext/bin", "/odm/bin")

    /** Firmware lib dirs (.so). 64-bit first — the cluster stack is aarch64. */
    val NATIVE_LIB_DIRS = listOf(
        "/system/lib64", "/vendor/lib64", "/system_ext/lib64", "/odm/lib64",
        "/system/lib", "/vendor/lib"
    )

    /**
     * Native files named from evidence, pulled first. Bins from the trinket/DL3
     * `03_native_backend.txt`; libs from the DL3 pull's DT_NEEDED (the projection logic lives in
     * these, not in the CLI bins).
     */
    val NATIVE_NAMED = listOf(
        // executables
        "fission_service", "fission_screennproject", "BydClusterManager",
        "fission_corebox", "fission_cbox_disp_mgr", "fissiond", "fissiontsrv",
        // libraries — where the actual routing / AutoContainerNative logic lives
        "libxdjacontainerservice_jni.so", "libfission_services.so", "libfission_event.so"
    )

    /** Name sweep for the rest of the native cluster/projection surface (bins and .so alike). */
    private val NATIVE_PATTERNS = listOf(
        "fission", "cluster", "container", "xdja", "autocontainer",
        "instrument", "meter", "projection", "kanzi"
    )

    /**
     * Shell pre-filter pattern for enumerating the bin/lib dirs — a SUPERSET of [NATIVE_PATTERNS]
     * and every [NATIVE_NAMED] entry, so nothing [classifyNative] would accept is filtered out
     * before it is seen.
     */
    const val NATIVE_GREP = "fission|cluster|container|xdja|autocontainer|instrument|meter|projection|kanzi"

    /** Per-file cap for native files — skips the multi-MB Kanzi renderers, keeps the dispatchers/libs. */
    const val NATIVE_FILE_CAP = 4L * 1024 * 1024

    /** Native count backstop, separate from the APK one. */
    const val NATIVE_MAX_COUNT = 40

    /** Classifies a native file (bin or .so) by file name. */
    @JvmStatic
    fun classifyNative(name: String?): Tier {
        if (name.isNullOrBlank()) return Tier.EXCLUDED
        if (NATIVE_NAMED.contains(name)) return Tier.TIER1
        val low = name.lowercase(Locale.US)
        return if (NATIVE_PATTERNS.any { low.contains(it) }) Tier.TIER2 else Tier.EXCLUDED
    }

    /**
     * Budget for a native file. [bundleBytesSoFar] is the WHOLE bundle so far (APKs + native
     * already accepted): native draws from the remaining [BUDGET_TOTAL], and thanks to
     * [NATIVE_RESERVE] at least that many bytes are always left for it.
     */
    @JvmStatic
    fun admitNative(sizeBytes: Long, bundleBytesSoFar: Long, nativeCount: Int): Skip = when {
        nativeCount >= NATIVE_MAX_COUNT -> Skip.MAX_COUNT
        sizeBytes > NATIVE_FILE_CAP -> Skip.TOO_BIG
        bundleBytesSoFar + sizeBytes > BUDGET_TOTAL -> Skip.OVER_BUDGET
        else -> Skip.NONE
    }
}
