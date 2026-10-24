package com.byd.dashcast.data.prefs

import android.content.Context
import android.graphics.Rect
import com.byd.dashcast.fission.FissionOrchestrator
import com.byd.dashcast.fission.LayoutPreset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ProjectionSessionPrefsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `normal package and split bounds survive a detached read`() {
        val bounds = Rect(0, 0, 960, 720)
        ProjectionSessionPrefs.rememberClassic(context, "com.example.first", bounds)
        bounds.right = 1
        ProjectionSessionPrefs.rememberClassic(context, "com.example.second", Rect(960, 0, 1920, 720))
        val saved = ProjectionSessionPrefs.read(context)!!
        assertEquals(listOf("com.example.first", "com.example.second"), saved.apps.map { it.packageName })
        assertEquals(Rect(0, 0, 960, 720), saved.apps[0].bounds)
        saved.apps[0].bounds!!.right = 2
        assertEquals(960, ProjectionSessionPrefs.read(context)!!.apps[0].bounds!!.right)
    }

    @Test fun `resizing primary preserves split ordering and fullscreen replaces both`() {
        ProjectionSessionPrefs.rememberClassic(context, "com.example.first", Rect(0, 0, 960, 720))
        ProjectionSessionPrefs.rememberClassic(context, "com.example.second", Rect(960, 0, 1920, 720))
        ProjectionSessionPrefs.rememberClassic(context, "com.example.first", Rect(0, 0, 900, 720))
        assertEquals("com.example.first", ProjectionSessionPrefs.read(context)!!.apps.first().packageName)
        ProjectionSessionPrefs.rememberClassic(context, "com.example.third", null)
        assertEquals(listOf("com.example.third"), ProjectionSessionPrefs.read(context)!!.apps.map { it.packageName })
    }

    @Test fun `snapshot is independent from cleanup history and stop clears it`() {
        ProjectionSessionPrefs.rememberClassic(context, "com.example.first", null)
        ClusterPrefs.setSessionClusterPkgs(context, setOf("com.example.stranded"))
        ClusterPrefs.clearSessionClusterPkgs(context)
        assertNotNull(ProjectionSessionPrefs.read(context))
        ProjectionSessionPrefs.clear(context)
        assertNull(ProjectionSessionPrefs.read(context))
    }

    @Test fun `removing failed or stopped primary keeps only the actual other app`() {
        ProjectionSessionPrefs.rememberClassic(context, "com.example.first", Rect(0, 0, 960, 720))
        ProjectionSessionPrefs.rememberClassic(context, "com.example.second", Rect(960, 0, 1920, 720))
        ProjectionSessionPrefs.remove(context, "com.example.first")
        assertEquals(listOf("com.example.second"), ProjectionSessionPrefs.read(context)!!.apps.map { it.packageName })
        ProjectionSessionPrefs.remove(context, "com.example.second")
        assertNull(ProjectionSessionPrefs.read(context))
    }

    @Test fun `actual layout bindings and geometry are detached from favorite edits`() {
        val preset = LayoutPreset("Actual").apply {
            slots.add(LayoutPreset.SlotDef("Left", 0, 0, 960, 720).apply { packageName = "com.example.first" })
            slots.add(LayoutPreset.SlotDef("Right", 960, 0, 960, 720).apply { packageName = "com.example.second" })
        }
        ProjectionSessionPrefs.rememberLayout(context, preset, setOf("com.example.first"))
        preset.name = "Edited favorite"
        preset.slots[0].w = 1
        val saved = ProjectionSessionPrefs.read(context)!!
        assertEquals("Actual", saved.layout!!.name)
        assertEquals(960, saved.layout.slots[0].w)
        assertNull(saved.layout.slots[1].packageName)
        assertEquals(listOf("com.example.first"), saved.apps.map { it.packageName })
    }

    @Test fun `saved actual session owns startup only when boot restore is enabled`() {
        val preset = LayoutPreset("Actual").apply {
            slots.add(LayoutPreset.SlotDef("App", 0, 0, 1920, 720).apply { packageName = "com.example.first" })
        }
        ProjectionSessionPrefs.rememberLayout(context, preset, setOf("com.example.first"))
        assertFalse(FissionOrchestrator.isAutoStartRequested(context))
        ClusterPrefs.setBootAutoStartEnabled(context, true)
        assertTrue(FissionOrchestrator.isAutoStartRequested(context))
        ProjectionSessionPrefs.rememberClassic(context, "com.example.first", null)
        assertFalse(FissionOrchestrator.isAutoStartRequested(context))
    }

    @Test fun `atomic replacement forgets failed split package and empty replacement clears`() {
        ProjectionSessionPrefs.rememberClassic(context, "com.example.first", Rect(0, 0, 960, 720))
        ProjectionSessionPrefs.rememberClassic(context, "com.example.second", Rect(960, 0, 1920, 720))
        ProjectionSessionPrefs.replaceClassic(context, listOf(ProjectionSessionPrefs.App("com.example.second", Rect(960, 0, 1920, 720))))
        assertEquals(listOf("com.example.second"), ProjectionSessionPrefs.read(context)!!.apps.map { it.packageName })
        ProjectionSessionPrefs.replaceClassic(context, emptyList())
        assertNull(ProjectionSessionPrefs.read(context))
    }

    @Test fun `boot settings remain independent across all four combinations`() {
        for (projection in listOf(false, true)) for (restore in listOf(false, true)) {
            ClusterPrefs.setBootProjectionEnabled(context, projection)
            ClusterPrefs.setBootAutoStartEnabled(context, restore)
            assertEquals(projection, ClusterPrefs.isBootProjectionEnabled(context))
            assertEquals(restore, ClusterPrefs.isBootAutoStartEnabled(context))
            assertEquals(projection || restore, ClusterPrefs.isAnyBootProjectionEnabled(context))
        }
    }

    @Test fun `deployed boot key retains restoration while new projection mode defaults off`() {
        context.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("boot_auto_start_enabled", true).commit()
        assertTrue(ClusterPrefs.isBootAutoStartEnabled(context))
        assertFalse(ClusterPrefs.isBootProjectionEnabled(context))
    }

    @Test fun `projection only suppresses independent favorite app automation`() {
        ClusterPrefs.setBootProjectionEnabled(context, true)
        ClusterPrefs.setBootAutoStartEnabled(context, false)
        com.byd.dashcast.proxy.DaemonConfig.setFissionModeEnabled(context, true)
        ClusterPrefs.setFissionAutoLayout(context, true)
        assertFalse(FissionOrchestrator.isAutoStartRequested(context))
    }

    @Test fun `missing saved layout apps still start projection only when separately requested`() {
        val preset = LayoutPreset("Missing").apply {
            slots.add(LayoutPreset.SlotDef("Gone", 0, 0, 1920, 720).apply { packageName = "com.example.uninstalled" })
        }
        ProjectionSessionPrefs.rememberLayout(context, preset, setOf("com.example.uninstalled"))
        ClusterPrefs.setBootAutoStartEnabled(context, true)
        ClusterPrefs.setBootProjectionEnabled(context, true)
        assertEquals(FissionOrchestrator.AutoStartResult.MISSING_LAYOUT, FissionOrchestrator.maybeAutoStartOnAppLaunch(context))
        val started = org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(com.byd.dashcast.cluster.ClusterService::class.java.name, started.component!!.className)
        assertTrue(started.getBooleanExtra(com.byd.dashcast.cluster.ClusterService.EXTRA_BOOT_PROJECTION, false))
        assertFalse(started.getBooleanExtra(com.byd.dashcast.cluster.ClusterService.EXTRA_BOOT_AUTOLAUNCH, false))
    }

    @Test fun `invalid saved package or geometry is never restored`() {
        val prefs = context.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        for (raw in listOf("broken", "{\"apps\":[{\"pkg\":\"com.example;bad\"}]}",
            "{\"apps\":[{\"pkg\":\"com.example.app\",\"bounds\":[0,0,0,10]}]}")) {
            prefs.edit().putString("last_projection_session_v1", raw).commit()
            assertNull(ProjectionSessionPrefs.read(context))
        }
    }
}
