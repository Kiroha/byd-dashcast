package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test

class ClusterCornerVerificationTest {
    private val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
    private val round = "b".repeat(32)
    private fun selected(source: Int = 0, path: String = ClusterThemePolicy.HOST_DIRECTORY + name) =
        ClusterThemeProtocol.Response(1001, 4, listOf(1, 0, 0, source, 0), listOf(ClusterThemeProtocol.UI_VERSION, "", path, "", "DashCast_a"))

    @Test fun `both native sources admit only exact own selection for a bounded visual round`() {
        for (source in listOf(0, 1)) {
            val receipt = ClusterCornerVerification.issue(name, 7, round, 1000, selected(source))
            assertTrue(receipt.matches(name, round, 7, 60999))
        }
        val receipt = ClusterCornerVerification.issue(name, 7, round, 1000, selected())
        assertTrue(receipt.matches(name, round, 7, 60999))
        for (source in listOf(-1, 2, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
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
    @Test fun `invalid source path and round explain refusal instead of generic requirement`() {
        val invalidSelection = assertThrows(IllegalArgumentException::class.java) {
            ClusterCornerVerification.issue(name, 7, round, 1000, selected(source = 2))
        }
        assertTrue(invalidSelection.message!!.contains("Fresh exact owned IVI selection required; source=2"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            ClusterCornerVerification.issue(name, 7, "bad", 1000, selected())
        }.message!!.contains("Invalid corner observation round"))
    }
}
