package com.byd.dashcast.cluster

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ClusterTaskMoveOwnershipTest {

    @After
    fun resetOwnership() {
        ClusterService.releaseTaskMoveOwnership()
    }

    @Test
    fun `cleanup move and service startup cannot own tasks concurrently`() {
        ClusterService.releaseTaskMoveOwnership()
        val moveEntered = CountDownLatch(1)
        val allowMoveToFinish = CountDownLatch(1)
        val startupAttempted = CountDownLatch(1)
        val startupClaimed = CountDownLatch(1)

        val moveThread = Thread {
            ClusterService.runTaskMoveWhileStopped {
                moveEntered.countDown()
                allowMoveToFinish.await(2, TimeUnit.SECONDS)
                true
            }
        }
        val startupThread = Thread {
            startupAttempted.countDown()
            ClusterService.claimTaskMoveOwnership()
            startupClaimed.countDown()
        }

        moveThread.start()
        assertTrue(moveEntered.await(1, TimeUnit.SECONDS))
        startupThread.start()
        assertTrue(startupAttempted.await(1, TimeUnit.SECONDS))
        assertFalse("startup must wait while the cleanup Binder move owns the monitor",
            startupClaimed.await(100, TimeUnit.MILLISECONDS))

        allowMoveToFinish.countDown()
        moveThread.join(1_000)
        assertTrue(startupClaimed.await(1, TimeUnit.SECONDS))
        startupThread.join(1_000)
        assertTrue(ClusterService.isRunning())
    }

    @Test
    fun `cleanup move is refused after service ownership is claimed`() {
        ClusterService.claimTaskMoveOwnership()
        val moved = AtomicBoolean(false)

        val accepted = ClusterService.runTaskMoveWhileStopped {
            moved.set(true)
            true
        }

        assertFalse(accepted)
        assertFalse(moved.get())
    }

    @Test
    fun `service releases task ownership only after display teardown`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/cluster").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val source = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/cluster/ClusterService.java"
        ).readText()
        val teardown = source.substringAfter("public void onDestroy()")
            .substringBefore("// ─────────────────────────────────────────────────────────────────────────")

        assertTrue(teardown.indexOf("releaseTaskMoveOwnership()") >
            teardown.indexOf("mDisplayHelper.stop()"))
    }

    @Test
    fun `failed synchronous service initialization releases task ownership`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/cluster").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val source = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/cluster/ClusterService.java"
        ).readText()
        val onCreate = source.substringAfter("public void onCreate()")
            .substringBefore("private void initializeAfterOwnershipClaim()")

        assertTrue(onCreate.indexOf("claimTaskMoveOwnership()") in
            0 until onCreate.indexOf("initializeAfterOwnershipClaim()"))
        assertTrue(onCreate.substringAfter("finally")
            .substringAfter("if (!initialized)")
            .contains("releaseTaskMoveOwnership()"))
    }
}