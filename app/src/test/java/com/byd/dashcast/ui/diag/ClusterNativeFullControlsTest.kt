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
    @Test fun `normal observation freezes displayed nonce at click and never binds a later queued status`() {
        val c = Robolectric.buildActivity(Activity::class.java).setup()
        val header = LinearLayout(c.get()); val body = LinearLayout(c.get())
        val controls = ClusterNativeFullControls(c.get(), header, body, { true }, {}, {})
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        val ready = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val worker = ClusterNativeFullControls::class.java.getDeclaredField("work").apply { isAccessible = true }.get(null) as ExecutorService
        val nonce = "a".repeat(32)
        var seen = ""
        var failure: Throwable? = null
        worker.execute { ready.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                try {
                    assertEquals(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, code)
                    data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); assertEquals(10, data.readInt())
                    seen = data.readString().orEmpty(); assertEquals(0, data.dataAvail())
                    reply!!.writeNoException(); reply.writeString("""{"state":"IDLE","filename":"","nativeMode":{"state":"CLOSED","nonce":"$nonce","debt":false,"pending":false}}""")
                    return true
                } catch (error: Throwable) { failure = error; throw error }
            }
        }); vf.set(null, "34")
        try {
            val field = ClusterNativeFullControls::class.java.getDeclaredField("displayedNative").apply { isAccessible = true }
            field.set(controls, JSONObject().put("nonce", nonce).put("normalSettled", true).put("pending", false).put("debt", true))
            views(body).filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_native_seen_normal) }.performClick()
            field.set(controls, JSONObject().put("nonce", "b".repeat(32)).put("normalSettled", true).put("pending", false).put("debt", true))
            release.countDown(); worker.execute { done.countDown() }; assertTrue(done.await(3, TimeUnit.SECONDS))
            failure?.let { throw it }; assertEquals(nonce, seen)
        } finally { release.countDown(); controls.close(); c.pause().stop().destroy(); bf.set(null, previous); vf.set(null, version) }
    }
}
