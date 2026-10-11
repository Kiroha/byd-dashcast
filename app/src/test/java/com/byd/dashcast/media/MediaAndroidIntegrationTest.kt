package com.byd.dashcast.media

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Binder
import android.os.Parcel
import android.os.RemoteException
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.hud.MapNotificationListenerService
import com.byd.dashcast.hud.NavigationListenerKeeper
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MediaAndroidIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }

    @Before fun reset() {
        val token = Any()
        NavigationListenerKeeper.onCreated(token)
        NavigationListenerKeeper.onConnected(token)
        NavigationListenerKeeper.onDestroyed(token)
    }

    @After fun clean() {
        binderField.set(null, null)
        MediaPrefs.setEnabled(context, false)
        reset()
    }

    @Test fun `music preference defaults off and does not notify guidance preference observers`() {
        assertFalse(MediaPrefs.isEnabled(context))
        var navChanges = 0
        val observer = ClusterPrefs.observeNavigationOutputs(context) { navChanges++ }
        MediaPrefs.setEnabled(context, true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(MediaPrefs.isEnabled(context))
        assertEquals(0, navChanges)
        observer.close()
    }

    @Test fun `media-only opt-in permits existing listener recovery with navigation off`() {
        ClusterPrefs.setNavigationEnabled(context, false)
        val component = ComponentName(context, MapNotificationListenerService::class.java)
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(component, true)
        assertFalse(NavigationListenerKeeper.recover(context) { fail("Both disabled") })
        MediaPrefs.setEnabled(context, true)
        var requests = 0
        assertTrue(NavigationListenerKeeper.recover(context) { assertEquals(component, it); requests++ })
        assertEquals(1, requests)
        assertFalse(ClusterPrefs.isNavigationEnabled(context))
    }

    @Test fun `pinned media writes use only existing instrument transactions`() {
        val binder = RecordingBinder()
        binderField.set(null, binder)
        val transport = ProxyClusterMediaTransport()
        val connection = transport.connection()!!
        assertEquals(0, transport.integer(connection, ClusterMediaProperties.SOURCE, 11))
        assertEquals(0, transport.bytes(connection, ClusterMediaProperties.TEXT, ClusterMediaEncoding.text("Test", "")))
        assertEquals(0, transport.integer(connection, ClusterMediaProperties.STATE, 1))
        assertEquals(listOf(ProxyDaemonContract.TXN_CAN_INSTRUMENT_INT,
            ProxyDaemonContract.TXN_CAN_INSTRUMENT_BYTES, ProxyDaemonContract.TXN_CAN_INSTRUMENT_INT), binder.codes)
        assertEquals(listOf(ClusterMediaProperties.SOURCE, ClusterMediaProperties.TEXT, ClusterMediaProperties.STATE), binder.properties)
    }

    @Test fun `media transport refuses navigation IDs and odd or oversized bytes`() {
        val binder = RecordingBinder(); binderField.set(null, binder)
        val transport = ProxyClusterMediaTransport()
        for (action in listOf<() -> Unit>(
            { transport.integer(binder, 0x43E0003A, 1) },
            { transport.integer(binder, ClusterMediaProperties.SOURCE, 12) },
            { transport.bytes(binder, ClusterMediaProperties.TEXT, ByteArray(255)) },
            { transport.bytes(binder, ClusterMediaProperties.TEXT, ByteArray(256)) },
            { transport.bytes(binder, 0x43F01030, ByteArray(8)) })) {
            try { action(); fail("Out-of-scope command") } catch (_: IllegalArgumentException) {}
        }
        assertTrue(binder.codes.isEmpty())
    }

    @Test fun `old binder cannot be replayed against its replacement`() {
        val old = RecordingBinder(); val replacement = RecordingBinder()
        binderField.set(null, replacement)
        try {
            ProxyClient.canInstrumentIntOnCurrentBinder(old, ClusterMediaProperties.SOURCE, 11)
            fail("Replacement must abort")
        } catch (_: ProxyClient.ProxyException) {}
        assertTrue(old.codes.isEmpty())
        assertTrue(replacement.codes.isEmpty())
    }

    @Test fun `media RemoteException gets one attempt and never invokes daemon recovery`() {
        val binder = RecordingBinder(throwRemote = true); binderField.set(null, binder)
        try {
            ProxyClient.canInstrumentIntOnCurrentBinder(binder, ClusterMediaProperties.SOURCE, 11)
            fail("RemoteException expected")
        } catch (_: RemoteException) {}
        assertSame(binder, binderField.get(null))
        assertEquals(1, binder.codes.size)
    }

    private class RecordingBinder(private val throwRemote: Boolean = false) : Binder() {
        val codes = mutableListOf<Int>()
        val properties = mutableListOf<Int>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            codes += code
            data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
            properties += data.readInt()
            if (throwRemote) throw RemoteException()
            reply!!.writeNoException()
            reply.writeInt(0)
            return true
        }
    }
}
