package com.byd.dashcast.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagePseudonymizerTest {

    private val installKeyA = ByteArray(32) { it.toByte() }
    private val installKeyB = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun markerIsStableWithinOneInstallation() {
        val first = PackagePseudonymizer.marker(installKeyA, "com.example.navigation")
        val second = PackagePseudonymizer.marker(installKeyA, "com.example.navigation")

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun markerChangesAcrossInstallations() {
        val first = PackagePseudonymizer.marker(installKeyA, "com.example.navigation")
        val second = PackagePseudonymizer.marker(installKeyB, "com.example.navigation")

        assertNotEquals(first, second)
    }

    @Test
    fun markerChangesAcrossPackages() {
        val first = PackagePseudonymizer.marker(installKeyA, "com.example.navigation")
        val second = PackagePseudonymizer.marker(installKeyA, "com.example.other")

        assertNotEquals(first, second)
    }
}
