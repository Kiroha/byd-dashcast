package com.byd.dashcast.infrastructure.task

/** A complete, bounded activities dump can corroborate an otherwise unreadable ATM query. */
object TaskLocationDumpParser {
    const val COMPLETION_MARKER = "DASHCAST_TASK_DUMP_COMPLETE"
    private val display = Regex("(?m)^\\s*Display #(\\d+) \\(activities from top to bottom\\):\\s*$")
    private val displayLine = Regex("(?m)^\\s*Display #[^\\n]+")
    private val task = Regex("(?m)^\\s*(?:\\*\\s*)?Task(?:Record)?\\{[^\\n]*#(\\d+)[^\\n]*")
    private val taskLine = Regex("(?m)^\\s*(?:\\*\\s*)?Task(?:Record)?\\{")
    private val record = Regex("(?m)^\\s*(?:\\*\\s*Hist\\s+#\\d+:\\s*|topResumedActivity=|mResumedActivity[=:]\\s*)ActivityRecord\\{[^}\\n]*\\bu(\\d+)\\s+([A-Za-z][A-Za-z0-9_.]*)/[^}\\n]*\\}?\\s*t(\\d+)\\}")
    private val user = Regex("\\bU=(\\d+)\\b")
    private val failure = Regex("(?i)permission denial|permission denied|broken pipe|dump timeout|timeout dumping|error dumping|can't find service|failure while dumping|failed to write while dumping|exception while|exception occurred|\\[output truncated\\]")

    @JvmStatic
    fun parse(output: String?, packageName: String): TaskLocation {
        if (output.isNullOrBlank() || !output.trimEnd().endsWith("\n$COMPLETION_MARKER") ||
            !output.contains("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)") ||
            failure.containsMatchIn(output)) return TaskLocation.unknown()
        val displays = display.findAll(output).toList()
        if (displays.isEmpty() || displayLine.findAll(output).count() != displays.size) return TaskLocation.unknown()
        val found = mutableListOf<TaskLocation>()
        var incompleteIdentity = false
        var unresolvedTarget = false
        val packageMention = Regex("(?<![A-Za-z0-9_.])${Regex.escape(packageName)}(?=[/\\s}])")
        for ((index, header) in displays.withIndex()) {
            val block = output.substring(header.range.last + 1,
                displays.getOrNull(index + 1)?.range?.first ?: output.length)
            val displayId = header.groupValues[1].toIntOrNull() ?: return TaskLocation.unknown()
            val tasks = task.findAll(block).toList()
            if (taskLine.findAll(block).count() != tasks.size) return TaskLocation.unknown()
            for ((taskIndex, taskHeader) in tasks.withIndex()) {
                val taskId = taskHeader.groupValues[1].toIntOrNull() ?: return TaskLocation.unknown()
                val indentation = taskHeader.value.substringAfterLast('\n').takeWhile { it.isWhitespace() }.length
                val rawBody = block.substring(taskHeader.range.first,
                    tasks.getOrNull(taskIndex + 1)?.range?.first ?: block.length)
                val lines = rawBody.lineSequence().dropWhile { it.isBlank() }.toList()
                // Global resumed/paused history is not part of the last task's ownership.
                val body = (lines.take(1) + lines.drop(1).takeWhile {
                    it.isBlank() || it.takeWhile(Char::isWhitespace).length > indentation
                }).joinToString("\n")
                val ownRecords = record.findAll(body).filter { it.groupValues[3].toIntOrNull() == taskId }.toList()
                val nextIndentation = tasks.getOrNull(taskIndex + 1)?.value?.substringAfterLast('\n')
                    ?.takeWhile { it.isWhitespace() }?.length ?: -1
                if (ownRecords.isEmpty() && nextIndentation <= indentation &&
                    !Regex("\\b(?:sz|numActivities)=0\\b").containsMatchIn(taskHeader.value)) {
                    incompleteIdentity = true
                }
                val matches = ownRecords.filter { it.groupValues[2] == packageName }
                if (matches.isEmpty()) {
                    if (nextIndentation <= indentation && packageMention.containsMatchIn(body)) unresolvedTarget = true
                    continue
                }
                val users = matches.map { it.groupValues[1].toIntOrNull() }.toSet() +
                    user.find(taskHeader.value)?.groupValues?.get(1)?.toIntOrNull()
                val knownUsers = users.filterNotNull().toSet()
                if (knownUsers != setOf(0)) return TaskLocation.unknown()
                found += TaskLocation.found(taskId, displayId)
            }
        }
        val unique = found.distinctBy { it.taskId to it.displayId }
        if (unresolvedTarget) return TaskLocation.unknown()
        if (unique.size == 1) return unique.single()
        if (unique.size > 1) return TaskLocation.unknown()
        if (incompleteIdentity) return TaskLocation.unknown()
        // A matching but unparsed component/affinity is not evidence that its task disappeared.
        if (packageMention.containsMatchIn(output)) {
            return TaskLocation.unknown()
        }
        return TaskLocation.absent()
    }
}
