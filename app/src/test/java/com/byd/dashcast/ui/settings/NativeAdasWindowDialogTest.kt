package com.byd.dashcast.ui.settings

import android.app.Application
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.R
import com.byd.dashcast.cluster.NativeAdasWindowControl
import com.byd.dashcast.cluster.NativeAdasWindowControlTest.ManualExecutor
import com.byd.dashcast.data.prefs.ClusterPrefs
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
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NativeAdasWindowDialogTest {
    private lateinit var host: ActivityController<AppCompatActivity>
    private lateinit var dialog: NativeAdasWindowDialog
    private val probe = ManualExecutor()
    private val dispatch = ManualExecutor()
    private val frames = mutableListOf<String>()
    private var saved: Int? = null
    private val control = NativeAdasWindowControl(object : NativeAdasWindowControl.Device {
        override fun available() = true
        override fun originalValue() = 1
        override fun send(frame: String) { frames += frame }
    }, object : NativeAdasWindowControl.Recovery {
        override fun value() = saved
        override fun saveOriginal(): Boolean { saved = 1; return true }
    }, probe, dispatch)

    @Before fun setup() {
        host = Robolectric.buildActivity(AppCompatActivity::class.java)
        host.get().setTheme(R.style.AppTheme); host.setup()
        dialog = NativeAdasWindowDialog(host.get(), control)
        dialog.show(); idle()
    }
    @After fun cleanup() { dialog.close(); host.pause().stop().destroy() }
    private fun field(name: String): Any = NativeAdasWindowDialog::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(dialog)!!
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun startHide() {
        (field("parked") as CheckBox).isChecked = true
        (field("hide") as Button).performClick()
    }

    @Test fun `opening requires fresh parked acknowledgement and emits no command`() {
        assertFalse((field("hide") as Button).isEnabled)
        assertTrue((field("restore") as Button).isEnabled)
        assertTrue(probe.tasks.isEmpty()); assertTrue(dispatch.tasks.isEmpty()); assertTrue(frames.isEmpty())
        assertTrue((field("hide") as Button).minHeight >= (64 * host.get().resources.displayMetrics.density).toInt())
    }
    @Test fun `restore remains accessible without parked acknowledgement or live SDK reader`() {
        saved = 1
        (field("restore") as Button).performClick(); dispatch.run(); idle()
        assertEquals(listOf(NativeAdasWindowControl.RESTORE_FRAME), frames)
        assertTrue(probe.tasks.isEmpty())
        assertEquals(host.get().getString(R.string.native_adas_restore_sent), (field("status") as TextView).text)
    }
    @Test fun `hide callback consumes acknowledgement and says sent rather than visible`() {
        startHide(); probe.run(); idle()
        assertEquals(listOf(NativeAdasWindowControl.HIDE_FRAME), frames)
        assertFalse((field("parked") as CheckBox).isChecked)
        assertFalse((field("hide") as Button).isEnabled)
        assertEquals(host.get().getString(R.string.native_adas_hide_sent), (field("status") as TextView).text)
    }
    @Test fun `timeout cancels late hide and keeps recovery action available`() {
        saved = 1; startHide()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        assertFalse((field("parked") as CheckBox).isChecked)
        assertFalse((field("hide") as Button).isEnabled)
        (field("restore") as Button).performClick(); dispatch.run(); idle()
        probe.run(); idle()
        assertEquals(listOf(NativeAdasWindowControl.RESTORE_FRAME), frames)
        assertEquals(host.get().getString(R.string.native_adas_restore_sent), (field("status") as TextView).text)
    }
    @Test fun `closing prevents queued hide while retaining later manual restoration`() {
        saved = 1; startHide(); dialog.close(); probe.run(); idle()
        assertTrue(frames.isEmpty())
        control.request(NativeAdasWindowControl.Action.RESTORE) { }; dispatch.run()
        assertEquals(listOf(NativeAdasWindowControl.RESTORE_FRAME), frames)
    }
    @Test fun `restore with no snapshot explains unavailability without querying or writing`() {
        (field("restore") as Button).performClick(); dispatch.run(); idle()
        assertTrue(frames.isEmpty()); assertTrue(probe.tasks.isEmpty())
        assertEquals(host.get().getString(R.string.native_adas_recovery_missing), (field("status") as TextView).text)
    }
    @Test fun `settings opens and closes the manual dialog without changing geometry preference`() {
        ClusterPrefs.setAdasWindowFixEnabled(host.get(), true)
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        settings.get().findViewById<Button>(R.id.btn_native_adas_panel).performClick(); idle()
        val shown = ShadowDialog.getLatestDialog()
        assertTrue(shown.isShowing)
        assertTrue(ClusterPrefs.isAdasWindowFixEnabled(host.get()))
        settings.pause().stop().destroy(); idle()
        assertFalse(shown.isShowing)
        assertTrue(ClusterPrefs.isAdasWindowFixEnabled(host.get()))
    }
}
