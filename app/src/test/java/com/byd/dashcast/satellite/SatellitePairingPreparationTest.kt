package com.byd.dashcast.satellite

import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
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
import org.robolectric.shadows.ShadowDialog
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatellitePairingPreparationTest {
    @Before fun setUp() {
        SatellitePairingSession.close()
        SatellitePrefs.setEnabled(RuntimeEnvironment.getApplication(), true)
    }
    @After fun tearDown() { SatellitePairingSession.close() }

    private fun prepare(attempt: Long, hosts: () -> List<String> = { listOf("192.168.51.1") },
        profile: (List<String>) -> String = { "synthetic-profile" },
        port: Int = 0) = SatellitePairingPreparation.prepare(attempt, hosts, profile) { text, current ->
        SatellitePairingServer(text, current.code, port, lifetimeMs = current.remainingMs,
            onClosed = { SatellitePairingSession.listenerClosed(attempt) })
    }

    @Test fun `profile addresses exclude IPv6 loopback public and unspecified endpoints`() {
        val addresses = listOf("fe80::1", "fd00::1", "::1", "127.0.0.1", "0.0.0.0", "8.8.8.8",
            "192.168.51.1", "192.168.49.1", "192.168.51.1").map(InetAddress::getByName)
        assertEquals(listOf("192.168.51.1", "192.168.49.1"), SatelliteLanAddresses.select(addresses))
    }

    @Test fun `no IPv4 and certificate exceptions retain fixed failure states without credentials`() {
        var attempt = SatellitePairingSession.begin()
        prepare(attempt, hosts = { emptyList() })
        assertEquals(SatellitePairingSession.Failure.NO_IPV4, SatellitePairingSession.snapshot()?.failure)
        attempt = SatellitePairingSession.begin()
        prepare(attempt, profile = { throw IllegalStateException("synthetic-private-profile") })
        val failed = requireNotNull(SatellitePairingSession.snapshot())
        assertEquals(SatellitePairingSession.Failure.CREDENTIALS, failed.failure)
        assertNull(failed.profile)
        assertFalse(failed.ready)
    }

    @Test fun `bind failure remains visible through its close callback and Retry creates a fresh attempt`() {
        val attempt = SatellitePairingSession.begin()
        ServerSocket(0).use { occupied -> prepare(attempt, port = occupied.localPort) }
        assertEquals(SatellitePairingSession.Failure.LISTENER, SatellitePairingSession.snapshot()?.failure)
        val controller = Robolectric.buildActivity(SatelliteSettingsActivity::class.java).setup()
        try {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertFalse(dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)
            assertTrue(dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled)
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
            assertNotEquals(attempt, SatellitePairingSession.snapshot()?.attempt)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `pending preparation survives CarPlay and shows the original code after binding`() {
        val attempt = SatellitePairingSession.begin()
        val original = requireNotNull(SatellitePairingSession.snapshot())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val controller = Robolectric.buildActivity(SatelliteSettingsActivity::class.java).setup()
        try {
            val task = worker.submit { prepare(attempt, hosts = {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); listOf("192.168.51.1")
            }) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            controller.pause().stop()
            controller.start().resume()
            val pending = requireNotNull(SatellitePairingSession.snapshot())
            assertEquals(original.code, pending.code)
            assertTrue(pending.remainingMs <= original.remainingMs)
            release.countDown()
            task.get(3, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            val ready = requireNotNull(SatellitePairingSession.snapshot())
            assertTrue(ready.ready)
            assertEquals(original.code, ready.code)
            assertTrue((ShadowDialog.getLatestDialog() as AlertDialog).isShowing)
        } finally { release.countDown(); worker.shutdownNow(); controller.pause().stop().destroy() }
    }

    @Test fun `old receiver teardown and old listener failures cannot cancel a replacement retry`() {
        val old = SatellitePairingSession.begin()
        SatellitePairingSession.claimReceiver(7)
        val fresh = SatellitePairingSession.begin()
        SatellitePairingSession.receiverStopped(7, failed = true)
        SatellitePairingSession.listenerClosed(old)
        SatellitePairingSession.fail(old, SatellitePairingSession.Failure.CREDENTIALS)
        assertEquals(fresh, SatellitePairingSession.snapshot()?.attempt)
        assertNull(SatellitePairingSession.snapshot()?.failure)
        SatellitePairingSession.claimReceiver(8)
        SatellitePairingSession.claimReceiver(7)
        SatellitePairingSession.receiverStopped(7, failed = false)
        assertEquals(fresh, SatellitePairingSession.snapshot()?.attempt)
        SatellitePairingSession.receiverStopped(8, failed = true)
        assertEquals(SatellitePairingSession.Failure.RECEIVER, SatellitePairingSession.snapshot()?.failure)
    }

    @Test fun `sampling during listener close does not mistake queued failure for expiry`() {
        val attempt = SatellitePairingSession.begin()
        val callbackEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val current = requireNotNull(SatellitePairingSession.snapshot())
        val server = SatellitePairingServer("synthetic-profile", current.code, 0, onClosed = {
            callbackEntered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            SatellitePairingSession.listenerClosed(attempt)
        })
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertTrue(server.start())
            assertTrue(SatellitePairingSession.attach(attempt, server))
            val task = worker.submit { server.close() }
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
            val closing = requireNotNull(SatellitePairingSession.snapshot())
            assertEquals(attempt, closing.attempt)
            assertTrue(closing.remainingMs > 0)
            assertFalse(closing.ready)
            release.countDown()
            task.get(3, TimeUnit.SECONDS)
            assertEquals(SatellitePairingSession.Failure.LISTENER, SatellitePairingSession.snapshot()?.failure)
        } finally { release.countDown(); server.close(); worker.shutdownNow() }
    }
}
