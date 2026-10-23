package com.byd.dashcast.cluster

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.*
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.R
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.ui.settings.NativeAdasWindowDialog
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
import java.time.Duration
import java.util.Collections

/** Exercise the real coordinator, pinned IPC and ordered transport rather than a plan-only stub. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ProjectionAdasRemaskTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val frames = mutableListOf<String>()
    private val commands = Collections.synchronizedList(mutableListOf<Int>())
    private val commandTimes = Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
    @Volatile private var reads = 0
    @Volatile private var resumed = true
    @Volatile private var typedPower = 2
    @Volatile private var rawPower = 3
    private var supported = true
    private var holdNextRead = false
    private var heldRead: Runnable? = null
    private val shellCommands = mutableListOf<String>()
    private var oldBinder: Any? = null
    private var oldVersion: Any? = null
    private var dialog: NativeAdasWindowDialog? = null
    private val instance = ProjectionAdasCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
    private val versionField = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
    private lateinit var owner: ProjectionAdasCoordinator
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun sendOrderedBroadcast(intent: Intent, permission: String?, receiver: BroadcastReceiver?,
            scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {
            frames += intent.getStringExtra("wholeFrame")!!
            receiver!!.onReceive(this, intent)
        }
    }
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
            reply!!.writeNoException()
            when (code) {
                ProxyDaemonContract.TXN_EXEC -> {
                    val command = data.readString()!!
                    shellCommands += command
                    reply.writeInt(0)
                    if (command == "dumpsys activity activities") {
                        reads++
                        reply.writeString(if (resumed) "Display #9\n mResumedActivity: ActivityRecord{a u0 com.maps/.Main t1}\n"
                            else "Display #9\n mResumedActivity: ActivityRecord{a u0 com.other/.Main t1}\n")
                    } else {
                        Regex("i32 1000 i32 (\\d+)").find(command)?.groupValues?.get(1)?.toInt()?.let {
                            commands += it
                            commandTimes += it to SystemClock.uptimeMillis()
                        }
                        reply.writeString("DASHCAST_ADAS_OK\n")
                    }
                }
                ProxyDaemonContract.TXN_ADAS_BODYWORK_READ -> reply.writeInt(when (data.readInt()) {
                    0 -> typedPower
                    0x12d0002a -> rawPower
                    else -> 102
                })
                else -> fail("Unexpected IPC: legacy instrument cache must not be consulted")
            }
            return true
        }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) { advance(10); Thread.sleep(2) }
        idle()
        assertTrue(message, condition())
    }
    @Before fun setup() {
        oldBinder = binderField.get(null); oldVersion = versionField.get(null)
        binderField.set(null, binder); versionField.set(null, "27")
        owner = ProjectionAdasCoordinator(context) { supported }
        // Keep fake Binder work deterministic under virtual time; transport concurrency has separate tests.
        val transport = ProjectionAdasCoordinator::class.java.getDeclaredField("transport")
            .apply { isAccessible = true }.get(owner)
        for (lane in listOf("reads", "writes", "recovery")) {
            ProjectionAdasTransport::class.java.getDeclaredField(lane).apply { isAccessible = true }
                .set(transport, object : java.util.concurrent.ThreadPoolExecutor(0, 1, 30,
                    java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(1)) {
                    override fun execute(command: Runnable) {
                        if (lane == "reads" && holdNextRead) { holdNextRead = false; heldRead = command }
                        else command.run()
                    }
                })
        }
        instance.set(null, owner)
        ClusterPrefs.setProjectionAdasEnabled(app, true)
        ClusterPrefs.saveProjectionAdasRecovery(app)
    }
    @After fun cleanup() {
        dialog?.close()
        ProjectionAdasCoordinator.cancelForManualRecovery()
        instance.set(null, null)
        ProjectionCommandBus.currentSession()?.let(ProjectionCommandBus::endSession)
        binderField.set(null, oldBinder); versionField.set(null, oldVersion)
        idle()
    }
    private fun launch() {
        ProjectionCommandBus.beginSession()
        ProjectionAdasCoordinator.targetRequested(context, "com.maps", 9)
        ProjectionAdasCoordinator.preferenceChanged(context)
        await("projection proof starts the initial plan") { commands.contains(13) }
        advance(1000)
        await("the complete first pass is delivered") { frames.size == 263 }
    }
    private fun restore() {
        var result: Boolean? = null
        ProjectionAdasCoordinator.restore(context) { result = it }
        await("restore reaches command 12") { commands.lastOrNull() == 12 }
        advance(1200)
        await("full restore completes") { result != null }
        assertEquals(true, result)
        assertEquals(listOf(12, 53), commands.takeLast(2))
    }
    @Test fun `same projection launch restore remask uses fresh proof and full sequence`() {
        launch(); restore()
        val restoredCount = frames.size
        assertEquals(263 + 40, restoredCount)
        ProjectionAdasCoordinator.targetRequested(context, "com.maps", 9)
        advance(30000); idle()
        assertEquals("old polls and confirmation must not re-hide", restoredCount, frames.size)
        val oldReads = reads
        val results = mutableListOf<NativeAdasWindowControl.Result>()
        val request = ProjectionAdasCoordinator.requestHide(context, results::add)
        await("remask obtains a new read and starts a new plan") { reads > oldReads && commands.lastOrNull() == 13 }
        assertTrue("arming is not broadcast delivery", results.isEmpty())
        advance(1000)
        await("remask emits all 263 frames") { frames.size == restoredCount + 263 }
        assertEquals(listOf(NativeAdasWindowControl.Result.HIDE_SENT), results)
        request.close()
        advance(5000)
        await("closing a completed request leaves its bounded confirmation alive") { commands.lastOrNull() == 13 }
        assertTrue(ClusterPrefs.isProjectionAdasEnabled(context))
    }
    @Test fun `the settings hide button routes restored projection through Electro instead of the SDK cache`() {
        launch()
        val host = Robolectric.buildActivity(AppCompatActivity::class.java)
        host.get().setTheme(R.style.AppTheme); host.setup()
        try {
            val ui = NativeAdasWindowDialog(host.get()).also { dialog = it; it.show() }
            fun field(name: String) = NativeAdasWindowDialog::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(ui)
            (field("fullRestore") as Button).performClick()
            await("settings full restore reaches command 12") { commands.lastOrNull() == 12 }
            advance(1200)
            await("settings reports full restoration delivery") {
                (field("status") as TextView).text == host.get().getString(R.string.native_adas_restore_sent)
            }
            (field("parked") as CheckBox).isChecked = true
            (field("hide") as Button).performClick()
            assertTrue((field("fullRestore") as Button).isEnabled)
            await("button requests full plan") { commands.lastOrNull() == 13 }
            advance(1000)
            await("button receives actual broadcast delivery") {
                (field("status") as TextView).text == host.get().getString(R.string.native_adas_hide_sent)
            }
            assertEquals(263 * 2 + 40, frames.size)
            assertFalse((field("parked") as CheckBox).isChecked)
        } finally { dialog?.close(); host.pause().stop().destroy() }
    }
    @Test fun `remask rejects fresh proof failures without any new mutation`() {
        launch(); restore()
        for (failure in 0..3) {
            resumed = failure != 0; typedPower = if (failure == 1) 3 else 2
            rawPower = if (failure == 2) 255 else 3; supported = failure != 3
            val beforeFrames = frames.size; val beforeCommands = commands.size
            val results = mutableListOf<NativeAdasWindowControl.Result>()
            ProjectionAdasCoordinator.requestHide(context, results::add)
            await("invalid proof returns an actionable refusal") { results.isNotEmpty() }
            assertEquals(listOf(NativeAdasWindowControl.Result.UNAVAILABLE), results)
            advance(20000)
            assertEquals(beforeFrames, frames.size); assertEquals(beforeCommands, commands.size)
        }
    }
    @Test fun `a retired session or real projection stop cannot reuse the remembered target`() {
        launch(); restore()
        ProjectionAdasCoordinator.stop(context); idle()
        val before = frames.size
        ProjectionCommandBus.beginSession()
        val results = mutableListOf<NativeAdasWindowControl.Result>()
        ProjectionAdasCoordinator.requestHide(context, results::add); idle()
        assertEquals(listOf(NativeAdasWindowControl.Result.UNAVAILABLE), results)
        assertEquals(before, frames.size)
    }
    @Test fun `cancel before first delivery prevents confirmation and hide`() {
        launch(); restore()
        val before = frames.size
        val results = mutableListOf<NativeAdasWindowControl.Result>()
        val request = ProjectionAdasCoordinator.requestHide(context, results::add)
        await("remask starts") { commands.lastOrNull() == 13 }
        request.close(); advance(30000)
        assertTrue(results.isEmpty()); assertEquals(before, frames.size)
    }
    @Test fun `stop after full manual restore still runs a complete restore after teardown and quiet time`() {
        launch(); restore()
        val before = frames.size
        val completeStop = ProjectionAdasCoordinator.prepareStop(context)
        ProjectionAdasCoordinator.stop(context)
        ProjectionCommandBus.currentSession()?.let(ProjectionCommandBus::endSession)
        advance(30000)
        assertEquals("no restore can interleave with unfinished native teardown", before, frames.size)
        completeStop.run(); completeStop.run()
        advance(999)
        assertEquals(before, frames.size)
        advance(1_001)
        await("stop sends command 12 after all forty restore frames") { commands.lastOrNull() == 12 }
        assertEquals(List(40) { ProjectionAdasPlan.RESTORE }, frames.drop(before))
        ProjectionAdasCoordinator.stop(context)
        advance(1200)
        await("repeated service teardown cannot cancel command 53") { commands.lastOrNull() == 53 }
        assertEquals(before + 40, frames.size)
        assertEquals(listOf(12, 53), commands.takeLast(2))
    }
    @Test fun `stop revokes a remask before its first delivery and restores the full pane`() {
        launch(); restore()
        val before = frames.size
        val result = mutableListOf<NativeAdasWindowControl.Result>()
        ProjectionAdasCoordinator.requestHide(context, result::add)
        await("remask initialization") { commands.lastOrNull() == 13 }
        val completeStop = ProjectionAdasCoordinator.prepareStop(context)
        completeStop.run()
        advance(2000)
        await("stop reaches its recovery debug command") { commands.lastOrNull() == 12 }
        advance(1200)
        await("stop finishes restoration") { commands.lastOrNull() == 53 }
        advance(30000)
        assertEquals(listOf(NativeAdasWindowControl.Result.CANCELLED), result)
        assertEquals(List(40) { ProjectionAdasPlan.RESTORE }, frames.drop(before))
    }
    @Test fun `old stop completion and stale helper cannot restore a newer projection owner`() {
        launch()
        val oldSession = ProjectionCommandBus.currentSession()!!
        val oldStop = ProjectionAdasCoordinator.prepareStop(context, oldSession)
        ProjectionCommandBus.beginSession()
        val before = frames.size
        oldStop.run()
        ProjectionAdasCoordinator.prepareStop(context, oldSession).run()
        advance(30000)
        assertEquals(before, frames.size)
        assertFalse(commands.contains(12)); assertFalse(commands.contains(53))
    }
    @Test fun `an unrelated stop cannot replay a journal from an earlier process or session`() {
        assertTrue(ClusterPrefs.hasProjectionAdasRecovery(context))
        ProjectionAdasCoordinator.prepareStop(context).run(); advance(30000)
        assertTrue(frames.isEmpty()); assertTrue(commands.isEmpty())
    }
    @Test fun `manual full restoration joins a deferred stop instead of racing native close commands`() {
        launch()
        val before = frames.size
        val completeStop = ProjectionAdasCoordinator.prepareStop(context)
        val results = mutableListOf<Boolean>()
        ProjectionAdasCoordinator.restore(context, results::add)
        assertFalse("one-shot manual actions cannot preempt the pending full recovery",
            ProjectionAdasCoordinator.prepareManualRequest(NativeAdasWindowControl.Action.HIDE))
        advance(30000)
        assertEquals(before, frames.size); assertTrue(results.isEmpty())
        completeStop.run(); advance(2000)
        await("deferred recovery reaches 12") { commands.lastOrNull() == 12 }
        advance(1200)
        await("joined manual result is completed once") { results.isNotEmpty() }
        assertEquals(listOf(true), results)
        assertEquals(List(40) { ProjectionAdasPlan.RESTORE }, frames.drop(before))
        assertEquals(listOf(12, 53), commands.takeLast(2))
    }
    @Test fun `rapid remask waits for the OEM debug quiet boundary after restore`() {
        launch(); restore()
        val before = commands.size
        val request = ProjectionAdasCoordinator.requestHide(context) {}
        idle(); advance(900)
        assertEquals(before, commands.size)
        advance(100)
        await("remask begins after the admission quiet guard") { commands.lastOrNull() == 13 }
        assertTrue(commandTimes.last { it.first == 13 }.second -
            commandTimes.last { it.first == 53 }.second >= 1000)
        request.close()
    }
    @Test fun `a genuinely new projection owner can automatically mask after the previous manual restore`() {
        launch(); restore()
        val before = frames.size
        ProjectionCommandBus.beginSession()
        ProjectionAdasCoordinator.targetRequested(context, "com.maps", 9)
        await("new owner starts its own plan") { commands.lastOrNull() == 13 }
        advance(1000)
        await("new owner sends its complete first pass") { frames.size == before + 263 }
    }
    @Test fun `new owner stop recovers outstanding in-process masking when its own launch never verifies`() {
        launch()
        val before = frames.size
        ProjectionCommandBus.beginSession()
        resumed = false
        ProjectionAdasCoordinator.targetRequested(context, "com.maps", 9)
        await("replacement owner has attempted fresh proof") { reads >= 2 }
        val completeStop = ProjectionAdasCoordinator.prepareStop(context)
        completeStop.run(); advance(2000)
        await("replacement stop recovers known outstanding mutation") { commands.lastOrNull() == 12 }
        advance(1200)
        await("full debt recovery completes") { commands.lastOrNull() == 53 }
        assertEquals(List(40) { ProjectionAdasPlan.RESTORE }, frames.drop(before))
    }
    @Test fun `saved one-frame recovery stays available while native stop completion is blocked`() {
        launch()
        val before = frames.size
        val completeStop = ProjectionAdasCoordinator.prepareStop(context)
        val control = NativeAdasWindowControl(object : NativeAdasWindowControl.Device {
            override fun available() = true
            override fun originalValue(): Int = error("restoration must not query the SDK cache")
            override fun send(frame: String) { frames += frame }
        }, object : NativeAdasWindowControl.Recovery {
            override fun value() = 1
            override fun saveOriginal(): Boolean = error("restoration must not capture a new original")
        }, java.util.concurrent.Executor { it.run() }, java.util.concurrent.Executor { it.run() })
        var manual: NativeAdasWindowControl.Result? = null
        control.request(NativeAdasWindowControl.Action.RESTORE) { manual = it }
        assertEquals(NativeAdasWindowControl.Result.RESTORE_SENT, manual)
        assertEquals(listOf(ProjectionAdasPlan.RESTORE), frames.drop(before))
        advance(30000)
        assertEquals(before + 1, frames.size)
        completeStop.run(); advance(2000)
        await("late native completion still runs full recovery") { commands.lastOrNull() == 12 }
        advance(1200)
        await("fallback did not invalidate the full recovery's final command") { commands.lastOrNull() == 53 }
        assertEquals(List(41) { ProjectionAdasPlan.RESTORE }, frames.drop(before))
    }
    @Test fun `a bounded slow refresh after the poll gap does not restart an intact masking pass`() {
        launch()
        val before = commands.count { it == 13 }
        holdNextRead = true
        advance(1_000)
        assertNotNull(heldRead)
        advance(2_499)
        heldRead!!.run(); idle()
        assertEquals(before, commands.count { it == 13 })
        advance(500)
        assertEquals(before, commands.count { it == 13 })
    }
    @Test fun `a timed out refresh cannot be revived by its late success`() {
        launch()
        holdNextRead = true
        advance(1_000)
        assertNotNull(heldRead)
        advance(2_500)
        val beforeReads = reads
        val beforeFrames = frames.size
        val beforeCommands = commands.size
        heldRead!!.run(); idle()
        assertEquals(beforeReads, reads)
        assertEquals(beforeFrames, frames.size)
        assertEquals(beforeCommands, commands.size)
    }
    @Test fun `the proof lease closes at four seconds even before a delayed expiry callback`() {
        launch()
        val validity = ProjectionAdasCoordinator::class.java.getDeclaredField("plan")
            .apply { isAccessible = true }.get(owner).let { plan ->
                ProjectionAdasPlan::class.java.getDeclaredField("io").apply { isAccessible = true }
                    .get(plan) as ProjectionAdasPlan.Io
            }
        val proofAt = ProjectionAdasCoordinator::class.java.getDeclaredField("proofAt")
            .apply { isAccessible = true }
        proofAt.setLong(owner, SystemClock.uptimeMillis() - 3_999)
        assertTrue(validity.valid())
        proofAt.setLong(owner, SystemClock.uptimeMillis() - 4_000)
        assertFalse(validity.valid())
        proofAt.setLong(owner, SystemClock.uptimeMillis() - 4_001)
        assertFalse(validity.valid())
    }
    @Test fun `new projection owner during restore service readiness prevents every old restore write`() {
        launch()
        val before = frames.size
        var restored: Boolean? = null
        ProjectionAdasCoordinator.restore(context) { restored = it }
        idle()
        assertTrue(shellCommands.last().contains("am startservice"))
        assertEquals(before, frames.size)
        ProjectionCommandBus.beginSession()
        advance(2_200)
        assertEquals(false, restored)
        assertEquals(before, frames.size)
        assertFalse(commands.contains(12)); assertFalse(commands.contains(53))
    }
    @Test fun `full restore cancels confirmation and ignores an outstanding proof refresh`() {
        launch()
        holdNextRead = true
        advance(1_000)
        assertNotNull(heldRead)
        val beforeReads = reads
        restore()
        val beforeFrames = frames.size
        val beforeCommands = commands.size
        heldRead!!.run(); advance(30_000)
        assertEquals(beforeReads, reads)
        assertEquals(beforeFrames, frames.size)
        assertEquals(beforeCommands, commands.size)
    }

    @Test fun `late old proof work cannot renew a replacement projection owner`() {
        launch()
        holdNextRead = true
        advance(1_000)
        assertNotNull(heldRead)
        val late = heldRead!!
        ProjectionCommandBus.beginSession()
        ProjectionAdasCoordinator.targetRequested(context, "com.maps", 9)
        await("replacement obtains its own proof") { commands.count { it == 13 } == 2 }
        val beforeReads = reads
        val beforeCommands = commands.size
        late.run(); idle()
        assertEquals(beforeReads, reads)
        assertEquals(beforeCommands, commands.size)
    }
    @Test fun `stop restores independently while an earlier proof refresh is outstanding`() {
        launch()
        holdNextRead = true
        advance(1_000)
        assertNotNull(heldRead)
        val beforeReads = reads
        val before = frames.size
        ProjectionAdasCoordinator.prepareStop(context).run()
        advance(2_000)
        await("stop initialized the service before its restore frames") { commands.lastOrNull() == 12 }
        advance(1_200)
        await("stop recovery completes despite the old refresh") { commands.lastOrNull() == 53 }
        heldRead!!.run(); advance(30_000)
        assertEquals(beforeReads, reads)
        assertEquals(List(40) { ProjectionAdasPlan.RESTORE }, frames.drop(before))
        assertEquals(listOf(12, 53), commands.takeLast(2))
    }
    @Test fun `negative resumed evidence cancels the outstanding automatic confirmation`() {
        launch()
        val before = frames.size
        resumed = false
        advance(30_000)
        assertEquals(before, frames.size)
        assertEquals(listOf(13), commands)
    }

}
