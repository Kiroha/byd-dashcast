package com.byd.dashcast.ui.diag

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Binder
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w640dp-h320dp-land")
class AdasTargetDiagActivityTest {
    private val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
    private val version = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
    private var previous: Any? = null
    private var previousVersion: Any? = null
    @Before fun setup() { previous = binderField.get(null); previousVersion = version.get(null); version.set(null, "28") }
    @After fun restore() {
        AdasTargetDiagnostics.capture.cancel("Test finished")
        AdasLogDiagnostics.capture.cancel("Test finished")
        binderField.set(null, previous); version.set(null, previousVersion)
    }
    private fun <T : View> find(activity: AdasTargetDiagActivity, tag: String): T = activity.findViewById<View>(android.R.id.content).findViewWithTag(tag)
    private fun waitFor(check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!check() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue(check())
    }

    @Test fun `opening and resuming perform no reads and explicit snapshot requires parked acknowledgment`() {
        val calls = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_ADAS_TARGET_READ, code)
                calls.incrementAndGet(); data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); data.readInt()
                reply!!.writeNoException(); reply.writeInt(-10011); return true
            }
        }
        binderField.set(null, binder)
        val controller = Robolectric.buildActivity(AdasTargetDiagActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertEquals(0, calls.get())
            find<Button>(activity, "adas_read").performClick()
            assertEquals(0, calls.get())
            assertTrue(find<TextView>(activity, "adas_status").text.contains("Acknowledge"))
            find<CheckBox>(activity, "adas_parked").isChecked = true
            assertEquals(0, find<Spinner>(activity, "adas_variant").selectedItemPosition)
            find<Button>(activity, "adas_read").performClick()
            waitFor { !AdasTargetDiagnostics.capture.isBusy() }
            assertEquals(33, calls.get())
            controller.pause().resume()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(33, calls.get())
            assertTrue(find<TextView>(activity, "adas_raw").text.contains("-10011"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `hiding activity retires capture while one blocked read retains shared diagnostic gate`() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val calls = AtomicInteger()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls.incrementAndGet(); entered.countDown(); unblock.await()
                reply!!.writeNoException(); reply.writeInt(1); return true
            }
        })
        val controller = Robolectric.buildActivity(AdasTargetDiagActivity::class.java).setup()
        try {
            find<CheckBox>(controller.get(), "adas_parked").isChecked = true
            find<Button>(controller.get(), "adas_read").performClick()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertFalse(find<Button>(controller.get(), "adas_logs").isEnabled)
            controller.pause().stop().destroy()
            assertEquals("CANCELLED", AdasTargetDiagnostics.capture.snapshot()!!.outcome)
            assertTrue(AdasTargetDiagnostics.capture.isBusy())
            assertFalse(DiagActivity.claimAdasDiagnostic())
        } finally { unblock.countDown() }
        waitFor { !AdasTargetDiagnostics.capture.isBusy() }
        assertEquals(1, calls.get())
        assertTrue(DiagActivity.claimAdasDiagnostic()); DiagActivity.releaseAdasDiagnostic()
    }

    @Test fun `OEM log inspection is explicit acknowledged and historical across resume without raw reads`() {
        version.set(null, "30")
        val calls = AtomicInteger()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_ADAS_LOG_PROBE, code)
                calls.incrementAndGet(); reply!!.writeNoException()
                reply.writeString("schema=1; root=/data/logs/host/logs status=DENIED; fixture")
                return true
            }
        })
        val controller = Robolectric.buildActivity(AdasTargetDiagActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(0, calls.get())
            find<Button>(activity, "adas_logs").performClick(); assertEquals(0, calls.get())
            assertTrue(find<TextView>(activity, "adas_status").text.contains("Acknowledge"))
            find<CheckBox>(activity, "adas_parked").isChecked = true
            find<Button>(activity, "adas_logs").performClick()
            waitFor { !AdasLogDiagnostics.capture.isBusy() }
            controller.pause().resume(); org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(1, calls.get())
            assertTrue(find<TextView>(activity, "adas_log_result").text.contains("status=DENIED"))
            assertTrue(find<TextView>(activity, "adas_log_result").text.contains("not source/sensor time"))
        } finally { controller.pause().stop().destroy() }
    }

    @Suppress("DEPRECATION")
    @Test fun `short landscape layout keeps raw rows reachable at large font scales`() {
        val resources = RuntimeEnvironment.getApplication().resources
        val original = Configuration(resources.configuration)
        try {
            for (fontScale in listOf(1.3f, 2f)) {
                val config = Configuration(original).apply { this.fontScale = fontScale }
                resources.updateConfiguration(config, resources.displayMetrics)
                val controller = Robolectric.buildActivity(AdasTargetDiagActivity::class.java).setup()
                try {
                    val content = controller.get().findViewById<ViewGroup>(android.R.id.content)
                    val scroll = content.getChildAt(0) as ScrollView
                    val density = resources.displayMetrics.density
                    val width = (640 * density).toInt(); val height = (320 * density).toInt()
                    scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    scroll.layout(0, 0, width, height)
                    val raw = find<TextView>(controller.get(), "adas_raw")
                    assertTrue(raw.width > 0); assertTrue(raw.height > 0)
                    assertEquals(LinearLayout.VERTICAL, (raw.parent as LinearLayout).orientation)
                    assertTrue(scroll.getChildAt(0).height > scroll.height)
                    scroll.isSmoothScrollingEnabled = false
                    scroll.fullScroll(View.FOCUS_DOWN)
                    assertTrue(scroll.scrollY > 0)
                } finally { controller.pause().stop().destroy() }
            }
        } finally { resources.updateConfiguration(original, resources.displayMetrics) }
    }

    @Test fun `unknown and secondary Android display cannot submit manual reads`() {
        val calls = AtomicInteger()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { calls.incrementAndGet(); return false }
        })
        val controller = Robolectric.buildActivity(DisplayFixtureActivity::class.java).setup()
        try {
            val activity = controller.get()
            for (id in listOf(null, 1)) {
                activity.observedDisplay = id
                activity.onWindowFocusChanged(true)
                find<CheckBox>(activity, "adas_parked").isChecked = true
                val button = find<Button>(activity, "adas_read")
                assertFalse(button.isEnabled)
                assertFalse(find<Button>(activity, "adas_logs").isEnabled)
                button.performClick()
                find<Button>(activity, "adas_logs").performClick()
                assertEquals(0, calls.get())
                assertTrue(find<TextView>(activity, "adas_status").text.contains("default display required"))
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `idle schematic draw suppresses completed points after display migration without resume`() {
        val calls = AtomicInteger()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls.incrementAndGet(); reply!!.writeNoException(); reply.writeInt(1); return true
            }
        })
        val controller = Robolectric.buildActivity(DisplayFixtureActivity::class.java)
        controller.get().observedDisplay = 0
        controller.setup()
        try {
            val activity = controller.get()
            find<CheckBox>(activity, "adas_parked").isChecked = true
            find<Spinner>(activity, "adas_variant").setSelection(1)
            find<Button>(activity, "adas_read").performClick()
            waitFor { !AdasTargetDiagnostics.capture.isBusy() }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val plot = find<View>(activity, "adas_plot")
            val points = plot.javaClass.getDeclaredField("points").apply { isAccessible = true }
            assertEquals(4, (points.get(plot) as List<*>).size)
            activity.observedDisplay = 1
            plot.layout(0, 0, 320, 280)
            plot.draw(Canvas(Bitmap.createBitmap(320, 280, Bitmap.Config.ARGB_8888)))
            assertTrue((points.get(plot) as List<*>).isEmpty())
            assertTrue(plot.contentDescription.contains("disabled"))
            find<Button>(activity, "adas_read").performClick()
            assertEquals(33, calls.get())
            assertFalse(find<Button>(activity, "adas_read").isEnabled)
        } finally { controller.pause().stop().destroy() }
    }
}

class DisplayFixtureActivity : AdasTargetDiagActivity() {
    var observedDisplay: Int? = null
    override fun diagnosticDisplayId(): Int? = observedDisplay
}
