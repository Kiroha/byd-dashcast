package com.byd.dashcast.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppsPanelLayoutPolicyTest {

    @Test
    fun `regular mode restores weighted five-column panel and honors filters`() {
        val visible = AppsPanelLayoutPolicy.resolve(compact = false, filtersEnabled = true)
        val hidden = AppsPanelLayoutPolicy.resolve(compact = false, filtersEnabled = false)

        assertNull(visible.fixedWidthDp)
        assertEquals(1.4f, visible.weight)
        assertEquals(5, visible.gridSpanCount)
        assertTrue(visible.showCategoryFilters)
        assertFalse(hidden.showCategoryFilters)
    }

    @Test
    fun `compact mode uses fixed two-column panel and always hides filters`() {
        val config = AppsPanelLayoutPolicy.resolve(compact = true, filtersEnabled = true)

        assertEquals(160, config.fixedWidthDp)
        assertEquals(0f, config.weight)
        assertEquals(2, config.gridSpanCount)
        assertFalse(config.showCategoryFilters)
    }

    @Test
    fun `production readers share defaults and Main applies the policy`() {
        assertFalse(com.byd.dashcast.ui.settings.SettingsActivity.DEFAULT_COMPACT_APPS_PANEL)
        assertTrue(com.byd.dashcast.ui.settings.SettingsActivity.DEFAULT_SHOW_CATEGORY_FILTERS)

        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/byd/dashcast/MainActivity.kt").isFile }
        assertTrue("could not locate the repo root", root != null)
        val main = File(root, "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()
        val coordinator = File(root,
            "app/src/main/java/com/byd/dashcast/ui/main/AppListCoordinator.kt").readText()
        val layout = File(root, "app/src/main/res/layout/activity_main.xml").readText()

        assertTrue(main.contains("AppsPanelLayoutPolicy.resolve(compact, filtersEnabled)"))
        assertTrue(main.contains("SettingsActivity.DEFAULT_SHOW_CATEGORY_FILTERS"))
        assertTrue(main.contains("SettingsActivity.DEFAULT_COMPACT_APPS_PANEL"))
        assertTrue(coordinator.contains("SettingsActivity.DEFAULT_COMPACT_APPS_PANEL"))
        val appsPanel = layout.substringAfter("android:id=\"@+id/ll_app_list_section\"")
            .substringBefore("</LinearLayout>")
        assertTrue(appsPanel.contains("android:layout_width=\"0dp\""))
        assertTrue(appsPanel.contains("android:layout_weight=\"1.4\""))
    }
}