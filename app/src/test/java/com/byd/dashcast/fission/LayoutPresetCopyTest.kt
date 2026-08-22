package com.byd.dashcast.fission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * [LayoutPreset.copy] exists for one reason: the layout editor drags the LIVE preset object, and
 * anything that keeps the same reference sees every unsaved move.
 *
 * saveLayout used to put `mEditing` itself into `mPresets`, which meant a saved layout was not a
 * snapshot but a window onto the canvas — the next drag was already "saved", the next
 * LayoutPrefs.save wrote it to disk, and Cancel cancelled nothing. A shallow copy would keep
 * exactly that defect one level down, in the slots, which is where the drags actually land, so
 * that is what this pins.
 */
class LayoutPresetCopyTest {

    private fun editing(): LayoutPreset {
        val p = LayoutPreset("Split")
        p.slots.add(LayoutPreset.SlotDef("Zone 1", 0, 0, 640, 480).apply {
            displayId = 3
            packageName = "com.example.maps"
        })
        p.slots.add(LayoutPreset.SlotDef("Zone 2", 640, 0, 640, 480))
        return p
    }

    @Test
    fun `a copy carries the same values`() {
        val original = editing()
        val c = original.copy()
        assertEquals(original.name, c.name)
        // The id must be preserved: it is what saveLayout matches on to replace in place, and a
        // fresh id would silently turn every save into a new layout.
        assertEquals(original.id, c.id)
        assertEquals(2, c.slots.size)
        assertEquals("Zone 1", c.slots[0].label)
        assertEquals(3, c.slots[0].displayId)
        assertEquals("com.example.maps", c.slots[0].packageName)
        assertEquals(640, c.slots[1].x)
    }

    @Test
    fun `dragging a zone after the save does not reach the saved copy`() {
        val original = editing()
        val saved = original.copy()

        // What the canvas does on a drag, and on binding an app to a zone.
        original.slots[0].x = 999
        original.slots[0].w = 111
        original.slots[0].packageName = "com.example.other"
        original.name = "renamed after saving"

        assertEquals("the saved slot must not move", 0, saved.slots[0].x)
        assertEquals(640, saved.slots[0].w)
        assertEquals("com.example.maps", saved.slots[0].packageName)
        assertEquals("Split", saved.name)
    }

    @Test
    fun `adding or removing a zone after the save does not reach the saved copy`() {
        val original = editing()
        val saved = original.copy()

        original.slots.add(LayoutPreset.SlotDef("Zone 3", 0, 480, 1280, 240))
        original.slots.removeAt(0)

        assertEquals("the saved layout keeps the zones it was saved with", 2, saved.slots.size)
        assertEquals("Zone 1", saved.slots[0].label)
    }

    @Test
    fun `the slot list itself is a different object`() {
        val original = editing()
        val c = original.copy()
        assertNotSame(original.slots, c.slots)
        assertNotSame(original.slots[0], c.slots[0])
    }
}
