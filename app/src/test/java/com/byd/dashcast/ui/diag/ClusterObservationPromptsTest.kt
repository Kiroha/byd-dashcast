package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.os.Binder
import android.content.DialogInterface
import android.os.Message
import com.byd.dashcast.R
import com.byd.dashcast.proxy.ClusterThemeSession
import com.byd.dashcast.proxy.ProxyClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterObservationPromptsTest {
    private fun fixture(test: (Activity, ClusterObservationPrompts, ClusterThemeSession) -> Unit) {
        val bf = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val vf = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val oldBinder = bf.get(null); val oldVersion = vf.get(null)
        val c = Robolectric.buildActivity(Activity::class.java).setup()
        val prompts = ClusterObservationPrompts(c.get())
        try {
            bf.set(null, Binder()); vf.set(null, "41")
            prompts.setVisible(true)
            test(c.get(), prompts, ClusterThemeSession.pin(c.get()))
        } finally {
            prompts.setVisible(false); c.pause().stop().destroy()
            bf.set(null, oldBinder); vf.set(null, oldVersion)
        }
    }
    private fun question(key: String, session: ClusterThemeSession, current: () -> Boolean = { true }, answer: () -> Unit = {}) =
        ClusterObservationPrompts.Question(key, 90, session, R.string.diag_cluster_observe_oem_title,
            R.string.diag_cluster_observe_oem_message, R.string.diag_cluster_observation_yes, current, answer)
    private fun popup(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()?.takeIf { it.isShowing }

    @Test fun `yes detaches before callback invalidates lane so same intermediate receipt does not ask twice`() = fixture { _, prompts, session ->
        var answers = 0
        val q = question("same-default-receipt", session) { answers++; prompts.update("rcc", null) }
        prompts.update("rcc", q); ShadowLooper.idleMainLooper()
        val first = checkNotNull(popup())
        val delayedDismiss = ReflectionHelpers.getField<Message>(first, "mDismissMessage").obj as DialogInterface.OnDismissListener
        first.setOnDismissListener(null) // Model Android delivering OnDismiss after the answer callback/poll.
        first.getButton(-1).performClick()
        prompts.update("rcc", q) // An intermediate DEFAULT_QUERIED poll before cleanup reaches CLEAN.
        ShadowLooper.idleMainLooper()
        assertEquals(1, answers); assertNull(popup())
        first.getButton(-1).performClick(); assertEquals(1, answers)
        prompts.update("rcc", question("new-default-receipt", session))
        ShadowLooper.idleMainLooper()
        val next = checkNotNull(popup()); assertNotSame(first, next)
        delayedDismiss.onDismiss(first)
        assertTrue(next.isShowing) // The first popup's delayed dismissal cannot detach this one.
    }

    @Test fun `manual reopen remains possible after failed answer no unknown and stale invalidation`() = fixture { _, prompts, session ->
        var current = true
        val q = question("retryable-receipt", session, { current }) {
            prompts.update("rcc", null)
            throw IllegalStateException("Fixture answer failure")
        }
        prompts.update("rcc", q); ShadowLooper.idleMainLooper()
        assertThrows(IllegalStateException::class.java) { popup()!!.getButton(-1).performClick() }
        prompts.update("rcc", q); ShadowLooper.idleMainLooper(); assertNull(popup())
        for (answer in listOf(-2, -3)) {
            prompts.reopen(); ShadowLooper.idleMainLooper(); assertNotNull(popup())
            popup()!!.getButton(answer).performClick(); ShadowLooper.idleMainLooper()
            prompts.update("rcc", q); assertNull(popup())
        }
        prompts.reopen(); ShadowLooper.idleMainLooper(); val stale = checkNotNull(popup())
        current = false; stale.getButton(-1).performClick(); ShadowLooper.idleMainLooper(); assertNull(popup())
        current = true; prompts.update("rcc", q); ShadowLooper.idleMainLooper(); assertNotNull(popup())
    }
}
