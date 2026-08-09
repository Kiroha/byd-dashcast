package com.byd.dashcast.proxy.daemon

import java.util.regex.Pattern

/**
 * Reads the OUTCOME out of a [Phase4TaskVerbs.moveAndResize] transcript.
 *
 * The daemon returns its whole cascade as text and never throws, so a caller that wants to know
 * "did the rect actually land?" has to read the transcript. The only line that answers that is the
 * closing `getTaskBounds(N) = [l,t][r,b]` — individual verbs in the cascade are *expected* to fail
 * on some ROMs while the rect still lands (DiLink 3 rejects `resizeTask` outright, because BYD's
 * own docking verb leaves the task outside FREEFORM and `canResizeTask()` is FREEFORM-only), so
 * scanning for "ERR" answers a different question than the one being asked.
 */
object MoveAndResizeOutcome {

    private val BOUNDS = Pattern.compile(
            "getTaskBounds\\(\\d+\\)\\s*=\\s*\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    /**
     * True when [log] reports the task settled on exactly [l],[t],[r],[b].
     *
     * False when the transcript carries no readable bounds line — the verb may have thrown, or the
     * ROM may not expose `getTaskBounds` — which leaves the outcome unknown, and unknown must not
     * read as success.
     */
    @JvmStatic
    fun landedOn(log: String?, l: Int, t: Int, r: Int, b: Int): Boolean {
        if (log == null) return false
        val m = BOUNDS.matcher(log)
        if (!m.find()) return false
        return try {
            m.group(1)!!.toInt() == l && m.group(2)!!.toInt() == t &&
                m.group(3)!!.toInt() == r && m.group(4)!!.toInt() == b
        } catch (e: NumberFormatException) {
            false
        }
    }
}
