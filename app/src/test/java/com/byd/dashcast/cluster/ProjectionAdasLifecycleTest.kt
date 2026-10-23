package com.byd.dashcast.cluster

import android.app.Application
import android.os.Binder
import android.os.Handler
import android.os.Looper
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.proxy.ProxyClient
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ProjectionAdasLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var owner: ProjectionAdasCoordinator
    private var oldBinder: Any? = null
    private val instance = ProjectionAdasCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
    private fun field(name: String) = ProjectionAdasCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    @Test fun `failed disk commit cannot leave automatic opt in enabled in memory`() {
        val backing = app.getSharedPreferences("failed-opt-in", 0)
        backing.edit().clear().commit()
        val failing = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)
        ) { _, method, args ->
            if (method.name != "edit") method.invoke(backing, *(args ?: emptyArray()))
            else {
                val editor = backing.edit()
                java.lang.reflect.Proxy.newProxyInstance(
                    android.content.SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(android.content.SharedPreferences.Editor::class.java)
                ) { proxy, editMethod, editArgs ->
                    val result = editMethod.invoke(editor, *(editArgs ?: emptyArray()))
                    when {
                        editMethod.name == "commit" -> false // Memory changed, disk reported failure.
                        result === editor -> proxy
                        else -> result
                    }
                }
            }
        } as android.content.SharedPreferences
        assertFalse(ClusterPrefs.saveProjectionAdasEnabled(failing, true))
        assertFalse(backing.getBoolean("projection_adas_hide", false))
    }

    @Before fun setup() {
        owner = ProjectionAdasCoordinator(app) { true }
        instance.set(null, owner)
        oldBinder = binderField.get(null); binderField.set(null, Binder())
        ClusterPrefs.setProjectionAdasEnabled(app, false)
        ClusterPrefs.saveProjectionAdasRecovery(app)
    }
    @After fun cleanup() {
        ProjectionAdasCoordinator.cancelForManualRecovery()
        instance.set(null, null); binderField.set(null, oldBinder)
        ProjectionCommandBus.currentSession()?.let(ProjectionCommandBus::endSession)
        idle()
    }
    private class PausedIo : ProjectionAdasPlan.Io {
        val frames = mutableListOf<String>()
        val commands = mutableListOf<Int>()
        var ack: (() -> Unit)? = null
        override fun now() = 0L
        override fun later(delay: Long, action: () -> Unit) { Handler(Looper.getMainLooper()).postDelayed(action, delay) }
        override fun valid() = true
        override fun journal(done: (Boolean) -> Unit) = done(true)
        override fun initialize(valid: () -> Boolean, done: (Boolean) -> Unit) = done(valid())
        override fun command(code: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
            if (valid()) commands += code
            done(valid())
        }
        override fun frames(frame: String, count: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
            ack = { if (valid()) repeat(count) { frames += frame }; done(valid()) }
        }
    }
    @Test fun `stop then destroy coalesces the in-flight recovery rather than cancelling it`() {
        val io = PausedIo(); field("plan").set(owner, ProjectionAdasPlan(io))
        field("sequenceRequested").setBoolean(owner, true)
        ProjectionAdasCoordinator.stop(app); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertTrue(field("restoring").getBoolean(owner))
        ProjectionAdasCoordinator.stop(app); idle()
        repeat(4) { io.ack!!() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        assertEquals(List(40) { ProjectionAdasPlan.RESTORE }, io.frames)
        assertEquals(listOf(12, 53), io.commands)
        assertFalse(field("restoring").getBoolean(owner))
        assertTrue(ClusterPrefs.hasProjectionAdasRecovery(app))
    }
    @Test fun `coalesced recovery callback cannot be stranded behind an earlier completion`() {
        field("restoring").setBoolean(owner, true)
        val results = mutableListOf<Boolean>()
        Handler(Looper.getMainLooper()).post { field("restoring").setBoolean(owner, false) }
        val caller = Thread { ProjectionAdasCoordinator.restore(app, results::add) }
        caller.start(); caller.join(2000)
        assertFalse(caller.isAlive)
        idle()
        assertEquals(listOf(false), results)
    }
    @Test fun `queued target cannot rearm masking after manual recovery invalidation`() {
        ProjectionCommandBus.beginSession()
        ProjectionAdasCoordinator.targetRequested(app, "com.maps", 9)
        ProjectionAdasCoordinator.cancelForManualRecovery()
        idle()
        assertNull(field("target").get(owner))
    }
    @Test fun `latest queued projection request wins without letting old target override it`() {
        ProjectionCommandBus.beginSession()
        ProjectionAdasCoordinator.targetRequested(app, "com.old", 9)
        ProjectionAdasCoordinator.targetRequested(app, "com.new", 10)
        idle()
        assertTrue(field("target").get(owner).toString().contains("com.new"))
    }
    @Test fun `queued old teardown cannot erase a new projection target`() {
        val session = ProjectionCommandBus.beginSession()
        ProjectionAdasCoordinator.targetRequested(app, "com.old", 9); idle()
        ProjectionAdasCoordinator.sessionEnded(session)
        val next = ProjectionCommandBus.beginSession()
        // Reproduce a new owner installing its target before the old queued teardown runs.
        val launch = ProjectionAdasCoordinator::class.java.getDeclaredMethod("launch",
            String::class.java, Int::class.javaPrimitiveType, ProjectionCommandSequencer.Session::class.java)
            .apply { isAccessible = true }
        launch.invoke(owner, "com.new", 10, next)
        val target = field("target").get(owner)
        idle()
        assertSame(target, field("target").get(owner))
    }
    @Test fun `disabled coordinator never restores a historical manual journal on unrelated teardown`() {
        val io = PausedIo(); field("plan").set(owner, ProjectionAdasPlan(io))
        ProjectionAdasCoordinator.stop(app); idle()
        assertNull(io.ack); assertTrue(io.commands.isEmpty())
    }
}
