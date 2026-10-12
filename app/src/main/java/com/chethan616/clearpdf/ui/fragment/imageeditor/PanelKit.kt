package com.chethan616.clearpdf.ui.fragment.imageeditor

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

/** Small builders for the editor's tool panels: chip rows, labelled sliders, icon buttons. */
class PanelKit(private val ctx: Context, private val container: LinearLayout) {

    private val accent = ctx.color(R.color.accent_blue)

    fun clear() = container.removeAllViews()

    private fun add(v: View, topMargin: Int = 8) {
        container.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (container.childCount > 0) this.topMargin = ctx.dp(topMargin)
        })
    }

    /** A scrolling row of single-choice chips. [selected] is the checked index (or −1). */
    fun chips(labels: List<String>, selected: Int, accentColor: Int = accent, onSelect: (Int) -> Unit): List<Chip> {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = labels.mapIndexed { i, label ->
            Chip(ctx).apply {
                text = label
                isCheckable = true
                isChecked = i == selected
                isCheckedIconVisible = false
                chipStrokeWidth = 0f
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ctx.accentWash(accentColor, 0.18f, 0.3f), ctx.color(R.color.app_surface_variant))
                )
                setTextColor(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accentColor, ctx.color(R.color.app_text))
                ))
                setOnClickListener { onSelect(i) }
            }
        }
        chips.forEach { row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(8) }) }
        add(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        return chips
    }

    /** A label + value line over a slider. Returns a setter to sync the shown value. */
    fun slider(
        label: String,
        from: Float,
        to: Float,
        value: Float,
        format: (Float) -> String = { Math.round(it).toString() },
        onReset: (() -> Unit)? = null,
        onChange: (Float) -> Unit
    ): (Float) -> Unit {
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(ctx.color(R.color.app_text_secondary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.color(R.color.app_text))
            setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(2))
            if (onReset != null) setOnClickListener { onReset() }
        }
        head.addView(valueText)
        column.addView(head)
        var dragging = false
        val slider = Slider(ctx).apply {
            valueFrom = from
            valueTo = to
            this.value = value.coerceIn(from, to)
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            addOnChangeListener { _, v, fromUser -> if (fromUser) { valueText.text = format(v); onChange(v) } }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) { dragging = true }
                override fun onStopTrackingTouch(s: Slider) { dragging = false }
            })
        }
        column.addView(slider)
        valueText.text = format(value)
        add(column)
        return { v ->
            valueText.text = format(v)
            if (!dragging && slider.value != v.coerceIn(from, to)) slider.value = v.coerceIn(from, to)
        }
    }

    /** A row of icon buttons; returns them so callers can mark the active one. */
    fun iconRow(items: List<Triple<Int, String, () -> Unit>>, scroll: Boolean = false): List<MaterialButton> {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val buttons = items.map { (icon, desc, action) ->
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                setIconResource(icon)
                contentDescription = desc
                tooltipText = desc
                iconTint = ColorStateList.valueOf(ctx.color(R.color.app_text))
                setOnClickListener { action() }
            }
        }
        buttons.forEach { row.addView(it, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)).apply { if (!scroll) weight = 1f }) }
        add(if (scroll) HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) } else row)
        return buttons
    }

    fun mark(button: MaterialButton, active: Boolean, accentColor: Int = accent) {
        button.iconTint = ColorStateList.valueOf(if (active) accentColor else ctx.color(R.color.app_text))
        button.backgroundTintList = ColorStateList.valueOf(if (active) ctx.accentWash(accentColor, 0.16f, 0.26f) else android.graphics.Color.TRANSPARENT)
    }

    fun button(text: String, @DrawableRes icon: Int? = null, primary: Boolean = true, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(ctx, null, if (primary) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            if (icon != null) setIconResource(icon)
            if (primary) backgroundTintList = ColorStateList.valueOf(accent)
            setOnClickListener { onClick() }
        }
        add(b)
        return b
    }

    fun text(text: String, sizeSp: Float = 12f): TextView {
        val t = TextView(ctx).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(ctx.color(R.color.app_text_secondary))
        }
        add(t)
        return t
    }

    fun view(v: View, topMargin: Int = 8) = add(v, topMargin)

    companion object {
        /** Picks a colour from a palette dialog; [clearLabel] adds a "none" option returning 0. */
        fun pickColor(ctx: Context, title: String, clearLabel: String? = null, onPick: (Int) -> Unit) {
            val colors = intArrayOf(
                0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt(), 0xFF757575.toInt(), 0xFF000000.toInt(), 0xFFFF453A.toInt(),
                0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(), 0xFF00C7BE.toInt(), 0xFF64D2FF.toInt(),
                0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFFA2845E.toInt(),
                0xFF8B0000.toInt(), 0xFF006400.toInt(), 0xFF00008B.toInt(), 0xFFFFC0CB.toInt(), 0xFFFFE4B5.toInt()
            )
            val grid = GridLayout(ctx).apply {
                columnCount = 5
                val pad = ctx.dp(20)
                setPadding(pad, ctx.dp(8), pad, 0)
            }
            var dialog: androidx.appcompat.app.AlertDialog? = null
            colors.forEach { c ->
                grid.addView(FrameLayout(ctx).apply {
                    setBackgroundResource(R.drawable.bg_pill)
                    backgroundTintList = ColorStateList.valueOf(c)
                    foreground = ctx.getDrawable(R.drawable.swatch_ring)
                    setOnClickListener { onPick(c); dialog?.dismiss() }
                }, GridLayout.LayoutParams().apply {
                    width = ctx.dp(40); height = ctx.dp(40)
                    setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
                })
            }
            val builder = MaterialAlertDialogBuilder(ctx).setTitle(title).setView(grid).setNegativeButton(R.string.cancel, null)
            if (clearLabel != null) builder.setNeutralButton(clearLabel) { _, _ -> onPick(0) }
            dialog = builder.show()
        }
    }
}
