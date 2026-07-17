package com.byd.dashcast.fission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutSlotSelectionTest {

    @Test
    fun firstSlotIsSelectedByDefault() {
        assertEquals("com.waze", LayoutSlotSelection.resolve(null, packages()))
    }

    @Test
    fun explicitActiveSlotIsPreserved() {
        assertEquals("org.schabi.newpipe", LayoutSlotSelection.resolve("org.schabi.newpipe", packages()))
    }

    @Test
    fun nextAndPreviousWrapInLayoutOrder() {
        assertEquals("org.schabi.newpipe", LayoutSlotSelection.step("com.waze", packages(), 1))
        assertEquals("com.waze", LayoutSlotSelection.step("org.schabi.newpipe", packages(), 1))
        assertEquals("org.schabi.newpipe", LayoutSlotSelection.step("com.waze", packages(), -1))
    }

    @Test
    fun emptyLayoutHasNoSelection() {
        assertNull(LayoutSlotSelection.resolve("com.waze", emptyList()))
        assertNull(LayoutSlotSelection.step("com.waze", emptyList(), 1))
    }

    private fun packages() = listOf("com.waze", "org.schabi.newpipe")
}
