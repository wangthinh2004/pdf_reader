package com.chethan616.clearpdf.ui.common

import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ItemActionBinding
import com.chethan616.clearpdf.databinding.ViewActionSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

/** One row of an [ActionSheet]. [destructive] actions use the danger colour for their label. */
data class SheetAction(
    @DrawableRes val icon: Int,
    val label: String,
    @ColorInt val tint: Int,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/** A bottom sheet listing actions under a title — the app's context menu. */
object ActionSheet {
    fun show(context: Context, title: String?, actions: List<SheetAction>): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val sheet = ViewActionSheetBinding.inflate(LayoutInflater.from(context))
        sheet.title.text = title
        sheet.title.visibleIf(!title.isNullOrBlank())
        actions.forEach { action ->
            val row = ItemActionBinding.inflate(LayoutInflater.from(context), sheet.actions, true)
            row.icon.setImageResource(action.icon)
            row.icon.tint(action.tint)
            row.iconTile.tintBackground(context.accentWash(action.tint, 0.14f, 0.24f))
            row.label.text = action.label
            if (action.destructive) row.label.setTextColor(context.color(R.color.app_danger))
            row.root.setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
        }
        dialog.setContentView(sheet.root)
        dialog.show()
        return dialog
    }
}
