package com.byd.dashcast.proxy.daemon;

/** Compatibility policy for recovering from BYD's poisoned FREEFORM display stack. */
public final class TaskLaunchRecovery {

    public interface Operations {
        String cleanDisplay();
        void launchPlain();
        int pollTask();
    }

    private TaskLaunchRecovery() {}

    public static boolean isStartFailure(String transcript) {
        if (transcript == null) return false;
        return transcript.contains("Error:")
                || transcript.contains("Exception occurred while executing");
    }

    public static boolean isFreeformStackFailure(String transcript) {
        if (transcript == null) return false;
        return transcript.contains("ActivityStack.getBounds()")
                || (transcript.contains("ActivityStack.onConfigurationChanged")
                    && transcript.contains("NullPointerException"));
    }

    public static boolean isSuccessful(String transcript) {
        if (transcript == null || transcript.isEmpty()) return false;
        return transcript.contains("FINISH: launchAndForce complete.")
                && !transcript.contains("FAIL: no task discovered")
                && !transcript.contains("EXCEPTION:");
    }

    /** The failed FREEFORM attempt creates a fresh zombie stack, so cleanup must run again. */
    public static int retryOnCleanDisplay(Operations operations) {
        operations.cleanDisplay();
        operations.launchPlain();
        return operations.pollTask();
    }
}
