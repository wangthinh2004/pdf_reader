package com.chethan616.clearpdf.data.repository

import com.chethan616.clearpdf.utils.DocKind
import com.chethan616.clearpdf.utils.docKindOf

/** One MediaStore.Files row, as plain values so the filter is testable without Android. */
data class MediaRow(
    val id: Long,
    val name: String?,
    val size: Long,
    val modifiedSec: Long,
    /** MediaStore RELATIVE_PATH (API 29+), e.g. "Download/Work/". */
    val relativePath: String?,
    /** MediaStore _data absolute path; the only location column before API 29. */
    val dataPath: String?
)

data class DeviceDocCandidate(
    val id: Long,
    val name: String,
    val size: Long,
    val modifiedMs: Long,
    /** Folder relative to the storage root, no trailing slash ("" = root/unknown). */
    val folder: String,
    val kind: DocKind
)

/**
 * Decides which MediaStore rows are documents Home should list. Only the kinds [docKindOf] maps to
 * Pdf/Word/Excel/Ppt/Hwp/Txt are kept — those are what the built-in viewers render without the optional
 * Office engine.
 */
object DeviceDocumentFilter {

    val EXTENSIONS = listOf("pdf", "docx", "doc", "odt", "rtf", "xlsx", "xls", "pptx", "ppt", "hwp", "hwpx", "txt")

    private val listedKinds = setOf(DocKind.Pdf, DocKind.Word, DocKind.Excel, DocKind.Ppt, DocKind.Hwp, DocKind.Txt)
    private val storageRoot = Regex("^/storage/(emulated/\\d+|[^/]+)/")

    fun accept(row: MediaRow): DeviceDocCandidate? {
        // Before Android 10, MediaProvider leaves DISPLAY_NAME null for non-media files.
        val name = (row.name ?: row.dataPath?.substringAfterLast('/'))?.trim().orEmpty()
        if (name.isEmpty() || name.startsWith(".") || row.size <= 0) return null
        val kind = docKindOf(name)
        if (kind !in listedKinds) return null
        val folder = folderOf(row) ?: return null
        return DeviceDocCandidate(row.id, name, row.size, row.modifiedSec * 1000, folder, kind)
    }

    fun select(rows: List<MediaRow>): List<DeviceDocCandidate> =
        rows.mapNotNull(::accept).sortedByDescending { it.modifiedMs }

    /** Folder relative to the storage root, or null when it lies somewhere Home must not list. */
    private fun folderOf(row: MediaRow): String? {
        val folder = row.relativePath?.trim('/')
            ?: row.dataPath?.substringBeforeLast('/', "")?.let { "$it/".replace(storageRoot, "") }?.trim('/')
            ?: ""
        val parts = folder.split('/').filter { it.isNotEmpty() }
        if (parts.any { it.startsWith(".") }) return null
        if (parts.size >= 2 && parts[0].equals("Android", true) &&
            (parts[1].equals("data", true) || parts[1].equals("obb", true))) return null
        return parts.joinToString("/")
    }
}
