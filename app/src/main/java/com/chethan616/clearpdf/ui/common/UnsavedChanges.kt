package com.chethan616.clearpdf.ui.common

import android.content.Context
import com.chethan616.clearpdf.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** "Save changes?" before leaving a screen with unsaved work: Save, Discard or Cancel. */
object UnsavedChanges {
    fun confirm(
        context: Context,
        onSave: () -> Unit,
        onDiscard: () -> Unit,
        title: CharSequence = context.getString(R.string.unsaved_title),
        body: CharSequence = context.getString(R.string.unsaved_body),
        saveLabel: CharSequence = context.getString(R.string.save)
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(saveLabel) { _, _ -> onSave() }
            .setNegativeButton(R.string.unsaved_discard) { _, _ -> onDiscard() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }
}
