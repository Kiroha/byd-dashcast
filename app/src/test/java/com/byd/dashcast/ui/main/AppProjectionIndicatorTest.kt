package com.byd.dashcast.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProjectionIndicatorTest {

    @Test
    fun layoutPackageIsActiveInFavoritesAndGrid() {
        assertTrue(AppProjectionIndicator.isActive("com.waze", null, null, setOf("com.waze")))
    }

    @Test
    fun classicClusterAndMainPackagesRemainActive() {
        assertTrue(AppProjectionIndicator.isActive("com.waze", "com.waze", null, emptySet()))
        assertTrue(AppProjectionIndicator.isActive("com.waze", null, "com.waze", emptySet()))
    }

    @Test
    fun unrelatedPackageIsInactive() {
        assertFalse(AppProjectionIndicator.isActive("com.waze", null, null, setOf("org.schabi.newpipe")))
    }
}
