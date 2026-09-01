package com.byd.dashcast.infrastructure.task

/** Extracts package task/display ownership from API 28/29 `dumpsys activity activities`. */
object LegacyTaskLocationParser {
    private val displayHeader = Regex("(?m)^\\s*Display #(\\d+)\\b")
    private val taskId = Regex("Task(?:Record)?\\{[^}]*#(\\d+)")
    private val activityTaskId = Regex("ActivityRecord\\{[^}]*\\st(\\d+)\\}")

    @JvmStatic
    fun parse(dump: String?, packageName: String?): TaskLocation {
        if (dump.isNullOrBlank() || packageName.isNullOrEmpty()) return TaskLocation.unknown()
        val headers = displayHeader.findAll(dump).toList()
        if (headers.isEmpty()) return TaskLocation.unknown()
        val found = mutableListOf<TaskLocation>()
        for ((index, header) in headers.withIndex()) {
            val start = header.range.first
            val end = headers.getOrNull(index + 1)?.range?.first ?: dump.length
            val block = dump.substring(start, end)
            if (!block.contains("$packageName/") &&
                !block.contains("A=$packageName ") &&
                !block.contains("A=$packageName}")) continue
            val id = taskId.find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: activityTaskId.find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val displayId = header.groupValues[1].toIntOrNull() ?: continue
            found += TaskLocation.found(id, displayId)
        }
        return found.firstOrNull { it.displayId == 0 }
            ?: found.firstOrNull()
            ?: TaskLocation.absent()
    }
}