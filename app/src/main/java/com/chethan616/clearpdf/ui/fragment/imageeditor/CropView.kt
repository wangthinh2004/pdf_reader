package com.chethan616.clearpdf.ui.fragment.imageeditor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.chethan616.clearpdf.imageeditor.engine.CropMask
import com.chethan616.clearpdf.imageeditor.engine.Offset
import com.chethan616.clearpdf.imageeditor.engine.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Crop surface: a resizable crop frame (optionally locked to an aspect ratio and previewing a
 * shape mask) or, in perspective mode, four free corners. Results are normalised to the image.
 */
class CropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    /** Width / height to keep, or null for free. */
    private var ratio: Float? = null
    private var mask: CropMask? = null
    var perspective = false
        private set

    /** Crop frame, normalised to the image. */
    private var crop = RectF(0f, 0f, 1f, 1f)
    /** Perspective corners TL, TR, BR, BL, normalised. */
    private val corners = arrayOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))

    var accent: Int = 0xFF0A84FF.toInt()

    private val img = RectF()
    private val density = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0x73000000 }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99FFFFFF.toInt() }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var drag = -1   // 0..3 corners, 4..7 edges (L, T, R, B), 8 move
    private var lastX = 0f
    private var lastY = 0f

    fun setImage(bmp: Bitmap) {
        val changed = bitmap?.let { it.width != bmp.width || it.height != bmp.height } ?: true
        bitmap = bmp
        if (changed) reset()
        invalidate()
    }

    fun setMode(ratio: Float?, mask: CropMask?, perspective: Boolean) {
        val ratioChanged = ratio != this.ratio
        this.ratio = ratio
        this.mask = mask
        this.perspective = perspective
        if (ratioChanged) applyRatio()
        invalidate()
    }

    private fun reset() {
        crop.set(0f, 0f, 1f, 1f)
        corners[0] = Offset(0f, 0f); corners[1] = Offset(1f, 0f); corners[2] = Offset(1f, 1f); corners[3] = Offset(0f, 1f)
        applyRatio()
    }

    /** Fits the largest frame of [ratio] centred in the image. */
    private fun applyRatio() {
        val r = ratio ?: return
        val bmp = bitmap ?: return
        val imgRatio = bmp.width / bmp.height.toFloat()
        // Normalised width / height of the frame.
        var w = 1f
        var h = imgRatio / r
        if (h > 1f) { h = 1f; w = r / imgRatio }
        crop.set((1f - w) / 2f, (1f - h) / 2f, (1f + w) / 2f, (1f + h) / 2f)
    }

    fun cropRect(): Rect = Rect(crop.left.coerceIn(0f, 1f), crop.top.coerceIn(0f, 1f), crop.right.coerceIn(0f, 1f), crop.bottom.coerceIn(0f, 1f))

    fun perspectiveCorners(): List<Offset> = corners.toList()

    private fun fit(bmp: Bitmap) {
        val pad = 16f * density
        val s = min((width - 2 * pad) / bmp.width, (height - 2 * pad) / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        img.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun sx(n: Float) = img.left + n * img.width()
    private fun sy(n: Float) = img.top + n * img.height()

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap?.takeUnless { it.isRecycled } ?: return
        fit(bmp)
        canvas.drawBitmap(bmp, null, img, bitmapPaint)
        frame.strokeWidth = 2f * density
        grid.strokeWidth = 1f * density
        handle.color = accent
        if (perspective) drawPerspective(canvas) else drawFrame(canvas)
    }

    private fun drawFrame(canvas: Canvas) {
        val l = sx(crop.left); val t = sy(crop.top); val r = sx(crop.right); val b = sy(crop.bottom)
        // Dim outside the frame (or outside the mask shape).
        path.reset()
        path.addRect(img, Path.Direction.CW)
        val inner = mask?.path(r - l, b - t)?.apply { offset(l, t) } ?: Path().apply { addRect(l, t, r, b, Path.Direction.CW) }
        path.op(inner, Path.Op.DIFFERENCE)
        canvas.drawPath(path, dim)
        if (mask != null) canvas.drawPath(inner, frame)
        canvas.drawRect(l, t, r, b, frame)
        for (i in 1..2) {
            val x = l + (r - l) * i / 3f
            val y = t + (b - t) * i / 3f
            canvas.drawLine(x, t, x, b, grid)
            canvas.drawLine(l, y, r, y, grid)
        }
        val hr = 7f * density
        for ((x, y) in listOf(l to t, r to t, r to b, l to b)) canvas.drawCircle(x, y, hr, handle)
        if (ratio == null) {
            val len = 10f * density
            handle.strokeWidth = 4f * density
            canvas.drawRect((l + r) / 2f - len, t - 2 * density, (l + r) / 2f + len, t + 2 * density, handle)
            canvas.drawRect((l + r) / 2f - len, b - 2 * density, (l + r) / 2f + len, b + 2 * density, handle)
            canvas.drawRect(l - 2 * density, (t + b) / 2f - len, l + 2 * density, (t + b) / 2f + len, handle)
            canvas.drawRect(r - 2 * density, (t + b) / 2f - len, r + 2 * density, (t + b) / 2f + len, handle)
        }
    }

    private fun drawPerspective(canvas: Canvas) {
        path.reset()
        corners.forEachIndexed { i, c -> if (i == 0) path.moveTo(sx(c.x), sy(c.y)) else path.lineTo(sx(c.x), sy(c.y)) }
        path.close()
        val outside = Path().apply { addRect(img, Path.Direction.CW); op(path, Path.Op.DIFFERENCE) }
        canvas.drawPath(outside, dim)
        canvas.drawPath(path, frame)
        corners.forEach { c -> canvas.drawCircle(sx(c.x), sy(c.y), 9f * density, handle) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drag = pick(e.x, e.y)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag < 0) return true
                val dx = (e.x - lastX) / img.width().coerceAtLeast(1f)
                val dy = (e.y - lastY) / img.height().coerceAtLeast(1f)
                lastX = e.x; lastY = e.y
                if (perspective) {
                    val c = corners[drag]
                    corners[drag] = Offset((c.x + dx).coerceIn(0f, 1f), (c.y + dy).coerceIn(0f, 1f))
                } else moveFrame(dx, dy)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
        return true
    }

    private fun pick(x: Float, y: Float): Int {
        val grab = 28f * density
        if (perspective) {
            return corners.indices.minByOrNull { hypot(sx(corners[it].x) - x, sy(corners[it].y) - y) }
                ?.takeIf { hypot(sx(corners[it].x) - x, sy(corners[it].y) - y) < grab * 1.5f } ?: -1
        }
        val l = sx(crop.left); val t = sy(crop.top); val r = sx(crop.right); val b = sy(crop.bottom)
        val pts = listOf(l to t, r to t, r to b, l to b)
        pts.forEachIndexed { i, (px, py) -> if (hypot(px - x, py - y) < grab) return i }
        if (ratio == null) {
            if (abs(x - l) < grab && y in t..b) return 4
            if (abs(y - t) < grab && x in l..r) return 5
            if (abs(x - r) < grab && y in t..b) return 6
            if (abs(y - b) < grab && x in l..r) return 7
        }
        if (x in l..r && y in t..b) return 8
        return -1
    }

    private fun moveFrame(dx: Float, dy: Float) {
        val min = 0.05f
        val c = RectF(crop)
        when (drag) {
            8 -> {
                val mx = dx.coerceIn(-c.left, 1f - c.right)
                val my = dy.coerceIn(-c.top, 1f - c.bottom)
                c.offset(mx, my)
            }
            4 -> c.left = (c.left + dx).coerceIn(0f, c.right - min)
            5 -> c.top = (c.top + dy).coerceIn(0f, c.bottom - min)
            6 -> c.right = (c.right + dx).coerceIn(c.left + min, 1f)
            7 -> c.bottom = (c.bottom + dy).coerceIn(c.top + min, 1f)
            in 0..3 -> {
                val left = drag == 0 || drag == 3
                val top = drag == 0 || drag == 1
                if (left) c.left = (c.left + dx).coerceIn(0f, c.right - min) else c.right = (c.right + dx).coerceIn(c.left + min, 1f)
                if (top) c.top = (c.top + dy).coerceIn(0f, c.bottom - min) else c.bottom = (c.bottom + dy).coerceIn(c.top + min, 1f)
                ratio?.let { r -> lockRatio(c, r, left, top) }
            }
        }
        crop = c
    }

    /** Keeps the frame at ratio [r] after a corner drag, pivoting on the opposite corner. */
    private fun lockRatio(c: RectF, r: Float, left: Boolean, top: Boolean) {
        val bmp = bitmap ?: return
        val k = bmp.width / bmp.height.toFloat()
        // In normalised units: (w * k) / h = r  →  h = w * k / r
        var w = c.width()
        var h = w * k / r
        val maxH = if (top) c.bottom else 1f - c.top
        if (h > maxH) { h = maxH; w = h * r / k }
        val maxW = if (left) c.right else 1f - c.left
        if (w > maxW) { w = maxW; h = w * k / r }
        if (left) c.left = c.right - w else c.right = c.left + w
        if (top) c.top = c.bottom - h else c.bottom = c.top + h
    }
}
