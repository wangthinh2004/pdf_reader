package com.kyant.pdfcore.word

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class PdfToWordTest {

    // ── Helpers: monospaced glyphs, 0.5 em wide, so geometry is easy to reason about ────────────

    private fun text(s: String, x: Float, baseline: Float, size: Float = 10f, bold: Boolean = false, font: String = "Arial"): List<Glyph> {
        val out = ArrayList<Glyph>()
        var cx = x
        val w = size * 0.5f
        for (ch in s) {
            if (ch != ' ') out += Glyph(ch.toString(), cx, baseline, w, size * 0.7f, TextStyle(font, size, bold = bold))
            cx += w
        }
        return out
    }

    /** 90 characters of 10 pt text = 450 pt, from x = 72 to 522: a full line on a 595 pt page. */
    private val full = "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut"

    private fun page(glyphs: List<Glyph>, rulings: List<Ruling> = emptyList(), index: Int = 0) =
        LayoutAnalyzer.analyze(index, RawPage(595f, 842f, glyphs, rulings))

    private fun assemble(vararg pages: PageLayout) = DocumentAssembler("Test").assemble(pages.toList(), emptyMap())

    private fun paragraphs(doc: WordDocument) = doc.blocks.filterIsInstance<Paragraph>()

    // ── Paragraphs ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `wrapped lines join into one paragraph and a gap starts the next`() {
        val glyphs = text(full, 72f, 100f) + text(full, 72f, 112f) + text("short end.", 72f, 124f) +
            text(full, 72f, 160f) + text("second.", 72f, 172f)
        val doc = assemble(page(glyphs))
        val paras = paragraphs(doc)
        assertEquals(2, paras.size)
        assertEquals("$full $full short end.", paras[0].text)
        assertEquals(Align.JUSTIFY, paras[0].align)
        assertTrue(paras[1].spaceBefore > 0f)
    }

    @Test
    fun `short lines stay separate paragraphs`() {
        val glyphs = text("Nguyen Van A", 72f, 100f) + text("12 Le Loi", 72f, 112f) + text(full, 72f, 124f)
        assertEquals(3, paragraphs(assemble(page(glyphs))).size)
    }

    @Test
    fun `larger short lines become headings by size rank`() {
        val glyphs = text("Big title", 72f, 100f, size = 20f) + text("Section", 72f, 140f, size = 14f) +
            text(full, 72f, 170f) + text(full, 72f, 182f) + text(full, 72f, 194f)
        val paras = paragraphs(assemble(page(glyphs)))
        assertEquals(1, paras[0].heading)
        assertEquals(2, paras[1].heading)
        assertEquals(0, paras[2].heading)
    }

    @Test
    fun `bold numbered body-size line is a heading`() {
        val glyphs = text("1.2 Scope of work", 72f, 100f, bold = true) + text(full, 72f, 130f) + text(full, 72f, 142f)
        assertEquals(3, paragraphs(assemble(page(glyphs)))[0].heading)
    }

    @Test
    fun `centered title is centered`() {
        val glyphs = text("REPORT", 282f, 100f) + text(full, 72f, 130f)
        assertEquals(Align.CENTER, paragraphs(assemble(page(glyphs)))[0].align)
    }

    @Test
    fun `bullet items keep their marker as a Word bullet`() {
        val glyphs = text("- first item", 90f, 100f) + text("- second item", 90f, 112f) + text(full, 72f, 140f)
        val paras = paragraphs(assemble(page(glyphs)))
        assertEquals("-", paras[0].bullet)
        assertEquals("first item", paras[0].text)
        assertEquals("second item", paras[1].text)
        assertEquals(null, paras[2].bullet)
    }

    @Test
    fun `a ragged line whose next word would not fit continues the list item`() {
        // The first line stops 60 pt short of the margin, but the next word is wider than that.
        val glyphs = text("- Ve ky thuat: lam chu cac cong nghe lap trinh Android", 132f, 100f) +
            text("(Java/Kotlin/Compose/Coroutines), Google Maps va Firebase.", 145f, 112f) +
            text(full, 72f, 140f)
        val paras = paragraphs(assemble(page(glyphs)))
        assertEquals("-", paras[0].bullet)
        assertTrue(paras[0].text.endsWith("Firebase."))
        assertEquals(Align.LEFT, paras[0].align)
    }

    @Test
    fun `table of contents entries are not headings`() {
        val glyphs = listOf("CHUONG 1. GIOI THIEU 7", "1.1. Su can thiet 7", "1.2. Muc tieu 8", "1.3. Pham vi 9", "1.4. Phuong phap 9")
            .flatMapIndexed { i, t -> text(t, 72f, 100f + i * 14, bold = true) } + text(full, 72f, 200f) + text(full, 72f, 212f)
        assertTrue(paragraphs(assemble(page(glyphs))).all { it.heading == 0 })
    }

    @Test
    fun `hyphenated line break is joined`() {
        val glyphs = text(full.dropLast(3) + " docu-", 72f, 100f) + text("ment ends here.", 72f, 112f)
        assertTrue(paragraphs(assemble(page(glyphs)))[0].text.endsWith("document ends here."))
    }

    @Test
    fun `underline stroke under text marks it underlined`() {
        val glyphs = text("Signed", 72f, 100f) + text(full, 72f, 130f)
        val doc = assemble(page(glyphs, listOf(Ruling(72f, 101f, 102f, 101f))))
        assertTrue(paragraphs(doc)[0].runs.first().style.underline)
    }

    // ── Tables ─────────────────────────────────────────────────────────────────────────────────

    private fun grid(xs: List<Float>, ys: List<Float>, skip: (Ruling) -> Boolean = { false }): List<Ruling> {
        val out = ArrayList<Ruling>()
        for (y in ys) for (k in 0 until xs.size - 1) out += Ruling(xs[k], y, xs[k + 1], y)
        for (x in xs) for (k in 0 until ys.size - 1) out += Ruling(x, ys[k], x, ys[k + 1])
        return out.filterNot(skip)
    }

    @Test
    fun `ruled grid becomes a bordered table with its cell text`() {
        val rulings = grid(listOf(72f, 200f, 400f), listOf(100f, 120f, 140f))
        val glyphs = text("Name", 76f, 114f) + text("Value", 204f, 114f) + text("A", 76f, 134f) + text("42", 204f, 134f)
        val doc = assemble(page(glyphs, rulings))
        val table = doc.blocks.filterIsInstance<Table>().single()
        assertTrue(table.bordered)
        assertEquals(listOf(128f, 200f), table.columnWidths)
        assertEquals("Value", (table.rows[0].cells[1].content.single() as Paragraph).text)
        assertEquals("42", (table.rows[1].cells[1].content.single() as Paragraph).text)
        assertTrue(paragraphs(doc).isEmpty())
    }

    @Test
    fun `missing inner border merges cells`() {
        // Row 0 has no border between its two cells (a header spanning both columns).
        val rulings = grid(listOf(72f, 200f, 400f), listOf(100f, 120f, 140f)) { it.horizontal.not() && it.position == 200f && it.y1 == 100f }
        val glyphs = text("Header", 76f, 114f) + text("A", 76f, 134f) + text("B", 204f, 134f)
        val table = assemble(page(glyphs, rulings)).blocks.filterIsInstance<Table>().single()
        assertEquals(1, table.rows[0].cells.size)
        assertEquals(2, table.rows[0].cells[0].colSpan)
        assertEquals(2, table.rows[1].cells.size)
    }

    @Test
    fun `vertical merge uses restart and continue`() {
        // Column 0 has no border between rows 0 and 1.
        val rulings = grid(listOf(72f, 200f, 400f), listOf(100f, 120f, 140f)) { it.horizontal && it.position == 120f && it.x1 == 72f }
        val glyphs = text("Tall", 76f, 114f) + text("x", 204f, 114f) + text("y", 204f, 134f)
        val table = assemble(page(glyphs, rulings)).blocks.filterIsInstance<Table>().single()
        assertEquals(VMerge.RESTART, table.rows[0].cells[0].vMerge)
        assertEquals(VMerge.CONTINUE, table.rows[1].cells[0].vMerge)
    }

    @Test
    fun `a single framed box is not a table`() {
        val rulings = grid(listOf(72f, 500f), listOf(90f, 130f))
        val glyphs = text("Note inside a box", 80f, 110f)
        val doc = assemble(page(glyphs, rulings))
        assertTrue(doc.blocks.none { it is Table })
        assertEquals("Note inside a box", paragraphs(doc).single().text)
    }

    @Test
    fun `aligned columns without borders become a borderless table`() {
        val glyphs = listOf(100f, 112f, 124f, 136f).flatMapIndexed { i, y ->
            text("Item $i", 72f, y) + text("${i * 3} pcs", 250f, y) + text("${i * 10}.000", 420f, y)
        }
        val table = assemble(page(glyphs)).blocks.filterIsInstance<Table>().single()
        assertFalse(table.bordered)
        assertEquals(3, table.columnWidths.size)
        assertEquals(4, table.rows.size)
        assertEquals("6 pcs", (table.rows[2].cells[1].content.single() as Paragraph).text)
    }

    // ── Columns & running headers ──────────────────────────────────────────────────────────────

    @Test
    fun `two-column page reads the left column first`() {
        val col = "word word word word word word word word word word word" // 55 chars = 275 pt? keep narrow
        val left = (0 until 12).flatMap { text(col.take(40), 72f, 100f + it * 12) }
        val right = (0 until 12).flatMap { text(col.take(40), 312f, 100f + it * 12) }
        val layout = page(left + right)
        assertNotNull(layout.gutter)
        val lines = layout.elements.filterIsInstance<LineElement>()
        assertTrue(lines.take(12).all { it.left < 300f })
        assertTrue(lines.drop(12).all { it.left > 300f })
    }

    @Test
    fun `a two-column name list is a table, not two text columns`() {
        val glyphs = (0 until 12).flatMap { i ->
            text("NGUYEN VAN ${'A' + i}", 72f, 100f + i * 14) + text("VJ503, VJ510", 330f, 100f + i * 14)
        }
        val layout = page(glyphs)
        assertEquals(null, layout.gutter)
        val table = assemble(layout).blocks.filterIsInstance<Table>().single()
        assertEquals(12, table.rows.size)
        assertEquals("VJ503, VJ510", (table.rows[0].cells[1].content.single() as Paragraph).text)
    }

    @Test
    fun `a wrapped cell stays in its row`() {
        val glyphs = text("BUI THAO VAN", 72f, 100f) + text("VJ503", 330f, 100f) +
            text("NGUYEN NGOC HONG", 72f, 130f) + text("VJ504", 330f, 136f) + text("HANH", 72f, 142f) +
            text("LE THI THU", 72f, 172f) + text("VJ505", 330f, 172f) +
            text("TA QUANG DUC", 72f, 202f) + text("VJ506", 330f, 202f)
        val table = assemble(page(glyphs)).blocks.filterIsInstance<Table>().single()
        assertEquals(4, table.rows.size)
        val wrapped = table.rows[1].cells[0].content.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        assertEquals("NGUYEN NGOC HONG HANH", wrapped)
        assertEquals("VJ504", (table.rows[1].cells[1].content.single() as Paragraph).text)
    }

    @Test
    fun `OCR words are always spaced`() {
        val a = Glyph("Chỗ", 72f, 100f, 20f, 8f, TextStyle("", 10f), isWord = true)
        val b = Glyph("ngồi", 93f, 100f, 22f, 8f, TextStyle("", 10f), isWord = true)
        assertEquals("Chỗ ngồi", TextBuilder.runs(listOf(a, b)).single().text)
    }

    @Test
    fun `repeated header and page numbers move to the header and footer`() {
        val pages = (0 until 3).map { i ->
            page(text("ACME Corp report", 72f, 40f) + text(full, 72f, 100f) + text("${i + 1}", 290f, 810f), index = i)
        }
        val doc = assemble(*pages.toTypedArray())
        assertEquals("ACME Corp report", doc.header.single().text)
        assertTrue(doc.footer.single().text.contains(PAGE_FIELD))
        assertTrue(paragraphs(doc).none { it.text.contains("ACME") })
        assertTrue(paragraphs(doc)[1].pageBreakBefore)
    }

    @Test
    fun `a paragraph cut by the page break is joined without a break`() {
        val p1 = page(text(full, 72f, 100f) + text(full, 72f, 112f) + text(full.dropLast(3), 72f, 124f), index = 0)
        val p2 = page(text("continued on the next page.", 72f, 100f) + text(full, 72f, 136f), index = 1)
        val paras = paragraphs(assemble(p1, p2))
        val joined = paras.single { it.text.contains("continued") }
        assertTrue(joined.text.startsWith(full))
        assertFalse(joined.pageBreakBefore)
        // A paragraph that ended with a full stop keeps its break.
        val p3 = page(text("Next page starts fresh.", 72f, 100f), index = 1)
        assertTrue(paragraphs(assemble(page(text("Done.", 72f, 100f)), p3)).last().pageBreakBefore)
    }

    // ── Text ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `combining marks are composed and private-use bullets mapped`() {
        assertEquals("Việt", TextBuilder.clean("Việt"))
        assertEquals("• a", TextBuilder.clean(" a"))
        assertEquals("ab", TextBuilder.clean("a\u0000b"))
    }

    @Test
    fun `font names are reduced to Word family names`() {
        assertEquals("Times New Roman", FontNames.family("ABCDEF+TimesNewRomanPS-BoldItalicMT"))
        assertEquals("Arial", FontNames.family("ArialMT"))
        assertEquals("Open Sans", FontNames.family("OpenSans-Regular"))
        assertEquals("Calibri", FontNames.family("Calibri,Bold"))
    }

    // ── Writer ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `docx has the required parts and well-formed xml`() {
        val rulings = grid(listOf(72f, 200f, 400f), listOf(300f, 320f, 340f))
        val glyphs = text("Tom & Jerry <3", 72f, 100f, size = 18f) + text(full, 72f, 130f) +
            text("- bullet", 90f, 160f) + text("A", 76f, 314f) + text("B", 204f, 334f)
        val doc = assemble(page(glyphs, rulings), page(text(full, 72f, 100f) + text("2", 290f, 810f), index = 1))
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(doc, emptyMap(), it) }.toByteArray()

        val parts = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; parts[e.name] = zip.readBytes() }
        }
        for (name in listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels", "word/numbering.xml", "word/footer1.xml")) {
            assertTrue("missing $name", name in parts)
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, data) ->
            runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(data)) }
                .onFailure { throw AssertionError("$name is not well-formed: ${it.message}") }
        }
        val xml = String(parts.getValue("word/document.xml"))
        assertTrue(xml.contains("Tom &amp; Jerry &lt;3"))
        assertTrue(xml.contains("<w:tbl>"))
        assertTrue(xml.contains("<w:pStyle w:val=\"Heading1\"/>"))
        assertTrue(xml.contains("<w:numPr>"))
        assertTrue(xml.contains("<w:br w:type=\"page\"/>"))
    }
}
