package com.byd.dashcast.fission;

import java.util.Collection;

/** Executes the required per-package Layout teardown order while isolating package failures. */
public final class FissionTeardownPlan {

    public interface Operations {
        String moveToDisplay0(String packageName) throws Exception;
        boolean forceStopAndWait(String packageName) throws Exception;
        void releaseSlot(String packageName) throws Exception;
        void onStepError(String packageName, String step, Throwable error);
    }

    private FissionTeardownPlan() {}

    public static void run(Collection<String> packages, boolean keepVirtualDisplays,
                           Operations operations) {
        if (packages == null || operations == null) return;
        for (String packageName : packages) {
            if (packageName == null || packageName.isEmpty()) continue;
            try {
                operations.moveToDisplay0(packageName);
            } catch (Throwable error) {
                operations.onStepError(packageName, "move", error);
            }
            try {
                if (!operations.forceStopAndWait(packageName)) {
                    operations.onStepError(packageName, "force-stop-verify",
                            new IllegalStateException("process death not verified"));
                }
            } catch (Throwable error) {
                operations.onStepError(packageName, "force-stop", error);
            }
            if (!keepVirtualDisplays) {
                try {
                    operations.releaseSlot(packageName);
                } catch (Throwable error) {
                    operations.onStepError(packageName, "release", error);
                }
            }
        }
    }
}
