package com.kyant.pdfcore.word

import kotlin.math.abs

/**
 * Consecutive lines of one text region (page body, a column, a table cell) → Word paragraphs.
 *
 * A line continues the paragraph above when it is set in the same size, follows at the normal
 * leading, starts at the paragraph's left edge and the line above ran (nearly) to the right edge.
 * Anything else — a short line, a gap, an indent, a list marker, a bold/plain switch — ends it.
 */
internal class ParagraphBuilder(
    /** Typical baseline-to-baseline distance as a multiple of the font size. */
    private val leading: Float,
    /** Typical whitespace between two lines of one paragraph, as a multiple of the font size. */
    private val lineGap: Float
) {

    class Region(val left: Float, val right: Float) {
        val width get() = right - left
        val center get() = (left + right) / 2
    }

    /** A built paragraph plus what heading detection needs to know about it. */
    class Built(val paragraph: Paragraph, val lineCount: Int, val size: Float, val boldShare: Float, val top: Float, val bottom: Float)

    fun build(lines: List<LineElement>, region: Region, previousBottom: Float?, inCell: Boolean = false): List<Built> {
        val groups = ArrayList<MutableList<LineElement>>()
        for (line in lines) {
            val current = groups.lastOrNull()
            if (current == null || breaksBefore(current, line, region)) groups += mutableListOf(line) else current += line
        }
        var bottom = previousBottom
        return groups.map { group ->
            val built = paragraph(group, region, bottom, inCell)
            bottom = group.last().bottom
            built
        }
    }

    private fun breaksBefore(group: List<LineElement>, line: LineElement, region: Region): Boolean {
        val prev = group.last()
        val size = prev.size.coerceAtLeast(1f)
        val delta = line.baseline - prev.baseline
        if (delta <= 0f) return true
        if (abs(line.size - prev.size) > maxOf(1f, size * 0.12f)) return true
        if (delta > size * leading * 1.45f + 1f) return true
        if (Markers.of(line) != null) return true
        if (allBold(line) != allBold(prev) && (allBold(line) || group.all(::allBold))) return true

        // Centered blocks (titles, addresses) keep their lines together.
        if (centered(prev, region) && centered(line, region) && Markers.of(group.first()) == null) return false

        // The line above ended early on purpose only if this line's first word would have fit
        // in the space it left; otherwise the word wrapped and the paragraph goes on.
        val room = region.right - prev.right
        val shortLine = room > firstWordWidth(line) + size * 0.5f
        if (shortLine && !prev.text.trimEnd().endsWith("-")) return true

        val marker = Markers.of(group.first())
        val bodyLeft = when {
            marker != null && group.size == 1 -> marker.textStart
            group.size >= 2 -> group[1].left
            else -> null
        }
        if (bodyLeft == null) {
            // Second line: a first-line indent above it is fine, a deeper indent is a new block.
            if (line.left > prev.left + size) return true
            return false
        }
        return abs(line.left - bodyLeft) > size
    }

    private fun firstWordWidth(line: LineElement): Float {
        val glyphs = line.glyphs.filter { it.text.isNotBlank() }.sortedBy { it.left }
        if (glyphs.isEmpty()) return 0f
        var end = glyphs.first().right
        for (k in 1 until glyphs.size) {
            val g = glyphs[k]
            if (g.isWord || g.left - end > g.style.size * 0.18f) break
            end = g.right
        }
        return end - glyphs.first().left
    }

    private fun paragraph(group: List<LineElement>, region: Region, previousBottom: Float?, inCell: Boolean): Built {
        val first = group.first()
        val size = dominantSize(group.flatMap { it.glyphs })
        val marker = Markers.of(first)

        // Text: segments joined by tabs, lines by spaces (or nothing after a hyphenated break).
        val runs = ArrayList<Run>()
        group.forEachIndexed { i, line ->
            line.segments.forEachIndexed { s, seg ->
                if (s > 0) runs += Run("\t", seg.runs.first().style)
                runs += seg.runs
            }
            if (i < group.lastIndex) {
                val last = runs.last()
                val next = group[i + 1].segments.first().text
                if (last.text.endsWith("-") && next.firstOrNull()?.isLowerCase() == true) {
                    runs[runs.lastIndex] = Run(last.text.dropLast(1), last.style)
                } else if (!last.text.endsWith(" ")) {
                    runs += Run(" ", last.style)
                }
            }
        }
        var merged = TextBuilder.merge(runs)

        // List items are never centred or right-aligned, however symmetric their indents look.
        val align = alignment(group, region, size).let {
            if (marker != null && (it == Align.CENTER || it == Align.RIGHT)) Align.LEFT else it
        }
        var indentLeft = 0f
        var firstLine = 0f
        var bullet: String? = null
        var level = 0
        if (marker != null) {
            indentLeft = marker.textStart - region.left
            firstLine = -(marker.textStart - first.left)
            if (marker.bullet != null) {
                bullet = marker.bullet
                merged = stripMarker(merged, marker.length)
                level = (((first.left - region.left) / 18f).toInt()).coerceIn(0, 2)
            }
        } else if (align == Align.LEFT || align == Align.JUSTIFY) {
            val bodyLeft = if (group.size >= 2) group[1].left else first.left
            indentLeft = bodyLeft - region.left
            firstLine = first.left - bodyLeft
        }
        if (inCell && marker == null) { indentLeft = 0f; firstLine = 0f }
        if (abs(indentLeft) < size * 0.5f || indentLeft < 0f) indentLeft = 0f
        if (abs(firstLine) < size * 0.5f) firstLine = 0f

        val spaceBefore = previousBottom?.let { (first.top - it - lineGap * size).coerceIn(0f, 72f) }?.takeIf { it >= 2f } ?: 0f
        val paragraph = Paragraph(
            runs = merged,
            align = align,
            indentLeft = indentLeft,
            firstLineIndent = firstLine,
            spaceBefore = if (inCell) 0f else spaceBefore,
            bullet = bullet,
            bulletLevel = level
        )
        return Built(paragraph, group.size, size, boldShare(group.flatMap { it.glyphs }), first.top, group.last().bottom)
    }

    private fun alignment(group: List<LineElement>, region: Region, size: Float): Align {
        val tol = maxOf(size, region.width * 0.03f)
        if (group.all { centered(it, region) }) return Align.CENTER
        if (group.all { abs(it.right - region.right) <= size && it.left > region.left + region.width * 0.4f }) return Align.RIGHT
        if (group.size >= 2 && group.dropLast(1).all { abs(it.right - region.right) <= tol * 1.5f }) return Align.JUSTIFY
        return Align.LEFT
    }

    private fun centered(line: LineElement, region: Region): Boolean {
        val tol = maxOf(line.size, region.width * 0.03f)
        return abs((line.left + line.right) / 2 - region.center) <= tol && line.left > region.left + line.size * 2f
    }

    private fun allBold(line: LineElement) = line.glyphs.filter { it.text.isNotBlank() }.all { it.style.bold }

    /** Fraction of the visible characters set in bold. */
    private fun boldShare(glyphs: List<Glyph>): Float {
        val visible = glyphs.filter { it.text.isNotBlank() }
        if (visible.isEmpty()) return 0f
        return visible.sumOf { g -> if (g.style.bold) g.text.length else 0 }.toFloat() / visible.sumOf { it.text.length }
    }

    private fun stripMarker(runs: List<Run>, length: Int): List<Run> {
        var remaining = length
        val out = ArrayList<Run>()
        for (r in runs) {
            if (remaining <= 0) { out += r; continue }
            if (r.text.length <= remaining) { remaining -= r.text.length; continue }
            out += Run(r.text.substring(remaining).trimStart(), r.style)
            remaining = 0
        }
        return out.filter { it.text.isNotEmpty() }
    }
}

