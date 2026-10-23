package com.byd.dashcast.ui.diag

import android.app.AlertDialog
import android.app.Activity
import android.os.Binder
import android.os.Parcel
import android.view.View
import org.json.JSONObject
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.ClusterThemeSession
import com.byd.dashcast.R
import com.byd.dashcast.report.BugWizardActivity
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.security.MessageDigest
import android.os.ParcelFileDescriptor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeLiveControlsTest {
    private fun views(g: ViewGroup): List<android.view.View> = (0 until g.childCount).flatMap { i ->
        val v = g.getChildAt(i); listOf(v) + if (v is ViewGroup) views(v) else emptyList()
    }
    private val busy = DiagActivity::class.java.getDeclaredField("sBusy").apply { isAccessible = true }.get(null) as AtomicBoolean
    private val detected = Platform::class.java.getDeclaredField("autoDiLink3").apply { isAccessible = true }
    private var oldDetected = false
    @Before fun resetCompletedTestLease() {
        oldDetected = detected.getBoolean(Platform.get()); detected.setBoolean(Platform.get(), true)
        RuntimeEnvironment.getApplication().getSharedPreferences("rcc_cluster_operational_debt", Context.MODE_PRIVATE).edit().clear().commit()
        val localBusy = ClusterThemeLiveControls::class.java.getDeclaredField("busy").apply { isAccessible = true }.get(null) as AtomicBoolean
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (localBusy.get() && System.nanoTime() < until) Thread.sleep(5)
        assertFalse("Previous physical-call fixture must have returned", localBusy.get())
        (ClusterThemeLiveControls::class.java.getDeclaredField("diagClaimed").apply { isAccessible = true }.get(null) as AtomicBoolean).set(false)
        busy.set(false)
    }
    @After fun restorePlatform() {
        detected.setBoolean(Platform.get(), oldDetected)
        RuntimeEnvironment.getApplication().getSharedPreferences("rcc_cluster_operational_debt", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun `restore stays pinned outside pages and is not part of diagnostic busy disabling`() {
        val c = Robolectric.buildActivity(DiagActivity::class.java).setup()
        try {
            val all = views(c.get().findViewById(android.R.id.content))
            val restore = all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_rcc_restore) }
            val root = restore.rootView.findViewById<View>(android.R.id.content).let { it as ViewGroup }.getChildAt(0) as ViewGroup
            val scroll = views(root).filterIsInstance<ScrollView>().first()
            assertTrue(root.indexOfChild(restore.parent as View) < root.indexOfChild(scroll.parent as View))
            val method = DiagActivity::class.java.getDeclaredMethod("allDiagButtons").apply { isAccessible = true }
            assertFalse((method.invoke(c.get()) as List<*>).contains(restore))
            assertEquals(7, all.filterIsInstance<Button>().count { it.contentDescription?.toString()?.endsWith("Open page") == true })
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(c.get().getString(R.string.diag_cluster_page_title) + "\n") }.performClick()
            assertTrue(restore.isEnabled && restore.visibility == View.VISIBLE)
            assertTrue(all.filterIsInstance<Button>().single { it.text.toString().startsWith("Read current connected daemon") }.isShown)
            c.get().onBackPressed()
            all.filterIsInstance<Button>().single { it.text.toString().startsWith("Exports & OEM collection\n") }.performClick()
            assertTrue(all.filterIsInstance<Button>().single { it.text == "Export complet accessible" }.isShown)
            assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().contains("state UNKNOWN") })
        } finally { c.pause().stop().destroy(); busy.set(false) }
    }
    @Test fun `cluster and reporting report shortcuts open the existing wizard without a cluster transaction`() {
        val c = Robolectric.buildActivity(DiagActivity::class.java).setup()
        try {
            val all = views(c.get().findViewById(android.R.id.content))
            val label = c.get().getString(R.string.diag_send_report)
            val shortcuts = all.filterIsInstance<Button>().filter { it.text == label }
            assertEquals(2, shortcuts.size)
            assertTrue(shortcuts.all { !it.isAllCaps && it.minimumHeight >= (48 * c.get().resources.displayMetrics.density).toInt() })
            for (button in shortcuts) {
                button.performClick()
                assertEquals(BugWizardActivity::class.java.name, shadowOf(c.get()).nextStartedActivity.component!!.className)
            }
            assertTrue(all.filterIsInstance<Button>().any { it.text == c.get().getString(R.string.diag_cluster_oem_query_button) })
        } finally { c.pause().stop().destroy(); busy.set(false) }
    }
    @Test fun `parked assertion mandatory and missing daemon releases claim before any load`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        bf.set(null, null); vf.set(null, null); busy.set(false)
        val c = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val all = views(c.get().findViewById(android.R.id.content))
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(c.get().getString(R.string.diag_cluster_page_title) + "\n") }.performClick()
            val load = all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_rcc_marker_button) }
            busy.set(true); load.performClick(); assertNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(ClusterThemeDiagnostics.snapshot(c.get()).contains("start-busy"))
            busy.set(false); load.performClick(); ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            views(dialog.window!!.decorView as ViewGroup).filterIsInstance<CheckBox>().let { checks ->
                assertEquals(2, checks.size); checks[0].isChecked = true
                assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled); checks[1].isChecked = true
            }
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (busy.get() && System.nanoTime() < until) Thread.sleep(5)
            assertFalse("Definite pre-transmission refusal must release Diag gate", busy.get())
            ShadowLooper.idleMainLooper()
            assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().contains("REFUSED before start transmission") })
            assertTrue(ClusterThemeDiagnostics.snapshot(c.get()).contains("REFUSED before start transmission"))
            assertTrue(all.filterIsInstance<Button>().single { it.text == "Verify experimental theme (local only)" }.isEnabled)
        } finally { c.destroy(); busy.set(false); bf.set(null, previous); vf.set(null, version) }
    }
    @Test @Config(qualifiers = "w640dp-h360dp-land")
    fun `recreated short landscape keeps Restore visible and re-queries active daemon without loading`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        var loads = 0
        val status = JSONObject().put("state", "AWAITING_OBSERVATION").put("detail", "Selection queried; pixels unconfirmed")
            .put("filename", "Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_" + "a".repeat(32) + ".rcc")
            .put("daemonUid", 2000).put("defaultQueried", false).put("markerObservedByUser", false).put("oemObservedByUser", false).put("raw", "").toString()
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                if (data.readInt() == 1) loads++
                reply!!.writeNoException(); reply.writeString(status); return true
            }
        }); vf.set(null, "31"); busy.set(false)
        var c = Robolectric.buildActivity(DiagActivity::class.java).setup()
        try {
            c.pause().stop().destroy(); c = Robolectric.buildActivity(DiagActivity::class.java).setup()
            val all = views(c.get().findViewById(android.R.id.content))
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(c.get().getString(R.string.diag_cluster_page_title) + "\n") }.performClick()
            assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().contains("state UNKNOWN") })
            all.filterIsInstance<Button>().single { it.text.toString().startsWith("Read current connected daemon") }.performClick()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!busy.get() && System.nanoTime() < until) { Thread.sleep(5); ShadowLooper.idleMainLooper() }
            assertTrue(busy.get()); assertEquals(0, loads)
            val restore = all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_rcc_restore) }
            val decor = c.get().window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 640, 360)
            val location = IntArray(2); restore.getLocationOnScreen(location)
            assertTrue("Restore must remain enabled", restore.isEnabled); assertTrue("Restore must have measured height; decor=${decor.measuredHeight} parent=${(restore.parent as ViewGroup).measuredHeight}", restore.height > 0)
            assertTrue("Restore must fit short landscape: y=${location[1]} h=${restore.height}", location[1] >= 0 && location[1] + restore.height <= 360)
        } finally { c.pause().stop().destroy(); busy.set(false); bf.set(null, previous); vf.set(null, version) }
    }

    @Test fun `empty raw daemon refusal is captured even when Activity closes before reply`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        val admitted = CountDownLatch(1); val finish = CountDownLatch(1)
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); assertEquals(0, data.readInt())
                admitted.countDown(); check(finish.await(5, TimeUnit.SECONDS))
                reply!!.writeNoException()
                reply.writeString("""{"state":"REFUSED","detail":"Renderer preflight refused before OEM exchange","filename":"","daemonUid":2000,"defaultQueried":false,"markerObservedByUser":false,"oemObservedByUser":false,"raw":""}""")
                return true
            }
        }); vf.set(null, "31")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val header = LinearLayout(activity.get()); val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), header, panel, { true }, {}, { false }, {})
        try {
            views(panel).filterIsInstance<Button>().single { it.text.toString().startsWith("Read current connected") }.performClick()
            assertTrue(admitted.await(3, TimeUnit.SECONDS))
            controls.close(); activity.pause().stop().destroy(); finish.countDown()
            awaitHistory(activity.get(), "Renderer preflight refused before OEM exchange")
        } finally { finish.countDown(); controls.close(); bf.set(null, previous); vf.set(null, version) }
    }

    class UnavailableSourceActivity : Activity() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        override fun getExternalFilesDir(type: String?): File? {
            entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); return null
        }
    }

    @Test fun `local source failure remains recorded after Activity destruction without start IPC`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        var ipc = 0
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                if (data.readInt() != 0) { ipc++; error("No start IPC expected") }
                reply!!.writeNoException(); reply.writeString("""{"state":"IDLE","detail":"Read only preflight","filename":"","daemonUid":2000,"defaultQueried":false,"markerObservedByUser":false,"oemObservedByUser":false}"""); return true
            }
        }); vf.set(null, "31")
        val activity = Robolectric.buildActivity(UnavailableSourceActivity::class.java).setup()
        val header = LinearLayout(activity.get()); val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), header, panel, { true }, {}, { false }, {})
        try {
            views(panel).filterIsInstance<Button>().single { it.text == activity.get().getString(R.string.diag_cluster_rcc_marker_button) }.performClick()
            ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            views(dialog.window!!.decorView as ViewGroup).filterIsInstance<CheckBox>().let { checks ->
                assertEquals(2, checks.size); checks[0].isChecked = true
                assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled); checks[1].isChecked = true
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(activity.get().entered.await(3, TimeUnit.SECONDS))
            controls.close(); activity.pause().stop().destroy(); activity.get().finish.countDown()
            awaitHistory(activity.get(), "Own external files unavailable")
            assertEquals(0, ipc)
        } finally { activity.get().finish.countDown(); controls.close(); bf.set(null, previous); vf.set(null, version) }
    }

    private fun awaitHistory(activity: Activity, text: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!ClusterThemeDiagnostics.snapshot(activity).contains(text) && System.nanoTime() < until) Thread.sleep(5)
        assertTrue(ClusterThemeDiagnostics.snapshot(activity).contains(text))
    }

    @Test fun `full theme confirmation sends exact asset through IVI operation12 and durable nonce`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        val received = CountDownLatch(1)
        var assetReceived = false
        var failure: Throwable? = null
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                try {
                    data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                    val op = data.readInt()
                    if (op == 0) {
                        reply!!.writeNoException(); reply.writeString("""{"state":"IDLE","detail":"Read only preflight","filename":"","daemonUid":2000,"defaultQueried":false,"markerObservedByUser":false,"oemObservedByUser":false}""")
                        return true
                    }
                    assertEquals(12, op)
                    assetReceived = true
                    ParcelFileDescriptor.CREATOR.createFromParcel(data).close()
                    // Robolectric cannot read a parcelled FD while its original remains open.
                    val source = File(RuntimeEnvironment.getApplication().getExternalFilesDir(null), "cluster-live-source")
                        .listFiles().orEmpty().single { it.name.startsWith("full-surface-test-") }
                    val bytes = source.readBytes()
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertEquals(ClusterThemeVariant.FULL_SURFACE_TEST.carrierSha256, digest)
                    assertEquals(1, data.readInt()); assertEquals(1, data.readInt())
                    val nonce = data.readString()!!
                    assertEquals(nonce, ClusterThemeProjectionDebt.nonce(RuntimeEnvironment.getApplication()))
                    assertNotNull(data.readStrongBinder()); assertEquals(0, data.dataAvail())
                    reply!!.writeNoException()
                    reply.writeString("""{"rccRejectedNonce":"$nonce","state":"REFUSED","detail":"Fixture no native load","filename":"","variant":"full-surface-test","daemonUid":2000,"defaultQueried":false,"markerObservedByUser":false,"themeObservedByUser":false,"oemObservedByUser":false,"raw":""}""")
                    return true
                } catch (error: Throwable) { failure = error; throw error }
                finally { if (assetReceived || failure != null) received.countDown() }
            }
        }); vf.set(null, "37")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val header = LinearLayout(activity.get()); val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), header, panel, { true }, {}, { false }, {})
        try {
            views(panel).filterIsInstance<Button>().single { it.text == activity.get().getString(R.string.diag_cluster_rcc_theme_button) }.performClick()
            ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertTrue(views(dialog.window!!.decorView as ViewGroup).filterIsInstance<TextView>().any {
                it.text == activity.get().getString(R.string.diag_cluster_rcc_ivi_theme_confirm)
            })
            views(dialog.window!!.decorView as ViewGroup).filterIsInstance<CheckBox>().let { checks ->
                assertEquals(2, checks.size); checks[0].isChecked = true
                assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled); checks[1].isChecked = true
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(received.await(5, TimeUnit.SECONDS))
            failure?.let { throw it }
            awaitHistory(activity.get(), "Fixture no native load")
        } finally { controls.close(); activity.pause().stop().destroy(); bf.set(null, previous); vf.set(null, version) }
    }

    @Test fun `incomplete retirement has no prepared claim and completed retirement names the visible verification button`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        bf.set(null, Binder()); vf.set(null, "37")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val header = LinearLayout(activity.get()); val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), header, panel, { false }, {}, { false }, {})
        val activeField = ClusterThemeLiveControls::class.java.getDeclaredField("active").apply { isAccessible = true }
        activeField.setBoolean(controls, true)
        val render = ClusterThemeLiveControls::class.java.getDeclaredMethod("render", String::class.java,
            Long::class.javaPrimitiveType, ClusterThemeSession::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        val session = ClusterThemeSession.pin(activity.get())
        val state = JSONObject().put("state", "QUARANTINE").put("detail", "rename failed; retained debt")
            .put("filename", "Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_" + "a".repeat(32) + ".rcc")
            .put("daemonUid", 2000).put("defaultQueried", false).put("markerObservedByUser", false).put("oemObservedByUser", false)
            .put("transport", "android-ivi").put("rccDebt", true).put("retirement", JSONObject().put("moved", false))
        try {
            render.invoke(controls, state.toString(), 0L, session, 0L); ShadowLooper.idleMainLooper()
            assertEquals(activity.get().getString(R.string.diag_cluster_rcc_restore), views(header).filterIsInstance<Button>().single().text)
            val prepare = views(panel).filterIsInstance<Button>().single { it.text == activity.get().getString(R.string.diag_cluster_rcc_retire_button) }
            assertEquals(View.VISIBLE, prepare.visibility)
            assertFalse(views(header).filterIsInstance<TextView>().any { it.text.toString().contains(activity.get().getString(R.string.diag_cluster_rcc_retired_waiting)) })
            prepare.performClick()
            ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertTrue(views(dialog.window!!.decorView as ViewGroup).filterIsInstance<TextView>().any {
                it.text == activity.get().getString(R.string.diag_cluster_rcc_retire_confirm)
            })
            dialog.dismiss()
            state.getJSONObject("retirement").put("moved", true)
            state.put("state", "WAITING_OEM_REINITIALIZATION").put("detail", "File retained")
            render.invoke(controls, state.toString(), 0L, session, 0L); ShadowLooper.idleMainLooper()
            assertEquals(activity.get().getString(R.string.diag_cluster_rcc_verify_return), views(header).filterIsInstance<Button>().single().text)
            assertEquals(View.GONE, prepare.visibility)
            assertTrue(views(header).filterIsInstance<TextView>().any { it.text.toString().contains(activity.get().getString(R.string.diag_cluster_rcc_retired_waiting)) })
            assertTrue(activity.get().getString(R.string.diag_cluster_rcc_debt).contains(activity.get().getString(R.string.diag_cluster_rcc_verify_return)))
            state.put("state", "CLEAN").put("rccDebt", false)
            render.invoke(controls, state.toString(), 0L, session, 0L); ShadowLooper.idleMainLooper()
            assertEquals(activity.get().getString(R.string.diag_cluster_rcc_restore), views(header).filterIsInstance<Button>().single().text)
            assertFalse(views(header).filterIsInstance<TextView>().any { it.text.toString().contains(activity.get().getString(R.string.diag_cluster_rcc_retired_waiting)) })
        } finally { controls.close(); activity.pause().stop().destroy(); bf.set(null, previous); vf.set(null, version) }
    }
}
