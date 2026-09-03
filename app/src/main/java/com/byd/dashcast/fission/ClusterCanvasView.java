package com.byd.dashcast.fission;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import com.byd.dashcast.R;
import java.util.List;

public class ClusterCanvasView extends View {

    private static final int   CW             = 1920;
    private static final int   CH             = 720;
    private static final int   COLOR_XDJA     = 0x99000000;
    private static final int   COLOR_DRAWING  = 0xAAF44336;
    private static final float HANDLE_RADIUS  = 32f;
    private static final float SNAP_THRESHOLD = 30f;

    private final Paint mPaintXdja   = new Paint();
    private final Paint mPaintFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintLabel  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintDraw   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintHandle     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintDrawStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBgRect          = new RectF();

    // Per-slot label cache — keeps onDraw allocation-free in the steady state.
    // A cached label is keyed on the exact inputs that compose it (label ref,
    // w, h, displayId); onDraw rebuilds an entry only when one of those changes,
    // which during a RESIZE drag is just the single slot being resized.
    private String[]            mLabelCache = new String[0];
    private String[]            mLabelName  = new String[0];
    private int[]               mLabelW     = new int[0];
    private int[]               mLabelH     = new int[0];
    private int[]               mLabelVd    = new int[0];
    private final StringBuilder mLabelSb    = new StringBuilder(24);

    private Bitmap mBg;

    private int mTop, mBottom, mLeft, mRight;
    private List<LayoutPreset.SlotDef> mSlots;

    private enum DragMode { NONE, DRAW, MOVE, RESIZE }
    private DragMode mDragMode  = DragMode.NONE;
    private int      mDragIdx   = -1;
    private float    mDragStartX, mDragStartY;
    private RectF    mCurrentRect;
    private float    mMoveOffsetX, mMoveOffsetY;
    private int      mResizeCorner = -1;

    public interface OnZoneDrawnListener     { void onZoneDrawn(int x, int y, int w, int h); }
    public interface OnZoneLongPressListener { void onZoneLongPress(int index); }
    public interface OnZoneTapListener       { void onZoneTap(int index); }

    private OnZoneDrawnListener     mDrawnListener;
    private OnZoneLongPressListener mLongPressListener;
    private OnZoneTapListener       mTapListener;
    private GestureDetector         mGesture;
    private float                   mScaleX, mScaleY;

    private static final int[] ZONE_COLORS = {
        0x883949AB, 0x88388E3C, 0x88F57F17,
        0x88AD1457, 0x880277BD, 0x884527A0
    };

    public ClusterCanvasView(Context ctx)                  { this(ctx, null); }
    public ClusterCanvasView(Context ctx, AttributeSet at) { super(ctx, at);  init(); }

