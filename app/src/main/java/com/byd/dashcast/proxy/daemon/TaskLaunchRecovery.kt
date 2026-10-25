package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.infrastructure.task.TaskLocation

/** Compatibility policy for recovering from BYD's poisoned FREEFORM display stack. */
object TaskLaunchRecovery {

    enum class Outcome { SUCCESS, FAILED, FRAMEWORK_BLOCKED, PLACEMENT_UNCONFIRMED }

    const val FRAMEWORK_BLOCKED_MARKER = "FRAMEWORK-BLOCKED: clean plain launch failed"
    const val PLACEMENT_UNCONFIRMED_MARKER = "PLACEMENT-UNCONFIRMED: primary recovery"
    const val RECOVERED_PRIMARY_MARKER = "RECOVERED-PRIMARY:"

    interface Operations {
        fun cleanDisplay(): String?
        fun launchPlain()
        fun pollTask(): TaskLocation
        fun isCurrent(): Boolean = true
    }

    @JvmStatic
    fun isStartFailure(transcript: String?): Boolean {
        if (transcript == null) return false
        return transcript.contains("Error:") ||
            transcript.contains("Exception occurred while executing")
    }

    @JvmStatic
    fun isFreeformStackFailure(transcript: String?): Boolean {
        if (transcript == null) return false
        return transcript.contains("ActivityStack.getBounds()") ||
            (transcript.contains("ActivityStack.onConfigurationChanged") &&
                transcript.contains("NullPointerException"))
    }

    @JvmStatic
    fun isSuccessful(transcript: String?): Boolean {
        if (transcript.isNullOrEmpty()) return false
        return transcript.contains("FINISH: launchAndForce complete.") &&
            !transcript.contains("FAIL: no task discovered") &&
            !transcript.contains(FRAMEWORK_BLOCKED_MARKER) &&
            !transcript.contains(PLACEMENT_UNCONFIRMED_MARKER) &&
            !transcript.contains("EXCEPTION:")
    }

    @JvmStatic
    fun outcome(transcript: String?): Outcome = when {
        isSuccessful(transcript) -> Outcome.SUCCESS
        // A verified primary must never fall through to a destructive relaunch because an
        // earlier cleanup left an EXCEPTION diagnostic in the combined transcript.
        transcript?.contains(RECOVERED_PRIMARY_MARKER) == true -> Outcome.PLACEMENT_UNCONFIRMED
        transcript?.contains(PLACEMENT_UNCONFIRMED_MARKER) == true -> Outcome.PLACEMENT_UNCONFIRMED
        transcript?.contains(FRAMEWORK_BLOCKED_MARKER) == true -> Outcome.FRAMEWORK_BLOCKED
        else -> Outcome.FAILED
    }

    fun hasRecoveredPrimary(transcript: String?): Boolean =
        transcript?.contains(RECOVERED_PRIMARY_MARKER) == true &&
            transcript.contains("FINISH: launchAndForce complete.")

    /** A final unreadable query cannot prove that the framework discarded the launch. */
    @JvmStatic
    fun isBlockedAfterPlainLaunch(transcript: String?, location: TaskLocation): Boolean =
        isFreeformStackFailure(transcript) && location.status == TaskLocation.Status.ABSENT

    @JvmStatic
    fun verifyPlacement(outcome: Outcome, location: TaskLocation, displayId: Int,
                        recoveredPrimary: Boolean = false): Outcome {
        if (outcome != Outcome.SUCCESS) return outcome
        return when (location.matchDisplay(displayId)) {
            TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY -> Outcome.SUCCESS
            TaskLocation.DisplayMatch.ABSENT ->
                if (recoveredPrimary) Outcome.PLACEMENT_UNCONFIRMED else Outcome.FAILED
            TaskLocation.DisplayMatch.ON_OTHER_DISPLAY,
            TaskLocation.DisplayMatch.UNKNOWN -> Outcome.PLACEMENT_UNCONFIRMED
        }
    }

    /** The failed FREEFORM attempt creates a fresh zombie stack, so cleanup must run again. */
    @JvmStatic
    fun retryOnCleanDisplay(operations: Operations): TaskLocation {
        if (!operations.isCurrent()) return TaskLocation.unknown()
        operations.cleanDisplay()
        if (!operations.isCurrent()) return TaskLocation.unknown()
        operations.launchPlain()
        if (!operations.isCurrent()) return TaskLocation.unknown()
        val location = operations.pollTask()
        return if (operations.isCurrent()) location else TaskLocation.unknown()
    }

    /** Only a complete independent lookup can authorize a second destructive launch. */
    fun retryIfAbsent(location: TaskLocation, current: () -> Boolean,
                      retry: () -> TaskLocation): TaskLocation {
        if (!current()) return TaskLocation.unknown()
        if (location.status != TaskLocation.Status.ABSENT) return location
        val result = retry()
        return if (current()) result else TaskLocation.unknown()
    }
}
