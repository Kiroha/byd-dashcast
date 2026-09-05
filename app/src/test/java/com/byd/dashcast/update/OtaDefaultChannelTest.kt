package com.byd.dashcast.update

import com.byd.dashcast.ui.settings.SettingsActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OtaDefaultChannelTest {

    @Test
    fun `fresh installs use stable while both readers share the same default`() {
        assertFalse(SettingsActivity.DEFAULT_OTA_PRERELEASE)

        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it,
                "app/src/main/java/com/byd/dashcast/update/UpdateChecker.kt").isFile }
        assertTrue("could not locate the repo root", root != null)
        val checker = File(root,
            "app/src/main/java/com/byd/dashcast/update/UpdateChecker.kt").readText()
        val settings = File(root,
            "app/src/main/java/com/byd/dashcast/ui/settings/SettingsActivity.kt").readText()

        assertTrue(checker.contains("SettingsActivity.DEFAULT_OTA_PRERELEASE"))
        assertTrue(settings.contains(
            "prefs.getBoolean(PREF_OTA_PRERELEASE, DEFAULT_OTA_PRERELEASE)"))
    }
}