package com.byd.dashcast.satellite

import android.app.Application
import android.os.SystemClock
import com.byd.dashcast.hud.HudNavigationData
import com.byd.dashcast.system.CanBusController
import com.byd.dashcast.util.concurrent.LatestValueDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercise production receiver callbacks and the real serial writer without opening a TLS listener. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteReceiverStatusTest {
    private lateinit var context: Application
    private lateinit var controller: ServiceController<SatelliteReceiverService>
    private lateinit var service: SatelliteReceiverService
    private lateinit var events: SatelliteWebSocketServer.Events

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        NavigationInputRouter.enableReceiver(context, true)
        NavigationInputRouter.selectRemote(context, true)
        controller = Robolectric.buildService(SatelliteReceiverService::class.java).create()
        service = controller.get()
        val owner = ReflectionHelpers.getField<Long>(service, "statusOwner")
        events = ReflectionHelpers.callInstanceMethod(service, "receiverEvents",
            ClassParameter.from(Long::class.javaPrimitiveType!!, owner))
    }

    @After fun tearDown() { controller.destroy(); NavigationInputRouter.enableReceiver(context, false) }

    private fun awaitWriter() {
        val writer = ReflectionHelpers.getField<LatestValueDispatcher<*>>(service, "navigation")
        val executor = ReflectionHelpers.getField<ExecutorService>(writer, "executor")
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun frame(sequence: Long = 1, age: Long = 0) = SatelliteProtocol.Navigation(sequence, age,
        HudNavigationData(CanBusController.ICON_TURN_RIGHT, 200, "private-road-marker", null, null, null, null))

    @Test fun `real receiver events separate listening authentication accepted guidance stop and disconnect`() {
        assertEquals(SatelliteStatusTracker.Connection.STARTING, SatelliteStatus.snapshot(context).connection)
        SatellitePrefs.token(context)
        events.listening()
        assertEquals(SatelliteStatusTracker.Connection.WAITING, SatelliteStatus.snapshot(context).connection)
        events.connected("peer")
        assertEquals(SatelliteStatusTracker.Guidance.WAITING, SatelliteStatus.snapshot(context).guidance)
        events.navigation("peer", frame(), SystemClock.elapsedRealtime()); awaitWriter()
        assertEquals(SatelliteStatusTracker.Guidance.ACTIVE, SatelliteStatus.snapshot(context).guidance)
        events.navigation("peer", frame(2).copy(data = null), SystemClock.elapsedRealtime()); awaitWriter()
        assertEquals(SatelliteStatusTracker.Guidance.WAITING, SatelliteStatus.snapshot(context).guidance)
        events.disconnected("peer"); awaitWriter()
        assertEquals(SatelliteStatusTracker.Connection.WAITING, SatelliteStatus.snapshot(context).connection)
    }

    @Test fun `obsolete queued guidance never becomes active and deselected source rejects current data`() {
        events.connected("peer")
        events.navigation("peer", frame(), SystemClock.elapsedRealtime() - SatelliteProtocol.MAX_AGE_MS - 1)
        awaitWriter()
        assertEquals(SatelliteStatusTracker.Guidance.WAITING, SatelliteStatus.snapshot(context).guidance)
        NavigationInputRouter.selectRemote(context, false)
        events.navigation("peer", frame(2), SystemClock.elapsedRealtime()); awaitWriter()
        assertEquals(SatelliteStatusTracker.Guidance.DISABLED, SatelliteStatus.snapshot(context).guidance)
        NavigationInputRouter.selectRemote(context, true)
        assertEquals(SatelliteStatusTracker.Guidance.WAITING, SatelliteStatus.snapshot(context).guidance)
    }

    @Test fun `expiry and revocation update visible facts before OEM cleanup`() {
        events.connected("peer")
        events.navigation("peer", frame(), SystemClock.elapsedRealtime()); awaitWriter()
        events.expired("peer")
        assertEquals(SatelliteStatusTracker.Guidance.EXPIRED, SatelliteStatus.snapshot(context).guidance)
        awaitWriter()
        SatellitePrefs.rotateToken(context)
        events.connected("peer")
        events.navigation("peer", frame(2), SystemClock.elapsedRealtime()); awaitWriter()
        assertEquals(SatelliteStatusTracker.Connection.WAITING, SatelliteStatus.snapshot(context).connection)
        assertEquals(SatelliteStatusTracker.Guidance.WAITING, SatelliteStatus.snapshot(context).guidance)
    }

    @Test fun `receiver start failure remains unavailable after its destruction`() {
        events.failed()
        controller.destroy()
        assertEquals(SatelliteStatusTracker.Connection.UNAVAILABLE, SatelliteStatus.snapshot(context).connection)
        NavigationInputRouter.enableReceiver(context, false)
        assertEquals(SatelliteStatusTracker.Connection.DISABLED, SatelliteStatus.snapshot(context).connection)
        // Recreate for the common teardown; the real failure was checked after destruction.
        controller = Robolectric.buildService(SatelliteReceiverService::class.java).create()
    }
}
