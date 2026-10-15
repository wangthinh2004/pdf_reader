package com.kyant.pdfcore.word

/*
 * PDF → Word works in three stages, each with its own model:
 *
 *  1. Raw page ([RawPage]): what PdfBox saw — glyphs, axis-aligned rulings and placed images, in
 *     points with a top-left origin, already corrected for crop box and page rotation.
 *  2. Page layout ([PageLayout], built by [LayoutAnalyzer]): lines grouped, tables found, columns
 *     resolved into reading order, running headers/footers set aside.
 *  3. Word document ([WordDocument], built by [DocumentAssembler]): paragraphs, tables and pictures
 *     with Word semantics (styles, indents, spacing), written by [DocxWriter].
 *
 * Stages 2 and 3 are plain Kotlin with no Android or PdfBox types, so they are unit-tested on the JVM.
 */

// ── Stage 1: raw page ────────────────────────────────────────────────────────────────────────────

data class TextStyle(
    /** Font family as Word would name it ("Times New Roman"), never a subset name. */
    val font: String,
    /** Points. */
    val size: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** 0xRRGGBB. */
    val color: Int = 0,
    val underline: Boolean = false
)

/** One drawn character (or ligature / OCR word). [baseline] is measured from the top of the page. */
class Glyph(
    val text: String,
    val left: Float,
    val baseline: Float,
    val width: Float,
    /** Ascent-ish height above the baseline; never 0. */
    val height: Float,
    val style: TextStyle,
    /** A whole word (from OCR): always separated from its neighbours by a space. */
    val isWord: Boolean = false
) {
    val right get() = left + width
    val top get() = baseline - height
    val centerX get() = left + width / 2
    val centerY get() = baseline - height / 2

    fun withStyle(style: TextStyle) = Glyph(text, left, baseline, width, height, style, isWord)
}

/** A horizontal or vertical stroke (table border, underline, rule). Coordinates are ordered. */
class Ruling(x1: Float, y1: Float, x2: Float, y2: Float) {
    val x1 = minOf(x1, x2)
    val x2 = maxOf(x1, x2)
    val y1 = minOf(y1, y2)
    val y2 = maxOf(y1, y2)
    val horizontal get() = (y2 - y1) <= (x2 - x1)
    val length get() = if (horizontal) x2 - x1 else y2 - y1
    /** The constant coordinate: y for a horizontal ruling, x for a vertical one. */
    val position get() = if (horizontal) (y1 + y2) / 2 else (x1 + x2) / 2
}

/** An image drawn on the page; the pixels live in the converter's media list under [mediaId]. */
class PlacedImage(val mediaId: Int, val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
}

class RawPage(
    val width: Float,
    val height: Float,
    val glyphs: List<Glyph>,
    val rulings: List<Ruling> = emptyList(),
    val images: List<PlacedImage> = emptyList()
)

/** Encoded picture bytes for word/media. */
class Media(val id: Int, val bytes: ByteArray, val extension: String, val pixelWidth: Int, val pixelHeight: Int)

// ── Stage 2: page layout ─────────────────────────────────────────────────────────────────────────

/** A run of same-styled text. */
data class Run(val text: String, val style: TextStyle)

/** Glyphs on one baseline, left to right, with no gap wider than a column/cell gap. */
class Segment(val glyphs: List<Glyph>) {
    val left = glyphs.minOf { it.left }
    val right = glyphs.maxOf { it.right }
    val top = glyphs.minOf { it.top }
    val bottom = glyphs.maxOf { it.baseline } + glyphs.maxOf { it.height } * 0.25f
    val baseline = glyphs.first().baseline
    val runs: List<Run> by lazy { TextBuilder.runs(glyphs) }
    val text: String by lazy { runs.joinToString("") { it.text } }
}

/** Where an element sits relative to a two-column page. */
enum class Column { FULL, LEFT, RIGHT }

sealed class Element {
    abstract val left: Float
    abstract val right: Float
    abstract val top: Float
    abstract val bottom: Float
    var column: Column = Column.FULL
}

