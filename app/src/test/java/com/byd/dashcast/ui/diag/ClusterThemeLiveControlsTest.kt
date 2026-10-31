package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.dashcast.R
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.ClusterThemeSession
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.report.BugWizardActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeLiveControlsTest {
    private val theme = ClusterThemeVariant.FULLSCREEN_INSTRUMENTS_21F7735C
    private val nonce = "a".repeat(32)
    private val owned = ClusterThemePolicy.PREFIX + nonce + ".rcc"
    private val round = "b".repeat(32)
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val diagBusy = DiagActivity::class.java.getDeclaredField("sBusy").apply { isAccessible = true }.get(null) as AtomicBoolean
    private val detected = Platform::class.java.getDeclaredField("autoDiLink3").apply { isAccessible = true }
    private var oldDetected = false
    private val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
    private val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
    private var priorBinder: Any? = null
    private var priorVersion: Any? = null

    private fun views(g: ViewGroup): List<View> = (0 until g.childCount).flatMap { i ->
        val v = g.getChildAt(i); listOf(v) + if (v is ViewGroup) views(v) else emptyList()
    }
    private fun dialogViews(d: AlertDialog) = views(d.window!!.decorView as ViewGroup)
    private fun dialogTexts(d: AlertDialog) = dialogViews(d).filterIsInstance<TextView>().map { it.text.toString() }
    private fun field(name: String) = ClusterThemeLiveControls::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun page(controls: ClusterThemeLiveControls) = field("page").get(controls) as FullscreenThemeView
    private fun state(controls: ClusterThemeLiveControls) = checkNotNull(page(controls).state)
    private fun renderDashboard(controls: ClusterThemeLiveControls, receipt: JSONObject) {
        ClusterThemeLiveControls::class.java.getDeclaredMethod("renderDashboard", JSONObject::class.java)
            .apply { isAccessible = true }.invoke(controls, receipt)
    }
    private fun confirmLoad(controls: ClusterThemeLiveControls) {
        ClusterThemeLiveControls::class.java.getDeclaredMethod("confirm", ClusterThemeVariant::class.java)
            .apply { isAccessible = true }.invoke(controls, theme)
        ShadowLooper.idleMainLooper()
    }
    private fun latestDialog(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()?.takeIf { it.isShowing }
    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do { ShadowLooper.idleMainLooper(); if (condition()) return; Thread.sleep(5) } while (System.nanoTime() < until)
        assertTrue("Condition did not settle", condition())
    }
    private fun drainLanes() {
        for (lane in listOf("work", "recovery")) {
            val done = CountDownLatch(1)
            (field(lane).get(null) as ExecutorService).execute { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        ShadowLooper.idleMainLooper()
    }
    private fun awaitHistory(activity: Activity, text: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!ClusterThemeDiagnostics.snapshot(activity).contains(text) && System.nanoTime() < until) Thread.sleep(5)
        assertTrue(ClusterThemeDiagnostics.snapshot(activity).contains(text))
    }
    /** Ticks every assertion, checking that the positive button stays disabled until the last one. */
    private fun assertAllAssertionsRequired(dialog: AlertDialog, count: Int = 2) {
        val checks = dialogViews(dialog).filterIsInstance<CheckBox>()
        assertEquals(count, checks.size)
        for (check in checks) {
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            check.isChecked = true
        }
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }
    private fun ownedReceipt(stage: String = "NONE") = JSONObject().put("state", "SELECTION_PENDING_OEM_APPLY").put("detail", "Fixture")
        .put("filename", owned).put("variant", theme.id).put("transport", "android-ivi").put("daemonUid", 2000)
        .put("rccDebt", true).put("mutated", true).put("phase", "observation").put("defaultQueried", false)
        .put("markerObservedByUser", false).put("themeObservedByUser", false).put("oemObservedByUser", false)
        .put("dashboard", JSONObject().put("stage", stage).put("selectionReady", true).put("pending", false).put("round", round))
    private fun idleReceipt() = """{"state":"IDLE","detail":"Read only preflight","filename":"","daemonUid":2000,"defaultQueried":false,"markerObservedByUser":false,"oemObservedByUser":false}"""

    @Before fun reset() {
        oldDetected = detected.getBoolean(Platform.get()); detected.setBoolean(Platform.get(), true)
        priorBinder = bf.get(null); priorVersion = vf.get(null)
        drainLanes()
        (field("reservation").get(null) as? ProjectionCommandSequencer.Session)?.let(ProjectionCommandBus::endSession)
        field("reservation").set(null, null); field("reservationFilename").set(null, ""); field("reservationDebt").setBoolean(null, false)
        for (key in listOf("busy", "recoveryBusy", "diagClaimed", "abortBeforeSend")) (field(key).get(null) as AtomicBoolean).set(false)
        field("dashboardPackage").set(null, ""); field("dashboardLabel").set(null, "")
        diagBusy.set(false)
        for (store in listOf("rcc_cluster_operational_debt", "native_cluster_operational_debt", "cluster_restart_intent"))
            app.getSharedPreferences(store, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().remove("small_cluster_panel_seen").commit()
    }
    @After fun restore() {
        drainLanes()
        detected.setBoolean(Platform.get(), oldDetected)
        bf.set(null, priorBinder); vf.set(null, priorVersion)
        field("dashboardPackage").set(null, ""); field("dashboardLabel").set(null, "")
        (field("reservation").get(null) as? ProjectionCommandSequencer.Session)?.let(ProjectionCommandBus::endSession)
        field("reservation").set(null, null)
        diagBusy.set(false); (field("diagClaimed").get(null) as AtomicBoolean).set(false)
        for (store in listOf("rcc_cluster_operational_debt", "native_cluster_operational_debt", "cluster_restart_intent"))
            app.getSharedPreferences(store, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().remove("small_cluster_panel_seen").commit()
    }

    @Test fun `guided page walks the full-screen instruments test with one primary action per step`() {
        vf.set(null, "47")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val dashboard = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), LinearLayout(activity.get()), LinearLayout(activity.get()),
            { true }, {}, { false }, {}, dashboardContainer = dashboard)
        fun s(id: Int, vararg args: Any) = activity.get().getString(id, *args)
        fun expect(step: Int, title: Int, message: String, primary: Int?, vararg secondary: Int, waiting: Boolean = false, danger: Boolean = false) {
            val now = state(controls)
            assertEquals(s(title), now.title); assertEquals(message, now.message); assertEquals(step, now.step)
            assertEquals(primary?.let { s(it) }, now.primary?.label)
            assertEquals(secondary.map { s(it) }, now.secondary.map { it.label })
            assertEquals(waiting, now.waiting); assertEquals(danger, now.danger)
        }
        val board = JSONObject().put("stage", "NONE").put("selectionReady", false).put("pending", false)
        val receipt = JSONObject().put("variant", theme.id).put("rccDebt", true).put("filename", "owned-fixture")
            .put("phase", "carrier-staging").put("dashboard", board)
        try {
            assertTrue("The page is the only content of its container", dashboard.childCount == 1)
            expect(0, R.string.fs_check_title, s(R.string.fs_check_message), R.string.fs_check_action)
            assertEquals(ClusterPreviewView.Mode.UNKNOWN, state(controls).preview)
            renderDashboard(controls, receipt)
            expect(1, R.string.fs_loading_title, s(R.string.fs_loading_message), null, waiting = true)
            board.put("selectionReady", true); receipt.put("phase", "observation"); renderDashboard(controls, receipt)
            expect(2, R.string.fs_app_title, s(R.string.fs_app_message), R.string.fs_app_action)
            field("dashboardPackage").set(null, "com.example.maps"); field("dashboardLabel").set(null, "Maps")
            renderDashboard(controls, receipt)
            expect(3, R.string.fs_start_title, s(R.string.fs_start_message, "Maps"), R.string.fs_start_action, R.string.fs_change_app)
            assertEquals(s(R.string.fs_start_expected), state(controls).expected)
            board.put("stage", "OPEN_RECEIVED").put("round", round); renderDashboard(controls, receipt)
            expect(3, R.string.fs_observe_title, s(R.string.fs_observe_message), R.string.fs_observe_action, R.string.fs_stop_action)
            assertEquals(ClusterPreviewView.Mode.FULLSCREEN, state(controls).preview)
            board.put("pending", true); renderDashboard(controls, receipt)
            expect(3, R.string.fs_opening_title, s(R.string.fs_opening_message), null, R.string.fs_stop_action, waiting = true)
            board.put("pending", false).put("stage", "OPENING"); renderDashboard(controls, receipt)
            expect(3, R.string.fs_opening_title, s(R.string.fs_opening_message), null, R.string.fs_stop_action, waiting = true)
            board.put("stage", "OPEN_RECEIVED").put("pixels", "USER_REPORTED"); receipt.put("themeObservedByUser", true); renderDashboard(controls, receipt)
            expect(4, R.string.fs_done_title, s(R.string.fs_done_message), R.string.fs_stop_action)
            board.put("stage", "CLOSED_RECEIVED"); renderDashboard(controls, receipt)
            expect(4, R.string.fs_closed_title, s(R.string.fs_closed_message, "Maps"), R.string.fs_finish_action,
                R.string.fs_relaunch_action, R.string.fs_change_app)
            board.put("stage", "NONE").put("bootstrapStarted", true)
            receipt.put("mutated", false).put("state", "PREFLIGHT").put("phase", "dashboard-bootstrap-16"); renderDashboard(controls, receipt)
            expect(1, R.string.fs_loading_title, s(R.string.fs_bootstrap_message), null, waiting = true)
            receipt.put("phase", "dashboard-display-wait"); renderDashboard(controls, receipt)
            expect(1, R.string.fs_loading_title, s(R.string.fs_display_message), null, waiting = true)
            receipt.put("state", "QUARANTINE").put("uncertain", true); renderDashboard(controls, receipt)
            expect(-1, R.string.fs_recover_title, s(R.string.fs_recover_message), R.string.fs_recover_action, danger = true)
            receipt.put("uncertain", false).put("variant", ClusterThemeVariant.FULLSCREEN_MINIMAL_21F7735C.id); renderDashboard(controls, receipt)
            expect(-1, R.string.fs_recover_title, s(R.string.fs_recover_other), R.string.fs_recover_action, danger = true)
            receipt.put("variant", theme.id).put("retirement", JSONObject().put("moved", true)); renderDashboard(controls, receipt)
            expect(-1, R.string.fs_recover_title, s(R.string.fs_recover_restart), R.string.fs_recover_action, danger = true)
            receipt.remove("retirement"); receipt.put("state", "CLEAN").put("rccDebt", false); renderDashboard(controls, receipt)
            expect(1, R.string.fs_load_title, s(R.string.fs_load_message), R.string.fs_load_action)
            assertEquals(s(R.string.fs_load_expected), state(controls).expected)
            vf.set(null, "46"); renderDashboard(controls, receipt)
            expect(0, R.string.fs_protocol_title, s(R.string.fs_protocol_message, 47), R.string.fs_check_action)
        } finally { controls.close(); activity.pause().stop().destroy() }
    }

    @Test fun `small cluster latch offers no test and leaves Restore as the only exit`() {
        vf.set(null, "47")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val controls = ClusterThemeLiveControls(activity.get(), LinearLayout(activity.get()), LinearLayout(activity.get()),
            { true }, {}, { false }, {}, dashboardContainer = LinearLayout(activity.get()))
        try {
            ClusterPrefs.latchSmallClusterPanel(activity.get())
            renderDashboard(controls, JSONObject().put("state", "CLEAN").put("rccDebt", false).put("filename", "owned-fixture"))
            assertEquals(activity.get().getString(R.string.fs_small_title), state(controls).title)
            assertNull(state(controls).primary)
            renderDashboard(controls, ownedReceipt())
            assertEquals(activity.get().getString(R.string.fs_small_message), state(controls).message)
            assertEquals(activity.get().getString(R.string.fs_recover_action), state(controls).primary?.label)
            assertFalse(field("openReady").getBoolean(controls))
        } finally { controls.close(); activity.pause().stop().destroy() }
    }

    @Test fun `retired full app receipt never offers opening or observation and only leads to Restore`() {
        vf.set(null, "47")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val header = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), header, LinearLayout(activity.get()),
            { true }, {}, { false }, {}, dashboardContainer = LinearLayout(activity.get()))
        val receipt = JSONObject().put("variant", ClusterThemeVariant.FULL_APP_21F7735C.id).put("rccDebt", true)
            .put("phase", "observation").put("filename", "legacy-fixture").put("themeObservedByUser", true)
            .put("dashboard", JSONObject().put("stage", "OPEN_RECEIVED").put("selectionReady", true)
                .put("pending", false).put("round", round).put("pixels", "USER_REPORTED"))
        try {
            repeat(2) {
                renderDashboard(controls, receipt)
                assertEquals(activity.get().getString(R.string.fs_recover_other), state(controls).message)
                assertEquals(activity.get().getString(R.string.fs_recover_action), state(controls).primary?.label)
                assertFalse(field("openReady").getBoolean(controls)); assertFalse(field("observeReady").getBoolean(controls))
            }
            assertTrue(views(header).filterIsInstance<Button>().single().isEnabled)
            receipt.put("recoveryPending", true); renderDashboard(controls, receipt)
            assertEquals(activity.get().getString(R.string.fs_wait_title), state(controls).title)
            assertEquals(-1, state(controls).step); assertNull(state(controls).primary)
            receipt.put("recoveryPending", false).getJSONObject("dashboard").put("stage", "CLOSED_RECEIVED")
            renderDashboard(controls, receipt)
            assertFalse(field("openReady").getBoolean(controls))
            assertEquals(activity.get().getString(R.string.fs_recover_other), state(controls).message)
        } finally { controls.close(); activity.pause().stop().destroy() }
    }

    @Test fun `load confirmation explains the step needs both assertions and sends the exact asset through operation 31`() {
        val received = CountDownLatch(1)
        var failure: Throwable? = null
        var assetReceived = false
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                try {
                    data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                    val op = data.readInt()
                    if (op == 0) { reply!!.writeNoException(); reply.writeString(idleReceipt()); return true }
                    assertEquals(31, op); assetReceived = true
                    ParcelFileDescriptor.CREATOR.createFromParcel(data).close()
                    // Robolectric cannot read a parcelled FD while its original remains open.
                    val source = File(app.getExternalFilesDir(null), "cluster-live-source")
                        .listFiles().orEmpty().single { it.name.startsWith(theme.id + "-") }
                    val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertEquals(theme.carrierSha256, digest)
                    assertEquals(1, data.readInt()); assertEquals(1, data.readInt())
                    val sent = data.readString()!!
                    assertEquals(sent, ClusterThemeProjectionDebt.nonce(app))
                    assertNotNull(data.readStrongBinder()); assertEquals(0, data.dataAvail())
                    reply!!.writeNoException()
                    reply.writeString("""{"rccRejectedNonce":"$sent","state":"REFUSED","detail":"Fixture no native load","filename":"","variant":"${theme.id}","daemonUid":2000,"defaultQueried":false,"markerObservedByUser":false,"themeObservedByUser":false,"oemObservedByUser":false,"raw":""}""")
                    return true
                } catch (error: Throwable) { failure = error; throw error }
                finally { if (assetReceived || failure != null) received.countDown() }
            }
        }); vf.set(null, "47")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), LinearLayout(activity.get()), panel, { true }, {}, { false }, {})
        try {
            controls.setActive(true)
            waitFor { state(controls).primary?.label == activity.get().getString(R.string.fs_load_action) }
            drainLanes()
            views(panel).filterIsInstance<Button>().single { it.text == activity.get().getString(R.string.fs_load_action) }.performClick()
            ShadowLooper.idleMainLooper()
            val dialog = checkNotNull(latestDialog())
            val texts = dialogTexts(dialog)
            assertTrue(texts.contains(activity.get().getString(R.string.fs_load_dialog_title)))
            assertTrue(texts.contains("1. " + activity.get().getString(R.string.fs_load_step_copy)))
            assertTrue(texts.contains(activity.get().getString(R.string.fs_load_expected)))
            assertEquals(activity.get().getString(R.string.fs_load_confirm), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
            assertAllAssertionsRequired(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(received.await(5, TimeUnit.SECONDS))
            failure?.let { throw it }
            awaitHistory(activity.get(), "Fixture no native load")
        } finally { controls.close(); activity.pause().stop().destroy() }
    }

    @Test fun `start confirmation names the chosen app needs both assertions and sends exactly one operation 20`() {
        ClusterThemeProjectionDebt.begin(app, nonce)
        val ops = Collections.synchronizedList(mutableListOf<Int>())
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val op = data.readInt(); ops.add(op)
                val receipt = ownedReceipt()
                if (op == 20) {
                    assertEquals(nonce, data.readString()); assertEquals(1, data.readInt()); assertEquals(1, data.readInt())
                    assertNotNull(data.readStrongBinder()); receipt.getJSONObject("dashboard").put("stage", "OPENING")
                } else assertEquals(0, op)
                reply!!.writeNoException(); reply.writeString(receipt.toString()); return true
            }
        }); vf.set(null, "47")
        field("dashboardPackage").set(null, "com.example.maps"); field("dashboardLabel").set(null, "Maps")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), LinearLayout(activity.get()), panel, { true }, {}, { false }, {})
        try {
            controls.setActive(true)
            waitFor { state(controls).primary?.label == activity.get().getString(R.string.fs_start_action) }
            drainLanes()
            views(panel).filterIsInstance<Button>().single { it.text == activity.get().getString(R.string.fs_start_action) }.performClick()
            ShadowLooper.idleMainLooper()
            val dialog = checkNotNull(latestDialog())
            assertTrue(dialogTexts(dialog).contains("2. " + activity.get().getString(R.string.fs_start_step_app, "Maps")))
            assertEquals(activity.get().getString(R.string.fs_start_confirm), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
            assertTrue(ops.all { it == 0 })
            assertAllAssertionsRequired(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { 20 in ops }
            drainLanes()
            assertEquals(1, ops.count { it == 20 }); assertTrue(ops.all { it == 0 || it == 20 })
        } finally { controls.close(); activity.pause().stop().destroy() }
    }

    @Test fun `observation offers three illustrated answers and only the map answer records the observation`() {
        ClusterThemeProjectionDebt.begin(app, nonce)
        val ops = Collections.synchronizedList(mutableListOf<Int>())
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val op = data.readInt(); ops.add(op)
                if (op == 22) { assertEquals(owned, data.readString()); assertEquals(round, data.readString()) }
                else assertEquals(0, op)
                reply!!.writeNoException(); reply.writeString(ownedReceipt("OPEN_RECEIVED").toString()); return true
            }
        }); vf.set(null, "47")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), LinearLayout(activity.get()), panel, { true }, {}, { false }, {})
        fun s(id: Int) = activity.get().getString(id)
        fun choose(label: Int) {
            views(panel).filterIsInstance<Button>().single { it.text == s(R.string.fs_observe_action) }.performClick()
            ShadowLooper.idleMainLooper()
            val dialog = checkNotNull(latestDialog())
            val rows = dialogViews(dialog).filter { it.contentDescription != null && it.isClickable }.map { it.contentDescription.toString() }
            assertEquals(listOf(s(R.string.fs_observe_ok), s(R.string.fs_observe_stock), s(R.string.fs_observe_problem)), rows)
            dialogViews(dialog).first { it.contentDescription?.toString() == s(label) }.performClick()
            ShadowLooper.idleMainLooper()
        }
        try {
            controls.setActive(true)
            waitFor { state(controls).primary?.label == s(R.string.fs_observe_action) }
            drainLanes()
            choose(R.string.fs_observe_stock)
            val help = checkNotNull(latestDialog())
            assertEquals(s(R.string.fs_observe_stock_help), help.findViewById<TextView>(android.R.id.message).text.toString())
            help.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            drainLanes(); assertTrue(ops.all { it == 0 })
            choose(R.string.fs_observe_ok)
            waitFor { 22 in ops }
            assertEquals(listOf(22), ops.filter { it != 0 })
            assertTrue(ClusterThemeDiagnostics.snapshot(activity.get()).contains("answer=fullscreen-visible"))
        } finally { controls.close(); activity.pause().stop().destroy() }
    }

    @Test fun `abnormal display answer opens the restore confirmation without sending any command`() {
        ClusterThemeProjectionDebt.begin(app, nonce)
        val ops = Collections.synchronizedList(mutableListOf<Int>())
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val op = data.readInt(); ops.add(op); assertEquals(0, op)
                reply!!.writeNoException(); reply.writeString(ownedReceipt("OPEN_RECEIVED").toString()); return true
            }
        }); vf.set(null, "47")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), LinearLayout(activity.get()), panel, { true }, {}, { false }, {})
        fun s(id: Int) = activity.get().getString(id)
        try {
            controls.setActive(true)
            waitFor { state(controls).primary?.label == s(R.string.fs_observe_action) }
            drainLanes()
            views(panel).filterIsInstance<Button>().single { it.text == s(R.string.fs_observe_action) }.performClick()
            ShadowLooper.idleMainLooper()
            dialogViews(checkNotNull(latestDialog())).first { it.contentDescription?.toString() == s(R.string.fs_observe_problem) }.performClick()
            waitFor { latestDialog()?.let { dialogTexts(it).contains(s(R.string.fs_restore_dialog_title)) } == true }
            val restore = checkNotNull(latestDialog())
            assertTrue(dialogTexts(restore).contains("1. " + s(R.string.fs_restore_step_close)))
            assertEquals(s(R.string.fs_restore_confirm), restore.getButton(AlertDialog.BUTTON_POSITIVE).text)
            assertEquals(2, dialogViews(restore).filterIsInstance<CheckBox>().size)
            assertFalse(restore.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            drainLanes(); assertTrue(ops.all { it == 0 })
        } finally { controls.close(); activity.pause().stop().destroy() }
    }

    @Test fun `old protocol daemon refuses the theme before source staging or durable debt`() {
        var mutations = 0
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                if (data.readInt() != 0) { mutations++; error("No start with old daemon") }
                reply!!.writeNoException(); reply.writeString(idleReceipt()); return true
            }
        }); vf.set(null, "46")
        val c = Robolectric.buildActivity(UnavailableSourceActivity::class.java).setup()
        val controls = ClusterThemeLiveControls(c.get(), LinearLayout(c.get()), LinearLayout(c.get()), { true }, {}, { false }, {})
        try {
            confirmLoad(controls)
            val popup = checkNotNull(latestDialog())
            dialogViews(popup).filterIsInstance<CheckBox>().forEach { it.isChecked = true }
            popup.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            awaitHistory(c.get(), "dashboard-protocol-refused")
            assertEquals(0, mutations); assertEquals(1L, c.get().entered.count)
            assertFalse(ClusterThemeProjectionDebt.blocked(c.get()))
        } finally { c.get().finish.countDown(); controls.close(); c.pause().stop().destroy() }
    }

    @Test fun `parked assertions are mandatory and a missing daemon releases the Diag claim before any load`() {
        bf.set(null, null); vf.set(null, "47")
        val c = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val controls = DiagActivity::class.java.getDeclaredField("liveThemeControls").apply { isAccessible = true }.get(c.get()) as ClusterThemeLiveControls
            confirmLoad(controls)
            val dialog = checkNotNull(latestDialog())
            assertAllAssertionsRequired(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            awaitHistory(c.get(), "REFUSED before start transmission")
            waitFor { !diagBusy.get() }
            val notice = FullscreenThemeView::class.java.getDeclaredField("noticeView").apply { isAccessible = true }.get(page(controls)) as TextView
            waitFor { notice.text.toString() == c.get().getString(R.string.fs_failed) }
            assertEquals(View.VISIBLE, notice.visibility)
            assertFalse(ClusterThemeProjectionDebt.blocked(c.get()))
        } finally { c.destroy() }
    }

    @Test fun `restore stays pinned outside pages and is not part of diagnostic busy disabling`() {
        val c = Robolectric.buildActivity(DiagActivity::class.java).setup()
        try {
            val all = views(c.get().findViewById(android.R.id.content))
            val restore = all.filterIsInstance<Button>().first { it.text == c.get().getString(R.string.diag_cluster_rcc_restore) }
            val root = restore.rootView.findViewById<View>(android.R.id.content).let { it as ViewGroup }.getChildAt(0) as ViewGroup
            val scroll = views(root).filterIsInstance<ScrollView>().first()
            assertTrue(root.indexOfChild(restore.parent as View) < root.indexOfChild(scroll.parent as View))
            val method = DiagActivity::class.java.getDeclaredMethod("allDiagButtons").apply { isAccessible = true }
            assertFalse((method.invoke(c.get()) as List<*>).contains(restore))
            assertEquals(7, all.filterIsInstance<Button>().count { it.contentDescription?.toString()?.endsWith("Open page") == true })
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(c.get().getString(R.string.diag_cluster_dashboard_title) + "\n") }.performClick()
            assertTrue(restore.isEnabled && restore.visibility == View.VISIBLE)
            assertTrue(all.filterIsInstance<TextView>().any { it.text == c.get().getString(R.string.fs_page_title) && it.isShown })
            assertTrue(all.filterIsInstance<Button>().none { it.text.toString().startsWith("Read current connected daemon") })
            c.get().onBackPressed()
            all.filterIsInstance<Button>().single { it.text.toString().startsWith("Exports & OEM collection\n") }.performClick()
            assertTrue(all.filterIsInstance<Button>().single { it.text == "Export complet accessible" }.isShown)
            assertTrue(restore.isShown)
        } finally { c.pause().stop().destroy(); diagBusy.set(false) }
    }

    @Test fun `cluster and reporting report shortcuts open the existing wizard without a cluster transaction`() {
        val c = Robolectric.buildActivity(DiagActivity::class.java).setup()
        try {
            val all = views(c.get().findViewById(android.R.id.content))
            val shortcuts = all.filterIsInstance<Button>().filter { it.text == c.get().getString(R.string.diag_send_report) }
            assertEquals(2, shortcuts.size)
            assertTrue(shortcuts.all { !it.isAllCaps && it.minimumHeight >= (48 * c.get().resources.displayMetrics.density).toInt() })
            for (button in shortcuts) {
                button.performClick()
                assertEquals(BugWizardActivity::class.java.name, shadowOf(c.get()).nextStartedActivity.component!!.className)
            }
        } finally { c.pause().stop().destroy(); diagBusy.set(false) }
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land")
    fun `recreated short landscape keeps Restore visible and re-queries active daemon without loading`() {
        var loads = 0
        val status = JSONObject().put("state", "AWAITING_OBSERVATION").put("detail", "Selection queried; pixels unconfirmed")
            .put("filename", owned).put("daemonUid", 2000).put("defaultQueried", false).put("markerObservedByUser", false)
            .put("oemObservedByUser", false).put("raw", "").toString()
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                if (data.readInt() != 0) loads++
                reply!!.writeNoException(); reply.writeString(status); return true
            }
        }); vf.set(null, "31")
        var c = Robolectric.buildActivity(DiagActivity::class.java).setup()
        try {
            c.pause().stop().destroy(); c = Robolectric.buildActivity(DiagActivity::class.java).setup()
            val all = views(c.get().findViewById(android.R.id.content))
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(c.get().getString(R.string.diag_cluster_dashboard_title) + "\n") }.performClick()
            waitFor { diagBusy.get() }
            val check = all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.fs_check_action) }
            assertTrue(check.isShown); check.performClick(); drainLanes()
            assertTrue(diagBusy.get()); assertEquals(0, loads)
            val restore = all.filterIsInstance<Button>().first { it.text == c.get().getString(R.string.diag_cluster_rcc_restore) }
            val decor = c.get().window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 640, 360)
            val location = IntArray(2); restore.getLocationOnScreen(location)
            assertTrue("Restore must remain enabled", restore.isEnabled); assertTrue("Restore must have measured height", restore.height > 0)
            assertTrue("Restore must fit short landscape: y=${location[1]} h=${restore.height}", location[1] >= 0 && location[1] + restore.height <= 360)
        } finally { c.pause().stop().destroy(); diagBusy.set(false) }
    }

    @Test fun `empty raw daemon refusal is captured even when Activity closes before reply`() {
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
        val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), LinearLayout(activity.get()), panel, { true }, {}, { false }, {})
        try {
            views(panel).filterIsInstance<Button>().single { it.text == activity.get().getString(R.string.fs_check_action) }.performClick()
            assertTrue(admitted.await(3, TimeUnit.SECONDS))
            controls.close(); activity.pause().stop().destroy(); finish.countDown()
            awaitHistory(activity.get(), "Renderer preflight refused before OEM exchange")
        } finally { finish.countDown(); controls.close() }
    }

    class UnavailableSourceActivity : Activity() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        override fun getExternalFilesDir(type: String?): File? {
            entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); return null
        }
    }

    @Test fun `local source failure remains recorded after Activity destruction without start IPC`() {
        var ipc = 0
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                if (data.readInt() != 0) { ipc++; error("No start IPC expected") }
                reply!!.writeNoException(); reply.writeString(idleReceipt()); return true
            }
        }); vf.set(null, "47")
        val activity = Robolectric.buildActivity(UnavailableSourceActivity::class.java).setup()
        val controls = ClusterThemeLiveControls(activity.get(), LinearLayout(activity.get()), LinearLayout(activity.get()), { true }, {}, { false }, {})
        try {
            confirmLoad(controls)
            val dialog = checkNotNull(latestDialog())
            assertAllAssertionsRequired(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(activity.get().entered.await(3, TimeUnit.SECONDS))
            controls.close(); activity.pause().stop().destroy(); activity.get().finish.countDown()
            awaitHistory(activity.get(), "Own external files unavailable")
            assertEquals(0, ipc)
        } finally { activity.get().finish.countDown(); controls.close() }
    }

    @Test fun `retired carrier shows the restart step and guidance while an unmoved carrier stays a plain recovery`() {
        bf.set(null, Binder()); vf.set(null, "41")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val header = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), header, LinearLayout(activity.get()), { false }, {}, { false }, {})
        field("active").setBoolean(controls, true)
        val render = ClusterThemeLiveControls::class.java.getDeclaredMethod("render", String::class.java,
            Long::class.javaPrimitiveType, ClusterThemeSession::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        val session = ClusterThemeSession.pin(activity.get())
        val receipt = JSONObject().put("state", "QUARANTINE").put("detail", "rename failed; retained debt")
            .put("filename", owned).put("daemonUid", 2000).put("defaultQueried", false).put("markerObservedByUser", false)
            .put("oemObservedByUser", false).put("transport", "android-ivi").put("rccDebt", true)
            .put("retirement", JSONObject().put("moved", false))
        fun show() { render.invoke(controls, receipt.toString(), 0L, session, 0L); ShadowLooper.idleMainLooper() }
        try {
            show()
            assertEquals(activity.get().getString(R.string.diag_cluster_rcc_restore), views(header).filterIsInstance<Button>().single().text)
            assertEquals(activity.get().getString(R.string.fs_recover_other), state(controls).message)
            assertNull(latestDialog())
            receipt.getJSONObject("retirement").put("moved", true)
            receipt.put("state", "WAITING_OEM_REINITIALIZATION").put("detail", "File retained")
            show()
            assertEquals(activity.get().getString(R.string.fs_recover_restart), state(controls).message)
            assertTrue(checkNotNull(latestDialog()).findViewById<TextView>(android.R.id.message).text.toString()
                .contains(activity.get().getString(R.string.fs_guidance_retired)))
            latestDialog()?.dismiss()
            receipt.put("state", "CLEAN").put("rccDebt", false).remove("retirement")
            vf.set(null, "47"); show()
            assertEquals(activity.get().getString(R.string.fs_load_title), state(controls).title)
            assertEquals(activity.get().getString(R.string.diag_cluster_rcc_restore), views(header).filterIsInstance<Button>().single().text)
        } finally { controls.close(); activity.pause().stop().destroy() }
    }

    @Test fun `foreground dashboard validity captured before queueing cannot revive after pause and a new activation`() {
        val reserved = checkNotNull(ProjectionCommandBus.reserveDiagnostic())
        val c = Robolectric.buildActivity(Activity::class.java).setup()
        val controls = ClusterThemeLiveControls(c.get(), LinearLayout(c.get()), LinearLayout(c.get()), { false }, {}, { false }, {})
        fun set(name: String, value: Any) = field(name).set(controls, value)
        set("active", true); set("activation", 7L)
        val validity = ClusterThemeLiveControls::class.java.getDeclaredMethod("validity",
            ProjectionCommandSequencer.Session::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(controls, reserved, true) as Binder
        fun allowed(): Int {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try { data.writeInterfaceToken("com.byd.dashcast.RccReservation"); assertTrue(validity.transact(1, data, reply, 0)); reply.readException(); return reply.readInt() }
            finally { reply.recycle(); data.recycle() }
        }
        try {
            assertEquals(1, allowed())
            controls.setActive(false); set("active", true); set("activation", 9L)
            assertEquals(0, allowed())
        } finally { controls.close(); c.pause().stop().destroy(); ProjectionCommandBus.endSession(reserved) }
    }

    @Test fun `remote recovery is admitted while a real attach Binder call blocks the Fission worker`() {
        val c = Robolectric.buildActivity(Activity::class.java).setup()
        val controls = ClusterThemeLiveControls(c.get(), LinearLayout(c.get()), LinearLayout(c.get()), { false }, {}, { false }, {})
        val fission = com.byd.dashcast.fission.FissionOrchestrator(c.get(),
            object : com.byd.dashcast.domain.cluster.ProjectionStateProvider {
                override fun isProjectionActive() = false
                override fun stopProjectionIfActive(onStopped: Runnable?) { onStopped?.run() }
            }, object : com.byd.dashcast.fission.FissionOrchestrator.Callbacks {
                override fun onSlotsChanged(slots: Collection<com.byd.dashcast.fission.FissionOrchestrator.SlotState>) {}
                override fun onDaemonBinderAcquired(binder: android.os.IBinder?) {}
                override fun onStatusMessage(message: String?) {}
                override fun onSlotError(pkg: String?, message: String?) {}
                override fun onProjectionConflict(proceedCallback: Runnable?) {}
            })
        val executor = com.byd.dashcast.fission.FissionOrchestrator::class.java.getDeclaredField("mExec").apply { isAccessible = true }.get(fission) as ExecutorService
        val fissionField = field("dashboardFission")
        val previous = fissionField.get(null); fissionField.set(null, fission)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val surface = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(com.byd.dashcast.proxy.daemon.SurfaceDaemon.TRANSACT_ATTACH_SLOT, code)
                data.enforceInterface(com.byd.dashcast.proxy.daemon.SurfaceDaemon.DESCRIPTOR)
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                reply!!.writeNoException(); reply.writeInt(0); return true
            }
        }
        executor.execute {
            try { com.byd.dashcast.fission.FissionClient.attachSlot(surface, "org.example.maps", 464, 256, 752, 320) }
            finally { returned.countDown() }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(1L, returned.count)
            val remote = CountDownLatch(1)
            val stop = ClusterThemeLiveControls::class.java.getDeclaredMethod("stopDashboardSlots", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            stop.invoke(controls, { remote.countDown(); Unit })
            assertTrue(remote.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1L, returned.count) // Remote request did not wait for attach/teardown.
        } finally {
            release.countDown(); assertTrue(returned.await(1, TimeUnit.SECONDS))
            val drained = CountDownLatch(1); executor.execute { drained.countDown() }; assertTrue(drained.await(1, TimeUnit.SECONDS))
            executor.shutdownNow(); fissionField.set(null, previous); controls.close(); c.pause().stop().destroy()
        }
    }
}
