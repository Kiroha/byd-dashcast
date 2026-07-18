package com.byd.dashcast.report

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterDisplaySelectionTest {
    @Test
    fun `prefers D50F presentation display two`() {
        assertEquals(2, ClusterDisplaySelection.choose(intArrayOf(2), intArrayOf(0, 2)))
    }

    @Test
    fun `preserves legacy display one and non-presentation fallback`() {
        assertEquals(1, ClusterDisplaySelection.choose(intArrayOf(1), intArrayOf(0, 1)))
        assertEquals(3, ClusterDisplaySelection.choose(intArrayOf(), intArrayOf(0, 3)))
        assertEquals(-1, ClusterDisplaySelection.choose(intArrayOf(), intArrayOf(0)))
    }
}