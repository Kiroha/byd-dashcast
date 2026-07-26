package com.byd.dashcast.fission

/** Executes the required per-package Layout teardown order while isolating package failures. */
object FissionTeardownPlan {

    interface Operations {
        @Throws(Exception::class)
        fun moveToDisplay0(packageName: String): String?
        @Throws(Exception::class)
        fun forceStopAndWait(packageName: String): Boolean
        @Throws(Exception::class)
        fun releaseSlot(packageName: String)
        fun onStepError(packageName: String, step: String, error: Throwable)
    }

    @JvmStatic
    fun run(packages: Collection<String?>?, keepVirtualDisplays: Boolean, operations: Operations?) {
        if (packages == null || operations == null) return
        for (packageName in packages) {
            if (packageName.isNullOrEmpty()) continue
            try {
                operations.moveToDisplay0(packageName)
            } catch (error: Throwable) {
                operations.onStepError(packageName, "move", error)
            }
            try {
                if (!operations.forceStopAndWait(packageName)) {
                    operations.onStepError(packageName, "force-stop-verify",
                        IllegalStateException("process death not verified"))
                }
            } catch (error: Throwable) {
                operations.onStepError(packageName, "force-stop", error)
            }
            if (!keepVirtualDisplays) {
                try {
                    operations.releaseSlot(packageName)
                } catch (error: Throwable) {
                    operations.onStepError(packageName, "release", error)
                }
            }
        }
    }
}
