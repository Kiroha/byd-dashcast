package com.byd.dashcast.cluster.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trinket display-selection fix: prefer the OEM's production target
 * `shared_fission_bg_XDJAScreenProjection_0` over the plain debug display, self-gated so DL3 /
 * DL5.0 / DL4 are untouched.
 */
class ClusterNamePriorityTest {

    @Test
    fun `trinket production full-screen display is preferred over the plain debug one`() {
        val shared0 = ClusterDisplayNames.clusterNamePriority("shared_fission_bg_XDJAScreenProjection_0")
        val plain = ClusterDisplayNames.clusterNamePriority("fission_bg_XDJAScreenProjection")
        assertTrue("shared_0 must rank strictly before plain", shared0 < plain)
    }

    @Test
    fun `shared_0 outranks shared_1`() {
        val s0 = ClusterDisplayNames.clusterNamePriority("shared_fission_bg_XDJAScreenProjection_0")
        val s1 = ClusterDisplayNames.clusterNamePriority("shared_fission_bg_XDJAScreenProjection_1")
        assertTrue(s0 < s1)
    }

    @Test
    fun `off trinket, no name carries shared_ so every candidate ranks equal-last`() {
        // DL3/DL4 name, DL5.0 name — none contain "shared_", so all share the same rank and the
        // previous first-match order is preserved bit-for-bit.
        val dl4 = ClusterDisplayNames.clusterNamePriority("fission_bg_xdjaVirtualSurface")
        val dl5 = ClusterDisplayNames.clusterNamePriority("fission_bg_XDJAScreenProjection")
        val plain = ClusterDisplayNames.clusterNamePriority("fission_bg_XDJAScreenProjection")
        assertEquals(2, dl4)
        assertEquals(2, dl5)
        assertEquals(dl4, plain)
        assertEquals(dl5, plain)
    }

    @Test
    fun `null and empty are safe and rank last`() {
        assertEquals(2, ClusterDisplayNames.clusterNamePriority(null))
        assertEquals(2, ClusterDisplayNames.clusterNamePriority(""))
    }

    @Test
    fun `priority only elevates genuine shared cluster displays`() {
        // Case-insensitive, and only the shared_* projection displays are elevated.
        assertEquals(0, ClusterDisplayNames.clusterNamePriority("SHARED_FISSION_BG_XDJASCREENPROJECTION_0"))
        // A shared_ name without the _0/_1 suffix is not a production target → stays last.
        assertEquals(2, ClusterDisplayNames.clusterNamePriority("shared_fission_bg_XDJAScreenProjection"))
    }

    @Test
    fun `the elevated names are still recognised as cluster displays`() {
        // The priority is only ever consulted for names that already pass isKnownClusterName.
        assertTrue(ClusterDisplayNames.isKnownClusterName("shared_fission_bg_XDJAScreenProjection_0"))
        assertTrue(ClusterDisplayNames.isKnownClusterName("fission_bg_XDJAScreenProjection"))
        assertFalse(ClusterDisplayNames.isKnownClusterName("com.android.launcher"))
    }
}
