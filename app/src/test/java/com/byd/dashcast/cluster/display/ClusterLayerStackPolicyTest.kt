package com.byd.dashcast.cluster.display

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole point of this policy is that it must be a no-op everywhere except the DiLink 5.0
 * shadow-display case, so the tests are written as regression guards for the OTHER platforms:
 * a change that starts rewriting DiLink 3 / DiLink 4 / trinket values must fail here.
 */
class ClusterLayerStackPolicyTest {

    @Test
    fun `DiLink 5 shadow render displays are rewritten to the composed cluster face`() {
        assertEquals(2, ClusterLayerStackPolicy.composedOrSelf(true, 3))
        assertEquals(2, ClusterLayerStackPolicy.composedOrSelf(true, 4))
    }

    @Test
    fun `DiLink 5 leaves every other value alone`() {
        // trinket / DiLink 5.1 projects onto the plain fission_bg display: layerStack 2 already.
        assertEquals(2, ClusterLayerStackPolicy.composedOrSelf(true, 2))
        // main display, and the DiLink-3-style cluster layerStack.
        assertEquals(0, ClusterLayerStackPolicy.composedOrSelf(true, 0))
        assertEquals(1, ClusterLayerStackPolicy.composedOrSelf(true, 1))
        assertEquals(5, ClusterLayerStackPolicy.composedOrSelf(true, 5))
        assertEquals(-1, ClusterLayerStackPolicy.composedOrSelf(true, -1))
    }

    @Test
    fun `non-DiLink5 platforms are never rewritten, including the 3 and 4 values`() {
        // DiLink 3 / DiLink 4: even a layerStack that happens to be 3 or 4 must pass through,
        // otherwise the shared policy would silently retarget their capture and mirror.
        // NOTE: DX_BYD_AUTO / AAOS is NOT in this group — Platform classifies it as DiLink 5, so it
        // takes the isDiLink5=true branch below. Harmless on its known topology (cluster display 1),
        // but it is the reason the rule stays narrowly scoped to the 3/4 values.
        for (value in -1..6) {
            assertEquals(value, ClusterLayerStackPolicy.composedOrSelf(false, value))
        }
    }

    @Test
    fun `AAOS-style cluster display 1 is untouched even though AAOS reports as DiLink 5`() {
        assertEquals(1, ClusterLayerStackPolicy.composedOrSelf(true, 1))
    }
}
