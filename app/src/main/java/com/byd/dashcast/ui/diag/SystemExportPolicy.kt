package com.byd.dashcast.ui.diag

/** Fixed firmware-only scope. This policy is deliberately independent from APK discovery. */
internal object SystemExportPolicy {
    private val partitions = listOf("/system", "/system_ext", "/product", "/vendor")
    val frameworkRoots = partitions.map { "$it/framework" }
    val xmlRoots = partitions.flatMap { listOf("$it/etc/permissions", "$it/etc/sysconfig") }
    val initRoots = partitions.map { "$it/etc/init" }
    val nativeRoots = partitions.flatMap { listOf("$it/lib", "$it/lib64") }
    val nativeNames = setOf("libxdjacontainerservice_jni.so", "libfission_services.so", "libfission_event.so",
        "libbydauto.so", "libbydautoservice.so",
        // P4 DT_NEEDED names: candidate probes, not assertions about linker resolution.
        "libEGL.so", "libGLESv2.so", "libandroid_runtime.so", "libbinder.so", "libc++.so", "libc.so",
        "libcrypto.so", "libcutils.so", "libdl.so", "libfission_cutils.so", "libgui.so", "libhardware.so",
        "libhardware_legacy.so", "liblog.so", "libm.so", "libnativeloader.so", "libnetutils.so",
        "libselinux.so", "libsync.so", "libui.so", "libutils.so", "libzmq.so")
    val halRoots = nativeRoots.map { "$it/hw" }
    val bionicRoots = listOf("/apex/com.android.runtime/lib/bionic", "/apex/com.android.runtime/lib64/bionic")
    val bionicNames = setOf("libc.so", "libm.so", "libdl.so")
    val exactPaths = listOf(CONTAINER_CONFIG, "/system/bin/fission_service",
        "/system/bin/fissiond", "/system/bin/qtandroidnative", "/system/lib64/libBydCluster.so", "/system/lib64/libAdas.so")
    val apexModules = setOf("com.android.runtime", "com.android.art", "com.android.conscrypt", "com.android.i18n", "com.android.media")
    const val CONTAINER_CONFIG = "/system/etc/container_comm_cfg.json"
    const val INIT_PATTERN = "AutoContainerNative|container|fission|autoservice|diagnosticsrv|diagmgr|strategyservice"
    const val MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_INVENTORY_BYTES = 32L * 1024 * 1024
    const val MAX_SOURCES = 20000
    const val MAX_ISSUES = 512
    val propertyKeys = listOf("ro.build.fingerprint", "ro.build.version.release", "ro.build.version.sdk",
        "ro.product.cpu.abi", "ro.build.display.id", "ro.build.system.fission_single_os", "sys.car.protocol",
        "persist.sys.protocol.record", "sys.byd.countrycode", "ro.hardware.auto", "ro.hardware",
        "ro.product.board", "ro.board.platform", "ro.arch")
    private val artifacts = setOf("jar", "art", "oat", "odex", "vdex")
    private val safePath = Regex("/[A-Za-z0-9_./+@=-]+")
    private fun clean(path: String): Boolean = path.matches(safePath) &&
        path.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." }

