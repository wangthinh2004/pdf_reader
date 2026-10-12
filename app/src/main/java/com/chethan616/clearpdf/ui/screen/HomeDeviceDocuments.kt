package com.chethan616.clearpdf.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.DeviceDocument
import com.chethan616.clearpdf.ui.theme.LiquidGlassColors
import com.chethan616.clearpdf.ui.theme.ToolAccents
import com.chethan616.clearpdf.ui.viewmodel.DeviceDocsState
import com.chethan616.clearpdf.utils.DocKind

// Flat by request: no glass, no backdrop sampling, no blur anywhere in this section — it can hold
// thousands of rows and the glass effects are what made Home lag.

private val CardCorner = 20.dp

private fun cardFill(isDark: Boolean) =
    if (isDark) Color(0xFF1C1C1E).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.92f)

/** The documents a tab + search query leave visible. */
internal fun visibleDeviceDocs(docs: List<DeviceDocument>, tab: DocKind?, query: String): List<DeviceDocument> {
    val q = query.trim()
    return docs.filter { (tab == null || it.kind == tab) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
}

internal fun LazyListScope.deviceDocumentsSection(
    state: DeviceDocsState,
    tab: DocKind?,
    onTabChange: (DocKind?) -> Unit,
    query: String,
    isDark: Boolean,
    onGrantAccess: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (DeviceDocument) -> Unit
) {
    if (state is DeviceDocsState.Unsupported) return
    val docs = if (state is DeviceDocsState.Ready) visibleDeviceDocs(state.docs, tab, query) else emptyList()
    val total = (state as? DeviceDocsState.Ready)?.docs?.size
    val headerOnly = docs.isEmpty()

    item(key = "deviceHeader", contentType = "deviceHeader") {
        val shape = if (headerOnly) RoundedCornerShape(CardCorner)
                    else RoundedCornerShape(topStart = CardCorner, topEnd = CardCorner)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(cardFill(isDark))
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = if (headerOnly) 20.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BasicText(
                if (total != null) stringResource(R.string.home_device_docs_count, total) else stringResource(R.string.home_device_docs),
                style = TextStyle(LiquidGlassColors.text(isDark), 18.sp, FontWeight.Bold)
            )
            when (state) {
                DeviceDocsState.Unsupported -> Unit
                DeviceDocsState.NoAccess -> AccessCard(isDark, onGrantAccess)
                DeviceDocsState.Loading -> CenterMessage(isDark, stringResource(R.string.home_device_scanning), spinner = true)
                DeviceDocsState.Error -> CenterMessage(isDark, stringResource(R.string.home_device_error), onClick = onRetry)
                is DeviceDocsState.Ready -> {
                    KindTabs(tab, onTabChange, isDark)
                    if (docs.isEmpty()) CenterMessage(isDark, stringResource(R.string.home_device_empty))
                }
            }
        }
    }

    items(docs, key = { "dev:" + it.uri }, contentType = { "deviceDoc" }) { doc ->
        val last = doc === docs.last()
        val shape: Shape = if (last) RoundedCornerShape(bottomStart = CardCorner, bottomEnd = CardCorner) else RoundedCornerShape(0.dp)
        DeviceDocRow(doc, isDark, shape, bottomPad = if (last) 12.dp else 0.dp) { onOpen(doc) }
    }
}

@Composable
private fun KindTabs(selected: DocKind?, onSelect: (DocKind?) -> Unit, isDark: Boolean) {
    val tabs = listOf(
        null to R.string.recents_filter_all,
        DocKind.Pdf to R.string.recents_filter_pdf,
        DocKind.Word to R.string.recents_filter_word,
        DocKind.Excel to R.string.recents_filter_excel,
        DocKind.Ppt to R.string.recents_filter_ppt,
        DocKind.Hwp to R.string.recents_filter_hwp,
        DocKind.Txt to R.string.recents_filter_txt
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEach { (kind, label) ->
            val on = kind == selected
            val bg by animateColorAsState(
                if (on) ToolAccents.Open else if (isDark) Color.White.copy(0.10f) else Color.Black.copy(0.06f),
                label = "deviceTab"
            )
            BasicText(
                stringResource(label),
                style = TextStyle(if (on) Color.White else LiquidGlassColors.text(isDark), 13.sp, FontWeight.SemiBold),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
                    .clickable { onSelect(kind) }.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun DeviceDocRow(doc: DeviceDocument, isDark: Boolean, shape: Shape, bottomPad: Dp, onClick: () -> Unit) {
    val (icon, tint, _) = kindVisual(doc.kind)
    val sub = LiquidGlassColors.secondary(isDark)
    val meta = buildString {
        append(formatFileSize(doc.size)); append(" · "); append(formatTimestamp(doc.modified))
        if (doc.folder.isNotEmpty()) { append(" · "); append(doc.folder) }
    }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(cardFill(isDark)).clickable(onClick = onClick)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp + bottomPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint)
        }
        Column(Modifier.weight(1f)) {
            BasicText(doc.name, style = TextStyle(LiquidGlassColors.text(isDark), 14.sp, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(meta, style = TextStyle(sub, 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AccessCard(isDark: Boolean, onGrant: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(stringResource(R.string.home_device_access_title), style = TextStyle(LiquidGlassColors.text(isDark), 15.sp, FontWeight.SemiBold))
        BasicText(stringResource(R.string.home_device_access_body), style = TextStyle(LiquidGlassColors.secondary(isDark), 13.sp))
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(R.string.home_device_access_grant),
            style = TextStyle(Color.White, 14.sp, FontWeight.SemiBold, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(ToolAccents.Open)
                .clickable(onClick = onGrant).padding(vertical = 12.dp)
        )
    }
}

@Composable
private fun CenterMessage(isDark: Boolean, text: String, spinner: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (spinner) CircularProgressIndicator(Modifier.size(24.dp), color = ToolAccents.Open, strokeWidth = 2.5.dp)
        BasicText(text, style = TextStyle(LiquidGlassColors.secondary(isDark), 13.sp, textAlign = TextAlign.Center))
    }
}
