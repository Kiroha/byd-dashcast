package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.infrastructure.task.TaskLocation

/** Pure timing/decision policy for post-launch fission task guardians. */
internal class FissionWatchdogPolicy {

    enum class Action { WAIT, REANCHOR, COMPLETE }

    private var completionPollValue = INITIAL_GUARD_POLLS

    fun onPoll(poll: Int, match: TaskLocation.DisplayMatch?): Action {
        require(poll > 0 && match != null) { "invalid watchdog poll" }
        if (match == TaskLocation.DisplayMatch.ON_OTHER_DISPLAY) {
            completionPollValue = Math.min(
                MAX_POLLS,
                Math.max(completionPollValue, poll + POST_REANCHOR_GUARD_POLLS))
            return Action.REANCHOR
        }
        if (poll >= MAX_POLLS) return Action.COMPLETE
        if (match == TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY && poll >= completionPollValue) {
            return Action.COMPLETE
        }
        return Action.WAIT
    }

    fun completionPoll(): Int = completionPollValue

    companion object {
        const val POLL_INTERVAL_MS = 500L
        const val INITIAL_GUARD_POLLS = 60        // 30 s
        const val POST_REANCHOR_GUARD_POLLS = 30  // 15 s
        const val MAX_POLLS = 180                 // 90 s hard bound

        /**
         * One verb transcript, shortened to fit a daemon-transcript line.
         *
         * The watchdog's own log lines carry four multi-line verb transcripts. Mirroring them
         * whole into the daemon file log would trade one unreadable channel for another, and a
         * pathological session re-anchors up to [MAX_POLLS] times.
         */
        @JvmStatic
        fun brief(s: String?): String {
            if (s.isNullOrEmpty()) return "-"
            val firstLine = s.lineSequence().first().trim()
            if (firstLine.isEmpty()) return "-"
            return if (firstLine.length > 60) firstLine.substring(0, 60) + ".." else firstLine
        }

        /** Prefer any known task outside the slot; otherwise keep the newest query ordering. */
        @JvmStatic
        fun selectTask(locations: List<TaskLocation>?, targetDisplayId: Int): TaskLocation {
            requireNotNull(locations) { "locations required" }
            var expected: TaskLocation? = null
            var unknown: TaskLocation? = null
            for (location in locations) {
                if (location == null) continue
                when (location.matchDisplay(targetDisplayId)) {
                    TaskLocation.DisplayMatch.ON_OTHER_DISPLAY -> return location
                    TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY -> if (expected == null) expected = location
                    TaskLocation.DisplayMatch.UNKNOWN -> if (unknown == null) unknown = location
                    else -> { /* other matches ignored */ }
                }
            }
            if (unknown != null) return unknown
            if (expected != null) return expected
            return TaskLocation.absent()
        }
    }
}
