package com.byd.dashcast.clusterlive

/** Positive identity for the single researched external maintenance reason, independent of DL3 fallback. */
internal object ClusterRestartCohort {
    fun verify(api: Int, variant: ClusterThemeVariant, hash: (String) -> String) {
        check(api == 29 && variant in setOf(ClusterThemeVariant.CORNER_MARKER_21F7735C,
            ClusterThemeVariant.FULL_DASHBOARD_21F7735C, ClusterThemeVariant.FULL_APP_21F7735C)) {
            "Maintenance restart is restricted to the researched Android 10 cohort"
        }
        for ((path, expected) in mapOf(
            "/system/lib64/libBydCluster.so" to "21f7735cad4408f7baf7086b0e0d7b4ed3d1d2818a35bea6b8ad026102d9f4b1",
            "/system/bin/fissiond" to "31d39dfc63ffdae993ae5003fc6d392409a1a4c482609cb36f2fcbd8343fd93e"))
            check(hash(path) == expected) { "Maintenance restart runtime identity mismatch: ${path.substringAfterLast('/')}" }
    }
}
