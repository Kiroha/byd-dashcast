package com.byd.dashcast.ui.diag

import android.app.AlertDialog
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.byd.dashcast.R
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemePolicy
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
class ClusterRccRecoveryControlsTest {
    private val nonce = "a23127f52c8f482985cb18fcc48ec6b2"
    private val name = ClusterThemePolicy.PREFIX + nonce + ".rcc"
    private fun field(name: String) = ClusterThemeLiveControls::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun flag(name: String) = field(name).get(null) as AtomicBoolean
    private fun views(group: ViewGroup): List<View> = (0 until group.childCount).flatMap {
        val view = group.getChildAt(it); listOf(view) + if (view is ViewGroup) views(view) else emptyList()
    }
    private fun receipt(eligible: Boolean = false) = JSONObject().put("state", "QUARANTINE").put("phase", if (eligible) "restore-query" else "idle")
        .put("detail", if (eligible) "OEM resource ACK3 ints=[3, 0, 9008, 0, 0] RESOURCE_IS_SAME 0x2330" else "Recovered previous RCC session")
        .put("filename", name).put("variant", "full-surface-test").put("transport", "android-ivi").put("daemonUid", 2000)
        .put("mutated", true).put("uncertain", true).put("defaultQueried", false).put("markerObservedByUser", false)
        .put("themeObservedByUser", false).put("oemObservedByUser", false).put("rccDebt", true).put("retirementAvailable", eligible)
        .put("recoveryPending", false).put("nativeMode", JSONObject().put("state", "CLOSED").put("debt", false).put("pending", false))

