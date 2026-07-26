package com.byd.dashcast.proxy.daemon;

import com.byd.dashcast.infrastructure.task.TaskLocation;

import java.util.List;

/** Pure timing/decision policy for post-launch fission task guardians. */
final class FissionWatchdogPolicy {
    static final long POLL_INTERVAL_MS = 500L;
    static final int INITIAL_GUARD_POLLS = 60;       // 30 s
    static final int POST_REANCHOR_GUARD_POLLS = 30; // 15 s
    static final int MAX_POLLS = 180;                // 90 s hard bound

    enum Action { WAIT, REANCHOR, COMPLETE }

    private int completionPoll = INITIAL_GUARD_POLLS;

    /** Prefer any known task outside the slot; otherwise keep the newest query ordering. */
    static TaskLocation selectTask(List<TaskLocation> locations, int targetDisplayId) {
        if (locations == null) throw new IllegalArgumentException("locations required");
        TaskLocation expected = null;
        TaskLocation unknown = null;
        for (TaskLocation location : locations) {
            if (location == null) continue;
            TaskLocation.DisplayMatch match = location.matchDisplay(targetDisplayId);
            if (match == TaskLocation.DisplayMatch.ON_OTHER_DISPLAY) return location;
            if (match == TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY && expected == null) {
                expected = location;
            } else if (match == TaskLocation.DisplayMatch.UNKNOWN && unknown == null) {
                unknown = location;
            }
        }
        if (unknown != null) return unknown;
        if (expected != null) return expected;
        return TaskLocation.absent();
    }

    Action onPoll(int poll, TaskLocation.DisplayMatch match) {
        if (poll <= 0 || match == null) throw new IllegalArgumentException("invalid watchdog poll");
        if (match == TaskLocation.DisplayMatch.ON_OTHER_DISPLAY) {
            completionPoll = Math.min(
                    MAX_POLLS,
                    Math.max(completionPoll, poll + POST_REANCHOR_GUARD_POLLS));
            return Action.REANCHOR;
        }
        if (poll >= MAX_POLLS) return Action.COMPLETE;
        if (match == TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY
                && poll >= completionPoll) {
            return Action.COMPLETE;
        }
        return Action.WAIT;
    }

    int completionPoll() {
        return completionPoll;
    }
}