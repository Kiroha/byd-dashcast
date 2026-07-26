package com.byd.dashcast.ui.main;

/** Aspect-preserving projection and inverse touch mapping for cluster or Layout mirrors. */
public final class MirrorProjection {

    public final int contentWidth;
    public final int contentHeight;
    public final int offsetX;
    public final int offsetY;
    public final float scale;

    private MirrorProjection(int contentWidth, int contentHeight,
                             int offsetX, int offsetY, float scale) {
        this.contentWidth = contentWidth;
        this.contentHeight = contentHeight;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.scale = scale;
    }

    public static MirrorProjection create(int contentWidth, int contentHeight,
                                          int viewWidth, int viewHeight) {
        if (contentWidth <= 0 || contentHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return null;
        }
        float scale = Math.min((float) viewWidth / contentWidth,
                (float) viewHeight / contentHeight);
        if (!Float.isFinite(scale) || scale <= 0f) return null;
        int drawWidth = (int) (contentWidth * scale);
        int drawHeight = (int) (contentHeight * scale);
        return new MirrorProjection(contentWidth, contentHeight,
                (viewWidth - drawWidth) / 2,
                (viewHeight - drawHeight) / 2,
                scale);
    }

    public float mapX(float viewX) {
        return clamp((viewX - offsetX) / scale, contentWidth - 1);
    }

    public float mapY(float viewY) {
        return clamp((viewY - offsetY) / scale, contentHeight - 1);
    }

    private static float clamp(float value, int maximum) {
        return Math.max(0f, Math.min(value, maximum));
    }
}
