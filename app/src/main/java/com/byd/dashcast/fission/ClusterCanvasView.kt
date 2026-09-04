package com.byd.dashcast.fission

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.byd.dashcast.R

/**
 * Editor canvas for a [LayoutPreset]: paints the 1920×720 cluster surface, its zones and their
 * corner handles, and turns touches into draw / move / resize gestures.
 *
 * `@JvmOverloads` (not a defaulted primary constructor alone) is what keeps BOTH `(Context)` and
 * `(Context, AttributeSet)` as real JVM constructors: the 2-arg one is the signature
 * `LayoutInflater` looks up by reflection for the `<com.byd.dashcast.fission.ClusterCanvasView …/>`
 * tag in `activity_layout_manager.xml`, so losing it would be an `InflateException` on the car
 * with a green build.
 */
class ClusterCanvasView @JvmOverloads constructor(
    ctx: Context,
    at: AttributeSet? = null
) : View(ctx, at) {

    private val mPaintXdja = Paint()
    private val mPaintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPaintStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPaintLabel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPaintDraw = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPaintHandle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPaintDrawStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mBgRect = RectF()

    // Per-slot label cache — keeps onDraw allocation-free in the steady state.
    // A cached label is keyed on the exact inputs that compose it (label ref,
    // w, h, displayId); onDraw rebuilds an entry only when one of those changes,
    // which during a RESIZE drag is just the single slot being resized.
    private var mLabelCache = arrayOfNulls<String>(0)
    private var mLabelName = arrayOfNulls<String>(0)
    private var mLabelW = IntArray(0)
    private var mLabelH = IntArray(0)
    private var mLabelVd = IntArray(0)
    private val mLabelSb = StringBuilder(24)

    private var mBg: Bitmap? = null

    private var mTop = 0
    private var mBottom = 0
    private var mLeft = 0
    private var mRight = 0
    private var mSlots: List<LayoutPreset.SlotDef>? = null

    private enum class DragMode { NONE, DRAW, MOVE, RESIZE }

    private var mDragMode = DragMode.NONE
    private var mDragIdx = -1
    private var mDragStartX = 0f
    private var mDragStartY = 0f
    private var mCurrentRect: RectF? = null
    private var mMoveOffsetX = 0f
    private var mMoveOffsetY = 0f
    private var mResizeCorner = -1

    fun interface OnZoneDrawnListener { fun onZoneDrawn(x: Int, y: Int, w: Int, h: Int) }
    fun interface OnZoneLongPressListener { fun onZoneLongPress(index: Int) }
    fun interface OnZoneTapListener { fun onZoneTap(index: Int) }

    private var mDrawnListener: OnZoneDrawnListener? = null
    private var mLongPressListener: OnZoneLongPressListener? = null
    private var mTapListener: OnZoneTapListener? = null
    private val mGesture: GestureDetector
    private var mScaleX = 0f
    private var mScaleY = 0f

    init {
        mPaintXdja.color = COLOR_XDJA
        mPaintXdja.style = Paint.Style.FILL

        mPaintStroke.color = 0xFFFFFFFF.toInt()
        mPaintStroke.style = Paint.Style.STROKE
        mPaintStroke.strokeWidth = 3f

        mPaintLabel.color = 0xFFFFFFFF.toInt()
        mPaintLabel.isFakeBoldText = true
        mPaintLabel.setShadowLayer(2f, 1f, 1f, Color.BLACK)

        mPaintDraw.color = COLOR_DRAWING
        mPaintDraw.style = Paint.Style.FILL

        mPaintHandle.color = 0xFFFFFFFF.toInt()
        mPaintHandle.style = Paint.Style.FILL

        mPaintDrawStroke.color = 0xFFF44336.toInt()
        mPaintDrawStroke.style = Paint.Style.STROKE
        mPaintDrawStroke.strokeWidth = 3f

        mGesture = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (mDragMode != DragMode.NONE || mLongPressListener == null || mSlots == null) return
                    val idx = hitTest(e.x, e.y)
                    if (idx >= 0) mLongPressListener?.onZoneLongPress(idx)
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (mTapListener == null || mSlots == null) return false
                    val idx = hitTest(e.x, e.y)
                    if (idx >= 0) {
                        mTapListener?.onZoneTap(idx)
                        return true
                    }
                    return false
                }
            })

        try {
            mBg = BitmapFactory.decodeResource(resources, R.drawable.cluster_bg)
        } catch (ignored: Exception) {
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val bg = mBg
        if (bg != null && !bg.isRecycled) {
            bg.recycle()
            mBg = null
        }
    }

    /**
     * NOT CALLED by anything in the tree — grepped across java, kotlin, xml and the manifest.
     *
     * Which means mTop/mBottom/mLeft/mRight are permanently 0, so the reserved-band shading at
     * :159-162 never draws and the drag clamping at :296-300 never clamps. The feature was designed
     * and the seam built; the wiring never happened. Kept rather than deleted: removing it would
     * take the shading and the clamping with it, and those are the useful half.
     */
    fun setMargins(top: Int, bottom: Int, left: Int, right: Int) {
        mTop = top
        mBottom = bottom
        mLeft = left
        mRight = right
        invalidate()
    }

    fun setSlots(slots: List<LayoutPreset.SlotDef>?) {
        mSlots = slots
        invalidate()
    }

    fun setOnZoneDrawnListener(l: OnZoneDrawnListener?) {
        mDrawnListener = l
    }

    fun setOnZoneLongPressListener(l: OnZoneLongPressListener?) {
        mLongPressListener = l
    }

    fun setOnZoneTapListener(l: OnZoneTapListener?) {
        mTapListener = l
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, (w * CH / CW.toFloat()).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        mScaleX = w.toFloat() / CW
        mScaleY = h.toFloat() / CH
        mPaintLabel.textSize = Math.max(14f, 26f * mScaleX)
        mBgRect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(c: Canvas) {
        val vw = width
        val vh = height
        val bg = mBg
        if (bg != null) c.drawBitmap(bg, null, mBgRect, null)
        else c.drawColor(0xFF0A0A0A.toInt())

        val px = mLeft * mScaleX
        val py = mTop * mScaleY
        val pr = vw - mRight * mScaleX
        val pb = vh - mBottom * mScaleY
        if (mTop > 0) c.drawRect(0f, 0f, vw.toFloat(), py, mPaintXdja)
        if (mBottom > 0) c.drawRect(0f, pb, vw.toFloat(), vh.toFloat(), mPaintXdja)
        if (mLeft > 0) c.drawRect(0f, py, px, pb, mPaintXdja)
        if (mRight > 0) c.drawRect(pr, py, vw.toFloat(), pb, mPaintXdja)

        val slots = mSlots
        if (slots != null) {
            val n = slots.size
            ensureLabelCache(n)
            for (i in 0 until n) {
                val s = slots[i]
                val col = ZONE_COLORS[i % ZONE_COLORS.size]
                mPaintFill.color = col
                mPaintStroke.color = col or 0xFF000000.toInt()
                val l = s.x * mScaleX
                val t = s.y * mScaleY
                val r = (s.x + s.w) * mScaleX
                val b = (s.y + s.h) * mScaleY
                c.drawRect(l, t, r, b, mPaintFill)
                c.drawRect(l, t, r, b, mPaintStroke)
                val hr = Math.min(HANDLE_RADIUS * 0.5f, 12f)
                c.drawCircle(l, t, hr, mPaintHandle)
                c.drawCircle(r, t, hr, mPaintHandle)
                c.drawCircle(r, b, hr, mPaintHandle)
                c.drawCircle(l, b, hr, mPaintHandle)
                // `!==` on purpose: the Java compared the cached label by REFERENCE, so a rename
                // that produces an equal-but-new String still invalidates the entry.
                val cached = mLabelCache[i]
                val lbl: String
                if (cached == null || mLabelName[i] !== s.label
                    || mLabelW[i] != s.w || mLabelH[i] != s.h
                    || mLabelVd[i] != s.displayId
                ) {
                    lbl = buildLabel(s)
                    mLabelCache[i] = lbl
                    mLabelName[i] = s.label
                    mLabelW[i] = s.w
                    mLabelH[i] = s.h
                    mLabelVd[i] = s.displayId
                } else {
                    lbl = cached
                }
                drawCenteredText(c, lbl, (l + r) / 2f, (t + b) / 2f)
            }
        }

        val cur = mCurrentRect
        if (mDragMode == DragMode.DRAW && cur != null) {
            mPaintDraw.color = COLOR_DRAWING
            c.drawRect(cur, mPaintDraw)
            c.drawRect(cur, mPaintDrawStroke)
            val cw = (cur.width() / mScaleX).toInt()
            val ch = (cur.height() / mScaleY).toInt()
            val sb = mLabelSb
            sb.setLength(0)
            sb.append(cw).append('×').append(ch)
            drawCenteredLine(c, sb, 0, sb.length, cur.centerX(), cur.centerY())
        }
    }

    // Draws a cached multi-line slot label. The String itself is built once in
    // buildLabel() and reused across frames (see mLabelCache), so this path does
    // not allocate per frame; lines are scanned by index instead of regex-split
    // into a fresh String[].
    private fun drawCenteredText(c: Canvas, text: String, cx: Float, cy: Float) {
        var lineCount = 1
        var nl = text.indexOf('\n')
        while (nl >= 0) {
            lineCount++
            nl = text.indexOf('\n', nl + 1)
        }
        val lh = mPaintLabel.textSize * 1.3f
        var y = cy - lh * (lineCount - 1) / 2f
        var start = 0
        while (true) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            val tw = mPaintLabel.measureText(text, start, end)
            c.drawText(text, start, end, cx - tw / 2f, y, mPaintLabel)
            y += lh
            if (end >= text.length) break
            start = end + 1
        }
    }

    // Single-line, allocation-free centered draw for the transient rubber-band
    // label: measures and draws straight from a CharSequence (the reused
    // mLabelSb) so no per-frame String is created while a zone is being drawn.
    private fun drawCenteredLine(c: Canvas, text: CharSequence, start: Int, end: Int, cx: Float, cy: Float) {
        val tw = mPaintLabel.measureText(text, start, end)
        c.drawText(text, start, end, cx - tw / 2f, cy, mPaintLabel)
    }

    // Grows the parallel label-cache arrays to hold at least n entries, keeping
    // existing entries. New tail slots start null, forcing a one-time rebuild.
    private fun ensureLabelCache(n: Int) {
        if (mLabelCache.size >= n) return
        mLabelCache = mLabelCache.copyOf(n)
        mLabelName = mLabelName.copyOf(n)
        mLabelW = mLabelW.copyOf(n)
        mLabelH = mLabelH.copyOf(n)
        mLabelVd = mLabelVd.copyOf(n)
    }

    // Composes a slot's multi-line label into the reused StringBuilder, then
    // snapshots it to a String for caching. append(int)/append(char) write the
    // digits straight into the builder's buffer with no intermediate Strings.
    private fun buildLabel(s: LayoutPreset.SlotDef): String {
        val sb = mLabelSb
        sb.setLength(0)
        sb.append(s.label).append('\n').append(s.w).append('×').append(s.h)
        if (s.displayId >= 0) sb.append('\n').append("VD:").append(s.displayId)
        return sb.toString()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        mGesture.onTouchEvent(event)
        val vx = event.x
        val vy = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val cornerIdx = hitCorner(vx, vy)
                if (cornerIdx >= 0) {
                    mDragMode = DragMode.RESIZE
                    mDragIdx = cornerIdx
                    return true
                }
                val moveIdx = hitTest(vx, vy)
                if (moveIdx >= 0) {
                    mDragMode = DragMode.MOVE
                    mDragIdx = moveIdx
                    val s = mSlots!![moveIdx]
                    mMoveOffsetX = vx - s.x * mScaleX
                    mMoveOffsetY = vy - s.y * mScaleY
                    return true
                }
                if (isInProjectionZone(vx, vy)) {
                    mDragMode = DragMode.DRAW
                    mDragStartX = snapX(vx / mScaleX, -1) * mScaleX
                    mDragStartY = snapY(vy / mScaleY, -1) * mScaleY
                    mCurrentRect = RectF(mDragStartX, mDragStartY, mDragStartX, mDragStartY)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slots = mSlots
                if (mDragMode == DragMode.MOVE && mDragIdx >= 0
                    && slots != null && mDragIdx < slots.size
                ) {
                    val s = slots[mDragIdx]
                    var nx = (vx - mMoveOffsetX) / mScaleX
                    var ny = (vy - mMoveOffsetY) / mScaleY
                    nx = Math.max(mLeft.toFloat(), Math.min(nx, (CW - mRight - s.w).toFloat()))
                    ny = Math.max(mTop.toFloat(), Math.min(ny, (CH - mBottom - s.h).toFloat()))
                    nx = snapEdgeX(nx, nx + s.w, mDragIdx)
                    ny = snapEdgeY(ny, ny + s.h, mDragIdx)
                    nx = Math.max(mLeft.toFloat(), Math.min(nx, (CW - mRight - s.w).toFloat()))
                    ny = Math.max(mTop.toFloat(), Math.min(ny, (CH - mBottom - s.h).toFloat()))
                    s.x = nx.toInt()
                    s.y = ny.toInt()
                    invalidate()
                    return true
                }
                if (mDragMode == DragMode.RESIZE && mDragIdx >= 0
                    && slots != null && mDragIdx < slots.size
                ) {
                    val s = slots[mDragIdx]
                    var cx = clampX(vx) / mScaleX
                    var cy = clampY(vy) / mScaleY
                    cx = snapX(cx, mDragIdx)
                    cy = snapY(cy, mDragIdx)
                    val r = s.x + s.w
                    val b = s.y + s.h
                    when (mResizeCorner) {
                        0 -> { s.w = Math.max(40, r - cx.toInt()); s.h = Math.max(20, b - cy.toInt()); s.x = r - s.w; s.y = b - s.h }
                        1 -> { s.w = Math.max(40, cx.toInt() - s.x); s.h = Math.max(20, b - cy.toInt()); s.y = b - s.h }
                        2 -> { s.w = Math.max(40, cx.toInt() - s.x); s.h = Math.max(20, cy.toInt() - s.y) }
                        3 -> { s.w = Math.max(40, r - cx.toInt()); s.h = Math.max(20, cy.toInt() - s.y); s.x = r - s.w }
                    }
                    invalidate()
                    return true
                }
                val cur = mCurrentRect
                if (mDragMode == DragMode.DRAW && cur != null) {
                    val x = clampX(vx)
                    val y = clampY(vy)
                    val xSnap = snapX(x / mScaleX, -1) * mScaleX
                    val ySnap = snapY(y / mScaleY, -1) * mScaleY
                    cur.set(Math.min(mDragStartX, xSnap), Math.min(mDragStartY, ySnap),
                        Math.max(mDragStartX, xSnap), Math.max(mDragStartY, ySnap))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cur = mCurrentRect
                if (mDragMode == DragMode.DRAW && cur != null
                    && cur.width() > 20 && cur.height() > 20
                ) {
                    val cx = (cur.left / mScaleX).toInt()
                    val cy = (cur.top / mScaleY).toInt()
                    val cw = (cur.width() / mScaleX).toInt()
                    val ch = (cur.height() / mScaleY).toInt()
                    mDrawnListener?.onZoneDrawn(cx, cy, cw, ch)
                }
                mDragMode = DragMode.NONE
                mDragIdx = -1
                mCurrentRect = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitCorner(vx: Float, vy: Float): Int {
        val slots = mSlots ?: return -1
        for (i in slots.size - 1 downTo 0) {
            val s = slots[i]
            val sl = s.x * mScaleX
            val st = s.y * mScaleY
            val sr = (s.x + s.w) * mScaleX
            val sb = (s.y + s.h) * mScaleY
            if (near(vx, vy, sl, st)) { mResizeCorner = 0; return i }
            if (near(vx, vy, sr, st)) { mResizeCorner = 1; return i }
            if (near(vx, vy, sr, sb)) { mResizeCorner = 2; return i }
            if (near(vx, vy, sl, sb)) { mResizeCorner = 3; return i }
        }
        return -1
    }

    private fun near(vx: Float, vy: Float, px: Float, py: Float): Boolean {
        return Math.abs(vx - px) < HANDLE_RADIUS && Math.abs(vy - py) < HANDLE_RADIUS
    }

    private fun isInProjectionZone(vx: Float, vy: Float): Boolean {
        return vx >= mLeft * mScaleX && vx <= width - mRight * mScaleX
            && vy >= mTop * mScaleY && vy <= height - mBottom * mScaleY
    }

    private fun clampX(x: Float): Float = Math.max(mLeft * mScaleX, Math.min(x, width - mRight * mScaleX))

    private fun clampY(y: Float): Float = Math.max(mTop * mScaleY, Math.min(y, height - mBottom * mScaleY))

    private fun hitTest(vx: Float, vy: Float): Int {
        val slots = mSlots ?: return -1
        for (i in slots.size - 1 downTo 0) {
            val s = slots[i]
            if (vx >= s.x * mScaleX && vx <= (s.x + s.w) * mScaleX
                && vy >= s.y * mScaleY && vy <= (s.y + s.h) * mScaleY
            ) return i
        }
        return -1
    }

    // snapX/snapY run several times per ACTION_MOVE event — candidate edges are
    // compared inline rather than packed into throwaway float[] arrays.

    private fun snapX(x: Float, excludeIdx: Int): Float {
        var best = x
        var bestDist = SNAP_THRESHOLD
        var d = Math.abs(x - mLeft)
        if (d < bestDist) { bestDist = d; best = mLeft.toFloat() }
        d = Math.abs(x - (CW - mRight))
        if (d < bestDist) { bestDist = d; best = (CW - mRight).toFloat() }
        val slots = mSlots
        if (slots != null) {
            for (i in 0 until slots.size) {
                if (i == excludeIdx) continue
                val s = slots[i]
                d = Math.abs(x - s.x)
                if (d < bestDist) { bestDist = d; best = s.x.toFloat() }
                d = Math.abs(x - (s.x + s.w))
                if (d < bestDist) { bestDist = d; best = (s.x + s.w).toFloat() }
            }
        }
        return best
    }

    private fun snapY(y: Float, excludeIdx: Int): Float {
        var best = y
        var bestDist = SNAP_THRESHOLD
        var d = Math.abs(y - mTop)
        if (d < bestDist) { bestDist = d; best = mTop.toFloat() }
        d = Math.abs(y - (CH - mBottom))
        if (d < bestDist) { bestDist = d; best = (CH - mBottom).toFloat() }
        val slots = mSlots
        if (slots != null) {
            for (i in 0 until slots.size) {
                if (i == excludeIdx) continue
                val s = slots[i]
                d = Math.abs(y - s.y)
                if (d < bestDist) { bestDist = d; best = s.y.toFloat() }
                d = Math.abs(y - (s.y + s.h))
                if (d < bestDist) { bestDist = d; best = (s.y + s.h).toFloat() }
            }
        }
        return best
    }

    private fun snapEdgeX(a: Float, b: Float, excl: Int): Float {
        val offA = snapX(a, excl) - a
        val offB = snapX(b, excl) - b
        return if (Math.abs(offA) <= Math.abs(offB)) a + offA else a + offB
    }

    private fun snapEdgeY(a: Float, b: Float, excl: Int): Float {
        val offA = snapY(a, excl) - a
        val offB = snapY(b, excl) - b
        return if (Math.abs(offA) <= Math.abs(offB)) a + offA else a + offB
    }

    companion object {
        private const val CW = 1920
        private const val CH = 720
        private const val COLOR_XDJA = 0x99000000.toInt()
        private const val COLOR_DRAWING = 0xAAF44336.toInt()
        private const val HANDLE_RADIUS = 32f
        private const val SNAP_THRESHOLD = 30f

        private val ZONE_COLORS = intArrayOf(
            0x883949AB.toInt(), 0x88388E3C.toInt(), 0x88F57F17.toInt(),
            0x88AD1457.toInt(), 0x880277BD.toInt(), 0x884527A0.toInt()
        )
    }
}
