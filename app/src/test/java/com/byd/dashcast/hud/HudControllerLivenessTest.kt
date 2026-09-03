package com.byd.dashcast.hud

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The HUD liveness signal — AUD-003.
 *
 * What this can pin is narrow, and worth saying plainly: activating the HUD needs CAN writes
 * through the uid-2000 daemon, so the interesting case — a live HUD kept alive across an identical
 * re-post — belongs on a car. The dedup state machine that decides when to signal lives inside a
 * NotificationListenerService and is not reachable either.
 *
 * What is reachable is the one mistake this design invites. `noteNavFrameSeen` exists so liveness
 * can be refreshed *without* going through `updateNavigation`; someone who later "simplifies" it
 * into an update would restore the CAN write this call was created to avoid, and would do it on
 * the notification dispatch thread, on every re-post. These cases go red if that happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HudControllerLivenessTest {

    @Test
    fun `noting a frame never lights the HUD by itself`() {
        // The call means "what is displayed is still current", not "display this". If it could
        // activate the HUD, a re-post arriving after closeNavigation would resurrect an arrow the
        // watchdog had just and correctly removed.
        assertFalse("precondition: nothing has opened the HUD", HudController.INSTANCE.isHudActive)
        HudController.INSTANCE.noteNavFrameSeen()
        assertFalse("must still be inactive", HudController.INSTANCE.isHudActive)
    }

    @Test
    fun `noting a frame on an inactive HUD is safe to call repeatedly`() {
        // It runs on the system notification dispatch thread on every re-post — several times a
        // second while guiding. Throwing there would take the nav listener down with it.
        repeat(50) { HudController.INSTANCE.noteNavFrameSeen() }
        assertFalse(HudController.INSTANCE.isHudActive)
    }
}
