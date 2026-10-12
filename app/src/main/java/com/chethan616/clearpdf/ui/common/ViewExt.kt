package com.chethan616.clearpdf.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

fun Context.dp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
fun Context.dp(value: Int): Int = dp(value.toFloat()).toInt()
fun View.dp(value: Int): Int = context.dp(value)

@ColorInt
fun Context.color(@ColorRes res: Int): Int = ContextCompat.getColor(this, res)

fun Context.isDarkTheme(): Boolean =
    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

/** [color] at [alpha] (0..1). */
@ColorInt
fun withAlpha(@ColorInt color: Int, alpha: Float): Int =
    ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).toInt())

/** Soft accent wash used behind icon tiles, badges and tool tiles. Stronger in dark mode. */
@ColorInt
fun Context.accentWash(@ColorInt accent: Int, light: Float = 0.14f, dark: Float = 0.25f): Int =
    withAlpha(accent, if (isDarkTheme()) dark else light)

/** Fills a neutral-shape background (bg_rounded / bg_pill) with [color]. */
fun View.tintBackground(@ColorInt color: Int) {
    backgroundTintList = ColorStateList.valueOf(color)
}

fun ImageView.tint(@ColorInt color: Int) {
    imageTintList = ColorStateList.valueOf(color)
}

fun View.visibleIf(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

/**
 * Edge-to-edge: pads this view by the system-bar insets on the requested sides, on top of the
 * padding it already had in XML.
 */
fun View.applySystemBarPadding(top: Boolean = false, bottom: Boolean = false, imeBottom: Boolean = false) {
    val start = paddingTop
    val end = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottomInset = maxOf(if (bottom) bars.bottom else 0, if (imeBottom) ime.bottom else 0)
        v.updatePadding(
            top = if (top) start + bars.top else start,
            bottom = end + bottomInset
        )
        insets
    }
    if (isAttachedToWindow) ViewCompat.requestApplyInsets(this)
    else addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            ViewCompat.requestApplyInsets(v)
        }
        override fun onViewDetachedFromWindow(v: View) = Unit
    })
}

/** Collects [flow] while the fragment's view is at least STARTED. */
fun <T> Fragment.collectWhileStarted(flow: Flow<T>, block: suspend (T) -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { block(it) }
        }
    }
}

/** Removes every child and returns the group, for rebuilding small static lists. */
fun ViewGroup.clear(): ViewGroup = apply { removeAllViews() }
