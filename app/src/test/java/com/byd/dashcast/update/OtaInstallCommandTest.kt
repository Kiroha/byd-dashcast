package com.byd.dashcast.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OtaInstallCommandTest {
    @Test
    fun `command stages and validates APK before installing`() {
        val command = OtaInstallCommand.build(
            "/storage/emulated/0/Android/data/com.byd.dashcast/files/update.apk",
            21_000_000L,
            "com.byd.dashcast",
            "com.byd.dashcast/.ui.welcome.WelcomeActivity"
        )

        assertTrue(command.indexOf("cat \"\$OTA_SRC\"") < command.indexOf("pm install -r"))
        assertTrue(command.contains("run-as 'com.byd.dashcast' cat"))
        assertTrue(command.contains("OTA_STAGE_SIZE expected=21000000"))
        assertTrue(command.contains("pm install -r \"\$OTA_TMP\""))
        assertTrue(command.contains("am start -W -f 0x10200000"))
        assertTrue(command.contains("monkey -p 'com.byd.dashcast'"))
    }

    @Test
    fun `shell values are quoted`() {
        assertTrue(OtaInstallCommand.quote("a'b").contains("'\"'\"'"))
    }

    @Test
    fun `generated command has valid POSIX shell syntax`() {
        val shell = File("/bin/sh")
        assumeTrue(shell.canExecute())
        val command = OtaInstallCommand.build(
            "/storage/emulated/0/Android/data/com.byd.dashcast/files/update.apk",
            21_000_000L,
            "com.byd.dashcast",
            "com.byd.dashcast/.ui.welcome.WelcomeActivity"
        )
        val process = ProcessBuilder(shell.path, "-n", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    @Test
    fun `invalid install arguments are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OtaInstallCommand.build("/tmp/update.apk", 0L, "com.byd.dashcast", null)
        }
    }
}