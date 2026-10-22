package com.byd.dashcast.proxy

import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeSessionTest {
    private val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
    private val versionField = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
    private val old = binderField.get(null)
    private val oldVersion = versionField.get(null)
    @After fun restore() { binderField.set(null, old); versionField.set(null, oldVersion) }
    @Test fun `missing or old existing daemon refuses without any bootstrap`() {
        binderField.set(null, null); versionField.set(null, null)
        assertThrows(IllegalStateException::class.java) { ClusterThemeSession.pin() }
        versionField.set(null, "30"); binderField.set(null, Binder())
        assertThrows(IllegalStateException::class.java) { ClusterThemeSession.pin() }
    }
    @Test fun `lost mutation reply is attempted once and never replayed`() {
        val calls = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                assertEquals(1, data.readInt()); calls.incrementAndGet(); throw DeadObjectException()
            }
        }
        binderField.set(null, binder); versionField.set(null, "31")
        val session = ClusterThemeSession.pin()
        val file = File.createTempFile("cluster-session", ".bin").apply { writeText("source") }
        var began = false
        try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            assertThrows(DeadObjectException::class.java) { session.start(fd) { began = true } }
        } } finally { file.delete() }
        assertTrue(began); assertEquals(1, calls.get()); assertSame(binder, binderField.get(null))
    }
    @Test fun `connection replacement never retargets pinned restore or observations`() {
        val calls = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { calls.incrementAndGet(); return false }
        }
        binderField.set(null, binder); versionField.set(null, "31")
        val session = ClusterThemeSession.pin(); binderField.set(null, Binder())
        assertThrows(IllegalStateException::class.java) { session.restore() }
        assertThrows(IllegalStateException::class.java) { session.observedMarker() }
        assertEquals(0, calls.get())
    }

    @Test fun `cache failure cannot interrupt restore or retry its Binder transaction`() {
        val calls = AtomicInteger()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                data.readInt(); calls.incrementAndGet()
                reply!!.writeNoException(); reply.writeString("""{"state":"CLEAN","detail":"Native query only","filename":"","raw":""}""")
                return true
            }
        }); versionField.set(null, "31")
        val brokenCache = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = error("cache unavailable")
        }
        val session = ClusterThemeSession.pin(brokenCache)
        assertTrue(session.restore().contains("CLEAN"))
        assertEquals("status then one restore, no replay", 2, calls.get())
    }

    @Test fun `status receipt is recorded without any UI and without raw content`() {
        val app = RuntimeEnvironment.getApplication()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); assertEquals(0, data.readInt())
                reply!!.writeNoException(); reply.writeString("""{"state":"REFUSED","detail":"Before native exchange","filename":"","raw":""}""")
                return true
            }
        }); versionField.set(null, "31")
        ClusterThemeSession.pin(app).status()
        val history = ClusterThemeDiagnostics.snapshot(app)
        assertTrue(history.contains("Before native exchange")); assertTrue(history.contains("REFUSED"))
    }

    @Test fun `reply followed by Binder replacement keeps receipt and subsequent unknown without accepting completion`() {
        val app = RuntimeEnvironment.getApplication()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); data.readInt()
                reply!!.writeNoException(); reply.writeString("""{"state":"REFUSED","detail":"Receipt before daemon replacement","filename":"","raw":""}""")
                binderField.set(null, Binder())
                return true
            }
        }); versionField.set(null, "31")
        val session = ClusterThemeSession.pin(app)
        assertThrows(IllegalStateException::class.java) { session.status() }
        val history = ClusterThemeDiagnostics.snapshot(app)
        assertTrue(history.contains("Receipt before daemon replacement"))
        assertTrue(history.contains("Daemon lost after call")); assertTrue(history.contains("physical completion UNKNOWN"))
        val events = org.json.JSONObject(app.getSharedPreferences(ClusterThemeDiagnostics.PREFERENCES, Context.MODE_PRIVATE)
            .getString(ClusterThemeDiagnostics.KEY, "")!!).getJSONArray("events")
        assertEquals(events.getJSONObject(events.length() - 2).getString("connectionToken"),
            events.getJSONObject(events.length() - 1).getString("connectionToken"))
    }

    @Test fun `malformed JSON reply errors never copy response payload into diagnostic history`() {
        val app = RuntimeEnvironment.getApplication()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); data.readInt()
                reply!!.writeNoException(); reply.writeString("MALFORMED_SECRET_RAW_BASE64_SENTINEL")
                return true
            }
        }); versionField.set(null, "31")
        val session = ClusterThemeSession.pin(app)
        assertThrows(org.json.JSONException::class.java) { session.status() }
        val history = ClusterThemeDiagnostics.snapshot(app)
        assertTrue(history.contains("malformed status response"))
        assertFalse(history.contains("MALFORMED_SECRET_RAW_BASE64_SENTINEL"))
    }
}
