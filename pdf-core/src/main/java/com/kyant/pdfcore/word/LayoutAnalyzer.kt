package com.kyant.pdfcore.word

import kotlin.math.abs

/**
 * Raw page → [PageLayout]: ruled tables, text lines, underlines, two-column reading order and
 * borderless tables. Every threshold is relative to the font size, so the same rules hold for a
 * 9 pt contract and a 14 pt slide export.
 */
object LayoutAnalyzer {

    /** A gap wider than this many font sizes splits a line into segments (cells, tab stops). */
    private const val SEGMENT_GAP = 1.5f

    /** Lines whose box lies within this fraction of the page edge are header/footer candidates. */
    private const val MARGIN_BAND = 0.085f

    fun analyze(index: Int, page: RawPage, recognized: Boolean = false): PageLayout {
        val glyphs = dedupe(page.glyphs.filter { it.text.isNotEmpty() && it.left < page.width && it.right > 0 && it.baseline > 0 && it.top < page.height })

        // 1. Ruled tables claim their glyphs and images first.
        val tables = RuledTables.find(page, glyphs)
        val claimedGlyphs = HashSet<Glyph>()
        val claimedImages = HashSet<PlacedImage>()
        tables.forEach { t ->
            t.cells.forEach { c -> c.lines.forEach { l -> claimedGlyphs += l.glyphs }; claimedImages += c.images }
        }
        val tableRulings = tables.flatMap { RuledTables.rulingsOf(it, page.rulings) }.toSet()

        // 2. Underlines: short horizontal strokes hugging a baseline, not part of a table.
        val free = underline(glyphs.filter { it !in claimedGlyphs }, page.rulings.filter { it.horizontal && it !in tableRulings && it.length < page.width * 0.9f })

        // 3. Lines. Those in the margin bands are noted as header/footer candidates but stay in
        // the body: only the assembler, seeing every page, knows which ones really repeat.
        val bodyLines = buildLines(free)
        val marginLines = bodyLines.filter {
            it.bottom < page.height * MARGIN_BAND || it.top > page.height * (1 - MARGIN_BAND)
        }

        // 4. Two columns? Lines are split at the gutter into left/right halves.
        val gutter = findGutter(bodyLines)
        val bodyElements = ArrayList<Element>()
        for (line in bodyLines) bodyElements += splitAtGutter(line, gutter)
        tables.forEach { bodyElements += it }
        page.images.filter { it !in claimedImages }.forEach { bodyElements += ImageElement(it) }
        if (gutter != null) bodyElements.forEach { it.column = columnOf(it, gutter) }

        // 5. Reading order, then borderless tables inside each column's flow.
        val ordered = readingOrder(bodyElements, gutter != null)
        val withTables = BorderlessTables.detect(ordered)
        return PageLayout(index, page.width, page.height, withTables, marginLines, gutter, recognized)
    }

    // ── Glyph clean-up ──────────────────────────────────────────────────────────────────────────

    /** Drops the second copy of text drawn twice in place (fake bold, shadow effects). */
    internal fun dedupe(glyphs: List<Glyph>): List<Glyph> {
        val seen = HashSet<Triple<String, Int, Int>>()
        return glyphs.filter { g ->
            if (g.text.isBlank()) return@filter true
            seen.add(Triple(g.text, Math.round(g.left), Math.round(g.baseline)))
        }
    }

    private fun underline(glyphs: List<Glyph>, strokes: List<Ruling>): List<Glyph> {
        if (strokes.isEmpty()) return glyphs
        return glyphs.map { g ->
            val hit = strokes.any { r ->
                val below = r.position - g.baseline
                below >= -0.5f && below <= g.style.size * 0.25f + 0.5f && g.centerX in r.x1..r.x2
            }
            if (hit && !g.style.underline && g.text.isNotBlank()) g.withStyle(g.style.copy(underline = true)) else g
        }
    }

    // ── Lines ───────────────────────────────────────────────────────────────────────────────────

    /** Glyphs → lines (by baseline) → segments (by wide gaps). */
    internal fun buildLines(glyphs: List<Glyph>): List<LineElement> {
        if (glyphs.isEmpty()) return emptyList()
        val sorted = glyphs.sortedWith(compareBy({ it.baseline }, { it.left }))
        val groups = ArrayList<MutableList<Glyph>>()
        var baseline = 0f
        var size = 0f
        for (g in sorted) {
            val current = groups.lastOrNull()
            val gs = g.style.size.coerceAtLeast(1f)
            // OCR word boxes are ragged (marks above, descenders below), so they get more slack.
            val slack = if (g.isWord) 0.5f else 0.3f
            val sameLine = current != null && (
                abs(g.baseline - baseline) <= slack * minOf(gs, size) ||
                    // A superscript/subscript or a smaller run riding on the line.
                    (gs < size * 0.85f && g.baseline > baseline - size && g.top < baseline)
                )
            if (sameLine) current!!.add(g) else {
                groups += mutableListOf(g); baseline = g.baseline; size = gs
            }
        }
        return groups.mapNotNull { group ->
            val segments = segmentsOf(group)
            if (segments.isEmpty()) null else LineElement(segments)
        }
    }

