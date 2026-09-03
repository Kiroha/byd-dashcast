package com.byd.dashcast.hud

import byd.fbs.naviInfo.NaviInfo
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trips [NaviInfoPayloadBuilder.build] through [NaviInfo.getRootAsNaviInfo] to catch the one
 * bug class this code is most exposed to: a swapped/misordered `add*` call in the vendored
 * `NaviInfo.createNaviInfo` silently shifting every field after it onto the wrong FlatBuffer slot.
 */
class NaviInfoPayloadBuilderTest {

    @Test
    fun `all 18 fields round-trip through the real FlatBuffer wire format`() {
        val bytes = NaviInfoPayloadBuilder.build(
            naviState = 1,
            nextRouteName = "Main Street",
            curToSegmentDist = 120,
            forwardState = "forward",
            nextTurnIcon = 2,
            routeRemainTime = 300,
            routeRemainDist = 1200,
            stringEtaArrivalTime = "12:34",
            exitNameInfo = "Exit 5",
            exitDirectionInfo = "right",
            routrRemainDisAuto = "800",
            routrRemainTimeAuto = "180",
            segRemainDisAuto = "60",
            nextNextTurnIcon = 3,
            nextToSegmentDist = 500,
            nextNextRouteName = "Second Ave",
            roungAboutNum = 1,
            nextRoungAboutNum = 2,
        )

        val info = NaviInfo.getRootAsNaviInfo(ByteBuffer.wrap(bytes))

        assertEquals(1, info.naviState())
        assertEquals("Main Street", info.nextRouteName())
        assertEquals(120, info.curToSegmentDist())
        assertEquals("forward", info.forwardState())
        assertEquals(2, info.nextTurnIcon())
        assertEquals(300, info.routeRemainTime())
        assertEquals(1200, info.routeRemainDist())
        assertEquals("12:34", info.stringEtaArrivalTime())
        assertEquals("Exit 5", info.exitNameInfo())
        assertEquals("right", info.exitDirectionInfo())
        assertEquals("800", info.routrRemainDisAuto())
        assertEquals("180", info.routrRemainTimeAuto())
        assertEquals("60", info.SegRemainDisAuto())
        assertEquals(3, info.nextNextTurnIcon())
        assertEquals(500, info.nextToSegmentDist())
        assertEquals("Second Ave", info.nextNextRouteName())
        assertEquals(1, info.roungAboutNum())
        assertEquals(2, info.nextRoungAboutNum())
    }

    @Test
    fun `defaults produce empty strings and zero ints, not nulls or garbage`() {
        val bytes = NaviInfoPayloadBuilder.build(naviState = 0)
        val info = NaviInfo.getRootAsNaviInfo(ByteBuffer.wrap(bytes))

        assertEquals(0, info.naviState())
        assertEquals("", info.nextRouteName())
        assertEquals(0, info.curToSegmentDist())
        assertEquals(0, info.nextTurnIcon())
    }
}
