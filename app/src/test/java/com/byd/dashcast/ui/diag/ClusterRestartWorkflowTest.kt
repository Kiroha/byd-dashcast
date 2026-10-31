package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.dashcast.R
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.infrastructure.ClusterRestartConnection
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterRestartWorkflowTest {
    private val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
    private val round = "b".repeat(32)
    private fun field(name: String) = ClusterThemeLiveControls::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun drain() {
        for (lane in listOf("work", "recovery")) {
            val latch = CountDownLatch(1)
            (field(lane).get(null) as ExecutorService).execute { latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Before @After fun reset() {
        drain()
        (field("reservation").get(null) as? ProjectionCommandSequencer.Session)?.let(ProjectionCommandBus::endSession)
        field("reservation").set(null, null); field("reservationDebt").setBoolean(null, false)
        for (key in listOf("busy", "recoveryBusy", "diagClaimed", "abortBeforeSend")) (field(key).get(null) as AtomicBoolean).set(false)
        for (store in listOf("cluster_restart_intent", "rcc_cluster_operational_debt", "native_cluster_operational_debt"))
            RuntimeEnvironment.getApplication().getSharedPreferences(store, 0).edit().clear().commit()
    }
    private fun views(group: ViewGroup): List<View> = (0 until group.childCount).flatMap {
        val child = group.getChildAt(it); listOf(child) + if (child is ViewGroup) views(child) else emptyList()
    }
    private inner class Fixture : AutoCloseable {
        val events = Collections.synchronizedList(mutableListOf<String>())
        var rejectProof = false
        var rejectRevalidation = false
        var timeout = false
        var boot = "11111111-1111-1111-1111-111111111111"
        var overrideReceipt: JSONObject? = null
        var authenticationGate: CountDownLatch? = null
        private val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        private val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        private val oldBinder = bf.get(null); private val oldVersion = vf.get(null)
        private fun receipt() = JSONObject().put("state", "WAITING_OEM_REINITIALIZATION").put("detail", "Preserved RCC")
            .put("filename", name).put("variant", "full-app-21f7735c").put("transport", "android-ivi")
            .put("daemonUid", 2000).put("rccDebt", true).put("mutated", true).put("uncertain", true)
            .put("defaultQueried", false).put("markerObservedByUser", false).put("themeObservedByUser", false)
            .put("oemObservedByUser", false).put("retirement", JSONObject().put("moved", true))
            .put("nativeMode", JSONObject().put("debt", false).put("pending", false))
        private val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val op = data.readInt()
                val result = overrideReceipt?.let { JSONObject(it.toString()) } ?: receipt()
                when (op) {
                    0 -> Unit
                    27 -> {
                        assertEquals("a".repeat(32), data.readString()); assertNotNull(data.readStrongBinder())
                        events += "verify-before-consent"
                        if (rejectProof) throw IllegalStateException("Another RCC remains")
                        result.put("restartRound", round)
                    }
                    28, 29 -> {
                        assertEquals("a".repeat(32), data.readString()); assertEquals(round, data.readString())
                        if (op == 29) events += "cancel" else {
                            events += "revalidate-after-consent"
                            if (rejectRevalidation) throw IllegalStateException("Original RCC reappeared")
                            result.put("restartAuthorized", true).put("restartRound", round).put("restartDispatchPending", true)
                        }
                    }
                    else -> error("Unexpected mutation: $op")
                }
                assertEquals(0, data.dataAvail())
                reply!!.writeNoException(); reply.writeString(result.toString()); return true
            }
        }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val header = LinearLayout(activity)
        val detail = LinearLayout(activity)
        val primary = LinearLayout(activity)
        val controls: ClusterThemeLiveControls
        init {
            bf.set(null, binder); vf.set(null, "45")
            controls = ClusterThemeLiveControls(activity, header, detail, { true }, {}, { false }, {}, dashboardContainer = primary,
                restartConnection = {
                    events += "authenticated-local-adb"
                    authenticationGate?.await(5, TimeUnit.SECONDS)
                    object : ClusterRestartConnection {
                        override fun dispatch() {
                            assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("cluster_restart_intent", 0).contains("filename"))
                            events += "ADB-OPEN-reboot:fission-all"
                            if (timeout) throw java.io.IOException("Disconnected")
                        }
                        override fun close() { events += "connection-close" }
                    }
                }, restartBoot = { boot })
            controls.setActive(true)
            waitFor { field("displayed").get(controls) != null }
        }
        fun dialog(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()?.takeIf { it.isShowing }
        fun waitFor(condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            do { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(5) } while (System.nanoTime() < until)
            assertTrue("Did not settle: $events", condition())
        }
        fun begin() {
            views(header).filterIsInstance<Button>().single { it.text == activity.getString(R.string.diag_cluster_rcc_restore) }.performClick()
            waitFor { dialog() != null && "verify-before-consent" in events && !field("restartPreparing").getBoolean(controls) }
        }
        fun accept() {
            val popup = checkNotNull(dialog())
            val parked = views(popup.window!!.decorView as ViewGroup).filterIsInstance<CheckBox>().single()
            assertFalse(popup.getButton(-1).isEnabled)
            parked.isChecked = true; popup.getButton(-1).performClick()
        }
        fun replaceBinder() { bf.set(null, Binder()) }
        fun clean(oem: Boolean = true, historical: Any? = null) {
            overrideReceipt = receipt().put("state", "CLEAN").put("rccDebt", false).put("oemObservedByUser", oem)
            if (historical != null) overrideReceipt!!.put("restartCompleted", historical)
        }
        fun poll() {
            waitFor { !(field("busy").get(null) as AtomicBoolean).get() && !(field("recoveryBusy").get(null) as AtomicBoolean).get() }
            (field("poll").get(controls) as Runnable).run()
            waitFor { !(field("busy").get(null) as AtomicBoolean).get() }
        }
        override fun close() {
            controls.close(); drain(); controller.pause().stop().destroy(); bf.set(null, oldBinder); vf.set(null, oldVersion)
        }
    }
    @Test fun `user consent authenticates then revalidates then persists before exactly one reboot service`() {
        Fixture().use { f ->
            f.begin(); assertEquals(listOf("verify-before-consent"), f.events)
            f.accept(); f.waitFor { "connection-close" in f.events }
            assertEquals(listOf("verify-before-consent", "authenticated-local-adb", "revalidate-after-consent", "ADB-OPEN-reboot:fission-all", "connection-close"), f.events)
            assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("cluster_restart_intent", 0).contains("filename"))
        }
    }
    @Test fun `cancel leaves preserved RCC and never opens a reboot transport`() {
        Fixture().use { f ->
            f.begin(); f.dialog()!!.getButton(-2).performClick(); f.waitFor { "cancel" in f.events }
            assertEquals(listOf("verify-before-consent", "cancel"), f.events)
        }
    }
    @Test fun `already restarted externally cancels consent and asks for read-only BYD verification`() {
        Fixture().use { f ->
            f.begin(); f.dialog()!!.getButton(-3).performClick()
            f.waitFor { "cancel" in f.events && f.dialog()?.getButton(-1)?.text == f.activity.getString(R.string.fs_verify_confirm) }
            assertEquals(listOf("verify-before-consent", "cancel"), f.events)
        }
    }
    @Test fun `unverified move never offers reboot consent`() {
        Fixture().use { f ->
            f.rejectProof = true; f.begin()
            assertEquals(f.activity.getString(android.R.string.ok), f.dialog()!!.getButton(-1).text)
            assertFalse(f.events.any { it.contains("ADB") || it.contains("authenticated") })
        }
    }
    @Test fun `original lookup reappearing during consent refuses reboot`() {
        Fixture().use { f ->
            f.begin(); f.rejectRevalidation = true; f.accept(); f.waitFor { "connection-close" in f.events }
            assertFalse("ADB-OPEN-reboot:fission-all" in f.events)
            assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("cluster_restart_intent", 0).contains("filename"))
        }
    }
    @Test fun `disconnect consumes persisted intent without replay`() {
        Fixture().use { f ->
            f.timeout = true; f.begin(); f.accept(); f.waitFor { "connection-close" in f.events }
            drain(); assertEquals(1, f.events.count { it == "ADB-OPEN-reboot:fission-all" })
            assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("cluster_restart_intent", 0).contains("filename"))
        }
    }
    @Test fun `page exit or replaced daemon invalidates an open confirmation`() {
        for (replace in listOf(false, true)) Fixture().use { f ->
            f.begin()
            if (replace) { f.replaceBinder(); f.accept() } else f.controls.setActive(false)
            drain(); assertFalse("ADB-OPEN-reboot:fission-all" in f.events)
        }
    }
    @Test fun `guided page shows one recovery action and no old test buttons`() {
        Fixture().use { f ->
            fun shown(group: ViewGroup): List<View> = (0 until group.childCount).map(group::getChildAt)
                .filter { it.visibility == View.VISIBLE }.flatMap { listOf(it) + if (it is ViewGroup) shown(it) else emptyList() }
            val recover = f.activity.getString(R.string.fs_recover_action)
            val buttons = shown(f.primary).filterIsInstance<Button>().map { it.text.toString() }
            assertEquals(1, buttons.count { it == recover })
            assertTrue(buttons.toString(), buttons.all { it == recover || it.startsWith(f.activity.getString(R.string.fs_details)) })
            val texts = shown(f.primary).filterIsInstance<TextView>().filter { it !is Button }.map { it.text.toString() }
            assertTrue(f.activity.getString(R.string.fs_recover_title) in texts)
            assertTrue(f.activity.getString(R.string.fs_recover_restart) in texts)
            assertTrue("No folded legacy controls remain", views(f.detail).isEmpty())
            assertTrue(views(f.header).filterIsInstance<Button>().single().text == f.activity.getString(R.string.diag_cluster_rcc_restore))
        }
    }
    @Test fun `failed preparation never retries from status polling`() {
        Fixture().use { f ->
            f.rejectProof = true; f.begin(); f.dialog()!!.dismiss()
            repeat(3) { f.poll() }
            assertEquals(1, f.events.count { it == "verify-before-consent" })
        }
    }
    @Test fun `page exit during delayed authentication prevents confirmation and ADB OPEN`() {
        Fixture().use { f ->
            val gate = CountDownLatch(1); f.authenticationGate = gate
            f.begin(); f.accept(); f.waitFor { "authenticated-local-adb" in f.events }
            f.controls.setActive(false); gate.countDown(); f.waitFor { "connection-close" in f.events }
            assertFalse("revalidate-after-consent" in f.events); assertFalse("ADB-OPEN-reboot:fission-all" in f.events)
        }
    }
    @Test fun `asynchronous CLEAN poll settles durable intent only after fresh correlated confirmation`() {
        Fixture().use { f ->
            val app = RuntimeEnvironment.getApplication()
            com.byd.dashcast.clusterlive.ClusterRestartIntent(app) { f.boot }.record(name, round)
            f.boot = "22222222-2222-2222-2222-222222222222"
            f.clean(); f.poll()
            f.waitFor { !app.getSharedPreferences("cluster_restart_intent", 0).contains("filename") }
        }
    }
    @Test fun `recovered CLEAN historical receipt closes crash gap but null or malformed receipt cannot`() {
        for (bad in listOf(JSONObject.NULL, JSONObject().put("round", 123))) Fixture().use { f ->
            val app = RuntimeEnvironment.getApplication()
            app.getSharedPreferences("cluster_restart_intent", 0).edit().clear().commit()
            com.byd.dashcast.clusterlive.ClusterRestartIntent(app) { f.boot }.record(name, round)
            val first = f.boot; f.boot = "22222222-2222-2222-2222-222222222222"
            f.clean(oem = false, historical = bad); f.poll(); drain()
            assertTrue(app.getSharedPreferences("cluster_restart_intent", 0).contains("filename"))
            f.clean(oem = false, historical = com.byd.dashcast.clusterlive.ClusterRestartCompletion(round, first, f.boot).json())
            f.poll(); f.waitFor { !app.getSharedPreferences("cluster_restart_intent", 0).contains("filename") }
        }
    }
}
