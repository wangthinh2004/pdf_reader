package com.kyant.pdfcore.word

import kotlin.math.abs

/**
 * Tables without borders: three or more consecutive lines that each break into several segments
 * starting at the same x positions. Becomes a borderless Word table so the columns stay aligned
 * and editable instead of being glued together with tabs.
 */
internal object BorderlessTables {

    private const val MIN_ROWS = 3

    fun detect(elements: List<Element>): List<Element> {
        val out = ArrayList<Element>(elements.size)
        var i = 0
        while (i < elements.size) {
            val run = runFrom(elements, i)
            if (run.size >= MIN_ROWS) {
                val table = build(run)
                if (table != null) {
                    table.column = run.first().column
                    out += table
                    i += run.size
                    continue
                }
            }
            out += elements[i]
            i++
        }
        return out
    }

    private fun runFrom(elements: List<Element>, start: Int): List<LineElement> {
        val first = elements[start] as? LineElement ?: return emptyList()
        if (first.segments.size < 2) return emptyList()
        val run = arrayListOf(first)
        var j = start + 1
        while (j < elements.size) {
            val next = elements[j] as? LineElement ?: break
            val prev = run.last()
            if (next.column != first.column || next.top < prev.top - prev.size) break
            val gap = next.top - prev.bottom
            // Rows can be airy (app-style tables pad each row by ~2 lines), but not a paragraph apart.
            if (gap > prev.size * 3.5f) break
            if (next.segments.size >= 2 || gap <= prev.size * 0.8f) {
                // A full row, or a line hugging the one above (a wrapped cell, a value centred
                // beside a two-line cell).
                run += next; j++; continue
            }
            // A one-column line that starts a row whose other cells sit on the lines right below it.
            val group = arrayListOf(next)
            var k = j + 1
            while (k < elements.size) {
                val more = elements[k] as? LineElement ?: break
                if (more.column != first.column || more.top - group.last().bottom > more.size * 0.8f) break
                group += more; k++
            }
            val lefts = group.flatMap { l -> l.segments.map { it.left } }
            if (lefts.max() - lefts.min() <= next.size) break
            run += group
            j = k
        }
        while (run.size > 1 && run.last().segments.size < 2) run.removeAt(run.lastIndex)
        return if (run.count { it.segments.size >= 2 } >= MIN_ROWS) run else emptyList()
    }

    private fun build(lines: List<LineElement>): TableElement? {
        val full = lines.filter { it.segments.size >= 2 }
        val size = lines.map { it.size }.sorted()[lines.size / 2].coerceAtLeast(4f)
        val tolerance = maxOf(4f, size * 0.8f)
        val lefts = full.flatMap { l -> l.segments.map { it.left } }.sorted()
        val anchors = ArrayList<Float>()
        for (x in lefts) if (anchors.isEmpty() || x - anchors.last() > tolerance) anchors += x
        // Keep only anchors several rows agree on.
        val usage = anchors.map { a -> full.count { l -> l.segments.any { abs(it.left - a) <= tolerance } } }
        val columns = anchors.filterIndexed { i, _ -> usage[i] >= 2 }
        if (columns.size < 2 || columns.size > 10) return null
        val strong = columns.count { a -> full.count { l -> l.segments.any { abs(it.left - a) <= tolerance } } >= full.size * 0.6f }
        if (strong < 2) return null

        fun columnOf(seg: Segment): Int = columns.indexOfLast { it <= seg.left + tolerance }.coerceAtLeast(0)

        // Prose in two columns also aligns; real table cells are short.
        val perColumnLengths = columns.indices.map { c ->
            full.flatMap { l -> l.segments.filter { columnOf(it) == c }.map { it.text.length } }.sorted()
        }
        if (perColumnLengths.all { it.isNotEmpty() && it[it.size / 2] > 40 }) return null

        // Lines → rows: an incomplete line that hugs the row above continues it.
        val rows = ArrayList<MutableList<LineElement>>()
        for (line in lines) {
            val row = rows.lastOrNull()
            val joins = row != null && line.segments.size < columns.size &&
                line.top - row.maxOf { it.bottom } < line.size * 0.8f
            if (joins) row!!.add(line) else rows += mutableListOf(line)
        }

        val xs = ArrayList<Float>()
        xs += minOf(columns.first(), lines.minOf { it.left })
        for (c in 1 until columns.size) xs += columns[c] - 2f
        xs += maxOf(lines.maxOf { it.right } + 2f, columns.last() + size)
        val ys = ArrayList<Float>()
        rows.forEachIndexed { r, row ->
            val top = row.minOf { it.top }
            ys += if (r == 0) top - 2f else (rows[r - 1].maxOf { it.bottom } + top) / 2
        }
        ys += rows.last().maxOf { it.bottom } + 2f

        val cells = ArrayList<TableCellLayout>()
        rows.forEachIndexed { r, row ->
            for (c in columns.indices) {
                val cellLines = row.sortedBy { it.baseline }.mapNotNull { line ->
                    line.segments.filter { columnOf(it) == c }.takeIf { it.isNotEmpty() }?.let(::LineElement)
                }
                cells += TableCellLayout(r, c, 1, 1, cellLines)
            }
        }
        return TableElement(xs, ys, cells, bordered = false)
    }
}
