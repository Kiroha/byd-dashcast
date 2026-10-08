package com.byd.dashcast.hud

import android.app.Application
import android.app.Notification
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.service.notification.StatusBarNotification
import byd.fbs.naviInfo.NaviInfo
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.system.CanBusController
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
import org.robolectric.shadows.ShadowSystemClock
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises real controller and listener code against a recording daemon Binder, without a car. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NavigationOutputRoutingTest {
    private lateinit var context: Application
    private lateinit var daemon: RecordingDaemon
    private val frame = HudNavigationData(CanBusController.ICON_TURN_RIGHT, 200, "Test road",
        2_000, 300, 14, 30)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ClusterPrefs.PREFS_NAME, 0).edit().clear().commit()
        daemon = RecordingDaemon()
        setStatic(ProxyClient::class.java, "sBinder", daemon)
        setStatic(ProxyClient::class.java, "sDaemonVer", "25")
        // This is a platform gate, deliberately independent of a physical windshield HUD.
        setStatic(HudController::class.java, "isDl3Hud", true)
    }

    @After
    fun tearDown() {
        HudController.closeNavigation(context)
        setStatic(HudController::class.java, "isDl3Hud", null)
        setStatic(ProxyClient::class.java, "sBinder", null)
        setStatic(ProxyClient::class.java, "sDaemonVer", null)
    }

    @Test
    fun `cluster alone delivers guidance and clears it without any CAN or Amap command`() {
        select(hud = false, cluster = true)

        assertTrue(HudController.updateNavigation(context, frame))
        assertTrue(HudController.isHudActive)
        assertEquals(listOf(5), daemon.containerModes)
        val guidance = decode(daemon.clusterFrames.single())
        assertEquals(1, guidance.naviState())
        assertEquals(3, guidance.nextTurnIcon())
        assertEquals(200, guidance.curToSegmentDist())

        HudController.closeNavigation(context)

        assertEquals(9, decode(daemon.clusterFrames.last()).naviState())
        assertFalse(HudController.isHudActive)
        assertEquals(0, daemon.canCalls)
        assertTrue(amapBroadcasts().isEmpty())
    }

    @Test
    fun `HUD alone sends CAN without cluster activation content clear or Amap`() {
        select(hud = true, cluster = false)

        assertTrue(HudController.updateNavigation(context, frame))
        HudController.closeNavigation(context)

        assertTrue(daemon.canCalls > 0)
        assertTrue(daemon.containerModes.isEmpty())
        assertTrue(daemon.clusterFrames.isEmpty())
        assertTrue(amapBroadcasts().isEmpty())
    }

    @Test
    fun `default selection preserves both destinations and OEM bridge`() {
        assertTrue(HudController.updateNavigation(context, frame))
        HudController.closeNavigation(context)

        assertTrue(daemon.canCalls > 0)
        assertEquals(listOf(5), daemon.containerModes)
        assertEquals(listOf(1, 9), daemon.clusterFrames.map { decode(it).naviState() })
        assertEquals(listOf(8, 9), amapBroadcasts().map { it.getIntExtra("TYPE", -1) })
    }

    @Test
    fun `master off and both destinations off each drive nothing`() {
        ClusterPrefs.setNavigationEnabled(context, false)
        assertFalse(HudController.updateNavigation(context, frame))
        ClusterPrefs.setNavigationEnabled(context, true)
        select(hud = false, cluster = false)
        assertFalse(HudController.updateNavigation(context, frame))

        assertFalse(HudController.isHudActive)
        assertEquals(0, daemon.canCalls)
        assertTrue(daemon.containerModes.isEmpty())
        assertTrue(daemon.clusterFrames.isEmpty())
        assertTrue(amapBroadcasts().isEmpty())
    }

    @Test
    fun `cluster delivery is independent of rejected HUD activation`() {
        daemon.acceptCan = false
        assertTrue(HudController.updateNavigation(context, frame))
        assertEquals(1, decode(daemon.clusterFrames.single()).naviState())

        select(hud = true, cluster = false)
        assertFalse(HudController.updateNavigation(context, frame))
    }

    @Test
    fun `stop clears the destination that was opened even after preferences changed`() {
        select(hud = false, cluster = true)
        assertTrue(HudController.updateNavigation(context, frame))
        select(hud = true, cluster = false)
        HudController.closeNavigation(context)

        assertEquals(listOf(1, 9), daemon.clusterFrames.map { decode(it).naviState() })
        assertEquals(0, daemon.canCalls)
        assertTrue(amapBroadcasts().isEmpty())
    }

    @Test
    fun `switching from both to cluster only ends the old bridge and stops further HUD writes`() {
        assertTrue(HudController.updateNavigation(context, frame))
        select(hud = false, cluster = true)
        HudController.refreshOutputPreferences(context)
        assertFalse(HudController.isHudActive)
        assertEquals(9, decode(daemon.clusterFrames.last()).naviState())
        assertEquals(listOf(8, 9), amapBroadcasts().map { it.getIntExtra("TYPE", -1) })
        val canCallsAfterClear = daemon.canCalls

        assertTrue(HudController.updateNavigation(context, frame))
        HudController.closeNavigation(context)

        assertEquals(canCallsAfterClear, daemon.canCalls)
        assertEquals(2, amapBroadcasts().size)
        assertEquals(listOf(1, 9, 1, 9), daemon.clusterFrames.map { decode(it).naviState() })
    }

    @Test
    fun `watchdog clears stale cluster-only guidance without touching HUD`() {
        select(hud = false, cluster = true)
        assertTrue(HudController.updateNavigation(context, frame))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(13))

        HudController::class.java.getDeclaredMethod("closeIfStale").apply {
            isAccessible = true
        }.invoke(HudController)

        assertFalse(HudController.isHudActive)
        assertEquals(listOf(1, 9), daemon.clusterFrames.map { decode(it).naviState() })
        assertEquals(0, daemon.canCalls)
        assertTrue(amapBroadcasts().isEmpty())
    }

    @Test
    fun `unknown platform cannot be enabled by either destination switch`() {
        setStatic(HudController::class.java, "isDl3Hud", false)
        for ((hud, cluster) in listOf(true to true, true to false, false to true)) {
            select(hud, cluster)
            assertFalse(HudController.updateNavigation(context, frame))
        }
        assertEquals(0, daemon.canCalls)
        assertTrue(daemon.containerModes.isEmpty())
        assertTrue(daemon.clusterFrames.isEmpty())
        assertTrue(amapBroadcasts().isEmpty())
    }

    @Test
    fun `Morphe Maps notification drives and clears cluster-only guidance`() {
        select(hud = false, cluster = true)
        val serviceController = Robolectric.buildService(MapNotificationListenerService::class.java).create()
        val service = serviceController.get()
        try {
            val notification = navigationNotification(pkg = "app.morphe.android.apps.maps")
            service.onNotificationPosted(notification)
            awaitWriter(service)

            assertEquals("Morphe Maps guidance must reach AutoContainer", 1, daemon.clusterFrames.size)
            assertEquals(listOf(5), daemon.containerModes)
            val guidance = decode(daemon.clusterFrames.single())
            assertEquals(1, guidance.naviState())
            assertEquals(3, guidance.nextTurnIcon())
            assertEquals(200, guidance.curToSegmentDist())

            service.onNotificationRemoved(notification)
            awaitWriter(service)
            assertEquals(listOf(1, 9), daemon.clusterFrames.map { decode(it).naviState() })
            assertFalse(HudController.isHudActive)
            assertEquals(0, daemon.canCalls)
            assertTrue(amapBroadcasts().isEmpty())
        } finally {
            val executor = writerExecutor(service)
            serviceController.destroy()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `an unknown package resembling Morphe Maps cannot drive navigation`() {
        select(hud = false, cluster = true)
        val serviceController = Robolectric.buildService(MapNotificationListenerService::class.java).create()
        val service = serviceController.get()
        try {
            service.onNotificationPosted(navigationNotification(pkg = "app.morphe.android.apps.maps.fake"))
            awaitWriter(service)

            assertFalse(HudController.isHudActive)
            assertTrue(daemon.containerModes.isEmpty())
            assertTrue(daemon.clusterFrames.isEmpty())
            assertEquals(0, daemon.canCalls)
            assertTrue(amapBroadcasts().isEmpty())
        } finally {
            val executor = writerExecutor(service)
            serviceController.destroy()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `changing settings during guidance clears on writer and identical new content resumes`() {
        select(hud = false, cluster = true)
        val serviceController = Robolectric.buildService(MapNotificationListenerService::class.java).create()
        val service = serviceController.get()
        try {
            val notification = navigationNotification()
            service.onNotificationPosted(notification)
            awaitWriter(service)
            assertEquals(1, decode(daemon.clusterFrames.single()).naviState())

            ClusterPrefs.setNavigationEnabled(context, false)
            shadowOf(Looper.getMainLooper()).idle()
            awaitWriter(service)
            assertEquals(listOf(1, 9), daemon.clusterFrames.map { decode(it).naviState() })
            assertFalse(HudController.isHudActive)

            // Re-enabling alone cannot replay the old notification after a route has gone stale.
            ClusterPrefs.setNavigationEnabled(context, true)
            shadowOf(Looper.getMainLooper()).idle()
            awaitWriter(service)
            assertEquals(2, daemon.clusterFrames.size)

            service.onNotificationPosted(notification)
            awaitWriter(service)
            assertEquals(listOf(1, 9, 1), daemon.clusterFrames.map { decode(it).naviState() })
            assertTrue(daemon.callThreads.all { it == "hud-nav-writer" })
            assertEquals(0, daemon.canCalls)
            assertTrue(amapBroadcasts().isEmpty())
        } finally {
            val executor = writerExecutor(service)
            serviceController.destroy()
            // close() is asynchronous and owns its writer until the terminal clear completes.
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `turning off while a frame is executing drops queued guidance before the serial clear`() {
        select(hud = false, cluster = true)
        val serviceController = Robolectric.buildService(MapNotificationListenerService::class.java).create()
        val service = serviceController.get()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        daemon.onClusterFrame = {
            if (daemon.clusterFrames.size == 1) {
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
        }
        try {
            service.onNotificationPosted(navigationNotification())
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            service.onNotificationPosted(navigationNotification("Turn right in 100 m"))
            ClusterPrefs.setNavigationEnabled(context, false)
            shadowOf(Looper.getMainLooper()).idle()
            release.countDown()
            awaitWriter(service)

            assertEquals(listOf(1, 9), daemon.clusterFrames.map { decode(it).naviState() })
            assertFalse(HudController.isHudActive)
            assertTrue(daemon.callThreads.all { it == "hud-nav-writer" })
            assertEquals(0, daemon.canCalls)
        } finally {
            release.countDown()
            val executor = writerExecutor(service)
            serviceController.destroy()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun select(hud: Boolean, cluster: Boolean) {
        ClusterPrefs.setNavigationHudEnabled(context, hud)
        ClusterPrefs.setNavigationClusterEnabled(context, cluster)
    }

    private fun amapBroadcasts() = shadowOf(context).broadcastIntents
        .filter { it.action == HudController.AMAP_BROADCAST_ACTION }

    private fun decode(payload: ByteArray): NaviInfo = NaviInfo.getRootAsNaviInfo(ByteBuffer.wrap(payload))

    @Suppress("DEPRECATION")
    private fun navigationNotification(
        title: String = "Turn right in 200 m",
        pkg: String = "com.google.android.apps.maps"
    ): StatusBarNotification {
        val notification = Notification().apply {
            flags = Notification.FLAG_ONGOING_EVENT
            category = Notification.CATEGORY_NAVIGATION
            extras.putCharSequence(Notification.EXTRA_TITLE, title)
            extras.putCharSequence(Notification.EXTRA_TEXT, "Test road")
        }
        return StatusBarNotification(pkg, pkg,
            1, "navigation", 10_001, 20_001, 0, notification, Process.myUserHandle(), 1_000L)
    }

    private fun awaitWriter(service: MapNotificationListenerService) {
        val executor = writerExecutor(service)
        val done = CountDownLatch(1)
        // Preserve submitted guidance: a drain already queued ahead of this action consumes it.
        executor.execute { done.countDown() }
        assertTrue("navigation writer did not finish", done.await(3, TimeUnit.SECONDS))
    }

    private fun writerExecutor(service: MapNotificationListenerService): java.util.concurrent.ExecutorService {
        val field = MapNotificationListenerService::class.java.getDeclaredField("hudDispatcher")
        field.isAccessible = true
        val dispatcher = field.get(service) as LatestValueDispatcher<*>
        val executorField = LatestValueDispatcher::class.java.getDeclaredField("executor")
        executorField.isAccessible = true
        return executorField.get(dispatcher) as java.util.concurrent.ExecutorService
    }

    private fun setStatic(type: Class<*>, name: String, value: Any?) {
        type.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }

    private class RecordingDaemon : Binder() {
        var canCalls = 0
        var acceptCan = true
        val containerModes = CopyOnWriteArrayList<Int>()
        val clusterFrames = CopyOnWriteArrayList<ByteArray>()
        val callThreads = CopyOnWriteArrayList<String>()
        var onClusterFrame: (() -> Unit)? = null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
            val response = requireNotNull(reply)
            callThreads.add(Thread.currentThread().name)
            response.writeNoException()
            when (code) {
                ProxyDaemonContract.TXN_CAN_SETTING_INT -> {
                    canCalls++
                    data.readInt()
                    data.readInt()
                    response.writeInt(if (acceptCan) 0 else -1)
                }
                ProxyDaemonContract.TXN_CAN_BATCH -> {
                    canCalls++
                    val count = data.readInt()
                    repeat(count) {
                        data.readInt()
                        data.readInt()
                        data.readInt()
                        data.createByteArray()
                    }
                    response.writeInt(if (acceptCan) count else 0)
                }
                ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO_RESULT -> {
                    containerModes.add(data.readInt())
                    data.readInt()
                    data.readString()
                    response.writeInt(0)
                }
                ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO2 -> {
                    assertEquals(4, data.readInt())
                    clusterFrames.add(requireNotNull(data.createByteArray()))
                    onClusterFrame?.invoke()
                }
                else -> error("Unexpected daemon transaction $code")
            }
            return true
        }
    }
}
