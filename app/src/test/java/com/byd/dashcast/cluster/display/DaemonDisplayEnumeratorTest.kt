package com.byd.dashcast.cluster.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for the uid-2000 daemon display enumeration.
 *
 * The DL4 sample below is copied verbatim from INC-20260727-203241 (`dumpsys display`,
 * Logical Displays section) — the exact bytes the daemon hands back on the car whose firmware
 * hides these displays from the app process.
 */
class DaemonDisplayEnumeratorTest {

    private val dl4Dump = listOf(
        "    mBaseDisplayInfo=DisplayInfo{\"Built-in Screen, displayId 0\", uniqueId " +
            "\"local:19260656133175937\", app 1920 x 1080, real 1920 x 1080, largest app 1920 x 1080, " +
            "smallest app 1920 x 1080, mode 1, defaultMode 1, rotation 0, density 240 (320.842 x 318.976) " +
            "dpi, layerStack 0, appVsyncOff 1000000, type BUILT_IN, state ON, removeMode 0}",
        "    mOverrideDisplayInfo=DisplayInfo{\"Built-in Screen, displayId 0\", uniqueId " +
            "\"local:19260656133175937\", app 1920 x 990, real 1920 x 1080, largest app 1920 x 1782, " +
            "smallest app 1080 x 942, rotation 0, layerStack 0, type BUILT_IN, state ON, removeMode 0}",
        "    mBaseDisplayInfo=DisplayInfo{\"fission_bg_xdjaVirtualSurface, displayId 1\", uniqueId " +
            "\"virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0\", app 1920 x 720, " +
            "real 1920 x 720, largest app 1920 x 720, smallest app 1920 x 720, mode 2, defaultMode 2, " +
            "rotation 0, density 320 (320.0 x 320.0) dpi, layerStack 1, appVsyncOff 0, type VIRTUAL, " +
            "state ON, owner com.xdja.containerservice (uid 1000), FLAG_PRESENTATION, removeMode 0}",
        "    mOverrideDisplayInfo=DisplayInfo{\"fission_bg_xdjaVirtualSurface, displayId 1\", uniqueId " +
            "\"virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0\", app 1920 x 720, " +
            "real 1920 x 720, largest app 1920 x 1920, smallest app 720 x 720, rotation 0, " +
            "layerStack 1, type VIRTUAL, state ON, removeMode 0}"
    ).joinToString("\n")

    /**
     * Copied verbatim from INC-20260619-222520 / INC-20260619-222619 (`dumpsys display`, Logical
     * Displays). The ONLY non-default logical display on that unit is another app's private PiP
     * surface — the topology that made the naive "first non-default display" fallback dangerous.
     */
    private val duduDump = listOf(
        "    mBaseDisplayInfo=DisplayInfo{\"Màn hình tích hợp, displayId 0\", uniqueId " +
            "\"local:19260239772068737\", app 1920 x 1080, real 1920 x 1080, largest app 1920 x 1080, " +
            "smallest app 1920 x 1080, mode 1, defaultMode 1, colorMode 0, rotation 0, " +
            "density 240 (320.842 x 318.976) dpi, layerStack 0, appVsyncOff 1000000, " +
            "presDeadline 16666666, type BUILT_IN, address {port=129, model=0x446d1605e72b}, " +
            "state ON, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, removeMode 0}",
        "    mBaseDisplayInfo=DisplayInfo{\"dudu-auto-ui-pip-1781882125841, displayId 1\", uniqueId " +
            "\"virtual:com.dudu.autoui,10071,dudu-auto-ui-pip-1781882125841,0\", app 1284 x 990, " +
            "real 1284 x 990, largest app 1284 x 990, smallest app 1284 x 990, mode 2, defaultMode 2, " +
            "colorMode 0, rotation 0, density 240 (240.0 x 240.0) dpi, layerStack 1, appVsyncOff 0, " +
            "presDeadline 16666666, type VIRTUAL, state OFF, owner com.dudu.autoui (uid 10071), " +
            "FLAG_PRIVATE, removeMode 1}",
        "    mOverrideDisplayInfo=DisplayInfo{\"dudu-auto-ui-pip-1781882125841, displayId 1\", uniqueId " +
            "\"virtual:com.dudu.autoui,10071,dudu-auto-ui-pip-1781882125841,0\", app 1284 x 990, " +
            "real 1284 x 990, largest app 1284 x 1284, smallest app 990 x 990, rotation 0, " +
            "layerStack 1, type VIRTUAL, state OFF, owner com.dudu.autoui (uid 10071), " +
            "FLAG_PRIVATE, removeMode 1}"
    ).joinToString("\n")

