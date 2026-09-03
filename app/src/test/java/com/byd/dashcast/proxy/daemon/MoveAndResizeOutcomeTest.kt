package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveAndResizeOutcomeTest {

    /** Verbatim transcript from INC-20260809-162829 (DiLink 3, 1.8.24-beta) — the case that made
     *  the old "no ERR in the log" test retry all 3 attempts even though the rect had landed. */
    private val dilink3Transcript = """
        == moveAndResize com.waze → display=1 rect=[150,150,1800,540] ==
        findTask = 16
          OK setDisplayToSingleTaskInstance(1)
          OK setTaskWindowingMode(16,FREEFORM) ; stackId=12 currentDisplay=1 ; SKIP move (already on display 1)
          OK setTaskResizeable(16,4)
        stackId = 12
          SKIP setStackWindowingMode: no candidate method
          OK resizeStack(12,[150,150][1800,540])
          OK setCustomTaskWindowingModeSplitScreenPrimary(16,mode=5,[150,150][1800,540])
          ERR resizeTask: IllegalArgumentException — resizeTask not allowed on task=TaskRecord{cd36949 #16 A=com.waze U=0 StackId=12 sz=1}
          OK setFocusedTask(16)
          getTaskBounds(16) = [150,150][1800,540]
        FINISH: moveAndResize complete.
    """.trimIndent()

    @Test
    fun landsDespiteAnErrLineInTheCascade() {
        assertTrue(MoveAndResizeOutcome.landedOn(dilink3Transcript, 150, 150, 1800, 540))
    }

    @Test
    fun rejectsBoundsThatDoNotMatchTheRequest() {
        assertFalse(MoveAndResizeOutcome.landedOn(dilink3Transcript, 150, 150, 1800, 541))
        assertFalse(MoveAndResizeOutcome.landedOn(dilink3Transcript, 0, 0, 1920, 720))
    }

    @Test
    fun unknownOutcomeIsNotSuccess() {
        assertFalse(MoveAndResizeOutcome.landedOn(null, 150, 150, 1800, 540))
        assertFalse(MoveAndResizeOutcome.landedOn("", 150, 150, 1800, 540))
        // getTaskBounds itself threw — the cascade ran but the outcome is unreadable.
        assertFalse(MoveAndResizeOutcome.landedOn(
                "OK setFocusedTask(16)\nERR getTaskBounds: NullPointerException — null",
                150, 150, 1800, 540))
        // No task at all: the cascade returns early, before any bounds line.
        assertFalse(MoveAndResizeOutcome.landedOn(
                "findTask = -1\nFAIL: no task for com.waze — launch the app first via launchAndForce.",
                150, 150, 1800, 540))
    }

    @Test
    fun acceptsNegativeAndZeroOrigins() {
        assertTrue(MoveAndResizeOutcome.landedOn(
                "getTaskBounds(9) = [-10,0][1920,720]", -10, 0, 1920, 720))
    }

    /** A full-panel launch resize (0,0,w,h) is the shape the other platforms report as OK. */
    @Test
    fun acceptsFullPanelBounds() {
        assertTrue(MoveAndResizeOutcome.landedOn(
                "OK resizeTask(16,[0,0][1920,720])\ngetTaskBounds(16) = [0,0][1920,720]",
                0, 0, 1920, 720))
    }

    /**
     * The cascade echoes the REQUESTED rect back in its own verb lines, so a parser that matched
     * any `[l,t][r,b]` would report success unconditionally and never detect a rect that did not
     * land — the exact bug the old "no ERR in the log" test had. Only getTaskBounds is authoritative.
     */
    @Test
    fun readsGetTaskBoundsOnly_notTheVerbEchoes() {
        val clamped = """
            OK resizeStack(12,[150,150][1800,540])
            OK setCustomTaskWindowingModeSplitScreenPrimary(16,mode=5,[150,150][1800,540])
            SKIP resizeTask: task is not in a resizable windowing mode (bounds already set by the preceding verb)
            OK setFocusedTask(16)
            getTaskBounds(16) = [0,0][1280,480]
        """.trimIndent()
        // Requested 150,150,1800,540 and every verb echoed it — but the task landed elsewhere.
        assertFalse(MoveAndResizeOutcome.landedOn(clamped, 150, 150, 1800, 540))
        assertTrue(MoveAndResizeOutcome.landedOn(clamped, 0, 0, 1280, 480))
    }
}
