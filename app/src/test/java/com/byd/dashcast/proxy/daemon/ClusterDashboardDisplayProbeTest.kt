package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.cluster.display.ClusterDisplayInfo
import com.byd.dashcast.clusterlive.ClusterDashboardDisplayPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterDashboardDisplayProbeTest {
    private fun base(id: Int, name: String, width: Int, height: Int, owner: String = "") =
        "  Display $id:\n    mBaseDisplayInfo=DisplayInfo{\"$name, displayId $id\", real $width x $height, layerStack $id, state ON, $owner}\n"
    private val central = base(0, "Écran intégré", 1920, 1080)
    private val cluster = base(7, "fission_bg_xdjaVirtualSurface", 1920, 720, "owner com.xdja.containerservice (uid 1000)")
    private fun dump(count: Int, body: String) = "DISPLAY MANAGER (dumpsys display)\nLogical Displays: size=$count\n$body\nDisplayModeDirector\n"

    @Test fun `complete central-only field report shape permits bootstrap but never passes size admission`() {
        assertEquals("ABSENT", ClusterDashboardDisplayProbe.parse(dump(1, central)).verdict)
        assertTrue(ClusterDashboardDisplayProbe.parse(dump(2, central + cluster)).ready)
        val evidence = ClusterDashboardDisplayProbe.parse(dump(2, central + cluster))
        assertTrue(ClusterDashboardDisplayPolicy.fromJson(evidence.json()).ready)
    }

    @Test fun `partial malformed duplicate or mismatched display inventories are unavailable`() {
        for (bad in listOf(dump(2, central), dump(3, central + cluster), dump(2, central + central),
            dump(2, central + "  Display 7:\n    mBaseDisplayInfo=malformed\n"),
            dump(2, central + cluster.replace("displayId 7", "displayId 8")),
            dump(2, central + cluster.replace("(uid 1000)}", "(uid 1000)"))))
            assertEquals("UNAVAILABLE", ClusterDashboardDisplayProbe.parse(bad).verdict)
    }

    @Test fun `exit zero timeout and dump errors cannot turn a valid-looking prefix into geometry proof`() {
        val valid = dump(2, central + cluster)
        for (marker in listOf("DUMP TIMEOUT", "EXPIRED", "Error in poll while dumping service", "Failed to read while dumping service",
            "Failed to write while dumping service", "[output truncated]", "Permission Denial"))
            assertEquals(marker, "UNAVAILABLE", ClusterDashboardDisplayProbe.parse(valid + marker).verdict)
        assertEquals("UNAVAILABLE", ClusterDashboardDisplayProbe.parse(valid, false).verdict)
    }

    @Test fun `late disappearance small replacement and binder death refuse the actual helper guard before size forcing`() {
        val good = ClusterDashboardDisplayProbe.parse(dump(2, central + cluster))
        val absent = ClusterDashboardDisplayProbe.parse(dump(1, central))
        val small = ClusterDashboardDisplayPolicy.inspect(listOf(ClusterDisplayInfo(7, "fission_bg_xdjaVirtualSurface", 1280, 480, 7,
            ownerUid = 1000, ownerPackage = "com.xdja.containerservice", state = "ON")))
        var sent = 0
        for (current in listOf(absent, small)) {
            assertThrows(IllegalStateException::class.java) {
                ClusterNativeModeHelper.requireDashboardGeometry({ current }, { true }); sent++
            }
        }
        var alive = true
        assertThrows(IllegalStateException::class.java) {
            ClusterNativeModeHelper.requireDashboardGeometry({ alive = false; good }, { alive }); sent++
        }
        assertEquals(0, sent)
        ClusterNativeModeHelper.requireDashboardGeometry({ good }, { true }); sent++
        assertEquals(1, sent)
    }
}
