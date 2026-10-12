package com.chethan616.clearpdf.ui.fragment.sheet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.chethan616.clearpdf.utils.xlsx.CellRange
import com.chethan616.clearpdf.utils.xlsx.SheetLayout
import com.chethan616.clearpdf.utils.xlsx.ValidationKind
import com.chethan616.clearpdf.utils.xlsx.XlsxPainter
import com.chethan616.clearpdf.utils.xlsx.XlsxRefs
import com.chethan616.clearpdf.utils.xlsx.XlsxSheet
import kotlin.math.abs
import kotlin.math.hypot

/** The selected range plus the cell typing goes into. */
data class GridSelection(val anchorR: Int, val anchorC: Int, val range: CellRange) {
    val isWholeColumn get() = range.r1 == 0 && range.r2 == XlsxRefs.MaxRow
    val isWholeRow get() = range.c1 == 0 && range.c2 == XlsxRefs.MaxCol

    companion object {
        fun cell(r: Int, c: Int, sheet: XlsxSheet): GridSelection =
            GridSelection(r, c, sheet.mergeAt(r, c) ?: CellRange(r, c, r, c))
    }
}

/** Colours of the grid chrome. */
class GridColors(val surface: Int, val headerBand: Int, val headerText: Int, val headerDivider: Int, val accent: Int)

/**
 * The spreadsheet grid. Every visible row is one [XlsxPainter.drawRow] call; the column-letter
 * header, the row-number gutter, frozen rows and frozen columns stay put while the rest scrolls in
 * both directions. Pinch zooms, a tap selects, the corner handles grow the selection and, in edit
 * mode, dragging a header divider resizes a column.
 */
class SheetGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onSelect(selection: GridSelection)
        fun onTapSelected(r: Int, c: Int)
        fun onDropdown(r: Int, c: Int)
        fun onColumnResize(col: Int, widthChars: Float)
    }

    var listener: Listener? = null
    private var sheet: XlsxSheet? = null
    private var layout: SheetLayout? = null
    private var painter: XlsxPainter? = null
    private var colors: GridColors? = null

    var selection: GridSelection? = null
        set(value) { field = value; invalidate() }
    var editMode = false
        set(value) { field = value; invalidate() }
    private var matches: List<Pair<Int, Int>> = emptyList()
    private var currentMatch: Pair<Int, Int>? = null

    private val density = resources.displayMetrics.density
    /** Zoom on top of the density: 1 = 100%. */
    var zoom = 1f
        private set
    private val scale get() = density * zoom
    private var scrollXpx = 0f
    private var scrollYpx = 0f

    private val headerH = 28f * density
    private var gutterW = 40f * density
    private val handleR = 7f * density
    private val chevronW = 22f * density

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
    }
    private val headerTextDy = headerPaint.fontMetrics.let { -(it.ascent + it.descent) / 2f }
    private val band = Paint()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val scroller = OverScroller(context)
    private var resizeCol = -1
    private var resizeWidth = 0f
    private var dragHandle: Pair<Int, Int>? = null

    fun bind(sheet: XlsxSheet, layout: SheetLayout, painter: XlsxPainter, colors: GridColors) {
        val sheetChanged = this.sheet !== sheet
        this.sheet = sheet
        this.layout = layout
        this.painter = painter
        this.colors = colors
        gutterW = ((layout.nRows.toString().length * 7 + 22) * density)
        if (sheetChanged) { scrollXpx = 0f; scrollYpx = 0f }
        clampScroll()
        invalidate()
    }

    fun setMatches(matches: List<Pair<Int, Int>>, current: Pair<Int, Int>?) {
        this.matches = matches
        currentMatch = current
        invalidate()
    }

    // ── Geometry ──────────────────────────────────────────────────────────────────────────────

    private fun fc() = (sheet?.frozenCols ?: 0).coerceAtMost(layout?.nCols ?: 0)
    private fun fr() = (sheet?.frozenRows ?: 0).coerceAtMost(layout?.nRows ?: 0)
    private fun frozenW() = (layout?.frozenWidth ?: 0f) * scale
    private fun frozenH() = (layout?.frozenHeight ?: 0f) * scale

    private fun maxScrollX(): Float {
        val l = layout ?: return 0f
        return ((l.totalWidth - l.frozenWidth) * scale - (width - gutterW - frozenW())).coerceAtLeast(0f)
    }

    private fun maxScrollY(): Float {
        val l = layout ?: return 0f
        return ((l.rowY[l.nRows] - l.frozenHeight) * scale - (height - headerH - frozenH())).coerceAtLeast(0f)
    }

    private fun clampScroll() {
        scrollXpx = scrollXpx.coerceIn(0f, maxScrollX())
        scrollYpx = scrollYpx.coerceIn(0f, maxScrollY())
    }

    /** Screen x of column [c]'s left edge (within the body, i.e. after the gutter). */
    private fun colLeft(c: Int): Float {
        val l = layout ?: return 0f
        val cc = c.coerceIn(0, l.nCols)
        val fc = fc()
        return if (cc < fc) l.colX[cc] * scale else frozenW() + (l.colX[cc] - l.colX[fc]) * scale - scrollXpx
    }

    /** Screen y of row [r]'s top edge (within the body, i.e. below the header). */
    private fun rowTop(r: Int): Float {
        val l = layout ?: return 0f
        val rr = r.coerceIn(0, l.nRows)
        return if (rr < fr()) l.rowY[rr] * scale else frozenH() + (l.rowY[rr] - l.frozenHeight) * scale - scrollYpx
    }

    /** Body point → (row, col); col −1 = row gutter. */
    private fun hit(x: Float, y: Float): Pair<Int, Int>? {
        val l = layout ?: return null
        val by = y - headerH
        if (by < 0f) return null
        val r = if (by < frozenH()) l.rowAt(by / scale) else l.rowAt(l.frozenHeight + (by - frozenH() + scrollYpx) / scale)
        val cx = x - gutterW
        if (cx < 0f) return r to -1
        val c = if (cx < frozenW()) l.colAt(cx / scale) else l.colAt(l.colX[fc()] + (cx - frozenW() + scrollXpx) / scale)
        return r to c
    }

    private fun selectionRect(s: GridSelection, out: RectF): Boolean {
        val l = layout ?: return false
        val r2 = s.range.r2.coerceAtMost(l.nRows - 1)
        val c2 = s.range.c2.coerceAtMost(l.nCols - 1)
        out.set(gutterW + colLeft(s.range.c1), headerH + rowTop(s.range.r1), gutterW + colLeft(c2 + 1), headerH + rowTop(r2 + 1))
        return true
    }

    private fun chevronRect(s: GridSelection, out: RectF): Boolean {
        val sh = sheet ?: return false
        if (!editMode) return false
        if (!s.range.isSingle && sh.mergeAt(s.anchorR, s.anchorC) != s.range) return false
        val v = sh.validationAt(s.anchorR, s.anchorC) ?: return false
        if (v.kind != ValidationKind.LIST || v.showDropDown) return false
        val r = RectF()
        selectionRect(s, r)
        val h = minOf(r.height(), chevronW)
        out.set(r.right + 2f, r.bottom - h, r.right + 2f + chevronW, r.bottom)
        return true
    }

    /** Scrolls so cell ([r], [c]) is on screen. */
    fun reveal(r: Int, c: Int) {
        val l = layout ?: return
        if (r >= fr()) {
            val top = (l.rowY[r] - l.frozenHeight) * scale
            val bottom = (l.rowY[(r + 1).coerceAtMost(l.nRows)] - l.frozenHeight) * scale
            val viewH = height - headerH - frozenH()
            if (top < scrollYpx) scrollYpx = top else if (bottom > scrollYpx + viewH) scrollYpx = bottom - viewH
        }
        if (c >= fc()) {
            val left = (l.colX[c] - l.colX[fc()]) * scale
            val right = (l.colX[(c + 1).coerceAtMost(l.nCols)] - l.colX[fc()]) * scale
            val viewW = width - gutterW - frozenW()
            if (left < scrollXpx) scrollXpx = left else if (right > scrollXpx + viewW) scrollXpx = right - viewW
        }
        clampScroll()
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val p = painter ?: return
        val col = colors ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(col.surface)
        val sel = selection

        // Body rows: scrolling rows first, then frozen rows over them.
        canvas.save()
        canvas.clipRect(0f, headerH + frozenH(), w, h)
        val firstY = l.frozenHeight + scrollYpx / scale
        var r = l.rowAt(firstY).coerceAtLeast(fr())
        val firstRow = r
        while (r < l.nRows) {
            val top = headerH + rowTop(r)
            if (top > h) break
            if (l.rowH(r) > 0f) drawRow(canvas, r, top, l, p, col, sel, w) { m -> r == maxOf(m.r1, firstRow, fr()) }
            r++
        }
        canvas.restore()
        for (fr in 0 until fr()) {
            if (l.rowH(fr) > 0f) drawRow(canvas, fr, headerH + rowTop(fr), l, p, col, sel, w) { m -> m.r1 == fr }
        }

        // Frozen-pane dividers.
        band.color = col.headerDivider
        if (frozenH() > 0f) canvas.drawRect(0f, headerH + frozenH() - 1f, w, headerH + frozenH() + 1f, band)
        if (frozenW() > 0f) canvas.drawRect(gutterW + frozenW() - 1f, headerH, gutterW + frozenW() + 1f, h, band)

        // Search hits and the selection.
        canvas.save()
        canvas.clipRect(gutterW, headerH, w, h)
        if (matches.isNotEmpty()) {
            val sh = sheet
            for (m in matches) {
                if (sh == null) break
                val mr = rowTop(m.first) + headerH
                if (mr > h) continue
                if (!selectionRect(GridSelection.cell(m.first, m.second, sh), rect)) continue
                if (rect.bottom < headerH) continue
                fillPaint.color = col.accent
                fillPaint.alpha = if (m == currentMatch) 115 else 46
                canvas.drawRect(rect, fillPaint)
            }
        }
        if (sel != null && selectionRect(sel, rect)) {
            val sh = sheet
            if (!sel.range.isSingle && sh?.mergeAt(sel.anchorR, sel.anchorC) != sel.range) {
                fillPaint.color = col.accent; fillPaint.alpha = 26
                canvas.drawRect(rect, fillPaint)
            }
            strokePaint.color = col.accent
            strokePaint.strokeWidth = 2f * density
            canvas.drawRect(rect, strokePaint)
            if (editMode || !sel.range.isSingle) {
                for ((x, y) in listOf(rect.left to rect.top, rect.right to rect.bottom)) {
                    fillPaint.color = 0xFFFFFFFF.toInt(); fillPaint.alpha = 255
                    canvas.drawCircle(x, y, handleR + 1.5f * density, fillPaint)
                    fillPaint.color = col.accent
                    canvas.drawCircle(x, y, handleR, fillPaint)
                }
            }
            val cr = RectF()
            if (chevronRect(sel, cr)) {
                fillPaint.color = col.surface; fillPaint.alpha = 255
                canvas.drawRoundRect(cr, 5f * density, 5f * density, fillPaint)
                strokePaint.color = col.headerDivider; strokePaint.strokeWidth = 1f
                canvas.drawRoundRect(cr, 5f * density, 5f * density, strokePaint)
                val s = 4f * density
                path.reset()
                path.moveTo(cr.centerX() - s, cr.centerY() - s / 2); path.lineTo(cr.centerX(), cr.centerY() + s / 2); path.lineTo(cr.centerX() + s, cr.centerY() - s / 2)
                strokePaint.color = col.accent; strokePaint.strokeWidth = 1.8f * density; strokePaint.strokeCap = Paint.Cap.ROUND
                canvas.drawPath(path, strokePaint)
            }
        }
        canvas.restore()

        drawHeader(canvas, l, col, sel, w)
        if (resizeCol >= 0) {
            val x = gutterW + colLeft(resizeCol) + resizeWidth * scale
            band.color = col.accent
            canvas.drawRect(x - density, 0f, x + density, h, band)
        }
    }

    private inline fun drawRow(
        canvas: Canvas, r: Int, top: Float, l: SheetLayout, p: XlsxPainter, col: GridColors, sel: GridSelection?, w: Float,
        noinline drawsMerge: (CellRange) -> Boolean
    ) {
        val rh = l.rowH(r) * scale
        canvas.save()
        canvas.translate(gutterW, top)
        canvas.clipRect(0f, -100_000f, w - gutterW, rh + 100_000f)
        p.drawRow(canvas, l, r, scale, scrollXpx, w - gutterW, drawsMerge = drawsMerge)
        canvas.restore()

        // Sticky row-number gutter.
        val inSel = sel != null && r in sel.range.r1..sel.range.r2
        band.color = col.headerBand
        canvas.drawRect(0f, top, gutterW, top + rh, band)
        if (inSel) {
            band.color = col.accent
            band.alpha = if (sel!!.isWholeRow) 77 else 36
            canvas.drawRect(0f, top, gutterW, top + rh, band)
            band.alpha = 255
        }
        headerPaint.color = if (inSel) col.accent else col.headerText
        headerPaint.isFakeBoldText = inSel
        if (rh > headerPaint.textSize * 0.8f) canvas.drawText((r + 1).toString(), gutterW / 2f, top + rh / 2f + headerTextDy, headerPaint)
        band.color = col.headerDivider
        canvas.drawRect(gutterW - 1f, top, gutterW, top + rh, band)
        canvas.drawRect(gutterW * 0.25f, top + rh - 1f, gutterW * 0.75f, top + rh, band)
    }

    private fun drawHeader(canvas: Canvas, l: SheetLayout, col: GridColors, sel: GridSelection?, w: Float) {
        band.color = col.headerBand
        canvas.drawRect(0f, 0f, w, headerH, band)
        val baseline = headerH / 2f + headerTextDy
        fun drawCol(c: Int) {
            val cw = l.colW(c) * scale
            if (cw <= 0f) return
            val x = gutterW + colLeft(c)
            if (x > w || x + cw < gutterW) return
            val inSel = sel != null && c in sel.range.c1..sel.range.c2
            if (inSel) {
                band.color = col.accent
                band.alpha = if (sel!!.isWholeColumn) 77 else 36
                canvas.drawRect(x, 0f, x + cw, headerH, band)
                band.alpha = 255
            }
            headerPaint.color = if (inSel) col.accent else col.headerText
            headerPaint.isFakeBoldText = inSel
            canvas.drawText(XlsxRefs.colLetter(c), x + cw / 2f, baseline, headerPaint)
            band.color = col.headerDivider
            canvas.drawRect(x + cw - 1f, headerH * 0.22f, x + cw, headerH * 0.78f, band)
        }
        canvas.save()
        canvas.clipRect(gutterW + frozenW(), 0f, w, headerH)
        var c = maxOf(fc(), l.colAt(l.colX[fc()] + scrollXpx / scale))
        while (c < l.nCols && gutterW + colLeft(c) < w) { drawCol(c); c++ }
        canvas.restore()
        canvas.save()
        canvas.clipRect(gutterW, 0f, w, headerH)
        for (f in 0 until fc()) drawCol(f)
        canvas.restore()
        band.color = col.headerBand
        canvas.drawRect(0f, 0f, gutterW, headerH, band)
        band.color = col.headerDivider
        canvas.drawRect(0f, headerH - 1f, w, headerH, band)
        canvas.drawRect(gutterW - 1f, 0f, gutterW, headerH, band)
    }

    // ── Gestures ──────────────────────────────────────────────────────────────────────────────

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollXpx += dx
            scrollYpx += dy
            clampScroll()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(scrollXpx.toInt(), scrollYpx.toInt(), -vx.toInt(), -vy.toInt(), 0, maxScrollX().toInt(), 0, maxScrollY().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap(e.x, e.y)
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = scale
            zoom = (zoom * d.scaleFactor).coerceIn(0.5f, 2.5f)
            val k = scale / old
            // Keep the content under the fingers in place.
            val fx = d.focusX - gutterW - frozenW()
            val fy = d.focusY - headerH - frozenH()
            scrollXpx = (scrollXpx + fx) * k - fx
            scrollYpx = (scrollYpx + fy) * k - fy
            clampScroll()
            invalidate()
            return true
        }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXpx = scroller.currX.toFloat()
            scrollYpx = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    private fun onTap(x: Float, y: Float) {
        val sh = sheet ?: return
        val sel = selection
        if (sel != null) {
            val cr = RectF()
            if (chevronRect(sel, cr)) {
                cr.inset(-8f * density, -8f * density)
                if (cr.contains(x, y)) { listener?.onDropdown(sel.anchorR, sel.anchorC); return }
            }
        }
        if (y < headerH) {
            // Column header: select the whole column.
            val cx = x - gutterW
            if (cx < 0f) return
            val l = layout ?: return
            val c = if (cx < frozenW()) l.colAt(cx / scale) else l.colAt(l.colX[fc()] + (cx - frozenW() + scrollXpx) / scale)
            listener?.onSelect(GridSelection(0, c, CellRange(0, c, XlsxRefs.MaxRow, c)))
            return
        }
        val (r, c) = hit(x, y) ?: return
        if (c < 0) {
            listener?.onSelect(GridSelection(r, 0, CellRange(r, 0, r, XlsxRefs.MaxCol)))
            return
        }
        val next = GridSelection.cell(r, c, sh)
        if (sel != null && sel.range == next.range && sel.anchorR == next.anchorR && sel.anchorC == next.anchorC) {
            listener?.onTapSelected(next.anchorR, next.anchorC)
        } else listener?.onSelect(next)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            if (beginResize(e) || beginHandleDrag(e)) return true
        }
        if (resizeCol >= 0) return continueResize(e)
        if (dragHandle != null) return continueHandleDrag(e)
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    private fun beginResize(e: MotionEvent): Boolean {
        if (!editMode || e.y > headerH) return false
        val l = layout ?: return false
        val slop = 12f * density
        for (c in 0 until l.nCols) {
            val edge = gutterW + colLeft(c + 1)
            if (edge > width + slop) break
            if (abs(e.x - edge) < slop && l.colW(c) > 0f) {
                resizeCol = c
                resizeWidth = l.colW(c)
                lastX = e.x
                return true
            }
        }
        return false
    }

    private var lastX = 0f

    private fun continueResize(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                resizeWidth = (resizeWidth + (e.x - lastX) / scale).coerceIn(12f, 1200f)
                lastX = e.x
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                listener?.onColumnResize(resizeCol, SheetLayout.charsFromPx(resizeWidth))
                resizeCol = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { resizeCol = -1; invalidate() }
        }
        return true
    }

    private fun beginHandleDrag(e: MotionEvent): Boolean {
        val sel = selection ?: return false
        if (!editMode && sel.range.isSingle) return false
        val r = RectF()
        if (!selectionRect(sel, r)) return false
        val grab = handleR * 3.2f
        val tl = hypot(e.x - r.left, e.y - r.top) < grab
        val br = hypot(e.x - r.right, e.y - r.bottom) < grab
        if (!tl && !br) return false
        dragHandle = if (br) sel.range.r1 to sel.range.c1 else sel.range.r2 to sel.range.c2
        return true
    }

    private fun continueHandleDrag(e: MotionEvent): Boolean {
        val (fixedR, fixedC) = dragHandle ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val sh = sheet ?: return true
                val sel = selection ?: return true
                val (hr, hc) = hit(e.x, e.y) ?: return true
                var range = CellRange.of(fixedR, fixedC, hr, hc.coerceAtLeast(0))
                // Grow to cover any merge the range cuts through.
                for (m in sh.merges) if (m.r1 <= range.r2 && m.r2 >= range.r1 && m.c1 <= range.c2 && m.c2 >= range.c1) {
                    range = CellRange(minOf(range.r1, m.r1), minOf(range.c1, m.c1), maxOf(range.r2, m.r2), maxOf(range.c2, m.c2))
                }
                listener?.onSelect(sel.copy(range = range))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragHandle = null
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampScroll()
    }
}
