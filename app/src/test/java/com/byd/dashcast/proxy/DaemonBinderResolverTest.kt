package com.byd.dashcast.proxy

import android.os.Binder
import android.os.IBinder
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * The surface daemon's re-acquisition path — AUD-009.
 *
 * The daemon itself cannot be stood up under Robolectric: it is a separate uid-2000 process
 * registered in a real ServiceManager. What can be pinned is the part that made the old code
 * dangerous rather than merely wrong — the throttle that lets the cluster touch path call this on
 * every MotionEvent without paying a reflective lookup each time.
 *
 * These cases used to assert only that the answer was null, which it is on this environment
 * whatever the throttle does — the function returns null both when it throttled and when the
 * daemon is absent. Every one of them passed with the throttle removed. So the lookup is now
 * driven through a seam that returns a real Binder and counts its calls: the two nulls become
 * distinguishable, and the interval is pinned to behaviour rather than to its own constant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DaemonBinderResolverTest {

    private var lookups = 0
    private var fake: IBinder? = null

    @Before
    fun freshThrottle() {
        // Robolectric starts elapsedRealtime() near zero, and the throttle's initial timestamp is
        // zero too — so at the base clock the FIRST call is already inside the window. Step past
        // it before every case; forgetting this is exactly how the previous suite ended up
        // asserting nothing.
        ShadowSystemClock.advanceBy(Duration.ofMillis(DaemonBinderResolver.REACQUIRE_MIN_INTERVAL_MS + 1))
        DaemonBinderResolver.resetReacquireThrottleForTesting()
        lookups = 0
        fake = Binder()
        DaemonBinderResolver.lookupForTesting = { lookups++; fake }
    }

    @After
    fun removeSeam() {
        DaemonBinderResolver.lookupForTesting = null
        DaemonBinderResolver.resetReacquireThrottleForTesting()
    }

    @Test
    fun `the first call goes through to the lookup and returns what it finds`() {
        assertSame(fake, DaemonBinderResolver.reacquireSurfaceBinder("first"))
        assertEquals(1, lookups)
    }

    @Test
    fun `a second attempt inside the window does not reach the lookup`() {
        // Not an optimisation. The busiest caller runs on every MotionEvent, so without this a
        // driver dragging a finger across a cluster whose daemon is gone pays a reflection plus a
        // ServiceManager lookup per event.
        DaemonBinderResolver.reacquireSurfaceBinder("first")
        assertNull("throttled, so no binder", DaemonBinderResolver.reacquireSurfaceBinder("second"))
        assertEquals("and no lookup was performed", 1, lookups)
    }

    @Test
    fun `many attempts inside the window still cost exactly one lookup`() {
        // The shape of a real drag.
        repeat(50) { DaemonBinderResolver.reacquireSurfaceBinder("motion event") }
        assertEquals(1, lookups)
    }

    @Test
    fun `the window reopens once the interval has passed`() {
        DaemonBinderResolver.reacquireSurfaceBinder("first")
        ShadowSystemClock.advanceBy(Duration.ofMillis(DaemonBinderResolver.REACQUIRE_MIN_INTERVAL_MS + 1))
        assertNotNull("the daemon may have respawned by now",
            DaemonBinderResolver.reacquireSurfaceBinder("after the window"))
        assertEquals(2, lookups)
    }

    @Test
    fun `just inside the boundary is still throttled`() {
        // Pins the comparison itself, which is the half a round number cannot check.
        DaemonBinderResolver.reacquireSurfaceBinder("first")
        ShadowSystemClock.advanceBy(Duration.ofMillis(DaemonBinderResolver.REACQUIRE_MIN_INTERVAL_MS - 1))
        assertNull(DaemonBinderResolver.reacquireSurfaceBinder("just inside"))
        assertEquals(1, lookups)
    }

    @Test
    fun `the window is a second, which is the respawn-detection budget`() {
        // Long enough that the touch path is not doing lookups, short enough that a daemon which
        // respawns is picked up on the next gesture rather than on the next projection.
        assertEquals(1_000L, DaemonBinderResolver.REACQUIRE_MIN_INTERVAL_MS)
    }

    @Test
    fun `resetting the throttle reopens the path without waiting`() {
        DaemonBinderResolver.reacquireSurfaceBinder("first")
        DaemonBinderResolver.resetReacquireThrottleForTesting()
        assertNotNull(DaemonBinderResolver.reacquireSurfaceBinder("after reset"))
        assertEquals("the reset must let a real lookup happen", 2, lookups)
    }

    @Test
    fun `an absent daemon is null and does not throw`() {
        // The path has to be safe to call from a touch handler on a device where the daemon never
        // started; throwing there would take the gesture down with it.
        fake = null
        assertNull(DaemonBinderResolver.reacquireSurfaceBinder("no daemon"))
        assertEquals("and it really did look", 1, lookups)
    }
}
