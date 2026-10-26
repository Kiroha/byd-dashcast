package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test

class ClusterRestartCohortTest {
    private val hashes = mapOf(
        "/system/lib64/libBydCluster.so" to "21f7735cad4408f7baf7086b0e0d7b4ed3d1d2818a35bea6b8ad026102d9f4b1",
        "/system/bin/fissiond" to "31d39dfc63ffdae993ae5003fc6d392409a1a4c482609cb36f2fcbd8343fd93e")
    @Test fun `positive current identity requires both exact runtime files`() {
        val paths = mutableListOf<String>()
        ClusterRestartCohort.verify(29, ClusterThemeVariant.FULL_APP_21F7735C) { paths += it; hashes.getValue(it) }
        assertEquals(hashes.keys, paths.toSet())
    }
    @Test fun `unknown API or old variant never reads artifacts or falls back`() {
        for ((api, variant) in listOf(33 to ClusterThemeVariant.FULL_APP_21F7735C, 29 to ClusterThemeVariant.MARKER))
            assertThrows(IllegalStateException::class.java) { ClusterRestartCohort.verify(api, variant) { fail("No fallback"); "" } }
    }
    @Test fun `mismatch or unreadable runtime file refuses restart`() {
        for (failed in hashes.keys) {
            assertThrows(IllegalStateException::class.java) { ClusterRestartCohort.verify(29, ClusterThemeVariant.FULL_APP_21F7735C) { if (it == failed) "changed" else hashes.getValue(it) } }
            assertThrows(java.io.IOException::class.java) { ClusterRestartCohort.verify(29, ClusterThemeVariant.FULL_APP_21F7735C) { if (it == failed) throw java.io.IOException("denied") else hashes.getValue(it) } }
        }
    }
}
