package com.byd.dashcast.fission;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * Tiny read-only rendering of a {@link LayoutPreset}: the cluster surface as a dark
 * rounded rect (1920:720 ratio) with each zone drawn as a colored rounded rect.
 * Used by the layout carousel on the Apps page. Same palette as the editor canvas
 * ({@link ClusterCanvasView}) so a layout is visually recognisable across screens.
 */
public class LayoutMiniPreviewView extends View {

    private static final int CW = 1920;
    private static final int CH = 720;

    private static final int COLOR_BG     = 0xFF060810;
    private static final int COLOR_BORDER = 0xFF20283A;

    // Mirror of ClusterCanvasView.ZONE_COLORS, fully opaque for small-size legibility.
    private static final int[] ZONE_COLORS = {
        0xFF3949AB, 0xFF388E3C, 0xFFF57F17,
        0xFFAD1457, 0xFF0277BD, 0xFF4527A0
    };

    private final Paint mPaintBg     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPaintZone   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect        = new RectF();

    private List<LayoutPreset.SlotDef> mSlots;

    public LayoutMiniPreviewView(Context ctx)                  { this(ctx, null); }
    public LayoutMiniPreviewView(Context ctx, AttributeSet at) {
        super(ctx, at);
        mPaintBg.setColor(COLOR_BG);
        mPaintBg.setStyle(Paint.Style.FILL);
        mPaintBorder.setColor(COLOR_BORDER);
        mPaintBorder.setStyle(Paint.Style.STROKE);
        mPaintBorder.setStrokeWidth(2f);
        mPaintZone.setStyle(Paint.Style.FILL);
    }

    public void setSlots(List<LayoutPreset.SlotDef> slots) {
        mSlots = slots;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * CH / (float) CW));
    }

    @Override
    protected void onDraw(Canvas c) {
        float vw = getWidth(), vh = getHeight();
        float r = vh * 0.12f;
        mRect.set(1f, 1f, vw - 1f, vh - 1f);
        c.drawRoundRect(mRect, r, r, mPaintBg);
        c.drawRoundRect(mRect, r, r, mPaintBorder);

        List<LayoutPreset.SlotDef> slots = mSlots;
        if (slots == null || slots.isEmpty()) return;

        float sx = vw / CW, sy = vh / CH;
        float zr = Math.max(2f, vh * 0.06f);
        for (int i = 0; i < slots.size(); i++) {
            LayoutPreset.SlotDef s = slots.get(i);
            mPaintZone.setColor(ZONE_COLORS[i % ZONE_COLORS.length]);
            mRect.set(s.x * sx + 1.5f, s.y * sy + 1.5f,
                      (s.x + s.w) * sx - 1.5f, (s.y + s.h) * sy - 1.5f);
            c.drawRoundRect(mRect, zr, zr, mPaintZone);
        }
    }
}
