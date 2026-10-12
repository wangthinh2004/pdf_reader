package com.chethan616.clearpdf.utils

import java.io.File
import java.io.InputStream
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwpxlib.reader.HWPXReader
import kr.dogfoot.hwpxlib.tool.textextractor.TextMarks

/**
 * Plain-text extraction for Korean Hangul word-processor files: `.hwp` (HWP 5.x, via hwplib) and
 * `.hwpx` (via hwpxlib). Table-cell text is kept inline, in reading order; styling, images and
 * layout are dropped. Pure JVM — no Android types — so it is unit-testable.
 */
object HwpTextExtractor {

    fun extractHwp(input: InputStream): List<String> {
        val file = HWPReader.fromInputStream(input)
        val raw = kr.dogfoot.hwplib.tool.textextractor.TextExtractor.extract(
            file, kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText
        )
        return toParagraphs(raw)
    }

    /**
     * hwpxlib reads the zip container from a [File] only, not from a stream. Namespace awareness is
     * off on purpose: hwpxlib matches elements by qName ("hp:p"), and Android's Expat SAX parser
     * leaves qName empty in namespace-aware mode, so every element would go unrecognised.
     */
    fun extractHwpx(file: File): List<String> {
        val hwpx = HWPXReader.fromFile(file, false)
        val marks = TextMarks().paraSeparatorAnd("\n").lineBreakAnd("\n").tabAnd("\t")
        val raw = kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor.extract(
            hwpx, kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText,
            true, marks
        )
        return toParagraphs(raw)
    }

    /** One entry per line, trimmed; blank runs collapse to a single "" and none lead or trail. */
    internal fun toParagraphs(raw: String): List<String> {
        val out = ArrayList<String>()
        for (line in raw.split('\n')) {
            val t = line.trim()
            if (t.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += t
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out
    }
}
