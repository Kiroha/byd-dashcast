package com.byd.dashcast.satellite

import org.junit.Assert.*
import org.junit.Test

class SatelliteVideoDestinationTest {
    @Test fun `cluster viewer requires live projection on its original display`() {
        assertTrue(SatelliteVideoDestination.allowed(3, 3, 3, true))
        assertFalse(SatelliteVideoDestination.allowed(3, 0, 3, true))
        assertFalse(SatelliteVideoDestination.allowed(3, 3, -1, true))
        assertFalse(SatelliteVideoDestination.allowed(3, 3, 4, true))
        assertFalse(SatelliteVideoDestination.allowed(3, 3, 3, false))
        assertFalse(SatelliteVideoDestination.allowed(0, 0, 0, true))
    }
}
