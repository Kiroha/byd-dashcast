package com.byd.dashcast.infrastructure.task

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskLocationDumpParserTest {
    private val pkg = "com.example.navigation"
    private fun dump(body: String) = "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n" +
        body.trimIndent() + "\n" + TaskLocationDumpParser.COMPLETION_MARKER
    private fun task(display: Int = 2, id: Int = 298, user: Int = 0, name: String = pkg) = """
        Display #$display (activities from top to bottom):
          * Task{abc #$id type=standard A=10020:$name U=$user visible=true mode=fullscreen sz=1}
            topResumedActivity=ActivityRecord{def u$user $name/.Main} t$id}
            * Hist #0: ActivityRecord{def u$user $name/.Main} t$id}
              mActivityComponent=$name/.Main
    """.trimIndent()

    @Test fun `Android 13 task is found with uid-prefixed affinity and task outside record brace`() {
        val found = TaskLocationDumpParser.parse(dump(task()), pkg)
        assertEquals(TaskLocation.Status.FOUND, found.status)
        assertEquals(298, found.taskId)
        assertEquals(2, found.displayId)
    }

    @Test fun `Android 10 TaskRecord and activity task inside brace remain supported`() {
        val found = TaskLocationDumpParser.parse(dump("""
            Display #1 (activities from top to bottom):
              * TaskRecord{abc #73 A=$pkg U=0 StackId=28 sz=1}
                * Hist #0: ActivityRecord{def u0 $pkg/.Main t73}
        """), pkg)
        assertEquals(TaskLocation.Status.FOUND, found.status)
        assertEquals(73, found.taskId)
        assertEquals(1, found.displayId)
    }

    @Test fun `fully recognized completed snapshot can confirm absence`() {
        assertEquals(TaskLocation.Status.ABSENT,
            TaskLocationDumpParser.parse(dump(task(name = "$pkg.extra")), pkg).status)
        assertEquals(TaskLocation.Status.ABSENT,
            TaskLocationDumpParser.parse(dump("Display #0 (activities from top to bottom):"), pkg).status)
    }

    @Test fun `unreadable identity or affinity alone cannot prove absence`() {
        for (body in listOf(
            "Display #2 (activities from top to bottom):\n * Task{abc #298 A=10020:$pkg U=0 sz=1}",
            "Display #2 (activities from top to bottom):\n * Task{abc #298 A=com.other U=0 sz=1}",
            "Display #2 (activities from top to bottom):\n * Task{abc unknown-id}"
        )) assertEquals(TaskLocation.Status.UNKNOWN, TaskLocationDumpParser.parse(dump(body), pkg).status)
    }

    @Test fun `partial or failed dumps never establish presence or absence`() {
        val complete = dump(task())
        for (output in listOf(null, "", task(), complete.substringBeforeLast('\n'),
            complete + "\n[output truncated]", complete.replace("Display #2", "Display #unknown"),
            complete.replace("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)", "unexpected format"),
            complete.replace("visible=true", "Broken pipe"),
            complete.replace("visible=true", "Permission Denial"))) {
            assertEquals(TaskLocation.Status.UNKNOWN, TaskLocationDumpParser.parse(output, pkg).status)
        }
    }

    @Test fun `unrecognized next display cannot attach its task to previous display`() {
        val output = dump("Display #0 (activities from top to bottom):\n" +
            task().replace("Display #2 (activities from top to bottom):", "Display #2 unsupported header:"))
        assertEquals(TaskLocation.Status.UNKNOWN, TaskLocationDumpParser.parse(output, pkg).status)
    }

    @Test fun `global resumed record cannot borrow unrelated task identity`() {
        val output = dump(task(display = 0, id = 42, name = "com.other") +
            "\n  Resumed activities:\n topResumedActivity=ActivityRecord{def u0 $pkg/.Main} t298}")
        assertEquals(TaskLocation.Status.UNKNOWN, TaskLocationDumpParser.parse(output, pkg).status)
    }

    @Test fun `multiple package tasks and other users are unknown`() {
        assertEquals(TaskLocation.Status.UNKNOWN,
            TaskLocationDumpParser.parse(dump(task() + "\n" + task(display = 0, id = 299)), pkg).status)
        assertEquals(TaskLocation.Status.UNKNOWN,
            TaskLocationDumpParser.parse(dump(task(user = 10)), pkg).status)
        assertEquals(TaskLocation.Status.UNKNOWN,
            TaskLocationDumpParser.parse(dump(task().replace("U=0", "U=10")), pkg).status)
    }

    @Test fun `hierarchical root task does not turn child into the wrong task`() {
        val output = dump("""
            Display #2 (activities from top to bottom):
              * Task{root #1 type=standard U=0 sz=1}
                * Task{child #298 type=standard A=10020:$pkg U=0 sz=1}
                  * Hist #0: ActivityRecord{def u0 $pkg/.Main} t298}
        """)
        assertEquals(298, TaskLocationDumpParser.parse(output, pkg).taskId)
    }

    @Test fun `one readable task cannot hide another unresolved task for the same package`() {
        val output = dump(task() + "\n" + """
            Display #0 (activities from top to bottom):
              * Task{other #299 type=standard A=10020:$pkg U=0 sz=1}
        """.trimIndent())
        assertEquals(TaskLocation.Status.UNKNOWN, TaskLocationDumpParser.parse(output, pkg).status)
    }

    @Test fun `stale detached activity cannot authorize destructive absence recovery`() {
        val output = dump("""
            Display #1 (activities from top to bottom):
              * TaskRecord{abc #28 A=$pkg U=0 sz=0}
                mLastPausedActivity=ActivityRecord{def u0 $pkg/.Main t-1 f}
        """)
        assertEquals(TaskLocation.Status.UNKNOWN, TaskLocationDumpParser.parse(output, pkg).status)
    }

    @Test fun `record-like text in an intent does not establish task identity`() {
        val output = dump("""
            Display #2 (activities from top to bottom):
              * Task{abc #298 type=standard U=0 sz=1}
                Intent { extra=ActivityRecord{fake u0 $pkg/.Main} t298} }
        """)
        assertEquals(TaskLocation.Status.UNKNOWN, TaskLocationDumpParser.parse(output, pkg).status)
    }
}
