package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProxyInstanceMarkerTest {
    @Test
    fun `missing marker is recreated but a foreign generation is never overwritten`() {
        val directory = Files.createTempDirectory("proxy-instance-marker").toFile()
        try {
            val marker = File(directory, "instance")
            val owned = "0123456789abcdef0123456789abcdef"
            val foreign = "fedcba9876543210fedcba9876543210"

            assertTrue(ProxyInstanceMarker.ensureOwned(marker, owned))
            assertTrue(ProxyInstanceMarker.ensureOwned(marker, owned))
            marker.writeText(foreign)

            assertFalse(ProxyInstanceMarker.ensureOwned(marker, owned))
            assertTrue(marker.readText() == foreign)
        } finally {
            directory.deleteRecursively()
        }
    }
}