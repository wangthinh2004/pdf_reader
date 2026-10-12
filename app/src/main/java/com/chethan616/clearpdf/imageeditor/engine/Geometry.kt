package com.chethan616.clearpdf.imageeditor.engine

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import kotlin.math.hypot

/** A 2D point. Editor ops store these normalised (0..1 of the bitmap they act on). */
data class Offset(val x: Float, val y: Float) {
    operator fun plus(o: Offset) = Offset(x + o.x, y + o.y)
    operator fun minus(o: Offset) = Offset(x - o.x, y - o.y)
    operator fun times(k: Float) = Offset(x * k, y * k)
    operator fun div(k: Float) = Offset(x / k, y / k)
    fun getDistance(): Float = hypot(x, y)

    companion object {
        val Zero = Offset(0f, 0f)
    }
}

/** An axis-aligned rectangle (normalised in crop ops). */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Shape masks a crop can be cut to. */
enum class CropMask {
    Rounded, Circle, CutCorner, Hexagon, Heart, Star;

    /** The mask outline filling a [w] × [h] box. */
    fun path(w: Float, h: Float): Path {
        val p = Path()
        when (this) {
            Rounded -> {
                val r = minOf(w, h) * 0.12f
                p.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
            }
            Circle -> p.addOval(RectF(0f, 0f, w, h), Path.Direction.CW)
            CutCorner -> {
                val c = minOf(w, h) * 0.15f
                p.moveTo(c, 0f); p.lineTo(w - c, 0f); p.lineTo(w, c); p.lineTo(w, h - c)
                p.lineTo(w - c, h); p.lineTo(c, h); p.lineTo(0f, h - c); p.lineTo(0f, c); p.close()
            }
            Hexagon -> {
                for (i in 0 until 6) {
                    val a = Math.toRadians((60.0 * i) - 90.0)
                    val x = (w / 2f + w / 2f * Math.cos(a)).toFloat()
                    val y = (h / 2f + h / 2f * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            // Material "favorite" and "star" glyphs on their 24-unit grid, stretched to the box.
            Heart -> p.addPath(scaled(HEART, 2f, 3f, 20f, 18.35f, w, h))
            Star -> p.addPath(scaled(STAR, 2f, 2f, 20f, 19f, w, h))
        }
        return p
    }

    private fun scaled(data: String, x: Float, y: Float, sw: Float, sh: Float, w: Float, h: Float): Path {
        val path = PathParser.createPathFromPathData(data)
        val m = Matrix()
        m.setTranslate(-x, -y)
        m.postScale(w / sw, h / sh)
        path.transform(m)
        return path
    }

    private companion object {
        const val HEART = "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
        const val STAR = "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"
    }
}
