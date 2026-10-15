package com.kyant.pdfcore.word

import kotlin.math.abs

/**
 * Tables drawn with borders: connected horizontal + vertical strokes form a grid; a missing inner
 * border between two grid cells means they are one merged cell (Word's gridSpan / vMerge).
 */
internal object RuledTables {

    /** Strokes this close are the same line; ends this close touch. */
    private const val SNAP = 2f

    /** Grid lines closer than this are one (double borders); no real column is this thin. */
    private const val MERGE_GAP = 5f

    fun find(page: RawPage, glyphs: List<Glyph>): List<TableElement> {
        val h = merge(page.rulings.filter { it.horizontal && it.length >= 4f }, horizontal = true)
        val v = merge(page.rulings.filter { !it.horizontal && it.length >= 4f }, horizontal = false)
        if (h.size < 2 || v.size < 2) return emptyList()

        // Connected components of crossing strokes.
        val all = h + v
        val parent = IntArray(all.size) { it }
        fun root(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in h.indices) for (j in v.indices) {
            if (crosses(h[i], v[j])) parent[root(i)] = root(h.size + j)
        }
        val components = all.indices.groupBy { root(it) }.values

        val tables = ArrayList<TableElement>()
        val usedGlyphs = HashSet<Glyph>()
        for (comp in components) {
            val ch = comp.filter { it < h.size }.map { all[it] }
            val cv = comp.filter { it >= h.size }.map { all[it] }
            if (ch.size < 2 || cv.size < 2) continue
            val table = grid(ch, cv, page, glyphs.filter { it !in usedGlyphs }) ?: continue
            table.cells.forEach { c -> c.lines.forEach { l -> usedGlyphs += l.glyphs } }
            tables += table
        }
        return tables
    }

    /** The page strokes that belong to [table] (so they aren't mistaken for underlines). */
    fun rulingsOf(table: TableElement, rulings: List<Ruling>): List<Ruling> = rulings.filter { r ->
        r.x1 >= table.left - SNAP && r.x2 <= table.right + SNAP && r.y1 >= table.top - SNAP && r.y2 <= table.bottom + SNAP
    }

    private fun crosses(h: Ruling, v: Ruling): Boolean =
        v.position >= h.x1 - SNAP && v.position <= h.x2 + SNAP && h.position >= v.y1 - SNAP && h.position <= v.y2 + SNAP

    /** Joins collinear pieces (a border drawn cell by cell is one stroke per cell). */
    private fun merge(rulings: List<Ruling>, horizontal: Boolean): List<Ruling> {
        val out = ArrayList<Ruling>()
        val byLine = rulings.sortedBy { it.position }.fold(ArrayList<MutableList<Ruling>>()) { acc, r ->
            val last = acc.lastOrNull()
            if (last != null && abs(last.first().position - r.position) <= 1f) last += r else acc += mutableListOf(r)
            acc
        }
        for (line in byLine) {
            val pos = line.map { it.position }.average().toFloat()
            val pieces = line.map { if (horizontal) it.x1 to it.x2 else it.y1 to it.y2 }.sortedBy { it.first }
            var (a, b) = pieces.first()
            for ((x, y) in pieces.drop(1)) {
                if (x <= b + SNAP) b = maxOf(b, y) else {
                    out += if (horizontal) Ruling(a, pos, b, pos) else Ruling(pos, a, pos, b); a = x; b = y
                }
            }
            out += if (horizontal) Ruling(a, pos, b, pos) else Ruling(pos, a, pos, b)
        }
        return out
    }

    /**
     * Grid lines from stroke positions. Tables drawn cell by cell (CSS `border-collapse: separate`,
     * some exporters) put two strokes a few points apart between neighbours; they are one line, or
     * the table grows hair-thin columns that slice words in half.
     */
    private fun cluster(values: List<Float>): List<Float> {
        val groups = ArrayList<MutableList<Float>>()
        for (x in values.sorted()) {
            val last = groups.lastOrNull()
            if (last != null && x - last.last() <= MERGE_GAP) last += x else groups += mutableListOf(x)
        }
        return groups.map { g -> if (g.size == 1) g[0] else (g.first() + g.last()) / 2 }
    }

    private fun grid(h: List<Ruling>, v: List<Ruling>, page: RawPage, glyphs: List<Glyph>): TableElement? {
        val xs = cluster(v.map { it.position } + h.minOf { it.x1 } + h.maxOf { it.x2 })
        val ys = cluster(h.map { it.position } + v.minOf { it.y1 } + v.maxOf { it.y2 })
        val cols = xs.size - 1
        val rows = ys.size - 1
        if (cols < 1 || rows < 1) return null
        val width = xs.last() - xs.first()
        val height = ys.last() - ys.first()
        if (width < 20f || height < 8f) return null
        // One box is a frame, not a table; a single column of boxes is only a table when it's small
        // (a page border with a rule across it would otherwise swallow the whole page).
        if (cols == 1 && (rows < 2 || width * height > page.width * page.height * 0.4f)) return null

        fun vBorder(x: Float, y: Float) = v.any { abs(it.position - x) <= MERGE_GAP && y >= it.y1 - SNAP && y <= it.y2 + SNAP }
        fun hBorder(y: Float, x: Float) = h.any { abs(it.position - y) <= MERGE_GAP && x >= it.x1 - SNAP && x <= it.x2 + SNAP }

        val owner = Array(rows) { IntArray(cols) { -1 } }
        class Span(val r: Int, val c: Int, val r2: Int, val c2: Int)
        val spans = ArrayList<Span>()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (owner[r][c] >= 0) continue
            val midY = (ys[r] + ys[r + 1]) / 2
            var c2 = c
            while (c2 + 1 < cols && owner[r][c2 + 1] < 0 && !vBorder(xs[c2 + 1], midY)) c2++
            var r2 = r
            while (r2 + 1 < rows && (c..c2).all { cc -> owner[r2 + 1][cc] < 0 && !hBorder(ys[r2 + 1], (xs[cc] + xs[cc + 1]) / 2) }) r2++
            for (rr in r..r2) for (cc in c..c2) owner[rr][cc] = spans.size
            spans += Span(r, c, r2, c2)
        }
        if (spans.size < 2) return null

        val buckets = Array(spans.size) { ArrayList<Glyph>() }
        for (g in glyphs) {
            val x = g.centerX
            val y = g.centerY
            if (x < xs.first() - 1 || x > xs.last() + 1 || y < ys.first() - 1 || y > ys.last() + 1) continue
            val c = (xs.indexOfLast { it <= x }).coerceIn(0, cols - 1)
            val r = (ys.indexOfLast { it <= y }).coerceIn(0, rows - 1)
            buckets[owner[r][c]] += g
        }
        val images = page.images.groupBy { img ->
            val x = (img.left + img.right) / 2
            val y = (img.top + img.bottom) / 2
            if (x < xs.first() || x > xs.last() || y < ys.first() || y > ys.last()) -1
            else owner[(ys.indexOfLast { it <= y }).coerceIn(0, rows - 1)][(xs.indexOfLast { it <= x }).coerceIn(0, cols - 1)]
        }
        val cells = spans.mapIndexed { i, s ->
            TableCellLayout(s.r, s.c, s.r2 - s.r + 1, s.c2 - s.c + 1, LayoutAnalyzer.buildLines(buckets[i]), images[i].orEmpty())
        }
        return TableElement(xs, ys, cells, bordered = true)
    }
}
