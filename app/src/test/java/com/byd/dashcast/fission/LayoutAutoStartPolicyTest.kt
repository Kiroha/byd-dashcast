package com.byd.dashcast.fission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutAutoStartPolicyTest {

    @Test
    fun requestedLayoutOwnsStartupEvenBeforeFavoriteIsReady() {
        assertTrue(LayoutAutoStartPolicy.isRequested(true, true))
        assertFalse(LayoutAutoStartPolicy.isRequested(false, true))
        assertFalse(LayoutAutoStartPolicy.isRequested(true, false))
    }

    @Test
    fun validFavoriteWithBoundAppsWins() {
        val first = preset("first", "com.waze")
        val favorite = preset("favorite", "org.schabi.newpipe")

        assertEquals(
            favorite,
            LayoutAutoStartPolicy.chooseLayout("favorite", listOf(first, favorite))
        )
    }

    @Test
    fun soleUsableSavedLayoutRepairsMissingFavorite() {
        val empty = preset("empty", null)
        val usable = preset("usable", "com.waze")

        assertEquals(
            usable,
            LayoutAutoStartPolicy.chooseLayout(null, listOf(empty, usable))
        )
    }

    @Test
    fun ambiguousSavedLayoutsRequireExplicitFavorite() {
        assertNull(
            LayoutAutoStartPolicy.chooseLayout(
                null,
                listOf(preset("one", "com.waze"), preset("two", "org.schabi.newpipe"))
            )
        )
    }

    @Test
    fun unsavedOrUnboundLayoutCannotAutoLaunch() {
        assertNull(LayoutAutoStartPolicy.chooseLayout(null, emptyList()))
        assertNull(LayoutAutoStartPolicy.chooseLayout(null, listOf(preset("empty", null))))
    }

    private fun preset(id: String, packageName: String?): LayoutPreset {
        return LayoutPreset(id).apply {
            this.id = id
            slots += LayoutPreset.SlotDef("slot", 0, 0, 960, 720).apply {
                this.packageName = packageName
            }
        }
    }
}
