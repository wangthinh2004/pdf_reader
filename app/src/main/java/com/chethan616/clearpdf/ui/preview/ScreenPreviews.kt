package com.chethan616.clearpdf.ui.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.RecentFile
import com.chethan616.clearpdf.office.OfficeEngineState
import com.chethan616.clearpdf.ui.screen.HomeScreen
import com.chethan616.clearpdf.ui.screen.LocalHomePreviewRecents
import com.chethan616.clearpdf.ui.screen.LocalOfficeEnginePreviewState
import com.chethan616.clearpdf.ui.screen.SettingsScreen
import com.chethan616.clearpdf.ui.screen.ToolsScreen
import com.chethan616.clearpdf.ui.theme.LocalIsDarkMode
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

// Mirrors the wallpaper + backdrop layering in DocsApp so the glass surfaces have something to
// sample. Android Studio previews only; nothing here is reachable from the running app.
@Composable
private fun PreviewHost(isDarkMode: Boolean, content: @Composable (LayerBackdrop) -> Unit) {
    CompositionLocalProvider(LocalIsDarkMode provides isDarkMode) {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            Image(
                painterResource(if (isDarkMode) R.drawable.wallpaper_dark else R.drawable.wallpaper_light),
                contentDescription = null,
                modifier = Modifier.layerBackdrop(backdrop).fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            content(backdrop)
        }
    }
}

private val SampleRecents = listOf(
    RecentFile("Hop dong thue nha.pdf", "content://preview/1", 1_759_900_000_000, pageCount = 12, sizeBytes = 1_240_000, currentPage = 3, pinned = true),
    RecentFile("Bao cao quy 3.docx", "content://preview/2", 1_759_800_000_000, sizeBytes = 86_000),
    RecentFile("Ngan sach 2026.xlsx", "content://preview/3", 1_759_700_000_000, sizeBytes = 42_000),
    RecentFile("Slide thuyet trinh.pptx", "content://preview/4", 1_759_600_000_000, sizeBytes = 3_500_000),
    RecentFile("Scan CCCD.jpg", "content://preview/5", 1_759_500_000_000, sizeBytes = 640_000),
    RecentFile("Giao trinh Android.pdf", "content://preview/6", 1_759_400_000_000, pageCount = 240, sizeBytes = 8_900_000)
)

@Composable
private fun HomePreview(isDarkMode: Boolean, recents: List<RecentFile> = SampleRecents) =
    PreviewHost(isDarkMode) { backdrop ->
        CompositionLocalProvider(LocalHomePreviewRecents provides recents) {
            HomeScreen(backdrop, onNavigateToOpenPdf = {}, onNavigateToScan = {}, onRecentFileSelected = { _, _ -> })
        }
    }

@Preview(name = "Home – Light", showSystemUi = true)
@Composable
private fun HomeLightPreview() = HomePreview(isDarkMode = false)

@Preview(name = "Home – Dark", showSystemUi = true)
@Composable
private fun HomeDarkPreview() = HomePreview(isDarkMode = true)

@Preview(name = "Home – Empty", showSystemUi = true)
@Composable
private fun HomeEmptyPreview() = HomePreview(isDarkMode = false, recents = emptyList())

@Preview(name = "Tools – Light", showSystemUi = true)
@Composable
private fun ToolsLightPreview() = PreviewHost(isDarkMode = false) { backdrop ->
    ToolsScreen(
        backdrop,
        onNavigateToOpenPdf = {},
        onNavigateToMergePdf = {},
        onNavigateToSplitPdf = {},
        onNavigateToCompressPdf = {},
        onNavigateToCreatePdf = {}
    )
}

@Preview(name = "Tools – Dark", showSystemUi = true)
@Composable
private fun ToolsDarkPreview() = PreviewHost(isDarkMode = true) { backdrop ->
    ToolsScreen(
        backdrop,
        onNavigateToOpenPdf = {},
        onNavigateToMergePdf = {},
        onNavigateToSplitPdf = {},
        onNavigateToCompressPdf = {},
        onNavigateToCreatePdf = {}
    )
}

@Preview(name = "Settings – Light", showSystemUi = true)
@Composable
private fun SettingsLightPreview() = PreviewHost(isDarkMode = false) { backdrop ->
    SettingsScreen(backdrop, isDarkMode = false)
}

@Preview(name = "Settings – Dark", showSystemUi = true)
@Composable
private fun SettingsDarkPreview() = PreviewHost(isDarkMode = true) { backdrop ->
    SettingsScreen(backdrop, isDarkMode = true)
}

// SettingsScreen is a long scroll; heightDp tall enough to show every section, Office engine included.
@Preview(name = "Settings – Full, engine installed", widthDp = 411, heightDp = 3200, showBackground = true)
@Composable
private fun SettingsFullPreview() = PreviewHost(isDarkMode = false) { backdrop ->
    CompositionLocalProvider(
        LocalOfficeEnginePreviewState provides OfficeEngineState.Installed("25.8.1", 412_000_000, updateAvailable = false)
    ) {
        SettingsScreen(backdrop, isDarkMode = false)
    }
}

@Preview(name = "Settings – Full, downloading", widthDp = 411, heightDp = 3200, showBackground = true)
@Composable
private fun SettingsDownloadingPreview() = PreviewHost(isDarkMode = true) { backdrop ->
    CompositionLocalProvider(
        LocalOfficeEnginePreviewState provides OfficeEngineState.Downloading(150_000_000, 412_000_000)
    ) {
        SettingsScreen(backdrop, isDarkMode = true)
    }
}
