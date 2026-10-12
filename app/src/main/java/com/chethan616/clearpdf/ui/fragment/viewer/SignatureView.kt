package com.chethan616.clearpdf.ui.fragment.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** A signing pad: draws ink strokes and exports them as a tightly cropped transparent bitmap. */
class SignatureView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private data class Ink(val points: MutableList<PointF>, val color: Int, val width: Float)

    private val strokes = mutableListOf<Ink>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    var inkColor = 0xFF141414.toInt()
    var inkWidth = 6.5f * resources.displayMetrics.density / 2.5f

    /** Called whenever the pad gains or loses ink. */
    var onInkChanged: ((Boolean) -> Unit)? = null

    val hasInk get() = strokes.any { it.points.size > 1 }

    fun clear() { strokes.clear(); invalidate(); onInkChanged?.invoke(false) }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        invalidate()
        onInkChanged?.invoke(hasInk)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                strokes.add(Ink(mutableListOf(PointF(event.x, event.y)), inkColor, inkWidth))
            }
            MotionEvent.ACTION_MOVE -> {
                val ink = strokes.lastOrNull() ?: return true
                for (i in 0 until event.historySize) ink.points.add(PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                ink.points.add(PointF(event.x, event.y))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onInkChanged?.invoke(hasInk)
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        strokes.forEach { drawInk(canvas, it, 0f, 0f, 1f) }
    }

    private fun drawInk(canvas: Canvas, ink: Ink, dx: Float, dy: Float, scale: Float) {
        if (ink.points.isEmpty()) return
        paint.color = ink.color
        paint.strokeWidth = ink.width * scale
        path.reset()
        path.moveTo((ink.points[0].x - dx) * scale, (ink.points[0].y - dy) * scale)
        ink.points.drop(1).forEach { path.lineTo((it.x - dx) * scale, (it.y - dy) * scale) }
        if (ink.points.size == 1) path.lineTo((ink.points[0].x - dx) * scale + 0.1f, (ink.points[0].y - dy) * scale)
        canvas.drawPath(path, paint)
    }

    /** The ink cropped to its bounds (plus padding), at most 800 px on its long side. */
    fun export(): Bitmap? {
        val pts = strokes.flatMap { it.points }
        if (pts.isEmpty()) return null
        val pad = strokes.maxOf { it.width } * 2f
        val left = pts.minOf { it.x } - pad
        val top = pts.minOf { it.y } - pad
        val right = pts.maxOf { it.x } + pad
        val bottom = pts.maxOf { it.y } + pad
        val rawW = max(1f, right - left)
        val rawH = max(1f, bottom - top)
        val scale = min(1f, 800f / max(rawW, rawH)).coerceAtLeast(0.1f)
        return runCatching {
            val bmp = Bitmap.createBitmap((rawW * scale).toInt().coerceAtLeast(1), (rawH * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            strokes.forEach { drawInk(c, it, left, top, scale) }
            bmp
        }.getOrNull()
    }
}
