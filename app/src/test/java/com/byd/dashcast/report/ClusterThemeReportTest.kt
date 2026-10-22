package com.byd.dashcast.report

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeReportTest {
    @Test fun `normal and offline body fallback embed cached early refusals without querying live-test API`() {
        val app = RuntimeEnvironment.getApplication()
        ClusterThemeDiagnostics.local(app, "start-failed", "LOCAL", "REFUSED before start transmission: own asset unavailable")
        val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val previous = binderField.get(null)
        var clusterCalls = 0
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST) clusterCalls++
                return false
            }
        })
        try {
            for (fallback in listOf(false, true)) {
                val target = File(app.cacheDir, "cluster-theme-report-$fallback")
                if (fallback) target.mkdirs()
                var output: File? = null
                BugReportCapture.finish(app, target, "Cached-only cluster test", object : BugReportCapture.Callback {
                    override fun onReady(file: File) { output = file }
                    override fun onError(message: String, partial: File?) { fail(message) }
                }, "Offline transport fixture", if (fallback) null else "existing shell body")
                ShadowLooper.idleMainLooper()
                val body = output!!.readText()
                assertTrue(body.contains("CLUSTER THEME TEST (historical app cache; current state UNKNOWN)"))
                assertTrue(body.contains("REFUSED before start transmission"))
                assertTrue(body.contains("No cluster IPC/OEM reads by this section"))
                assertEquals(fallback, body.contains("report body build failed"))
            }
            assertEquals("Report embedding must not query live theme status", 0, clusterCalls)
        } finally { binderField.set(null, previous) }
    }
}
