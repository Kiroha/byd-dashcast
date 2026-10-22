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
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeReportTest {
    @Test fun `normal and offline body fallback embed cached early refusals without querying live-test API`() {
        val app = RuntimeEnvironment.getApplication()
        ClusterThemeDiagnostics.local(app, "start-failed", "LOCAL", "REFUSED before start transmission: own asset unavailable")
        ClusterThemeDiagnostics.received(app, "oem-selection-query", "query-connection", JSONObject()
            .put("state", "REFUSED").put("detail", "Previous renderer discovery refusal")
            .put("oemQuery", JSONObject().put("state", "RECEIVED").put("phase", "complete")
                .put("registration", 0).put("native", 0).put("selection", "OEM selected default")
                .put("nativeIdentity", "UNKNOWN").put("receivedEpochMs", 123456789L)).toString())
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
                assertTrue(body.contains("oemQueryRegistration\":0")); assertTrue(body.contains("oemQueryNative\":0"))
                assertTrue(body.contains("OEM selected default")); assertTrue(body.contains("oemQueryNativeIdentity\":\"UNKNOWN"))
                assertTrue(body.contains("oemQueryReceivedEpochMs\":123456789"))
                assertTrue(body.contains("No cluster IPC/OEM reads by this section"))
                assertEquals(fallback, body.contains("report body build failed"))
            }
            assertEquals("Report embedding must not query live theme status", 0, clusterCalls)
        } finally { binderField.set(null, previous) }
    }
}
