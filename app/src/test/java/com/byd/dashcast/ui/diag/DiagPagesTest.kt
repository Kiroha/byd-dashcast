package com.byd.dashcast.ui.diag

import android.content.res.Configuration
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.proxy.ProxyClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiagPagesTest {
    private val titles = listOf("ADAS", RuntimeEnvironment.getApplication().getString(com.byd.dashcast.R.string.diag_cluster_page_title), "HUD & navigation", "Projection & displays",
        "Exports & OEM collection", "Reporting", "Diagnostic log")

    private fun views(group: ViewGroup): List<View> = (0 until group.childCount).flatMap { i ->
        val child = group.getChildAt(i)
        listOf(child) + if (child is ViewGroup) views(child) else emptyList()
    }
    private fun all(activity: DiagActivity) = views(activity.findViewById(android.R.id.content))
    private fun visible(view: View): Boolean = view.visibility == View.VISIBLE &&
        ((view.parent as? View)?.let(::visible) ?: true)
    private fun menu(activity: DiagActivity, title: String) = all(activity).filterIsInstance<Button>()
        .single { it.text.toString().startsWith("$title\n") }
    private fun tool(activity: DiagActivity, prefix: String) = all(activity).filterIsInstance<Button>()
        .single { it.text.toString().startsWith(prefix) }
    private fun save(activity: DiagActivity) = Bundle().also {
        DiagActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
            .apply { isAccessible = true }.invoke(activity, it)
    }
    private fun renderExport(activity: DiagActivity) = DiagActivity::class.java.getDeclaredMethod("renderFullExport")
        .apply { isAccessible = true }.invoke(activity)

    @Test fun `seven pages expose only their own tools and navigation never runs diagnostics`() {
        val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val priorBinder = binderField.get(null)
        val transactions = AtomicInteger()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                transactions.incrementAndGet()
                error("Page navigation must not call the daemon")
            }
        })
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        val activity = controller.get()
        val controlsField = DiagActivity::class.java.getDeclaredField("liveThemeControls").apply { isAccessible = true }
        val controls = controlsField.get(activity)
        val rawCapture = ClusterPrefs.isNavRawCaptureEnabled(activity)
        val launchCapture = ClusterPrefs.isLaunchDiagnosticsEnabled(activity)
        try {
            assertEquals(7, all(activity).filterIsInstance<Button>().count { it.contentDescription?.toString()?.endsWith("Open page") == true })
            val tools = mapOf(
                "ADAS" to "ADAS targets (read-only snapshot)",
                RuntimeEnvironment.getApplication().getString(com.byd.dashcast.R.string.diag_cluster_page_title) to controller.get().getString(com.byd.dashcast.R.string.diag_cluster_rcc_theme_button),
                "HUD & navigation" to "HUD bench (DL3)",
                "Projection & displays" to "OEM cluster probes (read-only)",
                "Exports & OEM collection" to "Export complet accessible",
                "Reporting" to "Pair reporting channel"
            )
            for (title in titles) {
                menu(activity, title).performClick()
                assertFalse(visible(menu(activity, title)))
                assertEquals(1, all(activity).filterIsInstance<ScrollView>().count(::visible))
                tools.forEach { (page, prefix) -> assertEquals("Tool $prefix belongs only to $page", page == title, visible(tool(activity, prefix))) }
                assertTrue(visible(tool(activity, activity.getString(com.byd.dashcast.R.string.diag_cluster_rcc_restore))))
                assertTrue(tool(activity, activity.getString(com.byd.dashcast.R.string.diag_cluster_rcc_restore)).isEnabled)
                assertSame("Navigation must retain live controls and polling session", controls, controlsField.get(activity))
                if (title == "Diagnostic log") {
                    val log = all(activity).filterIsInstance<TextView>().single { it.text.toString().startsWith("Ready.") }
                    assertTrue(visible(log)); assertTrue(log.isTextSelectable)
                    assertEquals(1, generateSequence(log.parent) { it.parent }.filterIsInstance<ScrollView>().count())
                }
                activity.onBackPressed()
                assertTrue(visible(menu(activity, title)))
                assertFalse(activity.isFinishing)
            }
            assertEquals(0, transactions.get())
            assertEquals(rawCapture, ClusterPrefs.isNavRawCaptureEnabled(activity))
            assertEquals(launchCapture, ClusterPrefs.isLaunchDiagnosticsEnabled(activity))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(all(activity).filterIsInstance<Button>().any { it.text == "BYD APK Extraction" })
            activity.onBackPressed()
            assertTrue("Back from the hub may leave Diagnostics", activity.isFinishing)
        } finally {
            controller.destroy()
            binderField.set(null, priorBinder)
        }
    }

    @Test fun `current page survives recreation and unknown saved destinations return to the hub`() {
        var controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            menu(controller.get(), RuntimeEnvironment.getApplication().getString(com.byd.dashcast.R.string.diag_cluster_page_title)).performClick()
            val saved = save(controller.get())
            controller.destroy()
            controller = Robolectric.buildActivity(DiagActivity::class.java).create(saved)
            assertTrue(visible(tool(controller.get(), controller.get().getString(com.byd.dashcast.R.string.diag_cluster_rcc_theme_button))))
            assertFalse(visible(menu(controller.get(), RuntimeEnvironment.getApplication().getString(com.byd.dashcast.R.string.diag_cluster_page_title))))
            tool(controller.get(), "Back").performClick()
            assertTrue(visible(menu(controller.get(), "ADAS")))
            controller.destroy()
            saved.putString("diag.page", "unrecognized-destination")
            controller = Robolectric.buildActivity(DiagActivity::class.java).create(saved)
            assertTrue(visible(menu(controller.get(), "ADAS")))
            assertFalse(visible(tool(controller.get(), controller.get().getString(com.byd.dashcast.R.string.diag_cluster_rcc_theme_button))))
        } finally { controller.destroy() }
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun `busy export leaves recovery cancellation and page content visible with large text`() {
        val resources = RuntimeEnvironment.getApplication().resources
        val originalConfiguration = Configuration(resources.configuration)
        resources.updateConfiguration(Configuration(originalConfiguration).apply { fontScale = 1.3f }, resources.displayMetrics)
        val snapshotField = FullApkExportSession::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
        val busy = DiagActivity::class.java.getDeclaredField("sBusy").apply { isAccessible = true }.get(null) as AtomicBoolean
        val priorSnapshot = snapshotField.get(FullApkExportSession)
        val priorBusy = busy.get()
        val controller = Robolectric.buildActivity(DiagActivity::class.java).setup()
        val activity = controller.get()
        try {
            val active = FullApkExport.Progress(stage = "Reading local sources", running = true,
                kind = ExportKind.APK, lastProgressMs = FullApkExport.monotonicMs() - 5_000)
            snapshotField.set(FullApkExportSession, active)
            busy.set(true)
            renderExport(activity)
            val restore = tool(activity, activity.getString(com.byd.dashcast.R.string.diag_cluster_rcc_restore))
            val cancel = tool(activity, "Cancel export")
            val summary = all(activity).filterIsInstance<TextView>().single { it.text.toString().contains("No progress for 5s") }
            for (title in titles) {
                menu(activity, title).performClick()
                val decor = activity.window.decorView
                decor.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, 640, 360)
                listOf(restore, cancel, summary).forEach { view ->
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    assertTrue("$title must show pinned $view", visible(view) && view.height > 0)
                    assertTrue("$title pinned action must fit the short screen", location[1] >= 0 && location[1] + view.height <= 360)
                }
                assertTrue(restore.isEnabled); assertTrue(cancel.isEnabled)
                assertTrue(restore.height >= 48); assertTrue(cancel.height >= 48)
                val page = all(activity).filterIsInstance<ScrollView>().single(::visible)
                assertTrue("$title needs remaining usable content height, got ${page.height}", page.height >= 80)
                assertSame("Page navigation cannot restart the export", active, FullApkExportSession.snapshot)
                assertTrue(busy.get())
                assertFalse(tool(activity, "Export complet accessible").isEnabled)
                activity.onBackPressed()
            }
            menu(activity, "Exports & OEM collection").performClick()
            assertTrue(all(activity).filterIsInstance<TextView>().any { visible(it) && it.text.toString().contains("Stockage interne uniquement") })
            assertTrue(all(activity).filterIsInstance<CheckBox>().none(::visible))
        } finally {
            controller.pause().stop().destroy()
            snapshotField.set(FullApkExportSession, priorSnapshot)
            busy.set(priorBusy)
            resources.updateConfiguration(originalConfiguration, resources.displayMetrics)
        }
    }
}
