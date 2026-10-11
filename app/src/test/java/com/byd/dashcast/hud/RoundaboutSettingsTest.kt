package com.byd.dashcast.hud

import android.app.Application
import android.widget.RadioButton
import android.widget.RadioGroup
import com.byd.dashcast.R
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.data.prefs.RoundaboutTrafficSide
import com.byd.dashcast.ui.settings.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class RoundaboutSettingsTest {
    @Test fun `settings persist each selection immediately and restore on reopening`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ClusterPrefs.PREFS_NAME, 0).edit().clear().commit()
        for ((side, id) in listOf(RoundaboutTrafficSide.SOURCE to R.id.rb_roundabout_source,
            RoundaboutTrafficSide.RIGHT to R.id.rb_roundabout_right,
            RoundaboutTrafficSide.LEFT to R.id.rb_roundabout_left)) {
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            try {
                val activity = controller.get()
                val group = activity.findViewById<RadioGroup>(R.id.rg_roundabout_traffic)
                assertEquals(3, group.childCount)
                group.check(id)
                assertEquals(side, ClusterPrefs.getRoundaboutTrafficSide(context))
                for (index in 0 until group.childCount) {
                    val option = group.getChildAt(index) as RadioButton
                    assertTrue(option.text.isNotBlank())
                    assertTrue(option.minimumHeight >= 64 * activity.resources.displayMetrics.density)
                }
            } finally { controller.pause().stop().destroy() }
            val reopened = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            try { assertEquals(id, reopened.get().findViewById<RadioGroup>(R.id.rg_roundabout_traffic).checkedRadioButtonId) }
            finally { reopened.pause().stop().destroy() }
        }
    }
}