    @Test
    fun `parses the DL4 logical displays and keeps one entry per id`() {
        val displays = DaemonDisplayEnumerator.parseDisplayDump(dl4Dump)
        assertEquals(2, displays.size)
        assertEquals(
            ClusterDisplayInfo(0, "Built-in Screen", 1920, 1080, 0, state = "ON"),
            displays[0]
        )
        assertEquals(
            ClusterDisplayInfo(
                1, "fission_bg_xdjaVirtualSurface", 1920, 720, 1,
                isPrivate = false,
                ownerUid = 1000,
                ownerPackage = "com.xdja.containerservice",
                state = "ON"
            ),
            displays[1]
        )
    }

    @Test
    fun `parses the ownership, privacy and power-state fields the picker needs`() {
        val displays = DaemonDisplayEnumerator.parseDisplayDump(duduDump)
        assertEquals(2, displays.size)
        val pip = displays[1]
        assertEquals(1, pip.id)
        assertTrue(pip.isPrivate)
        assertEquals(10071, pip.ownerUid)
        assertEquals("com.dudu.autoui", pip.ownerPackage)
        assertEquals("OFF", pip.state)
        assertTrue(pip.isThirdPartyOwned())
        assertTrue(pip.isStateOff())
        // The BYD cluster is system-owned, presentation (not private) and ON.
        val cluster = DaemonDisplayEnumerator.parseDisplayDump(dl4Dump)[1]
        assertTrue(!cluster.isPrivate)
        assertTrue(!cluster.isThirdPartyOwned())
        assertTrue(!cluster.isStateOff())
    }

    @Test
    fun `never picks another app's private virtual display`() {
        // The whole point of deliverable 1.5: on this unit there IS no cluster display, so the
        // honest "unavailable on this firmware" verdict must be allowed to fire. Returning the
        // dudu PiP surface instead would have driven a foreign, powered-OFF, private display.
        assertNull(DaemonDisplayEnumerator.pickCluster(
            DaemonDisplayEnumerator.parseDisplayDump(duduDump)))
    }

    @Test
    fun `rejects a third-party-owned display even when it is not private`() {
        val displays = listOf(
            ClusterDisplayInfo(0, "Built-in Screen", 1920, 1080, 0, state = "ON"),
            ClusterDisplayInfo(
                1, "some-app-overlay", 1284, 990, 1,
                isPrivate = false, ownerUid = 10071, ownerPackage = "com.dudu.autoui", state = "ON")
        )
        assertNull(DaemonDisplayEnumerator.pickCluster(displays))
    }

    @Test
    fun `rejects a private display even when its name looks like the cluster`() {
        // A known name must not be able to buy its way past the privacy filter: a private VD
        // only ever accepts its owner's windows, so "found" would be a lie in every case.
        val displays = listOf(
            ClusterDisplayInfo(0, "Built-in Screen", 1920, 1080, 0, state = "ON"),
            ClusterDisplayInfo(
                1, "fission_bg_fake", 1920, 720, 1,
                isPrivate = true, ownerUid = 10071, ownerPackage = "com.dudu.autoui", state = "ON")
        )
        assertNull(DaemonDisplayEnumerator.pickCluster(displays))
    }

    @Test
    fun `does not fall back to an unnamed display that DMS reports as powered off`() {
        val displays = listOf(
            ClusterDisplayInfo(0, "Built-in Screen", 1920, 1080, 0, state = "ON"),
            ClusterDisplayInfo(5, "Unknown Screen", 1280, 480, 5, state = "OFF")
        )
        assertNull(DaemonDisplayEnumerator.pickCluster(displays))
    }

