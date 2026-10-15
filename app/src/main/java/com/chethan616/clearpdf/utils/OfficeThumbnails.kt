package com.chethan616.clearpdf.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import com.chethan616.clearpdf.utils.xlsx.PaintTheme
import com.chethan616.clearpdf.utils.xlsx.SheetLayout
import com.chethan616.clearpdf.utils.xlsx.XlsxPainter
import com.chethan616.clearpdf.utils.xlsx.XlsxReader
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * First-page previews of Office Open XML files for the Home grid, without an Office engine.
 *
 * Every renderer here is a sketch of the top of the document, sized for a ~120 dp card: a picture
 * of the first slide, the top-left of the first sheet, the opening paragraphs of a Word file.
 */
object OfficeThumbnails {

    /** Only `docProps/thumbnail.jpeg`/`.png`; Office also writes `.emf`/`.wmf`, which Android can't decode. */
    private val EmbeddedNames = setOf("docProps/thumbnail.jpeg", "docProps/thumbnail.jpg", "docProps/thumbnail.png")

    /**
     * The preview the authoring app saved inside the package. PowerPoint writes one by default;
     * Word and Excel only with "Save preview picture". Scaled to [widthPx]; null when absent or so
     * small that scaling it up would look worse than drawing our own.
     */
    fun embedded(source: InputStream, widthPx: Int): Bitmap? = ZipInputStream(source).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name.removePrefix("/") !in EmbeddedNames) continue
            val bytes = zip.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < widthPx * MinEmbeddedRatio) return@use null
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
            if (decoded.width == widthPx) return@use decoded
            val height = (decoded.height * widthPx.toFloat() / decoded.width).toInt().coerceAtLeast(1)
            return@use Bitmap.createScaledBitmap(decoded, widthPx, height, true).also { decoded.recycle() }
        }
        null
    }

    fun pptx(bytes: ByteArray, widthPx: Int): Bitmap? = PptxRenderer.renderFirstSlide(bytes, widthPx)

    /** The first visible sheet from A1, scaled so about [XlsxColumns] columns span the width. */
    fun xlsx(source: InputStream, widthPx: Int): Bitmap? {
        val wb = XlsxReader.read(source)
        val sheet = wb.sheets.firstOrNull { !it.hidden && it.maxRow >= 0 } ?: return null
        val layout = SheetLayout(sheet, extraRows = 0, extraCols = 0)
        val usedCols = (sheet.maxCol + 1).coerceIn(1, layout.nCols)
        val usedRows = (sheet.maxRow + 1).coerceIn(1, layout.nRows)
        val shownCols = usedCols.coerceAtMost(XlsxColumns)
        // Never blow a two-column sheet up past its natural size: it would just be two huge cells.
        val scale = (widthPx / layout.colX[shownCols]).coerceAtMost(widthPx / XlsxMinWidthPx)
        val heightPx = (widthPx * 0.75f).toInt()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val painter = XlsxPainter(wb, PaintTheme(false, Color.WHITE, Color.BLACK, 0xFFE3E5EA.toInt()))
        var y = 0f
        var r = 0
        while (r < usedRows && y < heightPx) {
            val h = layout.rowH(r) * scale
            if (h > 0f) {
                canvas.save()
                canvas.translate(0f, y)
                painter.drawRow(canvas, layout, r, scale, 0f, widthPx.toFloat(), freeze = false) { m -> r == m.r1 }
                canvas.restore()
            }
            y += h
            r++
        }
        return bitmap
    }

    /**
     * The opening paragraphs of `word/document.xml` set on an A4-shaped page: headings and titles
     * bold and larger, everything else body text. Tables, images and real fonts are left out.
     */
    fun docx(source: InputStream, widthPx: Int): Bitmap? {
        val paragraphs = ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.removePrefix("/") == "word/document.xml") return@use readParagraphs(zip)
            }
            null
        } ?: return null

        val heightPx = (widthPx * 1.414f).toInt()
        val margin = widthPx * 0.09f
        val textWidth = (widthPx - 2 * margin).toInt()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val body = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3A3D45.toInt()
            textSize = widthPx / 30f
        }
        val heading = TextPaint(body).apply {
            color = 0xFF14161B.toInt()
            textSize = widthPx / 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        var y = margin
        for (p in paragraphs) {
            val paint = if (p.heading) heading else body
            val layout = StaticLayout.Builder.obtain(p.text, 0, p.text.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build()
            canvas.save()
            canvas.translate(margin, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + body.textSize * 0.6f
            if (y > heightPx) break
        }
        return bitmap
    }

    private class Paragraph(val text: String, val heading: Boolean)

    /** Up to [DocxParagraphs] non-blank paragraphs, streamed so a long document stops early. */
    private fun readParagraphs(stream: InputStream): List<Paragraph> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, "UTF-8")
        }
        val out = ArrayList<Paragraph>()
        val text = StringBuilder()
        var heading = false
        var inText = false
        while (out.size < DocxParagraphs) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:p" -> { text.setLength(0); heading = false }
                    "w:pStyle" -> parser.getAttributeValue(null, "w:val")?.lowercase()?.let {
                        heading = it.startsWith("heading") || it.startsWith("title") || it.startsWith("subtitle")
                    }
                    "w:t" -> inText = true
                    "w:tab" -> text.append(' ')
                    "w:br" -> text.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:t" -> inText = false
                    "w:p" -> if (text.isNotBlank()) out += Paragraph(text.toString().trim(), heading)
                }
            }
        }
        return out
    }

    /** PowerPoint saves 256 px previews; a 122 dp card is ~370 px on a 3x screen, which still looks fine. */
    private const val MinEmbeddedRatio = 0.6f
    private const val XlsxColumns = 6
    /** Excel px a thumbnail always shows at least, so a narrow sheet stays at a readable scale. */
    private const val XlsxMinWidthPx = 380f
    private const val DocxParagraphs = 24
}
