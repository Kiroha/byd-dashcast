package com.byd.dashcast.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of the cluster mirror.
 * Shows the current wm overscan inset margins as semi-transparent orange bands
 * so the user can preview the effect before tapping "Apply".
 */
class InsetOverlayView : View {

    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var mInsetH = 0     // horizontal inset in cluster pixels
    private var mInsetV = 0     // vertical inset in cluster pixels
    private var mScale = 0f     // cluster-to-view scale factor
    private var mOffX = 0f      // x offset of projected image inside view
    private var mOffY = 0f      // y offset of projected image inside view
    private var mShow = false

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    private fun init() {
        mPaint.color = 0xAAFF6F00.toInt() // semi-transparent orange
        mPaint.style = Paint.Style.FILL
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    /** Update the inset values (in cluster display pixels) and redraw. */
    fun setInsets(insetH: Int, insetV: Int) {
        mInsetH = insetH
        mInsetV = insetV
        postInvalidate()
    }

    /** Update the projection parameters from ClusterMirrorManager. */
    fun setProjection(scale: Float, offX: Float, offY: Float) {
        mScale = scale
        mOffX = offX
        mOffY = offY
        postInvalidate()
    }

    /** Show or hide the overlay bands. */
    fun setOverlayVisible(show: Boolean) {
        mShow = show
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!mShow || mScale <= 0f || (mInsetH == 0 && mInsetV == 0)) return

        val w = width
        val h = height

        val hPx = mInsetH * mScale  // inset width in view pixels
        val vPx = mInsetV * mScale  // inset height in view pixels

        // Projected image bounds inside the view (mirror offset from letterboxing)
        val x0 = mOffX
        val x1 = w - mOffX
        val y0 = mOffY
        val y1 = h - mOffY

        // Left band
        if (hPx > 0) canvas.drawRect(x0, y0, x0 + hPx, y1, mPaint)
        // Right band
        if (hPx > 0) canvas.drawRect(x1 - hPx, y0, x1, y1, mPaint)
        // Top band (between left and right bands)
        if (vPx > 0) canvas.drawRect(x0 + hPx, y0, x1 - hPx, y0 + vPx, mPaint)
        // Bottom band
        if (vPx > 0) canvas.drawRect(x0 + hPx, y1 - vPx, x1 - hPx, y1, mPaint)
    }
}
