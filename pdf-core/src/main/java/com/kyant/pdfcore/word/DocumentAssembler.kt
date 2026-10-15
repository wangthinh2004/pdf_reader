package com.kyant.pdfcore.word

import kotlin.math.abs

/**
 * Page layouts → [WordDocument]. Works on the whole document at once because the decisions that
 * matter are document-wide: what the body size is (so what counts as a heading), the leading, the
 * margins, and which margin lines repeat on every page (running headers/footers and page numbers).
 */
class DocumentAssembler(private val title: String) {

    fun assemble(pages: List<PageLayout>, media: Map<Int, Media>): WordDocument {
        val runningLines = runningHeaderFooter(pages)
        val bodies = pages.associateWith { bodyOf(it, runningLines.removed) }

        val allGlyphs = bodies.values.flatMap { e -> e.filterIsInstance<LineElement>().flatMap { it.glyphs } }
        val bodySize = dominantSize(allGlyphs).takeIf { it > 0f } ?: 11f
        val bodyFont = allGlyphs.filter { abs(it.style.size - bodySize) < 0.6f && it.style.font.isNotBlank() }
            .groupingBy { it.style.font }.eachCount().maxByOrNull { it.value }?.key ?: "Calibri"
        val (leading, lineGap) = measureLeading(bodies.values, bodySize)
        val setup = pageSetup(pages, bodies)
        val builder = ParagraphBuilder(leading, lineGap)
        val blocks = ArrayList<Block>()
        val built = ArrayList<ParagraphBuilder.Built>()

        for (page in pages) {
            val elements = bodies.getValue(page)
            val pageBlocks = ArrayList<Block>()
            var i = 0
            var previousBottom: Float? = null
            while (i < elements.size) {
                val e = elements[i]
                when (e) {
                    is LineElement -> {
                        val run = ArrayList<LineElement>()
                        while (i < elements.size && elements[i] is LineElement && elements[i].column == e.column) {
                            run += elements[i] as LineElement; i++
                        }
                        val region = regionOf(e.column, page, setup)
                        val paragraphs = builder.build(run, region, previousBottom)
                        built += paragraphs
                        pageBlocks += paragraphs.map { it.paragraph }
                        previousBottom = run.last().bottom
                        continue
                    }
                    is TableElement -> pageBlocks += table(e, page, setup, builder, media)
                    is ImageElement -> picture(e.image, regionOf(e.column, page, setup), setup, media)?.let { pageBlocks += it }
                }
                previousBottom = e.bottom
                i++
            }
            if (page.index > 0 && pageBlocks.isNotEmpty() && !continueAcrossPages(blocks, pageBlocks, built) && !continueTable(blocks, pageBlocks)) {
                pageBlocks.first().pageBreakBefore = true
            }
            blocks += pageBlocks
        }

        assignHeadings(built, bodySize)
        return WordDocument(
            title = title,
            page = setup,
            blocks = blocks,
            header = runningLines.header.map { lineParagraph(it, setup) },
            footer = runningLines.footer.map { lineParagraph(it, setup) },
            bodyStyle = TextStyle(bodyFont, bodySize),
            lineSpacing = leading
        )
    }

    /**
     * A paragraph the page break cut in two ("…Praesent libero." on one page, "ipsum dolor…" on the
     * next) is joined back into one, with no break: Word paginates it again on its own. Returns
     * true when it joined them (the first block of [pageBlocks] is then consumed).
     */
    private fun continueAcrossPages(blocks: MutableList<Block>, pageBlocks: MutableList<Block>, built: MutableList<ParagraphBuilder.Built>): Boolean {
        val before = blocks.lastOrNull() as? Paragraph ?: return false
        val after = pageBlocks.first() as? Paragraph ?: return false
        val a = built.indexOfFirst { it.paragraph === before }
        val b = built.indexOfFirst { it.paragraph === after }
        if (a < 0 || b < 0) return false
        if (abs(built[a].size - built[b].size) > 0.6f || before.bullet != null || after.bullet != null) return false
        if (after.align == Align.CENTER || after.align == Align.RIGHT) return false
        val end = before.text.trimEnd()
        val start = after.text.trimStart()
        if (end.isEmpty() || start.isEmpty()) return false
        val openSentence = end.last() !in ".!?:;\"”’)…" || end.endsWith(",")
        val continues = start.first().isLowerCase() || end.endsWith("-") || end.endsWith(",")
        if (!openSentence || !continues) return false

        val joiner = if (end.endsWith("-") && start.first().isLowerCase()) null else Run(" ", before.runs.last().style)
        val runs = if (joiner == null) {
            before.runs.dropLast(1) + Run(before.runs.last().text.trimEnd().dropLast(1), before.runs.last().style) + after.runs
        } else before.runs + joiner + after.runs
        val merged = Paragraph(TextBuilder.merge(runs), before.align, before.indentLeft, before.firstLineIndent, before.spaceBefore, bulletLevel = before.bulletLevel)
        merged.pageBreakBefore = before.pageBreakBefore
        blocks[blocks.lastIndex] = merged
        pageBlocks.removeAt(0)
        val first = built[a]
        built[a] = ParagraphBuilder.Built(merged, first.lineCount + built[b].lineCount, first.size, (first.boldShare * first.lineCount + built[b].boldShare * built[b].lineCount) / (first.lineCount + built[b].lineCount), first.top, built[b].bottom)
        built.removeAt(b)
        return true
    }

