package com.chethan616.clearpdf.ui.fragment.viewer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.chethan616.clearpdf.ui.viewmodel.OcrTextBlock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Draws [Markup]s onto a page canvas of size [w] × [h] pixels. */
class MarkupRenderer {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    fun draw(canvas: Canvas, markups: List<Markup>, w: Float, h: Float, blocks: List<OcrTextBlock>) {
        markups.forEach { draw(canvas, it, w, h, blocks) }
    }

    fun draw(canvas: Canvas, m: Markup, w: Float, h: Float, blocks: List<OcrTextBlock>) {
        when (m) {
            is Markup.Stroke -> {
                if (m.points.isEmpty()) return
                stroke.color = m.color
                stroke.alpha = (m.alpha * 255).toInt()
                stroke.strokeWidth = (m.width * w).coerceAtLeast(1f)
                smoothPath(m.points, w, h)
                canvas.drawPath(path, stroke)
            }
            is Markup.Rect -> {
                rect.set(minOf(m.start.x, m.end.x) * w, minOf(m.start.y, m.end.y) * h, maxOf(m.start.x, m.end.x) * w, maxOf(m.start.y, m.end.y) * h)
                val p = shapePaint(m.color, m.alpha, m.filled, w)
                canvas.drawRect(rect, p)
            }
            is Markup.Oval -> {
                rect.set(minOf(m.start.x, m.end.x) * w, minOf(m.start.y, m.end.y) * h, maxOf(m.start.x, m.end.x) * w, maxOf(m.start.y, m.end.y) * h)
                canvas.drawOval(rect, shapePaint(m.color, m.alpha, m.filled, w))
            }
            is Markup.Line -> {
                stroke.color = m.color
                stroke.alpha = (m.alpha * 255).toInt()
                stroke.strokeWidth = (m.width * w).coerceAtLeast(1f)
                val sx = m.start.x * w; val sy = m.start.y * h; val ex = m.end.x * w; val ey = m.end.y * h
                canvas.drawLine(sx, sy, ex, ey, stroke)
                if (m.arrowHead) drawArrowHead(canvas, sx, sy, ex, ey, stroke.strokeWidth, m.color, m.alpha)
            }
            is Markup.TextHighlight -> {
                val r = textRect(blocks, m.blockId, m.start, m.end) ?: return
                fill.color = m.color
                fill.alpha = (m.alpha * 255).toInt()
                val pad = r.height() * 0.03f
                rect.set(r.left * w, (r.top - pad) * h, r.right * w, (r.bottom + pad) * h)
                canvas.drawRect(rect, fill)
            }
            is Markup.TextLine -> {
                val r = textRect(blocks, m.blockId, m.start, m.end) ?: return
                val y = m.lineY(r) * h
                stroke.color = m.color
                stroke.alpha = 255
                stroke.strokeWidth = (m.width * w).coerceAtLeast(1.5f)
                canvas.drawLine(r.left * w, y, r.right * w, y, stroke)
            }
            is Markup.Image -> {
                if (m.bitmap.isRecycled) return
                rect.set(minOf(m.start.x, m.end.x) * w, minOf(m.start.y, m.end.y) * h, maxOf(m.start.x, m.end.x) * w, maxOf(m.start.y, m.end.y) * h)
                canvas.drawBitmap(m.bitmap, null, rect, fill.apply { alpha = 255; isFilterBitmap = true })
            }
            is Markup.TextBox -> {
                text.color = m.color
                text.textSize = m.fontSize * h
                val lineH = text.textSize * 1.2f
                var y = m.position.y * h - text.ascent()
                m.text.split("\n").forEach { line ->
                    canvas.drawText(line, m.position.x * w, y, text)
                    y += lineH
                }
            }
            is Markup.Note -> {
                val size = NOTE_SIZE * w
                val x = m.anchor.x * w; val y = m.anchor.y * h
                rect.set(x, y, x + size, y + size)
                fill.color = m.color
                fill.alpha = 235
                canvas.drawRoundRect(rect, size * 0.18f, size * 0.18f, fill)
                stroke.color = Color.WHITE
                stroke.alpha = 230
                stroke.strokeWidth = size * 0.07f
                val inset = size * 0.25f
                for (i in 0..2) {
                    val ly = y + inset + i * (size - 2 * inset) / 2f
                    canvas.drawLine(x + inset, ly, x + size - inset, ly, stroke)
                }
            }
        }
    }

    /** The outline of a selected markup, with a resize handle at its bottom-right when [resizable]. */
    fun drawSelection(canvas: Canvas, bounds: RectF, w: Float, h: Float, accent: Int, density: Float, resizable: Boolean) {
        val pad = 6f * density
        rect.set(bounds.left * w - pad, bounds.top * h - pad, bounds.right * w + pad, bounds.bottom * h + pad)
        stroke.color = accent
        stroke.alpha = 255
        stroke.strokeWidth = 1.5f * density
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, stroke)
        stroke.pathEffect = null
        if (resizable) {
            fill.color = accent
            fill.alpha = 255
            canvas.drawCircle(rect.right, rect.bottom, 9f * density, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(rect.right, rect.bottom, 4f * density, fill)
        }
    }

    private fun shapePaint(color: Int, alpha: Float, filled: Boolean, w: Float): Paint =
        if (filled) fill.apply { this.color = color; this.alpha = (alpha * 255).toInt() }
        else stroke.apply {
            this.color = color
            this.alpha = (alpha * 255).toInt()
            strokeWidth = (w * 0.004f).coerceAtLeast(2f)
        }

    private fun smoothPath(points: List<NPoint>, w: Float, h: Float) {
        path.reset()
        path.moveTo(points[0].x * w, points[0].y * h)
        if (points.size == 1) {
            path.lineTo(points[0].x * w + 0.1f, points[0].y * h)
            return
        }
        for (i in 1 until points.size - 1) {
            val p0 = points[i]; val p1 = points[i + 1]
            path.quadTo(p0.x * w, p0.y * h, (p0.x + p1.x) / 2f * w, (p0.y + p1.y) / 2f * h)
        }
        path.lineTo(points.last().x * w, points.last().y * h)
    }

    private fun drawArrowHead(canvas: Canvas, sx: Float, sy: Float, ex: Float, ey: Float, width: Float, color: Int, alpha: Float) {
        val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
        val len = (width * 3.5f).coerceAtLeast(18f)
        val a1 = angle + PI - PI / 6
        val a2 = angle + PI + PI / 6
        path.reset()
        path.moveTo(ex, ey)
        path.lineTo((ex + len * cos(a1)).toFloat(), (ey + len * sin(a1)).toFloat())
        path.lineTo((ex + len * cos(a2)).toFloat(), (ey + len * sin(a2)).toFloat())
        path.close()
        fill.color = color
        fill.alpha = (alpha * 255).toInt()
        canvas.drawPath(path, fill)
    }
}
