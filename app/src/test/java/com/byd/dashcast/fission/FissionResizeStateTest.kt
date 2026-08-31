package com.byd.dashcast.fission

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FissionResizeStateTest {

    @Test
    fun `rejected resize leaves the last confirmed rectangle unchanged`() {
        val original = Rect(0, 0, 800, 480)
        val requested = Rect(20, 30, 1000, 600)
        val slot = FissionOrchestrator.SlotState("pkg", "label", 2, 2, original)

        assertFalse(FissionOrchestrator.applyAcceptedResize(slot, requested, false))
        assertEquals(original, slot.rect)
    }

    @Test
    fun `accepted resize commits a defensive rectangle copy`() {
        val requested = Rect(20, 30, 1000, 600)
        val slot = FissionOrchestrator.SlotState("pkg", "label", 2, 2, Rect())

        assertTrue(FissionOrchestrator.applyAcceptedResize(slot, requested, true))
        requested.setEmpty()
        assertEquals(Rect(20, 30, 1000, 600), slot.rect)
    }

    @Test
    fun `daemon rollback is guarded by a resize generation`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/proxy/daemon").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val source = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/proxy/daemon/SurfaceDaemon.java"
        ).readText()
        val resize = source.substringAfter("private static boolean handleResizeSlot")
            .substringBefore("private static void applySlotOverlayGeometry")

        assertTrue(resize.contains("resizeGeneration.incrementAndGet()"))
        assertTrue(resize.contains("isCurrentResize(pkg, slot, generation)"))
        assertTrue(source.contains("isCurrentResize(pkg, slot, rollbackGeneration)"))
    }
}