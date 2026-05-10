package com.byd.dashcast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Transparent overlay drawn on top of the cluster mirror.
 * Shows the current wm overscan inset margins as semi-transparent orange bands
 * so the user can preview the effect before tapping "Apply".
 */
public class InsetOverlayView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int   mInsetH = 0;   // horizontal inset in cluster pixels
    private int   mInsetV = 0;   // vertical inset in cluster pixels
    private float mScale  = 0f;  // cluster-to-view scale factor
    private float mOffX   = 0f;  // x offset of projected image inside view
    private float mOffY   = 0f;  // y offset of projected image inside view
    private boolean mShow = false;

    public InsetOverlayView(Context context) {
        super(context);
        init();
    }

    public InsetOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        mPaint.setColor(0xAAFF6F00); // semi-transparent orange
        mPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    /** Update the inset values (in cluster display pixels) and redraw. */
    public void setInsets(int insetH, int insetV) {
        mInsetH = insetH;
        mInsetV = insetV;
        postInvalidate();
    }

    /** Update the projection parameters from ClusterMirrorManager. */
    public void setProjection(float scale, float offX, float offY) {
        mScale = scale;
        mOffX  = offX;
        mOffY  = offY;
        postInvalidate();
    }

    /** Show or hide the overlay bands. */
    public void setOverlayVisible(boolean show) {
        mShow = show;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mShow || mScale <= 0f || (mInsetH == 0 && mInsetV == 0)) return;

        int w = getWidth();
        int h = getHeight();

        float hPx = mInsetH * mScale;  // inset width in view pixels
        float vPx = mInsetV * mScale;  // inset height in view pixels

        // Projected image bounds inside the view (mirror offset from letterboxing)
        float x0 = mOffX;
        float x1 = w - mOffX;
        float y0 = mOffY;
        float y1 = h - mOffY;

        // Left band
        if (hPx > 0) canvas.drawRect(x0, y0, x0 + hPx, y1, mPaint);
        // Right band
        if (hPx > 0) canvas.drawRect(x1 - hPx, y0, x1, y1, mPaint);
        // Top band (between left and right bands)
        if (vPx > 0) canvas.drawRect(x0 + hPx, y0, x1 - hPx, y0 + vPx, mPaint);
        // Bottom band
        if (vPx > 0) canvas.drawRect(x0 + hPx, y1 - vPx, x1 - hPx, y1, mPaint);
    }
}
