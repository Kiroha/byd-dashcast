package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test

class ClusterThemeVariantTest {
    @Test fun `legacy schema is exclusively the historical marker experiment`() {
        assertEquals(ClusterThemeVariant.MARKER, ClusterThemeVariant.recover(1, null, null))
        assertThrows(IllegalArgumentException::class.java) { ClusterThemeVariant.recover(1, "full-surface-test", null) }
        assertThrows(IllegalArgumentException::class.java) { ClusterThemeVariant.recover(1, null, "0".repeat(64)) }
    }

    @Test fun `new schema requires an exact compiled profile and matching hash`() {
        for (profile in ClusterThemeVariant.values()) {
            assertEquals(profile, ClusterThemeVariant.recover(2, profile.id, profile.carrierSha256))
            assertThrows(IllegalArgumentException::class.java) { ClusterThemeVariant.recover(2, profile.id, "0".repeat(64)) }
            assertThrows(IllegalArgumentException::class.java) { ClusterThemeVariant.recover(2, profile.id, null) }
        }
        assertThrows(IllegalStateException::class.java) { ClusterThemeVariant.recover(2, null, null) }
        assertThrows(IllegalStateException::class.java) { ClusterThemeVariant.recover(2, "arbitrary", null) }
        assertThrows(IllegalStateException::class.java) { ClusterThemeVariant.recover(3, null, null) }
    }

    @Test fun `variants have distinct fixed assets hashes and protocol operations`() {
        val marker = ClusterThemeVariant.MARKER
        val full = ClusterThemeVariant.FULL_SURFACE_TEST
        assertEquals(1, marker.startOperation); assertEquals(31, marker.minimumProtocol)
        assertEquals(5, full.startOperation); assertEquals(32, full.minimumProtocol)
        assertNotEquals(marker.asset, full.asset); assertNotEquals(marker.carrierSha256, full.carrierSha256)
        assertEquals(ClusterThemePolicy.ASSET, marker.asset)
        assertEquals(15, ClusterThemeVariant.CORNER_MARKER.startOperation)
        assertEquals(38, ClusterThemeVariant.CORNER_MARKER.minimumProtocol)
        assertTrue(ClusterThemeVariant.CORNER_MARKER.isMarker); assertFalse(full.isMarker)
        assertEquals(18, ClusterThemeVariant.CORNER_MARKER_21F7735C.startOperation)
        assertEquals(39, ClusterThemeVariant.CORNER_MARKER_21F7735C.minimumProtocol)
        assertTrue(ClusterThemeVariant.CORNER_MARKER.isCorner)
        assertTrue(ClusterThemeVariant.CORNER_MARKER_21F7735C.isCorner)
        assertFalse(marker.isCorner); assertFalse(full.isCorner)
        assertFalse(ClusterThemeVariant.isCornerId("corner-marker-untrusted"))
        assertEquals(4, ClusterThemeVariant.values().size)
    }

    @Test fun `journal schema types cannot be coerced from fractional or string values`() {
        for (schema in listOf(null, "1", "2", 1.0, 1.5, 2.5, true))
            assertThrows(IllegalArgumentException::class.java) { ClusterThemeVariant.recover(schema, null, null) }
        assertEquals(ClusterThemeVariant.MARKER, ClusterThemeVariant.recover(1L, null, null))
    }
}
