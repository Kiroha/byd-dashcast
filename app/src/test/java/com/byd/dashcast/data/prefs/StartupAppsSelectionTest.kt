package com.byd.dashcast.data.prefs

import android.app.Application
import android.content.Context
import com.byd.dashcast.fission.FissionOrchestrator
import com.byd.dashcast.fission.LayoutPrefs
import com.byd.dashcast.fission.LayoutPreset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class StartupAppsSelectionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        LayoutPrefs.save(context, emptyList())
    }

    @Test fun `legacy app and favorite never enable boot without prior permission`() {
        prefs.edit().putString("auto_launch_pkg", "com.example.legacy")
            .putBoolean("fission_auto_layout", true).commit()
        assertEquals(StartupAppsSelection(StartupAppsMode.NONE), ClusterPrefs.getStartupApps(context))
        assertFalse(FissionOrchestrator.isAutoStartRequested(context))
        // A dormant legacy key cannot revive a choice after migration.
        prefs.edit().putBoolean("boot_auto_start_enabled", true).commit()
        assertEquals(StartupAppsMode.NONE, ClusterPrefs.getStartupApps(context).mode)
    }

    @Test fun `legacy restore migration always chooses last session instead of fixed fallback`() {
        prefs.edit().putBoolean("boot_auto_start_enabled", true)
            .putString("auto_launch_pkg", "com.example.legacy").putBoolean("fission_auto_layout", true).commit()
        assertEquals(StartupAppsSelection(StartupAppsMode.LAST_SESSION), ClusterPrefs.getStartupApps(context))
        assertFalse(FissionOrchestrator.isAutoStartRequested(context))
    }

    @Test fun `one selected source persists independently of projection and snapshot`() {
        ProjectionSessionPrefs.rememberClassic(context, "com.example.previous", null)
        val choices = listOf(StartupAppsSelection(StartupAppsMode.NONE), StartupAppsSelection(StartupAppsMode.LAST_SESSION),
            StartupAppsSelection(StartupAppsMode.APP, "com.example.chosen"), StartupAppsSelection(StartupAppsMode.LAYOUT, "exact-layout"))
        for (projection in listOf(false, true)) for (choice in choices) {
            ClusterPrefs.setBootProjectionEnabled(context, projection)
            ClusterPrefs.setStartupApps(context, choice)
            assertEquals(choice, ClusterPrefs.getStartupApps(context))
            assertEquals(projection, ClusterPrefs.isBootProjectionEnabled(context))
            assertEquals(projection || choice.mode != StartupAppsMode.NONE, ClusterPrefs.isAnyBootProjectionEnabled(context))
            assertEquals("com.example.previous", ProjectionSessionPrefs.read(context)!!.apps.single().packageName)
        }
    }

    @Test fun `fixed app overrides a saved layout without selecting a second owner`() {
        val layout = LayoutPreset("Previous").apply {
            slots.add(LayoutPreset.SlotDef("App", 0, 0, 1920, 720).apply { packageName = "com.example.previous" })
        }
        ProjectionSessionPrefs.rememberLayout(context, layout, setOf("com.example.previous"))
        ClusterPrefs.setStartupApps(context, StartupAppsSelection(StartupAppsMode.APP, "com.example.chosen"))
        assertFalse(FissionOrchestrator.isAutoStartRequested(context))
        ClusterPrefs.setStartupApps(context, StartupAppsSelection(StartupAppsMode.LAST_SESSION))
        assertTrue(FissionOrchestrator.isAutoStartRequested(context))
    }

    @Test fun `deleted selected layout remains unavailable instead of becoming the favorite`() {
        val favorite = LayoutPreset("Other").apply {
            slots.add(LayoutPreset.SlotDef("App", 0, 0, 1920, 720).apply { packageName = "com.example.other" })
        }
        LayoutPrefs.save(context, listOf(favorite))
        LayoutPrefs.setFavoriteIdIfPresent(context, favorite.id)
        val choice = StartupAppsSelection(StartupAppsMode.LAYOUT, "deleted-layout")
        ClusterPrefs.setStartupApps(context, choice)
        assertEquals(FissionOrchestrator.AutoStartResult.MISSING_LAYOUT,
            FissionOrchestrator.maybeAutoStartOnAppLaunch(context))
        assertEquals(choice, ClusterPrefs.getStartupApps(context))
    }

    @Test fun `invalid fixed package cannot become a command target`() {
        assertThrows(IllegalArgumentException::class.java) {
            ClusterPrefs.setStartupApps(context, StartupAppsSelection(StartupAppsMode.APP, "com.example;bad"))
        }
    }

    @Test fun `delayed layout dispatch still honors projection after choosing none`() {
        ClusterPrefs.setStartupApps(context, StartupAppsSelection(StartupAppsMode.NONE))
        ClusterPrefs.setBootProjectionEnabled(context, true)
        assertEquals(FissionOrchestrator.AutoStartResult.DISABLED, FissionOrchestrator.maybeAutoStartOnAppLaunch(context))
        val intent = org.robolectric.Shadows.shadowOf(context as Application).nextStartedService
        assertNotNull(intent)
        assertEquals(com.byd.dashcast.cluster.ClusterService::class.java.name, intent.component!!.className)
        assertTrue(intent.getBooleanExtra(com.byd.dashcast.cluster.ClusterService.EXTRA_BOOT_PROJECTION, false))
        assertFalse(intent.getBooleanExtra(com.byd.dashcast.cluster.ClusterService.EXTRA_BOOT_AUTOLAUNCH, false))
    }
}
