package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.IOException

class FirmwareDiagnosticsTest {
    @After fun clear(){FirmwareDiagnostics.begin()}

    @Test fun `all populated metadata fields remain bounded UTF8 and do not contain replacement characters`() {
        FirmwareDiagnostics.begin()
        for(field in FirmwareDiagnostics.Field.entries)FirmwareDiagnostics.record(field,"é🚘".repeat(30000))
        val snapshot=FirmwareDiagnostics.snapshot()
        assertTrue(snapshot.toByteArray(Charsets.UTF_8).size<=FirmwareDiagnostics.MAX_BYTES)
        assertFalse(snapshot.contains('\ufffd'));assertTrue(snapshot.contains("[truncated]"))
    }
    @Test fun `first preflight cause phase canonical and device survive repeated cleanup failures`() {
        FirmwareDiagnostics.begin()
        FirmwareDiagnostics.phase("SHELL destination","/storage/emulated/0/Android/data/com.byd.dashcast/files")
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.CANONICAL,"/storage/emulated/0")
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.ACTUAL_DEVICE,"61")
        FirmwareDiagnostics.failure(IOException("first block provenance unavailable"))
        val before=FirmwareDiagnostics.snapshot()
        FirmwareDiagnostics.phase("cleanup","/data/local/tmp")
        FirmwareDiagnostics.ready();FirmwareDiagnostics.failure(IOException("second cleanup failure"))
        assertEquals(before,FirmwareDiagnostics.snapshot())
        assertTrue(FirmwareDiagnostics.failureSummary()!!.startsWith("Export non démarré"))
        assertTrue(FirmwareDiagnostics.failureSummary()!!.contains("first block provenance"))
        assertTrue(FirmwareDiagnostics.failurePath()!!.startsWith("/storage/emulated/0"))
    }
    @Test fun `initially attested destination reports subsequent failure distinctly and new session clears it`() {
        FirmwareDiagnostics.begin();FirmwareDiagnostics.ready()
        FirmwareDiagnostics.failure(IOException("changed mount tuple"))
        assertTrue(FirmwareDiagnostics.failureSummary()!!.startsWith("Export arrêté"))
        FirmwareDiagnostics.begin()
        assertNull(FirmwareDiagnostics.failureSummary());assertNull(FirmwareDiagnostics.failurePath())
        assertFalse(FirmwareDiagnostics.snapshot().contains("changed mount tuple"))
    }
    @Test fun `failure uses bounded deepest cause and omits URLs or bot token shapes`() {
        FirmwareDiagnostics.begin()
        FirmwareDiagnostics.failure(IOException("outer",IOException("failed https://example.invalid/private?token=x 123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijk")))
        val detail=FirmwareDiagnostics.failureSummary()!!
        assertTrue(detail.contains("<url>"));assertTrue(detail.contains("<token>"))
        assertFalse(detail.contains("token=x"));assertFalse(detail.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
    }
    @Test fun `concurrent report snapshots remain complete bounded UTF8 while worker updates metadata`() {
        FirmwareDiagnostics.begin()
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker=Thread {
            try {repeat(150){FirmwareDiagnostics.record(FirmwareDiagnostics.Field.BLOCKS,"é🚘".repeat(5000))}}
            catch(e:Throwable){failure.set(e)}
        }
        worker.start()
        repeat(150) {
            val snapshot=FirmwareDiagnostics.snapshot()
            assertTrue(snapshot.toByteArray(Charsets.UTF_8).size<=FirmwareDiagnostics.MAX_BYTES)
            assertFalse(snapshot.contains('\ufffd'));assertTrue(snapshot.endsWith('\n'))
        }
        worker.join(5000);assertFalse(worker.isAlive);assertNull(failure.get())
    }
}
