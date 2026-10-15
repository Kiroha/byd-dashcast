package com.byd.dashcast.report

import com.byd.dashcast.satellite.SatellitePrefs
import com.byd.dashcast.satellite.SatelliteRemoteVideoDiagnostics
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Exercise the report emitter, not just the diagnostic formatter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SatelliteDiagnosticsReportTest {
    @Test fun `finished bug report includes retained typed sender snapshot without payload or identity`() {
        val context = RuntimeEnvironment.getApplication()
        SatellitePrefs.setEnabled(context, true)
        val token = SatellitePrefs.token(context)
        SatelliteRemoteVideoDiagnostics.connected("private-owner", "private-peer")
        val snapshot = JSONObject(File("../docs/satellite/diagnostics-video-fixtures.json").readText())
            .getJSONArray("accepted").getJSONObject(0)
        assertTrue(SatelliteRemoteVideoDiagnostics.receive(context, token, "private-owner", snapshot))
        SatelliteRemoteVideoDiagnostics.disconnected("private-owner")
        val output = File(context.cacheDir, "satellite-diagnostics-report.txt")
        var produced: File? = null
        try {
            BugReportCapture.finish(context, output, "Manual diagnostic fixture", object : BugReportCapture.Callback {
                override fun onReady(file: File) { produced = file }
                override fun onError(message: String, partial: File?) { produced = partial }
            }, null, "Synthetic shell body")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertNotNull(produced)
            val body = produced!!.readText()
            assertTrue(body.contains("DASHSAT VIDEO (manual sender snapshot; historical evidence)"))
            assertTrue(body.contains("state=waiting_viewer"))
            assertTrue(body.contains("offer_sent=1"))
            assertTrue(body.contains("receiptConnection=disconnected_or_replaced"))
            assertFalse(body.contains(token)); assertFalse(body.contains("private-owner")); assertFalse(body.contains("private-peer"))
            assertFalse(body.contains("forbidden_free_text"))
        } finally { SatellitePrefs.setEnabled(context, false) }
    }
}
