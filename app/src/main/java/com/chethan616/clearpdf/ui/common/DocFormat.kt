package com.chethan616.clearpdf.ui.common

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.utils.DocKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Icon, accent colour and short badge for a document kind. */
data class KindVisual(@DrawableRes val icon: Int, @ColorRes val color: Int, val label: String)

fun kindVisual(kind: DocKind): KindVisual = when (kind) {
    DocKind.Pdf -> KindVisual(R.drawable.ic_picture_as_pdf, R.color.kind_pdf, "PDF")
    DocKind.Word -> KindVisual(R.drawable.ic_description, R.color.kind_word, "DOC")
    DocKind.Excel -> KindVisual(R.drawable.ic_grid_on, R.color.kind_excel, "XLS")
    DocKind.Ppt -> KindVisual(R.drawable.ic_slideshow, R.color.kind_ppt, "PPT")
    DocKind.Hwp -> KindVisual(R.drawable.ic_article, R.color.kind_hwp, "HWP")
    DocKind.Txt -> KindVisual(R.drawable.ic_text_snippet, R.color.kind_txt, "TXT")
    DocKind.Image -> KindVisual(R.drawable.ic_image, R.color.kind_image, "IMG")
    DocKind.Other -> KindVisual(R.drawable.ic_description, R.color.kind_other, "FILE")
}

/** "Just now", "5 min ago", "Yesterday", then a short date. */
fun formatTimestamp(context: Context, ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> context.getString(R.string.recents_just_now)
        diff < 3_600_000 -> context.getString(R.string.recents_minutes_ago, diff / 60_000)
        diff < 86_400_000 -> context.getString(R.string.recents_hours_ago, diff / 3_600_000)
        diff < 172_800_000 -> context.getString(R.string.recents_yesterday)
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
    }
}

fun formatFileSize(context: Context, bytes: Long): String = when {
    bytes < 1024 -> context.getString(R.string.recents_size_bytes, bytes)
    bytes < 1024 * 1024 -> context.getString(R.string.recents_size_kb, bytes / 1024)
    else -> context.getString(R.string.recents_size_mb, bytes / (1024.0 * 1024.0))
}
