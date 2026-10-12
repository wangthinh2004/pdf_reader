package com.chethan616.clearpdf.ui.fragment.viewer

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.SignatureManager
import com.chethan616.clearpdf.databinding.DialogSignatureBinding
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Draw a new signature (saved under a name) or pick a saved one. */
object SignatureDialog {

    private val inks = intArrayOf(
        0xFF141414.toInt(), 0xFF1565C0.toInt(), 0xFF0D3B66.toInt(),
        0xFFB3261E.toInt(), 0xFF1B5E20.toInt(), 0xFF6A1B9A.toInt()
    )

    fun show(context: Context, onSignature: (Bitmap) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val b = DialogSignatureBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // The pad takes every drag; the sheet only closes from outside taps / back.
        dialog.behavior.isDraggable = false

        fun loadSaved() {
            b.saved.removeAllViews()
            val files = runCatching { SignatureManager.listSignatures(context) }.getOrDefault(emptyList())
            b.savedTitle.isVisible = files.isNotEmpty()
            b.savedScroll.isVisible = files.isNotEmpty()
            files.forEach { file ->
                val bmp = SignatureManager.loadSignature(file) ?: return@forEach
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_rounded)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    val pad = context.dp(8)
                    setPadding(pad, pad, pad, pad)
                }
                tile.addView(ImageView(context).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(context.dp(110), context.dp(56)))
                tile.addView(TextView(context).apply {
                    text = SignatureManager.displayName(file)
                    textSize = 11f
                    maxLines = 1
                    setTextColor(0xFF333333.toInt())
                })
                tile.setOnClickListener { dialog.dismiss(); onSignature(bmp) }
                tile.setOnLongClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.sig_delete_title)
                        .setMessage(context.getString(R.string.sig_delete_msg, SignatureManager.displayName(file)))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ -> SignatureManager.deleteSignature(file); loadSaved() }
                        .show()
                    true
                }
                b.saved.addView(tile, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = context.dp(8)
                })
            }
        }
        loadSaved()

        // Ink colours
        val swatches = mutableListOf<View>()
        inks.forEach { ink ->
            val swatch = FrameLayout(context).apply {
                setBackgroundResource(R.drawable.bg_pill)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ink)
                setOnClickListener {
                    b.pad.inkColor = ink
                    swatches.forEach { it.alpha = 0.45f }
                    alpha = 1f
                }
                alpha = if (ink == inks[0]) 1f else 0.45f
            }
            swatches += swatch
            b.colors.addView(swatch, LinearLayout.LayoutParams(context.dp(30), context.dp(30)).apply { marginEnd = context.dp(10) })
        }

        // Ink weights
        val density = context.resources.displayMetrics.density
        listOf(R.string.sig_thin to 2f, R.string.sig_medium to 3f, R.string.sig_thick to 4.5f, R.string.sig_heavy to 6f).forEach { (label, dpWidth) ->
            b.weights.addView((LayoutInflater.from(context).inflate(R.layout.view_filter_chip, b.weights, false) as Chip).apply {
                id = View.generateViewId()
                setText(label)
                isChecked = dpWidth == 3f
                setOnClickListener { b.pad.inkWidth = dpWidth * density }
            })
        }
        b.pad.inkWidth = 3f * density

        ToolUi.setEnabled(b.use, false)
        b.pad.onInkChanged = { has ->
            b.hint.isVisible = !has
            ToolUi.setEnabled(b.use, has)
        }
        b.use.setTextColor(context.color(android.R.color.white))
        b.clear.setOnClickListener { b.pad.clear() }
        b.undo.setOnClickListener { b.pad.undo() }
        b.use.setOnClickListener {
            val bmp = b.pad.export() ?: return@setOnClickListener
            askName(context) { name ->
                runCatching { SignatureManager.saveSignature(context, bmp, name) }
                dialog.dismiss()
                onSignature(bmp)
            }
        }
        dialog.show()
    }

    private fun askName(context: Context, onName: (String) -> Unit) {
        val layout = LayoutInflater.from(context).inflate(R.layout.view_text_field, null) as TextInputLayout
        layout.hint = context.getString(R.string.sig_name_hint)
        val input = layout.findViewById<TextInputEditText>(R.id.input)
        val frame = FrameLayout(context).apply {
            val pad = context.dp(24)
            setPadding(pad, 0, pad, 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.sig_name_title)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sig_name_save, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isNotEmpty()) { dialog.dismiss(); onName(name) }
        }
    }
}