    @Before @After fun resetLanes() {
        for (lane in listOf("work", "recovery")) {
            val done = CountDownLatch(1)
            (field(lane).get(null) as ExecutorService).execute { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        (field("reservation").get(null) as? ProjectionCommandSequencer.Session)?.let(ProjectionCommandBus::endSession)
        field("reservation").set(null, null); field("reservationFilename").set(null, ""); field("reservationDebt").setBoolean(null, false)
        for (key in listOf("busy", "recoveryBusy", "diagClaimed", "abortBeforeSend")) flag(key).set(false)
        (DiagActivity::class.java.getDeclaredField("sBusy").apply { isAccessible = true }.get(null) as AtomicBoolean).set(false)
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("rcc_cluster_operational_debt", 0).edit().clear().commit()
        app.getSharedPreferences("native_cluster_operational_debt", 0).edit().clear().commit()
    }
    private inner class Fixture(initial: JSONObject = receipt(), protocol: String = "40") : AutoCloseable {
        private val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        private val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        private val previous = bf.get(null); private val version = vf.get(null)
        @Volatile var current = initial
        var validity: IBinder? = null
        val operations = Collections.synchronizedList(mutableListOf<Int>())
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val op = data.readInt(); operations.add(op)
                when (op) {
                    0 -> Unit
                    13, 14 -> {
                        assertEquals(nonce, data.readString()); assertEquals(1, data.readInt()); assertEquals(1, data.readInt())
                        validity = data.readStrongBinder(); assertNotNull(validity)
                        current = if (op == 13) receipt().put("recoveryPending", true)
                        else receipt().put("phase", "carrier-retired").put("state", "WAITING_OEM_REINITIALIZATION")
                            .put("retirement", JSONObject().put("moved", true))
                    }
                    4 -> {
                        assertEquals(name, data.readString())
                        current = receipt().put("state", "CLEAN").put("rccDebt", false).put("defaultQueried", true)
                            .put("oemObservedByUser", true).put("rccSafeNonce", nonce)
                    }
                    else -> error("Unexpected recovery effect $op")
                }
                assertEquals(0, data.dataAvail())
                reply!!.writeNoException(); reply.writeString(current.toString()); return true
            }
        }
        val controller: org.robolectric.android.controller.ActivityController<DiagActivity>
        val app: DiagActivity
        val controls: ClusterThemeLiveControls
        val all: List<View>
        init {
            bf.set(null, binder); vf.set(null, protocol)
            controller = Robolectric.buildActivity(DiagActivity::class.java).setup(); app = controller.get()
            controls = DiagActivity::class.java.getDeclaredField("liveThemeControls").apply { isAccessible = true }.get(app) as ClusterThemeLiveControls
            all = views(app.findViewById(android.R.id.content))
        }
        fun dialog() = ShadowAlertDialog.getLatestAlertDialog()?.takeIf { it.isShowing }
        fun waitFor(condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(5) } while (System.nanoTime() < until)
            assertTrue("Recovery condition did not settle; operations=$operations", condition())
        }
        fun header() = all.filterIsInstance<Button>().first { it.parent !is android.widget.ScrollView &&
            it.text in listOf(app.getString(R.string.diag_cluster_rcc_restore), app.getString(R.string.diag_cluster_rcc_retire_button), app.getString(R.string.diag_cluster_rcc_verify_return)) }
        fun clickHeader() { header().performClick(); waitFor { dialog() != null } }
        fun confirm() {
            shadowOf(Looper.getMainLooper()).idle()
            val popup = checkNotNull(dialog())
            val checks = views(popup.window!!.decorView as ViewGroup).filterIsInstance<CheckBox>()
            assertEquals(2, checks.size); assertFalse(popup.getButton(-1).isEnabled)
            checks[0].isChecked = true; assertFalse(popup.getButton(-1).isEnabled); checks[1].isChecked = true
            assertTrue(popup.getButton(-1).isEnabled); popup.getButton(-1).performClick()
        }
        fun poll() {
            waitFor { !flag("busy").get() && !flag("recoveryBusy").get() }
            (field("poll").get(controls) as Runnable).run()
            waitFor { !flag("busy").get() }
        }
        fun checkLease() {
            val d = Parcel.obtain(); val r = Parcel.obtain()
            try {
                d.writeInterfaceToken("com.byd.dashcast.RccReservation")
                assertTrue(validity!!.transact(1, d, r, 0)); r.readException(); assertEquals(1, r.readInt())
            } finally { r.recycle(); d.recycle() }
        }
        fun replaceBinder() { bf.set(null, Binder()) }
        override fun close() {
            controller.pause().stop().destroy()
            for (lane in listOf("work", "recovery")) {
                val done = CountDownLatch(1); (field(lane).get(null) as ExecutorService).execute { done.countDown() }; assertTrue(done.await(5, TimeUnit.SECONDS))
            }
            shadowOf(Looper.getMainLooper()).idle(); bf.set(null, previous); vf.set(null, version)
        }
    }
    @Test fun `global Restore from home or ADAS opens Cluster and fresh IVI confirmation without legacy restore`() {
        for (adas in listOf(false, true)) Fixture().use { f ->
            if (adas) f.all.filterIsInstance<Button>().single { it.text.toString().startsWith("ADAS\n") }.performClick()
            f.clickHeader()
            assertTrue(f.operations.isNotEmpty()); assertTrue(f.operations.all { it == 0 })
            assertTrue(f.all.filterIsInstance<Button>().single { it.text.toString().startsWith("Read current connected daemon") }.isShown)
            f.confirm(); f.waitFor { 13 in f.operations }
            assertFalse(2 in f.operations); assertFalse(14 in f.operations)
            f.checkLease()
            assertNull(RuntimeEnvironment.getApplication().getSharedPreferences("rcc_cluster_operational_debt", 0).getString("nonce", null))
        }
    }
    @Test fun `queued recovered restore holds reservation until SAME produces automatic actionable popup`() {
        Fixture().use { f ->
            f.clickHeader(); f.confirm(); f.waitFor { 13 in f.operations && !flag("recoveryBusy").get() }
            f.poll(); assertNull(f.dialog()); f.checkLease()
            f.current = receipt(eligible = true); f.poll(); f.waitFor { f.dialog() != null }
            val popup = f.dialog()!!
            assertEquals(f.app.getString(R.string.diag_cluster_rcc_retire_button), popup.getButton(-1).text)
            assertTrue(popup.findViewById<TextView>(android.R.id.message).text.contains("RESOURCE_IS_SAME"))
            assertEquals(f.app.getString(R.string.diag_cluster_rcc_retire_button), f.header().text)
            assertEquals(listOf(13), f.operations.filter { it != 0 }); f.checkLease()
            popup.getButton(-1).performClick()
            assertTrue(f.dialog()!!.getButton(-1).text == f.app.getString(R.string.diag_cluster_native_send))
            assertFalse(14 in f.operations)
            f.confirm(); f.waitFor { 14 in f.operations && f.dialog()?.findViewById<TextView>(android.R.id.message)?.text?.contains(f.app.getString(R.string.diag_cluster_rcc_retired_waiting)) == true }
            assertEquals(listOf(13, 14), f.operations.filter { it != 0 }); assertTrue(f.current.getBoolean("rccDebt")); f.checkLease()
        }
    }
    @Test fun `cancelled SAME is deduplicated during polling but explicit header reopens current next step`() {
        Fixture(receipt(true)).use { f ->
            f.clickHeader(); val popup = f.dialog()!!; popup.cancel()
            f.poll(); assertNull(f.dialog()); assertTrue(f.operations.all { it == 0 })
            f.clickHeader(); assertEquals(f.app.getString(R.string.diag_cluster_rcc_retire_button), f.dialog()!!.getButton(-1).text)
            assertTrue(f.operations.all { it == 0 })
        }
    }
    @Test fun `expired SAME and replaced daemon cannot use an old prepare action`() {
        for (replaced in listOf(false, true)) Fixture(receipt(true)).use { f ->
            f.clickHeader(); val old = f.dialog()!!
            if (replaced) f.replaceBinder() else { f.current = receipt().put("phase", "restore-query"); f.poll() }
            old.getButton(-1).performClick()
            assertFalse(old.isShowing); assertTrue(f.operations.all { it == 0 })
        }
    }
    @Test fun `pausing invalidates automatic recovery guidance without any command`() {
        Fixture(receipt(true)).use { f ->
            f.clickHeader(); val old = f.dialog()!!; f.controller.pause()
            assertFalse(old.isShowing); old.getButton(-1).performClick()
            assertTrue(f.operations.all { it == 0 })
        }
    }
    @Test fun `helper readiness failure without sealed eligibility is a failure popup with no preparation or OEM observation`() {
        Fixture(receipt().put("phase", "restore-query").put("detail", "Restore unknown: Helper readiness unavailable before admission; no GO sent")).use { f ->
            f.all.filterIsInstance<Button>().single { it.text.toString().startsWith(f.app.getString(R.string.diag_cluster_page_title) + "\n") }.performClick()
            f.waitFor { f.dialog() != null }
            val popup = f.dialog()!!
            assertEquals(f.app.getString(android.R.string.ok), popup.getButton(-1).text)
            assertTrue(popup.findViewById<TextView>(android.R.id.message).text.contains("Helper readiness unavailable"))
            assertTrue(f.operations.all { it == 0 }); assertTrue(f.current.getBoolean("rccDebt"))
            assertTrue(ClusterThemeDiagnostics.snapshot(f.app).contains("restore-guidance"))
        }
    }
    @Test fun `mismatched local and current daemon session refuses recovery visibly`() {
        RuntimeEnvironment.getApplication().getSharedPreferences("rcc_cluster_operational_debt", 0).edit().putString("nonce", "e".repeat(32)).commit()
        Fixture().use { f ->
            f.clickHeader(); assertTrue(f.dialog()!!.findViewById<TextView>(android.R.id.message).text.contains(f.app.getString(R.string.diag_cluster_rcc_restore_mismatch)))
            assertTrue(f.operations.all { it == 0 })
            assertEquals("e".repeat(32), RuntimeEnvironment.getApplication().getSharedPreferences("rcc_cluster_operational_debt", 0).getString("nonce", null))
        }
    }
    @Test fun `automatic SAME with a mismatched local nonce offers no preparation`() {
        RuntimeEnvironment.getApplication().getSharedPreferences("rcc_cluster_operational_debt", 0).edit().putString("nonce", "e".repeat(32)).commit()
        Fixture(receipt(true)).use { f ->
            f.all.filterIsInstance<Button>().single { it.text.toString().startsWith(f.app.getString(R.string.diag_cluster_page_title) + "\n") }.performClick()
            f.waitFor { f.dialog() != null }
            assertEquals(f.app.getString(android.R.string.ok), f.dialog()!!.getButton(-1).text)
            assertTrue(f.dialog()!!.findViewById<TextView>(android.R.id.message).text.contains(f.app.getString(R.string.diag_cluster_rcc_restore_mismatch)))
            f.dialog()!!.getButton(-1).performClick(); assertTrue(f.operations.all { it == 0 })
        }
    }
    @Test fun `unknown journal profile or transport refuses instead of claiming no recovery or sending legacy restore`() {
        for (status in listOf(receipt().put("variant", "UNKNOWN").put("transport", "strict-renderer"), receipt().put("transport", "UNKNOWN"))) Fixture(status).use { f ->
            f.clickHeader()
            assertTrue(f.dialog()!!.findViewById<TextView>(android.R.id.message).text.contains(f.app.getString(R.string.diag_cluster_rcc_restore_mismatch)))
            assertTrue(f.operations.all { it == 0 }); assertTrue(f.current.getBoolean("rccDebt"))
        }
    }
    @Test fun `older daemon refuses the new recovery flow visibly without sending legacy restore`() {
        Fixture(protocol = "39").use { f ->
            f.clickHeader()
            assertTrue(f.dialog()!!.findViewById<TextView>(android.R.id.message).text.contains("40"))
            assertTrue(f.operations.all { it == 0 })
        }
    }
    @Test fun `old protocol eligible SAME page entry offers reconnect guidance rather than preparation`() {
        Fixture(receipt(true), protocol = "39").use { f ->
            f.all.filterIsInstance<Button>().single { it.text.toString().startsWith(f.app.getString(R.string.diag_cluster_page_title) + "\n") }.performClick()
            f.waitFor { f.dialog() != null }
            assertEquals(f.app.getString(android.R.string.ok), f.dialog()!!.getButton(-1).text)
            assertEquals(f.app.getString(R.string.diag_cluster_rcc_restore_protocol), f.dialog()!!.findViewById<TextView>(android.R.id.message).text)
            f.dialog()!!.getButton(-1).performClick(); assertTrue(f.operations.all { it == 0 })
        }
    }
    @Test fun `fresh explicit recovery after eligibility expiry offers identical SAME result again once`() {
        Fixture(receipt(true)).use { f ->
            f.clickHeader(); f.dialog()!!.cancel(); f.current = receipt().put("phase", "restore-query")
            f.poll(); f.waitFor { f.dialog() != null }; f.dialog()!!.cancel()
            f.clickHeader(); f.confirm(); f.waitFor { 13 in f.operations && !flag("recoveryBusy").get() }
            f.current = receipt(true); f.poll(); f.waitFor { f.dialog() != null }
            assertEquals(f.app.getString(R.string.diag_cluster_rcc_retire_button), f.dialog()!!.getButton(-1).text)
            f.dialog()!!.cancel(); f.poll(); assertNull(f.dialog()); assertEquals(listOf(13), f.operations.filter { it != 0 })
        }
    }
    @Test fun `fresh default receipt opens OEM observation without offering preparation or sending restore`() {
        Fixture(receipt().put("state", "DEFAULT_QUERIED").put("defaultQueried", true).put("iviRecoverySettled", true)).use { f ->
            f.all.filterIsInstance<Button>().single { it.text.toString().startsWith(f.app.getString(R.string.diag_cluster_page_title) + "\n") }.performClick()
            f.waitFor { f.dialog() != null }
            assertEquals(f.app.getString(R.string.diag_cluster_observation_yes), f.dialog()!!.getButton(-1).text)
            assertTrue(f.operations.all { it == 0 }); f.dialog()!!.getButton(-1).performClick()
            f.waitFor { 4 in f.operations }; assertEquals(listOf(4), f.operations.filter { it != 0 })
        }
    }
}