    /** Android may expose partition aliases below /system; accept only those fixed aliases. */
    private fun partitionAlias(path: String): String = when {
        path.startsWith("/system/product/") -> path.removePrefix("/system")
        path.startsWith("/system/system_ext/") -> path.removePrefix("/system")
        path.startsWith("/system/vendor/") -> path.removePrefix("/system")
        else -> path
    }
    fun isFramework(path: String): Boolean {
        if (!clean(path) || path.substringAfterLast('.', "") !in artifacts) return false
        val normalized = partitionAlias(path)
        return frameworkRoots.any { normalized.startsWith("$it/") }
    }
    fun isApexClasspath(path: String): Boolean {
        if (!clean(path) || path.substringAfterLast('.', "") !in artifacts) return false
        val fields = path.split('/')
        return fields.size >= 5 && fields[1] == "apex" && validApexModule(fields[2]) && fields[3] == "javalib"
    }
    private fun validApexModule(module: String): Boolean = module in apexModules ||
        (module.substringBefore('@') in apexModules && module.substringAfter('@', "").matches(Regex("[0-9]+")))
    fun isAutoHalName(name: String): Boolean = name == "auto.so" || name.matches(Regex("auto\\.[A-Za-z0-9_+@=-][A-Za-z0-9_.+@=-]*\\.so"))
    fun isBionic(path: String): Boolean {
        if (!clean(path)) return false
        val fields = path.split('/')
        return fields.size == 6 && fields[1] == "apex" &&
            (fields[2] == "com.android.runtime" ||
                (fields[2].substringBefore('@') == "com.android.runtime" && validApexModule(fields[2]))) &&
            fields[3] in setOf("lib", "lib64") && fields[4] == "bionic" && fields[5] in bionicNames
    }
    fun allowed(path: String): Boolean {
        if (!clean(path)) return false
        val normalized = partitionAlias(path)
        return isFramework(path) || isApexClasspath(path) || isBionic(path) || normalized in exactPaths ||
            (xmlRoots.any { normalized.startsWith("$it/") } && path.endsWith(".xml")) ||
            (initRoots.any { normalized.startsWith("$it/") } && path.endsWith(".rc")) ||
            (nativeRoots.any { normalized.substringBeforeLast('/') == it } && path.substringAfterLast('/') in nativeNames) ||
            (halRoots.any { normalized.substringBeforeLast('/') == it } && isAutoHalName(path.substringAfterLast('/')))
    }
    fun accepted(path: String, canonical: String): Boolean = allowed(path) && allowed(canonical)
    fun classpath(value: String): Pair<List<String>, List<String>> {
        val paths = value.split(':').filter { it.isNotEmpty() }.distinct()
        return paths.filter { isFramework(it) || isApexClasspath(it) } to
            paths.filterNot { isFramework(it) || isApexClasspath(it) }
    }
    fun contextIssues(before: Map<String, String>, after: Map<String, String>): List<String> {
        val first = before["ro.build.fingerprint"].orEmpty()
        val last = after["ro.build.fingerprint"].orEmpty()
        return when {
            first.isBlank() || last.isBlank() -> listOf("Before/after build fingerprint unavailable; same-ROM provenance is unknown.")
            first != last -> listOf("Build fingerprint changed during collection; corpus may mix firmware versions.")
            else -> emptyList()
        }
    }
    fun finalContext(before: Map<String, String>, cancelled: Boolean,
                     capture: () -> Map<String, String>): FullApkExport.ContextCheck {
        if (cancelled) return FullApkExport.ContextCheck(issues = listOf(
            "After-copy build context unavailable: collection was cancelled; no new context command was started."))
        val after = capture()
        return FullApkExport.ContextCheck(after, contextIssues(before, after) + listOfNotNull(after["contextCaptureIssue"]))
    }
    fun completionIssue(reply: String, marker: String): String? = when {
        reply.contains("[output truncated]") -> "Truncated command reply"
        reply.trim() != "$marker:0" -> "Missing/failed completion marker (timeout, denied access or unsupported tool): ${reply.take(300)}"
        else -> null
    }
}

/** A rejected/unknown generated size stops later snapshots instead of evading aggregate limits. */
internal class SystemInventoryBudget(
    private val perRoot: Long = SystemExportPolicy.MAX_SNAPSHOT_BYTES,
    private val total: Long = SystemExportPolicy.MAX_TOTAL_INVENTORY_BYTES,
) {
    var bytes: Long = 0; private set
    var stopped: Boolean = false; private set
    fun accept(size: Long?): Boolean {
        if (stopped) return false
        if (size == null || size < 0 || size > perRoot || size > total - bytes) {
            stopped = true
            return false
        }
        bytes += size
        return true
    }
}
