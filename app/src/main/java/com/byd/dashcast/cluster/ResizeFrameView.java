package com.byd.dashcast.cluster;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * v1.2.71 — Editable frame overlay used by {@link ClusterResizeActivity}.
 *
 * <p>Internally maintains the rectangle in <b>cluster coordinates</b> (1920×720)
 * so calls to {@code BetaProxyClient.moveAndResize(...)} are direct, and uses a
 * simple linear mapping (view ↔ cluster) since the mirror is rendered at the
 * exact aspect ratio of the cluster.
 *
 * <p>Touch model (kept deliberately minimal):
 * <ul>
 *   <li>Drag a corner (4 hit zones, ~64dp each) → resize from that corner.</li>
 *   <li>Drag any other point inside the frame → translate.</li>
 *   <li>Tap outside the frame → no-op.</li>
 * </ul>
 *
 * <p>Listener receives every change for live-preview (throttled by the host),
 * plus a {@code commit} signal on ACTION_UP for guaranteed final delivery.
 */
public class ResizeFrameView extends View {

    public interface Listener {
        void onFrameChanged(int l, int t, int r, int b, boolean commit);
    }

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_TL   = 1;
    private static final int HANDLE_TR   = 2;
    private static final int HANDLE_BL   = 3;
    private static final int HANDLE_BR   = 4;
    private static final int HANDLE_MOVE = 5;

    private int mClusterW = 1920;
    private int mClusterH = 720;
    private int mMinSize  = 200;   // cluster px

    private final Rect mFrame = new Rect();
    private Listener mListener;

    private final Paint mFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandle = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int   mActiveHandle = HANDLE_NONE;
    private float mDownX, mDownY;
    private Rect  mDownRect = new Rect();

    public ResizeFrameView(Context c)                          { super(c);     init(); }
    public ResizeFrameView(Context c, AttributeSet a)          { super(c, a);  init(); }
    public ResizeFrameView(Context c, AttributeSet a, int s)   { super(c,a,s); init(); }

    private void init() {
        // Material 3 theming: pull colorPrimary / onSurface from the current
        // theme so the overlay stays consistent with day/night and any
        // future dynamic-color change.
        int primary = resolveThemeColor(android.R.attr.colorPrimary, 0xFF1976D2);
        int onPrim  = resolveThemeColor(android.R.attr.colorBackground, 0xFFFFFFFF);
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor((primary & 0x00FFFFFF) | 0x33000000); // ~20% alpha
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(dp(2.5f));
        mStroke.setColor(primary);
        mHandle.setStyle(Paint.Style.FILL);
        mHandle.setColor(onPrim);
    }

    private int resolveThemeColor(int attr, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        return fallback;
    }

    public void setClusterSize(int w, int h) {
        mClusterW = w; mClusterH = h; invalidate();
    }

    public void setFrame(int l, int t, int r, int b) {
        mFrame.set(l, t, r, b);
        invalidate();
    }

    public Rect getFrame() { return new Rect(mFrame); }

    public void setListener(Listener l) { mListener = l; }

    // ── Drawing ────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas c) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float sx = (float) getWidth()  / mClusterW;
        float sy = (float) getHeight() / mClusterH;
        float l = mFrame.left   * sx;
        float t = mFrame.top    * sy;
        float r = mFrame.right  * sx;
        float b = mFrame.bottom * sy;
        c.drawRect(l, t, r, b, mFill);
        c.drawRect(l, t, r, b, mStroke);
        float hs = dp(8);
        c.drawRect(l - hs, t - hs, l + hs, t + hs, mHandle);
        c.drawRect(r - hs, t - hs, r + hs, t + hs, mHandle);
        c.drawRect(l - hs, b - hs, l + hs, b + hs, mHandle);
        c.drawRect(r - hs, b - hs, r + hs, b + hs, mHandle);
    }

    // ── Touch handling ─────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDownX = e.getX(); mDownY = e.getY();
                mDownRect.set(mFrame);
                mActiveHandle = hitTest(mDownX, mDownY);
                return mActiveHandle != HANDLE_NONE;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActiveHandle == HANDLE_NONE) return false;
                applyDrag(e.getX(), e.getY(), false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mActiveHandle == HANDLE_NONE) return false;
                applyDrag(e.getX(), e.getY(), true);
                mActiveHandle = HANDLE_NONE;
                return true;
            }
        }
        return false;
    }

    private int hitTest(float x, float y) {
        float sx = (float) getWidth()  / mClusterW;
        float sy = (float) getHeight() / mClusterH;
        float l = mFrame.left * sx, t = mFrame.top * sy;
        float r = mFrame.right * sx, b = mFrame.bottom * sy;
        float h = dp(32); // corner hit radius
        if (Math.hypot(x - l, y - t) < h) return HANDLE_TL;
        if (Math.hypot(x - r, y - t) < h) return HANDLE_TR;
        if (Math.hypot(x - l, y - b) < h) return HANDLE_BL;
        if (Math.hypot(x - r, y - b) < h) return HANDLE_BR;
        if (x >= l && x <= r && y >= t && y <= b) return HANDLE_MOVE;
        return HANDLE_NONE;
    }

    private void applyDrag(float x, float y, boolean commit) {
        float sx = (float) getWidth()  / mClusterW;
        float sy = (float) getHeight() / mClusterH;
        int dx = Math.round((x - mDownX) / sx);
        int dy = Math.round((y - mDownY) / sy);
        int l = mDownRect.left, t = mDownRect.top;
        int r = mDownRect.right, b = mDownRect.bottom;
        switch (mActiveHandle) {
            case HANDLE_TL: l += dx; t += dy; break;
            case HANDLE_TR: r += dx; t += dy; break;
            case HANDLE_BL: l += dx; b += dy; break;
            case HANDLE_BR: r += dx; b += dy; break;
            case HANDLE_MOVE: {
                int w = r - l, h = b - t;
                l += dx; t += dy;
                if (l < 0) l = 0;
                if (t < 0) t = 0;
                if (l + w > mClusterW) l = mClusterW - w;
                if (t + h > mClusterH) t = mClusterH - h;
                r = l + w; b = t + h;
                break;
            }
        }
        // Clamp + min-size for resize handles
        if (mActiveHandle != HANDLE_MOVE) {
            if (l < 0) l = 0;
            if (t < 0) t = 0;
            if (r > mClusterW) r = mClusterW;
            if (b > mClusterH) b = mClusterH;
            if (r - l < mMinSize) {
                if (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_BL) l = r - mMinSize;
                else                                                          r = l + mMinSize;
            }
            if (b - t < mMinSize) {
                if (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_TR) t = b - mMinSize;
                else                                                          b = t + mMinSize;
            }
        }
        mFrame.set(l, t, r, b);
        invalidate();
        if (mListener != null) mListener.onFrameChanged(l, t, r, b, commit);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
