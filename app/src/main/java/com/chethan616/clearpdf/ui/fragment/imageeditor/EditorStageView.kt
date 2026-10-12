package com.chethan616.clearpdf.ui.fragment.imageeditor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.chethan616.clearpdf.imageeditor.engine.BrushKind
import com.chethan616.clearpdf.imageeditor.engine.DrawStroke
import com.chethan616.clearpdf.imageeditor.engine.Offset
import com.chethan616.clearpdf.imageeditor.engine.OverlayRenderer
import com.chethan616.clearpdf.imageeditor.engine.ShapeKind
import com.chethan616.clearpdf.imageeditor.engine.draw.ShapePaths
import com.chethan616.clearpdf.imageeditor.engine.draw.StrokeRenderer
import com.chethan616.clearpdf.imageeditor.engine.TextLayer
import com.chethan616.clearpdf.ui.viewmodel.ImageEditorViewModel.BrushSettings
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** What the stage does with touches. */
sealed interface StageInput {
    data object None : StageInput
    data class Brush(val settings: BrushSettings, val onStroke: (List<Offset>) -> Unit) : StageInput
    data class Mask(val width: Float, val restore: Boolean, val onStroke: (List<Offset>) -> Unit) : StageInput
    data class Text(val layer: TextLayer, val onChange: ((TextLayer) -> TextLayer) -> Unit) : StageInput
}

/**
 * The document surface of the image editor: the edited image fitted in the view, with live stroke
 * and text previews drawn in image space so they match the export.
 */
class EditorStageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var input: StageInput = StageInput.None
        set(value) { field = value; invalidate() }

    private val rect = RectF()
    private val live = mutableListOf<Offset>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val checkerLight = Paint().apply { color = 0xFFF2F2F4.toInt() }
    private val checkerDark = Paint().apply { color = 0xFFD9DADF.toInt() }
    private val density = resources.displayMetrics.density

    // Text gesture state
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f

    private fun fit(bmp: Bitmap) {
        val s = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val w = bmp.width * s
        val h = bmp.height * s
        val l = (width - w) / 2f
        val t = (height - h) / 2f
        rect.set(l, t, l + w, t + h)
    }

    private fun toNorm(x: Float, y: Float) = Offset((x - rect.left) / rect.width().coerceAtLeast(1f), (y - rect.top) / rect.height().coerceAtLeast(1f))

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap?.takeUnless { it.isRecycled } ?: return
        fit(bmp)
        if (bmp.hasAlpha()) drawChecker(canvas)
        canvas.drawBitmap(bmp, null, rect, bitmapPaint)
        canvas.save()
        canvas.clipRect(rect)
        when (val i = input) {
            is StageInput.Brush -> if (live.isNotEmpty()) drawLiveStroke(canvas, i.settings)
            is StageInput.Mask -> if (live.isNotEmpty()) drawLiveMask(canvas, i)
            is StageInput.Text -> {
                canvas.translate(rect.left, rect.top)
                OverlayRenderer.drawTextOn(canvas, rect.width().roundToInt(), rect.height().roundToInt(), i.layer)
            }
            StageInput.None -> Unit
        }
        canvas.restore()
    }

    private fun drawChecker(canvas: Canvas) {
        val cell = 12f * density
        canvas.drawRect(rect, checkerLight)
        canvas.save()
        canvas.clipRect(rect)
        var y = rect.top
        var row = 0
        while (y < rect.bottom) {
            var x = rect.left + if (row % 2 == 0) 0f else cell
            while (x < rect.right) {
                canvas.drawRect(x, y, x + cell, y + cell, checkerDark)
                x += cell * 2
            }
            y += cell
            row++
        }
        canvas.restore()
    }

    private fun drawLiveStroke(canvas: Canvas, b: BrushSettings) {
        val short = min(rect.width(), rect.height())
        val px = live.map { PointF(rect.left + it.x * rect.width(), rect.top + it.y * rect.height()) }
        val widthPx = (b.width * short).coerceAtLeast(1f)
        val shape = if (b.brush == BrushKind.Eraser) ShapeKind.Free else b.shape
        val argb = (b.color and 0x00FFFFFF) or ((b.alpha * 255).roundToInt().coerceIn(0, 255) shl 24)
        val stroke = DrawStroke(b.brush, shape, argb, b.width, b.softness, emptyList(), polygonVertices = b.vertices, seed = 7)
        val paint = StrokeRenderer.paintFor(stroke, widthPx, short).apply {
            when (b.brush) {
                // Hints for effects that only exist once rendered on the bitmap.
                BrushKind.Eraser -> { xfermode = null; color = Color.argb(150, 255, 255, 255) }
                BrushKind.Blur, BrushKind.Pixelate -> color = Color.argb(110, 128, 128, 140)
                else -> Unit
            }
        }
        canvas.drawPath(ShapePaths.build(shape, px, widthPx, b.vertices, 7), paint)
    }

    private fun drawLiveMask(canvas: Canvas, m: StageInput.Mask) {
        val short = min(rect.width(), rect.height())
        val px = live.map { PointF(rect.left + it.x * rect.width(), rect.top + it.y * rect.height()) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (m.width * short).coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = if (m.restore) Color.argb(120, 48, 209, 88) else Color.argb(120, 255, 69, 58)
        }
        canvas.drawPath(ShapePaths.smooth(px, false), paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        return when (val i = input) {
            is StageInput.Brush -> {
                val geometric = !i.settings.shape.isFreehand && i.settings.brush != BrushKind.Eraser
                if (i.settings.shape == ShapeKind.FloodFill && i.settings.brush != BrushKind.Eraser) {
                    if (e.actionMasked == MotionEvent.ACTION_UP) i.onStroke(listOf(toNorm(e.x, e.y)))
                    true
                } else stroke(e, geometric) { i.onStroke(it) }
            }
            is StageInput.Mask -> stroke(e, geometric = false) { i.onStroke(it) }
            is StageInput.Text -> textGesture(e, i)
            StageInput.None -> false
        }
    }

    private fun stroke(e: MotionEvent, geometric: Boolean, onDone: (List<Offset>) -> Unit): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                live.clear()
                live.add(toNorm(e.x, e.y))
            }
            MotionEvent.ACTION_MOVE -> {
                val n = toNorm(e.x, e.y)
                if (geometric && live.size > 1) live[1] = n else live.add(n)
            }
            MotionEvent.ACTION_UP -> {
                if (live.size > 1 || !geometric) onDone(live.toList())
                live.clear()
            }
            MotionEvent.ACTION_CANCEL -> live.clear()
        }
        invalidate()
        return true
    }

    /** One finger moves the text; two fingers scale and rotate it. */
    private fun textGesture(e: MotionEvent, t: StageInput.Text): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                lastSpan = span(e)
                lastAngle = angle(e)
                lastX = (e.getX(0) + e.getX(1)) / 2f
                lastY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                val cx = if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.x
                val cy = if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.y
                val dx = cx - lastX
                val dy = cy - lastY
                var zoom = 1f
                var rot = 0f
                if (e.pointerCount >= 2) {
                    val s = span(e)
                    if (lastSpan > 0f) zoom = s / lastSpan
                    lastSpan = s
                    val a = angle(e)
                    rot = a - lastAngle
                    lastAngle = a
                }
                lastX = cx; lastY = cy
                t.onChange { l ->
                    l.copy(
                        center = Offset(
                            (l.center.x + dx / rect.width().coerceAtLeast(1f)).coerceIn(0f, 1f),
                            (l.center.y + dy / rect.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        ),
                        size = (l.size * zoom).coerceIn(0.015f, 0.6f),
                        rotation = l.rotation + rot
                    )
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Continue the pan from the finger that stays down.
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
            }
        }
        return true
    }

    private fun span(e: MotionEvent) = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
    private fun angle(e: MotionEvent) = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
}
