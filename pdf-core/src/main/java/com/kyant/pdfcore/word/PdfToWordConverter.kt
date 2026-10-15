package com.kyant.pdfcore.word

import android.content.Context
import android.net.Uri
import com.kyant.pdfcore.internal.PdfBox
import com.kyant.pdfcore.security.PdfSecurityService
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.Closeable
import java.io.OutputStream

/** An OCR word with its box normalized to the displayed page (0..1, top-left origin). */
class RecognizedWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

class WordConversionSummary(val pages: Int, val recognizedPages: Int, val tables: Int, val pictures: Int)

/**
 * PDF → .docx, on device. Page by page so the caller can report progress and run OCR (which lives
 * in another module and is asynchronous) on the pages that have no text layer:
 *
 * ```
 * PdfToWordConverter.open(context, uri, title).use { s ->
 *     for (i in 0 until s.pageCount) if (s.readPage(i)) s.supplyRecognizedText(i, ocr(i))
 *     s.write(output)
 * }
 * ```
 */
object PdfToWordConverter {

    /** @throws PdfSecurityService.PasswordRequiredException for a password-protected file. */
    fun open(context: Context, uri: Uri, title: String): Session {
        PdfBox.ensureInitialized(context)
        val document = try {
            context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
        } catch (e: InvalidPasswordException) {
            throw PdfSecurityService.PasswordRequiredException()
        } ?: throw IllegalArgumentException("Cannot open $uri")
        return Session(document, title)
    }

    class Session internal constructor(private val document: PDDocument, private val title: String) : Closeable {
        val pageCount: Int = document.numberOfPages

        private val media = MediaStore()
        private val reader = PdfPageReader(document, media)
        private val layouts = arrayOfNulls<PageLayout>(pageCount)
        private val scans = HashMap<Int, RawPage>()

        /**
         * Reads and lays out page [index]. Returns true when the page is a scan with no usable
         * text layer: the caller should then OCR it and hand the words to [supplyRecognizedText].
         */
        fun readPage(index: Int): Boolean {
            val result = reader.read(index)
            val page = result.page
            val visibleChars = page.glyphs.sumOf { g -> g.text.count { !it.isWhitespace() } }
            val invisibleChars = result.invisibleGlyphs.sumOf { g -> g.text.count { !it.isWhitespace() } }
            val coverImages = page.images.filter { it.width * it.height > page.width * page.height * 0.5f }

            // A searchable scan: the invisible OCR layer is the text, the page photo is not content.
            if (visibleChars < 10 && invisibleChars >= 10) {
                val text = RawPage(page.width, page.height, result.invisibleGlyphs, page.rulings, page.images - coverImages.toSet())
                layouts[index] = LayoutAnalyzer.analyze(index, text, recognized = true)
                return false
            }
            if (visibleChars < 10 && coverImages.isNotEmpty()) {
                scans[index] = page
                layouts[index] = LayoutAnalyzer.analyze(index, page)
                return true
            }
            layouts[index] = LayoutAnalyzer.analyze(index, page)
            return false
        }

        /** OCR words for a page [readPage] flagged; with no words the page keeps its picture. */
        fun supplyRecognizedText(index: Int, words: List<RecognizedWord>) {
            val page = scans.remove(index) ?: return
            if (words.isEmpty()) return
            val boxes = words.filter { it.text.isNotBlank() && it.bottom > it.top && it.right > it.left }
            if (boxes.isEmpty()) return
            // An OCR box spans ascenders to descenders: about 1.15 em, more with stacked Vietnamese
            // marks or all caps. Boxes near the page's typical height are body text at one size;
            // only clearly bigger ones keep their own (titles).
            val heights = boxes.map { (it.bottom - it.top) * page.height }.sorted()
            val typical = heights[heights.size / 2]
            val bodySize = Math.round(typical / 1.25f * 2).coerceAtLeast(10) / 2f
            val glyphs = boxes.map { w ->
                val left = w.left * page.width
                val h = (w.bottom - w.top) * page.height
                val size = if (h <= typical * 1.45f) bodySize else Math.round(h / 1.25f * 2) / 2f
                // Marks stack upwards, so the box bottom is the steadier reference for the baseline.
                val baseline = w.bottom * page.height - minOf(h, typical) * 0.22f
                Glyph(w.text, left, baseline, (w.right - w.left) * page.width, h * 0.78f, TextStyle("", size.coerceIn(5f, 72f)), isWord = true)
            }
            val cover = page.images.filter { it.width * it.height > page.width * page.height * 0.5f }.toSet()
            media.drop(cover.map { it.mediaId })
            layouts[index] = LayoutAnalyzer.analyze(index, RawPage(page.width, page.height, glyphs, emptyList(), page.images - cover), recognized = true)
        }

        fun write(out: OutputStream): WordConversionSummary {
            val pages = layouts.filterNotNull()
            val doc = DocumentAssembler(title).assemble(pages, media.items)
            DocxWriter.write(doc, media.items, out)
            fun count(blocks: List<Block>, predicate: (Block) -> Boolean): Int = blocks.sumOf { b ->
                (if (predicate(b)) 1 else 0) + if (b is Table) b.rows.sumOf { r -> r.cells.sumOf { c -> count(c.content, predicate) } } else 0
            }
            return WordConversionSummary(
                pages = pages.size,
                recognizedPages = pages.count { it.recognized },
                tables = count(doc.blocks) { it is Table },
                pictures = count(doc.blocks) { it is Picture }
            )
        }

        override fun close() = document.close()
    }
}
