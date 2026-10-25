package com.byd.dashcast.cluster.display

import android.app.Application
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.cluster.ProjectionAdasCoordinator
import com.byd.dashcast.cluster.ProjectionAdasPlan
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager
import com.byd.dashcast.data.prefs.ClusterPrefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Keep activation callbacks pending while exercising the actual helper and process owner queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DashboardDisplayHelperLifecycleTest {
    private val ready = mutableListOf<Int>()
    private var disconnected = 0
    private val callbacks = mutableListOf<ClusterManager.DisplayReadyCallback>()
    private lateinit var helper: DashboardDisplayHelper
    private val coordinatorInstance = ProjectionAdasCoordinator::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }

    @Before fun setup() {
        ProjectionCommandBus.currentSession()?.let(ProjectionCommandBus::endSession)
        ClusterManager.notifyProjectionStopped()
        ClusterDisplayRegistry.clear()
        helper = DashboardDisplayHelper(RuntimeEnvironment.getApplication(),
            object : DashboardDisplayHelper.Listener {
                override fun onDashboardDisplayConnected(display: Display?, displayId: Int) { ready += displayId }
                override fun onDashboardDisplayDisconnected() { disconnected++ }
            }, { _, callback -> callbacks += callback }, {})
    }

    @After fun cleanup() {
        helper.stopWithoutAdb()
        ProjectionCommandBus.currentSession()?.let(ProjectionCommandBus::endSession)
        ClusterManager.notifyProjectionStopped()
        ClusterDisplayRegistry.clear()
        ProjectionAdasCoordinator.cancelForManualRecovery()
        coordinatorInstance.set(null, null)
    }

    private fun disconnectListener(): DisplayManager.DisplayListener =
        DashboardDisplayHelper::class.java.getDeclaredField("mDisconnectListener")
            .apply { isAccessible = true }.get(helper) as DisplayManager.DisplayListener

    @Test fun `start stop start drops old ready timeout late ready and display removal`() {
        helper.start()
        val old = callbacks.single()
        val oldDisconnect = disconnectListener()
        old.onDisplayReady(null, 9)
        helper.stopWithoutAdb()
        helper.start()
        callbacks.last().onDisplayReady(null, 9)
        old.onDisplayReady(null, 8)
        old.onDisplayTimeout()
        old.onDisplayLateReady(null, 10)
        oldDisconnect.onDisplayRemoved(9)
        assertEquals(listOf(9, 9), ready)
        assertEquals(9, helper.getKnownClusterDisplayId())
        assertEquals(0, disconnected)
        assertNotSame(oldDisconnect, disconnectListener())
    }

    @Test fun `completion is independent from display order and resets on new ownership`() {
        helper.start()
        callbacks.last().onDisplayReady(null, 9)
        assertFalse(helper.isActivationComplete())
        callbacks.last().onActivationComplete(true)
        assertTrue(helper.isActivationComplete())
        val old = callbacks.last()
        helper.start()
        assertFalse(helper.isActivationComplete())
        old.onActivationComplete(true)
        assertFalse(helper.isActivationComplete())
        callbacks.last().onActivationComplete(true)
        assertTrue(helper.isActivationComplete())
        callbacks.last().onDisplayReady(null, 9)
        assertEquals(9, helper.getKnownClusterDisplayId())
        helper.stopWithoutAdb()
        callbacks.last().onActivationComplete(true)
        assertFalse(helper.isActivationComplete())
    }

    @Test fun `replacement without stop retires old callbacks and keeps a single current subscription`() {
        helper.start()
        val old = callbacks.single()
        val oldDisconnect = disconnectListener()
        helper.start()
        callbacks.last().onDisplayReady(null, 9)
        old.onDisplayReady(null, 8)
        old.onDisplayTimeout()
        old.onDisplayLateReady(null, 10)
        oldDisconnect.onDisplayRemoved(9)
        assertEquals(listOf(9), ready)
        assertEquals(0, disconnected)
        assertEquals(9, helper.getKnownClusterDisplayId())
        disconnectListener().onDisplayRemoved(9)
        assertEquals(1, disconnected)
        assertEquals(-1, helper.getKnownClusterDisplayId())
    }

    @Test fun `another process owner retires callbacks even before local stop`() {
        helper.start()
        callbacks.single().onDisplayReady(null, 9)
        val oldDisconnect = disconnectListener()
        ProjectionCommandBus.beginSession()
        callbacks.single().onDisplayTimeout()
        callbacks.single().onDisplayLateReady(null, 8)
        oldDisconnect.onDisplayRemoved(9)
        assertEquals(listOf(9), ready)
        assertEquals(0, disconnected)
        assertEquals(-1, helper.getKnownClusterDisplayId())
    }

    @Test fun `duplicate readiness and a late timeout cannot disconnect a connected display`() {
        helper.start()
        val callback = callbacks.single()
        callback.onDisplayReady(null, 9)
        callback.onDisplayReady(null, 9)
        callback.onDisplayLateReady(null, 9)
        callback.onDisplayTimeout()
        assertEquals(listOf(9), ready)
        assertEquals(0, disconnected)
        disconnectListener().onDisplayRemoved(9)
        assertEquals(1, disconnected)
    }

    @Test fun `stop clears cached Qt mode before an old close can finish`() {
        helper.start()
        val owner = ProjectionCommandBus.currentSession()!!
        var release: ((Boolean) -> Unit)? = null
        ProjectionCommandBus.submitOwned(owner, { true }, { release = it }, {})
        ClusterManager.notifyProjectionActive()
        helper.stop()
        assertFalse("restart must not reuse a surface pending native close", ClusterManager.isQtInProjectionMode())
        assertEquals(-1, helper.getKnownClusterDisplayId())
        helper.start()
        release!!(true) // The queued old close is rejected before any real transport is invoked.
        callbacks.last().onDisplayReady(null, 9)
        assertEquals(listOf(9), ready)
    }

    private fun serviceWithRealDisplayManager(): ClusterService {
        val app = RuntimeEnvironment.getApplication()
        // Attach a real Service without invoking startup, which would probe live transports.
        val service = Robolectric.buildService(ClusterService::class.java).get()
        val manager = ClusterManager(app) { _, _, _, _ -> }
        helper = DashboardDisplayHelper(app, object : DashboardDisplayHelper.Listener {
            override fun onDashboardDisplayConnected(display: Display?, displayId: Int) {}
            override fun onDashboardDisplayDisconnected() {}
        }, manager::activateClusterDisplay, manager::cancel)
        fun field(name: String, value: Any) {
            ClusterService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        field("mDisplayHelper", helper)
        field("mMirrorManager", ClusterMirrorManager()) // Inactive: release performs no daemon I/O.
        field("mLauncher", DashboardLauncher(app))
        field("mProjectionActive", true)
        helper.start()
        return service
    }

    @Test fun `old service destroy and stop cannot erase replacement geometry`() {
        val service = serviceWithRealDisplayManager()
        val current = ProjectionCommandBus.beginSession()
        val app = RuntimeEnvironment.getApplication()
        val coordinator = ProjectionAdasCoordinator(app) { false }
        coordinatorInstance.set(null, coordinator)
        ClusterPrefs.setProjectionAdasEnabled(app, false)
        ProjectionAdasCoordinator.targetRequested(app, "com.example.navigation", 9)
        shadowOf(Looper.getMainLooper()).idle()
        val target = ProjectionAdasCoordinator::class.java.getDeclaredField("target").apply { isAccessible = true }
        val epoch = ProjectionAdasCoordinator::class.java.getDeclaredField("epoch").apply { isAccessible = true }
        val activeTarget = target.get(coordinator)
        val activeEpoch = epoch.getLong(coordinator)
        assertNotNull(activeTarget)
        val geometry = ClusterDisplayInfo(9, "fission_bg_xdjaVirtualSurface", 1920, 720, 9)
        ClusterDisplayRegistry.set(geometry)
        service.stopProjectionNoAdb()
        service.onDestroy()
        assertSame(geometry, ClusterDisplayRegistry.get())
        assertTrue(ProjectionCommandBus.isCurrent(current))
        assertSame(activeTarget, target.get(coordinator))
        assertEquals(activeEpoch, epoch.getLong(coordinator))
    }

    @Test fun `current service destroy clears its own geometry before queued native close`() {
        val service = serviceWithRealDisplayManager()
        val owner = ProjectionCommandBus.currentSession()!!
        var release: ((Boolean) -> Unit)? = null
        ProjectionCommandBus.submitOwned(owner, { true }, { release = it }, {})
        ClusterDisplayRegistry.set(ClusterDisplayInfo(9, "fission_bg_xdjaVirtualSurface", 1920, 720, 9))
        try {
            service.onDestroy()
            assertNull(ClusterDisplayRegistry.get())
        } finally {
            ProjectionCommandBus.beginSession()
            release!!(true) // Discard the old queued close; never invoke a native transport.
        }
    }

    @Test fun `current service stop and destroy retain full existing ADAS recovery`() {
        val service = serviceWithRealDisplayManager()
        val app = RuntimeEnvironment.getApplication()
        val coordinator = ProjectionAdasCoordinator(app) { true }
        coordinatorInstance.set(null, coordinator)
        val frames = mutableListOf<String>()
        val commands = mutableListOf<Int>()
        val io = object : ProjectionAdasPlan.Io {
            override fun now() = SystemClock.uptimeMillis()
            override fun later(delay: Long, action: () -> Unit) {
                Handler(Looper.getMainLooper()).postDelayed(action, delay)
            }
            override fun valid() = true
            override fun journal(done: (Boolean) -> Unit) = done(true)
            override fun initialize(valid: () -> Boolean, done: (Boolean) -> Unit) = done(valid())
            override fun command(code: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
                if (valid()) commands += code
                done(valid())
            }
            override fun frames(frame: String, count: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
                if (valid()) repeat(count) { frames += frame }
                done(valid())
            }
        }
        ProjectionAdasCoordinator::class.java.getDeclaredField("plan").apply { isAccessible = true }
            .set(coordinator, ProjectionAdasPlan(io))
        ProjectionAdasCoordinator::class.java.getDeclaredField("sequenceRequested").apply { isAccessible = true }
            .setBoolean(coordinator, true)
        ClusterPrefs.saveProjectionAdasRecovery(app)
        service.stopProjectionNoAdb()
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3200))
        assertEquals(List(40) { ProjectionAdasPlan.RESTORE }, frames)
        assertEquals(listOf(12, 53), commands)
    }
}
