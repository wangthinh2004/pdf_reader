package com.chethan616.clearpdf.ui.fragment.viewer

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import com.chethan616.clearpdf.ui.viewmodel.ExportOverlay
import com.chethan616.clearpdf.ui.viewmodel.NormalizedPoint
import com.chethan616.clearpdf.ui.viewmodel.OcrTextBlock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point on a page, normalised to the page: x and y both run 0..1 across the page. */
data class NPoint(val x: Float, val y: Float) {
    operator fun plus(o: NPoint) = NPoint(x + o.x, y + o.y)
    operator fun minus(o: NPoint) = NPoint(x - o.x, y - o.y)
    fun toExport() = NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** Annotation tools of the viewer's editor. */
enum class EditTool { None, Draw, Highlight, Rect, Ellipse, Line, Arrow, Text, Note, Image, Eraser, Select }

/**
 * One annotation on a page. Every coordinate is normalised to the page (see [NPoint]), so markups
 * stay put at any zoom or render size and export to the PDF without conversion. Widths are a
 * fraction of the page width, font sizes a fraction of the page height.
 */
sealed class Markup {
    data class Stroke(val points: List<NPoint>, val color: Int, val width: Float, val alpha: Float = 1f) : Markup()
    data class Rect(val start: NPoint, val end: NPoint, val color: Int, val alpha: Float = 1f, val filled: Boolean = false) : Markup()
    data class Oval(val start: NPoint, val end: NPoint, val color: Int, val alpha: Float = 1f, val filled: Boolean = false) : Markup()
    data class Line(
        val start: NPoint, val end: NPoint, val color: Int, val width: Float,
        val alpha: Float = 1f, val arrowHead: Boolean = false
    ) : Markup()

    /** Highlight over a character range of an OCR / text-layer block. [groupId] ties lines of one selection together. */
    data class TextHighlight(
        val blockId: String, val start: Int, val end: Int, val color: Int,
        val alpha: Float = 0.30f, val groupId: Long = 0L
    ) : Markup()

    /** Underline or strike-through over a character range of a text block. */
    data class TextLine(
        val blockId: String, val start: Int, val end: Int, val color: Int, val width: Float,
        val strikeThrough: Boolean, val groupId: Long = 0L
    ) : Markup()

    data class Image(val id: Long, val bitmap: Bitmap, val start: NPoint, val end: NPoint, val isSignature: Boolean = false) : Markup()

    /** Inserted text. [position] is the top-left. */
    data class TextBox(val id: Long, val position: NPoint, val text: String, val color: Int, val fontSize: Float) : Markup()

    /** Sticky note icon anchored at its top-left. */
    data class Note(val id: Long, val anchor: NPoint, val text: String, val color: Int) : Markup()
}

// ── Geometry ──────────────────────────────────────────────────────────────────────────────────

private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

/** Note icon edge as a fraction of the page width. */
const val NOTE_SIZE = 0.06f

/** Width of a text box's widest line, as a fraction of the page width. */
fun Markup.TextBox.widthFraction(pageW: Float, pageH: Float): Float {
    measurePaint.textSize = fontSize * pageH
    val lines = if (text.isEmpty()) listOf(" ") else text.split("\n")
    val px = (lines.maxOfOrNull { measurePaint.measureText(it) } ?: 0f).coerceAtLeast(fontSize * pageH)
    return px / pageW.coerceAtLeast(1f)
}

fun Markup.TextBox.lineCount() = if (text.isEmpty()) 1 else text.split("\n").size

/** Normalised bounds used for selection, move and resize. Null for text-anchored markups. */
fun Markup.bounds(pageW: Float, pageH: Float): RectF? = when (this) {
    is Markup.Stroke -> if (points.isEmpty()) null else RectF(
        points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y }
    )
    is Markup.Rect -> box(start, end)
    is Markup.Oval -> box(start, end)
    is Markup.Line -> box(start, end)
    is Markup.Image -> box(start, end)
    is Markup.TextBox -> RectF(
        position.x, position.y,
        position.x + widthFraction(pageW, pageH),
        position.y + fontSize * 1.2f * lineCount()
    )
    is Markup.Note -> RectF(anchor.x, anchor.y, anchor.x + NOTE_SIZE, anchor.y + NOTE_SIZE * pageW / pageH.coerceAtLeast(1f))
    else -> null
}

private fun box(a: NPoint, b: NPoint) = RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

