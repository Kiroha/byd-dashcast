package com.byd.dashcast.hud

import com.byd.dashcast.data.prefs.RoundaboutTrafficSide
import org.junit.Assert.*
import org.junit.Test

class RoundaboutDirectionPolicyTest {
    private fun frame(icon: Int) = HudNavigationData(icon, 123, "Road", 456, 789, 12, 34)

    @Test fun `source mode preserves every raw snapshot by identity`() {
        for (icon in -1..50) {
            val data = frame(icon)
            assertSame(data, RoundaboutDirectionPolicy.apply(data, RoundaboutTrafficSide.SOURCE))
        }
    }

    @Test fun `numbered exits preserve ordinal and every non-icon value in both directions`() {
        for (side in listOf(RoundaboutTrafficSide.RIGHT, RoundaboutTrafficSide.LEFT)) {
            for (input in 25..44) {
                val data = frame(input)
                val rendered = RoundaboutDirectionPolicy.apply(data, side)
                val exit = (input - 25) % 10 + 1
                assertEquals((if (side == RoundaboutTrafficSide.LEFT) 34 else 24) + exit, rendered.iconId)
                assertEquals(exit, ClusterNavPusher.roundaboutExitNum(rendered.iconId))
                assertEquals(if (side == RoundaboutTrafficSide.LEFT) 11 else 17,
                    ClusterNavPusher.toAmapIcon(rendered.iconId, rendered.roundaboutClockwiseOverride))
                assertEquals(input, data.iconId)
                assertValues(data, rendered)
            }
        }
    }

    @Test fun `generic pairs retain geometry with no invented exit and explicit output direction`() {
        val pairs = listOf(15 to 16, 18 to 17, 20 to 19, 22 to 21)
        for ((ccw, cw) in pairs) for (input in listOf(ccw, cw)) {
            val data = frame(input)
            val right = RoundaboutDirectionPolicy.apply(data, RoundaboutTrafficSide.RIGHT)
            val left = RoundaboutDirectionPolicy.apply(data, RoundaboutTrafficSide.LEFT)
            assertEquals(ccw, right.iconId)
            assertEquals(cw, left.iconId)
            assertEquals(0, ClusterNavPusher.roundaboutExitNum(right.iconId))
            assertEquals(0, ClusterNavPusher.roundaboutExitNum(left.iconId))
            assertEquals(17, ClusterNavPusher.toAmapIcon(right.iconId, right.roundaboutClockwiseOverride))
            assertEquals(11, ClusterNavPusher.toAmapIcon(left.iconId, left.roundaboutClockwiseOverride))
            assertValues(data, right)
            assertValues(data, left)
        }
        // The old automatic table is unchanged even where its legacy suffixes disagree.
        assertEquals(17, ClusterNavPusher.toAmapIcon(16))
        assertEquals(17, ClusterNavPusher.toAmapIcon(19))
    }

    @Test fun `unconfirmed variants and non-roundabouts reject even an inappropriate render override`() {
        for (id in (-1..50).filter { it !in 15..22 && it !in 25..44 }) {
            val data = frame(id)
            for (side in RoundaboutTrafficSide.entries) assertSame(data, RoundaboutDirectionPolicy.apply(data, side))
            assertEquals(ClusterNavPusher.toAmapIcon(id), ClusterNavPusher.toAmapIcon(id, true))
            assertEquals(ClusterNavPusher.toAmapIcon(id), ClusterNavPusher.toAmapIcon(id, false))
        }
    }

    private fun assertValues(raw: HudNavigationData, rendered: HudNavigationData) {
        assertEquals(raw.distanceMeters, rendered.distanceMeters)
        assertEquals(raw.roadName, rendered.roadName)
        assertEquals(raw.remainingDistanceMeters, rendered.remainingDistanceMeters)
        assertEquals(raw.remainingTimeSeconds, rendered.remainingTimeSeconds)
        assertEquals(raw.etaHour, rendered.etaHour)
        assertEquals(raw.etaMinute, rendered.etaMinute)
        assertNull(raw.roundaboutClockwiseOverride)
    }
}
