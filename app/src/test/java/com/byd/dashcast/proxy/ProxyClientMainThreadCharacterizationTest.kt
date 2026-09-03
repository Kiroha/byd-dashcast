package com.byd.dashcast.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PRE-0 characterisation net — `ProxyClient` main-thread fail-fast (audit ANR family).
 *
 * Freezes CURRENT behaviour, not a specification.
 *
 * The property pinned here is the one the project paid for twice: **a typed verb called with no
 * live daemon must never block the caller's thread waiting for a ~23 s ADB bootstrap.** The guard
 * lives in `reconnectUnlessMainThread()`, which refuses to reconnect synchronously on the main
 * looper (and on any thread that opted out via `setNonBlockingReconnect`), kicks the reconnect to
 * a background executor and lets the verb fail immediately.
 *
 * Waves V5 and V6 rewrite exactly this area — `ProxyClient` mixes a connection state machine, a
 * retry policy and a 33-verb facade, and AUD-176/AUD-177 propose to split it. If that split
 * reintroduces a synchronous reconnect on the main thread, the head unit gets an ANR while the
 * driver is looking at the screen. Nothing else in the suite would catch it.
 *
 * Robolectric runs the test body on the main looper, which is what makes this observable at all.
 *
 * ── HONEST LIMIT OF THIS NET, MEASURED ─────────────────────────────────────────────────────────
 * The timing assertions below do NOT, on their own, protect the ANR guard. Verified by sabotage:
 * neutralising the main-looper check in `reconnectUnlessMainThread()` leaves every test here
 * GREEN, because in a JVM `attemptReconnect()` fails instantly (no adbd, no ServiceManager) — the
 * ~23 s bootstrap that makes the guard necessary only exists on the head unit.
 *
 * So what IS protected here: that these verbs throw `ProxyException` rather than succeeding or
 * throwing something else with no daemon, and that `invalidateBinder` stays a safe no-op. The
 * timing budget only catches a path that is slow even in a JVM.
 *
 * Making the guard itself testable requires a seam in `ProxyClient` (an injectable reconnect
 * strategy), which is production refactoring and therefore out of scope for a test commit. That
 * seam belongs to AUD-176 in wave V5, which splits this class anyway. Until then, the guard is
 * protected by review, not by this suite — recorded so nobody mistakes green for covered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProxyClientMainThreadCharacterizationTest {

    /** Generous: the point is "does not block for tens of seconds", not a micro-benchmark. */
    private val failFastBudgetMs = 4_000L

    @Test
    fun `no daemon is connected in a bare unit-test process`() {
        // Baseline for every other assertion here: there is no binder in ServiceManager.
        assertFalse(ProxyClient.isConnected())
    }

    @Test
    fun `a typed verb fails fast on the main looper instead of blocking on a cold daemon`() {
        assertTrue(
            "Robolectric must run this on the main looper or the guard is not exercised",
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper())

        val startedAt = System.nanoTime()
        try {
            ProxyClient.runShell("echo characterisation")
            // Not an assumption about which outcome is correct: if a future change makes this
            // succeed without a daemon, that is a behaviour change worth stopping on.
            fail("expected ProxyException with no daemon connected")
        } catch (expected: ProxyClient.ProxyException) {
            // Current behaviour: fails, and fails immediately.
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        // Weak assertion — see the honest-limit note in the class KDoc. Catches a path that is
        // slow even in a JVM; does NOT prove the main-looper guard is present.
        assertTrue(
            "runShell blocked the main thread for ${elapsedMs}ms",
            elapsedMs < failFastBudgetMs)
    }

    @Test
    fun `a background thread that opted out of blocking reconnect also fails fast`() {
        // ShellGateway sets this on its serial worker so a cold daemon cannot stall the queue.
        val done = CountDownLatch(1)
        var elapsedMs = -1L
        var threw = false
        Thread({
            ProxyClient.setNonBlockingReconnect(true)
            val startedAt = System.nanoTime()
            try {
                ProxyClient.runShell("echo characterisation")
            } catch (e: ProxyClient.ProxyException) {
                threw = true
            } finally {
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                // ThreadLocal opt-out: clear it so the flag cannot leak to a pooled thread.
                ProxyClient.setNonBlockingReconnect(false)
                done.countDown()
            }
        }, "characterisation-optout").start()

        assertTrue("worker did not finish", done.await(30, TimeUnit.SECONDS))
        assertTrue("expected ProxyException with no daemon connected", threw)
        assertTrue(
            "opted-out worker blocked for ${elapsedMs}ms — the opt-out is gone",
            elapsedMs in 0 until failFastBudgetMs)
    }

    @Test
    fun `invalidateBinder is safe to call when nothing is connected`() {
        // Pinned because AUD-009 (wave V2) rewrites the binder-invalidation sites: whatever the
        // split between the two daemons becomes, calling this on an empty cache must stay a no-op
        // and must not throw.
        ProxyClient.invalidateBinder("characterisation")
        assertFalse(ProxyClient.isConnected())
    }
}