/** List markers at the start of a line. */
internal object Markers {

    class Marker(val bullet: String?, val length: Int, val textStart: Float)

    private val BULLET = Regex("^([•●▪■◦○◆◇►▸‣⁃∙·\\-–—*+✓✔➢➤□❖])(\\s+|\\t)")
    private val NUMBER = Regex("^((\\d{1,3}(\\.\\d{1,3}){0,3}[.)])|([a-zA-Z][.)])|(\\(\\d{1,3}\\))|(\\([a-zA-Z]\\))|([ivxIVX]{1,5}[.)]))(\\s+|\\t)")

    fun of(line: LineElement): Marker? {
        val text = line.text
        val bullet = BULLET.find(text)
        val number = if (bullet == null) NUMBER.find(text) else null
        val match = bullet ?: number ?: return null
        if (match.value.length >= text.length) return null
        val core = match.value.count { !it.isWhitespace() }
        return Marker(bullet?.groupValues?.get(1), match.value.length, textStart(line, core))
    }

    /** x of the first glyph after the marker's [core] non-space characters. */
    private fun textStart(line: LineElement, core: Int): Float {
        var consumed = 0
        for (g in line.glyphs.filter { it.text.isNotBlank() }.sortedBy { it.left }) {
            if (consumed >= core) return g.left
            consumed += g.text.count { !it.isWhitespace() }
        }
        return line.left
    }
}
