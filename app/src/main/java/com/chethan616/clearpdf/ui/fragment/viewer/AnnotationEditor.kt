package com.chethan616.clearpdf.ui.fragment.viewer

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.StringRes
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.ui.common.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Dialog for typing a text box or sticky note and picking its colour. */
object AnnotationEditor {

    fun show(
        context: Context,
        @StringRes title: Int,
        initialText: String,
        initialColor: Int,
        palette: IntArray,
        onSave: (text: String, color: Int) -> Unit
    ) {
        var color = initialColor
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(24)
            setPadding(pad, context.dp(8), pad, 0)
        }
        val field = LayoutInflater.from(context).inflate(R.layout.view_text_field, column, false) as TextInputLayout
        field.hint = context.getString(R.string.anno_hint)
        val input = field.findViewById<TextInputEditText>(R.id.input)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.maxLines = 6
        input.setText(initialText)
        input.setSelection(initialText.length)
        column.addView(field)

        val swatches = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun refresh() {
            for (i in 0 until swatches.childCount) swatches.getChildAt(i).alpha = if (swatches.getChildAt(i).tag == color) 1f else 0.45f
        }
        palette.forEach { c ->
            swatches.addView(FrameLayout(context).apply {
                setBackgroundResource(R.drawable.bg_pill)
                backgroundTintList = ColorStateList.valueOf(c)
                foreground = context.getDrawable(R.drawable.swatch_ring)
                tag = c
                setOnClickListener { color = c; refresh() }
            }, LinearLayout.LayoutParams(context.dp(26), context.dp(26)).apply { setMargins(0, context.dp(12), context.dp(8), 0) })
        }
        refresh()
        column.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(swatches)
        })

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(column)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.anno_save) { _, _ -> onSave(input.text?.toString().orEmpty(), color) }
            .show()
        input.requestFocus()
    }
}
