package com.byd.dashcast.hud

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.Looper
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.ProxyKeeperService
import com.byd.dashcast.util.concurrent.LatestValueDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationListenerService
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NavigationListenerKeeperTest {
    private lateinit var context: Application
    private lateinit var component: ComponentName

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ClusterPrefs.PREFS_NAME, 0).edit().clear().commit()
        component = ComponentName(context, MapNotificationListenerService::class.java)
        resetRecovery()
    }

    @After
    fun tearDown() {
        resetRecovery()
        grant(false)
        ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
            .set(null, null)
    }

    @Test
    fun `unconfirmed rebind refreshes only the already approved exact listener after thirty seconds`() {
        grant(true)
        val refreshed = mutableListOf<ComponentName>()
        fun probe() = NavigationListenerKeeper.recover(context,
            refreshApprovedListener = { refreshed += it }, requestRebind = {})
        assertTrue(probe())
        assertTrue(refreshed.isEmpty())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        assertTrue(probe())
        assertEquals(listOf(component), refreshed)
        repeat(10) { probe() }
        assertEquals(1, refreshed.size)
        grant(false)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        assertFalse(probe())
        assertEquals(1, refreshed.size)
    }

    @Test
    fun `a confirmed or disabled listener never refreshes its existing approval`() {
        grant(true)
        val token = Any()
        NavigationListenerKeeper.onCreated(token)
        NavigationListenerKeeper.onConnected(token)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        assertFalse(NavigationListenerKeeper.recover(context,
            refreshApprovedListener = { fail("healthy listener") }, requestRebind = {}))
        NavigationListenerKeeper.onDestroyed(token)
        ClusterPrefs.setNavigationEnabled(context, false)
        assertFalse(NavigationListenerKeeper.recover(context,
            refreshApprovedListener = { fail("disabled listener") }, requestRebind = {}))
    }

    @Test
    fun `recovery requires permission for the exact listener component`() {
        val requested = mutableListOf<ComponentName>()
        assertFalse(NavigationListenerKeeper.recover(context) { requested += it })
        val unrelated = ComponentName(context.packageName, context.packageName + ".OtherListener")
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(unrelated, true)
        assertFalse(NavigationListenerKeeper.recover(context) { requested += it })

        grant(true)
        assertTrue(NavigationListenerKeeper.recover(context) { requested += it })
        assertEquals(listOf(component), requested)
        assertTrue(NavigationListenerKeeper.summary().contains("connected=false"))
    }

    @Test
    fun `master off and no selected output suppress recovery while cluster alone enables it`() {
        grant(true)
        var requests = 0
        ClusterPrefs.setNavigationEnabled(context, false)
        assertFalse(NavigationListenerKeeper.recover(context) { requests++ })
        ClusterPrefs.setNavigationEnabled(context, true)
        ClusterPrefs.setNavigationHudEnabled(context, false)
        ClusterPrefs.setNavigationClusterEnabled(context, false)
        assertFalse(NavigationListenerKeeper.recover(context) { requests++ })

        ClusterPrefs.setNavigationClusterEnabled(context, true)
        assertTrue(NavigationListenerKeeper.recover(context) { requests++ })
        assertEquals(1, requests)
        assertFalse(ClusterPrefs.isNavigationHudEnabled(context))
    }

    @Test
    fun `HUD alone also enables notification recovery`() {
        grant(true)
        ClusterPrefs.setNavigationClusterEnabled(context, false)
        assertTrue(NavigationListenerKeeper.recover(context) {})
        assertFalse(ClusterPrefs.isNavigationClusterEnabled(context))
    }

    @Test
    fun `platform rejection is retried with backoff without throwing into the keeper`() {
        grant(true)
        var requests = 0
        assertTrue(NavigationListenerKeeper.recover(context) {
            requests++
            throw SecurityException()
        })
        assertFalse(NavigationListenerKeeper.recover(context) { requests++ })
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        assertTrue(NavigationListenerKeeper.recover(context) { requests++ })
        assertEquals(2, requests)
    }

    @Test
    fun `real listener callbacks suppress healthy recovery and restore it after disconnect`() {
        grant(true)
        withListener { listener ->
            assertTrue(NavigationListenerKeeper.recover(context) {})
            listener.onListenerConnected()
            ShadowSystemClock.advanceBy(Duration.ofHours(1))
            assertFalse(NavigationListenerKeeper.recover(context) { fail("healthy idle listener") })
            listener.onListenerDisconnected()
            val before = ShadowNotificationListenerService.getRebindRequestCount()
            NavigationListenerKeeper.maybeKeepAlive(context)
            assertEquals(before + 1, ShadowNotificationListenerService.getRebindRequestCount())
        }
    }

    @Test
    fun `real service destruction invalidates connectivity even without disconnect callback`() {
        grant(true)
        withListener { it.onListenerConnected() }
        assertTrue(NavigationListenerKeeper.recover(context) {})
    }

    @Test
    fun `repeated keeper starts probe promptly without duplicating reconnect requests`() {
        grant(true)
        // Exercise the actual ACC-on entry point without starting the unrelated proxy bootstrap.
        val keeper = Robolectric.buildService(ProxyKeeperService::class.java).get()
        setField(keeper, "mRunning", true)
        setField(keeper, "mHandler", Handler(Looper.getMainLooper()))
        try {
            val before = ShadowNotificationListenerService.getRebindRequestCount()
            repeat(3) {
                assertEquals(Service.START_STICKY,
                    keeper.onStartCommand(Intent(context, ProxyKeeperService::class.java), 0, it))
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before + 1, ShadowNotificationListenerService.getRebindRequestCount())
        } finally {
            keeper.onDestroy()
        }
    }

    @Test
    fun `normal background heartbeat also supervises the listener`() {
        grant(true)
        val keeper = Robolectric.buildService(ProxyKeeperService::class.java).get()
        setField(keeper, "mHudListenerArmed", true)
        ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
            .set(null, Binder())
        try {
            val before = ShadowNotificationListenerService.getRebindRequestCount()
            keeper.javaClass.getDeclaredMethod("tickInternal").apply { isAccessible = true }
                .invoke(keeper)
            assertEquals(before + 1, ShadowNotificationListenerService.getRebindRequestCount())
        } finally {
            keeper.onDestroy()
        }
    }

    private fun grant(enabled: Boolean) {
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(component, enabled)
    }

    private fun resetRecovery() {
        val token = Any()
        NavigationListenerKeeper.onCreated(token)
        NavigationListenerKeeper.onConnected(token)
        NavigationListenerKeeper.onDestroyed(token)
    }

    private fun withListener(block: (MapNotificationListenerService) -> Unit) {
        val controller = Robolectric.buildService(MapNotificationListenerService::class.java).create()
        val service = controller.get()
        val dispatcher = service.javaClass.getDeclaredField("hudDispatcher")
            .apply { isAccessible = true }.get(service) as LatestValueDispatcher<*>
        val executor = dispatcher.javaClass.getDeclaredField("executor")
            .apply { isAccessible = true }.get(dispatcher) as ExecutorService
        try {
            block(service)
        } finally {
            controller.destroy()
            assertTrue("listener writer did not terminate", executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
