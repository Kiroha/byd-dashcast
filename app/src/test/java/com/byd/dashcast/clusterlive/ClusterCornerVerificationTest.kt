package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test

class ClusterCornerVerificationTest {
    private val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
    private val round = "b".repeat(32)
    private fun selected(source: Int = 0, path: String = ClusterThemePolicy.HOST_DIRECTORY + name) =
        ClusterThemeProtocol.Response(1001, 4, listOf(1, 0, 0, source, 0), listOf(ClusterThemeProtocol.UI_VERSION, "", path, "", "DashCast_a"))

    @Test fun `only persisted exact own selection admits a bounded original round`() {
        val receipt = ClusterCornerVerification.issue(name, 7, round, 1000, selected())
        assertTrue(receipt.matches(name, round, 7, 60999))
        for (source in listOf(1, 2)) assertThrows(IllegalArgumentException::class.java) {
            ClusterCornerVerification.issue(name, 7, round, 1000, selected(source))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClusterCornerVerification.issue(name, 7, round, 1000, selected(path = "default"))
        }
        assertFalse(receipt.matches(name, round, 7, 61000))
        assertFalse(receipt.matches(name, "c".repeat(32), 7, 1001))
        assertFalse(receipt.matches(name, round, 8, 1001))
        assertFalse(receipt.matches(name.replace("aaaa", "dddd"), round, 7, 1001))
        assertFalse(receipt.copy(observed = true).matches(name, round, 7, 1001))
    }
}
