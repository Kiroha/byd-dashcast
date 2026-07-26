package com.byd.dashcast.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorProjectionTest {

    @Test
    fun letterboxesSlotAndMapsTouchBackToSlotCoordinates() {
        val projection = MirrorProjection.create(1011, 400, 1488, 647)!!

        assertEquals(1.471f, projection.scale, 0.001f)
        assertEquals(0, projection.offsetX)
        assertEquals(29, projection.offsetY)
        assertEquals(505f, projection.mapX(744f), 1f)
        assertEquals(200f, projection.mapY(323.5f), 1f)
    }

    @Test
    fun touchMappingClampsLetterboxAndEdges() {
        val projection = MirrorProjection.create(1000, 400, 1000, 600)!!

        assertEquals(0f, projection.mapY(0f), 0f)
        assertEquals(399f, projection.mapY(599f), 0f)
        assertEquals(999f, projection.mapX(1200f), 0f)
    }

    @Test
    fun invalidDimensionsHaveNoProjection() {
        assertNull(MirrorProjection.create(0, 400, 1000, 600))
        assertNull(MirrorProjection.create(1000, 400, 0, 600))
    }
}