/** Whether a tap at [p] (normalised) lands on this markup. [tol] is a normalised tolerance. */
fun Markup.hitTest(p: NPoint, pageW: Float, pageH: Float, blocks: List<OcrTextBlock>, tol: Float): Boolean = when (this) {
    is Markup.Stroke -> points.any { dist(it, p, pageW, pageH) <= max(width, tol) }
    is Markup.Line -> distToSegment(p, start, end, pageW, pageH) <= max(width, tol)
    is Markup.TextHighlight -> textRect(blocks, blockId, start, end)?.let { r -> r.inset(-tol, -tol / 2); r.contains(p.x, p.y) } == true
    is Markup.TextLine -> textRect(blocks, blockId, start, end)?.let { r -> r.inset(-tol, -tol / 2); r.contains(p.x, p.y) } == true
    else -> bounds(pageW, pageH)?.let { r -> r.inset(-tol / 2, -tol / 2); r.contains(p.x, p.y) } == true
}

/** Distance in page-width units, correcting for the page's aspect ratio. */
private fun dist(a: NPoint, b: NPoint, pageW: Float, pageH: Float): Float =
    hypot(a.x - b.x, (a.y - b.y) * pageH / pageW.coerceAtLeast(1f))

private fun distToSegment(p: NPoint, a: NPoint, b: NPoint, pageW: Float, pageH: Float): Float {
    val k = pageH / pageW.coerceAtLeast(1f)
    val ax = a.x; val ay = a.y * k; val bx = b.x; val by = b.y * k; val px = p.x; val py = p.y * k
    val l2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
    if (l2 == 0f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / l2).coerceIn(0f, 1f)
    return hypot(px - (ax + t * (bx - ax)), py - (ay + t * (by - ay)))
}

/** Normalised rectangle covering characters [start, end) of a text block. */
fun textRect(blocks: List<OcrTextBlock>, blockId: String, start: Int, end: Int): RectF? {
    val block = blocks.firstOrNull { it.id == blockId } ?: return null
    return textRangeRect(block, start, end)
}

fun textRangeRect(block: OcrTextBlock, start: Int, end: Int): RectF {
    val len = block.text.length
    val s = start.coerceIn(0, len)
    val e = (if (end < 0) len else end).coerceIn(s, len)
    if (s >= e) return RectF(block.left, block.top, block.right, block.bottom)
    // Character geometry is normally exact; interpolate any missing edges across the block.
    fun charLeft(i: Int) = block.charLefts.getOrNull(i)?.takeIf(Float::isFinite)
        ?: (block.left + (block.right - block.left) * i / len.coerceAtLeast(1))
    fun charRight(i: Int) = block.charRights.getOrNull(i)?.takeIf(Float::isFinite)
        ?: (block.left + (block.right - block.left) * (i + 1) / len.coerceAtLeast(1))
    val left = charLeft(s).coerceIn(block.left, block.right)
    val right = charRight(e - 1).coerceIn(block.left, block.right).coerceAtLeast(left)
    return RectF(left, block.top, right, block.bottom)
}

/** The y of an underline / strike-through for a text range rectangle. */
fun Markup.TextLine.lineY(r: RectF): Float =
    if (strikeThrough) r.centerY() else r.bottom + (r.height() * 0.08f)

// ── Editing ───────────────────────────────────────────────────────────────────────────────────

fun Markup.isMovable(): Boolean = this !is Markup.TextHighlight && this !is Markup.TextLine

fun Markup.isResizable(): Boolean = isMovable() && this !is Markup.Note

fun Markup.isRecolorable(): Boolean = this is Markup.Stroke || this is Markup.Rect || this is Markup.Oval ||
    this is Markup.Line || this is Markup.TextHighlight || this is Markup.TextLine || this is Markup.TextBox || this is Markup.Note

fun Markup.translated(d: NPoint): Markup = when (this) {
    is Markup.Stroke -> copy(points = points.map { it + d })
    is Markup.Rect -> copy(start = start + d, end = end + d)
    is Markup.Oval -> copy(start = start + d, end = end + d)
    is Markup.Line -> copy(start = start + d, end = end + d)
    is Markup.Image -> copy(start = start + d, end = end + d)
    is Markup.TextBox -> copy(position = position + d)
    is Markup.Note -> copy(anchor = anchor + d)
    else -> this
}

