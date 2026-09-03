package com.byd.dashcast.fission

import org.junit.Assert.assertEquals
import org.junit.Test

class FissionTeardownPlanTest {

    @Test
    fun teardownOrdersMoveForceStopThenReleaseForEveryPackage() {
        val events = mutableListOf<String>()
        val operations = RecordingOperations(events)

        FissionTeardownPlan.run(listOf("com.waze", "org.schabi.newpipe"), false, operations)
        events += "complete"

        assertEquals(
            listOf(
                "move:com.waze", "force:com.waze", "release:com.waze",
                "move:org.schabi.newpipe", "force:org.schabi.newpipe", "release:org.schabi.newpipe",
                "complete"
            ),
            events
        )
    }

    @Test
    fun precreatedSlotsRemainButAppsAreStillMovedAndKilled() {
        val events = mutableListOf<String>()

        FissionTeardownPlan.run(listOf("com.waze"), true, RecordingOperations(events))

        assertEquals(listOf("move:com.waze", "force:com.waze"), events)
    }

    @Test
    fun failureForOnePackageDoesNotSkipRemainingPackages() {
        val events = mutableListOf<String>()
        val operations = object : FissionTeardownPlan.Operations {
            override fun moveToDisplay0(packageName: String): String {
                events += "move:$packageName"
                if (packageName == "com.waze") throw IllegalStateException("move failed")
                return "OK"
            }

            override fun forceStopAndWait(packageName: String): Boolean {
                events += "force:$packageName"
                return true
            }

            override fun releaseSlot(packageName: String) {
                events += "release:$packageName"
            }

            override fun onStepError(packageName: String, step: String, error: Throwable) {
                events += "error:$packageName:$step"
            }
        }

        FissionTeardownPlan.run(listOf("com.waze", "org.schabi.newpipe"), false, operations)

        assertEquals(
            listOf(
                "move:com.waze", "error:com.waze:move", "force:com.waze", "release:com.waze",
                "move:org.schabi.newpipe", "force:org.schabi.newpipe", "release:org.schabi.newpipe"
            ),
            events
        )
    }

    @Test
    fun unverifiedForceStopIsReportedBeforeSlotRelease() {
        val events = mutableListOf<String>()
        val operations = object : FissionTeardownPlan.Operations {
            override fun moveToDisplay0(packageName: String): String {
                events += "move:$packageName"
                return "OK"
            }

            override fun forceStopAndWait(packageName: String): Boolean {
                events += "force:$packageName"
                return false
            }

            override fun releaseSlot(packageName: String) {
                events += "release:$packageName"
            }

            override fun onStepError(packageName: String, step: String, error: Throwable) {
                events += "error:$packageName:$step"
            }
        }

        FissionTeardownPlan.run(listOf("com.waze"), false, operations)

        assertEquals(
            listOf(
                "move:com.waze", "force:com.waze",
                "error:com.waze:force-stop-verify", "release:com.waze"
            ),
            events
        )
    }

    @Test
    fun releaseFailuresAreReturnedAsRetryableOwnership() {
        val operations = object : FissionTeardownPlan.Operations {
            override fun moveToDisplay0(packageName: String) = "OK"
            override fun forceStopAndWait(packageName: String) = true
            override fun releaseSlot(packageName: String) {
                if (packageName == "stuck.pkg") throw IllegalStateException("binder died")
            }
            override fun onStepError(packageName: String, step: String, error: Throwable) = Unit
        }

        val unreleased = FissionTeardownPlan.run(
            listOf("released.pkg", "stuck.pkg"), false, operations)

        assertEquals(setOf("stuck.pkg"), unreleased)
    }

    private class RecordingOperations(private val events: MutableList<String>) :
        FissionTeardownPlan.Operations {
        override fun moveToDisplay0(packageName: String): String {
            events += "move:$packageName"
            return "OK"
        }

        override fun forceStopAndWait(packageName: String): Boolean {
            events += "force:$packageName"
            return true
        }

        override fun releaseSlot(packageName: String) {
            events += "release:$packageName"
        }

        override fun onStepError(packageName: String, step: String, error: Throwable) {
            events += "error:$packageName:$step"
        }
    }
}
