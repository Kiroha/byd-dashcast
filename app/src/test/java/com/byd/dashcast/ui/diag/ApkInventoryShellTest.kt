package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ApkInventoryShellTest {
    @Test fun `real shell pages many inventory lines without omissions`() {
        var calls = 0
        val shell = ApkInventoryShell { script ->
            calls++
            val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            output
        }
        val result = shell.lines("i=1; while [ \"\$i\" -le 151 ]; do echo APK:/path/\$i.apk; i=\$((i+1)); done", {}, {})
        assertEquals(3, calls)
        assertEquals(151, result.lines.size)
        assertTrue(result.issues.isEmpty())
        assertEquals("APK:/path/151.apk", result.lines.last())
    }

    @Test fun `missing sentinel timeout and capped replies remain incomplete`() {
        for (output in listOf("APK|42|/system/test.apk", "ERR timeout 30s", "APK|42|/system/test.apk\n[output truncated]")) {
            val result = ApkInventoryShell { output }.lines("ignored", {}, {})
            assertTrue(result.issues.any { it.contains("Missing completion marker") })
        }
    }

    @Test fun `permission error exit is recorded even when some files were found`() {
        val result = ApkInventoryShell { "APK|1|/system/good.apk\nfind: /private: Permission denied\n__DC_EXIT__:1\n__DC_LINES__:2" }
            .lines("ignored", {}, {})
        assertEquals(2, result.lines.size)
        assertTrue(result.issues.any { it.contains("exited 1") })
    }

    @Test fun `changing inventory count across pages is explicit`() {
        var page = 0
        val result = ApkInventoryShell { page++; "APK|1|/system/$page.apk\n__DC_EXIT__:0\n__DC_LINES__:${if (page == 1) 65 else 66}" }
            .lines("ignored", {}, {})
        assertEquals(2, page)
        assertTrue(result.issues.any { it.contains("changed between pages") })
    }

    @Test(expected = CancellationException::class)
    fun `cancel is checked before each command`() {
        ApkInventoryShell { error("must not execute") }.lines("ignored", { throw CancellationException() }, {})
    }

    @Test fun `shell quote keeps literal punctuation`() {
        assertEquals("'a'\\''b; c'", ApkInventoryShell.quote("a'b; c"))
    }
}
