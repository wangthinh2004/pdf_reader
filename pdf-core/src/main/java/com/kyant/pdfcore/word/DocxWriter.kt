package com.kyant.pdfcore.word

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Writes a [WordDocument] as a .docx (WordprocessingML in a zip). Hand-written rather than via POI:
 * the parts needed are small and fixed, and POI's OOXML half would add tens of megabytes.
 *
 * Word validates element *order* inside pPr/rPr/tblPr/tcPr against the schema and calls the file
 * corrupt otherwise, so every property block below is emitted in schema order.
 */
object DocxWriter {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val EMU_PER_PT = 12700
    private const val PAGE_BREAK_RUN = """<w:r><w:br w:type="page"/></w:r>"""

    fun write(doc: WordDocument, media: Map<Int, Media>, out: OutputStream) {
        val used = LinkedHashMap<Int, Media>()
        fun collect(blocks: List<Block>) {
            for (b in blocks) when (b) {
                is Picture -> media[b.mediaId]?.let { used[b.mediaId] = it }
                is Table -> b.rows.forEach { r -> r.cells.forEach { c -> collect(c.content) } }
                is Paragraph -> {}
            }
        }
        collect(doc.blocks)
        val bullets = LinkedHashSet<String>()
        doc.blocks.forEach { b -> collectBullets(b, bullets) }
        val bulletIds = bullets.withIndex().associate { (i, ch) -> ch to i + 1 }

        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(used.values, doc))
            put("_rels/.rels", rootRels())
            put("docProps/core.xml", coreProps(doc.title))
            put("docProps/app.xml", appProps())
            put("word/_rels/document.xml.rels", documentRels(used.values, doc, bullets.isNotEmpty()))
            put("word/document.xml", DocumentXml(doc, used, bulletIds).build())
            put("word/styles.xml", styles(doc))
            put("word/settings.xml", settings())
            if (bullets.isNotEmpty()) put("word/numbering.xml", numbering(bullets))
            if (doc.header.isNotEmpty()) put("word/header1.xml", headerFooter("hdr", doc.header))
            if (doc.footer.isNotEmpty()) put("word/footer1.xml", headerFooter("ftr", doc.footer))
            for (m in used.values) {
                zip.putNextEntry(ZipEntry("word/media/image${m.id}.${m.extension}")); zip.write(m.bytes); zip.closeEntry()
            }
        }
    }

    private fun collectBullets(b: Block, into: MutableSet<String>) {
        when (b) {
            is Paragraph -> b.bullet?.let { into += it }
            is Table -> b.rows.forEach { r -> r.cells.forEach { c -> c.content.forEach { collectBullets(it, into) } } }
            is Picture -> {}
        }
    }

    // ── Package parts ──────────────────────────────────────────────────────────────────────────

    private fun contentTypes(media: Collection<Media>, doc: WordDocument): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        media.map { it.extension }.distinct().forEach { ext ->
            append("""<Default Extension="$ext" ContentType="image/${if (ext == "jpg") "jpeg" else ext}"/>""")
        }
        append("""<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""")
        append("""<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""")
        append("""<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>""")
        if (doc.blocks.any { hasBullet(it) }) append("""<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>""")
        if (doc.header.isNotEmpty()) append("""<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>""")
        if (doc.footer.isNotEmpty()) append("""<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>""")
        append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
        append("</Types>")
    }

    private fun hasBullet(b: Block): Boolean = when (b) {
        is Paragraph -> b.bullet != null
        is Table -> b.rows.any { r -> r.cells.any { c -> c.content.any(::hasBullet) } }
        is Picture -> false
    }

    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
        """<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>""" +
        "</Relationships>"

    private fun documentRels(media: Collection<Media>, doc: WordDocument, numbering: Boolean) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL">""")
        append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("""<Relationship Id="rIdSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>""")
        if (numbering) append("""<Relationship Id="rIdNumbering" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>""")
        if (doc.header.isNotEmpty()) append("""<Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>""")
        if (doc.footer.isNotEmpty()) append("""<Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>""")
        for (m in media) {
            append("""<Relationship Id="rIdImg${m.id}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image${m.id}.${m.extension}"/>""")
        }
        append("</Relationships>")
    }

    private fun coreProps(title: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            "<dc:title>${esc(title)}</dc:title>" +
            """<dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified></cp:coreProperties>"""
    }

    private fun appProps() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>PDF Office</Application></Properties>"""

    private fun settings() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="$W">""" +
        """<w:defaultTabStop w:val="720"/><w:characterSpacingControl w:val="doNotCompress"/>""" +
        """<w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>"""

    private fun styles(doc: WordDocument): String {
        val font = esc(doc.bodyStyle.font)
        val size = halfPoints(doc.bodyStyle.size)
        val line = lineValue(doc.lineSpacing)
        fun heading(level: Int) = """<w:style w:type="paragraph" w:styleId="Heading$level"><w:name w:val="heading $level"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="9"/><w:qFormat/>""" +
            """<w:pPr><w:keepNext/><w:keepLines/><w:outlineLvl w:val="${level - 1}"/></w:pPr><w:rPr><w:b/><w:bCs/></w:rPr></w:style>"""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="$font" w:hAnsi="$font" w:eastAsia="$font" w:cs="$font"/><w:sz w:val="$size"/><w:szCs w:val="$size"/><w:lang w:val="en-US" w:eastAsia="en-US" w:bidi="ar-SA"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="$line" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            heading(1) + heading(2) + heading(3) +
            """<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:uiPriority w:val="34"/><w:qFormat/><w:pPr><w:ind w:left="720"/></w:pPr></w:style>""" +
            """<w:style w:type="character" w:default="1" w:styleId="DefaultParagraphFont"><w:name w:val="Default Paragraph Font"/><w:uiPriority w:val="1"/><w:semiHidden/></w:style>""" +
            """<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:uiPriority w:val="99"/><w:semiHidden/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>""" +
            "</w:styles>"
    }

    private fun numbering(bullets: Collection<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering xmlns:w="$W">""")
        bullets.forEachIndexed { i, ch ->
            append("""<w:abstractNum w:abstractNumId="$i"><w:multiLevelType w:val="hybridMultilevel"/>""")
            for (lvl in 0..2) {
                append("""<w:lvl w:ilvl="$lvl"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="${esc(ch)}"/><w:lvlJc w:val="left"/>""")
                append("""<w:pPr><w:ind w:left="${720 * (lvl + 1)}" w:hanging="360"/></w:pPr></w:lvl>""")
            }
            append("</w:abstractNum>")
        }
        bullets.forEachIndexed { i, _ -> append("""<w:num w:numId="${i + 1}"><w:abstractNumId w:val="$i"/></w:num>""") }
        append("</w:numbering>")
    }

    private fun headerFooter(tag: String, paragraphs: List<Paragraph>): String {
        val body = DocumentXml(null, emptyMap(), emptyMap())
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:$tag xmlns:w="$W" xmlns:r="$R">""" +
            paragraphs.joinToString("") { body.paragraph(it) } + "</w:$tag>"
    }

    // ── Body ───────────────────────────────────────────────────────────────────────────────────

    private class DocumentXml(val doc: WordDocument?, val media: Map<Int, Media>, val bulletIds: Map<String, Int>) {
        private var drawingId = 1

        fun build(): String {
            val d = doc!!
            val sb = StringBuilder(64 * 1024)
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            sb.append("""<w:document xmlns:w="$W" xmlns:r="$R" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""")
            var previousWasTable = false
            for (b in d.blocks) {
                when (b) {
                    is Paragraph -> sb.append(paragraph(b))
                    is Picture -> sb.append(picture(b))
                    is Table -> {
                        // A table can't carry the break itself; a paragraph holding only the break does.
                        if (b.pageBreakBefore) sb.append("<w:p>$PAGE_BREAK_RUN</w:p>")
                        // Two tables back to back would merge into one when edited.
                        if (previousWasTable) sb.append("<w:p/>")
                        sb.append(table(b))
                    }
                }
                previousWasTable = b is Table
            }
            // The body must end with a paragraph after a table for Word to stay editable at the end.
            if (previousWasTable || d.blocks.isEmpty()) sb.append("<w:p/>")
            val p = d.page
            sb.append("<w:sectPr>")
            if (d.header.isNotEmpty()) sb.append("""<w:headerReference w:type="default" r:id="rIdHeader"/>""")
            if (d.footer.isNotEmpty()) sb.append("""<w:footerReference w:type="default" r:id="rIdFooter"/>""")
            val landscape = if (p.width > p.height) """ w:orient="landscape"""" else ""
            sb.append("""<w:pgSz w:w="${twips(p.width)}" w:h="${twips(p.height)}"$landscape/>""")
            sb.append("""<w:pgMar w:top="${twips(p.marginTop)}" w:right="${twips(p.marginRight)}" w:bottom="${twips(p.marginBottom)}" w:left="${twips(p.marginLeft)}" w:header="${twips(p.marginTop / 2)}" w:footer="${twips(p.marginBottom / 2)}" w:gutter="0"/>""")
            sb.append("</w:sectPr></w:body></w:document>")
            return sb.toString()
        }

        fun paragraph(p: Paragraph): String = buildString {
            append("<w:p>")
            append(paragraphProperties(p))
            // A page-break run rather than pageBreakBefore: lighter viewers only honour the run.
            if (p.pageBreakBefore) append(PAGE_BREAK_RUN)
            for (r in p.runs) append(run(r))
            append("</w:p>")
        }

        private fun paragraphProperties(p: Paragraph): String {
            val sb = StringBuilder("<w:pPr>")
            // Schema order: pStyle, numPr, spacing, ind, jc.
            when {
                p.heading in 1..3 -> sb.append("""<w:pStyle w:val="Heading${p.heading}"/>""")
                p.bullet != null -> sb.append("""<w:pStyle w:val="ListParagraph"/>""")
            }
            val numId = p.bullet?.let { bulletIds[it] }
            if (numId != null) sb.append("""<w:numPr><w:ilvl w:val="${p.bulletLevel}"/><w:numId w:val="$numId"/></w:numPr>""")
            val before = twips(p.spaceBefore)
            if (before > 0 || p.heading > 0) sb.append("""<w:spacing w:before="$before" w:after="0"/>""")
            val left = twips(p.indentLeft)
            val first = twips(p.firstLineIndent)
            if (left != 0 || first != 0 || numId != null) {
                sb.append("""<w:ind w:left="$left"""")
                when {
                    first > 0 -> sb.append(""" w:firstLine="$first"""")
                    first < 0 -> sb.append(""" w:hanging="${-first}"""")
                    numId != null -> sb.append(""" w:hanging="0"""")
                }
                sb.append("/>")
            }
            when (p.align) {
                Align.CENTER -> sb.append("""<w:jc w:val="center"/>""")
                Align.RIGHT -> sb.append("""<w:jc w:val="right"/>""")
                Align.JUSTIFY -> sb.append("""<w:jc w:val="both"/>""")
                Align.LEFT -> {}
            }
            sb.append("</w:pPr>")
            return if (sb.length == "<w:pPr></w:pPr>".length) "" else sb.toString()
        }

        private fun run(r: Run): String {
            val props = runProperties(r.style)
            val sb = StringBuilder()
            // Tabs, line breaks and the PAGE field are elements, not text.
            val parts = r.text.split(PAGE_FIELD)
            parts.forEachIndexed { i, part ->
                if (part.isNotEmpty()) {
                    sb.append("<w:r>").append(props)
                    val pieces = part.split('\t')
                    pieces.forEachIndexed { k, piece ->
                        if (k > 0) sb.append("<w:tab/>")
                        val lines = piece.split('\n')
                        lines.forEachIndexed { j, text ->
                            if (j > 0) sb.append("<w:br/>")
                            if (text.isNotEmpty()) sb.append("""<w:t xml:space="preserve">""").append(esc(text)).append("</w:t>")
                        }
                    }
                    sb.append("</w:r>")
                }
                if (i < parts.lastIndex) {
                    sb.append("""<w:fldSimple w:instr=" PAGE "><w:r>""").append(props).append("<w:t>1</w:t></w:r></w:fldSimple>")
                }
            }
            return sb.toString()
        }

        private fun runProperties(s: TextStyle): String {
            val sb = StringBuilder("<w:rPr>")
            // Schema order: rFonts, b, bCs, i, iCs, color, sz, szCs, u.
            if (s.font.isNotBlank()) {
                val f = esc(s.font)
                sb.append("""<w:rFonts w:ascii="$f" w:hAnsi="$f" w:eastAsia="$f" w:cs="$f"/>""")
            }
            if (s.bold) sb.append("<w:b/><w:bCs/>")
            if (s.italic) sb.append("<w:i/><w:iCs/>")
            val rgb = s.color and 0xFFFFFF
            val nearBlack = (rgb shr 16 and 0xFF) < 0x30 && (rgb shr 8 and 0xFF) < 0x30 && (rgb and 0xFF) < 0x30
            if (!nearBlack) sb.append("""<w:color w:val="${"%06X".format(rgb)}"/>""")
            if (s.size > 0f) {
                val hp = halfPoints(s.size)
                sb.append("""<w:sz w:val="$hp"/><w:szCs w:val="$hp"/>""")
            }
            if (s.underline) sb.append("""<w:u w:val="single"/>""")
            sb.append("</w:rPr>")
            return sb.toString()
        }

        private fun picture(p: Picture): String {
            val m = media[p.mediaId] ?: return ""
            val id = drawingId++
            val cx = (p.width * EMU_PER_PT).roundToInt().coerceAtLeast(1)
            val cy = (p.height * EMU_PER_PT).roundToInt().coerceAtLeast(1)
            val pPr = StringBuilder("<w:pPr>")
            if (p.indentLeft > 0f) pPr.append("""<w:ind w:left="${twips(p.indentLeft)}"/>""")
            if (p.align == Align.CENTER) pPr.append("""<w:jc w:val="center"/>""")
            pPr.append("</w:pPr>")
            val pageBreak = if (p.pageBreakBefore) PAGE_BREAK_RUN else ""
            return "<w:p>$pPr$pageBreak<w:r><w:drawing>" +
                """<wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$id" name="Picture $id"/>""" +
                """<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>""" +
                """<pic:nvPicPr><pic:cNvPr id="$id" name="image${m.id}.${m.extension}"/><pic:cNvPicPr/></pic:nvPicPr>""" +
                """<pic:blipFill><a:blip r:embed="rIdImg${m.id}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""" +
                """<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>""" +
                "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        }

        private fun table(t: Table): String = buildString {
            append("<w:tbl><w:tblPr>")
            // Schema order: tblW, tblInd, tblBorders, tblLayout, tblCellMar.
            append("""<w:tblW w:w="${t.columnWidths.sumOf { twips(it) }}" w:type="dxa"/>""")
            if (t.indentLeft > 0f) append("""<w:tblInd w:w="${twips(t.indentLeft)}" w:type="dxa"/>""")
            val border = if (t.bordered) """w:val="single" w:sz="4" w:space="0" w:color="000000"""" else """w:val="nil""""
            append("<w:tblBorders>")
            for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) append("<w:$side $border/>")
            append("</w:tblBorders>")
            append("""<w:tblLayout w:type="fixed"/>""")
            append("""<w:tblCellMar><w:left w:w="57" w:type="dxa"/><w:right w:w="57" w:type="dxa"/></w:tblCellMar>""")
            append("</w:tblPr><w:tblGrid>")
            t.columnWidths.forEach { append("""<w:gridCol w:w="${twips(it)}"/>""") }
            append("</w:tblGrid>")
            for (row in t.rows) {
                append("<w:tr>")
                if (row.height > 0f) append("""<w:trPr><w:trHeight w:val="${twips(row.height)}"/></w:trPr>""")
                var col = 0
                for (cell in row.cells) {
                    val width = t.columnWidths.subList(col, (col + cell.colSpan).coerceAtMost(t.columnWidths.size)).sum()
                    append("<w:tc><w:tcPr>")
                    append("""<w:tcW w:w="${twips(width)}" w:type="dxa"/>""")
                    if (cell.colSpan > 1) append("""<w:gridSpan w:val="${cell.colSpan}"/>""")
                    when (cell.vMerge) {
                        VMerge.RESTART -> append("""<w:vMerge w:val="restart"/>""")
                        VMerge.CONTINUE -> append("<w:vMerge/>")
                        VMerge.NONE -> {}
                    }
                    append("</w:tcPr>")
                    // A cell must end with a paragraph.
                    var endsWithParagraph = false
                    for (b in cell.content) {
                        when (b) {
                            is Paragraph -> append(paragraph(b.also { it.pageBreakBefore = false }))
                            is Picture -> append(picture(b))
                            is Table -> {}
                        }
                        endsWithParagraph = b is Paragraph || b is Picture
                    }
                    if (!endsWithParagraph) append("<w:p/>")
                    append("</w:tc>")
                    col += cell.colSpan
                }
                append("</w:tr>")
            }
            append("</w:tbl>")
        }
    }

    // ── Units & escaping ───────────────────────────────────────────────────────────────────────

    private fun twips(pt: Float) = (pt * 20).roundToInt()
    private fun halfPoints(pt: Float) = (pt * 2).roundToInt().coerceIn(2, 3276)

    /** Word's "auto" line spacing is in 240ths of a single line (~1.17 × the font size). */
    private fun lineValue(multiple: Float) = (multiple / 1.17f * 240).roundToInt().coerceIn(240, 480)

    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                Character.isHighSurrogate(ch) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { sb.append(ch).append(s[i + 1]); i++ }
                Character.isSurrogate(ch) -> {}
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
