package com.byd.dashcast.app

import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.infrastructure.task.TaskLocation
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
 * Pins the liveness gate plus the privileged location/move decision used after a real boot.
 *
 * What is pinned here and why it matters:
 *
 *  - The AUD-006 liveness guard. `session_cluster_pkgs` is rewritten by `ClusterSessionTracker`
 *    after every mutation, so while a projection is live it holds the packages currently ON the
 *    cluster. Running the cleanup then moves the driver's navigation off the cluster mid-drive.
 *    The guard keys on `ClusterService.isRunning()`; if anyone changes what that flag means, the
 *    AUD-006 fix silently stops protecting anything — and only this test would notice.
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
    fun `an absent task is complete without a move`() {
        val operations = FakeOperations(TaskLocation.absent())

        assertTrue(BootDisplayCleanup.cleanPackage("com.example.nav", operations))
        assertEquals(0, operations.moves)
    }

    @Test
    fun `cleanup clears only packages with a verified safe final state`() {
        ClusterPrefs.setSessionClusterPkgs(ctx, setOf("com.example.nav", "com.example.unknown"))
        val operations = PerPackageOperations(
            mapOf(
                "com.example.nav" to ArrayDeque(listOf(TaskLocation.found(42, 3), TaskLocation.found(42, 0))),
                "com.example.unknown" to ArrayDeque(listOf(TaskLocation.unknown()))
            )
        )

        BootDisplayCleanup.cleanup(ctx, operations)

        assertEquals(setOf("com.example.unknown"), ClusterPrefs.getSessionClusterPkgs(ctx))
    }

    @Test
    fun `a task already on display zero is complete without a move`() {
        val operations = FakeOperations(TaskLocation.found(42, 0))

        assertTrue(BootDisplayCleanup.cleanPackage("com.example.nav", operations))
        assertEquals(0, operations.moves)
    }

    @Test
    fun `a task on a secondary display is cleared only after verified landing`() {
        val operations = FakeOperations(
            TaskLocation.found(42, 3),
            TaskLocation.found(42, 0)
        )

        assertTrue(BootDisplayCleanup.cleanPackage("com.example.nav", operations))
        assertEquals(1, operations.moves)
    }

    @Test
    fun `unknown location or an unverified move stays pending`() {
        val unknown = FakeOperations(TaskLocation.unknown())
        assertTrue(!BootDisplayCleanup.cleanPackage("com.example.nav", unknown))
        assertEquals(0, unknown.moves)

        val didNotLand = FakeOperations(
            TaskLocation.found(42, 3),
            TaskLocation.found(42, 3)
        )
        assertTrue(!BootDisplayCleanup.cleanPackage("com.example.nav", didNotLand))
        assertEquals(1, didNotLand.moves)
    }

    @Test
    fun `a disappearing task is complete even when the move reported failure`() {
        val operations = FakeOperations(
            TaskLocation.found(42, 3),
            TaskLocation.absent(),
            moveResult = false
        )

        assertTrue(BootDisplayCleanup.cleanPackage("com.example.nav", operations))
        assertEquals(1, operations.moves)
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

    private class FakeOperations(
        vararg locations: TaskLocation,
        private val moveResult: Boolean = true
    ) : BootDisplayCleanup.Operations {
        private val queue = ArrayDeque(locations.toList())
        var moves = 0

        override fun locate(packageName: String): TaskLocation =
            if (queue.isEmpty()) TaskLocation.unknown() else queue.removeFirst()

        override fun moveToDisplayZero(packageName: String): Boolean {
            moves++
            return moveResult
        }
    }

    private class PerPackageOperations(
        private val locations: Map<String, ArrayDeque<TaskLocation>>
    ) : BootDisplayCleanup.Operations {
        override fun locate(packageName: String): TaskLocation =
            locations[packageName]?.removeFirstOrNull() ?: TaskLocation.unknown()

        override fun moveToDisplayZero(packageName: String): Boolean = true
    }
}
