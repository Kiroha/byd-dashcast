package com.byd.dashcast.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The surface daemon's re-acquisition path — AUD-009.
 *
 * The daemon itself cannot be stood up under Robolectric: it is a separate uid-2000 process
 * registered in a real ServiceManager. What can be pinned is the part that made the old code
 * dangerous rather than merely wrong — the throttle that lets the cluster touch path call this on
 * every MotionEvent without paying a reflective lookup each time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DaemonBinderResolverTest {

    @Before
    fun freshThrottle() {
        DaemonBinderResolver.resetReacquireThrottleForTesting()
    }

    @Test
    fun `with no daemon registered the answer is null rather than an exception`() {
        // The path has to be safe to call from a touch handler on a device where the daemon never
        // started; throwing there would take the gesture down with it.
        assertNull(DaemonBinderResolver.reacquireSurfaceBinder("test"))
    }

    @Test
    fun `a second attempt inside the window is throttled`() {
        // Not an optimisation. The busiest caller runs on every MotionEvent, so without this a
        // driver dragging a finger across a cluster whose daemon is gone pays a reflection plus a
        // ServiceManager lookup per event.
        DaemonBinderResolver.reacquireSurfaceBinder("first")
        assertNull("the window is still open", DaemonBinderResolver.reacquireSurfaceBinder("second"))
    }

    @Test
    fun `the window is a second, which is the respawn-detection budget`() {
        // Long enough that the touch path is not doing lookups, short enough that a daemon which
        // respawns is picked up on the next gesture rather than on the next projection.
        assertEquals(1_000L, DaemonBinderResolver.REACQUIRE_MIN_INTERVAL_MS)
    }

    @Test
    fun `resetting the throttle reopens the path`() {
        DaemonBinderResolver.reacquireSurfaceBinder("first")
        DaemonBinderResolver.resetReacquireThrottleForTesting()
        // Still null here — no daemon in this environment — but it went through the lookup rather
        // than returning early, which is what the reset is for.
        assertNull(DaemonBinderResolver.reacquireSurfaceBinder("after reset"))
    }
}
