package com.byd.dashcast.proxy.daemon

/** Compatibility policy for recovering from BYD's poisoned FREEFORM display stack. */
object TaskLaunchRecovery {

    interface Operations {
        fun cleanDisplay(): String?
        fun launchPlain()
        fun pollTask(): Int
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
            !transcript.contains("EXCEPTION:")
    }

    /** The failed FREEFORM attempt creates a fresh zombie stack, so cleanup must run again. */
    @JvmStatic
    fun retryOnCleanDisplay(operations: Operations): Int {
        operations.cleanDisplay()
        operations.launchPlain()
        return operations.pollTask()
    }
}