    internal fun segmentsOf(glyphs: List<Glyph>): List<Segment> {
        val byX = glyphs.sortedBy { it.left }
        val out = ArrayList<Segment>()
        var current = ArrayList<Glyph>()
        var right = Float.NEGATIVE_INFINITY
        for (g in byX) {
            if (g.text.isBlank() && current.isEmpty()) continue
            val gap = g.left - right
            if (current.isNotEmpty() && gap > SEGMENT_GAP * g.style.size.coerceAtLeast(4f)) {
                out += trimmed(current); current = ArrayList()
            }
            if (g.text.isBlank() && current.isEmpty()) continue
            current += g
            if (g.text.isNotBlank()) right = maxOf(right, g.right)
        }
        if (current.isNotEmpty()) out += trimmed(current)
        return out.filter { s -> s.glyphs.any { it.text.isNotBlank() } }
    }

    private fun trimmed(glyphs: List<Glyph>): Segment {
        val last = glyphs.indexOfLast { it.text.isNotBlank() }
        return Segment(glyphs.subList(0, last + 1).toList())
    }

    // ── Columns ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A vertical strip in the middle half of the text area that almost no line crosses, with text
     * on both sides of it in many lines. Word spaces are bridged, so justified text doesn't open
     * fake gutters.
     */
    internal fun findGutter(lines: List<LineElement>): ClosedFloatingPointRange<Float>? {
        if (lines.size < 8) return null
        val left = lines.minOf { it.left }
        val right = lines.maxOf { it.right }
        val width = right - left
        if (width < 200f) return null
        val bins = IntArray(width.toInt() + 1)
        val spans = lines.map { line -> wordSpans(line) }
        for (lineSpans in spans) {
            val covered = BooleanArray(bins.size)
            for ((a, b) in lineSpans) {
                for (x in (a - left).toInt().coerceAtLeast(0)..(b - left).toInt().coerceAtMost(bins.size - 1)) covered[x] = true
            }
            for (x in bins.indices) if (covered[x]) bins[x]++
        }
        val from = (width * 0.25f).toInt()
        val to = (width * 0.75f).toInt()
        val allowed = maxOf(1, lines.size / 30)
        var best: IntRange? = null
        var start = -1
        for (x in from..to + 1) {
            val open = x <= to && bins[x] <= allowed
            if (open && start < 0) start = x
            if (!open && start >= 0) {
                val r = start until x
                if (best == null || r.count() > best.count()) best = r
                start = -1
            }
        }
        val gap = best ?: return null
        if (gap.count() < 6) return null
        val g1 = left + gap.first
        val g2 = left + gap.last + 1
        // Both columns must carry real text, not a lone label column.
        val leftLines = spans.count { s -> s.any { it.second <= g1 } }
        val rightLines = spans.count { s -> s.any { it.first >= g2 } }
        if (leftLines < lines.size * 0.3f || rightLines < lines.size * 0.3f) return null
        // Prose columns run up to the gutter and their lines are long; a table's first column
        // (names, labels) is short and ragged.
        val leftWidth = g1 - left
        val leftPieces = lines.mapNotNull { line ->
            val glyphs = line.glyphs.filter { it.text.isNotBlank() && it.right <= g1 + 1f }
            if (glyphs.isEmpty()) null else glyphs.maxOf { it.right } to glyphs.sumOf { it.text.length }
        }
        val nearGutter = leftPieces.count { g1 - it.first <= leftWidth * 0.1f }
        val chars = leftPieces.map { it.second }.sorted()
        if (nearGutter < leftPieces.size * 0.5f || chars[chars.size / 2] < 20) return null
        return g1..g2
    }

    private fun wordSpans(line: LineElement): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (g in line.glyphs.filter { it.text.isNotBlank() }.sortedBy { it.left }) {
            val last = out.lastOrNull()
            if (last != null && g.left - last.second < g.style.size * 0.6f) out[out.lastIndex] = last.first to maxOf(last.second, g.right)
            else out += g.left to g.right
        }
        return out
    }

    private fun splitAtGutter(line: LineElement, gutter: ClosedFloatingPointRange<Float>?): List<LineElement> {
        if (gutter == null) return listOf(line)
        val mid = (gutter.start + gutter.endInclusive) / 2
        val crosses = line.segments.any { it.left < gutter.start && it.right > gutter.endInclusive }
        if (crosses) return listOf(line)
        val (l, r) = line.segments.partition { it.right <= mid }
        return listOfNotNull(l.takeIf { it.isNotEmpty() }?.let(::LineElement), r.takeIf { it.isNotEmpty() }?.let(::LineElement))
    }

    private fun columnOf(e: Element, gutter: ClosedFloatingPointRange<Float>): Column = when {
        e.right <= gutter.endInclusive -> Column.LEFT
        e.left >= gutter.start -> Column.RIGHT
        else -> Column.FULL
    }

    /**
     * Top to bottom; on a two-column page, full-width elements cut the page into bands and each
     * band is read left column first.
     */
    internal fun readingOrder(elements: List<Element>, twoColumns: Boolean): List<Element> {
        val byTop = elements.sortedWith(compareBy({ it.top }, { it.left }))
        if (!twoColumns) return byTop
        val out = ArrayList<Element>()
        val band = ArrayList<Element>()
        fun flush() {
            out += band.filter { it.column == Column.LEFT }
            out += band.filter { it.column == Column.RIGHT }
            band.clear()
        }
        for (e in byTop) {
            if (e.column == Column.FULL) { flush(); out += e } else band += e
        }
        flush()
        return out
    }
}