    /** A table the page break split (same columns on both sides) becomes one table again. */
    private fun continueTable(blocks: MutableList<Block>, pageBlocks: MutableList<Block>): Boolean {
        val before = blocks.lastOrNull() as? Table ?: return false
        val after = pageBlocks.first() as? Table ?: return false
        if (before.bordered != after.bordered || before.columnWidths.size != after.columnWidths.size) return false
        if (before.columnWidths.zip(after.columnWidths).any { (a, b) -> abs(a - b) > 6f }) return false
        val merged = Table(before.columnWidths, before.rows + after.rows, before.bordered, before.indentLeft)
        merged.pageBreakBefore = before.pageBreakBefore
        blocks[blocks.lastIndex] = merged
        pageBlocks.removeAt(0)
        return true
    }

    // ── Document-wide measurements ─────────────────────────────────────────────────────────────

    /** Median baseline distance and inter-line whitespace for body-size lines that follow each other. */
    private fun measureLeading(bodies: Collection<List<Element>>, bodySize: Float): Pair<Float, Float> {
        val ratios = ArrayList<Float>()
        val gaps = ArrayList<Float>()
        for (body in bodies) {
            val lines = body.filterIsInstance<LineElement>()
            for (k in 1 until lines.size) {
                val a = lines[k - 1]
                val b = lines[k]
                if (a.column != b.column || abs(a.size - bodySize) > 0.6f || abs(b.size - bodySize) > 0.6f) continue
                val delta = (b.baseline - a.baseline) / bodySize
                if (delta in 0.9f..1.9f) {
                    ratios += delta
                    gaps += (b.top - a.bottom) / bodySize
                }
            }
        }
        if (ratios.isEmpty()) return 1.2f to 0.2f
        return median(ratios) to median(gaps)
    }

    private fun pageSetup(pages: List<PageLayout>, bodies: Map<PageLayout, List<Element>>): PageSetup {
        val first = pages.firstOrNull()
        val width = first?.width ?: 595f
        val height = first?.height ?: 842f
        val lefts = ArrayList<Float>()
        val rights = ArrayList<Float>()
        val tops = ArrayList<Float>()
        val bottoms = ArrayList<Float>()
        for (p in pages) {
            val content = bodies.getValue(p).filter { it is LineElement || it is TableElement }
            if (content.isEmpty()) continue
            lefts += content.minOf { it.left }
            rights += p.width - content.maxOf { it.right }
            tops += content.minOf { it.top }
            bottoms += p.height - content.maxOf { it.bottom }
        }
        fun margin(values: List<Float>, fallback: Float) = (if (values.isEmpty()) fallback else median(values)).coerceIn(18f, 108f)
        return PageSetup(width, height, margin(lefts, 72f), margin(rights, 72f), margin(tops, 72f), margin(bottoms, 72f))
    }

    private fun regionOf(column: Column, page: PageLayout, setup: PageSetup): ParagraphBuilder.Region {
        val left = setup.marginLeft
        val right = page.width - setup.marginRight
        val gutter = page.gutter
        return when {
            gutter == null || column == Column.FULL -> ParagraphBuilder.Region(left, right)
            column == Column.LEFT -> ParagraphBuilder.Region(left, gutter.start)
            else -> ParagraphBuilder.Region(gutter.endInclusive, right)
        }
    }

