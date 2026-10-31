package com.byd.dashcast.ui.diag

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/** Schematic 1920×720 cluster. It illustrates what to look for; it never mirrors the real cluster pixels. */
internal class ClusterPreviewView(context: Context) : View(context) {
    enum class Mode { STOCK, READY, FULLSCREEN, ATTENTION, UNKNOWN }

    var mode = Mode.UNKNOWN
        set(value) { if (field != value) { field = value; invalidate() } }
    var maxHeightDp = 170

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val frame = RectF()
    private val box = RectF()
    private val path = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO } // The caption next to it carries the meaning.

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cap = (maxHeightDp * resources.displayMetrics.density).toInt()
        setMeasuredDimension(width, minOf(width * 3 / 8, cap).coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = minOf(width.toFloat(), h * 8f / 3f)
        val left = (width - w) / 2f
        frame.set(left, 0f, left + w, h)
        fill.shader = null; fill.color = Color.rgb(10, 12, 16)
        canvas.drawRoundRect(frame, h * 0.16f, h * 0.16f, fill)
        frame.inset(h * 0.045f, h * 0.045f)
        canvas.save()
        path.reset(); path.addRoundRect(frame, h * 0.12f, h * 0.12f, Path.Direction.CW)
        canvas.clipPath(path)
        when (mode) {
            Mode.FULLSCREEN -> { drawMap(canvas); drawOverlays(canvas, map = true) }
            Mode.ATTENTION -> drawAttention(canvas)
            Mode.UNKNOWN -> { drawStock(canvas); veil(canvas) }
            Mode.STOCK, Mode.READY -> {
                drawStock(canvas); drawOverlays(canvas, map = false)
                if (mode == Mode.READY) drawReadyBadge(canvas)
            }
        }
        canvas.restore()
    }

    private fun drawStock(canvas: Canvas) {
        val f = frame
        val h = f.height()
        fill.shader = LinearGradient(0f, f.top, 0f, f.bottom, Color.rgb(16, 30, 52), Color.rgb(6, 10, 18), Shader.TileMode.CLAMP)
        canvas.drawRect(f, fill)
        fill.shader = null
        val cy = f.centerY() + h * 0.04f
        val r = h * 0.34f
        stroke.strokeWidth = h * 0.035f
        listOf(f.left + f.width() * 0.2f to Color.rgb(79, 195, 247), f.right - f.width() * 0.2f to Color.rgb(102, 187, 106)).forEach { (cx, accent) ->
            box.set(cx - r, cy - r, cx + r, cy + r)
            stroke.color = Color.rgb(48, 60, 78); canvas.drawArc(box, 135f, 270f, false, stroke)
            stroke.color = accent; canvas.drawArc(box, 135f, 110f, false, stroke)
        }
        stroke.color = Color.rgb(70, 84, 104); stroke.strokeWidth = h * 0.02f
        val cx = f.centerX()
        canvas.drawLine(cx - h * 0.28f, f.bottom - h * 0.12f, cx - h * 0.06f, f.top + h * 0.38f, stroke)
        canvas.drawLine(cx + h * 0.28f, f.bottom - h * 0.12f, cx + h * 0.06f, f.top + h * 0.38f, stroke)
    }

    private fun drawMap(canvas: Canvas) {
        val f = frame
        val h = f.height()
        val w = f.width()
        fill.color = Color.rgb(233, 236, 228); canvas.drawRect(f, fill)
        fill.color = Color.rgb(200, 230, 201)
        box.set(f.left + w * 0.05f, f.top + h * 0.45f, f.left + w * 0.3f, f.bottom - h * 0.05f)
        canvas.drawRoundRect(box, h * 0.08f, h * 0.08f, fill)
        fill.color = Color.rgb(170, 205, 250)
        box.set(f.right - w * 0.28f, f.top + h * 0.05f, f.right - w * 0.02f, f.top + h * 0.4f)
        canvas.drawRoundRect(box, h * 0.1f, h * 0.1f, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = h * 0.07f
        canvas.drawLine(f.left, f.top + h * 0.62f, f.right, f.top + h * 0.48f, stroke)
        canvas.drawLine(f.left + w * 0.62f, f.top, f.left + w * 0.5f, f.bottom, stroke)
        stroke.color = Color.rgb(255, 213, 79); stroke.strokeWidth = h * 0.045f
        canvas.drawLine(f.left + w * 0.15f, f.top, f.left + w * 0.38f, f.bottom, stroke)
        stroke.color = Color.rgb(26, 115, 232); stroke.strokeWidth = h * 0.06f
        path.reset()
        path.moveTo(f.centerX(), f.bottom - h * 0.1f)
        path.lineTo(f.left + w * 0.53f, f.top + h * 0.52f)
        path.lineTo(f.right - w * 0.1f, f.top + h * 0.43f)
        canvas.drawPath(path, stroke)
        val ax = f.centerX()
        val ay = f.bottom - h * 0.14f
        val s = h * 0.09f
        path.reset()
        path.moveTo(ax, ay - s); path.lineTo(ax + s * 0.75f, ay + s * 0.7f)
        path.lineTo(ax, ay + s * 0.3f); path.lineTo(ax - s * 0.75f, ay + s * 0.7f); path.close()
        fill.color = Color.rgb(26, 115, 232); canvas.drawPath(path, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = h * 0.015f; canvas.drawPath(path, stroke)
    }

    /** Telltales, speed and gear: the BYD elements the full-screen theme keeps above the map. */
    private fun drawOverlays(canvas: Canvas, map: Boolean) {
        val f = frame
        val h = f.height()
        val gap = h * 0.11f
        val startX = f.centerX() - gap * 2
        if (map) {
            fill.color = Color.argb(160, 0, 0, 0)
            box.set(startX - gap * 0.6f, f.top + h * 0.03f, startX + gap * 4.6f, f.top + h * 0.15f)
            canvas.drawRoundRect(box, h * 0.06f, h * 0.06f, fill)
        }
        intArrayOf(Color.rgb(67, 160, 71), Color.rgb(255, 179, 0), Color.rgb(229, 57, 53), Color.rgb(30, 136, 229), Color.rgb(67, 160, 71))
            .forEachIndexed { i, c -> fill.color = c; canvas.drawCircle(startX + gap * i, f.top + h * 0.09f, h * 0.032f, fill) }
        if (map) {
            pill(canvas, f.left + h * 0.08f, f.bottom - h * 0.32f, h * 0.5f, h * 0.24f, "50")
            pill(canvas, f.right - h * 0.32f, f.bottom - h * 0.32f, h * 0.24f, h * 0.24f, "D")
        } else {
            label.color = Color.WHITE; label.textSize = h * 0.2f
            canvas.drawText("50", f.left + f.width() * 0.2f, f.centerY() + h * 0.1f, label)
            canvas.drawText("D", f.right - f.width() * 0.2f, f.centerY() + h * 0.1f, label)
        }
    }

    private fun pill(canvas: Canvas, x: Float, y: Float, w: Float, ph: Float, text: String) {
        fill.color = Color.argb(210, 12, 16, 24)
        box.set(x, y, x + w, y + ph)
        canvas.drawRoundRect(box, ph / 2, ph / 2, fill)
        label.color = Color.WHITE; label.textSize = ph * 0.62f
        canvas.drawText(text, box.centerX(), box.centerY() - (label.descent() + label.ascent()) / 2, label)
    }

    private fun drawReadyBadge(canvas: Canvas) {
        val f = frame
        val r = f.height() * 0.09f
        val cx = f.right - r * 1.6f
        val cy = f.top + r * 1.6f
        fill.color = Color.rgb(46, 125, 50); canvas.drawCircle(cx, cy, r, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = r * 0.28f
        path.reset()
        path.moveTo(cx - r * 0.45f, cy); path.lineTo(cx - r * 0.1f, cy + r * 0.38f); path.lineTo(cx + r * 0.5f, cy - r * 0.35f)
        canvas.drawPath(path, stroke)
    }

    private fun drawAttention(canvas: Canvas) {
        val f = frame
        fill.color = Color.rgb(20, 20, 24); canvas.drawRect(f, fill)
        val cx = f.centerX()
        val cy = f.centerY()
        val s = f.height() * 0.32f
        path.reset()
        path.moveTo(cx, cy - s); path.lineTo(cx + s * 1.1f, cy + s * 0.8f); path.lineTo(cx - s * 1.1f, cy + s * 0.8f); path.close()
        fill.color = Color.rgb(255, 160, 0); canvas.drawPath(path, fill)
        label.color = Color.rgb(20, 20, 24); label.textSize = s * 0.95f
        canvas.drawText("!", cx, cy + s * 0.62f, label)
    }

    private fun veil(canvas: Canvas) {
        fill.color = Color.argb(170, 30, 34, 40); canvas.drawRect(frame, fill)
        label.color = Color.WHITE; label.textSize = frame.height() * 0.42f
        canvas.drawText("?", frame.centerX(), frame.centerY() - (label.descent() + label.ascent()) / 2, label)
    }
}