    @Test
    fun `still picks the system-owned cluster when a foreign private display is listed first`() {
        val displays = DaemonDisplayEnumerator.parseDisplayDump(
            duduDump + "\n" + dl4Dump.lineSequence().filter { it.contains("fission") }
                .joinToString("\n").replace("displayId 1", "displayId 2"))
        // id 1 = dudu (private, third-party), id 2 = fission cluster (system, ON).
        assertEquals(2, DaemonDisplayEnumerator.pickCluster(displays)?.id)
    }

    @Test
    fun `picks the fission cluster and never the default display`() {
        val cluster = DaemonDisplayEnumerator.pickCluster(
            DaemonDisplayEnumerator.parseDisplayDump(dl4Dump))
        assertEquals(1, cluster?.id)
        assertEquals(1, cluster?.layerStack)
        assertEquals(1920, cluster?.width)
        assertEquals(720, cluster?.height)
    }

    @Test
    fun `prefers a known cluster name over an earlier unnamed non-default display`() {
        val displays = listOf(
            ClusterDisplayInfo(0, "Built-in Screen", 1920, 1080, 0),
            ClusterDisplayInfo(4, "HDMI Screen", 1280, 720, 4),
            ClusterDisplayInfo(2, "fission_bg_XDJAScreenProjection", 1920, 720, 2)
        )
        assertEquals(2, DaemonDisplayEnumerator.pickCluster(displays)?.id)
    }

    @Test
    fun `prefers the production shared display even when a plain cluster name comes first`() {
        val displays = listOf(
            ClusterDisplayInfo(2, "fission_bg_XDJAScreenProjection", 1920, 720, 2),
            ClusterDisplayInfo(3, "shared_fission_bg_XDJAScreenProjection_0", 1920, 720, 3)
        )
        assertEquals(3, DaemonDisplayEnumerator.pickCluster(displays)?.id)
    }

    @Test
    fun `falls back to the first non-default display when no name matches`() {
        val displays = listOf(
            ClusterDisplayInfo(0, "Built-in Screen", 1920, 1080, 0),
            ClusterDisplayInfo(3, "Unknown Screen", 1280, 480, 3)
        )
        assertEquals(3, DaemonDisplayEnumerator.pickCluster(displays)?.id)
    }

    @Test
    fun `no cluster when only the default display exists`() {
        assertNull(DaemonDisplayEnumerator.pickCluster(
            listOf(ClusterDisplayInfo(0, "Built-in Screen", 1920, 1080, 0))))
    }

    @Test
    fun `unparseable or empty input yields no displays rather than a bogus one`() {
        assertTrue(DaemonDisplayEnumerator.parseDisplayDump(null).isEmpty())
        assertTrue(DaemonDisplayEnumerator.parseDisplayDump("").isEmpty())
        assertTrue(DaemonDisplayEnumerator.parseDisplayDump("permission denied").isEmpty())
        // DisplayDeviceInfo records describe physical devices, not logical displays: they carry
        // no ", displayId N" suffix and must not be mistaken for one.
        assertTrue(DaemonDisplayEnumerator.parseDisplayDump(
            "  DisplayDeviceInfo{\"fission_bg_xdjaVirtualSurface\": uniqueId=\"virtual:x\", " +
                "1920 x 720, layerStack 1}").isEmpty())
    }

    @Test
    fun `layerStack defaults to the display id when the dump omits it`() {
        val parsed = DaemonDisplayEnumerator.parseDisplayDump(
            "mBaseDisplayInfo=DisplayInfo{\"cluster, displayId 7\", real 800 x 480}")
        assertEquals(1, parsed.size)
        assertEquals(7, parsed[0].layerStack)
        assertEquals(800, parsed[0].width)
    }

    @Test
    fun `matches the app-side cluster naming rule`() {
        assertTrue(ClusterDisplayNames.isKnownClusterName("fission_bg_xdjaVirtualSurface"))
        assertTrue(ClusterDisplayNames.isKnownClusterName("XDJAScreenProjection_0"))
        assertTrue(!ClusterDisplayNames.isKnownClusterName("Built-in Screen"))
        assertTrue(!ClusterDisplayNames.isKnownClusterName(null))
    }
}
