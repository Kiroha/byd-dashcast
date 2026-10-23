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
}
