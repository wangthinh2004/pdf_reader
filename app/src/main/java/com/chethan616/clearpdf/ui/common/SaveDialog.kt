package com.chethan616.clearpdf.ui.common

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.SaveLocationManager
import com.chethan616.clearpdf.databinding.DialogSaveBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "Save document" dialog: file name plus an optional one-off save folder. Create it as a property
 * of the Fragment (it registers a folder picker, which must happen before the Fragment starts).
 */
class SaveDialog(private val fragment: Fragment) {

    private var binding: DialogSaveBinding? = null
    private var locationUri: Uri? = null

    private val folderPicker = fragment.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = fragment.requireContext()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        locationUri = uri
        binding?.folderName?.text = uri.lastPathSegment?.replace("primary:", "") ?: ctx.getString(R.string.selected_folder)
    }

    /** [onSave] receives the file name (".pdf" appended when missing) and the chosen folder, if any. */
    fun show(initialFileName: String, onSave: (fileName: String, locationUri: Uri?) -> Unit) {
        val ctx = fragment.requireContext()
        val b = DialogSaveBinding.inflate(fragment.layoutInflater)
        binding = b
        locationUri = SaveLocationManager.getSaveUri(ctx)
        b.name.setText(initialFileName)
        b.name.setSelection(initialFileName.length)
        b.folderName.text = SaveLocationManager.getSavePathDisplay(ctx)
        b.folderIconTile.tintBackground(ctx.accentWash(ctx.color(R.color.accent_indigo), 0.18f, 0.26f))
        b.folder.setOnClickListener { folderPicker.launch(null) }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.save_document)
            .setView(b.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = b.name.text?.toString().orEmpty().ifBlank { "Document" }
                    .let { if (!it.lowercase().endsWith(".pdf")) "$it.pdf" else it }
                onSave(name, locationUri)
            }
            .create()
        dialog.setOnDismissListener { binding = null }
        dialog.show()
    }
}
