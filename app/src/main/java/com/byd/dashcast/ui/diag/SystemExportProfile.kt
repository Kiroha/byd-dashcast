package com.byd.dashcast.ui.diag

/** Fixed plans only: no user-supplied paths, directory traversal or executable invocation. */
internal enum class SystemExportProfile(val id: String, val requestedPaths: List<String>) {
    FULL("system-full-v1", emptyList()),
    DILINK_P15("dilink-p15-v1", listOf(
        "/system/bin/fission_service", "/system/bin/diagmgrsrv",
        "/system/bin/fission_cbox_set_property", "/system/bin/fission_cbox_prop_event",
        "/system/bin/fission_poweron_syncprop", "/system/bin/fission_console_service",
        "/system/bin/fission_disp_mgr", "/system/bin/fission", "/system/bin/fission_ivi_reboot_timeout",
        "/system/bin/fissiontsrv", "/system/bin/licenseCheck",
        // DT_NEEDED in the collected arm64 fissiond. Both ABI paths are fixed hypotheses.
        "/system/lib/libfission_license.so", "/system/lib64/libfission_license.so",
        "/system/lib/libssl.so", "/system/lib64/libssl.so"));

    fun allowed(path: String): Boolean = if (this == FULL) SystemExportPolicy.allowed(path)
        else path in requestedPaths
    fun accepted(path: String, canonical: String): Boolean = allowed(path) && allowed(canonical)

    /** App metadata is probed independently even when the shell probe failed or succeeded.
     * Neither identity receives additional permissions. Cancellation starts no later probe. */
    fun discoverTargets(shellReady: Boolean, checkCancelled: () -> Unit,
                        shell: (String, Int) -> Unit, app: (String) -> Unit) {
        check(this != FULL)
        requestedPaths.forEachIndexed { index, path ->
            checkCancelled()
            if (shellReady) shell(path, index)
            checkCancelled()
            app(path)
        }
    }
}
