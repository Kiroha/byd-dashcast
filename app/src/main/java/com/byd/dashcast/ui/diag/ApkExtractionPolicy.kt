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

    /**
     * Set once, before planning, when a large-capacity sink is available (an Azure Blob container
     * is configured) instead of the 50 MB messaging channel. Every ceiling below then opens up.
     *
     * <p>It is deliberately a flag rather than a parameter: the budget is consulted from several
     * decision helpers that the unit tests call directly, and threading a budget through all of
     * them would change every test signature for no behavioural gain. Default `false` keeps the
     * historical Telegram-sized behaviour exactly as it was.
     */
    @JvmStatic
    var largeSink: Boolean = false

    /** Whole-bundle ceiling (APKs + native). Telegram's bot API refuses documents over 50 MB. */
    @JvmStatic
    val BUDGET_TOTAL: Long get() = if (largeSink) 2L * 1024 * 1024 * 1024 else 42L * 1024 * 1024

    /**
     * Bytes reserved for the native binaries/.so libraries — the APK planning cannot spend into
     * this, so a fat APK set can never starve the native pull (that starvation is exactly why 1.8.9
     * came back with 40 MB of APKs and no room for the .so where the -1 actually lives).
     */
    @JvmStatic
    val NATIVE_RESERVE: Long get() = if (largeSink) 512L * 1024 * 1024 else 16L * 1024 * 1024

    /** APKs are budgeted below the reserve; native draws from the rest up to [BUDGET_TOTAL]. */
    @JvmStatic
    val APK_BUDGET: Long get() = BUDGET_TOTAL - NATIVE_RESERVE

    /** No single APK may eat the whole APK budget and starve the named targets. */
    @JvmStatic
    val BUDGET_FILE: Long get() = if (largeSink) 512L * 1024 * 1024 else 22L * 1024 * 1024

    /** Backstop against a firmware with many matching packages. */
    @JvmStatic
    val MAX_COUNT: Int get() = if (largeSink) 60 else 14

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
        "com.byd.launchermap",
        // Defines the BYDAUTO_* permission namespace (/system/framework/AutoPermission,
        // ~164 KB). Its manifest lists every BYDAUTO_* permission and its protection level —
        // the cheapest way to learn which one gates BYDAutoInstrumentDevice writes.
        "com.byd.auto.permission"
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

    /**
     * True on the platforms whose OEM firmware has already been fully extracted and reverse-
     * engineered, so a further extraction would only re-send what we already have. The diagnostic
     * gates on this to say "reverse engineering complete — nothing sent" instead of re-uploading.
     *
     * DONE: **DiLink 3** and **DiLink 5.0** (container service, amapservice, clusterdebug, the
     * native fission/.so stack + libProjectionMsgSdk — see doc_api/).
     * STILL NEEDED (extraction stays live): **DiLink 5.1 / trinket** — we still need its native
     * stack to confirm libProjectionMsgSdk is absent — and **DiLink 4**.
     *
     * The DiLink generation tracks the Android version, so the API level cleanly separates the two
     * DL5 variants: DL5.0 = Android 12 (API ≤ 32), DL5.1 = Android 13 (API 33). Gating on
     * `apiLevel < 33` therefore captures DL5.0 while ALWAYS leaving trinket (API 33) live — the
     * critical property, since a wrongly-gated trinket would block the extraction we are waiting on.
     */
    @JvmStatic
    fun isPlatformFullyMined(isDiLink3: Boolean, isDiLink5: Boolean, apiLevel: Int): Boolean =
        // DiLink 3 was un-blocked 2026-08-09: the windshield-HUD question is still OPEN on DL3
        // (a tester's SX326 refuses featureID 0x43f01030, and we need his system APKs to look for
        // HUD-specific services), so the platform is NOT fully mined and extraction must stay
        // available. Only DiLink 5.0 (API < 33) remains blocked.
        isDiLink5 && apiLevel < 33

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

    /**
     * Framework dir — added for the bydauto SDK. The class that refuses instrument registers
     * (`AbsBYDAutoDevice.checkDeviceFeatures`) is on the boot classpath, not in any APK or .so, so
     * it is invisible to the bin/lib sweep. Its feature↔device table is what decides which
     * `INSTRUMENT_*` registers a given car accepts.
     */
    val NATIVE_FRAMEWORK_DIRS = listOf("/system/framework")

    // ── bydauto framework SDK + permission model (added after the DL3 SX326 RE) ──
    //
    // The DL3 extraction (05_hud_probes) proved the framework-jar sweep pulled NOTHING useful for
    // the instrument gate: there is no discrete `bydauto.jar`, and the "byd" name filter skips the
    // boot-classpath jars that actually hold `android.hardware.bydauto.*`. Three gaps were left, each
    // fixed below and by [BydApkExtractionBundle.planFramework] / the 06 probe:
    //   1. AbsBYDAutoDevice / checkDeviceFeatures / BYDAutoFeatureIds — baked into a boot-classpath
    //      jar (its dex lives in the sibling .vdex on API 29); named + pulled here.
    //   2. the BYDAUTO_*_COMMON tier is NOT declared in com.byd.auto.permission — some other package
    //      declares it, at an unknown protectionLevel; the 06 probe resolves sourcePackage + level.
    //   3. the feature↔device table is runtime state (libbydautoservice.so loads per-device enable
    //      bitmaps from the MCU into mDeviceFlags; the gate is isEnable(deviceType, featureId)). It is
    //      in NO file — only a live `dumpsys`/service probe (06) can show which featureIds the
    //      instrument device (deviceType 1007 = BYDAUTO_DEVICE_INSTRUMENT) accepts on a given car.

    /**
     * OAT dirs where a boot-classpath jar's compiled bytecode lives on API 29 (the .jar itself is
     * often resource-only; the dex is in the sibling .vdex/.odex). Enumerated only by
     * [BydApkExtractionBundle.planFramework], never by the bin/lib native sweep.
     */
    val FRAMEWORK_OAT_DIRS = listOf("/system/framework/oat/arm64", "/system/framework/oat/arm")

    /**
     * Permission / sysconfig dirs. Their XMLs declare (a) the `android.hardware.bydauto` shared-
     * library → jar mapping (so the exact jar holding AbsBYDAutoDevice can be named), and (b) the
     * privapp-permissions allow-list — which package is *allowed* to hold each signature BYDAUTO_*
     * permission. Read by the 06 text probe, not pulled file-by-file (they are numerous and tiny).
     */
    val PERMISSION_DIRS = listOf(
        "/system/etc/permissions", "/system/etc/sysconfig",
        "/vendor/etc/permissions", "/product/etc/permissions", "/system_ext/etc/permissions"
    )

    /**
     * Boot-classpath jars pulled for the bydauto SDK. `android.hardware.bydauto.*` (AbsBYDAutoDevice /
     * checkDeviceFeatures / BYDAutoFeatureIds — the Java gate that emits "no permission to use the
     * feature 0x… with this device: N") is baked into one of these; name them so they are pulled
     * regardless of any name filter, together with their .vdex/.odex siblings that carry the dex.
     * Large (tens of MB each), so in practice only fit once a large sink is configured.
     */
    val FRAMEWORK_SDK_JARS = listOf("framework.jar", "ext.jar", "services.jar")

    /** Any framework jar whose lowercase name matches is BYD-specific and pulled unconditionally. */
    const val FRAMEWORK_JAR_GREP = "byd|bydauto|instrument"

    /**
     * True for a framework artefact worth pulling: a named boot-classpath SDK jar, its .vdex/.odex
     * bytecode sibling, or any BYD-specific framework jar. The numerous permission XMLs are handled
     * by the 06 text probe instead, so they never count against the native-file backstop.
     */
    @JvmStatic
    fun isFrameworkArtifact(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val low = name.lowercase(Locale.US)
        if (!(low.endsWith(".jar") || low.endsWith(".vdex") || low.endsWith(".odex"))) return false
        val base = low.substringBeforeLast('.')
        if (FRAMEWORK_SDK_JARS.any { it.substringBeforeLast('.') == base }) return true
        return Regex(FRAMEWORK_JAR_GREP).containsMatchIn(low)
    }

    /** Name sweep for the rest of the native cluster/projection surface (bins and .so alike). */
    private val NATIVE_PATTERNS = listOf(
        "fission", "cluster", "container", "xdja", "autocontainer",
        "instrument", "meter", "projection", "kanzi",
        // "byd" catches the OEM cluster renderer (libBydCluster.so) and the bydauto framework jars.
        // Deliberately not "auto" on its own — that would sweep in autofill and friends.
        "byd"
    )

    /**
     * Shell pre-filter pattern for enumerating the bin/lib dirs — a SUPERSET of [NATIVE_PATTERNS]
     * and every [NATIVE_NAMED] entry, so nothing [classifyNative] would accept is filtered out
     * before it is seen.
     */
    const val NATIVE_GREP =
        "fission|cluster|container|xdja|autocontainer|instrument|meter|projection|kanzi|byd"

    /**
     * Per-file cap for native files. The 4 MB default skips the multi-MB Kanzi renderers and keeps
     * the dispatchers/libs — it exists because the whole bundle had to fit under a 50 MB messaging
     * ceiling. With a large sink configured that reason disappears, and the cap is what has been
     * excluding the artefacts we most want: the cluster Qt theme bundles (~126 MB and ~118 MB, which
     * hold the instrument-cluster glyph library) and the renderer libBydCluster.so (~29 MB).
     */
    @JvmStatic
    val NATIVE_FILE_CAP: Long get() = if (largeSink) 256L * 1024 * 1024 else 4L * 1024 * 1024

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
