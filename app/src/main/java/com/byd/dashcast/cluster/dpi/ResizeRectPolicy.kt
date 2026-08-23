package com.byd.dashcast.cluster.dpi

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The rectangle arithmetic behind the cluster resize editor, as a pure function.
 *
 * It lives apart from [ResizeFrameView] because its output is not a drawing: it is sent to
 * `ProxyClient.moveAndResize` and repositions a real application window on a driver's instrument
 * cluster. A wrong rect here is not a cosmetic glitch, and none of it was testable while it sat
 * inside a View's touch handler.
 *
 * Two defects were found by the full audit and are fixed here, both of them invisible on the
 * 1920x720 panel the editor was built against:
 *
 *  - **Soft-snap was gated per AXIS, not per EDGE.** Dragging the left handle ran both `l` and `r`
 *    through the snap, so an untouched right edge could jump to an anchor on its own. The guard now
 *    asks whether THIS edge is being dragged.
 *  - **The min-size fallback bypassed the very guarantees it existed to restore.** When the snapped
 *    rect came out under [MIN_SIZE] the code recomputed from the down-rect through the grid only —
 *    no bounds clamp, no min-size re-check — so it could emit something worse than what it
 *    rejected, including a sliver a few pixels wide, and commit that to the cluster. The fallback
 *    now re-runs the same clamp and min-size enforcement as the primary path.
 */
object ResizeRectPolicy {

    const val MIN_SIZE = 200
    const val SNAP_TOL_PX = 24
    const val GRID_STEP = 10

    const val HANDLE_NONE = 0
    const val HANDLE_TL = 1
    const val HANDLE_T = 2
    const val HANDLE_TR = 3
    const val HANDLE_L = 4
    const val HANDLE_R = 5
    const val HANDLE_BL = 6
    const val HANDLE_B = 7
    const val HANDLE_BR = 8
    const val HANDLE_MOVE = 9

    fun snapStep(v: Int): Int = (v.toFloat() / GRID_STEP).roundToInt() * GRID_STEP

    fun softSnap(v: Int, anchors: IntArray): Int {
        for (a in anchors) if (abs(v - a) <= SNAP_TOL_PX) return a
        return v
    }

    /** Which edges a handle actually moves — the distinction the old per-axis guard lost. */
    private fun movesLeft(h: Int) = h == HANDLE_TL || h == HANDLE_BL || h == HANDLE_L
    private fun movesRight(h: Int) = h == HANDLE_TR || h == HANDLE_BR || h == HANDLE_R
    private fun movesTop(h: Int) = h == HANDLE_TL || h == HANDLE_TR || h == HANDLE_T
    private fun movesBottom(h: Int) = h == HANDLE_BL || h == HANDLE_BR || h == HANDLE_B

    /**
     * @return the final rect as [l, t, r, b], in cluster pixels.
     */
    @JvmStatic
    fun compute(
        downL: Int, downT: Int, downR: Int, downB: Int,
        handle: Int, dx: Int, dy: Int,
        clusterW: Int, clusterH: Int,
        snapXs: IntArray, snapYs: IntArray,
    ): IntArray {
        var l = downL; var t = downT; var r = downR; var b = downB

        when (handle) {
            HANDLE_TL -> { l += dx; t += dy }
            HANDLE_TR -> { r += dx; t += dy }
            HANDLE_BL -> { l += dx; b += dy }
            HANDLE_BR -> { r += dx; b += dy }
            HANDLE_T -> { t += dy }
            HANDLE_B -> { b += dy }
            HANDLE_L -> { l += dx }
            HANDLE_R -> { r += dx }
            HANDLE_MOVE -> {
                val w = r - l; val h = b - t
                l += dx; t += dy
                if (l < 0) l = 0
                if (t < 0) t = 0
                if (l + w > clusterW) l = clusterW - w
                if (t + h > clusterH) t = clusterH - h
                r = l + w; b = t + h
            }
        }

        if (handle != HANDLE_MOVE) {
            val c = clampAndEnforce(l, t, r, b, handle, clusterW, clusterH)
            l = c[0]; t = c[1]; r = c[2]; b = c[3]
        }

        l = snapStep(l); t = snapStep(t); r = snapStep(r); b = snapStep(b)

        // Per-EDGE, not per-axis: only an edge the gesture is actually dragging may snap. A MOVE
        // drags all four together, so all four are eligible.
        if (handle == HANDLE_MOVE || movesLeft(handle)) l = softSnap(l, snapXs)
        if (handle == HANDLE_MOVE || movesRight(handle)) r = softSnap(r, snapXs)
        if (handle == HANDLE_MOVE || movesTop(handle)) t = softSnap(t, snapYs)
        if (handle == HANDLE_MOVE || movesBottom(handle)) b = softSnap(b, snapYs)

        // A MOVE must keep its size: snapping one edge would otherwise stretch the window.
        if (handle == HANDLE_MOVE) {
            val w = downR - downL; val h = downB - downT
            r = l + w; b = t + h
            if (r > clusterW) { r = clusterW; l = r - w }
            if (b > clusterH) { b = clusterH; t = b - h }
            if (l < 0) { l = 0; r = w }
            if (t < 0) { t = 0; b = h }
            return intArrayOf(l, t, r, b)
        }

        val c = clampAndEnforce(l, t, r, b, handle, clusterW, clusterH)
        return intArrayOf(c[0], c[1], c[2], c[3])
    }

    /**
     * Bounds clamp then min-size, in that order, pushing the edge the gesture is NOT dragging.
     *
     * Run twice on purpose — once before the grid/snap step and once after. The old code ran the
     * clamp twice but the min-size check only once, and "recover" from a violation by recomputing
     * a raw rect that had been through neither.
     */
    private fun clampAndEnforce(
        l0: Int, t0: Int, r0: Int, b0: Int, handle: Int, clusterW: Int, clusterH: Int,
    ): IntArray {
        var l = l0; var t = t0; var r = r0; var b = b0
        if (l < 0) l = 0
        if (t < 0) t = 0
        if (r > clusterW) r = clusterW
        if (b > clusterH) b = clusterH
        if (r - l < MIN_SIZE) {
            if (movesLeft(handle)) l = r - MIN_SIZE else r = l + MIN_SIZE
        }
        if (b - t < MIN_SIZE) {
            if (movesTop(handle)) t = b - MIN_SIZE else b = t + MIN_SIZE
        }
        // Enforcing min-size can push an edge back out of bounds on a panel narrower than
        // MIN_SIZE*2; slide the whole span inside rather than emit an out-of-bounds rect.
        if (l < 0) { l = 0; if (r < MIN_SIZE) r = minOf(MIN_SIZE, clusterW) }
        if (t < 0) { t = 0; if (b < MIN_SIZE) b = minOf(MIN_SIZE, clusterH) }
        if (r > clusterW) { r = clusterW; if (r - l < MIN_SIZE) l = maxOf(0, r - MIN_SIZE) }
        if (b > clusterH) { b = clusterH; if (b - t < MIN_SIZE) t = maxOf(0, b - MIN_SIZE) }
        return intArrayOf(l, t, r, b)
    }
}
