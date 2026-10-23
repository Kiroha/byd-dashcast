package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.os.Binder
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import com.byd.dashcast.R
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterNativeFullControlsTest {
    private fun views(root: ViewGroup): List<View> = (0 until root.childCount).flatMap {
        val child = root.getChildAt(it); listOf(child) + if (child is ViewGroup) views(child) else emptyList()
    }
    @Test fun `native Full and RCC buttons are distinct report is first and confirmation needs both assertions`() {
        val c = Robolectric.buildActivity(DiagActivity::class.java).setup()
        try {
            val all = views(c.get().findViewById(android.R.id.content))
            val full = all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_native_full) }
            val report = views(full.parent as ViewGroup).filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_send_report) }
            val parent = full.parent as ViewGroup
            assertTrue(parent.indexOfChild(report) < parent.indexOfChild(full))
            assertTrue(all.filterIsInstance<Button>().any { it.text == c.get().getString(R.string.diag_cluster_rcc_theme_button) })
            assertTrue(all.filterIsInstance<Button>().any { it.text == c.get().getString(R.string.diag_cluster_native_normal) })
            full.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val checks = views(dialog.window!!.decorView as ViewGroup).filterIsInstance<CheckBox>()
            assertEquals(2, checks.size); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            checks[0].isChecked = true; assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            checks[1].isChecked = true; assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun `outstanding RCC debt explains the exact restore action in a popup and sends no native command`() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val previous = bf.get(null)
        var transactions = 0
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                transactions++; error("RCC debt must prevent native IPC")
            }
        })
        com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt.begin(app, "a".repeat(32))
        val c = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = c.get()
            views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.diag_cluster_native_full) }.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            val labels = views(dialog.window!!.decorView as ViewGroup).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
            assertTrue(labels.contains(activity.getString(R.string.diag_cluster_native_rcc_blocked)))
            assertTrue(labels.any { it.contains(activity.getString(R.string.diag_cluster_rcc_restore)) })
            assertTrue(com.byd.dashcast.clusterlive.ClusterThemeDiagnostics.snapshot(app).contains("native-request-rcc-blocked"))
            assertEquals(0, transactions)
        } finally {
            c.destroy(); bf.set(null, previous)
            app.getSharedPreferences("rcc_cluster_operational_debt", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @Test @Config(qualifiers = "fr")
    fun `French visible category header and projection action use translated names`() {
        val c = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = c.get(); val all = views(activity.findViewById(android.R.id.content))
            val title = activity.getString(R.string.diag_cluster_page_title)
            assertEquals("Cluster : projection et RCC", title)
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(title + "\n") }.performClick()
            assertTrue(all.filterIsInstance<android.widget.TextView>().any { it.text == title })
            assertTrue(all.filterIsInstance<Button>().any { it.text == "Tester la projection BYD (avec bandeaux)" })
        } finally { c.destroy() }
    }

}