    // ── Running headers / footers ──────────────────────────────────────────────────────────────

    private class Running(val header: List<LineElement>, val footer: List<LineElement>, val removed: Set<LineElement>)

    private val pageNumber = Regex("^[-–(\\s]*((page|trang|p\\.)\\s*)?#+(\\s*(/|of|trên|của)\\s*#+)?[-–)\\s]*$", RegexOption.IGNORE_CASE)

    private fun key(line: LineElement) = line.text.replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").trim().lowercase()

    /**
     * Margin lines that repeat on at least half the pages (same text with digits wildcarded, same
     * band) become the Word header/footer; page numbers anywhere in the margins become a PAGE field.
     */
    private fun runningHeaderFooter(pages: List<PageLayout>): Running {
        val counts = HashMap<String, Int>()
        for (p in pages) p.marginLines.map { band(it, p) + key(it) }.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val needed = maxOf(2, (pages.size + 1) / 2)
        val removed = HashSet<LineElement>()
        val header = LinkedHashMap<String, LineElement>()
        val footer = LinkedHashMap<String, LineElement>()
        for (p in pages) for (line in p.marginLines) {
            val k = key(line)
            val bandKey = band(line, p) + k
            val repeated = pages.size >= 2 && (counts[bandKey] ?: 0) >= needed
            if (!repeated && !pageNumber.matches(k)) continue
            removed += line
            val target = if (band(line, p) == "T") header else footer
            target.putIfAbsent(bandKey, line)
        }
        return Running(header.values.toList(), footer.values.toList(), removed)
    }

    private fun band(line: LineElement, page: PageLayout) = if (line.top < page.height / 2) "T" else "B"

    /** Body elements without the lines that went to the running header/footer. */
    private fun bodyOf(page: PageLayout, removed: Set<LineElement>): List<Element> =
        if (removed.isEmpty()) page.elements else page.elements.filter { it !in removed }

    private fun lineParagraph(line: LineElement, setup: PageSetup): Paragraph {
        val isNumber = pageNumber.matches(key(line))
        val runs = line.segments.flatMapIndexed { i, s -> (if (i > 0) listOf(Run("\t", s.runs.first().style)) else emptyList()) + s.runs }
        val withField = if (isNumber) runs.map { r -> Run(r.text.replaceFirst(Regex("\\d+"), PAGE_FIELD), r.style) } else runs
        val center = setup.marginLeft + setup.contentWidth / 2
        val mid = (line.left + line.right) / 2
        val align = when {
            abs(mid - center) < setup.contentWidth * 0.1f -> Align.CENTER
            line.left > center -> Align.RIGHT
            else -> Align.LEFT
        }
        return Paragraph(TextBuilder.merge(withField), align)
    }

    // ── Headings ───────────────────────────────────────────────────────────────────────────────

    private val tocEntry = Regex("[\\s.…·_]\\d{1,3}$")

    private val labelledField = Regex(":\\s*\\S")
    private val sectionNumber = Regex("^((\\d+(\\.\\d+)*\\.?)|([IVXLC]+\\.)|(chương|chapter|phần|part|mục|bài)\\b)", RegexOption.IGNORE_CASE)

