package com.chethan616.clearpdf.presentation.common

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.utils.DocKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** How a document kind looks in the PDF Office lists: icon and its tint, tile colour, badge colour and text. */
data class KindStyle(
    @DrawableRes val icon: Int,
    @ColorRes val iconTint: Int,
    @ColorRes val tile: Int,
    @ColorRes val accent: Int,
    val badge: String
)

fun DocKind.style(): KindStyle = when (this) {
    DocKind.Pdf -> KindStyle(R.drawable.ic_ds_file_pdf, R.color.ds_kind_pdf_icon, R.color.ds_kind_pdf_bg, R.color.ds_kind_pdf, "PDF")
    DocKind.Word -> KindStyle(R.drawable.ic_ds_file_word, R.color.ds_kind_word_icon, R.color.ds_kind_word_bg, R.color.ds_kind_word, "Word")
    DocKind.Excel -> KindStyle(R.drawable.ic_ds_file_excel, R.color.ds_kind_excel_icon, R.color.ds_kind_excel_bg, R.color.ds_kind_excel, "Excel")
    DocKind.Ppt -> KindStyle(R.drawable.ic_ds_file_ppt, R.color.ds_kind_ppt_icon, R.color.ds_kind_ppt_bg, R.color.ds_kind_ppt, "PPT")
    DocKind.Hwp -> KindStyle(R.drawable.ic_ds_file_word, R.color.ds_kind_other, R.color.ds_kind_other_bg, R.color.ds_kind_other, "HWP")
    DocKind.Txt -> KindStyle(R.drawable.ic_ds_file_word, R.color.ds_kind_other, R.color.ds_kind_other_bg, R.color.ds_kind_other, "TXT")
    DocKind.Image -> KindStyle(R.drawable.ic_ds_file_word, R.color.ds_kind_other, R.color.ds_kind_other_bg, R.color.ds_kind_other, "IMG")
    DocKind.Other -> KindStyle(R.drawable.ic_ds_file_word, R.color.ds_kind_other, R.color.ds_kind_other_bg, R.color.ds_kind_other, "FILE")
}

/** "2.4 MB", "320 KB"; null when the size is unknown. */
fun formatDocumentSize(context: Context, bytes: Long): String? = when {
    bytes < 0 -> null
    bytes < 1024 -> context.getString(R.string.recents_size_bytes, bytes)
    bytes < 1024 * 1024 -> context.getString(R.string.recents_size_kb, bytes / 1024)
    else -> context.getString(R.string.recents_size_mb, bytes / (1024.0 * 1024.0))
}

/** "Today, 09:15", "Yesterday", "Oct 3, 2026". */
fun formatDocumentDate(context: Context, millis: Long, now: Long = System.currentTimeMillis()): String {
    val day = Calendar.getInstance().apply { timeInMillis = now; truncateToDay() }
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val time = DateFormat.getTimeFormat(context).format(Date(millis))
    return when {
        !then.before(day) -> context.getString(R.string.doc_today, time)
        !then.before(day.apply { add(Calendar.DAY_OF_YEAR, -1) }) -> context.getString(R.string.doc_yesterday)
        else -> SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMdyyyy"), Locale.getDefault())
            .format(Date(millis))
    }
}

/** Row subtitle: "2.4 MB  ·  Today, 09:15" (two spaces around the dot, as in Figma). */
fun LibraryDocument.metaLine(context: Context): String =
    listOfNotNull(formatDocumentSize(context, sizeBytes), formatDocumentDate(context, modifiedAt)).joinToString("  ·  ")

private fun Calendar.truncateToDay() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}
