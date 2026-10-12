package com.chethan616.clearpdf.ui.fragment.viewer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView

/**
 * A vertical [RecyclerView] the whole of which can be pinch-zoomed and panned. Pages scroll as
 * usual; when zoomed in, horizontal drags pan across the magnified page and vertical drags at the
 * start or end of the document pan the remaining magnified area. Double-tap toggles zoom.
 */
class ZoomRecyclerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : RecyclerView(context, attrs) {

    var scale = 1f
        private set
    private var tx = 0f
    private var ty = 0f
    private val maxScale = 5f
    private val matrix = Matrix()
    private val inverse = Matrix()

    /** True while an editing tool owns single-finger drags (pinch still zooms). */
    var panLocked = false

    /** Called whenever the zoom level changes. */
    var onScaleChanged: ((Float) -> Unit)? = null

    /** Tap that no child consumed (toggles the viewer's chrome). */
    var onBackgroundTap: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var multiTouch = false
    private var animator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomTo(scale * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (panLocked) return false
            animateZoom(if (scale > 1.2f) 1f else 2.5f, e.x, e.y)
            return true
        }
    })

    init {
        scaleDetector.isQuickScaleEnabled = false
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val newScale = target.coerceIn(1f, maxScale)
        val k = newScale / scale
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        scale = newScale
        clamp()
        invalidate()
        onScaleChanged?.invoke(scale)
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(scale, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomTo(it.animatedValue as Float, fx, fy) }
            start()
        }
    }

    fun resetZoom() {
        animator?.cancel()
        scale = 1f; tx = 0f; ty = 0f
        invalidate()
        onScaleChanged?.invoke(scale)
    }

    private fun clamp() {
        tx = tx.coerceIn(width - width * scale, 0f)
        ty = ty.coerceIn(height - height * scale, 0f)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { multiTouch = false; lastX = ev.x; lastY = ev.y; animator?.cancel() }
            MotionEvent.ACTION_POINTER_DOWN -> if (!multiTouch) {
                // A second finger turns the gesture into a pinch: whatever a page was doing stops.
                multiTouch = true
                val cancel = transformed(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                stopScroll()
            }
        }
        if (multiTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) multiTouch = false
            return true
        }

        if (ev.actionMasked == MotionEvent.ACTION_MOVE && scale > 1.01f && !panLocked) {
            val dx = ev.x - lastX
            val dy = ev.y - lastY
            tx += dx
            // At either end of the document the list can't scroll, so drags pan the magnified view.
            val dir = if (dy < 0) 1 else -1
            if (!canScrollVertically(dir)) ty += dy
            clamp()
            invalidate()
        }
        lastX = ev.x
        lastY = ev.y

        val t = transformed(ev)
        val handled = super.dispatchTouchEvent(t)
        t.recycle()
        return handled
    }

    private fun transformed(ev: MotionEvent): MotionEvent {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(tx, ty)
        matrix.invert(inverse)
        return MotionEvent.obtain(ev).apply { transform(inverse) }
    }

    /** Maps a point inside the (unscaled) list to on-screen coordinates of this view. */
    fun toScreen(x: Float, y: Float): Pair<Float, Float> = (x * scale + tx) to (y * scale + ty)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clamp()
    }

    override fun performClick(): Boolean {
        super.performClick()
        onBackgroundTap?.invoke()
        return true
    }
}
