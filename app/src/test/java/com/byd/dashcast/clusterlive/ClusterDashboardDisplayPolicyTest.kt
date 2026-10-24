package com.byd.dashcast.clusterlive

import com.byd.dashcast.cluster.display.ClusterDisplayInfo
import org.junit.Assert.*
import org.junit.Test

class ClusterDashboardDisplayPolicyTest {
    private val central = ClusterDisplayInfo(0, "Écran intégré", 1920, 1080, 0, state = "ON")
    private val cluster = ClusterDisplayInfo(7, "fission_bg_xdjaVirtualSurface", 1920, 720, 7,
        ownerUid = 1000, ownerPackage = "com.xdja.containerservice", state = "ON")

    @Test fun `central display only is absent projection rather than incompatible physical cluster`() {
        assertEquals("ABSENT", ClusterDashboardDisplayPolicy.inspect(listOf(central)).verdict)
        assertEquals("UNAVAILABLE", ClusterDashboardDisplayPolicy.inspect(emptyList()).verdict)
        assertEquals("UNAVAILABLE", ClusterDashboardDisplayPolicy.inspect(listOf(central), false).verdict)
        assertEquals("ABSENT", ClusterDashboardDisplayPolicy.inspect(listOf(cluster.copy(id = 0))).verdict)
    }

    @Test fun `only current positive OEM ownership and exact geometry can be ready`() {
        assertTrue(ClusterDashboardDisplayPolicy.inspect(listOf(central, cluster)).ready)
        for (bad in listOf(cluster.copy(isPrivate = true), cluster.copy(ownerUid = -1),
            cluster.copy(ownerPackage = null), cluster.copy(ownerUid = 10099, ownerPackage = "other.app")))
            assertEquals("OWNER_REJECTED", ClusterDashboardDisplayPolicy.inspect(listOf(central, bad)).verdict)
        assertEquals("STATE_REJECTED", ClusterDashboardDisplayPolicy.inspect(listOf(cluster.copy(state = "OFF"))).verdict)
        assertEquals("STATE_REJECTED", ClusterDashboardDisplayPolicy.inspect(listOf(cluster.copy(state = ""))).verdict)
        assertEquals("SMALL", ClusterDashboardDisplayPolicy.inspect(listOf(cluster.copy(width = 1280, height = 480))).verdict)
        assertEquals("SIZE_REJECTED", ClusterDashboardDisplayPolicy.inspect(listOf(cluster.copy(width = 0))).verdict)
        assertEquals("SIZE_REJECTED", ClusterDashboardDisplayPolicy.inspect(listOf(cluster.copy(height = 1080))).verdict)
    }

    @Test fun `real application slots coexist while any extra named projection target remains ambiguous`() {
        val slot = cluster.copy(id = 8, name = "dashcast_slot_org.example.maps", width = 752, height = 320,
            ownerUid = 2000, ownerPackage = "com.android.shell")
        assertTrue(ClusterDashboardDisplayPolicy.inspect(listOf(central, cluster, slot)).ready)
        assertEquals("AMBIGUOUS", ClusterDashboardDisplayPolicy.inspect(listOf(cluster, cluster.copy(id = 9))).verdict)
        assertEquals("AMBIGUOUS", ClusterDashboardDisplayPolicy.inspect(listOf(cluster,
            slot.copy(name = "shared_fission_other", ownerUid = 1000, ownerPackage = "other.oem"))).verdict)
        assertEquals("OWNER_REJECTED", ClusterDashboardDisplayPolicy.inspect(listOf(cluster,
            slot.copy(name = "shared_fission_other", ownerUid = -1))).verdict)
    }
}
