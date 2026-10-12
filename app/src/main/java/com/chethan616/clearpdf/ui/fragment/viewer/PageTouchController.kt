package com.chethan616.clearpdf.ui.fragment.viewer

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.chethan616.clearpdf.ui.viewmodel.OcrTextBlock
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Editing logic shared by every [PdfPageView]: drawing, shapes, eraser, selecting / moving /
 * resizing markups, and long-press text selection.
 */
class PageTouchController(
    private val store: AnnotationsViewModel,
    private val blocksOf: (Int) -> List<OcrTextBlock>,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onTapEmpty()
        fun onMarkupSelectionChanged()
        fun onTextSelectionChanged()
        /** Text / note tool tapped at [at], or an existing text box / note should be edited. */
        fun onRequestText(page: Int, at: NPoint, note: Boolean, existing: MarkupRef?)
    }

    private enum class Mode { None, Pending, Draw, Shape, Erase, Move, Resize, Handle, Extend }

    private var mode = Mode.None
    private var draft: Markup? = null
    private var draftPage = -1
    private var downX = 0f
    private var downY = 0f
    private var last: NPoint = NPoint(0f, 0f)
    private var startPoint: NPoint = NPoint(0f, 0f)
    private var strokePoints = mutableListOf<NPoint>()
    private var resizeBounds: RectF? = null
    private var draggingStartHandle = false
    private var activeView: PdfPageView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var longPress: Runnable? = null

    fun draft(page: Int): Markup? = draft.takeIf { draftPage == page }

    fun onTouch(view: PdfPageView, e: MotionEvent): Boolean {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val page = view.pageIndex
        val p = view.toPage(e.x, e.y)
        val density = view.resources.displayMetrics.density
        val tol = 18f * density / w
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeView = view
                downX = e.x; downY = e.y
                last = p; startPoint = p
                mode = Mode.Pending

                // Dragging a text-selection handle wins over everything else.
                store.textSelection?.takeIf { it.page == page }?.let { sel ->
                    val ordered = blocksOf(page).readingOrder()
                    view.handlePoints(sel, ordered)?.let { (a, b) ->
                        val ra = hypot((a.x - p.x) * w, (a.y - p.y) * h)
                        val rb = hypot((b.x - p.x) * w, (b.y - p.y) * h)
                        if (minOf(ra, rb) < 28f * density) {
                            draggingStartHandle = ra < rb
                            mode = Mode.Handle
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }

                when (store.tool) {
                    EditTool.None -> scheduleLongPress(view, page, p)
                    EditTool.Draw, EditTool.Highlight -> {
                        mode = Mode.Draw
                        strokePoints = mutableListOf(p)
                        draftPage = page
                        draft = strokeDraft()
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        view.invalidate()
                    }
                    EditTool.Rect, EditTool.Ellipse, EditTool.Line, EditTool.Arrow -> {
                        mode = Mode.Shape
                        draftPage = page
                        draft = shapeDraft(p, p)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    EditTool.Eraser -> {
                        mode = Mode.Erase
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        erase(page, p, w, h, tol)
                    }
                    EditTool.Select, EditTool.Image -> {
                        if (beginTransform(view, page, p, w, h, tol, density)) {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    EditTool.Text, EditTool.Note -> Unit
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.Pending -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) cancelLongPress()
                    Mode.Draw -> {
                        if (hypot((p.x - last.x) * w, (p.y - last.y) * h) >= 1.5f) {
                            strokePoints.add(p)
                            draft = strokeDraft()
                            last = p
                            view.invalidate()
                        }
                    }
                    Mode.Shape -> { draft = shapeDraft(startPoint, p); view.invalidate() }
                    Mode.Erase -> erase(page, p, w, h, tol)
                    Mode.Move -> {
                        val ref = store.selection ?: return true
                        val m = store.get(ref) ?: return true
                        store.replace(ref, m.translated(p - last))
                        last = p
                    }
                    Mode.Resize -> {
                        val ref = store.selection ?: return true
                        val bounds = resizeBounds ?: return true
                        val m = store.get(ref) ?: return true
                        val resized = m.resized(p - last, bounds)
                        store.replace(ref, resized)
                        resizeBounds = resized.bounds(w, h)
                        last = p
                    }
                    Mode.Handle, Mode.Extend -> moveSelectionEnd(page, p)
                    Mode.None -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                when (mode) {
                    Mode.Pending -> onTap(view, page, p, w, h, tol)
                    Mode.Draw -> {
                        if (strokePoints.size > 1) store.add(page, strokeDraft())
                        clearDraft(view)
                    }
                    Mode.Shape -> {
                        val d = draft
                        if (d != null && hypot((p.x - startPoint.x) * w, (p.y - startPoint.y) * h) > 8f * density) store.add(page, d)
                        clearDraft(view)
                    }
                    Mode.Handle, Mode.Extend -> callbacks.onTextSelectionChanged()
                    else -> Unit
                }
                mode = Mode.None
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                clearDraft(view)
                mode = Mode.None
                return true
            }
        }
        return false
    }

    private fun onTap(view: PdfPageView, page: Int, p: NPoint, w: Float, h: Float, tol: Float) {
        when (store.tool) {
            EditTool.Text -> { callbacks.onRequestText(page, p, note = false, existing = null); return }
            EditTool.Note -> { callbacks.onRequestText(page, p, note = true, existing = null); return }
            else -> Unit
        }
        if (store.textSelection != null) {
            store.textSelection = null
            callbacks.onTextSelectionChanged()
            store.bump()
            return
        }
        val hit = hitMarkup(page, p, w, h, tol)
        if (hit != null) {
            val m = store.get(hit)
            // Tapping an already selected text box or note opens it for editing.
            if (store.selection == hit && (m is Markup.TextBox || m is Markup.Note)) {
                callbacks.onRequestText(page, p, note = m is Markup.Note, existing = hit)
                return
            }
            store.selection = hit
            store.bump()
            callbacks.onMarkupSelectionChanged()
            return
        }
        if (store.selection != null) {
            store.selection = null
            store.bump()
            callbacks.onMarkupSelectionChanged()
            return
        }
        callbacks.onTapEmpty()
    }

    private fun hitMarkup(page: Int, p: NPoint, w: Float, h: Float, tol: Float): MarkupRef? {
        val list = store.markups(page)
        val blocks = blocksOf(page)
        for (i in list.indices.reversed()) if (list[i].hitTest(p, w, h, blocks, tol)) return MarkupRef(page, i)
        return null
    }

    /** Starts moving or resizing the markup under the finger. False when nothing was hit. */
    private fun beginTransform(view: PdfPageView, page: Int, p: NPoint, w: Float, h: Float, tol: Float, density: Float): Boolean {
        store.selection?.takeIf { it.page == page }?.let { ref ->
            val m = store.get(ref) ?: return@let
            val b = m.bounds(w, h) ?: return@let
            val pad = 6f * density
            val hx = b.right * w + pad; val hy = b.bottom * h + pad
            if (m.isResizable() && hypot(hx - p.x * w, hy - p.y * h) < 24f * density) {
                mode = Mode.Resize
                resizeBounds = b
                return true
            }
        }
        val hit = hitMarkup(page, p, w, h, tol)
        if (hit == null || store.get(hit)?.isMovable() != true) {
            if (store.selection != null) {
                store.selection = null
                store.bump()
                callbacks.onMarkupSelectionChanged()
            }
            return false
        }
        if (store.selection != hit) {
            store.selection = hit
            store.bump()
            callbacks.onMarkupSelectionChanged()
        }
        mode = Mode.Move
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        return true
    }

    private fun erase(page: Int, p: NPoint, w: Float, h: Float, tol: Float) {
        val hit = hitMarkup(page, p, w, h, tol * 1.5f) ?: return
        store.remove(hit)
    }

    private fun strokeDraft(): Markup.Stroke {
        val highlight = store.tool == EditTool.Highlight
        return Markup.Stroke(
            points = strokePoints.toList(),
            color = store.color,
            width = if (highlight) store.strokeWidth * 3f else store.strokeWidth,
            alpha = if (highlight) 0.4f else 1f
        )
    }

    private fun shapeDraft(a: NPoint, b: NPoint): Markup = when (store.tool) {
        EditTool.Rect -> Markup.Rect(a, b, store.color)
        EditTool.Ellipse -> Markup.Oval(a, b, store.color)
        EditTool.Arrow -> Markup.Line(a, b, store.color, store.strokeWidth, arrowHead = true)
        else -> Markup.Line(a, b, store.color, store.strokeWidth)
    }

    private fun clearDraft(view: PdfPageView) {
        draft = null
        draftPage = -1
        strokePoints = mutableListOf()
        view.invalidate()
    }

    // ── Text selection ────────────────────────────────────────────────────────────────────────

    private fun scheduleLongPress(view: PdfPageView, page: Int, p: NPoint) {
        cancelLongPress()
        val r = Runnable {
            val pos = wordAt(page, p) ?: return@Runnable
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val ordered = blocksOf(page).readingOrder()
            val text = ordered[pos.block].text
            if (text.isBlank()) return@Runnable
            // A press on a space selects the nearest word instead of the blank.
            var c = pos.char.coerceIn(0, text.lastIndex)
            if (text[c].isWhitespace()) {
                val left = (c downTo 0).firstOrNull { !text[it].isWhitespace() }
                val right = (c..text.lastIndex).firstOrNull { !text[it].isWhitespace() }
                c = when {
                    left == null -> right ?: return@Runnable
                    right == null -> left
                    c - left <= right - c -> left
                    else -> right
                }
            }
            var s = c; var e = c
            while (s > 0 && !text[s - 1].isWhitespace()) s--
            while (e < text.length && !text[e].isWhitespace()) e++
            store.selection = null
            store.textSelection = TextSelection(page, TextPos(pos.block, s), TextPos(pos.block, e.coerceAtLeast(s + 1)))
            mode = Mode.Extend
            draggingStartHandle = false
            view.parent?.requestDisallowInterceptTouchEvent(true)
            store.bump()
            callbacks.onTextSelectionChanged()
        }
        longPress = r
        handler.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress() {
        longPress?.let(handler::removeCallbacks)
        longPress = null
    }

    private fun moveSelectionEnd(page: Int, p: NPoint) {
        val sel = store.textSelection?.takeIf { it.page == page } ?: return
        val pos = nearestPos(page, p) ?: return
        store.textSelection = if (mode == Mode.Handle && draggingStartHandle) {
            TextSelection(page, sel.end, pos)
        } else {
            TextSelection(page, if (mode == Mode.Handle) sel.start else sel.anchor, pos)
        }
        store.bump()
    }

    /** The character under [p], if the point is on a text block. */
    private fun wordAt(page: Int, p: NPoint): TextPos? {
        val ordered = blocksOf(page).readingOrder()
        val i = ordered.indexOfFirst { b ->
            val padY = (b.bottom - b.top) * 0.3f
            p.x >= b.left - 0.01f && p.x <= b.right + 0.01f && p.y >= b.top - padY && p.y <= b.bottom + padY
        }
        if (i < 0) return null
        return TextPos(i, charIndex(ordered[i], p.x))
    }

    /** The character position nearest to [p], for extending a selection. */
    private fun nearestPos(page: Int, p: NPoint): TextPos? {
        val ordered = blocksOf(page).readingOrder()
        if (ordered.isEmpty()) return null
        val i = ordered.indices.minByOrNull { idx ->
            val b = ordered[idx]
            val dy = when {
                p.y < b.top -> b.top - p.y
                p.y > b.bottom -> p.y - b.bottom
                else -> 0f
            }
            val dx = when {
                p.x < b.left -> b.left - p.x
                p.x > b.right -> p.x - b.right
                else -> 0f
            }
            dy * 4f + dx
        } ?: return null
        val b = ordered[i]
        val c = charIndex(b, p.x)
        // Past the middle of the last character counts as after it.
        val endChar = if (p.x > (b.charRights.getOrNull(c) ?: b.right)) c + 1 else c
        return TextPos(i, endChar.coerceIn(0, b.text.length))
    }

    private fun charIndex(b: OcrTextBlock, x: Float): Int {
        val len = b.text.length
        if (len == 0) return 0
        for (c in 0 until len) {
            val r = b.charRights.getOrNull(c)?.takeIf(Float::isFinite) ?: (b.left + (b.right - b.left) * (c + 1) / len)
            if (x <= r) return c
        }
        return len - 1
    }
}
