package com.byd.dashcast.domain.cluster;

/**
 * Domain interface exposing the minimal projection state needed by satellite activities
 * (FissionActivity, FissionLayoutEditorActivity) without forcing a direct dependency
 * on the concrete {@code ClusterService} class.
 *
 * Implementing classes: {@code ClusterService} (production), test stubs.
 */
public interface ProjectionStateProvider {

    /** @return {@code true} if cluster projection is currently active. */
    boolean isProjectionActive();

    /**
     * Stops projection without re-sending ADB teardown commands (caller already sent them),
     * then invokes {@code onStopped} on the main thread when the service is about to stop.
     *
     * @param onStopped runnable fired after projection teardown; may be {@code null}.
     */
    void stopProjectionIfActive(Runnable onStopped);
}
