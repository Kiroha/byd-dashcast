package com.byd.dashcast.ui.diag

import android.app.AlertDialog
import android.content.Context
import android.os.Binder
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.byd.dashcast.R
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt
import com.byd.dashcast.proxy.ProxyClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterNativeReturnControlsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun views(root: ViewGroup): List<View> = (0 until root.childCount).flatMap {
        val child = root.getChildAt(it); listOf(child) + if (child is ViewGroup) views(child) else emptyList()
    }
    @After fun clearDebts() {
        for (store in listOf("rcc_cluster_operational_debt", "native_cluster_operational_debt"))
            app.getSharedPreferences(store, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `native test start is gone and the remaining return needs both assertions`() {
        ClusterNativeProjectionDebt.begin(app, "c".repeat(32))
        val c = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = c.get()
            val buttons = views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
            assertFalse(buttons.any { it.text.toString().contains("bandeaux") })
            val back = buttons.single { it.text == activity.getString(R.string.diag_cluster_native_normal) }
            assertEquals(View.VISIBLE, back.visibility)
            back.performClick(); ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            val checks = views(dialog.window!!.decorView as ViewGroup).filterIsInstance<CheckBox>()
            assertEquals(2, checks.size); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            checks[0].isChecked = true; assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            checks[1].isChecked = true; assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertEquals(activity.getString(R.string.fs_restore_confirm), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        } finally { c.destroy() }
    }

    @Test fun `outstanding RCC debt asks to return to the BYD display first and sends no native command`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val previous = bf.get(null)
        var transactions = 0
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                transactions++; error("RCC debt must prevent native IPC")
            }
        })
        ClusterNativeProjectionDebt.begin(app, "c".repeat(32))
        ClusterThemeProjectionDebt.begin(app, "a".repeat(32))
        val c = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = c.get()
            views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.diag_cluster_native_normal) }.performClick()
            ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            val labels = views(dialog.window!!.decorView as ViewGroup).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.contains(activity.getString(R.string.diag_cluster_native_rcc_blocked)))
            assertTrue(labels.contains(activity.getString(R.string.fs_recover_other)))
            assertTrue(ClusterThemeDiagnostics.snapshot(app).contains("native-request-rcc-blocked"))
            assertEquals(0, transactions)
        } finally {
            c.destroy(); bf.set(null, previous)
        }
    }

    @Test @Config(qualifiers = "fr")
    fun `French page title and guided page heading are translated`() {
        val c = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = c.get(); val all = views(activity.findViewById(android.R.id.content))
            val title = activity.getString(R.string.diag_cluster_dashboard_title)
            assertEquals("Combiné plein écran", title)
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(title + "\n") }.performClick()
            assertTrue(all.filterIsInstance<TextView>().any { it.text == title })
            assertTrue(all.filterIsInstance<TextView>().any { it.text == "Carte plein écran + instruments BYD" })
            assertTrue(all.filterIsInstance<Button>().any { it.text == "Revenir à l’affichage BYD" })
        } finally { c.destroy() }
    }
}
