package com.chethan616.clearpdf.ui.fragment.viewer

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.ui.common.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Share the document: the original file (for converted Office / text / image documents) or the
 * PDF, optionally password-protected.
 */
object ShareDialog {

    fun show(context: Context, originalExt: String?, onShare: (original: Boolean, password: String?) -> Unit) {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(24)
            setPadding(pad, context.dp(8), pad, 0)
        }
        val group = android.widget.RadioGroup(context)
        val pdfOption = android.widget.RadioButton(context).apply { id = android.view.View.generateViewId(); setText(R.string.viewer_share_pdf) }
        group.addView(pdfOption)
        var originalOption: android.widget.RadioButton? = null
        if (originalExt != null) {
            originalOption = android.widget.RadioButton(context).apply { id = android.view.View.generateViewId(); text = originalExt }
            group.addView(originalOption)
        }
        group.check(pdfOption.id)
        column.addView(android.widget.TextView(context).apply {
            setText(R.string.viewer_share_format)
            textSize = 13f
        })
        column.addView(group)

        val encrypt = MaterialSwitch(context).apply { setText(R.string.viewer_share_encrypt) }
        column.addView(encrypt)
        val field = LayoutInflater.from(context).inflate(R.layout.view_text_field, column, false) as TextInputLayout
        field.hint = context.getString(R.string.viewer_share_password_hint)
        field.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        val input = field.findViewById<TextInputEditText>(R.id.input)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        field.isVisible = false
        column.addView(field)
        encrypt.setOnCheckedChangeListener { _, on -> field.isVisible = on }
        group.setOnCheckedChangeListener { _, id ->
            val pdf = id == pdfOption.id
            encrypt.isVisible = pdf
            field.isVisible = pdf && encrypt.isChecked
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.viewer_share_title)
            .setView(column)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.viewer_share_button) { _, _ ->
                val original = originalOption != null && group.checkedRadioButtonId == originalOption.id
                val password = input.text?.toString()?.takeIf { !original && encrypt.isChecked && it.isNotBlank() }
                onShare(original, password)
            }
            .show()
    }
}
