package com.byd.dashcast.media

import android.app.Application
import android.os.Looper
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MediaBenchDialogTest {
    private lateinit var activityController: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity
    private lateinit var bench: MediaBenchDialog
    private lateinit var dialog: AlertDialog
    private val scheduler = ManualMediaScheduler()
    private val transport = FakeMediaTransport()
    private val writer = ClusterMediaWriter(transport, scheduler, { scheduler.now })
    private val ownership = MediaOutputOwnership { writer }
    private var automatic = false

    @Before fun setup() {
        activityController = Robolectric.buildActivity(AppCompatActivity::class.java)
        activity = activityController.get()
        activity.setTheme(R.style.AppTheme)
        activityController.setup()
        bench = MediaBenchDialog(activity, ownership) { automatic }
        bench.show()
        idle() // Android delivers Dialog.OnShowListener through the main message queue.
        dialog = field("dialog") as AlertDialog
    }
    @After fun cleanup() {
        bench.close()
        scheduler.advance(1000)
        activityController.pause().stop().destroy()
    }

    @Test fun `without parked acknowledgement no acquisition or SDK calls are possible`() {
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        scheduler.advance(1000)
        assertTrue(transport.writes.isEmpty())
        assertTrue(ownership.acquire(Any()))
    }

    @Test fun `six second deadline releases even when writer callback never arrives`() {
        start()
        assertFalse(ownership.acquire(Any()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(ownership.acquire(Any()))
        scheduler.advance(10000)
        // The blocked/not-started update was invalidated before its first SDK operation.
        assertTrue(transport.writes.isEmpty())
    }

    @Test fun `failed run deadline never cancels a later acknowledged run`() {
        transport.rejectSource = true
        start(); scheduler.advance(50); idle()
        scheduler.advance(); idle() // Process the no-acquisition cleanup acknowledgement.
        val failure = (field("status") as TextView).text.toString()
        assertEquals(activity.getString(R.string.media_bench_unavailable), failure)
        assertEquals(activity.getString(R.string.media_bench_nothing), (field("cleanupStatus") as TextView).text.toString())
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        transport.rejectSource = false
        start(); scheduler.advance(50); idle()
        assertEquals(activity.getString(R.string.media_bench_accepted), (field("status") as TextView).text.toString())
        // Pass the OLD six-second deadline while the second run is only four seconds old.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4100))
        assertFalse(ownership.acquire(Any()))
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(ownership.acquire(Any()))
    }

    @Test fun `dismissal cancels a partial acquisition before title or playing state`() {
        start(); scheduler.advance()
        dialog.cancel()
        idle()
        scheduler.advance(1000)
        assertEquals(listOf("source:11", "text:    ", "state:2"), transport.writes)
        assertTrue(ownership.acquire(Any()))
    }

    @Test fun `automatic output rejects bench without changing its owner`() {
        automatic = true
        start(); scheduler.advance(1000)
        assertEquals(activity.getString(R.string.media_bench_busy), (field("status") as TextView).text.toString())
        assertTrue(transport.writes.isEmpty())
    }

    @Test fun `reference comparison preserves the existing artist and title payload`() {
        start(); scheduler.advance(50); idle()
        assertEquals(listOf("source:11", "text:  DashCast - DashCast test  ", "state:1"), transport.writes)
    }

    @Test fun `short comparison sends title only through the unchanged music properties`() {
        select(1)
        start(); scheduler.advance(50); idle()
        assertEquals(listOf("source:11", "text:  DashCast  ", "state:1"), transport.writes)
    }

    @Test fun `minimal comparison sends two letters through the unchanged music properties`() {
        select(2)
        start(); scheduler.advance(50); idle()
        assertEquals(listOf("source:11", "text:  DC  ", "state:1"), transport.writes)
    }

    @Test fun `running comparison locks its choices and captures its payload before async output`() {
        select(1)
        start()
        assertTrue(buttons().all { !it.isEnabled })
        // Even an internal/programmatic selection change cannot rewrite this pending run.
        select(2)
        scheduler.advance(50); idle()
        assertEquals("text:  DashCast  ", transport.writes[1])
    }

    @Test fun `cleanup acknowledgement unlocks choices and requires parked consent for each comparison`() {
        select(1)
        start(); scheduler.advance(50); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(buttons().all { !it.isEnabled })
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        scheduler.advance(); idle()
        assertTrue(buttons().all { it.isEnabled })
        assertFalse((field("parked") as CheckBox).isChecked)
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        select(2)
        start(); scheduler.advance(50); idle()
        assertEquals("text:  DC  ", transport.writes[transport.writes.lastIndex - 1])
    }

    @Test fun `closing a selected comparison before output invalidates every queued write`() {
        select(2)
        start()
        bench.close()
        scheduler.advance(1000); idle()
        assertTrue(transport.writes.isEmpty())
        assertTrue(ownership.acquire(Any()))
    }

    @Test fun `missing cleanup acknowledgement keeps comparison controls disabled`() {
        select(2)
        start(); scheduler.advance(50); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        // The release is queued but the writer/cleanup callback does not run.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(buttons().all { !it.isEnabled })
        assertFalse((field("parked") as CheckBox).isEnabled)
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    private fun start() {
        (field("parked") as CheckBox).isChecked = true
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun field(name: String): Any = bench.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(bench)!!
    private fun buttons(): List<RadioButton> {
        val modes = field("modes") as RadioGroup
        return (0 until modes.childCount).map { modes.getChildAt(it) as RadioButton }
    }
    private fun select(index: Int) { (field("modes") as RadioGroup).check(buttons()[index].id) }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
}
