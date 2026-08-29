package com.byd.dashcast.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note exists because a report can be honest about its text and silent about its envelope.
 * These tests pin what it must keep saying, not how it phrases it — except for the one word a
 * reader acts on.
 */
class ReportAttachmentNoteTest {

    @Test
    fun `a report sent alone gains no line`() {
        assertEquals("", ReportAttachmentNote.forShots(0))
        assertEquals("a negative count is a bug upstream, not a reason to write nonsense",
            "", ReportAttachmentNote.forShots(-1))
    }

    @Test
    fun `the note states the count and that the shots are not redacted`() {
        val note = ReportAttachmentNote.forShots(12)
        assertTrue(note, note.contains("12 screenshot(s)"))
        // The word a reader acts on. If a later edit softens this, the note stops doing its job.
        assertTrue(note, note.contains("NOT redacted"))
    }

    /**
     * INC-20260826-194829 attached twelve frames holding four minutes of street-level position and
     * a saved Home pin. A count alone would not have told the sender what that meant, so the note
     * names the things those frames actually carried.
     */
    @Test
    fun `the note says what a raw frame can carry`() {
        val note = ReportAttachmentNote.forShots(1)
        for (hazard in listOf("street names", "destination", "Home pin", "camera view")) {
            assertTrue("$hazard is not named in: $note", note.contains(hazard))
        }
    }

    @Test
    fun `the note is appendable as-is`() {
        val note = ReportAttachmentNote.forShots(3)
        assertTrue("it must end with a newline so the next line is not glued to it",
            note.endsWith("\n"))
        assertTrue("it must not open with one either", !note.startsWith("\n"))
    }
}