/** Resize by dragging the bottom-right corner of [bounds] by [d]. */
fun Markup.resized(d: NPoint, bounds: RectF): Markup {
    val minSize = 0.01f
    fun corner() = NPoint((bounds.right + d.x).coerceAtLeast(bounds.left + minSize), (bounds.bottom + d.y).coerceAtLeast(bounds.top + minSize))
    return when (this) {
        is Markup.Rect -> copy(start = NPoint(bounds.left, bounds.top), end = corner())
        is Markup.Oval -> copy(start = NPoint(bounds.left, bounds.top), end = corner())
        is Markup.Image -> {
            // Keep the picture's aspect ratio.
            val w = (bounds.width() + d.x).coerceAtLeast(0.03f)
            val h = w * bounds.height() / bounds.width().coerceAtLeast(0.0001f)
            copy(start = NPoint(bounds.left, bounds.top), end = NPoint(bounds.left + w, bounds.top + h))
        }
        is Markup.Line -> {
            val br = NPoint(bounds.right, bounds.bottom)
            if (abs(end.x - br.x) + abs(end.y - br.y) <= abs(start.x - br.x) + abs(start.y - br.y)) copy(end = end + d)
            else copy(start = start + d)
        }
        is Markup.Stroke -> {
            val fx = ((bounds.width() + d.x) / bounds.width().coerceAtLeast(0.001f)).coerceIn(0.2f, 8f)
            val fy = ((bounds.height() + d.y) / bounds.height().coerceAtLeast(0.001f)).coerceIn(0.2f, 8f)
            val f = (fx + fy) / 2f
            copy(points = points.map { NPoint(bounds.left + (it.x - bounds.left) * f, bounds.top + (it.y - bounds.top) * f) })
        }
        is Markup.TextBox -> {
            val factor = ((bounds.height() + d.y * 0.5f) / bounds.height().coerceAtLeast(0.001f)).coerceIn(0.3f, 6f)
            copy(fontSize = (fontSize * factor).coerceIn(0.006f, 0.25f))
        }
        else -> this
    }
}

fun Markup.color(): Int? = when (this) {
    is Markup.Stroke -> color
    is Markup.Rect -> color
    is Markup.Oval -> color
    is Markup.Line -> color
    is Markup.TextHighlight -> color
    is Markup.TextLine -> color
    is Markup.TextBox -> color
    is Markup.Note -> color
    else -> null
}

fun Markup.recolored(c: Int): Markup = when (this) {
    is Markup.Stroke -> copy(color = c)
    is Markup.Rect -> copy(color = c)
    is Markup.Oval -> copy(color = c)
    is Markup.Line -> copy(color = c)
    is Markup.TextHighlight -> copy(color = c)
    is Markup.TextLine -> copy(color = c)
    is Markup.TextBox -> copy(color = c)
    is Markup.Note -> copy(color = c)
    else -> this
}

// ── Export ────────────────────────────────────────────────────────────────────────────────────

/** Converts a page's markups to the view model's export overlays. */
fun List<Markup>.toExportOverlays(blocks: List<OcrTextBlock>): List<ExportOverlay> = mapNotNull { m ->
    when (m) {
        is Markup.Stroke -> if (m.points.size > 1) ExportOverlay.Stroke(m.points.map { it.toExport() }, m.color, m.width, m.alpha) else null
        is Markup.Rect -> ExportOverlay.RectShape(m.start.toExport(), m.end.toExport(), m.color, m.alpha, m.filled)
        is Markup.Oval -> ExportOverlay.OvalShape(m.start.toExport(), m.end.toExport(), m.color, m.alpha, m.filled)
        is Markup.Line -> ExportOverlay.LineShape(m.start.toExport(), m.end.toExport(), m.color, m.width, m.alpha, m.arrowHead)
        is Markup.TextHighlight -> textRect(blocks, m.blockId, m.start, m.end)?.let { r ->
            ExportOverlay.RectShape(NormalizedPoint(r.left, r.top), NormalizedPoint(r.right, r.bottom), m.color, m.alpha, filled = true)
        }
        is Markup.TextLine -> textRect(blocks, m.blockId, m.start, m.end)?.let { r ->
            val y = m.lineY(r)
            ExportOverlay.LineShape(NormalizedPoint(r.left, y), NormalizedPoint(r.right, y), m.color, m.width, 1f, false)
        }
        is Markup.Image -> if (!m.bitmap.isRecycled) ExportOverlay.ImageStamp(m.bitmap, m.start.toExport(), m.end.toExport()) else null
        is Markup.TextBox -> if (m.text.isNotBlank()) ExportOverlay.TextStamp(m.position.toExport(), m.text, m.color, m.fontSize) else null
        is Markup.Note -> ExportOverlay.NoteStamp(m.anchor.toExport(), m.text, m.color)
    }
}

/** Repaints a signature's ink in [colorArgb], keeping its alpha. */
fun recolorSignature(source: Bitmap, colorArgb: Int): Bitmap {
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return source
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    val rgb = colorArgb and 0x00FFFFFF
    for (i in pixels.indices) {
        val a = android.graphics.Color.alpha(pixels[i])
        pixels[i] = if (a == 0) 0 else (a shl 24) or rgb
    }
    return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    }
}
