package com.byd.dashcast.ui.main

/** Aspect-preserving projection and inverse touch mapping for cluster or Layout mirrors. */
class MirrorProjection private constructor(
    @JvmField val contentWidth: Int,
    @JvmField val contentHeight: Int,
    @JvmField val offsetX: Int,
    @JvmField val offsetY: Int,
    @JvmField val scale: Float,
) {

    fun mapX(viewX: Float): Float = clamp((viewX - offsetX) / scale, contentWidth - 1)

    fun mapY(viewY: Float): Float = clamp((viewY - offsetY) / scale, contentHeight - 1)

    companion object {
        @JvmStatic
        fun create(contentWidth: Int, contentHeight: Int,
                   viewWidth: Int, viewHeight: Int): MirrorProjection? {
            if (contentWidth <= 0 || contentHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return null
            }
            val scale = Math.min(viewWidth.toFloat() / contentWidth,
                viewHeight.toFloat() / contentHeight)
            if (!scale.isFinite() || scale <= 0f) return null
            val drawWidth = (contentWidth * scale).toInt()
            val drawHeight = (contentHeight * scale).toInt()
            return MirrorProjection(contentWidth, contentHeight,
                (viewWidth - drawWidth) / 2,
                (viewHeight - drawHeight) / 2,
                scale)
        }

        private fun clamp(value: Float, maximum: Int): Float =
            Math.max(0f, Math.min(value, maximum.toFloat()))
    }
}
