package com.byd.dashcast.satellite

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteRemoteVideoDiagnosticsTest {
    private fun fixtures() = JSONObject(File("../docs/satellite/diagnostics-video-fixtures.json").readText())
    private fun message() = fixtures().getJSONArray("accepted").getJSONObject(0)
    @Test fun `shared schema fixtures enforce strict scalar types fixed enums and bounds`() {
        val fixtures = fixtures()
        val accepted = fixtures.getJSONArray("accepted")
        repeat(accepted.length()) { SatelliteRemoteVideoDiagnostics.parse(accepted.getJSONObject(it)) }
        val rejected = fixtures.getJSONArray("rejected")
        repeat(rejected.length()) { index ->
            try { SatelliteRemoteVideoDiagnostics.parse(rejected.getJSONObject(index)); fail("Rejected fixture accepted: $index") }
            catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
        }
    }
    @Test fun `typed copy cannot be changed by later JSON mutation and ordinary disconnect retains evidence`() {
        var now = 100L
        val ledger = SatelliteRemoteVideoDiagnostics.Ledger { now }
        val message = message()
        val value = SatelliteRemoteVideoDiagnostics.parse(message)
        ledger.connected("connection-a", "peer-a")
        assertTrue(ledger.accept("connection-a", value))
        message.put("state", "private_free_text").getJSONObject("counts").put("offer_sent", 999)
        ledger.disconnected("connection-a"); now = 1100
        val summary = ledger.summary()
        assertTrue(summary.contains("receivedAgeMs=1000"))
        assertTrue(summary.contains("snapshotAgeAtReceiptMs=200"))
        assertTrue(summary.contains("state=waiting_viewer"))
        assertTrue(summary.contains("offer_sent=1"))
        assertTrue(summary.contains("receiptConnection=disconnected_or_replaced"))
        assertFalse(summary.contains("connection-a")); assertFalse(summary.contains("peer-a")); assertFalse(summary.contains("private_free_text"))
    }
    @Test fun `same peer reconnect preserves history but late callback replay different peer reset and TTL are fenced`() {
        var now = 100L
        val ledger = SatelliteRemoteVideoDiagnostics.Ledger { now }
        val snapshot = SatelliteRemoteVideoDiagnostics.parse(message())
        ledger.connected("old", "peer-a"); assertTrue(ledger.accept("old", snapshot))
        assertFalse(ledger.accept("old", snapshot))
        ledger.disconnected("old"); ledger.connected("new", "peer-a")
        assertTrue(ledger.summary().contains("state=waiting_viewer"))
        assertFalse(ledger.accept("old", snapshot.copy(request = 2)))
        ledger.disconnected("old") // Cannot clear a replacement owner.
        assertTrue(ledger.accept("new", snapshot))
        now += SatelliteRemoteVideoDiagnostics.MAX_AGE_MS
        assertTrue(ledger.summary().startsWith("No manually received"))
        ledger.connected("third", "peer-b")
        assertTrue(ledger.summary().startsWith("No manually received"))
        assertTrue(ledger.accept("third", snapshot))
        ledger.clear(); assertTrue(ledger.summary().startsWith("No manually received"))
        assertFalse(ledger.accept("third", snapshot.copy(request = 2)))
    }
    @Test fun `token rotation and disabling fence a cache commit already waiting for the preference monitor`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        SatellitePrefs.setEnabled(context, true)
        var token = SatellitePrefs.token(context)
        for (revoke in listOf(true, false)) {
            SatelliteRemoteVideoDiagnostics.connected("owner", "peer")
            val entered = java.util.concurrent.CountDownLatch(1)
            val accepted = java.util.concurrent.atomic.AtomicBoolean(true)
            val expected = token
            val thread: Thread
            synchronized(SatellitePrefs) {
                thread = Thread {
                    entered.countDown()
                    accepted.set(SatelliteRemoteVideoDiagnostics.receive(context, expected, "owner", message()))
                }.apply { start() }
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                if (revoke) token = SatellitePrefs.rotateToken(context) else SatellitePrefs.setEnabled(context, false)
            }
            thread.join(3000)
            assertFalse(thread.isAlive); assertFalse(accepted.get())
            assertTrue(SatelliteRemoteVideoDiagnostics.summary().startsWith("No manually received"))
        }
    }
    @Test fun `disabling clears an accepted cache instead of masking it until reenabled`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        SatellitePrefs.setEnabled(context, true)
        val token = SatellitePrefs.token(context)
        SatelliteRemoteVideoDiagnostics.connected("old-owner", "peer")
        assertTrue(SatelliteRemoteVideoDiagnostics.receive(context, token, "old-owner", message()))
        assertTrue(SatelliteRemoteVideoDiagnostics.summary().contains("state=waiting_viewer"))
        SatellitePrefs.setEnabled(context, false)
        SatellitePrefs.setEnabled(context, true)
        assertTrue(SatelliteRemoteVideoDiagnostics.summary().startsWith("No manually received"))
        assertFalse(SatelliteRemoteVideoDiagnostics.receive(context, token, "old-owner", message().put("request", 2)))
    }
    @Test fun `unknown legacy identities cannot inherit a previous senders snapshot`() {
        val ledger = SatelliteRemoteVideoDiagnostics.Ledger { 100L }
        ledger.connected("one", null)
        assertTrue(ledger.accept("one", SatelliteRemoteVideoDiagnostics.parse(message())))
        ledger.disconnected("one"); ledger.connected("two", null)
        assertTrue(ledger.summary().startsWith("No manually received"))
    }
    @Test fun `sender detail history is copied typed and retained after peer disconnect without identity`() {
        var now=0L;val ledger=SatelliteRemoteVideoDiagnostics.Ledger { now }
        val json=fixtures().getJSONArray("accepted").getJSONObject(1)
        val snapshot=SatelliteRemoteVideoDiagnostics.parse(json)
        ledger.connected("privateSession","privatePeer");assertTrue(ledger.accept("privateSession",snapshot))
        json.getJSONObject("details").getJSONArray("attempts").getJSONObject(0).put("keyFramesEncoded",999)
        ledger.disconnected("privateSession");now=1000
        val summary=ledger.summary()
        assertTrue(summary.contains("keyFramesEncoded=1"));assertTrue(summary.contains("sampleAgeAtCaptureMs=2000"))
        assertFalse(summary.contains("999"));assertFalse(summary.contains("private"))
        assertTrue(summary.contains("historical evidence"))
    }

}
