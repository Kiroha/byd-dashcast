package com.byd.dashcast.ui.diag

/**
 * Bounded, read-only evidence for choosing a HUD receiver profile. No bind, service start, CAN
 * write or synthetic guidance is performed. The extraction zipper applies the existing redaction.
 */
internal object HudOemEvidenceCapture {
    private const val PACKAGE_LIMIT = 96 * 1024
    private const val CONTEXT_LIMIT = 32 * 1024

    fun collect(shell: (String) -> String): String {
        val report = StringBuilder("HUD OEM receiver evidence — read-only collection\n")
        fun section(label: String, command: String, limit: Int) {
            report.append("\n=== ").append(label).append(" ===\n")
            try {
                // Keep errors: an unreadable dump must never be presented as an absent service.
                val output = shell("$command 2>&1 | head -c ${limit + 1}")
                if (output.isBlank()) report.append("[EMPTY_OUTPUT]\n")
                else {
                    val bytes = output.toByteArray(Charsets.UTF_8)
                    report.append(String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8)).append('\n')
                    if (bytes.size > limit) {
                        report.append("[TRUNCATED: output exceeded ").append(limit).append(" bytes]\n")
                    }
                }
            } catch (e: Exception) {
                report.append("[FAILED: ").append(e.javaClass.simpleName).append("]\n")
            }
        }

        for (property in listOf(
            "ro.build.version.release", "ro.build.version.sdk", "ro.build.display.id",
            "ro.build.fingerprint", "ro.vehicle.type", "ro.build.car.platform"
        )) {
            section(property, "getprop $property", 1024)
        }
        for (pkg in ApkExtractionPolicy.HUD_OEM_PACKAGES) {
            section("APK paths: $pkg", "pm path $pkg", CONTEXT_LIMIT)
            section("Package dump: $pkg", "dumpsys package $pkg", PACKAGE_LIMIT)
        }
        // Capture all permissions of the real app and shell identities, not only BYDAUTO names.
        for (pkg in listOf("com.byd.dashcast", "com.android.shell")) {
            section("Caller package dump: $pkg", "dumpsys package $pkg", PACKAGE_LIMIT)
        }
        section(
            "Running SOME/IP services (an empty runtime list does not prove package absence)",
            "dumpsys activity services com.ts.car.someip.service", CONTEXT_LIMIT
        )
        val dirs = ApkExtractionPolicy.PERMISSION_DIRS.joinToString(" ")
        section(
            "SOME/IP and naviauto permission/sysconfig XML matches (paths and context)",
            "grep -rniE -C 3 'someip|com\\.ts\\.car|com\\.byd\\.naviauto' $dirs",
            CONTEXT_LIMIT
        )
        return report.toString()
    }
}
