package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.os.Binder
import android.os.Parcel
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.byd.dashcast.R
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.proxy.ClusterThemeSession
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterVisualConfirmationTest {
    private val nonce = "a".repeat(32)
    private val filename = "Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_${"c".repeat(32)}.rcc"
    private fun status(native: Boolean = false, rcc: Boolean = false, restore: Boolean = false, variant: String = "full-surface-test") = JSONObject()
        .put("state", if (rcc) if (restore) "DEFAULT_QUERIED" else "AWAITING_OBSERVATION" else "IDLE")
        .put("phase", if (restore) "restore" else "idle").put("detail", "Test receipt").put("daemonUid", 2000)
        .put("filename", if (rcc) filename else "").put("variant", variant).put("mutated", rcc)
        .put("defaultQueried", restore).put("markerObservedByUser", false).put("themeObservedByUser", false).put("oemObservedByUser", false)
        .put("nativeMode", JSONObject().put("nonce", nonce).put("state", if (native) "RETURNED" else "IDLE")
            .put("command", if (native) 18 else 0).put("pending", false).put("debt", native).put("normalSettled", native).put("admittedElapsedMs", if (native) 10 else 0))

    private class Fixture(initial: JSONObject) : AutoCloseable {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val app = activity.get()
        val header = LinearLayout(app)
        val body = LinearLayout(app)
        val prompts = ClusterObservationPrompts(app)
        val ops = Collections.synchronizedList(mutableListOf<Int>())
        val filenames = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var receipt = initial
        @Volatile var holdStatus: CountDownLatch? = null
        val statusEntered = CountDownLatch(1)
        private val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        private val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        private val previous = bf.get(null)
        private val version = vf.get(null)
        private val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                check(code == ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val op = data.readInt(); ops.add(op)
                if (op == 0) { statusEntered.countDown(); holdStatus?.await(5, TimeUnit.SECONDS) }
                else if (op == 10) {
                    check(data.readString() == receipt.getJSONObject("nativeMode").getString("nonce"))
                    receipt = JSONObject(receipt.toString()).apply {
                        getJSONObject("nativeMode").put("state", "CLOSED").put("debt", false).put("pending", false)
                    }
                } else if (op in listOf(3, 4, 6)) {
                    filenames.add(data.readString().orEmpty())
                    receipt = JSONObject(receipt.toString()).apply { put(when (op) { 3 -> "markerObservedByUser"; 4 -> "oemObservedByUser"; else -> "themeObservedByUser" }, true) }
                } else error("Unexpected effect $op")
                check(data.dataAvail() == 0)
                reply!!.writeNoException(); reply.writeString(receipt.toString()); return true
            }
        }
        val native: ClusterNativeFullControls
        val rcc: ClusterThemeLiveControls
        init {
            bf.set(null, binder); vf.set(null, "34")
            app.getSharedPreferences("native_cluster_operational_debt", 0).edit().clear().commit()
            native = ClusterNativeFullControls(app, header, body, { true }, {}, {}, prompts)
            rcc = ClusterThemeLiveControls(app, header, body, { true }, {}, { false }, {}, nativeStatus = { text, s -> native.receivedStatus(text, s) }, prompts = prompts)
        }
        fun activate() { prompts.setVisible(true); native.setActive(true); rcc.setActive(true) }
        fun pause() { prompts.setVisible(false); native.setActive(false); rcc.setActive(false) }
        fun currentDialog(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()?.takeIf { it.isShowing }
        fun waitFor(test: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do { shadowOf(Looper.getMainLooper()).idle(); if (test()) return; Thread.sleep(5) } while (System.nanoTime() < until)
            assertTrue("Condition did not settle; ops=$ops", test())
        }
        fun poll() {
            waitFor { !(ClusterThemeLiveControls::class.java.getDeclaredField("busy").apply { isAccessible = true }.get(null) as AtomicBoolean).get() }
            val field = ClusterThemeLiveControls::class.java.getDeclaredField("poll").apply { isAccessible = true }
            (field.get(rcc) as Runnable).run()
        }
        fun replaceBinder() { bf.set(null, Binder()) }
        override fun close() {
            holdStatus?.countDown(); pause(); native.close(); rcc.close()
            waitFor { !(ClusterThemeLiveControls::class.java.getDeclaredField("busy").apply { isAccessible = true }.get(null) as AtomicBoolean).get() }
            (ClusterThemeLiveControls::class.java.getDeclaredField("diagClaimed").apply { isAccessible = true }.get(null) as AtomicBoolean).set(false)
            activity.pause().stop().destroy(); bf.set(null, previous); vf.set(null, version)
        }
    }
    private fun buttons(root: ViewGroup): List<Button> = (0 until root.childCount).flatMap {
        val view = root.getChildAt(it)
        (if (view is Button) listOf(view) else emptyList()) + (if (view is ViewGroup) buttons(view) else emptyList())
    }
    @Test fun `normal return automatically asks and yes sends only local nonce bound observation`() {
        Fixture(status(native = true)).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }
            assertEquals(f.app.getString(R.string.diag_cluster_observe_normal_yes), f.currentDialog()!!.getButton(-1).text)
            f.currentDialog()!!.getButton(-1).performClick()
            f.waitFor { f.ops.contains(10) }
            assertEquals(listOf(10), f.ops.filter { it != 0 }); assertFalse(f.receipt.getJSONObject("nativeMode").getBoolean("debt"))
            assertTrue(ClusterThemeDiagnostics.snapshot(f.app).contains("yes-user-observation"))
        }
    }
    @Test fun `no and unknown preserve native debt and do not repeat every status poll`() {
        Fixture(status(native = true)).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }
            f.currentDialog()!!.getButton(-2).performClick()
            f.poll(); f.waitFor { f.ops.count { it == 0 } >= 2 }
            assertNull(f.currentDialog()); assertTrue(f.receipt.getJSONObject("nativeMode").getBoolean("debt"))
            buttons(f.body).first { it.text == f.app.getString(R.string.diag_cluster_observation_again) }.performClick()
            assertNotNull(f.currentDialog()); f.currentDialog()!!.getButton(-3).performClick()
            f.poll(); f.waitFor { f.ops.count { it == 0 } >= 3 }
            assertNull(f.currentDialog()); assertEquals(0, f.ops.count { it == 10 })
        }
    }
    @Test fun `native Full observation describes projection with bands and never closes debt`() {
        Fixture(status(native = true).apply { getJSONObject("nativeMode").put("command", 16).put("normalSettled", false) }).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }
            assertEquals(f.app.getString(R.string.diag_cluster_observe_full_yes), f.currentDialog()!!.getButton(-1).text)
            f.currentDialog()!!.getButton(-1).performClick()
            assertTrue(f.receipt.getJSONObject("nativeMode").getBoolean("debt")); assertTrue(f.ops.all { it == 0 })
        }
    }
    @Test fun `replaced Binder refuses old popup without repinning or any observation`() {
        Fixture(status(native = true)).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }
            val old = f.currentDialog()!!
            f.replaceBinder(); old.getButton(-1).performClick()
            assertFalse(old.isShowing); assertEquals(0, f.ops.count { it != 0 })
        }
    }
    @Test fun `new nonce invalidates old popup and cannot be confirmed with its stale button`() {
        Fixture(status(native = true)).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }; val old = f.currentDialog()!!
            f.receipt = status(native = true).apply { getJSONObject("nativeMode").put("nonce", "b".repeat(32)) }
            f.poll(); f.waitFor { !old.isShowing && f.currentDialog() != null }
            old.getButton(-1).performClick(); assertEquals(0, f.ops.count { it == 10 })
        }
    }
    @Test fun `pause and resume never offer cached normal receipt before fresh current status`() {
        Fixture(status(native = true)).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }; f.pause(); assertNull(f.currentDialog())
            f.receipt = status(native = true).apply { getJSONObject("nativeMode").put("pending", true).put("normalSettled", false) }
            f.holdStatus = CountDownLatch(1); f.activate()
            shadowOf(Looper.getMainLooper()).idle(); assertNull(f.currentDialog())
            f.holdStatus!!.countDown(); f.waitFor { f.ops.count { it == 0 } >= 2 }
            shadowOf(Looper.getMainLooper()).idle(); assertNull(f.currentDialog()); assertEquals(0, f.ops.count { it != 0 })
        }
    }
    @Test fun `RCC theme observation is automatic and survives repeated passive polling`() {
        Fixture(status(rcc = true)).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }; val popup = f.currentDialog()!!
            f.poll(); f.waitFor { f.ops.count { it == 0 } >= 2 }
            assertSame(popup, f.currentDialog()); assertTrue(popup.isShowing)
            popup.getButton(-1).performClick(); f.waitFor { f.ops.contains(6) }
            assertEquals(listOf(filename), f.filenames); assertEquals(listOf(6), f.ops.filter { it != 0 })
        }
    }
    @Test fun `RCC original observation is not offered before restore selection and never sends restore`() {
        Fixture(status(rcc = true, restore = true)).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }
            f.currentDialog()!!.getButton(-1).performClick(); f.waitFor { f.ops.contains(4) }
            assertEquals(listOf(4), f.ops.filter { it != 0 }); assertEquals(listOf(filename), f.filenames)
        }
    }
    @Test fun `RCC marker observation uses marker operation and changed file expires positive`() {
        Fixture(status(rcc = true, variant = "marker")).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }
            f.receipt = status(rcc = true, variant = "marker").put("filename", filename.replace("cccc", "dddd"))
            f.currentDialog()!!.getButton(-1).performClick()
            f.waitFor { ClusterThemeDiagnostics.snapshot(f.app).contains("Visual confirmation expired") }
            assertTrue(f.ops.all { it == 0 })
        }
    }
    @Test fun `local native debt explains blocked RCC click even without a status receipt`() {
        Fixture(status()).use { f ->
            f.prompts.setVisible(true)
            ClusterNativeProjectionDebt.begin(f.app, nonce)
            buttons(f.body).single { it.text == f.app.getString(R.string.diag_cluster_rcc_theme_button) }.performClick()
            assertNotNull(f.currentDialog()); assertEquals(f.app.getString(android.R.string.ok), f.currentDialog()!!.getButton(-1).text)
            f.currentDialog()!!.getButton(-1).performClick()
            assertTrue(f.ops.isEmpty()); assertTrue(ClusterNativeProjectionDebt.blocked(f.app))
            ClusterNativeProjectionDebt.clear(f.app, nonce)
        }
    }
    @Test fun `cancel keeps debt and double positive records only one observation`() {
        Fixture(status(native = true)).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }
            f.currentDialog()!!.cancel(); assertTrue(f.receipt.getJSONObject("nativeMode").getBoolean("debt"))
            assertEquals(0, f.ops.count { it == 10 })
            f.prompts.reopen(); shadowOf(Looper.getMainLooper()).idle(); val yes = f.currentDialog()!!.getButton(-1)
            yes.performClick(); yes.performClick()
            f.waitFor { f.ops.contains(10) }; assertEquals(1, f.ops.count { it == 10 })
            assertTrue(ClusterThemeDiagnostics.snapshot(f.app).contains("response=cancel"))
        }
    }
    @Test fun `restoration in flight offers nothing then fresh default selection asks for OEM observation`() {
        Fixture(status(rcc = true, restore = true).put("state", "RESTORING").put("defaultQueried", false)).use { f ->
            f.activate(); f.waitFor { f.ops.isNotEmpty() }
            shadowOf(Looper.getMainLooper()).idle(); assertNull(f.currentDialog())
            f.receipt = status(rcc = true, restore = true)
            f.poll(); f.waitFor { f.currentDialog() != null }
            assertEquals(f.app.getString(R.string.diag_cluster_observation_yes), f.currentDialog()!!.getButton(-1).text)
            assertTrue(f.ops.all { it == 0 })
        }
    }
    @Test fun `native return popup survives fresh status from another wrapper of the same pinned Binder`() {
        Fixture(status(native = true)).use { f ->
            f.activate(); f.waitFor { f.currentDialog() != null }; val popup = f.currentDialog()!!
            val other = ClusterThemeSession.pin(f.app)
            f.native.receivedStatus(other.status(), other)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(popup, f.currentDialog()); assertTrue(popup.isShowing)
        }
    }

    @Test fun `closed daemon nonce with remaining matching app debt asks and reconciles with idempotent op10 only`() {
        Fixture(status(native = true).apply { getJSONObject("nativeMode").put("state", "CLOSED").put("debt", false) }).use { f ->
            ClusterNativeProjectionDebt.begin(f.app, nonce)
            f.activate(); f.waitFor { f.currentDialog() != null }
            f.currentDialog()!!.getButton(-1).performClick()
            f.waitFor { !ClusterNativeProjectionDebt.blocked(f.app) }
            assertEquals(listOf(10), f.ops.filter { it != 0 })
        }
    }

}
