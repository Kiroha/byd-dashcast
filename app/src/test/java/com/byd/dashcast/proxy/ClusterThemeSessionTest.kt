package com.byd.dashcast.proxy

import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
}