    private void init() {
        mPaintXdja.setColor(COLOR_XDJA);
        mPaintXdja.setStyle(Paint.Style.FILL);

        mPaintStroke.setColor(0xFFFFFFFF);
        mPaintStroke.setStyle(Paint.Style.STROKE);
        mPaintStroke.setStrokeWidth(3f);

        mPaintLabel.setColor(0xFFFFFFFF);
        mPaintLabel.setFakeBoldText(true);
        mPaintLabel.setShadowLayer(2f, 1f, 1f, Color.BLACK);

        mPaintDraw.setColor(COLOR_DRAWING);
        mPaintDraw.setStyle(Paint.Style.FILL);

        mPaintHandle.setColor(0xFFFFFFFF);
        mPaintHandle.setStyle(Paint.Style.FILL);

        mPaintDrawStroke.setColor(0xFFF44336);
        mPaintDrawStroke.setStyle(Paint.Style.STROKE);
        mPaintDrawStroke.setStrokeWidth(3f);

        mGesture = new GestureDetector(getContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public void onLongPress(MotionEvent e) {
                    if (mDragMode != DragMode.NONE || mLongPressListener == null || mSlots == null) return;
                    int idx = hitTest(e.getX(), e.getY());
                    if (idx >= 0) mLongPressListener.onZoneLongPress(idx);
                }
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    if (mTapListener == null || mSlots == null) return false;
                    int idx = hitTest(e.getX(), e.getY());
                    if (idx >= 0) { mTapListener.onZoneTap(idx); return true; }
                    return false;
                }
            });

        try {
            mBg = BitmapFactory.decodeResource(getResources(), R.drawable.cluster_bg);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mBg != null && !mBg.isRecycled()) { mBg.recycle(); mBg = null; }
    }

    /**
     * NOT CALLED by anything in the tree — grepped across java, kotlin, xml and the manifest.
     *
     * Which means mTop/mBottom/mLeft/mRight are permanently 0, so the reserved-band shading at
     * :159-162 never draws and the drag clamping at :296-300 never clamps. The feature was designed
     * and the seam built; the wiring never happened. Kept rather than deleted: removing it would
     * take the shading and the clamping with it, and those are the useful half.
     */
    public void setMargins(int top, int bottom, int left, int right) {
        mTop = top; mBottom = bottom; mLeft = left; mRight = right;
        invalidate();
    }

    public void setSlots(List<LayoutPreset.SlotDef> slots) { mSlots = slots; invalidate(); }
    public void setOnZoneDrawnListener(OnZoneDrawnListener l)         { mDrawnListener = l; }
    public void setOnZoneLongPressListener(OnZoneLongPressListener l) { mLongPressListener = l; }
    public void setOnZoneTapListener(OnZoneTapListener l)             { mTapListener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (w * CH / (float) CW));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mScaleX = (float) w / CW;
        mScaleY = (float) h / CH;
        mPaintLabel.setTextSize(Math.max(14f, 26f * mScaleX));
        mBgRect.set(0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        int vw = getWidth(), vh = getHeight();
        if (mBg != null) c.drawBitmap(mBg, null, mBgRect, null);
        else c.drawColor(0xFF0A0A0A);

        float px = mLeft   * mScaleX, py  = mTop    * mScaleY;
        float pr = vw - mRight * mScaleX, pb = vh - mBottom * mScaleY;
        if (mTop    > 0) c.drawRect(0,  0,  vw, py, mPaintXdja);
        if (mBottom > 0) c.drawRect(0,  pb, vw, vh, mPaintXdja);
        if (mLeft   > 0) c.drawRect(0,  py, px, pb, mPaintXdja);
        if (mRight  > 0) c.drawRect(pr, py, vw, pb, mPaintXdja);

        List<LayoutPreset.SlotDef> slots = mSlots;
        if (slots != null) {
            int n = slots.size();
            ensureLabelCache(n);
            for (int i = 0; i < n; i++) {
                LayoutPreset.SlotDef s   = slots.get(i);
                int col = ZONE_COLORS[i % ZONE_COLORS.length];
                mPaintFill.setColor(col);
                mPaintStroke.setColor(col | 0xFF000000);
                float l = s.x * mScaleX, t = s.y * mScaleY;
                float r = (s.x + s.w) * mScaleX, b = (s.y + s.h) * mScaleY;
                c.drawRect(l, t, r, b, mPaintFill);
                c.drawRect(l, t, r, b, mPaintStroke);
                float hr = Math.min(HANDLE_RADIUS * 0.5f, 12f);
                c.drawCircle(l, t, hr, mPaintHandle);
                c.drawCircle(r, t, hr, mPaintHandle);
                c.drawCircle(r, b, hr, mPaintHandle);
                c.drawCircle(l, b, hr, mPaintHandle);
                String lbl = mLabelCache[i];
                if (lbl == null || mLabelName[i] != s.label
                        || mLabelW[i] != s.w || mLabelH[i] != s.h
                        || mLabelVd[i] != s.displayId) {
                    lbl            = buildLabel(s);
                    mLabelCache[i] = lbl;
                    mLabelName[i]  = s.label;
                    mLabelW[i]     = s.w;
                    mLabelH[i]     = s.h;
                    mLabelVd[i]    = s.displayId;
                }
                drawCenteredText(c, lbl, (l + r) / 2f, (t + b) / 2f);
            }
        }

        if (mDragMode == DragMode.DRAW && mCurrentRect != null) {
            mPaintDraw.setColor(COLOR_DRAWING);
            c.drawRect(mCurrentRect, mPaintDraw);
            c.drawRect(mCurrentRect, mPaintDrawStroke);
            int cw = (int) (mCurrentRect.width()  / mScaleX);
            int ch = (int) (mCurrentRect.height() / mScaleY);
            StringBuilder sb = mLabelSb;
            sb.setLength(0);
            sb.append(cw).append('×').append(ch);
            drawCenteredLine(c, sb, 0, sb.length(),
                    mCurrentRect.centerX(), mCurrentRect.centerY());
        }
    }

    // Draws a cached multi-line slot label. The String itself is built once in
    // buildLabel() and reused across frames (see mLabelCache), so this path does
    // not allocate per frame; lines are scanned by index instead of regex-split
    // into a fresh String[].
    private void drawCenteredText(Canvas c, String text, float cx, float cy) {
        int lineCount = 1;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) lineCount++;
        float lh = mPaintLabel.getTextSize() * 1.3f;
        float y = cy - lh * (lineCount - 1) / 2f;
        int start = 0;
        while (true) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            float tw = mPaintLabel.measureText(text, start, end);
            c.drawText(text, start, end, cx - tw / 2f, y, mPaintLabel);
            y += lh;
            if (end >= text.length()) break;
            start = end + 1;
        }
    }

    // Single-line, allocation-free centered draw for the transient rubber-band
    // label: measures and draws straight from a CharSequence (the reused
    // mLabelSb) so no per-frame String is created while a zone is being drawn.
    private void drawCenteredLine(Canvas c, CharSequence text, int start, int end, float cx, float cy) {
        float tw = mPaintLabel.measureText(text, start, end);
        c.drawText(text, start, end, cx - tw / 2f, cy, mPaintLabel);
    }

    // Grows the parallel label-cache arrays to hold at least n entries, keeping
    // existing entries. New tail slots start null, forcing a one-time rebuild.
    private void ensureLabelCache(int n) {
        if (mLabelCache.length >= n) return;
        mLabelCache = java.util.Arrays.copyOf(mLabelCache, n);
        mLabelName  = java.util.Arrays.copyOf(mLabelName,  n);
        mLabelW     = java.util.Arrays.copyOf(mLabelW,     n);
        mLabelH     = java.util.Arrays.copyOf(mLabelH,     n);
        mLabelVd    = java.util.Arrays.copyOf(mLabelVd,    n);
    }

    // Composes a slot's multi-line label into the reused StringBuilder, then
    // snapshots it to a String for caching. append(int)/append(char) write the
    // digits straight into the builder's buffer with no intermediate Strings.
    private String buildLabel(LayoutPreset.SlotDef s) {
        StringBuilder sb = mLabelSb;
        sb.setLength(0);
        sb.append(s.label).append('\n').append(s.w).append('×').append(s.h);
        if (s.displayId >= 0) sb.append('\n').append("VD:").append(s.displayId);
        return sb.toString();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGesture.onTouchEvent(event);
        float vx = event.getX(), vy = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                int cornerIdx = hitCorner(vx, vy);
                if (cornerIdx >= 0) {
                    mDragMode = DragMode.RESIZE; mDragIdx = cornerIdx;
                    return true;
                }
                int moveIdx = hitTest(vx, vy);
                if (moveIdx >= 0) {
                    mDragMode = DragMode.MOVE; mDragIdx = moveIdx;
                    LayoutPreset.SlotDef s = mSlots.get(moveIdx);
                    mMoveOffsetX = vx - s.x * mScaleX;
                    mMoveOffsetY = vy - s.y * mScaleY;
                    return true;
                }
                if (isInProjectionZone(vx, vy)) {
                    mDragMode    = DragMode.DRAW;
                    mDragStartX  = snapX(vx / mScaleX, -1) * mScaleX;
                    mDragStartY  = snapY(vy / mScaleY, -1) * mScaleY;
                    mCurrentRect = new RectF(mDragStartX, mDragStartY, mDragStartX, mDragStartY);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mDragMode == DragMode.MOVE && mDragIdx >= 0
                        && mSlots != null && mDragIdx < mSlots.size()) {
                    LayoutPreset.SlotDef s = mSlots.get(mDragIdx);
                    float nx = (vx - mMoveOffsetX) / mScaleX;
                    float ny = (vy - mMoveOffsetY) / mScaleY;
                    nx = Math.max(mLeft, Math.min(nx, CW - mRight  - s.w));
                    ny = Math.max(mTop,  Math.min(ny, CH - mBottom - s.h));
                    nx = snapEdgeX(nx, nx + s.w, mDragIdx);
                    ny = snapEdgeY(ny, ny + s.h, mDragIdx);
                    nx = Math.max(mLeft, Math.min(nx, CW - mRight  - s.w));
                    ny = Math.max(mTop,  Math.min(ny, CH - mBottom - s.h));
                    s.x = (int) nx; s.y = (int) ny;
                    invalidate();
                    return true;
                }
                if (mDragMode == DragMode.RESIZE && mDragIdx >= 0
                        && mSlots != null && mDragIdx < mSlots.size()) {
                    LayoutPreset.SlotDef s = mSlots.get(mDragIdx);
                    float cx = clampX(vx) / mScaleX, cy = clampY(vy) / mScaleY;
                    cx = snapX(cx, mDragIdx); cy = snapY(cy, mDragIdx);
                    int r = s.x + s.w, b = s.y + s.h;
                    switch (mResizeCorner) {
                        case 0: s.w = Math.max(40, r-(int)cx); s.h = Math.max(20, b-(int)cy); s.x = r-s.w; s.y = b-s.h; break;
                        case 1: s.w = Math.max(40, (int)cx-s.x); s.h = Math.max(20, b-(int)cy); s.y = b-s.h; break;
                        case 2: s.w = Math.max(40, (int)cx-s.x); s.h = Math.max(20, (int)cy-s.y); break;
                        case 3: s.w = Math.max(40, r-(int)cx); s.h = Math.max(20, (int)cy-s.y); s.x = r-s.w; break;
                    }
                    invalidate();
                    return true;
                }
                if (mDragMode == DragMode.DRAW && mCurrentRect != null) {
                    float x = clampX(vx), y = clampY(vy);
                    float xSnap = snapX(x / mScaleX, -1) * mScaleX;
                    float ySnap = snapY(y / mScaleY, -1) * mScaleY;
                    mCurrentRect.set(Math.min(mDragStartX, xSnap), Math.min(mDragStartY, ySnap),
                                     Math.max(mDragStartX, xSnap), Math.max(mDragStartY, ySnap));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mDragMode == DragMode.DRAW && mCurrentRect != null
                        && mCurrentRect.width() > 20 && mCurrentRect.height() > 20) {
                    int cx = (int) (mCurrentRect.left   / mScaleX);
                    int cy = (int) (mCurrentRect.top    / mScaleY);
                    int cw = (int) (mCurrentRect.width() / mScaleX);
                    int ch = (int) (mCurrentRect.height() / mScaleY);
                    if (mDrawnListener != null) mDrawnListener.onZoneDrawn(cx, cy, cw, ch);
                }
                mDragMode = DragMode.NONE; mDragIdx = -1; mCurrentRect = null;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private int hitCorner(float vx, float vy) {
        if (mSlots == null) return -1;
        for (int i = mSlots.size() - 1; i >= 0; i--) {
            LayoutPreset.SlotDef s = mSlots.get(i);
            float sl = s.x * mScaleX, st = s.y * mScaleY;
            float sr = (s.x + s.w) * mScaleX, sb = (s.y + s.h) * mScaleY;
            if (near(vx, vy, sl, st)) { mResizeCorner = 0; return i; }
            if (near(vx, vy, sr, st)) { mResizeCorner = 1; return i; }
            if (near(vx, vy, sr, sb)) { mResizeCorner = 2; return i; }
            if (near(vx, vy, sl, sb)) { mResizeCorner = 3; return i; }
        }
        return -1;
    }

    private boolean near(float vx, float vy, float px, float py) {
        return Math.abs(vx - px) < HANDLE_RADIUS && Math.abs(vy - py) < HANDLE_RADIUS;
    }

    private boolean isInProjectionZone(float vx, float vy) {
        return vx >= mLeft * mScaleX && vx <= getWidth()  - mRight  * mScaleX
            && vy >= mTop  * mScaleY && vy <= getHeight() - mBottom * mScaleY;
    }

    private float clampX(float x) { return Math.max(mLeft * mScaleX, Math.min(x, getWidth()  - mRight  * mScaleX)); }
    private float clampY(float y) { return Math.max(mTop  * mScaleY, Math.min(y, getHeight() - mBottom * mScaleY)); }

    private int hitTest(float vx, float vy) {
        if (mSlots == null) return -1;
        for (int i = mSlots.size() - 1; i >= 0; i--) {
            LayoutPreset.SlotDef s = mSlots.get(i);
            if (vx >= s.x * mScaleX && vx <= (s.x + s.w) * mScaleX
             && vy >= s.y * mScaleY && vy <= (s.y + s.h) * mScaleY) return i;
        }
        return -1;
    }

    // snapX/snapY run several times per ACTION_MOVE event — candidate edges are
    // compared inline rather than packed into throwaway float[] arrays.

    private float snapX(float x, int excludeIdx) {
        float best = x, bestDist = SNAP_THRESHOLD;
        float d = Math.abs(x - mLeft);
        if (d < bestDist) { bestDist = d; best = mLeft; }
        d = Math.abs(x - (CW - mRight));
        if (d < bestDist) { bestDist = d; best = CW - mRight; }
        if (mSlots != null) {
            for (int i = 0; i < mSlots.size(); i++) {
                if (i == excludeIdx) continue;
                LayoutPreset.SlotDef s = mSlots.get(i);
                d = Math.abs(x - s.x);
                if (d < bestDist) { bestDist = d; best = s.x; }
                d = Math.abs(x - (s.x + s.w));
                if (d < bestDist) { bestDist = d; best = s.x + s.w; }
            }
        }
        return best;
    }

    private float snapY(float y, int excludeIdx) {
        float best = y, bestDist = SNAP_THRESHOLD;
        float d = Math.abs(y - mTop);
        if (d < bestDist) { bestDist = d; best = mTop; }
        d = Math.abs(y - (CH - mBottom));
        if (d < bestDist) { bestDist = d; best = CH - mBottom; }
        if (mSlots != null) {
            for (int i = 0; i < mSlots.size(); i++) {
                if (i == excludeIdx) continue;
                LayoutPreset.SlotDef s = mSlots.get(i);
                d = Math.abs(y - s.y);
                if (d < bestDist) { bestDist = d; best = s.y; }
                d = Math.abs(y - (s.y + s.h));
                if (d < bestDist) { bestDist = d; best = s.y + s.h; }
            }
        }
        return best;
    }

    private float snapEdgeX(float a, float b, int excl) {
        float offA = snapX(a, excl) - a, offB = snapX(b, excl) - b;
        return Math.abs(offA) <= Math.abs(offB) ? a + offA : a + offB;
    }

    private float snapEdgeY(float a, float b, int excl) {
        float offA = snapY(a, excl) - a, offB = snapY(b, excl) - b;
        return Math.abs(offA) <= Math.abs(offB) ? a + offA : a + offB;
    }
}
