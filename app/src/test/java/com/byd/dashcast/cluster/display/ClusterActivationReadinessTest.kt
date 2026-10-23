package com.byd.dashcast.cluster.display

import android.app.Application
import android.os.Looper
import android.view.Display
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.infrastructure.AdbLocalClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/** A VD can be published independently of the asynchronous AutoContainer activation result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ClusterActivationReadinessTest {
    private val commands = mutableListOf<Int>()
    private val pending = mutableMapOf<Int, MutableList<AdbLocalClient.Callback>>()
    private val ready = mutableListOf<Int>()
    private val late = mutableListOf<Int>()
    private var timeouts = 0
    private lateinit var manager: ClusterManager
    private val callback = object : ClusterManager.DisplayReadyCallback {
        override fun onDisplayReady(display: Display?, displayId: Int) { ready += displayId }
        override fun onDisplayTimeout() { timeouts++ }
        override fun onDisplayLateReady(display: Display?, displayId: Int) { late += displayId }
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun display() = ShadowDisplayManager.addDisplay("w1920dp-h720dp")
    private fun take(command: Int) = pending.getValue(command).removeAt(0)

    @Before fun setup() {
        ProjectionCommandBus.currentSession()?.let(ProjectionCommandBus::endSession)
        ClusterManager.notifyProjectionStopped()
        ClusterDisplayRegistry.clear()
        ClusterPrefs.setAdasWindowFixEnabled(RuntimeEnvironment.getApplication(), false)
        manager = ClusterManager(RuntimeEnvironment.getApplication()) { _, _, command, completion ->
            commands += command
            pending.getOrPut(command) { mutableListOf() } += completion
        }
    }

    @After fun cleanup() {
        manager.abandon()
        ProjectionCommandBus.currentSession()?.let(ProjectionCommandBus::endSession)
        ClusterManager.notifyProjectionStopped()
        ClusterDisplayRegistry.clear()
    }

    @Test fun `early display waits for successful cmd16 and continues polling`() {
        manager.activateClusterDisplay(callback)
        advance(250)
        val id = display()
        advance(1750)
        assertEquals(listOf(16), commands)
        assertTrue(ready.isEmpty())
        assertFalse(ClusterManager.isQtInProjectionMode())
        take(16).onSuccess("")
        advance(500)
        assertEquals(listOf(id), ready)
        assertTrue(ClusterManager.isQtInProjectionMode())
        advance(3000)
        take(35).onSuccess("")
        advance(20000)
        assertEquals(listOf(id), ready)
        assertEquals(0, timeouts)
    }

    @Test fun `failed cmd16 cannot become ready through cmd35 or a late display`() {
        manager.activateClusterDisplay(callback)
        advance(2000)
        take(16).onError("activation refused")
        advance(3000)
        take(35).onSuccess("")
        display()
        advance(15000)
        assertEquals(1, timeouts)
        display() // Exercise the late-arrival listener as well as its polling fallback.
        advance(60000)
        assertTrue(ready.isEmpty())
        assertTrue(late.isEmpty())
        assertFalse(ClusterManager.isQtInProjectionMode())
        assertEquals(listOf(16, 35), commands)
    }

    @Test fun `late cmd16 success can resolve during the existing grace window`() {
        manager.activateClusterDisplay(callback)
        advance(20000)
        assertEquals(1, timeouts)
        val id = display()
        advance(1000)
        assertTrue(late.isEmpty())
        take(16).onSuccess("")
        advance(2000)
        assertTrue(ready.isEmpty())
        assertEquals(listOf(id), late)
        assertTrue(ClusterManager.isQtInProjectionMode())
    }

    @Test fun `cancelled activation success cannot authorize its replacement`() {
        manager.activateClusterDisplay(callback)
        advance(2000)
        val old = take(16)
        manager.cancel()
        manager.activateClusterDisplay(callback)
        val id = display()
        old.onSuccess("")
        advance(2000)
        assertTrue(ready.isEmpty())
        assertFalse(ClusterManager.isQtInProjectionMode())
        take(16).onSuccess("")
        advance(500)
        assertEquals(listOf(id), ready)
    }

    @Test fun `success posted before cancellation cannot leak into a new activation`() {
        manager.activateClusterDisplay(callback)
        advance(2000)
        take(16).onSuccess("") // The transport thread only posts; the main looper has not drained.
        manager.cancel()
        manager.activateClusterDisplay(callback)
        display()
        advance(2000)
        assertTrue(ready.isEmpty())
        assertFalse(ClusterManager.isQtInProjectionMode())
    }

    @Test fun `retired and never started managers cannot erase current owner geometry`() {
        manager.activateClusterDisplay(callback)
        val newOwner = ProjectionCommandBus.beginSession()
        val geometry = ClusterDisplayInfo(9, "fission_bg_xdjaVirtualSurface", 1920, 720, 9)
        ClusterDisplayRegistry.set(geometry)
        manager.cancel()
        manager.abandon()
        assertSame(geometry, ClusterDisplayRegistry.get())
        val unused = ClusterManager(RuntimeEnvironment.getApplication()) { _, _, _, _ -> fail("no transport") }
        unused.cancel()
        assertSame(geometry, ClusterDisplayRegistry.get())
        assertTrue(ProjectionCommandBus.isCurrent(newOwner))
    }

    @Test fun `new activation and current cancellation clear their own geometry`() {
        val geometry = ClusterDisplayInfo(9, "fission_bg_xdjaVirtualSurface", 1920, 720, 9)
        ClusterDisplayRegistry.set(geometry)
        manager.activateClusterDisplay(callback)
        assertNull(ClusterDisplayRegistry.get())
        ClusterDisplayRegistry.set(geometry)
        manager.cancel()
        assertNull(ClusterDisplayRegistry.get())
    }

    @Test fun `existing display in native mode still requires warm cmd16 success`() {
        val id = display()
        manager.activateClusterDisplay(callback)
        advance(500)
        assertEquals(listOf(16), commands)
        assertTrue(ready.isEmpty())
        take(16).onSuccess("")
        advance(0)
        assertEquals(listOf(id), ready)
    }

    @Test fun `existing display with unchanged active Qt session keeps the fast path`() {
        val id = display()
        ClusterManager.notifyProjectionActive()
        manager.activateClusterDisplay(callback)
        advance(0)
        assertEquals(listOf(id), ready)
        assertTrue(commands.isEmpty())
    }
}
