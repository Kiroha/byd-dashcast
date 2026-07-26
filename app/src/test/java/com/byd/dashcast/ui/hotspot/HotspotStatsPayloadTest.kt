package com.byd.dashcast.ui.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotStatsPayloadTest {
    @Test
    fun `parses up state and preserves client payload`() {
        val output = "${HotspotStatsPayload.STATE_UP}\n${HotspotStatsPayload.CLIENTS}\n" +
            "WifiP2pGroup\n===ARP===\n192.168.49.2"

        val parsed = HotspotStatsPayload.parse(output)!!

        assertTrue(parsed.serviceUp)
        assertEquals("WifiP2pGroup\n===ARP===\n192.168.49.2", parsed.clientsOutput)
    }

    @Test
    fun `parses down state`() {
        val parsed = HotspotStatsPayload.parse(
            "${HotspotStatsPayload.STATE_DOWN}\n${HotspotStatsPayload.CLIENTS}\n"
        )!!

        assertFalse(parsed.serviceUp)
        assertEquals("", parsed.clientsOutput)
    }

    @Test
    fun `rejects incomplete snapshots`() {
        assertNull(HotspotStatsPayload.parse(null))
        assertNull(HotspotStatsPayload.parse(HotspotStatsPayload.STATE_UP))
        assertNull(HotspotStatsPayload.parse(HotspotStatsPayload.CLIENTS))
    }
}