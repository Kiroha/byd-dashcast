package com.byd.dashcast.infrastructure.task

/** Extracts package task/display ownership from API 28/29 `dumpsys activity activities`. */
object LegacyTaskLocationParser {
    private val displayHeader = Regex("(?m)^\\s*Display #(\\d+)\\b")
    private val taskStart = Regex("(?m)^\\s*(?:\\*\\s*)?Task(?:Record)?\\{")
    private val taskId = Regex("Task(?:Record)?\\{[^}]*#(\\d+)")
    private val activityTaskId = Regex("ActivityRecord\\{[^}]*\\st(\\d+)\\}")

    @JvmStatic
    fun parse(dump: String?, packageName: String?): TaskLocation {
        val found = parseAll(dump, packageName)
        if (found.isEmpty()) {
            return if (dump.isNullOrBlank() || displayHeader.find(dump) == null) {
                TaskLocation.unknown()
            } else {
                TaskLocation.absent()
            }
        }
        return found.firstOrNull { it.displayId == 0 } ?: found.first()
    }

    @JvmStatic
    fun parseAll(dump: String?, packageName: String?): List<TaskLocation> {
        if (dump.isNullOrBlank() || packageName.isNullOrEmpty()) return TaskLocation.unknown()
            .let(::listOf)
        val headers = displayHeader.findAll(dump).toList()
        if (headers.isEmpty()) return listOf(TaskLocation.unknown())
        val found = mutableListOf<TaskLocation>()
        for ((index, header) in headers.withIndex()) {
            val start = header.range.first
            val end = headers.getOrNull(index + 1)?.range?.first ?: dump.length
            val block = dump.substring(start, end)
            val displayId = header.groupValues[1].toIntOrNull() ?: continue
            val taskHeaders = taskStart.findAll(block).toList()
            if (taskHeaders.isEmpty()) {
                addMatchingTask(found, block, packageName, displayId)
                continue
            }
            for ((taskIndex, taskHeader) in taskHeaders.withIndex()) {
                val taskEnd = taskHeaders.getOrNull(taskIndex + 1)?.range?.first ?: block.length
                addMatchingTask(
                    found,
                    block.substring(taskHeader.range.first, taskEnd),
                    packageName,
                    displayId,
                )
            }
        }
        return found.distinctBy { it.taskId to it.displayId }
    }

    private fun addMatchingTask(
        found: MutableList<TaskLocation>,
        block: String,
        packageName: String,
        displayId: Int,
    ) {
        if (!block.contains("$packageName/") &&
            !block.contains("A=$packageName ") &&
            !block.contains("A=$packageName}")) return
        val id = taskId.find(block)?.groupValues?.get(1)?.toIntOrNull()
            ?: activityTaskId.find(block)?.groupValues?.get(1)?.toIntOrNull()
            ?: return
        found += TaskLocation.found(id, displayId)
    }
}