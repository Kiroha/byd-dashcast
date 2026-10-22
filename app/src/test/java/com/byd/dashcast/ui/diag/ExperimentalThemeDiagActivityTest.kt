package com.byd.dashcast.ui.diag

import android.os.Binder
import android.os.Parcel
import android.view.ViewGroup
import android.widget.Button
import com.byd.dashcast.proxy.ProxyClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExperimentalThemeDiagActivityTest {
    private val busy = DiagActivity::class.java.getDeclaredField("sBusy").apply { isAccessible = true }
        .get(null) as AtomicBoolean

    private fun buttons(group: ViewGroup): List<Button> = (0 until group.childCount).flatMap {
        val child = group.getChildAt(it)
        when (child) {
            is Button -> listOf(child)
            is ViewGroup -> buttons(child)
            else -> emptyList()
        }
    }

    @Test fun `local button respects busy gate and reports without daemon calls`() {
        val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val previous = binderField.get(null)
        val calls = AtomicInteger()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls.incrementAndGet()
                error("Local fixture must not call a daemon")
            }
        })
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val button = buttons(controller.get().findViewById(android.R.id.content))
                .single { it.text == "Verify experimental theme (local only)" }
            busy.set(true)
            button.performClick()
            assertTrue(busy.get())
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            busy.set(false)
            DiagActivity::class.java.getDeclaredField("reportingPairInFlight").apply { isAccessible = true }
                .setBoolean(controller.get(), true)
            button.performClick()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (busy.get() && System.nanoTime() < deadline) Thread.sleep(2)
            assertFalse("Worker must release shared diagnostic gate", busy.get())
            ShadowLooper.idleMainLooper()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            val message = shadowOf(dialog).message.toString()
            assertTrue(message, message.contains("Bundled files: PASS"))
            assertTrue(message.contains("Qt rendering: NOT TESTED"))
            assertTrue(message.contains("OEM loading/access: NOT ESTABLISHED"))
            assertTrue(button.isEnabled)
            val pairing = DiagActivity::class.java.getDeclaredField("reportingPairBtn").apply { isAccessible = true }
                .get(controller.get()) as Button
            assertFalse("Theme completion must preserve pairing in flight", pairing.isEnabled)
            assertEquals(0, calls.get())
        } finally {
            controller.destroy()
            busy.set(false)
            binderField.set(null, previous)
        }
    }
}
