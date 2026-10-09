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
import com.byd.dashcast.satellite.NavigationInputRouter
import com.byd.dashcast.satellite.SatellitePrefs
import com.byd.dashcast.util.AppLogger
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationListenerService
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
        // Recent-route timestamps are process-wide in production, so isolate each report scenario.
        val activityField = MapNotificationListenerService::class.java.getDeclaredField("sNavActivity")
        activityField.isAccessible = true
        (activityField.get(null) as MutableMap<*, *>).clear()
        context.getSharedPreferences(ClusterPrefs.PREFS_NAME, 0).edit().clear().commit()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
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
    fun `satellite selection prevents local notifications and local teardown from clearing its route`() {
        select(hud = false, cluster = true)
        NavigationInputRouter.enableReceiver(context, true)
        NavigationInputRouter.selectRemote(context, true)
        NavigationInputRouter.acquireRemote(context, "satellite")
        assertTrue(NavigationInputRouter.updateRemote(context, "satellite", NavigationInputRouter.remoteRevision(), frame))
        val count = daemon.clusterFrames.size

        assertFalse(NavigationInputRouter.updateLocal(context, frameAtDistance(10)))
        NavigationInputRouter.closeLocal(context)

        assertTrue(HudController.isHudActive)
        assertEquals(count, daemon.clusterFrames.size)
        assertEquals(200, decode(daemon.clusterFrames.last()).curToSegmentDist())
    }

    @Test
    fun `late satellite disconnect cannot clear a replacement session or local guidance`() {
        select(hud = false, cluster = true)
        NavigationInputRouter.enableReceiver(context, true)
        NavigationInputRouter.selectRemote(context, true)
        NavigationInputRouter.acquireRemote(context, "old")
        NavigationInputRouter.acquireRemote(context, "new")
        assertTrue(NavigationInputRouter.updateRemote(context, "new", NavigationInputRouter.remoteRevision(), frame))
        val count = daemon.clusterFrames.size
        NavigationInputRouter.closeRemote(context, "old", true)
        assertEquals(count, daemon.clusterFrames.size)

        NavigationInputRouter.enableReceiver(context, false)
        assertFalse(HudController.isHudActive)
        assertEquals(9, decode(daemon.clusterFrames.last()).naviState())
        assertTrue(NavigationInputRouter.updateLocal(context, frameAtDistance(100)))
        NavigationInputRouter.closeRemote(context, "new", true)
        assertTrue(HudController.isHudActive)
        assertEquals(100, decode(daemon.clusterFrames.last()).curToSegmentDist())
    }

    @Test
    fun `queued satellite frames from before source changes are rejected`() {
        select(hud = false, cluster = true)
        NavigationInputRouter.enableReceiver(context, true)
        NavigationInputRouter.selectRemote(context, true)
        NavigationInputRouter.acquireRemote(context, "satellite")
        val revision = NavigationInputRouter.remoteRevision()
        NavigationInputRouter.selectRemote(context, false)
        NavigationInputRouter.selectRemote(context, true)
        assertFalse(NavigationInputRouter.updateRemote(context, "satellite", revision, frame))
        assertTrue(daemon.clusterFrames.isEmpty())
    }

    @Test
    fun `switching back to local accepts an identical fresh notification instead of stale dedup`() {
        select(hud = false, cluster = true)
        val service = Robolectric.buildService(MapNotificationListenerService::class.java).create().get()
        try {
            val notification = navigationNotification(title = "Turn right in 200 m")
            service.onNotificationPosted(notification)
            awaitWriter(service)
            assertTrue(HudController.isHudActive)
            NavigationInputRouter.enableReceiver(context, true)
            NavigationInputRouter.selectRemote(context, true)
            shadowOf(Looper.getMainLooper()).idle()
            service.onNotificationPosted(notification)
            awaitWriter(service)
            assertFalse(HudController.isHudActive)
            NavigationInputRouter.selectRemote(context, false)
            shadowOf(Looper.getMainLooper()).idle()
            service.onNotificationPosted(notification)
            awaitWriter(service)
            assertTrue(HudController.isHudActive)
        } finally { service.onDestroy() }
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
    fun `proxy replacement rearms the cluster channel before the next live frame`() {
        select(hud = false, cluster = true)
        assertTrue(HudController.updateNavigation(context, frame))
        val replacement = RecordingDaemon()
        setStatic(ProxyClient::class.java, "sBinder", replacement)

        assertTrue(HudController.updateNavigation(context, frameAtDistance(180)))

        assertEquals("activation cached for the old proxy must not carry over",
            listOf(5), replacement.containerModes)
        assertEquals(listOf("mode:5", "guidance:1"), replacement.navigationCalls)
        assertEquals(180, decode(replacement.clusterFrames.single()).curToSegmentDist())
        assertEquals(0, replacement.canCalls)
        assertTrue(amapBroadcasts().isEmpty())
    }

    @Test
    fun `failed cluster send invalidates activation before retrying with fresh guidance`() {
        select(hud = false, cluster = true)
        assertTrue(HudController.updateNavigation(context, frame))
        daemon.failNextClusterSend = true
        assertFalse(HudController.updateNavigation(context, frameAtDistance(180)))

        assertTrue(HudController.updateNavigation(context, frameAtDistance(160)))

        assertEquals(listOf(5, 5), daemon.containerModes)
        assertEquals(listOf("mode:5", "guidance:1"), daemon.navigationCalls.takeLast(2))
        assertEquals(160, decode(daemon.clusterFrames.last()).curToSegmentDist())
        assertEquals(0, daemon.canCalls)
        assertTrue(amapBroadcasts().isEmpty())
    }

    @Test
    fun `a proxy change during content delivery rearms and resends the same fresh frame once`() {
        select(hud = false, cluster = true)
        val replacement = RecordingDaemon()
        daemon.onClusterFrame = { setStatic(ProxyClient::class.java, "sBinder", replacement) }

        assertTrue(HudController.updateNavigation(context, frame))

        assertEquals(listOf("mode:5", "guidance:1"), replacement.navigationCalls)
        assertEquals(200, decode(replacement.clusterFrames.single()).curToSegmentDist())
        assertEquals(0, replacement.canCalls)
        assertTrue(amapBroadcasts().isEmpty())
    }

    @Test
    fun `repeated proxy replacement bounds immediate recovery and waits for the next fresh frame`() {
        select(hud = false, cluster = true)
        val second = RecordingDaemon()
        val third = RecordingDaemon()
        daemon.onClusterFrame = { setStatic(ProxyClient::class.java, "sBinder", second) }
        second.onClusterFrame = { setStatic(ProxyClient::class.java, "sBinder", third) }

        assertFalse(HudController.updateNavigation(context, frame))

        assertEquals(1, daemon.clusterFrames.size)
        assertEquals(1, second.clusterFrames.size)
        assertTrue("a single update must not loop across restarting proxies", third.clusterFrames.isEmpty())
        assertTrue(HudController.updateNavigation(context, frameAtDistance(180)))
        assertEquals(listOf("mode:5", "guidance:1"), third.navigationCalls)
        assertEquals(180, decode(third.clusterFrames.single()).curToSegmentDist())
        assertEquals(0, third.canCalls)
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
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
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
            assertEquals(MapNotificationListenerService.NAV_PARSED,
                MapNotificationListenerService.recentNavStatus("maps"))
            assertTrue(MapNotificationListenerService.navSeenSummary("maps").startsWith("yes ("))
            assertEquals(MapNotificationListenerService.NAV_NONE,
                MapNotificationListenerService.recentNavStatus("waze"))

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
    fun `Morphe distance without maneuver remains reportable as a Maps parse failure`() {
        select(hud = false, cluster = true)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val controller = Robolectric.buildService(MapNotificationListenerService::class.java).create()
        val service = controller.get()
        val executor = writerExecutor(service)
        try {
            val notification = navigationNotification(pkg = "app.morphe.android.apps.maps")
            notification.notification.extras.putCharSequence("android.title", "100 m")
            notification.notification.extras.putCharSequence("android.text", "Example road")
            AppLogger.clear()
            service.onNotificationPosted(notification)
            awaitWriter(service)

            assertEquals(MapNotificationListenerService.NAV_PARSE_FAIL,
                MapNotificationListenerService.recentNavStatus("maps"))
            assertTrue(MapNotificationListenerService.navSeenSummary("maps").startsWith("parse-fail ("))
            assertTrue(daemon.containerModes.isEmpty())
            assertTrue(daemon.clusterFrames.isEmpty())
            assertEquals(0, daemon.canCalls)
            val journal = AppLogger.get()
            assertTrue(journal.contains("NAV REJECT app=app.morphe.android.apps.maps reason=no_maneuver"))
            assertFalse(journal.contains("Example road"))
            assertTrue(MapNotificationListenerService.diagnosticsSummary().contains("lastRejection: app=app.morphe.android.apps.maps reason=no_maneuver"))
            assertTrue(MapNotificationListenerService.diagnosticsSummary(context)
                .contains("currentOutputs=NavigationOutputs(hud=false, cluster=true)"))
        } finally {
            controller.destroy()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `reconnecting the listener restores an already active Morphe route after clearing it`() {
        select(hud = false, cluster = true)
        val controller = Robolectric.buildService(MapNotificationListenerService::class.java).create()
        val service = controller.get()
        val executor = writerExecutor(service)
        try {
            val notification = navigationNotification(pkg = "app.morphe.android.apps.maps")
            service.onNotificationPosted(notification)
            awaitWriter(service)
            service.onListenerDisconnected()
            awaitWriter(service)
            assertEquals(listOf(1, 9), daemon.clusterFrames.map { decode(it).naviState() })

            Shadow.extract<ShadowNotificationListenerService>(service).addActiveNotification(notification)
            service.onListenerConnected()
            awaitWriter(service)

            assertEquals(listOf(1, 9, 1), daemon.clusterFrames.map { decode(it).naviState() })
            assertEquals(200, decode(daemon.clusterFrames.last()).curToSegmentDist())
            val framesBeforeSnapshot = daemon.clusterFrames.size
            assertEquals(listOf(notification.key),
                MapNotificationListenerService.activeMapsNotificationsForCapture()!!.map { it.key })
            assertEquals("a diagnostic snapshot must not activate or resend guidance",
                framesBeforeSnapshot, daemon.clusterFrames.size)
            assertEquals(0, daemon.canCalls)
            assertTrue(amapBroadcasts().isEmpty())
        } finally {
            controller.destroy()
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

    private fun frameAtDistance(distance: Int) = HudNavigationData(frame.iconId, distance,
        frame.roadName, frame.remainingDistanceMeters, frame.remainingTimeSeconds,
        frame.etaHour, frame.etaMinute)

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
        var failNextClusterSend = false
        val containerModes = CopyOnWriteArrayList<Int>()
        val clusterFrames = CopyOnWriteArrayList<ByteArray>()
        val navigationCalls = CopyOnWriteArrayList<String>()
        val callThreads = CopyOnWriteArrayList<String>()
        var onClusterFrame: (() -> Unit)? = null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
            val response = requireNotNull(reply)
            callThreads.add(Thread.currentThread().name)
            if (code == ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO2 && failNextClusterSend) {
                failNextClusterSend = false
                response.writeException(IllegalStateException("simulated container failure"))
                return true
            }
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
                    val mode = data.readInt()
                    containerModes.add(mode)
                    navigationCalls.add("mode:$mode")
                    data.readInt()
                    data.readString()
                    response.writeInt(0)
                }
                ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO2 -> {
                    assertEquals(4, data.readInt())
                    clusterFrames.add(requireNotNull(data.createByteArray()))
                    navigationCalls.add("guidance:" + decodePayloadState(clusterFrames.last()))
                    onClusterFrame?.invoke()
                }
                else -> error("Unexpected daemon transaction $code")
            }
            return true
        }

        private fun decodePayloadState(bytes: ByteArray): Int =
            NaviInfo.getRootAsNaviInfo(ByteBuffer.wrap(bytes)).naviState()
    }
}
