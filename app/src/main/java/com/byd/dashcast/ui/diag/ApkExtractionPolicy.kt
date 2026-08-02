package com.byd.dashcast.ui.diag

import java.util.Locale

/**
 * Decides which packages the BYD extraction copies, and how much of them fits.
 *
 * WHY THIS EXISTS. On DiLink 5.1 ("trinket" / D50F_LC) the cluster has never displayed anything,
 * and INC-20260731-214358 showed the Android side is flawless: the app IS composited into
 * `fission_bg_XDJAScreenProjection`, a VIRTUAL display owned by `com.xdja.containerservice` with
 * `toInternalDisplay=false`, which the OEM never routes to the panel. Across the whole capture
 * corpus the activation call returns 0 on DiLink 3/4 and 1 on DiLink 5.0, but **-1 on trinket in
 * 9 captures out of 9**. The semantics of that call live in the OEM's own code; studying it is
 * interoperability analysis of software running on the tester's own vehicle.
 *
 * SELECTION.
 *  - [TIER1]: an explicit, evidence-derived list, pulled first. It exists because the primary
 *    target, `com.xdja.containerservice`, contains neither "byd" nor "cluster" — a pattern-only
 *    filter would have missed the one package that answers the question.
 *  - [TIER2_PATTERNS]: a name/path sweep — "cluster", "byd", "xdja", "fission"… — matched against
 *    BOTH the package name and its APK path, so a package whose PATH says "cluster" is caught even
 *    when its name does not, and wherever it is installed (`/system`, `/vendor`, `/data`, …).
 *
 * Partition is recorded, not used to exclude: the manifest labels each APK `[system]` or `[data]`
 * so the analyst knows the provenance, but a match under `/data` is pulled like any other.
 */
object ApkExtractionPolicy {

    /** Total bytes of APK payload. Telegram's bot API refuses documents over 50 MB. */
    const val BUDGET_TOTAL = 42L * 1024 * 1024

    /** No single APK may eat the whole budget and starve the named targets. */
    const val BUDGET_FILE = 26L * 1024 * 1024

    /** Backstop against a firmware with hundreds of matching packages. */
    const val MAX_COUNT = 14

    /** Read-only firmware partitions — used only to LABEL provenance in the manifest. */
    private val SYSTEM_PREFIXES = listOf(
        "/system/", "/system_ext/", "/vendor/", "/product/", "/odm/", "/apex/"
    )

    /**
     * Named from hard evidence in the captures, highest value first:
     *  - `com.xdja.containerservice` registers the `AutoContainer` service we call **and** owns
     *    the cluster virtual display (`owner com.xdja.containerservice (uid 1000)`). The -1 comes
     *    from here.
     *  - `com.byd.launchermap` hosts `com.byd.automap.meter.MeterActivity`, the OEM cluster map
     *    that re-takes the panel (INC-20260728-222626).
     *  - `com.example.amapservice` emits `com.byd.automap.START_MAP_VIEW` and carries
     *    `com.byd.cluster.projectionmanager`.
     */
    val TIER1 = listOf(
        "com.xdja.containerservice",
        "com.byd.launchermap",
        "com.example.amapservice"
    )

    /** Name/path sweep for OEM cluster/projection surface area. "cluster" is a first-class term. */
    private val TIER2_PATTERNS = listOf(
        "cluster", "xdja", "fission", "instrument", "meter",
        "dilink", "autocontainer", "automap", "byd"
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
     * [TIER2_PATTERNS] term — so "cluster" is found wherever it appears, whatever the partition.
     * Everything else is [EXCLUDED] (i.e. it simply doesn't match — unrelated apps are not swept).
     */
    @JvmStatic
    fun classify(pkg: String?, apkPath: String?): Tier {
        if (pkg.isNullOrBlank()) return Tier.EXCLUDED
        if (TIER1.contains(pkg)) return Tier.TIER1
        val hay = (pkg + " " + (apkPath ?: "")).lowercase(Locale.US)
        return if (TIER2_PATTERNS.any { hay.contains(it) }) Tier.TIER2 else Tier.EXCLUDED
    }

    /**
     * Budget check for one candidate, given what has already been accepted.
     *
     * @return [Skip.NONE] when it fits; otherwise the reason to record in the manifest.
     */
    @JvmStatic
    fun admit(sizeBytes: Long, acceptedBytes: Long, acceptedCount: Int): Skip = when {
        acceptedCount >= MAX_COUNT -> Skip.MAX_COUNT
        sizeBytes > BUDGET_FILE -> Skip.TOO_BIG
        acceptedBytes + sizeBytes > BUDGET_TOTAL -> Skip.OVER_BUDGET
        else -> Skip.NONE
    }

    /** Tier-1 first, then tier 2, so the named targets claim the budget before the sweep. */
    @JvmStatic
    fun order(tier: Tier): Int = when (tier) {
        Tier.TIER1 -> 0
        Tier.TIER2 -> 1
        Tier.EXCLUDED -> 2
    }

    // ── Native binaries ─────────────────────────────────────────────────────────
    //
    // The projection backend is partly NATIVE, and the -1 our activation gets from AutoContainer
    // is decided there, not in any APK: the running process `fission_service[ivi]` registers the
    // `AutoContainerNative` service that returns it. Those executables are ELF files under the
    // firmware bin dirs, not packages — the APK sweep can never reach them. They are pulled through
    // the uid-2000 daemon (the app process cannot read system_file under SELinux).

    /** Firmware bin dirs the native cluster/projection executables live in. */
    val NATIVE_BIN_DIRS = listOf("/system/bin", "/vendor/bin", "/system_ext/bin", "/odm/bin")

    /** Native executables named from the trinket capture's `03_native_backend.txt`, pulled first. */
    val NATIVE_NAMED = listOf(
        "fission_service", "fission_screennproject", "BydClusterManager",
        "fission_corebox", "fission_cbox_disp_mgr", "fissiond", "fissiontsrv"
    )

    /** Name sweep for the rest of the native cluster/projection surface. */
    private val NATIVE_PATTERNS = listOf(
        "fission", "cluster", "container", "xdja", "autocontainer", "instrument", "meter"
    )

    /** Per-file cap for native binaries — skips the multi-MB Kanzi renderers, keeps the dispatchers. */
    const val NATIVE_FILE_CAP = 4L * 1024 * 1024

    /** Native count backstop, separate from the APK one. */
    const val NATIVE_MAX_COUNT = 24

    /** Classifies a native binary by file name (there is no package/path to match on). */
    @JvmStatic
    fun classifyNative(name: String?): Tier {
        if (name.isNullOrBlank()) return Tier.EXCLUDED
        if (NATIVE_NAMED.contains(name)) return Tier.TIER1
        val low = name.lowercase(Locale.US)
        return if (NATIVE_PATTERNS.any { low.contains(it) }) Tier.TIER2 else Tier.EXCLUDED
    }

    /**
     * Budget for a native binary. [bundleBytesSoFar] is the WHOLE bundle so far (APKs + native
     * already accepted): native shares the one [BUDGET_TOTAL] ceiling with the APKs, so the zip
     * stays under Telegram's 50 MB limit whatever the mix.
     */
    @JvmStatic
    fun admitNative(sizeBytes: Long, bundleBytesSoFar: Long, nativeCount: Int): Skip = when {
        nativeCount >= NATIVE_MAX_COUNT -> Skip.MAX_COUNT
        sizeBytes > NATIVE_FILE_CAP -> Skip.TOO_BIG
        bundleBytesSoFar + sizeBytes > BUDGET_TOTAL -> Skip.OVER_BUDGET
        else -> Skip.NONE
    }
}