    /**
     * Larger-than-body short paragraphs become Heading 1–3 by size rank. Body-size lines that are
     * mostly bold (at least half the characters) and look like section titles (numbered, or ALL
     * CAPS) become Heading 2/3, the common case for documents typed in one size.
     */
    private fun assignHeadings(paragraphs: List<ParagraphBuilder.Built>, bodySize: Float) {
        fun eligible(b: ParagraphBuilder.Built) = b.paragraph.bullet == null && b.lineCount <= 3 &&
            b.paragraph.text.length in 2..200 && b.paragraph.text.any { it.isLetter() }
        // Table-of-contents entries look like headings (bold, numbered) but end in a page number.
        val toc = HashSet<ParagraphBuilder.Built>()
        var k = 0
        while (k < paragraphs.size) {
            var e = k
            while (e < paragraphs.size && tocEntry.containsMatchIn(paragraphs[e].paragraph.text.trimEnd())) e++
            if (e - k >= 4) toc += paragraphs.subList(k, e)
            k = maxOf(e, k + 1)
        }
        fun eligibleHeading(b: ParagraphBuilder.Built) = eligible(b) && b !in toc
        val big = paragraphs.filter { eligibleHeading(it) && it.size >= bodySize * 1.15f && it.size >= bodySize + 1f }
        val sizes = big.map { Math.round(it.size) }.distinct().sortedDescending()
        for (b in big) b.paragraph.heading = (sizes.indexOf(Math.round(b.size)) + 1).coerceAtMost(3)

        for (b in paragraphs) {
            if (b.paragraph.heading > 0 || !eligibleHeading(b) || b.boldShare < 0.5f || b.lineCount > 1) continue
            if (abs(b.size - bodySize) > 1f) continue
            val text = b.paragraph.text.trim()
            val letters = text.filter { it.isLetter() }
            val caps = letters.length >= 3 && letters == letters.uppercase()
            val numbered = sectionNumber.containsMatchIn(text)
            if (!caps && !numbered) continue
            // "MÃ SINH VIÊN: A44691" is a labelled field, not a section title.
            if (labelledField.containsMatchIn(text) || '\t' in text) continue
            if (text.endsWith(".") && text.length > 40) continue
            val depth = Regex("^\\d+(\\.\\d+)+").find(text)?.value?.count { it == '.' } ?: 0
            b.paragraph.heading = if (depth >= 1) 3 else 2
        }
    }

    // ── Tables & pictures ──────────────────────────────────────────────────────────────────────

    private fun table(e: TableElement, page: PageLayout, setup: PageSetup, builder: ParagraphBuilder, media: Map<Int, Media>): Block {
        val region = regionOf(e.column, page, setup)
        val widths = e.xs.zipWithNext { a, b -> b - a }
        val total = widths.sum()
        val scale = if (total > setup.contentWidth) setup.contentWidth / total else 1f
        val rows = e.ys.size - 1
        val cols = e.xs.size - 1
        val owner = Array(rows) { arrayOfNulls<TableCellLayout>(cols) }
        for (c in e.cells) for (r in c.row until c.row + c.rowSpan) for (k in c.col until c.col + c.colSpan) {
            if (r < rows && k < cols) owner[r][k] = c
        }
        val tableRows = (0 until rows).map { r ->
            val cells = ArrayList<TableCell>()
            var c = 0
            while (c < cols) {
                val cell = owner[r][c]
                if (cell == null) { cells += TableCell(1, VMerge.NONE, emptyList()); c++; continue }
                val span = cell.colSpan.coerceAtMost(cols - c)
                if (cell.row < r) {
                    cells += TableCell(span, VMerge.CONTINUE, emptyList())
                } else {
                    val cellRegion = ParagraphBuilder.Region(e.xs[cell.col] + 2f, e.xs[(cell.col + span).coerceAtMost(cols)] - 2f)
                    val content = ArrayList<Block>()
                    content += builder.build(cell.lines, cellRegion, null, inCell = true).map { it.paragraph }
                    cell.images.forEach { img -> picture(img, cellRegion, setup, media, maxWidth = cellRegion.width * scale)?.let { content += it } }
                    cells += TableCell(span, if (cell.rowSpan > 1) VMerge.RESTART else VMerge.NONE, content)
                }
                c += span
            }
            TableRow((e.ys[r + 1] - e.ys[r]) * if (e.bordered) 1f else 0f, cells)
        }
        val indent = ((e.left - region.left) * scale).coerceAtLeast(0f).takeIf { it > 4f } ?: 0f
        return Table(widths.map { it * scale }, tableRows, e.bordered, indent)
    }

    private fun picture(img: PlacedImage, region: ParagraphBuilder.Region, setup: PageSetup, media: Map<Int, Media>, maxWidth: Float = setup.contentWidth): Picture? {
        if (media[img.mediaId] == null || img.width < 4f || img.height < 4f) return null
        val scale = if (img.width > maxWidth) maxWidth / img.width else 1f
        val mid = (img.left + img.right) / 2
        val centered = abs(mid - region.center) < region.width * 0.06f && img.left > region.left + 8f
        val indent = if (centered) 0f else (img.left - region.left).coerceIn(0f, (maxWidth - img.width * scale).coerceAtLeast(0f))
        return Picture(img.mediaId, img.width * scale, img.height * scale, if (centered) Align.CENTER else Align.LEFT, indent.takeIf { it > 4f } ?: 0f)
    }

    private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]
}
