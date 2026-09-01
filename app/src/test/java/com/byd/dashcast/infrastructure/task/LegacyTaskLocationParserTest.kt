package com.byd.dashcast.infrastructure.task

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyTaskLocationParserTest {

    @Test
    fun `finds package task on display zero`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{abc #42 type=standard A=com.byd.androidauto}
                ActivityRecord{def u0 com.byd.androidauto/.Main t42}
            Display #1 (activities from top to bottom):
              * Task{ghi #7 type=standard A=com.other}
        """.trimIndent()

        val location = LegacyTaskLocationParser.parse(dump, "com.byd.androidauto")

        assertEquals(TaskLocation.Status.FOUND, location.status)
        assertEquals(42, location.taskId)
        assertEquals(0, location.displayId)
    }

    @Test
    fun `prefers display zero when package has multiple tasks`() {
        val dump = """
            Display #1 (activities from top to bottom):
              * Task{aaa #7 type=standard A=com.example.app}
            Display #0 (activities from top to bottom):
              * Task{bbb #8 type=standard A=com.example.app}
        """.trimIndent()

        val all = LegacyTaskLocationParser.parseAll(dump, "com.example.app")
        assertEquals(listOf(1, 0), all.map { it.displayId })
        assertEquals(0, LegacyTaskLocationParser.parse(dump, "com.example.app").displayId)
    }

    @Test
    fun `unrecognized dump is unknown rather than absent`() {
        assertEquals(TaskLocation.Status.UNKNOWN,
            LegacyTaskLocationParser.parse("Activity manager unavailable", "com.example").status)
    }
}