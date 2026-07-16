package com.byd.dashcast.infrastructure.task

/**
 * Result of locating an application's task through the privileged proxy daemon.
 *
 * [UNKNOWN] is deliberately distinct from [ABSENT]: a transport or reflection failure must never
 * be interpreted as proof that a running navigation task disappeared.
 */
class TaskLocation private constructor(
    val status: Status,
    val taskId: Int,
    val displayId: Int
) {
    enum class Status(val wireCode: Int) {
        FOUND(1),
        ABSENT(0),
        UNKNOWN(-1)
    }

    enum class DisplayMatch {
        ON_EXPECTED_DISPLAY,
        ON_OTHER_DISPLAY,
        ABSENT,
        UNKNOWN
    }

    fun matchDisplay(expectedDisplayId: Int): DisplayMatch = when (status) {
        Status.FOUND -> if (displayId < 0 || expectedDisplayId < 0) {
            DisplayMatch.UNKNOWN
        } else if (displayId == expectedDisplayId) {
            DisplayMatch.ON_EXPECTED_DISPLAY
        } else {
            DisplayMatch.ON_OTHER_DISPLAY
        }
        Status.ABSENT -> DisplayMatch.ABSENT
        Status.UNKNOWN -> DisplayMatch.UNKNOWN
    }

    companion object {
        const val NO_TASK_ID = -1
        const val UNKNOWN_DISPLAY_ID = -1

        @JvmStatic
        fun found(taskId: Int, displayId: Int): TaskLocation =
            if (taskId > 0) TaskLocation(Status.FOUND, taskId, displayId) else unknown()

        @JvmStatic
        fun absent(): TaskLocation = TaskLocation(Status.ABSENT, NO_TASK_ID, UNKNOWN_DISPLAY_ID)

        @JvmStatic
        fun unknown(): TaskLocation = TaskLocation(Status.UNKNOWN, NO_TASK_ID, UNKNOWN_DISPLAY_ID)

        @JvmStatic
        fun fromWire(statusCode: Int, taskId: Int, displayId: Int): TaskLocation =
            when (statusCode) {
                Status.FOUND.wireCode -> found(taskId, displayId)
                Status.ABSENT.wireCode -> absent()
                else -> unknown()
            }
    }
}
