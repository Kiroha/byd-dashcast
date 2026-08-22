package com.byd.dashcast.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-006's discriminator, pinned.
 *
 * Both wrong answers are visible to the driver and neither shows up in a log they would read:
 * treat a re-delivery as a boot and the cleanup yanks their projected app off the cluster
 * mid-drive; treat a real boot as a re-delivery and last session's app stays stranded on a
 * VirtualDisplay that may still be alive.
 */
class BootCleanupPolicyTest {

    @Test
    fun `a genuine boot is cleaned up`() {
        // What the receiver sees seconds after a real cold start.
        assertTrue(BootCleanupPolicy.shouldCleanup(isReplace = false, uptimeMs = 20_000L))
    }

    @Test
    fun `an ACC-on re-delivery is not`() {
        // The observed case: this receiver ran 25 minutes into a 15-hour-old boot.
        val fifteenHours = 15L * 60 * 60 * 1000
        assertFalse(BootCleanupPolicy.shouldCleanup(isReplace = false, uptimeMs = fifteenHours))
    }

    @Test
    fun `the window is inclusive at the boundary and closed just past it`() {
        val w = BootCleanupPolicy.BOOT_CLEANUP_WINDOW_MS
        assertTrue(BootCleanupPolicy.shouldCleanup(false, w))
        assertFalse(BootCleanupPolicy.shouldCleanup(false, w + 1))
    }

    /**
     * The exemption, and the reason it exists: our process was just replaced at an arbitrary
     * uptime, so the uptime tells us nothing — but apps really can be stranded on the cluster.
     */
    @Test
    fun `a package replacement is cleaned up at any uptime`() {
        assertTrue(BootCleanupPolicy.shouldCleanup(isReplace = true, uptimeMs = 20_000L))
        assertTrue(BootCleanupPolicy.shouldCleanup(isReplace = true, uptimeMs = 15L * 3600 * 1000))
    }

    @Test
    fun `the window is the value AUD-006 chose`() {
        // Not a tautology: it leaves room for a slow head-unit boot while staying orders of
        // magnitude below the observed re-deliveries. Moving it is a decision, not a tidy-up.
        assertTrue(BootCleanupPolicy.BOOT_CLEANUP_WINDOW_MS == 180_000L)
    }
}
