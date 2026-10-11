package com.byd.dashcast.media

import android.app.Application
import android.os.Looper
import android.widget.CheckBox
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

    private fun start() {
        (field("parked") as CheckBox).isChecked = true
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun field(name: String): Any = bench.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(bench)!!
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
}
