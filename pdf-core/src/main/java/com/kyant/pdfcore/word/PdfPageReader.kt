package com.kyant.pdfcore.word

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads one PDF page into a [RawPage] with PdfBox: glyphs with font/size/weight/colour, the
 * axis-aligned strokes that tables are drawn with, and the images with where they are placed.
 * Coordinates come out in points, top-left origin, crop box and /Rotate applied.
 */
internal class PdfPageReader(private val document: PDDocument, private val media: MediaStore) {

    class Result(val page: RawPage, val invisibleGlyphs: List<Glyph>)

    fun read(index: Int): Result {
        val page = document.getPage(index)
        val frame = Frame(page)
        val (visible, invisible) = readText(index, frame)
        val graphics = GraphicsReader(page, frame, media).apply { processPage(page) }
        return Result(RawPage(frame.width, frame.height, visible, graphics.rulings, graphics.images), invisible)
    }

    // ── Text ───────────────────────────────────────────────────────────────────────────────────

    private fun readText(index: Int, frame: Frame): Pair<List<Glyph>, List<Glyph>> {
        val stripper = GlyphStripper()
        stripper.sortByPosition = false
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.getText(document)
        val raw = stripper.glyphs
        if (raw.isEmpty()) return emptyList<Glyph>() to emptyList()
        // Keep the dominant writing direction; rotated margin labels would scramble the lines.
        val dir = raw.groupingBy { it.dir }.eachCount().maxByOrNull { it.value }!!.key
        val kept = raw.filter { it.dir == dir }
        val (visible, invisible) = kept.partition { it.visible }
        return visible.map { it.glyph } to invisible.map { it.glyph }
    }

    private class Captured(val glyph: Glyph, val dir: Int, val visible: Boolean)

    private class GlyphStripper : PDFTextStripper() {
        val glyphs = ArrayList<Captured>()
        private val styles = HashMap<PDFont, FontInfo>()

        init {
            // The text stripper ignores colour operators by default, so every glyph would read black.
            addOperator(SetNonStrokingColorSpace())
            addOperator(SetNonStrokingColor())
            addOperator(SetNonStrokingColorN())
            addOperator(SetNonStrokingDeviceGrayColor())
            addOperator(SetNonStrokingDeviceRGBColor())
            addOperator(SetNonStrokingDeviceCMYKColor())
        }

        override fun processTextPosition(text: TextPosition) {
            val unicode = text.unicode ?: return
            if (unicode.isEmpty()) return
            val state = graphicsState
            val mode = state.textState.renderingMode
            val visible = mode.isFill || mode.isStroke
            val size = text.yScale.takeIf { it in 1f..400f } ?: text.fontSizeInPt.takeIf { it > 0f } ?: return
            val info = styles.getOrPut(text.font) { FontInfo.of(text.font) }
            val color = runCatching { colorOf(state.nonStrokingColor) }.getOrDefault(0)
            val fakeBold = mode == RenderingMode.FILL_STROKE && state.lineWidth > 0f
            val height = text.heightDir.takeIf { it in size * 0.4f..size * 1.2f } ?: size * 0.72f
            val style = TextStyle(info.family, size, info.bold || fakeBold, info.italic, color)
            val glyph = Glyph(unicode, text.xDirAdj, text.yDirAdj, text.widthDirAdj.coerceAtLeast(0.1f), height, style)
            glyphs += Captured(glyph, Math.round(text.dir), visible)
        }

