package com.chethan616.clearpdf.ui.fragment.tool

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ViewToolFileBinding
import com.chethan616.clearpdf.databinding.ViewToolHeroBinding
import com.chethan616.clearpdf.databinding.ViewToolStatusBinding
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable

/** Binding helpers for the shared tool views (hero card, file card, status card, buttons). */
object ToolUi {

    fun hero(
        b: ViewToolHeroBinding,
        @DrawableRes icon: Int,
        @ColorInt accent: Int,
        title: CharSequence,
        subtitle: CharSequence,
        buttonText: CharSequence?,
        onClick: (() -> Unit)?
    ) {
        val ctx = b.root.context
        b.iconTile.tintBackground(ctx.accentWash(accent, 0.14f, 0.24f))
        b.icon.setImageResource(icon)
        b.icon.tint(accent)
        b.title.text = title
        b.subtitle.text = subtitle
        b.actions.isVisible = buttonText != null
        if (buttonText != null) {
            primaryButton(b.primary, accent)
            b.primary.text = buttonText
            b.primary.setOnClickListener { onClick?.invoke() }
        }
    }

    fun file(
        b: ViewToolFileBinding,
        name: String?,
        detail: String?,
        @ColorInt accent: Int,
        @DrawableRes icon: Int = R.drawable.ic_picture_as_pdf
    ) {
        val ctx = b.root.context
        b.root.isVisible = !name.isNullOrEmpty()
        b.iconTile.tintBackground(ctx.accentWash(accent))
        b.icon.setImageResource(icon)
        b.icon.tint(accent)
        b.name.text = name
        b.detail.text = detail
        b.detail.isVisible = !detail.isNullOrBlank()
    }

    /**
     * Shows [message] as a success or error line. [actionLabel] adds a trailing text button;
     * [onDismiss] adds a close button. A null message hides the card.
     */
    fun status(
        b: ViewToolStatusBinding,
        message: CharSequence?,
        isError: Boolean,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val ctx = b.root.context
        b.root.isVisible = !message.isNullOrBlank()
        if (message.isNullOrBlank()) return
        val tone = ctx.color(if (isError) R.color.app_danger else R.color.app_success)
        b.iconTile.tintBackground(ctx.accentWash(tone, 0.13f, 0.22f))
        b.icon.setImageResource(if (isError) R.drawable.ic_error_outline else R.drawable.ic_check_circle)
        b.icon.tint(tone)
        b.message.text = message
        b.action.isVisible = actionLabel != null
        b.action.text = actionLabel
        b.action.setOnClickListener { onAction?.invoke() }
        b.dismiss.isVisible = onDismiss != null
        b.dismiss.setOnClickListener { onDismiss?.invoke() }
    }

    /** "Open PDF" for a tool's finished output; hidden until there is one. */
    fun openOutput(button: MaterialButton, uri: android.net.Uri?, @ColorInt accent: Int, onOpen: (android.net.Uri) -> Unit) {
        button.isVisible = uri != null
        primaryButton(button, accent)
        button.setOnClickListener { uri?.let(onOpen) }
    }

    /** Vivid accent fill with a white label: the screen's main action. */
    fun primaryButton(button: MaterialButton, @ColorInt accent: Int) {
        button.backgroundTintList = ColorStateList.valueOf(accent)
        button.setTextColor(Color.WHITE)
        button.iconTint = ColorStateList.valueOf(Color.WHITE)
    }

    /**
     * Busy state for an action button: a spinner replaces the icon and taps are ignored. A disabled
     * button keeps its colour at reduced opacity.
     */
    fun setBusy(button: MaterialButton, busy: Boolean, @DrawableRes idleIcon: Int?, label: CharSequence) {
        button.text = label
        button.isClickable = !busy
        if (busy) {
            val spec = CircularProgressIndicatorSpec(button.context, null).apply {
                indicatorColors = intArrayOf(Color.WHITE)
                indicatorSize = button.context.dp(18)
                trackThickness = button.context.dp(2)
            }
            button.icon = IndeterminateDrawable.createCircularDrawable(button.context, spec)
        } else {
            if (idleIcon != null) button.setIconResource(idleIcon) else button.icon = null
        }
    }

    fun setEnabled(button: MaterialButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }
}
