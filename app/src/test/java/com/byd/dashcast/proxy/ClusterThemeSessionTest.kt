package com.byd.dashcast.proxy

import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
import com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt
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
    @After fun restore() {
        binderField.set(null, old); versionField.set(null, oldVersion)
        RuntimeEnvironment.getApplication().getSharedPreferences("native_cluster_operational_debt", Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("rcc_cluster_operational_debt", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun `Android RCC operation35 commits exact debt before fixed marker request`() {
        val app = RuntimeEnvironment.getApplication(); var calls = 0
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); assertEquals(11, data.readInt())
                ParcelFileDescriptor.CREATOR.createFromParcel(data).close()
                assertEquals(1, data.readInt()); assertEquals(1, data.readInt())
                val nonce = data.readString()!!; assertEquals(nonce, ClusterThemeProjectionDebt.nonce(app))
                assertNotNull(data.readStrongBinder()); assertEquals(0, data.dataAvail()); calls++
                reply!!.writeNoException(); reply.writeString("""{"state":"PREFLIGHT","filename":"${com.byd.dashcast.clusterlive.ClusterThemePolicy.PREFIX}$nonce.rcc","transport":"android-ivi","rccDebt":true}""")
                return true
            }
        }); versionField.set(null, "34")
        val session = ClusterThemeSession.pin(app); val file = File.createTempFile("rcc-ivi", ".rcc").apply { writeText("fixture") }
        try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            assertThrows(IllegalStateException::class.java) { session.startIvi(it, ClusterThemeVariant.MARKER, Binder()) {} }
            assertEquals(0, calls); assertFalse(ClusterThemeProjectionDebt.blocked(app))
            versionField.set(null, "35"); session.startIvi(it, ClusterThemeVariant.MARKER, Binder()) {}
            assertEquals(1, calls); assertTrue(ClusterThemeProjectionDebt.blocked(app))
        } } finally { file.delete() }
    }
    @Test fun `RCC lost reply retains debt and one request without retry`() {
        val app = RuntimeEnvironment.getApplication(); var calls = 0
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { calls++; throw DeadObjectException() }
        }); versionField.set(null, "35")
        val file = File.createTempFile("rcc-ivi", ".rcc").apply { writeText("fixture") }
        try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            assertThrows(DeadObjectException::class.java) { ClusterThemeSession.pin(app).startIvi(it, ClusterThemeVariant.FULL_SURFACE_TEST, Binder()) {} }
            assertEquals(1, calls); assertTrue(ClusterThemeProjectionDebt.blocked(app))
        } } finally { file.delete() }
    }
    @Test fun `explicit orphan debt reconciliation is operation13 and only current exact safe nonce clears it`() {
        val app = RuntimeEnvironment.getApplication(); val nonce = "e".repeat(32)
        ClusterThemeProjectionDebt.begin(app, nonce)
        var calls = 0; var safe = "a".repeat(32)
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); assertEquals(13, data.readInt())
                assertEquals(nonce, data.readString()); assertEquals(1, data.readInt()); assertEquals(1, data.readInt())
                assertNotNull(data.readStrongBinder()); assertEquals(0, data.dataAvail()); calls++
                reply!!.writeNoException(); reply.writeString("""{"state":"CLEAN","filename":"${com.byd.dashcast.clusterlive.ClusterThemePolicy.PREFIX}$nonce.rcc","transport":"android-ivi","rccDebt":false,"rccSafeNonce":"$safe"}""")
                return true
            }
        }); versionField.set(null, "35")
        val session = ClusterThemeSession.pin(app); session.restoreIvi(Binder())
        assertTrue(ClusterThemeProjectionDebt.blocked(app)); safe = nonce; session.restoreIvi(Binder())
        assertFalse(ClusterThemeProjectionDebt.blocked(app)); assertEquals(2, calls)
    }
    @Test fun `native full requires34 and commits negative debt before one fixed16 transaction`() {
        val app = RuntimeEnvironment.getApplication(); var calls = 0
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                assertEquals(8, data.readInt()); assertEquals(16, data.readInt())
                val nonce = data.readString()!!; assertTrue(nonce.matches(Regex("[0-9a-f]{32}")))
                assertEquals(nonce, ClusterNativeProjectionDebt.nonce(app))
                assertEquals(1, data.readInt()); assertEquals(1, data.readInt()); assertNotNull(data.readStrongBinder()); assertEquals(0, data.dataAvail())
                calls++; reply!!.writeNoException()
                reply.writeString("""{"state":"IDLE","filename":"","nativeMode":{"state":"FULL_QUEUED","nonce":"$nonce","debt":true,"pending":true}}""")
                return true
            }
        }); versionField.set(null, "33")
        val session = ClusterThemeSession.pin(app)
        assertThrows(IllegalStateException::class.java) { session.nativeFull(Binder()) }; assertEquals(0, calls)
        versionField.set(null, "34"); session.nativeFull(Binder()); assertEquals(1, calls)
    }
    @Test fun `native lost reply retains app debt and never retries16`() {
        val app = RuntimeEnvironment.getApplication(); var calls = 0
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls++; throw DeadObjectException()
            }
        }); versionField.set(null, "34")
        assertThrows(DeadObjectException::class.java) { ClusterThemeSession.pin(app).nativeFull(Binder()) }
        assertEquals(1, calls); assertTrue(ClusterNativeProjectionDebt.blocked(app))
    }
    @Test fun `durable debt failure refuses before native transaction`() {
        val base = RuntimeEnvironment.getApplication(); var calls = 0
        val app = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val original = base.getSharedPreferences(name, mode)
                if (name != "native_cluster_operational_debt") return original
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                            override fun commit(): Boolean { editor.commit(); return false }
                        }
                    }
                }
            }
        }
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { calls++; return false }
        }); versionField.set(null, "34")
        assertThrows(IllegalStateException::class.java) { ClusterThemeSession.pin(app).nativeFull(Binder()) }
        assertEquals(0, calls)
    }
    @Test fun `native normal uses18 only and closed nonce observation clears app debt`() {
        val app = RuntimeEnvironment.getApplication(); val nonce = "d".repeat(32); val operations = ArrayList<Int>()
        ClusterNativeProjectionDebt.begin(app, nonce)
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); val op = data.readInt(); operations.add(op)
                if (op == 9) {
                    assertEquals(18, data.readInt()); assertEquals(nonce, data.readString())
                    assertEquals(1, data.readInt()); assertEquals(1, data.readInt()); assertNotNull(data.readStrongBinder())
                } else { assertEquals(10, op); assertEquals(nonce, data.readString()) }
                assertEquals(0, data.dataAvail())
                reply!!.writeNoException(); reply.writeString("""{"state":"IDLE","filename":"","nativeMode":{"state":"${if (op == 10) "CLOSED" else "RETURNED"}","nonce":"$nonce","debt":${op != 10},"pending":false}}""")
                return true
            }
        }); versionField.set(null, "34")
        val session = ClusterThemeSession.pin(app); session.nativeNormal(nonce, Binder())
        assertTrue(ClusterNativeProjectionDebt.blocked(app)); session.nativeNormalObserved(nonce)
        assertFalse(ClusterNativeProjectionDebt.blocked(app)); assertEquals(listOf(9, 10), operations)
    }
    @Test fun `missing or old existing daemon refuses without any bootstrap`() {
        binderField.set(null, null); versionField.set(null, null)
        assertThrows(IllegalStateException::class.java) { ClusterThemeSession.pin() }
        versionField.set(null, "30"); binderField.set(null, Binder())
        assertThrows(IllegalStateException::class.java) { ClusterThemeSession.pin() }
    }
    @Test fun `read only OEM query requires protocol 33 and sends only closed operation 7`() {
        val calls = AtomicInteger()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                assertEquals(7, data.readInt()); assertEquals(0, data.dataAvail()); calls.incrementAndGet()
                reply!!.writeNoException(); reply.writeString("""{"state":"IDLE","filename":"","oemQuery":{"state":"PENDING"}}""")
                return true
            }
        }); versionField.set(null, "32")
        val session = ClusterThemeSession.pin()
        assertThrows(IllegalStateException::class.java) { session.querySelection() }
        assertEquals(0, calls.get())
        versionField.set(null, "33")
        assertTrue(session.querySelection().contains("PENDING")); assertEquals(1, calls.get())
    }
    @Test fun `lost OEM query reply is never replayed`() {
        val calls = AtomicInteger()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); assertEquals(7, data.readInt())
                calls.incrementAndGet(); throw DeadObjectException()
            }
        }); versionField.set(null, "33")
        assertThrows(DeadObjectException::class.java) { ClusterThemeSession.pin().querySelection() }
        assertEquals(1, calls.get())
    }
    @Test fun `full surface start and observation refuse protocol 31 before any transaction`() {
        val calls = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls.incrementAndGet(); error("No IPC allowed")
            }
        }
        binderField.set(null, binder); versionField.set(null, "31")
        val session = ClusterThemeSession.pin()
        val file = File.createTempFile("cluster-profile", ".rcc").apply { writeText("test") }
        try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            assertThrows(IllegalStateException::class.java) {
                session.start(descriptor, ClusterThemeVariant.FULL_SURFACE_TEST) { error("No transmission boundary") }
            }
        } } finally { file.delete() }
        assertThrows(IllegalStateException::class.java) { session.observedFullSurface() }
        assertEquals(0, calls.get()); assertSame(binder, binderField.get(null))
    }

    @Test fun `protocol 32 sends fixed full surface operation then variant specific observation with nonce`() {
        val name = "Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_" + "a".repeat(32) + ".rcc"
        val calls = ArrayList<Int>()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val operation = data.readInt(); calls.add(operation)
                when (operation) {
                    5 -> {
                        ParcelFileDescriptor.CREATOR.createFromParcel(data).close()
                        assertEquals(1, data.readInt())
                    }
                    6 -> assertEquals(name, data.readString())
                    else -> error("Unexpected operation")
                }
                assertEquals(0, data.dataAvail())
                reply!!.writeNoException(); reply.writeString("""{"filename":"$name","variant":"full-surface-test"}""")
                return true
            }
        }); versionField.set(null, "32")
        val session = ClusterThemeSession.pin()
        val file = File.createTempFile("cluster-profile", ".rcc").apply { writeText("test") }
        try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            session.start(it, ClusterThemeVariant.FULL_SURFACE_TEST) {}
        } } finally { file.delete() }
        session.observedFullSurface()
        assertEquals(listOf(5, 6), calls)
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
