package com.byd.dashcast.app

import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.data.prefs.ClusterPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PRE-0 characterisation net — `BootDisplayCleanup` (audit AUD-006).
 *
 * This test freezes CURRENT behaviour, defects included. It is not a specification: it is a
 * seismograph. If a later change makes one of these assertions fail, that change altered
 * behaviour that something else relies on, and the failure is the signal to stop and think.
 *
 * What is pinned here and why it matters:
 *
 *  - The AUD-006 liveness guard. `session_cluster_pkgs` is rewritten by `ClusterSessionTracker`
 *    after every mutation, so while a projection is live it holds the packages currently ON the
 *    cluster. Running the cleanup then moves the driver's navigation off the cluster mid-drive.
 *    The guard keys on `ClusterService.isRunning()`; if anyone changes what that flag means, the
 *    AUD-006 fix silently stops protecting anything — and only this test would notice.
 *
 *  - The "no running task counts as cleaned" semantics. `moveTaskToDisplayZero` returns TRUE when
 *    it finds no task for a package ("already gone, skipping"), so the package is dropped from the
 *    persisted set. That is deliberate, but it is also the project's most common regression shape
 *    — a false success read from a query that returned nothing. Pinning it here means any future
 *    change to that reading is a visible, deliberate decision.
 *
 *    Note on coverage: the genuine partial-failure branch (a move that throws, which must KEEP the
 *    set for a retry) is NOT exercised. Robolectric provides a working `ActivityTaskManager`
 *    returning an empty task list, so the reflection never fails here. That branch stays uncovered
 *    and is called out rather than faked.
 *
 * Runs at SDK 29 = the project's `targetSdkVersion`, i.e. the API level the head units actually
 * run, rather than the higher `compileSdkVersion`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BootDisplayCleanupCharacterizationTest {

    // RuntimeEnvironment rather than androidx.test:core — one dependency less, and the
    // project stays buildable offline from the existing Gradle cache.
    private val ctx: android.content.Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        ClusterPrefs.clearSessionClusterPkgs(ctx)
        ClusterService.sIsRunning = false
    }

    @After
    fun restore() {
        // Static mutable state: never leak it into another test class.
        ClusterService.sIsRunning = false
        ClusterPrefs.clearSessionClusterPkgs(ctx)
    }

    @Test
    fun `cleanup is a no-op while the cluster service is alive`() {
        ClusterPrefs.setSessionClusterPkgs(ctx, setOf("ru.yandex.yandexnavi"))
        ClusterService.sIsRunning = true

        BootDisplayCleanup.cleanup(ctx)

        // Untouched: the guard returns before reading, moving or rewriting anything.
        assertEquals(setOf("ru.yandex.yandexnavi"), ClusterPrefs.getSessionClusterPkgs(ctx))
    }

    @Test
    fun `cleanup clears the set when no running task matches the packages`() {
        ClusterPrefs.setSessionClusterPkgs(ctx, setOf("com.example.nav", "com.example.media"))
        ClusterService.sIsRunning = false

        BootDisplayCleanup.cleanup(ctx)

        // Current behaviour, pinned as-is: with no matching task, moveTaskToDisplayZero reports
        // success ("already gone, skipping"), every package is considered handled, and the
        // persisted set is cleared. This is the "empty query reads as success" shape — deliberate
        // here, but the exact thing a later refactor could flip without noticing.
        assertTrue(ClusterPrefs.getSessionClusterPkgs(ctx).isEmpty())
    }

    @Test
    fun `cleanup on an empty set leaves the preference empty and does not throw`() {
        ClusterService.sIsRunning = false

        BootDisplayCleanup.cleanup(ctx)

        assertTrue(ClusterPrefs.getSessionClusterPkgs(ctx).isEmpty())
    }

    @Test
    fun `isRunning mirrors the flag the AUD-006 guard reads`() {
        // Pins the contract itself: the guard and the service must agree on one source of truth.
        ClusterService.sIsRunning = true
        assertTrue(ClusterService.isRunning())
        ClusterService.sIsRunning = false
        assertTrue(!ClusterService.isRunning())
    }
}