        // Nothing is written; the glyphs are all we want.
        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) = Unit
    }

    // ── Graphics: strokes and images ───────────────────────────────────────────────────────────

    private class GraphicsReader(page: PDPage, private val frame: Frame, private val media: MediaStore) : PDFGraphicsStreamEngine(page) {
        val rulings = ArrayList<Ruling>()
        val images = ArrayList<PlacedImage>()

        private val subpaths = ArrayList<MutableList<PointF>>()
        private var current = PointF()
        private var hasCurve = false

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            subpaths += mutableListOf(PointF(p0.x, p0.y), PointF(p1.x, p1.y), PointF(p2.x, p2.y), PointF(p3.x, p3.y), PointF(p0.x, p0.y))
            current = PointF(p0.x, p0.y)
        }

        override fun moveTo(x: Float, y: Float) {
            subpaths += mutableListOf(PointF(x, y)); current = PointF(x, y)
        }

        override fun lineTo(x: Float, y: Float) {
            if (subpaths.isEmpty()) subpaths += mutableListOf(PointF(current.x, current.y))
            subpaths.last() += PointF(x, y); current = PointF(x, y)
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            hasCurve = true
            moveTo(x3, y3)
        }

        override fun getCurrentPoint(): PointF = current
        override fun closePath() {
            val sp = subpaths.lastOrNull() ?: return
            if (sp.size > 1) sp += PointF(sp.first().x, sp.first().y)
        }

        override fun endPath() = reset()
        override fun clip(windingRule: Path.FillType?) = Unit
        override fun shadingFill(shadingName: COSName?) = Unit

        override fun strokePath() {
            if (!isWhite(graphicsState.strokingColor)) {
                for (sp in subpaths) for (k in 1 until sp.size) addStroke(sp[k - 1], sp[k])
            }
            reset()
        }

        override fun fillPath(windingRule: Path.FillType?) {
            if (!isWhite(graphicsState.nonStrokingColor)) {
                // Thin filled rectangles are how many generators draw table borders and rules.
                for (sp in subpaths) {
                    if (sp.size < 4) continue
                    val pts = sp.map { frame.map(it.x, it.y) }
                    val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
                    val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
                    val w = maxX - minX; val h = maxY - minY
                    if (h <= 2.5f && w >= 3f) rulings += Ruling(minX, (minY + maxY) / 2, maxX, (minY + maxY) / 2)
                    else if (w <= 2.5f && h >= 3f) rulings += Ruling((minX + maxX) / 2, minY, (minX + maxX) / 2, maxY)
                }
            }
            reset()
        }

        override fun fillAndStrokePath(windingRule: Path.FillType?) {
            val saved = subpaths.map { it.toMutableList() }
            fillPath(windingRule)
            subpaths.addAll(saved)
            strokePath()
        }

        private fun addStroke(a: PointF, b: PointF) {
            val p = frame.map(a.x, a.y)
            val q = frame.map(b.x, b.y)
            val dx = abs(p.x - q.x)
            val dy = abs(p.y - q.y)
            if (dy <= 0.8f && dx >= 3f) rulings += Ruling(p.x, (p.y + q.y) / 2, q.x, (p.y + q.y) / 2)
            else if (dx <= 0.8f && dy >= 3f) rulings += Ruling((p.x + q.x) / 2, p.y, (p.x + q.x) / 2, q.y)
        }

        private fun reset() {
            subpaths.clear(); hasCurve = false
        }

        override fun drawImage(pdImage: PDImage) {
            val m = graphicsState.currentTransformationMatrix
            val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (u, v) ->
                frame.map(m.scaleX * u + m.shearX * v + m.translateX, m.shearY * u + m.scaleY * v + m.translateY)
            }
            val left = corners.minOf { it.x }
            val top = corners.minOf { it.y }
            val width = corners.maxOf { it.x } - left
            val height = corners.maxOf { it.y } - top
            if (width < 4f || height < 4f) return
            val id = media.add(pdImage, width, height) ?: return
            images += PlacedImage(id, left, top, width, height)
        }

        private fun isWhite(color: PDColor?): Boolean {
            val rgb = runCatching { color?.let { colorOf(it) } }.getOrNull() ?: return false
            return (rgb shr 16 and 0xFF) > 0xF0 && (rgb shr 8 and 0xFF) > 0xF0 && (rgb and 0xFF) > 0xF0
        }
    }

    /** Device space (PDF units, bottom-left origin) → displayed page (top-left origin, rotated). */
    private class Frame(page: PDPage) {
        private val box = page.cropBox ?: page.mediaBox
        private val rotation = ((page.rotation % 360) + 360) % 360
        private val w = box.width
        private val h = box.height
        val width = if (rotation == 90 || rotation == 270) h else w
        val height = if (rotation == 90 || rotation == 270) w else h

        fun map(x: Float, y: Float): PointF {
            val u = x - box.lowerLeftX
            val v = h - (y - box.lowerLeftY)
            return when (rotation) {
                90 -> PointF(h - v, u)
                180 -> PointF(w - u, h - v)
                270 -> PointF(v, w - u)
                else -> PointF(u, v)
            }
        }
    }
}

