package com.byd.dashcast.cluster.dpi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * v1.2.72 — Editable frame overlay used by [ClusterResizeActivity].
 *
 * Internally maintains the rectangle in **cluster coordinates** (1920×720)
 * and maps linearly to view-coordinates (TextureView mirror fills the canvas).
 *
 * Ergonomic targets (car HMI, not phone): 8 handles total (4 corners + 4
 * mid-edges), visual size ~24dp, hit radius ~56dp. Soft-snap to the cluster
 * edges, center and quarters with a tolerance of [SNAP_TOL_PX] px, plus a hard
 * 10-px grid step to avoid sub-pixel battles.
 *
 * Calls [View.setSystemGestureExclusionRects] on every frame change so Android's
 * back-gesture / next-swipe don't steal touches that land on the handles (API 29+).
 */
class ResizeFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface Listener {
        fun onFrameChanged(l: Int, t: Int, r: Int, b: Int, commit: Boolean)
    }

    private var mClusterW = 1920
    private var mClusterH = 720
    private val mMinSize = 200 // cluster px
    private val mSnapXs = intArrayOf(0, 480, 960, 1440, 1920) // recomputed in setClusterSize
    private val mSnapYs = intArrayOf(0, 180, 360, 540, 720)

    private val mFrame = Rect()
    private var mListener: Listener? = null

    private val mFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mHandle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mHandleBorder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mHandleActive = Paint(Paint.ANTI_ALIAS_FLAG)

    private var mActiveHandle = HANDLE_NONE
    private var mDownX = 0f
    private var mDownY = 0f
    private val mDownRect = Rect()

    init {
        val primary = resolveThemeColor(android.R.attr.colorPrimary, 0xFF1976D2.toInt())
        val onPrim = resolveThemeColor(android.R.attr.colorBackground, 0xFFFFFFFF.toInt())
        mFill.style = Paint.Style.FILL
        mFill.color = (primary and 0x00FFFFFF) or 0x33000000 // ~20% alpha
        mStroke.style = Paint.Style.STROKE
        mStroke.strokeWidth = dp(3f)
        mStroke.color = primary
        mHandle.style = Paint.Style.FILL
        mHandle.color = onPrim
        mHandleBorder.style = Paint.Style.STROKE
        mHandleBorder.strokeWidth = dp(2.5f)
        mHandleBorder.color = primary
        mHandleActive.style = Paint.Style.FILL
        mHandleActive.color = primary
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    fun setClusterSize(w: Int, h: Int) {
        mClusterW = w
        mClusterH = h
        mSnapXs[1] = w / 4; mSnapXs[2] = w / 2; mSnapXs[3] = 3 * w / 4; mSnapXs[4] = w
        mSnapYs[1] = h / 4; mSnapYs[2] = h / 2; mSnapYs[3] = 3 * h / 4; mSnapYs[4] = h
        updateGestureExclusion()
        invalidate()
    }

    fun setFrame(l: Int, t: Int, r: Int, b: Int) {
        mFrame.set(l, t, r, b)
        updateGestureExclusion()
        invalidate()
    }

    fun getFrame(): Rect = Rect(mFrame)

    fun setListener(l: Listener?) {
        mListener = l
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        updateGestureExclusion()
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        val sx = width.toFloat() / mClusterW
        val sy = height.toFloat() / mClusterH
        val l = mFrame.left * sx
        val t = mFrame.top * sy
        val r = mFrame.right * sx
        val b = mFrame.bottom * sy
        c.drawRect(l, t, r, b, mFill)
        c.drawRect(l, t, r, b, mStroke)
        // 8 handles: corners are round, mid-edges are pill-shaped.
        val radius = dp(12f)
        drawHandle(c, l, t, radius, HANDLE_TL)
        drawHandle(c, r, t, radius, HANDLE_TR)
        drawHandle(c, l, b, radius, HANDLE_BL)
        drawHandle(c, r, b, radius, HANDLE_BR)
        drawHandle(c, (l + r) / 2f, t, radius, HANDLE_T)
        drawHandle(c, (l + r) / 2f, b, radius, HANDLE_B)
        drawHandle(c, l, (t + b) / 2f, radius, HANDLE_L)
        drawHandle(c, r, (t + b) / 2f, radius, HANDLE_R)
    }

    private fun drawHandle(c: Canvas, cx: Float, cy: Float, radius: Float, id: Int) {
        val p = if (mActiveHandle == id) mHandleActive else mHandle
        c.drawCircle(cx, cy, radius, p)
        c.drawCircle(cx, cy, radius, mHandleBorder)
    }

    // ── Touch handling ─────────────────────────────────────────────────────

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mDownX = e.x
                mDownY = e.y
                mDownRect.set(mFrame)
                mActiveHandle = hitTest(mDownX, mDownY)
                invalidate()
                return mActiveHandle != HANDLE_NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mActiveHandle == HANDLE_NONE) return false
                applyDrag(e.x, e.y, false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mActiveHandle == HANDLE_NONE) return false
                applyDrag(e.x, e.y, true)
                mActiveHandle = HANDLE_NONE
                invalidate()
                return true
            }
        }
        return false
    }

    private fun hyp(dx: Float, dy: Float): Float =
        Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

    private fun hitTest(x: Float, y: Float): Int {
        val sx = width.toFloat() / mClusterW
        val sy = height.toFloat() / mClusterH
        val l = mFrame.left * sx
        val t = mFrame.top * sy
        val r = mFrame.right * sx
        val b = mFrame.bottom * sy
        val mx = (l + r) / 2f
        val my = (t + b) / 2f
        val h = dp(56f) // generous tactile radius for car use
        // Corners win over edges; closest wins among corners.
        var best = HANDLE_NONE
        var bestD = h
        var d: Float
        d = hyp(x - l, y - t); if (d < bestD) { bestD = d; best = HANDLE_TL }
        d = hyp(x - r, y - t); if (d < bestD) { bestD = d; best = HANDLE_TR }
        d = hyp(x - l, y - b); if (d < bestD) { bestD = d; best = HANDLE_BL }
        d = hyp(x - r, y - b); if (d < bestD) { bestD = d; best = HANDLE_BR }
        if (best != HANDLE_NONE) return best
        d = hyp(x - mx, y - t); if (d < bestD) { bestD = d; best = HANDLE_T }
        d = hyp(x - mx, y - b); if (d < bestD) { bestD = d; best = HANDLE_B }
        d = hyp(x - l, y - my); if (d < bestD) { bestD = d; best = HANDLE_L }
        d = hyp(x - r, y - my); if (d < bestD) { bestD = d; best = HANDLE_R }
        if (best != HANDLE_NONE) return best
        if (x >= l && x <= r && y >= t && y <= b) return HANDLE_MOVE
        return HANDLE_NONE
    }

    private fun applyDrag(x: Float, y: Float, commit: Boolean) {
        val sx = width.toFloat() / mClusterW
        val sy = height.toFloat() / mClusterH
        val dx = Math.round((x - mDownX) / sx)
        val dy = Math.round((y - mDownY) / sy)
        var l = mDownRect.left
        var t = mDownRect.top
        var r = mDownRect.right
        var b = mDownRect.bottom
        when (mActiveHandle) {
            HANDLE_TL -> { l += dx; t += dy }
            HANDLE_TR -> { r += dx; t += dy }
            HANDLE_BL -> { l += dx; b += dy }
            HANDLE_BR -> { r += dx; b += dy }
            HANDLE_T -> { t += dy }
            HANDLE_B -> { b += dy }
            HANDLE_L -> { l += dx }
            HANDLE_R -> { r += dx }
            HANDLE_MOVE -> {
                val w = r - l
                val h = b - t
                l += dx; t += dy
                if (l < 0) l = 0
                if (t < 0) t = 0
                if (l + w > mClusterW) l = mClusterW - w
                if (t + h > mClusterH) t = mClusterH - h
                r = l + w; b = t + h
            }
        }
        // Clamp + min-size for resize handles
        if (mActiveHandle != HANDLE_MOVE) {
            if (l < 0) l = 0
            if (t < 0) t = 0
            if (r > mClusterW) r = mClusterW
            if (b > mClusterH) b = mClusterH
            if (r - l < mMinSize) {
                if (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_BL || mActiveHandle == HANDLE_L)
                    l = r - mMinSize
                else r = l + mMinSize
            }
            if (b - t < mMinSize) {
                if (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_TR || mActiveHandle == HANDLE_T)
                    t = b - mMinSize
                else b = t + mMinSize
            }
        }
        // Hard grid step (avoids sub-pixel jitter from the daemon).
        l = snapStep(l); t = snapStep(t); r = snapStep(r); b = snapStep(b)
        // Soft snap to anchors (edges, center, quarters).
        if (mActiveHandle != HANDLE_T && mActiveHandle != HANDLE_B) l = softSnap(l, mSnapXs)
        if (mActiveHandle != HANDLE_T && mActiveHandle != HANDLE_B) r = softSnap(r, mSnapXs)
        if (mActiveHandle != HANDLE_L && mActiveHandle != HANDLE_R) t = softSnap(t, mSnapYs)
        if (mActiveHandle != HANDLE_L && mActiveHandle != HANDLE_R) b = softSnap(b, mSnapYs)
        // Final clamp (in case snap pushed past the bounds).
        if (l < 0) l = 0
        if (t < 0) t = 0
        if (r > mClusterW) r = mClusterW
        if (b > mClusterH) b = mClusterH
        if (r - l < mMinSize || b - t < mMinSize) {
            // Reject the snap by reverting to grid-only.
            l = snapStep(mDownRect.left + (if (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_BL || mActiveHandle == HANDLE_L || mActiveHandle == HANDLE_MOVE) dx else 0))
            t = snapStep(mDownRect.top + (if (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_TR || mActiveHandle == HANDLE_T || mActiveHandle == HANDLE_MOVE) dy else 0))
            r = snapStep(mDownRect.right + (if (mActiveHandle == HANDLE_TR || mActiveHandle == HANDLE_BR || mActiveHandle == HANDLE_R || mActiveHandle == HANDLE_MOVE) dx else 0))
            b = snapStep(mDownRect.bottom + (if (mActiveHandle == HANDLE_BL || mActiveHandle == HANDLE_BR || mActiveHandle == HANDLE_B || mActiveHandle == HANDLE_MOVE) dy else 0))
        }
        mFrame.set(l, t, r, b)
        updateGestureExclusion()
        invalidate()
        mListener?.onFrameChanged(l, t, r, b, commit)
    }

    // ── System gesture exclusion ───────────────────────────────────────────

    /**
     * Tells Android not to interpret touches on / near the handles as edge
     * gestures (back, next, system bars). Without this, dragging a corner close
     * to a screen edge often triggers back-gesture instead.
     *
     * The system caps the total excluded area to 200dp per edge; we keep each
     * rect well under that. API 29+ only (our minSdk is 29).
     *
     * v1.2.85 — also reserves a 200dp strip along every screen edge so the
     * full-frame (cluster spans the whole canvas) case is editable.
     */
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (width <= 0 || height <= 0) return
        val sx = width.toFloat() / mClusterW
        val sy = height.toFloat() / mClusterH
        val l = (mFrame.left * sx).toInt()
        val t = (mFrame.top * sy).toInt()
        val r = (mFrame.right * sx).toInt()
        val b = (mFrame.bottom * sy).toInt()
        val pad = dp(48f).toInt()
        val centers = arrayOf(
            intArrayOf(l, t), intArrayOf(r, t), intArrayOf(l, b), intArrayOf(r, b), // corners
            intArrayOf((l + r) / 2, t), intArrayOf((l + r) / 2, b), // top/bot mid
            intArrayOf(l, (t + b) / 2), intArrayOf(r, (t + b) / 2) // left/right mid
        )
        // Cap each strip at 200dp (Android's hard limit per edge; anything beyond
        // is silently ignored). We use 198dp for a tiny safety margin.
        val strip = dp(198f).toInt()
        val w = width
        val h = height
        val rects = ArrayList<Rect>(centers.size + 4)
        for (c in centers) {
            rects.add(Rect(c[0] - pad, c[1] - pad, c[0] + pad, c[1] + pad))
        }
        // Full-edge strips so edge swipes never steal the touch at the screen border.
        rects.add(Rect(0, 0, strip, h)) // left
        rects.add(Rect(w - strip, 0, w, h)) // right
        rects.add(Rect(0, h - strip, w, h)) // bottom
        rects.add(Rect(0, 0, w, strip)) // top
        try {
            systemGestureExclusionRects = rects
        } catch (ignored: Throwable) {
            // Defensive: some OEMs strip the API; the activity still works,
            // just with whatever back-gesture behavior the platform decides.
        }
    }

    /** Disables gesture exclusion (no rects) — useful when activity exits. */
    fun clearGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            systemGestureExclusionRects = emptyList()
        } catch (ignored: Throwable) {
            // no-op
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val HANDLE_NONE = 0
        private const val HANDLE_TL = 1
        private const val HANDLE_T = 2
        private const val HANDLE_TR = 3
        private const val HANDLE_L = 4
        private const val HANDLE_R = 5
        private const val HANDLE_BL = 6
        private const val HANDLE_B = 7
        private const val HANDLE_BR = 8
        private const val HANDLE_MOVE = 9

        /** Snap tolerance, cluster px (≈ 3% of 720 height). */
        private const val SNAP_TOL_PX = 24
        /** Hard grid step, cluster px. */
        private const val GRID_STEP = 10

        private fun snapStep(v: Int): Int = Math.round(v.toFloat() / GRID_STEP) * GRID_STEP

        private fun softSnap(v: Int, anchors: IntArray): Int {
            for (a in anchors) {
                if (Math.abs(v - a) <= SNAP_TOL_PX) return a
            }
            return v
        }
    }
}
