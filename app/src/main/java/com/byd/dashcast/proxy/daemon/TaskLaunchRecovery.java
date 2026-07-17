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

    /** The failed FREEFORM attempt creates a fresh zombie stack, so cleanup must run again. */
    public static int retryOnCleanDisplay(Operations operations) {
        operations.cleanDisplay();
        operations.launchPlain();
        return operations.pollTask();
    }
}