/** Font facts PdfBox exposes, reduced to what Word needs. */
internal class FontInfo(val family: String, val bold: Boolean, val italic: Boolean) {
    companion object {
        fun of(font: PDFont?): FontInfo {
            val raw = font?.name ?: font?.fontDescriptor?.fontName ?: ""
            val descriptor = font?.fontDescriptor
            val lower = raw.lowercase()
            val bold = listOf("bold", "black", "heavy", "semibold", "demi").any { it in lower } ||
                descriptor?.isForceBold == true || (descriptor?.fontWeight ?: 0f) >= 600f
            val italic = "italic" in lower || "oblique" in lower || "inclined" in lower ||
                descriptor?.isItalic == true || abs(descriptor?.italicAngle ?: 0f) > 3f
            return FontInfo(FontNames.family(raw), bold, italic)
        }
    }
}

internal object FontNames {
    private val ALIASES = mapOf(
        "helvetica" to "Arial", "arialmt" to "Arial", "arial" to "Arial",
        "times" to "Times New Roman", "timesnewroman" to "Times New Roman", "timesnewromanps" to "Times New Roman", "timesroman" to "Times New Roman",
        "courier" to "Courier New", "couriernew" to "Courier New", "symbol" to "Symbol", "zapfdingbats" to "Wingdings",
        "calibri" to "Calibri", "cambria" to "Cambria", "verdana" to "Verdana", "tahoma" to "Tahoma", "georgia" to "Georgia",
        "segoeui" to "Segoe UI", "roboto" to "Roboto", "opensans" to "Open Sans", "notosans" to "Noto Sans"
    )

    /** "ABCDEF+TimesNewRomanPS-BoldItalicMT" → "Times New Roman". */
    fun family(postScriptName: String): String {
        var name = postScriptName.substringAfter('+')
        name = name.substringBefore('-').substringBefore(',')
        name = name.removeSuffix("PSMT").removeSuffix("MT").removeSuffix("PS")
        for (suffix in listOf("BoldItalic", "BoldOblique", "Bold", "Italic", "Oblique", "Regular", "Medium", "Light", "Semibold", "SemiBold")) {
            if (name.length > suffix.length + 2 && name.endsWith(suffix)) name = name.removeSuffix(suffix)
        }
        if (name.isBlank()) return "Calibri"
        ALIASES[name.lowercase().replace(" ", "")]?.let { return it }
        // CamelCase → spaced words: "OpenSans" → "Open Sans".
        return name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").trim()
    }
}

internal fun colorOf(color: PDColor): Int = color.toRGB() and 0xFFFFFF

/**
 * Pictures for word/media, deduplicated (a logo on every page is stored once) and downsampled to
 * about 220 dpi at the size they're drawn, which keeps both memory and the .docx size sane.
 */
internal class MediaStore {
    val items = LinkedHashMap<Int, Media>()
    private val byObject = HashMap<COSBase, Int>()
    private var nextId = 1

    fun add(image: PDImage, drawnWidthPt: Float, drawnHeightPt: Float): Int? {
        val key = image.cosObject
        byObject[key]?.let { return it }
        val encoded = runCatching { encode(image, drawnWidthPt, drawnHeightPt) }.getOrNull() ?: return null
        val id = nextId++
        items[id] = Media(id, encoded.first, encoded.second, encoded.third.first, encoded.third.second)
        byObject[key] = id
        return id
    }

    fun drop(ids: Collection<Int>) = ids.forEach { items.remove(it) }

    private fun encode(image: PDImage, wPt: Float, hPt: Float): Triple<ByteArray, String, Pair<Int, Int>>? {
        val pxW = image.width
        val pxH = image.height
        if (pxW <= 0 || pxH <= 0) return null
        // Baseline JPEGs in RGB/gray go in as they are: no decode, no generation loss.
        if (image is PDImageXObject && image.suffix == "jpg" && image.softMask == null) {
            val components = runCatching { image.colorSpace.numberOfComponents }.getOrDefault(0)
            if (components == 1 || components == 3) {
                val bytes = image.createInputStream(listOf(COSName.DCT_DECODE.name)).use { it.readBytes() }
                if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return Triple(bytes, "jpeg", pxW to pxH)
            }
        }
        val targetW = max(1f, wPt * 3f)
        val targetH = max(1f, hPt * 3f)
        var sample = max(1, min(pxW / targetW, pxH / targetH).toInt())
        while ((pxW.toLong() / sample) * (pxH.toLong() / sample) > 12_000_000L) sample++
        val bitmap = image.getImage(null, sample) ?: return null
        try {
            val out = ByteArrayOutputStream()
            val png = bitmap.hasAlpha() || image.isStencil
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out)
            return Triple(out.toByteArray(), if (png) "png" else "jpeg", bitmap.width to bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}
