package com.chethan616.clearpdf.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.os.bundleOf
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.utils.DocKind
import com.chethan616.clearpdf.utils.docKindOf

/** Navigation shared by every screen: opening documents by kind and stepping into tools. */
object AppNavigator {

    const val ARG_URI = "uri"

    /** Every document type the "Open" picker accepts. */
    val OPEN_DOC_MIMES = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",        // xlsx
        "application/vnd.ms-excel",                                                  // xls
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",   // docx
        "application/msword",                                                        // doc
        "application/vnd.openxmlformats-officedocument.presentationml.presentation", // pptx
        "application/vnd.ms-powerpoint",                                             // ppt
        "application/x-hwp",
        "application/haansofthwp",
        "application/vnd.hancom.hwp",
        "application/vnd.hancom.hwpx",
        "application/haansofthwpx",
        "text/*",
        "image/*"
    )

    private val slideOptions: NavOptions = NavOptions.Builder()
        .setLaunchSingleTop(true)
        .setEnterAnim(R.anim.nav_enter)
        .setExitAnim(R.anim.nav_exit)
        .setPopEnterAnim(R.anim.nav_pop_enter)
        .setPopExitAnim(R.anim.nav_pop_exit)
        .build()

    /** Opens a tool (or any destination without arguments). */
    fun open(nav: NavController, destinationId: Int) {
        runCatching { nav.navigate(destinationId, null, slideOptions) }
    }

    fun openPdfViewer(nav: NavController, uri: Uri? = null) {
        runCatching { nav.navigate(R.id.pdf_viewer, bundleOf(ARG_URI to uri?.toString()), slideOptions) }
    }

    /**
     * Opens [uri] in the viewer that fits its kind: spreadsheets → grid viewer, images → image
     * editor, everything else → the PDF viewer (which converts Office/text sources to PDF).
     * [knownName] skips the provider query when the caller already knows the file name (recents).
     */
    fun openDocument(nav: NavController, context: Context, uri: Uri, knownName: String? = null) {
        val name = knownName?.ifBlank { null } ?: queryDisplayName(context, uri)
        val dest = when (docKindOf(name)) {
            DocKind.Excel -> R.id.spreadsheet
            DocKind.Image -> R.id.image_editor
            else -> R.id.pdf_viewer
        }
        runCatching { nav.navigate(dest, bundleOf(ARG_URI to uri.toString()), slideOptions) }
    }

    fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i != -1 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /** Parses a uri argument, tolerating values that are still percent-encoded. */
    fun parseUriArg(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        val parsed = Uri.parse(raw)
        return if (parsed.scheme.isNullOrEmpty() && raw.contains('%')) Uri.parse(Uri.decode(raw)) else parsed
    }
}
