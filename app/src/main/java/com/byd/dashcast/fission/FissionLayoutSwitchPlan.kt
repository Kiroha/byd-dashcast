package com.byd.dashcast.fission

/** Applies a layout switch without sacrificing the currently owned slots on a start failure. */
object FissionLayoutSwitchPlan {

    interface Operations {
        @Throws(Exception::class)
        fun start(packageName: String)

        @Throws(Exception::class)
        fun rollback(packageName: String)

        @Throws(Exception::class)
        fun stop(packageName: String)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun run(
        currentlyOwned: Collection<String?>?,
        requested: Collection<String?>?,
        operations: Operations,
    ) {
        val original = linkedSetOf<String>()
        for (packageName in currentlyOwned.orEmpty()) {
            if (!packageName.isNullOrEmpty()) original += packageName
        }
        val target = linkedSetOf<String>()
        for (packageName in requested.orEmpty()) {
            if (!packageName.isNullOrEmpty()) target += packageName
        }
        val active = original.toMutableSet()
        val started = mutableListOf<String>()

        try {
            for (packageName in target) {
                if (!active.add(packageName)) continue
                operations.start(packageName)
                started += packageName
            }
        } catch (startError: Exception) {
            for (packageName in started.asReversed()) {
                try {
                    operations.rollback(packageName)
                } catch (rollbackError: Exception) {
                    startError.addSuppressed(rollbackError)
                }
            }
            throw startError
        }

        // Commit only after every new package started. Until this point the old layout remains
        // intact, so a failed replacement can be rolled back without destroying prior ownership.
        for (packageName in original) {
            if (!target.contains(packageName)) operations.stop(packageName)
        }
    }
}