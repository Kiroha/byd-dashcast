package com.byd.dashcast.ui.settings

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.R
import com.byd.dashcast.data.apps.AppRepository
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.data.prefs.StartupAppsMode
import com.byd.dashcast.data.prefs.StartupAppsSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class StartupAppsDialogTest {
    class Host : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.AppTheme)
            super.onCreate(savedInstanceState)
        }
    }

    @Test fun `opening app shortcut and cancelling never changes central choice`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup()
        val host = activity.get()
        host.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ClusterPrefs.setStartupApps(host, StartupAppsSelection(StartupAppsMode.LAST_SESSION))
        val repository = AppRepository()
        try {
            var changed = false
            StartupAppsDialog.show(host, repository, "com.example.preselected") { changed = true }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(StartupAppsMode.APP.ordinal, dialog.listView.checkedItemPosition)
            assertEquals(StartupAppsMode.LAST_SESSION, ClusterPrefs.getStartupApps(host).mode)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertFalse(changed)
            assertEquals(StartupAppsMode.LAST_SESSION, ClusterPrefs.getStartupApps(host).mode)
        } finally { repository.shutdown(); activity.pause().stop().destroy() }
    }

    @Test fun `none is applied only after explicit confirmation and keeps projection switch`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup()
        val host = activity.get()
        host.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ClusterPrefs.setStartupApps(host, StartupAppsSelection(StartupAppsMode.LAST_SESSION))
        ClusterPrefs.setBootProjectionEnabled(host, true)
        val repository = AppRepository()
        try {
            var changed = false
            StartupAppsDialog.show(host, repository) { changed = true }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.listView.performItemClick(null, StartupAppsMode.NONE.ordinal, 0)
            assertEquals(StartupAppsMode.LAST_SESSION, ClusterPrefs.getStartupApps(host).mode)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(changed)
            assertEquals(StartupAppsMode.NONE, ClusterPrefs.getStartupApps(host).mode)
            assertTrue(ClusterPrefs.isBootProjectionEnabled(host))
        } finally { repository.shutdown(); activity.pause().stop().destroy() }
    }

    @Test fun `unavailable chosen source is visible without silently selecting another`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup()
        val host = activity.get()
        ClusterPrefs.setStartupApps(host, StartupAppsSelection(StartupAppsMode.LAYOUT, "deleted-id"))
        assertEquals(host.getString(R.string.startup_unavailable_fmt, "deleted-id"), StartupAppsDialog.summary(host))
        assertEquals(StartupAppsMode.LAYOUT, ClusterPrefs.getStartupApps(host).mode)
        activity.pause().stop().destroy()
    }
}
