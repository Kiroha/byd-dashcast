package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test

class ClusterThemeRecoveryPolicyTest {
    @Test fun `sources reject pipe device socket empty and oversized before staging`() {
        for (mode in listOf(0x1000, 0x2000, 0x4000, 0x6000, 0xa000, 0xc000))
            assertFalse(ClusterThemePolicy.permitsCarrierSource(mode, 100))
        assertFalse(ClusterThemePolicy.permitsCarrierSource(0x8000, 0))
        assertFalse(ClusterThemePolicy.permitsCarrierSource(0x8000, ClusterThemePolicy.MAX_CARRIER + 1))
        assertTrue(ClusterThemePolicy.permitsCarrierSource(0x81a4, ClusterThemePolicy.MAX_CARRIER))
    }
    @Test fun `cleanup requires actual exclusive creation and same dev inode owner regular type`() {
        assertTrue(ClusterThemePolicy.ownsObject(0x81a4, 2000, 4, 8, 2000, 4, 8))
        for ((dev, ino) in listOf(-1L to -1L, 4L to 9L, 5L to 8L))
            assertFalse(ClusterThemePolicy.ownsObject(0x81a4, 2000, 4, 8, 2000, dev, ino))
        assertFalse(ClusterThemePolicy.ownsObject(0xa1a4, 2000, 4, 8, 2000, 4, 8))
        assertFalse(ClusterThemePolicy.ownsObject(0x81a4, 1000, 4, 8, 2000, 4, 8))
    }
    @Test fun `unknown completion or selection alone can never clean release quarantine`() {
        for (mask in 0 until 8) {
            val default = mask and 1 != 0; val settled = mask and 2 != 0; val unknown = mask and 4 != 0
            assertEquals(mask == 3, ClusterThemePolicy.canDeleteCarrier(default, settled, unknown))
        }
    }
    @Test fun `stale unrelated failure ACKs cannot establish nonce load settlement`() {
        val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
        val ack = ClusterThemeProtocol.Response(1005, 0, listOf(2, 0, 0, 0, 0), listOf(ClusterThemePolicy.HOST_DIRECTORY + name, "", "", "", ""))
        assertTrue(ClusterThemePolicy.loadAckMatches(ack, name))
        assertFalse(ClusterThemePolicy.loadAckMatches(ack, name.replace("aaaa", "bbbb")))
        assertFalse(ClusterThemePolicy.loadAckMatches(ack.copy(sub = 4), name))
        assertFalse(ClusterThemePolicy.loadAckMatches(ack.copy(ints = listOf(3, 0, 9006, 0, 0)), name))
        assertFalse(ClusterThemePolicy.loadAckMatches(ack.copy(strings = listOf("default", "", "", "", "")), name))
    }
}
