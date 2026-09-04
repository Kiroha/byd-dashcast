package com.byd.dashcast.fission

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Tiny read-only rendering of a [LayoutPreset]: the cluster surface as a dark
 * rounded rect (1920:720 ratio) with each zone drawn as a colored rounded rect.
 * Used by the layout carousel on the Apps page. Same palette as the editor canvas
 * ([ClusterCanvasView]) so a layout is visually recognisable across screens.
 *
 * `@JvmOverloads` (not a defaulted primary constructor alone) is what keeps BOTH
 * `(Context)` and `(Context, AttributeSet)` as real JVM constructors: the 1-arg one is
 * what `FissionCoordinator` calls, the 2-arg one is the signature `LayoutInflater`
 * looks up by reflection should this view ever be placed in a layout XML.
 */
class LayoutMiniPreviewView @JvmOverloads constructor(
    ctx: Context,
    at: AttributeSet? = null
) : View(ctx, at) {

    private val mPaintBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPaintBorder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPaintZone = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mRect = RectF()

    private var mSlots: List<LayoutPreset.SlotDef>? = null

    init {
        mPaintBg.color = COLOR_BG
        mPaintBg.style = Paint.Style.FILL
        mPaintBorder.color = COLOR_BORDER
        mPaintBorder.style = Paint.Style.STROKE
        mPaintBorder.strokeWidth = 2f
        mPaintZone.style = Paint.Style.FILL
    }

    fun setSlots(slots: List<LayoutPreset.SlotDef>?) {
        mSlots = slots
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, Math.round(w * CH / CW.toFloat()))
    }

    override fun onDraw(c: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val r = vh * 0.12f
        mRect.set(1f, 1f, vw - 1f, vh - 1f)
        c.drawRoundRect(mRect, r, r, mPaintBg)
        c.drawRoundRect(mRect, r, r, mPaintBorder)

        val slots = mSlots
        if (slots == null || slots.isEmpty()) return

        val sx = vw / CW
        val sy = vh / CH
        val zr = Math.max(2f, vh * 0.06f)
        for (i in 0 until slots.size) {
            val s = slots[i]
            mPaintZone.color = ZONE_COLORS[i % ZONE_COLORS.size]
            mRect.set(s.x * sx + 1.5f, s.y * sy + 1.5f,
                (s.x + s.w) * sx - 1.5f, (s.y + s.h) * sy - 1.5f)
            c.drawRoundRect(mRect, zr, zr, mPaintZone)
        }
    }

    companion object {
        private const val CW = 1920
        private const val CH = 720

        private const val COLOR_BG = 0xFF060810.toInt()
        private const val COLOR_BORDER = 0xFF20283A.toInt()

        // Mirror of ClusterCanvasView.ZONE_COLORS, fully opaque for small-size legibility.
        private val ZONE_COLORS = intArrayOf(
            0xFF3949AB.toInt(), 0xFF388E3C.toInt(), 0xFFF57F17.toInt(),
            0xFFAD1457.toInt(), 0xFF0277BD.toInt(), 0xFF4527A0.toInt()
        )
    }
}
