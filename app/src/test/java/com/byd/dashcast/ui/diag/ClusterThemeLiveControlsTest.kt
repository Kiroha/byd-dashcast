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
    @Test fun `retired full app never reenables preparation opening or observation from an old positive receipt`() {
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = vf.get(null); vf.set(null, "44")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val header = LinearLayout(activity.get()); val panel = LinearLayout(activity.get())
        val dashboard = LinearLayout(activity.get()).also(panel::addView)
        val controls = ClusterThemeLiveControls(activity.get(), header, panel, { true }, {}, { false }, {}, dashboardContainer = dashboard)
        val render = ClusterThemeLiveControls::class.java.getDeclaredMethod("renderDashboard", JSONObject::class.java).apply { isAccessible = true }
        val status = ClusterThemeLiveControls::class.java.getDeclaredField("dashboardStatus").apply { isAccessible = true }.get(controls) as TextView
        val receipt = JSONObject().put("variant", ClusterThemeVariant.FULL_APP_21F7735C.id).put("rccDebt", true)
            .put("phase", "observation").put("filename", "legacy-fixture").put("themeObservedByUser", true)
            .put("dashboard", JSONObject().put("stage", "OPEN_RECEIVED").put("selectionReady", true)
                .put("pending", false).put("round", "a".repeat(32)).put("pixels", "USER_REPORTED"))
        fun button(text: Int) = views(panel).filterIsInstance<Button>().single { it.text == activity.get().getString(text) }
        try {
            repeat(2) {
                render.invoke(controls, receipt)
                assertFalse(button(R.string.diag_cluster_full_app_prepare).isEnabled)
                assertFalse(button(R.string.diag_cluster_dashboard_open).isEnabled)
                assertFalse(button(R.string.diag_cluster_dashboard_observe).isEnabled)
                assertEquals(activity.get().getString(R.string.diag_cluster_full_app_observe_message), status.text.toString())
            }
            assertTrue(views(header).filterIsInstance<Button>().single().isEnabled)
            receipt.put("recoveryPending", true); render.invoke(controls, receipt)
            assertEquals(activity.get().getString(R.string.diag_cluster_dashboard_restoring), status.text.toString())
            receipt.put("recoveryPending", false).getJSONObject("dashboard").put("stage", "CLOSED_RECEIVED")
            render.invoke(controls, receipt)
            assertFalse(button(R.string.diag_cluster_dashboard_open).isEnabled)
            assertEquals(activity.get().getString(R.string.diag_cluster_full_app_observe_message), status.text.toString())
        } finally { controls.close(); activity.pause().stop().destroy(); vf.set(null, previous) }
    }
    @Test fun `dashboard main flow keeps details folded and distinguishes preparation receipts and user observation`() {
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previousVersion = vf.get(null); vf.set(null, "43")
        val c = Robolectric.buildActivity(DiagActivity::class.java).setup()
        try {
            val all = views(c.get().findViewById(android.R.id.content))
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(c.get().getString(R.string.diag_cluster_dashboard_title) + "\n") }.performClick()
            val details = all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_dashboard_details) }
            val raw = all.filterIsInstance<Button>().single { it.text.toString().startsWith("Read current connected daemon") }
            assertFalse(raw.isShown)
            val controls = DiagActivity::class.java.getDeclaredField("liveThemeControls").apply { isAccessible = true }.get(c.get()) as ClusterThemeLiveControls
            val render = ClusterThemeLiveControls::class.java.getDeclaredMethod("renderDashboard", JSONObject::class.java).apply { isAccessible = true }
            val main = ClusterThemeLiveControls::class.java.getDeclaredField("dashboardStatus").apply { isAccessible = true }.get(controls) as TextView
            val open = all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_dashboard_open) }
            val observe = all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_dashboard_observe) }
            val dashboard = JSONObject().put("stage", "NONE").put("selectionReady", false).put("pending", false)
            val receipt = JSONObject().put("variant", ClusterThemeVariant.FULL_DASHBOARD_21F7735C.id).put("rccDebt", true)
                .put("filename", "owned-fixture").put("phase", "carrier-staging").put("dashboard", dashboard)
            render.invoke(controls, receipt)
            assertFalse(open.isEnabled); assertFalse(observe.isEnabled)
            assertEquals(c.get().getString(R.string.diag_cluster_dashboard_waiting), main.text.toString().substringBefore('\n'))
            dashboard.put("selectionReady", true); receipt.put("phase", "observation"); render.invoke(controls, receipt)
            assertTrue(open.isEnabled); assertFalse(observe.isEnabled)
            dashboard.put("stage", "OPEN_RECEIVED").put("round", "a".repeat(32)); render.invoke(controls, receipt)
            assertFalse(open.isEnabled); assertTrue(observe.isEnabled)
            assertEquals(c.get().getString(R.string.diag_cluster_dashboard_open_received), main.text.toString().substringBefore('\n'))
            dashboard.put("pixels", "USER_REPORTED"); receipt.put("themeObservedByUser", true); render.invoke(controls, receipt)
            assertEquals(c.get().getString(R.string.diag_cluster_dashboard_confirmed), main.text.toString().substringBefore('\n'))
            dashboard.put("stage", "CLOSED_RECEIVED"); render.invoke(controls, receipt)
            assertTrue(open.isEnabled); assertFalse(observe.isEnabled)
            dashboard.put("stage", "NONE").put("bootstrapStarted", true)
            receipt.put("mutated", false).put("state", "PREFLIGHT").put("phase", "dashboard-bootstrap-16")
            render.invoke(controls, receipt)
            assertEquals(c.get().getString(R.string.diag_cluster_dashboard_bootstrap), main.text.toString().substringBefore('\n'))
            receipt.put("phase", "dashboard-display-wait"); render.invoke(controls, receipt)
            assertEquals(c.get().getString(R.string.diag_cluster_dashboard_display_wait), main.text.toString().substringBefore('\n'))
            receipt.put("state", "QUARANTINE").put("uncertain", true); render.invoke(controls, receipt)
            assertEquals(c.get().getString(R.string.diag_cluster_dashboard_restore_required), main.text.toString().substringBefore('\n'))
            render.invoke(controls, receipt)
            assertEquals(c.get().getString(R.string.diag_cluster_dashboard_restore_required), main.text.toString().substringBefore('\n'))
            dashboard.put("geometry", JSONObject().put("verdict", "SMALL"))
            receipt.put("state", "CLEAN").put("rccDebt", false); render.invoke(controls, receipt)
            assertEquals(c.get().getString(R.string.diag_cluster_dashboard_idle), main.text.toString().substringBefore('\n'))
            val message = ClusterThemeLiveControls::class.java.getDeclaredMethod("dashboardMessage", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            message.invoke(controls, R.string.diag_cluster_dashboard_geometry, 43)
            assertTrue(main.isShown); assertEquals(c.get().getString(R.string.diag_cluster_dashboard_geometry), main.text)
            details.performClick(); assertTrue(raw.isShown)
        } finally { c.pause().stop().destroy(); busy.set(false); vf.set(null, previousVersion) }
    }
    @Test fun `foreground dashboard validity captured before queueing cannot revive after pause and a new activation`() {
        val prior = ClusterThemeLiveControls::class.java.getDeclaredField("reservation").apply { isAccessible = true }.get(null)
        if (prior is com.byd.dashcast.cluster.display.ProjectionCommandSequencer.Session)
            com.byd.dashcast.cluster.display.ProjectionCommandBus.endSession(prior)
        val reserved = checkNotNull(com.byd.dashcast.cluster.display.ProjectionCommandBus.reserveDiagnostic())
        val c = Robolectric.buildActivity(Activity::class.java).setup()
        val controls = ClusterThemeLiveControls(c.get(), LinearLayout(c.get()), LinearLayout(c.get()), { false }, {}, { false }, {})
        fun set(name: String, value: Any) = ClusterThemeLiveControls::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controls, value)
        set("active", true); set("activation", 7L)
        val validity = ClusterThemeLiveControls::class.java.getDeclaredMethod("validity",
            com.byd.dashcast.cluster.display.ProjectionCommandSequencer.Session::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
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
        } finally { controls.close(); c.pause().stop().destroy(); com.byd.dashcast.cluster.display.ProjectionCommandBus.endSession(reserved) }
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
        val executor = com.byd.dashcast.fission.FissionOrchestrator::class.java.getDeclaredField("mExec").apply { isAccessible = true }.get(fission) as java.util.concurrent.ExecutorService
        val field = ClusterThemeLiveControls::class.java.getDeclaredField("dashboardFission").apply { isAccessible = true }
        val previous = field.get(null); field.set(null, fission)
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
            executor.shutdownNow(); field.set(null, previous); controls.close(); c.pause().stop().destroy()
        }
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
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(c.get().getString(R.string.diag_cluster_dashboard_title) + "\n") }.performClick()
            all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_dashboard_details) }.performClick()
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
    @Test fun `current diagnostic offers only matched corner load and explains unavailable full replacement`() {
        val c = Robolectric.buildActivity(DiagActivity::class.java).setup()
        try {
            val all = views(c.get().findViewById(android.R.id.content))
            val buttons = all.filterIsInstance<Button>()
            assertEquals(1, buttons.count { it.text == c.get().getString(R.string.diag_cluster_rcc_corners_button) })
            assertFalse(buttons.any { it.text == c.get().getString(R.string.diag_cluster_rcc_marker_button) })
            assertFalse(buttons.any { it.text == c.get().getString(R.string.diag_cluster_rcc_theme_button) })
            assertTrue(all.filterIsInstance<TextView>().any { it.text.contains(c.get().getString(R.string.diag_cluster_rcc_cohort_help)) })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun `reused protocol40 daemon refuses current corner start before source staging or durable debt`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        var mutations = 0
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                if (data.readInt() != 0) { mutations++; error("No start with old daemon") }
                reply!!.writeNoException(); reply.writeString("""{"state":"IDLE","detail":"Old daemon fixture","filename":"","daemonUid":2000,"defaultQueried":false,"markerObservedByUser":false,"oemObservedByUser":false}""")
                return true
            }
        }); vf.set(null, "40")
        val c = Robolectric.buildActivity(UnavailableSourceActivity::class.java).setup()
        val controls = ClusterThemeLiveControls(c.get(), LinearLayout(c.get()), LinearLayout(c.get()), { true }, {}, { false }, {})
        val confirm = ClusterThemeLiveControls::class.java.getDeclaredMethod("confirm", ClusterThemeVariant::class.java).apply { isAccessible = true }
        try {
            confirm.invoke(controls, ClusterThemeVariant.CORNER_MARKER_21F7735C); ShadowLooper.idleMainLooper()
            val popup = ShadowAlertDialog.getLatestAlertDialog()
            views(popup.window!!.decorView as ViewGroup).filterIsInstance<CheckBox>().forEach { it.isChecked = true }
            popup.getButton(-1).performClick()
            awaitHistory(c.get(), c.get().getString(R.string.diag_cluster_rcc_restore_protocol))
            assertEquals(0, mutations); assertEquals(1L, c.get().entered.count)
            assertFalse(ClusterThemeProjectionDebt.blocked(c.get()))
        } finally {
            c.get().finish.countDown(); controls.close(); c.pause().stop().destroy(); bf.set(null, previous); vf.set(null, version)
        }
    }
    @Test fun `parked assertion mandatory and missing daemon releases claim before any load`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        bf.set(null, null); vf.set(null, null); busy.set(false)
        val c = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val all = views(c.get().findViewById(android.R.id.content))
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(c.get().getString(R.string.diag_cluster_dashboard_title) + "\n") }.performClick()
            all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_dashboard_details) }.performClick()
            val load = all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_rcc_corners_button) }
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
            all.filterIsInstance<Button>().single { it.text.toString().startsWith(c.get().getString(R.string.diag_cluster_dashboard_title) + "\n") }.performClick()
            all.filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_dashboard_details) }.performClick()
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
        }); vf.set(null, "41")
        val activity = Robolectric.buildActivity(UnavailableSourceActivity::class.java).setup()
        val header = LinearLayout(activity.get()); val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), header, panel, { true }, {}, { false }, {})
        try {
            views(panel).filterIsInstance<Button>().single { it.text == activity.get().getString(R.string.diag_cluster_rcc_corners_button) }.performClick()
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

    @Test fun `corner confirmation sends exact observed cohort asset through operation18 with both assertions`() =
        assertFixedAssetLoad(ClusterThemeVariant.CORNER_MARKER_21F7735C, 18, R.string.diag_cluster_rcc_corners_button, R.string.diag_cluster_rcc_corners_confirm)

    @Test fun `real Prepare click reaches operation19 with only the central Android display present`() {
        val dm = RuntimeEnvironment.getApplication().getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        assertTrue(dm.displays.none { it.displayId != 0 && com.byd.dashcast.cluster.display.ClusterDisplayNames.isKnownClusterName(it.name) })
        assertFixedAssetLoad(ClusterThemeVariant.FULL_DASHBOARD_21F7735C, 19,
            R.string.diag_cluster_dashboard_prepare, R.string.diag_cluster_dashboard_prepare_confirm)
    }

    @Test fun `dashboard preparation explains old protocol42 locally before any source staging or start IPC`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null); var starts = 0
        bf.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                if (data.readInt() != 0) starts++
                reply!!.writeNoException(); reply.writeString("""{"state":"IDLE","filename":"","rccDebt":false}""")
                return true
            }
        }); vf.set(null, "42")
        val c = Robolectric.buildActivity(UnavailableSourceActivity::class.java).setup()
        val panel = LinearLayout(c.get()); val main = LinearLayout(c.get()); panel.addView(main)
        val controls = ClusterThemeLiveControls(c.get(), LinearLayout(c.get()), panel, { true }, {}, { false }, {}, dashboardContainer = main)
        try {
            views(main).filterIsInstance<Button>().single { it.text == c.get().getString(R.string.diag_cluster_dashboard_prepare) }.performClick()
            ShadowLooper.idleMainLooper(); val dialog = ShadowAlertDialog.getLatestAlertDialog()
            views(dialog.window!!.decorView as ViewGroup).filterIsInstance<CheckBox>().forEach { it.isChecked = true }
            dialog.getButton(-1).performClick()
            assertEquals(0, starts); assertEquals(1L, c.get().entered.count)
            assertTrue(views(main).filterIsInstance<TextView>().any { it.text == c.get().getString(R.string.diag_cluster_dashboard_protocol, 43) })
            val render = ClusterThemeLiveControls::class.java.getDeclaredMethod("renderDashboard", JSONObject::class.java).apply { isAccessible = true }
            repeat(2) {
                render.invoke(controls, JSONObject().put("state", "IDLE").put("rccDebt", false))
                assertTrue(views(main).filterIsInstance<TextView>().any { it.text == c.get().getString(R.string.diag_cluster_dashboard_protocol, 43) })
            }
            assertFalse(ClusterThemeProjectionDebt.blocked(c.get()))
        } finally { c.get().finish.countDown(); controls.close(); c.pause().stop().destroy(); bf.set(null, previous); vf.set(null, version) }
    }

    private fun assertFixedAssetLoad(variant: ClusterThemeVariant, operation: Int, button: Int, confirmation: Int) {
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
                    assertEquals(operation, op)
                    assetReceived = true
                    ParcelFileDescriptor.CREATOR.createFromParcel(data).close()
                    // Robolectric cannot read a parcelled FD while its original remains open.
                    val source = File(RuntimeEnvironment.getApplication().getExternalFilesDir(null), "cluster-live-source")
                        .listFiles().orEmpty().single { it.name.startsWith(variant.id + "-") }
                    val bytes = source.readBytes()
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertEquals(variant.carrierSha256, digest)
                    assertEquals(1, data.readInt()); assertEquals(1, data.readInt())
                    val nonce = data.readString()!!
                    assertEquals(nonce, ClusterThemeProjectionDebt.nonce(RuntimeEnvironment.getApplication()))
                    assertNotNull(data.readStrongBinder()); assertEquals(0, data.dataAvail())
                    reply!!.writeNoException()
                    reply.writeString("""{"rccRejectedNonce":"$nonce","state":"REFUSED","detail":"Fixture no native load","filename":"","variant":"${variant.id}","daemonUid":2000,"defaultQueried":false,"markerObservedByUser":false,"themeObservedByUser":false,"oemObservedByUser":false,"raw":""}""")
                    return true
                } catch (error: Throwable) { failure = error; throw error }
                finally { if (assetReceived || failure != null) received.countDown() }
            }
        }); vf.set(null, if (variant.isDashboard) "43" else "41")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val header = LinearLayout(activity.get()); val panel = LinearLayout(activity.get())
        val dashboard = if (variant.isDashboard) LinearLayout(activity.get()).also(panel::addView) else null
        val controls = ClusterThemeLiveControls(activity.get(), header, panel, { true }, {}, { false }, {}, dashboardContainer = dashboard)
        if (variant.isDashboard) controls.setActive(true)
        try {
            views(panel).filterIsInstance<Button>().single { it.text == activity.get().getString(button) }.performClick()
            ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertTrue(views(dialog.window!!.decorView as ViewGroup).filterIsInstance<TextView>().any {
                it.text == activity.get().getString(confirmation)
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
        bf.set(null, Binder()); vf.set(null, "41")
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
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().findViewById<TextView>(android.R.id.message).text.toString()
                .contains(activity.get().getString(R.string.diag_cluster_rcc_retired_waiting)))
            state.put("state", "CLEAN").put("rccDebt", false)
            render.invoke(controls, state.toString(), 0L, session, 0L); ShadowLooper.idleMainLooper()
            assertEquals(activity.get().getString(R.string.diag_cluster_rcc_restore), views(header).filterIsInstance<Button>().single().text)
            assertFalse(views(header).filterIsInstance<TextView>().any { it.text.toString().contains(activity.get().getString(R.string.diag_cluster_rcc_retired_waiting)) })
        } finally { controls.close(); activity.pause().stop().destroy(); bf.set(null, previous); vf.set(null, version) }
    }

    @Test fun `historical own path cannot claim corner selection after uncertainty or another verification`() {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = bf.get(null); val version = vf.get(null)
        bf.set(null, Binder()); vf.set(null, "38")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val header = LinearLayout(activity.get()); val panel = LinearLayout(activity.get())
        val controls = ClusterThemeLiveControls(activity.get(), header, panel, { false }, {}, { false }, {})
        val render = ClusterThemeLiveControls::class.java.getDeclaredMethod("render", String::class.java,
            Long::class.javaPrimitiveType, ClusterThemeSession::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        val session = ClusterThemeSession.pin(activity.get())
        val state = JSONObject().put("state", "QUARANTINE").put("phase", "observation").put("uncertain", true)
            .put("variant", "corner-marker").put("rccDebt", true).put("mutated", true).put("filename", "")
            .put("detail", "Unknown exchange fixture").put("daemonUid", 2000).put("defaultQueried", false)
            .put("markerObservedByUser", false).put("oemObservedByUser", false)
            .put("loadEvidence", JSONObject().put("selection", "OWN_PATH_REPORTED"))
        val positive = activity.get().getString(R.string.diag_cluster_rcc_corners_pending)
        val neutral = activity.get().getString(R.string.diag_cluster_rcc_corners_verify_confirm)
        fun shown(): String = views(header).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        fun show() { render.invoke(controls, state.toString(), 0L, session, 0L); ShadowLooper.idleMainLooper() }
        try {
            show(); assertFalse(shown().contains(positive)); assertTrue(shown().contains(neutral))
            state.put("state", "SELECTION_PENDING_OEM_APPLY").put("uncertain", false)
            show(); assertTrue(shown().contains(positive))
            state.put("phase", "corner-read-only-verification")
            show(); assertFalse(shown().contains(positive)); assertTrue(shown().contains(neutral))
        } finally { controls.close(); activity.pause().stop().destroy(); bf.set(null, previous); vf.set(null, version) }
    }
}
