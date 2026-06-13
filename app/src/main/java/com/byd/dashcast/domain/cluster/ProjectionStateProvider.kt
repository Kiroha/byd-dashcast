package com.byd.dashcast.domain.cluster

/**
 * Domain interface exposing the minimal projection state needed by satellite activities
 * (FissionActivity, FissionLayoutEditorActivity) without forcing a direct dependency
 * on the concrete `ClusterService` class.
 *
 * Implementing classes: `ClusterService` (production), test stubs.
 */
interface ProjectionStateProvider {

    /** @return `true` if cluster projection is currently active. */
    fun isProjectionActive(): Boolean

    /**
     * Stops projection without re-sending ADB teardown commands (caller already sent them),
     * then invokes `onStopped` on the main thread when the service is about to stop.
     *
     * @param onStopped runnable fired after projection teardown; may be `null`.
     */
    fun stopProjectionIfActive(onStopped: Runnable?)
}
