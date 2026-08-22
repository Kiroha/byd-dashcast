package com.byd.dashcast.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which answers from `canListenStart` mean the HUD push-feedback listener is actually live.
 *
 * The keeper used to latch "armed" on having MADE the call, and only unlatch when the call THREW.
 * The daemon does not throw when it is merely not ready — it answers with a status string. So
 * "register-in-flight" or "ERR register timeout", which is the normal shape of a daemon still
 * coming up, left the listener unarmed for the life of the process with no exception anywhere,
 * and every bug report from that car carried no HUD state at all.
 *
 * The strings here are the real return values of
 * `com.byd.dashcast.proxy.daemon.CanFeedbackListener.startSetting`. If that verb ever gains a new
 * answer, this is where the two must be reconciled.
 */
class HudListenerArmVerdictTest {

    @Test
    fun `a fresh registration is armed`() {
        assertTrue(ProxyKeeperService.isListenerArmed(
            "registered (registerListener(listener), EventSink, looper thread) + instrument[all features]"))
    }

    @Test
    fun `an existing registration is armed`() {
        assertTrue(ProxyKeeperService.isListenerArmed("already-registered"))
    }

    /**
     * The case the bug was made of: a truthful, non-throwing "not yet". Retrying is safe because
     * startSetting short-circuits on both sRegistered and sRegisterInFlight, so it cannot register
     * a duplicate device listener.
     */
    @Test
    fun `a registration still in flight is not armed`() {
        assertFalse(ProxyKeeperService.isListenerArmed("register-in-flight"))
    }

    @Test
    fun `every error answer is not armed`() {
        listOf(
            "ERR register timeout",
            "ERR getInstance() null",
            "ERR ClassNotFoundException: android.hardware.bydauto.setting.BYDAutoSettingDevice",
            "ERR(outer) IllegalStateException",
            "no-result",
        ).forEach {
            assertFalse("must not count as armed: $it", ProxyKeeperService.isListenerArmed(it))
        }
    }

    @Test
    fun `a null answer is not armed`() {
        // What the keeper substitutes when the verb threw.
        assertFalse(ProxyKeeperService.isListenerArmed(null))
    }

    @Test
    fun `an empty answer is not armed`() {
        assertFalse(ProxyKeeperService.isListenerArmed(""))
    }
}
