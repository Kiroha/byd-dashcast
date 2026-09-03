package com.byd.dashcast.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CAN re-activation cadence — AUD-003 follow-up.
 *
 * Context for whoever changes this. `isHudActive` used to be assigned only after
 * `CanBusController.setNaviActive(true)` returned, so on a car that refuses that register the flag
 * stayed false: the watchdog was never armed, `closeNavigation` short-circuited forever, and the
 * guidance writes further down `updateNavigation` kept going — an arrow nothing in the app could
 * clear. The flag now opens the session immediately and CAN acceptance is tracked separately, which
 * means the retry has to be rate-limited by something. This is that something.
 *
 * Why a pure function instead of driving the controller: activation needs a uid-2000 daemon and a
 * DiLink 3 car, the same wall `HudControllerLivenessTest` documents. The predicate does not.
 */
class HudActivationRetryTest {

    private val cadence = 5_000L

    @Test
    fun `an accepted activation is never retried`() {
        // The expensive mistake in the other direction: re-issuing CAN batches on a car that
        // already said yes, for the whole trip.
        assertFalse(HudController.shouldRetryActivation(true, 1_000_000L, 0L, cadence))
        assertFalse(HudController.shouldRetryActivation(true, 1_000_000L, 999_999L, cadence))
    }

    @Test
    fun `a refused activation is retried once the cadence has elapsed`() {
        assertTrue(HudController.shouldRetryActivation(false, 5_000L, 0L, cadence))
        assertTrue(HudController.shouldRetryActivation(false, 10_001L, 5_000L, cadence))
    }

    @Test
    fun `a refused activation is not retried before the cadence`() {
        // This is the case that matters: guidance frames arrive far faster than the cadence, and
        // the old code attempted activation on every one of them.
        assertFalse(HudController.shouldRetryActivation(false, 4_999L, 0L, cadence))
        assertFalse(HudController.shouldRetryActivation(false, 5_100L, 5_000L, cadence))
    }

    @Test
    fun `the boundary is inclusive so a slow frame does not skip a whole cadence`() {
        assertTrue(HudController.shouldRetryActivation(false, 5_000L, 0L, cadence))
    }

    @Test
    fun `a never-attempted activation is retried immediately`() {
        // The sentinel exists because 0 does not mean "never" — 0 is a real elapsedRealtime value,
        // the instant the head unit booted. This assertion is what caught that: with a 0 sentinel
        // it failed, and an attempt made in the first milliseconds of uptime would have had its
        // first retry silently delayed by a full cadence.
        assertTrue(HudController.shouldRetryActivation(false, 0L, HudController.NEVER_ATTEMPTED, cadence))
        assertTrue(HudController.shouldRetryActivation(false, 1L, HudController.NEVER_ATTEMPTED, cadence))
    }

    @Test
    fun `the sentinel cannot be reached by arithmetic overflow`() {
        // nowMs - Long.MIN_VALUE overflows, which is why the sentinel is compared and not
        // subtracted. If someone folds the branch back into the subtraction, this goes red.
        assertTrue(HudController.shouldRetryActivation(false, Long.MAX_VALUE, HudController.NEVER_ATTEMPTED, cadence))
    }
}
