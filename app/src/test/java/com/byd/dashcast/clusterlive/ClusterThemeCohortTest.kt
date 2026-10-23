package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test

class ClusterThemeCohortTest {
    private val live = ClusterThemeVariant.CORNER_MARKER_21F7735C
    @Test fun `complete installed cohorts are distinct and old profiles cannot load on the observed cohort`() {
        val actual = ClusterThemeCohort.expected(live)
        assertEquals(5, actual.size)
        assertEquals("21f7735cad4408f7baf7086b0e0d7b4ed3d1d2818a35bea6b8ad026102d9f4b1", actual["/system/lib64/libBydCluster.so"])
        assertEquals("1dfee5a0ad746166cb28d79bddfe9352dc510df6cbcd0001f4751af1a6dbf5e4", actual["/system/lib64/commonTranslate.rcc"])
        ClusterThemeCohort.requireMatch(live, actual)
        for (old in ClusterThemeVariant.values().filter { it != live }) {
            ClusterThemeCohort.requireMatch(old, ClusterThemeCohort.expected(old))
            assertThrows(IllegalArgumentException::class.java) { ClusterThemeCohort.requireMatch(old, actual) }
            assertThrows(IllegalArgumentException::class.java) { ClusterThemeCohort.requireMatch(live, ClusterThemeCohort.expected(old)) }
        }
    }
    @Test fun `every mixed changed incomplete or extra artifact set is refused`() {
        val actual = ClusterThemeCohort.expected(live)
        val old = ClusterThemeCohort.expected(ClusterThemeVariant.MARKER)
        for (path in actual.keys) {
            for (bad in listOf(actual - path, actual + (path to "0".repeat(64)), actual + (path to old.getValue(path))))
                assertThrows(IllegalArgumentException::class.java) { ClusterThemeCohort.requireMatch(live, bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { ClusterThemeCohort.requireMatch(live, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { ClusterThemeCohort.requireMatch(live, actual + ("/tmp/caller.so" to "0".repeat(64))) }
    }
}