/** One visual text line (possibly several segments, e.g. "Name:      Value"). */
class LineElement(val segments: List<Segment>) : Element() {
    override val left = segments.minOf { it.left }
    override val right = segments.maxOf { it.right }
    override val top = segments.minOf { it.top }
    override val bottom = segments.maxOf { it.bottom }
    val baseline = segments.first().baseline
    val glyphs: List<Glyph> get() = segments.flatMap { it.glyphs }
    /** The size most of this line's characters are set in. */
    val size: Float by lazy { dominantSize(glyphs) }
    val text: String get() = segments.joinToString("\t") { it.text }
}

class TableCellLayout(
    val row: Int,
    val col: Int,
    val rowSpan: Int,
    val colSpan: Int,
    val lines: List<LineElement>,
    val images: List<PlacedImage> = emptyList()
)

class TableElement(
    /** Column boundaries, left to right (n + 1 values). */
    val xs: List<Float>,
    /** Row boundaries, top to bottom (n + 1 values). */
    val ys: List<Float>,
    val cells: List<TableCellLayout>,
    val bordered: Boolean
) : Element() {
    override val left get() = xs.first()
    override val right get() = xs.last()
    override val top get() = ys.first()
    override val bottom get() = ys.last()
}

class ImageElement(val image: PlacedImage) : Element() {
    override val left get() = image.left
    override val right get() = image.right
    override val top get() = image.top
    override val bottom get() = image.bottom
}

class PageLayout(
    val index: Int,
    val width: Float,
    val height: Float,
    /** Body content in reading order. */
    val elements: List<Element>,
    /** Lines in the top/bottom margin bands (also in [elements]): running header/footer candidates. */
    val marginLines: List<LineElement>,
    /** The gutter between two columns, or null for a single-column page. */
    val gutter: ClosedFloatingPointRange<Float>?,
    /** True when the text came from OCR rather than the PDF's own text layer. */
    val recognized: Boolean = false
)

// ── Stage 3: Word document ───────────────────────────────────────────────────────────────────────

enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

sealed class Block {
    var pageBreakBefore = false
}

class Paragraph(
    val runs: List<Run>,
    val align: Align = Align.LEFT,
    /** Points, relative to the text margin. */
    val indentLeft: Float = 0f,
    /** Points; negative = hanging indent. */
    val firstLineIndent: Float = 0f,
    val spaceBefore: Float = 0f,
    /** 0 = body text, 1..3 = Heading 1..3. */
    var heading: Int = 0,
    /** The bullet character of a bulleted list item ("•", "-", …), or null. */
    val bullet: String? = null,
    /** 0-based list level, for [bullet] items. */
    val bulletLevel: Int = 0
) : Block() {
    val text get() = runs.joinToString("") { it.text }
}

class Picture(val mediaId: Int, val width: Float, val height: Float, val align: Align, val indentLeft: Float = 0f) : Block()

class TableCell(
    val colSpan: Int,
    /** RESTART for the top cell of a vertical merge, CONTINUE for the cells it swallows. */
    val vMerge: VMerge,
    val content: List<Block>
)

enum class VMerge { NONE, RESTART, CONTINUE }

class TableRow(val height: Float, val cells: List<TableCell>)

class Table(val columnWidths: List<Float>, val rows: List<TableRow>, val bordered: Boolean, val indentLeft: Float) : Block()

class PageSetup(
    val width: Float,
    val height: Float,
    val marginLeft: Float,
    val marginRight: Float,
    val marginTop: Float,
    val marginBottom: Float
) {
    val contentWidth get() = width - marginLeft - marginRight
}

class WordDocument(
    val title: String,
    val page: PageSetup,
    val blocks: List<Block>,
    val header: List<Paragraph> = emptyList(),
    val footer: List<Paragraph> = emptyList(),
    /** Body text size and family, for the document defaults. */
    val bodyStyle: TextStyle,
    /** Line height as a multiple of the font size, measured from the PDF. */
    val lineSpacing: Float = 1.15f
)

/** Field placeholder for the current page number inside a header/footer run. */
const val PAGE_FIELD = "\u0000PAGE\u0000"

internal fun dominantSize(glyphs: List<Glyph>): Float {
    if (glyphs.isEmpty()) return 0f
    val weights = HashMap<Float, Int>()
    for (g in glyphs) {
        val key = Math.round(g.style.size * 2) / 2f
        weights[key] = (weights[key] ?: 0) + g.text.length
    }
    return weights.maxByOrNull { it.value }!!.key
}
