package com.chethan616.clearpdf.ui.fragment.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.viewmodel.FindMatch
import com.chethan616.clearpdf.ui.viewmodel.OcrTextBlock

/**
 * One PDF page: the rendered bitmap scaled to the view's width, its annotations, search matches,
 * the text selection and the selected markup's frame. Touches go to [PageHost], which owns the
 * editing logic shared by every page.
 */
class PdfPageView(context: Context) : View(context) {

    /** Everything a page needs from the viewer. */
    interface PageHost {
        val annotations: AnnotationsViewModel
        fun blocks(page: Int): List<OcrTextBlock>
        fun matches(page: Int): List<FindMatch>
        fun currentMatch(): FindMatch?
        fun draft(page: Int): Markup?
        fun onPageTouch(view: PdfPageView, event: MotionEvent): Boolean
    }

    var host: PageHost? = null
    var pageIndex = -1
        private set
    /** Height / width of the page, from its bitmap once rendered. */
    var aspect = 1.4142f
        private set
    var darkPages = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private var bitmap: Bitmap? = null
    private val renderer = MarkupRenderer()
    private val density = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val matchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val accent = context.color(R.color.accent_blue)
    private val invert = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    init {
        matchPaint.color = 0x66FFEB3B
        selectionPaint.color = (accent and 0x00FFFFFF) or 0x55000000
        handlePaint.color = accent
        placeholderPaint.color = context.color(R.color.app_text_tertiary)
        placeholderPaint.textAlign = Paint.Align.CENTER
        placeholderPaint.textSize = 14f * density
    }

    fun bind(page: Int, bitmap: Bitmap?, fallbackAspect: Float) {
        pageIndex = page
        this.bitmap = bitmap?.takeUnless { it.isRecycled }
        val newAspect = this.bitmap?.let { it.height.toFloat() / it.width.coerceAtLeast(1) } ?: fallbackAspect
        if (newAspect != aspect) {
            aspect = newAspect
            requestLayout()
        }
        contentDescription = context.getString(R.string.page_number, page + 1)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * aspect).toInt())
    }

    /** Converts view coordinates to normalised page coordinates. */
    fun toPage(x: Float, y: Float) = NPoint(x / width.coerceAtLeast(1), y / height.coerceAtLeast(1))

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = host?.onPageTouch(this, event) ?: super.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        pagePaint.color = if (darkPages) Color.BLACK else Color.WHITE
        canvas.drawRect(0f, 0f, w, h, pagePaint)

        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            dst.set(0f, 0f, w, h)
            bitmapPaint.colorFilter = if (darkPages) invert else null
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        } else {
            canvas.drawText("${pageIndex + 1}", w / 2f, h / 2f, placeholderPaint)
        }

        val host = host ?: return
        val blocks = host.blocks(pageIndex)

        // Search matches, the current one stronger.
        val current = host.currentMatch()
        host.matches(pageIndex).forEach { m ->
            matchPaint.color = if (m == current) 0x99FF9800.toInt() else 0x66FFEB3B
            canvas.drawRect(m.left * w, m.top * h, m.right * w, m.bottom * h, matchPaint)
        }

        val store = host.annotations
        renderer.draw(canvas, store.markups(pageIndex), w, h, blocks)
        host.draft(pageIndex)?.let { renderer.draw(canvas, it, w, h, blocks) }

        // Text selection with its two drag handles.
        store.textSelection?.takeIf { it.page == pageIndex }?.let { sel ->
            val ordered = blocks.readingOrder()
            val ranges = sel.ranges(ordered)
            ranges.forEach { (b, s, e) ->
                val r = textRangeRect(b, s, e)
                canvas.drawRect(r.left * w, r.top * h, r.right * w, r.bottom * h, selectionPaint)
            }
            handlePoints(sel, ordered)?.let { (a, b) ->
                drawHandle(canvas, a.x * w, a.y * h, start = true)
                drawHandle(canvas, b.x * w, b.y * h, start = false)
            }
        }

        // Frame around the markup being moved / resized.
        store.selection?.takeIf { it.page == pageIndex }?.let { ref ->
            val m = store.get(ref) ?: return@let
            val bounds = m.bounds(w, h)
                ?: (m as? Markup.TextHighlight)?.let { textRect(blocks, it.blockId, it.start, it.end) }
                ?: (m as? Markup.TextLine)?.let { textRect(blocks, it.blockId, it.start, it.end) }
                ?: return@let
            renderer.drawSelection(canvas, bounds, w, h, accent, density, m.isResizable())
        }
    }

    /** Bottom-left of the selection start and bottom-right of its end, normalised. */
    fun handlePoints(sel: TextSelection, ordered: List<OcrTextBlock>): Pair<NPoint, NPoint>? {
        val ranges = sel.ranges(ordered)
        if (ranges.isEmpty()) return null
        val first = ranges.first().let { (b, s, e) -> textRangeRect(b, s, e) }
        val last = ranges.last().let { (b, s, e) -> textRangeRect(b, s, e) }
        return NPoint(first.left, first.bottom) to NPoint(last.right, last.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, start: Boolean) {
        val r = 7f * density
        canvas.drawRect(x - density, y - 18f * density, x + density, y, handlePaint)
        canvas.drawCircle(x + if (start) -r * 0.4f else r * 0.4f, y + r, r, handlePaint)
    }
}
