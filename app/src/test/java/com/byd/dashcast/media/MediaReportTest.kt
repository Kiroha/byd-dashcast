package com.byd.dashcast.media

import com.byd.dashcast.report.BugReportCapture
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MediaReportTest {
    @Test fun `real report includes passive snapshot with current opt in and no media query`() {
        val app = RuntimeEnvironment.getApplication()
        MediaPrefs.setEnabled(app, true)
        val epoch = MediaDiagnostics.store.begin()
        MediaDiagnostics.store.listener(true)
        MediaDiagnostics.store.collection(epoch, 2, 1, true)
        MediaDiagnostics.store.output("TEXT", -7)
        MediaDiagnostics.store.guidance(true)
        val output = File(app.cacheDir, "media-report-test.txt")
        var produced: File? = null
        BugReportCapture.finish(app, output, null, object : BugReportCapture.Callback {
            override fun onReady(file: File) { produced = file }
            override fun onError(message: String, partial: File?) { produced = partial }
        }, "test", null)
        ShadowLooper.idleMainLooper()
        val body = checkNotNull(produced).readText()
        val section = body.substringAfter("CLUSTER MEDIA (local scalars; acceptance is not render)").substringBefore("════════ SHELL DUMP")
        assertTrue(section.contains("enabled=true listenerConnected=true observer=OBSERVING"))
        assertTrue(section.contains("sessions=2 usableSessions=1 playingSessions=0 selected=true"))
        assertTrue(section.contains("outputStage=TEXT"))
        assertTrue(section.contains("sdkReturn=-7"))
        assertTrue(section.contains("clusterGuidanceSuppressed=true"))
        MediaPrefs.setEnabled(app, false)
        MediaDiagnostics.store.stop()
        MediaDiagnostics.store.guidance(false)
        output.delete()
    }
}
