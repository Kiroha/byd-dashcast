package com.byd.dashcast.ui.main

import com.byd.dashcast.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppListToggleAccessibilityTest {

    @Test
    fun `toggle describes its target mode`() {
        assertEquals(R.string.menu_view_list, AppListCoordinator.viewToggleDescription(true))
        assertEquals(R.string.menu_view_grid, AppListCoordinator.viewToggleDescription(false))
    }

    @Test
    fun `layout application updates content description with the icon`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/ui/main/AppListCoordinator.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/ui/main/AppListCoordinator.kt").readText()
        val apply = source.substringAfter("private fun applyLayoutManager(ctx: Context, isGrid: Boolean)")
            .substringBefore("// ── Public API")

        assertTrue(apply.contains("text = if (isGrid) \"☰\" else \"⊞\""))
        assertTrue(apply.contains("contentDescription = ctx.getString(viewToggleDescription(isGrid))"))
    }
}