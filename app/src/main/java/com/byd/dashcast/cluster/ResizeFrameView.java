package com.byd.dashcast.cluster;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v1.2.72 — Editable frame overlay used by {@link ClusterResizeActivity}.
 *
 * <p>Internally maintains the rectangle in <b>cluster coordinates</b> (1920×720)
 * and maps linearly to view-coordinates (TextureView mirror fills the canvas).
 *
 * <p>Ergonomic targets (car HMI, not phone): 8 handles total (4 corners + 4
 * mid-edges), visual size ~24dp, hit radius ~56dp. Soft-snap to the cluster
 * edges, center and quarters with a tolerance of {@value #SNAP_TOL_PX} px,
 * plus a hard 10-px grid step to avoid sub-pixel battles.
 *
 * <p>Calls {@link View#setSystemGestureExclusionRects(java.util.List)} on every
 * frame change so Android's back-gesture / next-swipe don't steal touches that
 * land on the handles (API 29+, our target).
 */
public class ResizeFrameView extends View {

    public interface Listener {
        void onFrameChanged(int l, int t, int r, int b, boolean commit);
    }

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_TL   = 1;
    private static final int HANDLE_T    = 2;
    private static final int HANDLE_TR   = 3;
    private static final int HANDLE_L    = 4;
    private static final int HANDLE_R    = 5;
    private static final int HANDLE_BL   = 6;
    private static final int HANDLE_B    = 7;
    private static final int HANDLE_BR   = 8;
    private static final int HANDLE_MOVE = 9;

    /** Snap tolerance, cluster px (≈ 3% of 720 height). */
    private static final int SNAP_TOL_PX = 24;
    /** Hard grid step, cluster px. */
    private static final int GRID_STEP   = 10;

    private int mClusterW = 1920;
    private int mClusterH = 720;
    private int mMinSize  = 200;   // cluster px

    private final Rect mFrame = new Rect();
    private Listener mListener;

    private final Paint mFill          = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandle        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandleBorder  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandleActive  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int   mActiveHandle = HANDLE_NONE;
    private float mDownX, mDownY;
    private final Rect mDownRect = new Rect();

    public ResizeFrameView(Context c)                          { super(c);     init(); }
    public ResizeFrameView(Context c, AttributeSet a)          { super(c, a);  init(); }
    public ResizeFrameView(Context c, AttributeSet a, int s)   { super(c,a,s); init(); }

    private void init() {
        int primary = resolveThemeColor(android.R.attr.colorPrimary,    0xFF1976D2);
        int onPrim  = resolveThemeColor(android.R.attr.colorBackground, 0xFFFFFFFF);
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor((primary & 0x00FFFFFF) | 0x33000000); // ~20% alpha
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(dp(3f));
        mStroke.setColor(primary);
        mHandle.setStyle(Paint.Style.FILL);
        mHandle.setColor(onPrim);
        mHandleBorder.setStyle(Paint.Style.STROKE);
        mHandleBorder.setStrokeWidth(dp(2.5f));
        mHandleBorder.setColor(primary);
        mHandleActive.setStyle(Paint.Style.FILL);
        mHandleActive.setColor(primary);
    }

    private int resolveThemeColor(int attr, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        return fallback;
    }

    public void setClusterSize(int w, int h) {
        mClusterW = w; mClusterH = h; updateGestureExclusion(); invalidate();
    }

    public void setFrame(int l, int t, int r, int b) {
        mFrame.set(l, t, r, b);
        updateGestureExclusion();
        invalidate();
    }

    public Rect getFrame() { return new Rect(mFrame); }

    public void setListener(Listener l) { mListener = l; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        updateGestureExclusion();
    }

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
        // 8 handles: corners are round, mid-edges are pill-shaped.
        float radius = dp(12);
        drawHandle(c, l, t, radius, HANDLE_TL);
        drawHandle(c, r, t, radius, HANDLE_TR);
        drawHandle(c, l, b, radius, HANDLE_BL);
        drawHandle(c, r, b, radius, HANDLE_BR);
        drawHandle(c, (l + r) / 2f, t, radius, HANDLE_T);
        drawHandle(c, (l + r) / 2f, b, radius, HANDLE_B);
        drawHandle(c, l, (t + b) / 2f, radius, HANDLE_L);
        drawHandle(c, r, (t + b) / 2f, radius, HANDLE_R);
    }

    private void drawHandle(Canvas c, float cx, float cy, float radius, int id) {
        boolean active = (mActiveHandle == id);
        Paint p = active ? mHandleActive : mHandle;
        c.drawCircle(cx, cy, radius, p);
        c.drawCircle(cx, cy, radius, mHandleBorder);
    }

    // ── Touch handling ─────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDownX = e.getX(); mDownY = e.getY();
                mDownRect.set(mFrame);
                mActiveHandle = hitTest(mDownX, mDownY);
                invalidate();
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
                invalidate();
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
        float mx = (l + r) / 2f, my = (t + b) / 2f;
        float h = dp(56); // generous tactile radius for car use
        // Corners win over edges; closest wins among corners.
        int  best   = HANDLE_NONE;
        float bestD = h;
        float d;
        d = (float) Math.hypot(x - l, y - t); if (d < bestD) { bestD = d; best = HANDLE_TL; }
        d = (float) Math.hypot(x - r, y - t); if (d < bestD) { bestD = d; best = HANDLE_TR; }
        d = (float) Math.hypot(x - l, y - b); if (d < bestD) { bestD = d; best = HANDLE_BL; }
        d = (float) Math.hypot(x - r, y - b); if (d < bestD) { bestD = d; best = HANDLE_BR; }
        if (best != HANDLE_NONE) return best;
        d = (float) Math.hypot(x - mx, y - t); if (d < bestD) { bestD = d; best = HANDLE_T; }
        d = (float) Math.hypot(x - mx, y - b); if (d < bestD) { bestD = d; best = HANDLE_B; }
        d = (float) Math.hypot(x - l, y - my); if (d < bestD) { bestD = d; best = HANDLE_L; }
        d = (float) Math.hypot(x - r, y - my); if (d < bestD) { bestD = d; best = HANDLE_R; }
        if (best != HANDLE_NONE) return best;
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
            case HANDLE_T:           t += dy; break;
            case HANDLE_B:           b += dy; break;
            case HANDLE_L:  l += dx;          break;
            case HANDLE_R:  r += dx;          break;
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
                if (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_BL || mActiveHandle == HANDLE_L)
                     l = r - mMinSize;
                else r = l + mMinSize;
            }
            if (b - t < mMinSize) {
                if (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_TR || mActiveHandle == HANDLE_T)
                     t = b - mMinSize;
                else b = t + mMinSize;
            }
        }
        // Hard grid step (avoids sub-pixel jitter from the daemon).
        l = snapStep(l); t = snapStep(t); r = snapStep(r); b = snapStep(b);
        // Soft snap to anchors (edges, center, quarters).
        int[] xs = { 0, mClusterW / 4, mClusterW / 2, 3 * mClusterW / 4, mClusterW };
        int[] ys = { 0, mClusterH / 4, mClusterH / 2, 3 * mClusterH / 4, mClusterH };
        if (mActiveHandle != HANDLE_T && mActiveHandle != HANDLE_B) l = softSnap(l, xs);
        if (mActiveHandle != HANDLE_T && mActiveHandle != HANDLE_B) r = softSnap(r, xs);
        if (mActiveHandle != HANDLE_L && mActiveHandle != HANDLE_R) t = softSnap(t, ys);
        if (mActiveHandle != HANDLE_L && mActiveHandle != HANDLE_R) b = softSnap(b, ys);
        // Final clamp (in case snap pushed past the bounds).
        if (l < 0) l = 0; if (t < 0) t = 0;
        if (r > mClusterW) r = mClusterW; if (b > mClusterH) b = mClusterH;
        if (r - l < mMinSize || b - t < mMinSize) {
            // Reject the snap by reverting to grid-only.
            l = snapStep(mDownRect.left  + (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_BL || mActiveHandle == HANDLE_L || mActiveHandle == HANDLE_MOVE ? dx : 0));
            t = snapStep(mDownRect.top   + (mActiveHandle == HANDLE_TL || mActiveHandle == HANDLE_TR || mActiveHandle == HANDLE_T || mActiveHandle == HANDLE_MOVE ? dy : 0));
            r = snapStep(mDownRect.right + (mActiveHandle == HANDLE_TR || mActiveHandle == HANDLE_BR || mActiveHandle == HANDLE_R || mActiveHandle == HANDLE_MOVE ? dx : 0));
            b = snapStep(mDownRect.bottom+ (mActiveHandle == HANDLE_BL || mActiveHandle == HANDLE_BR || mActiveHandle == HANDLE_B || mActiveHandle == HANDLE_MOVE ? dy : 0));
        }
        mFrame.set(l, t, r, b);
        updateGestureExclusion();
        invalidate();
        if (mListener != null) mListener.onFrameChanged(l, t, r, b, commit);
    }

    private static int snapStep(int v) {
        return Math.round((float) v / GRID_STEP) * GRID_STEP;
    }

    private static int softSnap(int v, int[] anchors) {
        for (int a : anchors) {
            if (Math.abs(v - a) <= SNAP_TOL_PX) return a;
        }
        return v;
    }

    // ── System gesture exclusion ───────────────────────────────────────────

    /**
     * Tells Android not to interpret touches on / near the handles as edge
     * gestures (back, next, system bars). Without this, dragging a corner
     * close to a screen edge often triggers back-gesture instead.
     *
     * <p>The system caps the total excluded area to 200dp per edge; we keep
     * each rect well under that. API 29+ only (our minSdk is 29).
     */
    private void updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float sx = (float) getWidth()  / mClusterW;
        float sy = (float) getHeight() / mClusterH;
        int l = (int) (mFrame.left   * sx);
        int t = (int) (mFrame.top    * sy);
        int r = (int) (mFrame.right  * sx);
        int b = (int) (mFrame.bottom * sy);
        int pad = (int) dp(48);
        int[][] centers = {
                {l, t}, {r, t}, {l, b}, {r, b},                  // corners
                {(l + r) / 2, t}, {(l + r) / 2, b},               // top/bot mid
                {l, (t + b) / 2}, {r, (t + b) / 2}                // left/right mid
        };
        List<Rect> rects = new ArrayList<>(centers.length);
        for (int[] c : centers) {
            rects.add(new Rect(c[0] - pad, c[1] - pad, c[0] + pad, c[1] + pad));
        }
        try {
            setSystemGestureExclusionRects(rects);
        } catch (Throwable ignored) {
            // Defensive: some OEMs strip the API; the activity still works,
            // just with whatever back-gesture behavior the platform decides.
        }
    }

    /** Disables gesture exclusion (no rects) — useful when activity exits. */
    public void clearGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            setSystemGestureExclusionRects(new ArrayList<>(Arrays.asList()));
        } catch (Throwable ignored) { /* no-op */ }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
