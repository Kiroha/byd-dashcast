package com.byd.dashcast.cluster.dpi

import com.byd.dashcast.cluster.dpi.ResizeRectPolicy as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rectangle that gets written to a driver's instrument cluster.
 *
 * This arithmetic used to live inside a View's touch handler, where nothing could reach it. The
 * full audit found two defects in it, and both produced a rect the user never asked for — one that
 * goes straight to ProxyClient.moveAndResize on a live cluster. These cases exist so that cannot
 * happen again silently.
 */
class ResizeRectPolicyTest {

    private val W = 1920
    private val H = 720

    private fun run(l: Int, t: Int, r: Int, b: Int, handle: Int, dx: Int = 0, dy: Int = 0,
                    w: Int = W, h: Int = H) =
        P.compute(l, t, r, b, handle, dx, dy, w, h,
            intArrayOf(0, w / 4, w / 2, 3 * w / 4, w), intArrayOf(0, h / 4, h / 2, 3 * h / 4, h))

    // ── the untouched edge must not move ────────────────────────────────────────────────────

    /**
     * The user parked the right edge at 1430 — ten pixels short of the 1440 three-quarter anchor,
     * deliberately, and grid-aligned so the editor can actually hold it. Dragging the LEFT handle
     * must not steal it.
     *
     * The audit illustrated this with r = 1435, which is unreachable: every commit passes through
     * the 10px grid step, so a rect can never rest on an odd value. The defect is real all the
     * same — 1430 is inside SNAP_TOL_PX of 1440 and the old per-axis guard snapped it.
     */
    @Test
    fun `dragging the left handle leaves the right edge alone`() {
        val out = run(750, 100, 1430, 600, P.HANDLE_L, dx = -50)
        assertEquals("the right edge was not dragged and must not snap", 1430, out[2])
    }

    @Test
    fun `dragging the right handle leaves the left edge alone`() {
        // 470 is grid-aligned and within SNAP_TOL_PX of the 480 quarter anchor.
        val out = run(470, 100, 1000, 600, P.HANDLE_R, dx = 50)
        assertEquals("the left edge was not dragged and must not snap", 470, out[0])
    }

    @Test
    fun `dragging the top handle leaves the bottom edge alone`() {
        // 530 is grid-aligned and within SNAP_TOL_PX of the 540 three-quarter anchor.
        val out = run(200, 100, 900, 530, P.HANDLE_T, dy = -20)
        assertEquals(530, out[3])
    }

    @Test
    fun `the dragged edge still snaps to its anchor`() {
        // The feature itself must survive the fix: 955 is within 24px of the 960 half-width anchor.
        val out = run(955, 100, 1500, 600, P.HANDLE_L, dx = 0)
        assertEquals("the dragged edge must still snap", 960, out[0])
    }

    // ── no rect smaller than the minimum, ever ──────────────────────────────────────────────

    /**
     * The traced failure from the audit: min-size clamp, then grid, then a snap that undoes the
     * clamp, then a "fallback" that recomputed from the raw down-rect with nothing re-checked —
     * final width 10px, committed to the cluster.
     */
    @Test
    fun `the audit's ten-pixel sliver cannot be produced`() {
        val out = run(750, 100, 970, 600, P.HANDLE_L, dx = 205)
        val width = out[2] - out[0]
        assertTrue("width $width must not fall under MIN_SIZE (${P.MIN_SIZE}); rect=${out.toList()}",
            width >= P.MIN_SIZE)
    }

    /**
     * Same property, swept. Any drag from any handle, at any distance, must leave a usable window —
     * this is the invariant, not the single traced case.
     */
    @Test
    fun `no handle at any drag distance can breach the minimum or the panel`() {
        val handles = intArrayOf(P.HANDLE_TL, P.HANDLE_T, P.HANDLE_TR, P.HANDLE_L,
            P.HANDLE_R, P.HANDLE_BL, P.HANDLE_B, P.HANDLE_BR)
        for (hnd in handles) {
            for (d in intArrayOf(-4000, -900, -205, -37, 0, 37, 205, 900, 4000)) {
                val out = run(750, 200, 970, 500, hnd, dx = d, dy = d)
                val l = out[0]; val t = out[1]; val r = out[2]; val b = out[3]
                val tag = "handle=$hnd d=$d -> ${out.toList()}"
                assertTrue("$tag width under minimum", r - l >= P.MIN_SIZE)
                assertTrue("$tag height under minimum", b - t >= P.MIN_SIZE)
                assertTrue("$tag left out of panel", l >= 0)
                assertTrue("$tag top out of panel", t >= 0)
                assertTrue("$tag right past panel", r <= W)
                assertTrue("$tag bottom past panel", b <= H)
            }
        }
    }

    // ── a move is a move, not a resize ──────────────────────────────────────────────────────

    @Test
    fun `dragging the whole frame preserves its size exactly`() {
        val out = run(700, 200, 1100, 500, P.HANDLE_MOVE, dx = 137, dy = -43)
        assertEquals("width must survive a move", 400, out[2] - out[0])
        assertEquals("height must survive a move", 300, out[3] - out[1])
    }

    @Test
    fun `a move pushed past the edge stops at the edge without shrinking`() {
        val out = run(700, 200, 1100, 500, P.HANDLE_MOVE, dx = 5000, dy = 5000)
        assertEquals(400, out[2] - out[0])
        assertEquals(300, out[3] - out[1])
        assertTrue(out[2] <= W && out[3] <= H && out[0] >= 0 && out[1] >= 0)
    }

    // ── the panel that is not 1920x720 ──────────────────────────────────────────────────────

    /**
     * The DL3 panel this project has already met on a car. Every bound here is derived from the
     * real geometry, so a rect computed for it must stay inside it.
     */
    @Test
    fun `an 8 inch 1280x480 panel is respected`() {
        val out = run(100, 100, 500, 400, P.HANDLE_BR, dx = 4000, dy = 4000, w = 1280, h = 480)
        assertTrue("right ${out[2]} must stay within 1280", out[2] <= 1280)
        assertTrue("bottom ${out[3]} must stay within 480", out[3] <= 480)
        assertTrue(out[2] - out[0] >= P.MIN_SIZE)
        assertTrue(out[3] - out[1] >= P.MIN_SIZE)
    }

    @Test
    fun `the grid step and snap tolerance are the values the editor was tuned with`() {
        assertEquals(10, P.GRID_STEP)
        assertEquals(24, P.SNAP_TOL_PX)
        assertEquals(200, P.MIN_SIZE)
    }
}
