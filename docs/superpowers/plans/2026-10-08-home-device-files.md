# Home Device Documents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Home lists every PDF / Word / Excel / PowerPoint file on the device under tabs All | PDF | Word | Excel | PPT, and tapping one opens it with the existing kind-based routing.

**Architecture:** A MediaStore-backed `DeviceDocumentScanner` (pure filter logic split out for unit tests) feeds an activity-scoped `DeviceDocumentsViewModel`. `DocsNavGraph` owns the ViewModel and the all-files-access request, and passes state + callbacks into `HomeScreen`, which renders a new flat (no-glass) section from `HomeDeviceDocuments.kt` below Recents.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Lifecycle ViewModel, MediaStore, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-08-home-device-files-design.md`

## Global Constraints

- Scanned extensions are exactly those `docKindOf()` maps to Pdf/Word/Excel/Ppt: `pdf; docx doc odt rtf; xlsx xls; pptx ppt`. Do not change `docKindOf`.
- The new section must not use glass: no `liquidGlassPanel`, no `LayerBackdrop`/`backdrop` parameter, no `GlassSegmentedControl`, no blur. Flat fills only.
- Flat card fill: light `Color.White.copy(alpha = 0.92f)`, dark `Color(0xFF1C1C1E).copy(alpha = 0.85f)`.
- Permission: `MANAGE_EXTERNAL_STORAGE` on API ≥ 30; `READ_EXTERNAL_STORAGE` (already declared, maxSdk 32) on API ≤ 29. minSdk is 23.
- Denied access must leave hero card + Recents behaving as today.
- New strings: English in `values/strings.xml`, Vietnamese in new `values-vi/strings.xml`.
- Tap routing stays `onRecentFileSelected(uri, name)` → `navigateToDocument` (unchanged).
- Build/test: `./gradlew :app:testPlayDebugUnitTest` and `./gradlew :app:assemblePlayDebug` from repo root.
- Working tree on `main` has unrelated uncommitted changes. Create branch `feature/home-device-docs` before the first commit and `git add` only the files each task names.

## Review Focus

1. **Files in `Android/data`, `Android/obb`, or dot-folders** (app caches, `.thumbnails`, `.Trash`) — must not appear; pinned by `excludesPrivateAndHiddenFolders` in Task 1.
2. **Uppercase / mixed-case extensions (`REPORT.PDF`, `Budget.XLSX`)** — must be listed and classified; pinned by `classifiesCaseInsensitively` in Task 1.
3. **Lookalike names (`notes.pdf.tmp`, `archive.docx.zip`, `pdf` with no dot)** — must be excluded; pinned by `rejectsLookalikeNames` in Task 1.
4. **Zero-byte placeholder files** (failed downloads) — must be excluded; pinned by `skipsEmptyFiles` in Task 1.
5. **Pre-Android-10 rows with only `_data` path (no `relative_path`)** — folder and exclusion must still work; pinned by `derivesFolderFromDataPathOnOldDevices` in Task 1.

---

## File Structure

| File | Responsibility |
|---|---|
| Create `app/src/main/java/com/chethan616/clearpdf/data/repository/DeviceDocumentFilter.kt` | Pure logic: row → `DeviceDocCandidate?` (kind, exclusion, folder), sort. No Android types. |
| Create `app/src/main/java/com/chethan616/clearpdf/data/repository/DeviceDocumentScanner.kt` | MediaStore query → rows → filter → `List<DeviceDocument>` with `Uri`. |
| Create `app/src/main/java/com/chethan616/clearpdf/data/repository/StorageAccess.kt` | Has-access check and the settings intent for all-files access. |
| Create `app/src/main/java/com/chethan616/clearpdf/ui/viewmodel/DeviceDocumentsViewModel.kt` | `DeviceDocsState` + refresh lifecycle. |
| Create `app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeDeviceDocuments.kt` | Flat composables + `LazyListScope.deviceDocumentsSection(...)`. |
| Create `app/src/test/java/com/chethan616/clearpdf/data/repository/DeviceDocumentFilterTest.kt` | Unit tests for the filter. |
| Create `app/src/main/res/values-vi/strings.xml` | Vietnamese strings for the new section. |
| Modify `app/src/main/AndroidManifest.xml` | Declare `MANAGE_EXTERNAL_STORAGE`. |
| Modify `app/src/main/res/values/strings.xml` | English strings. |
| Modify `app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeScreen.kt` | New params, always-LazyColumn, append section, remove `HomeLaunchpad`, expose helpers as `internal`. |
| Modify `app/src/main/java/com/chethan616/clearpdf/ui/navigation/DocsNavGraph.kt` | Create ViewModel, permission launchers, pass into `HomeScreen`. |

---

### Task 1: Device document filter (pure logic) + tests

**Files:**
- Create: `app/src/main/java/com/chethan616/clearpdf/data/repository/DeviceDocumentFilter.kt`
- Test: `app/src/test/java/com/chethan616/clearpdf/data/repository/DeviceDocumentFilterTest.kt`

**Interfaces:**
- Consumes: `com.chethan616.clearpdf.utils.DocKind`, `docKindOf(String?)`.
- Produces:
  - `data class MediaRow(val id: Long, val name: String?, val size: Long, val modifiedSec: Long, val relativePath: String?, val dataPath: String?)`
  - `data class DeviceDocCandidate(val id: Long, val name: String, val size: Long, val modifiedMs: Long, val folder: String, val kind: DocKind)`
  - `object DeviceDocumentFilter { val EXTENSIONS: List<String>; fun accept(row: MediaRow): DeviceDocCandidate?; fun select(rows: List<MediaRow>): List<DeviceDocCandidate> }`

- [ ] **Step 0: Create the branch**

```bash
git checkout -b feature/home-device-docs
```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.chethan616.clearpdf.data.repository

import com.chethan616.clearpdf.utils.DocKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceDocumentFilterTest {

    private fun row(
        name: String?, size: Long = 100, modifiedSec: Long = 1_000,
        rel: String? = "Download/", data: String? = null, id: Long = 1
    ) = MediaRow(id, name, size, modifiedSec, rel, data)

    @Test fun classifiesEachSupportedExtension() {
        assertEquals(DocKind.Pdf, DeviceDocumentFilter.accept(row("a.pdf"))?.kind)
        assertEquals(DocKind.Word, DeviceDocumentFilter.accept(row("a.docx"))?.kind)
        assertEquals(DocKind.Word, DeviceDocumentFilter.accept(row("a.doc"))?.kind)
        assertEquals(DocKind.Word, DeviceDocumentFilter.accept(row("a.odt"))?.kind)
        assertEquals(DocKind.Word, DeviceDocumentFilter.accept(row("a.rtf"))?.kind)
        assertEquals(DocKind.Excel, DeviceDocumentFilter.accept(row("a.xlsx"))?.kind)
        assertEquals(DocKind.Excel, DeviceDocumentFilter.accept(row("a.xls"))?.kind)
        assertEquals(DocKind.Ppt, DeviceDocumentFilter.accept(row("a.pptx"))?.kind)
        assertEquals(DocKind.Ppt, DeviceDocumentFilter.accept(row("a.ppt"))?.kind)
    }

    @Test fun classifiesCaseInsensitively() {
        assertEquals(DocKind.Pdf, DeviceDocumentFilter.accept(row("REPORT.PDF"))?.kind)
        assertEquals(DocKind.Excel, DeviceDocumentFilter.accept(row("Budget.XLSX"))?.kind)
    }

    @Test fun rejectsUnsupportedAndLookalikeNames() {
        assertNull(DeviceDocumentFilter.accept(row("photo.jpg")))
        assertNull(DeviceDocumentFilter.accept(row("book.epub")))
        assertNull(DeviceDocumentFilter.accept(row("macro.xlsm")))
        assertNull(DeviceDocumentFilter.accept(row(null)))
    }

    @Test fun rejectsLookalikeNames() {
        assertNull(DeviceDocumentFilter.accept(row("notes.pdf.tmp")))
        assertNull(DeviceDocumentFilter.accept(row("archive.docx.zip")))
        assertNull(DeviceDocumentFilter.accept(row("pdf")))
    }

    @Test fun skipsEmptyFiles() {
        assertNull(DeviceDocumentFilter.accept(row("a.pdf", size = 0)))
    }

    @Test fun excludesPrivateAndHiddenFolders() {
        assertNull(DeviceDocumentFilter.accept(row("a.pdf", rel = "Android/data/com.x/files/")))
        assertNull(DeviceDocumentFilter.accept(row("a.pdf", rel = "Android/obb/com.x/")))
        assertNull(DeviceDocumentFilter.accept(row("a.pdf", rel = "Documents/.cache/")))
        assertNull(DeviceDocumentFilter.accept(row(".hidden.pdf")))
        assertEquals("Documents/Work", DeviceDocumentFilter.accept(row("a.pdf", rel = "Documents/Work/"))?.folder)
    }

    @Test fun derivesFolderFromDataPathOnOldDevices() {
        val ok = DeviceDocumentFilter.accept(row("a.pdf", rel = null, data = "/storage/emulated/0/Download/a.pdf"))
        assertEquals("Download", ok?.folder)
        assertNull(DeviceDocumentFilter.accept(row("a.pdf", rel = null, data = "/storage/emulated/0/Android/data/x/a.pdf")))
        assertEquals("", DeviceDocumentFilter.accept(row("a.pdf", rel = null, data = null))?.folder)
    }

    @Test fun convertsSecondsToMillis() {
        assertEquals(5_000L, DeviceDocumentFilter.accept(row("a.pdf", modifiedSec = 5))?.modifiedMs)
    }

    @Test fun selectSortsNewestFirstAndDropsRejected() {
        val out = DeviceDocumentFilter.select(listOf(
            row("old.pdf", modifiedSec = 1, id = 1),
            row("skip.jpg", modifiedSec = 9, id = 2),
            row("new.docx", modifiedSec = 5, id = 3)
        ))
        assertEquals(listOf(3L, 1L), out.map { it.id })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testPlayDebugUnitTest --tests "com.chethan616.clearpdf.data.repository.DeviceDocumentFilterTest"`
Expected: FAIL — compilation error, `Unresolved reference: MediaRow` / `DeviceDocumentFilter`.

- [ ] **Step 3: Implement**

```kotlin
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
 * Pdf/Word/Excel/Ppt are kept — those are what the built-in viewers render without the optional
 * Office engine.
 */
object DeviceDocumentFilter {

    val EXTENSIONS = listOf("pdf", "docx", "doc", "odt", "rtf", "xlsx", "xls", "pptx", "ppt")

    private val listedKinds = setOf(DocKind.Pdf, DocKind.Word, DocKind.Excel, DocKind.Ppt)
    private val storageRoot = Regex("^/storage/(emulated/\\d+|[^/]+)/")

    fun accept(row: MediaRow): DeviceDocCandidate? {
        val name = row.name?.trim().orEmpty()
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
            ?: row.dataPath?.substringBeforeLast('/', "")?.replace(storageRoot, "")?.trim('/')
            ?: ""
        val parts = folder.split('/').filter { it.isNotEmpty() }
        if (parts.any { it.startsWith(".") }) return null
        if (parts.size >= 2 && parts[0].equals("Android", true) &&
            (parts[1].equals("data", true) || parts[1].equals("obb", true))) return null
        return parts.joinToString("/")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testPlayDebugUnitTest --tests "com.chethan616.clearpdf.data.repository.DeviceDocumentFilterTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/data/repository/DeviceDocumentFilter.kt app/src/test/java/com/chethan616/clearpdf/data/repository/DeviceDocumentFilterTest.kt
git commit -m "feat(home): filter MediaStore rows into listable device documents"
```

---

### Task 2: Storage access, MediaStore scanner, ViewModel

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (after the `READ_EXTERNAL_STORAGE` line, ~line 14)
- Create: `app/src/main/java/com/chethan616/clearpdf/data/repository/StorageAccess.kt`
- Create: `app/src/main/java/com/chethan616/clearpdf/data/repository/DeviceDocumentScanner.kt`
- Create: `app/src/main/java/com/chethan616/clearpdf/ui/viewmodel/DeviceDocumentsViewModel.kt`

**Interfaces:**
- Consumes: Task 1's `MediaRow`, `DeviceDocumentFilter.select`, `DeviceDocumentFilter.EXTENSIONS`.
- Produces:
  - `object StorageAccess { fun hasDocumentAccess(context: Context): Boolean; fun needsSettingsScreen(): Boolean; fun settingsIntent(context: Context): Intent; const val LEGACY_PERMISSION: String }`
  - `data class DeviceDocument(val uri: Uri, val name: String, val size: Long, val modified: Long, val folder: String, val kind: DocKind)`
  - `object DeviceDocumentScanner { suspend fun scan(context: Context): List<DeviceDocument> }`
  - `sealed interface DeviceDocsState { data object NoAccess; data object Loading; data class Ready(val docs: List<DeviceDocument>); data object Error }`
  - `class DeviceDocumentsViewModel(app: Application) : AndroidViewModel(app) { val state: StateFlow<DeviceDocsState>; fun refresh() }`

- [ ] **Step 1: Declare the permission** — insert after the `READ_EXTERNAL_STORAGE` line in `app/src/main/AndroidManifest.xml`:

```xml
    <!-- Home lists every PDF / Office document on the device. On Android 11+ documents owned by
         other apps are only visible to MediaStore queries with All files access. -->
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
        tools:ignore="ScopedStorage" />
```

- [ ] **Step 2: Create `StorageAccess.kt`**

```kotlin
package com.chethan616.clearpdf.data.repository

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Whether Home may scan the device for documents, and how to ask for it. */
object StorageAccess {

    const val LEGACY_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasDocumentAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, LEGACY_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** API 30+: access is granted on a system settings page, not a runtime dialog. */
    fun needsSettingsScreen(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The app's own All-files-access page, or the global list when a ROM lacks the per-app one. */
    fun settingsIntent(context: Context): Intent {
        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        return if (perApp.resolveActivity(context.packageManager) != null) perApp
        else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}
```

Note: `Settings.ACTION_MANAGE_*_ALL_FILES_ACCESS_PERMISSION` are API 30; `settingsIntent` is only called when `needsSettingsScreen()` is true — annotate it `@androidx.annotation.RequiresApi(Build.VERSION_CODES.R)`.

- [ ] **Step 3: Create `DeviceDocumentScanner.kt`**

```kotlin
package com.chethan616.clearpdf.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.chethan616.clearpdf.utils.DocKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceDocument(
    val uri: Uri,
    val name: String,
    val size: Long,
    /** Epoch millis. */
    val modified: Long,
    val folder: String,
    val kind: DocKind
)

/** Lists documents on shared storage through MediaStore. Needs [StorageAccess.hasDocumentAccess]. */
object DeviceDocumentScanner {

    suspend fun scan(context: Context): List<DeviceDocument> = withContext(Dispatchers.IO) {
        val filesUri = MediaStore.Files.getContentUri("external")
        val hasRelative = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            @Suppress("DEPRECATION") add(MediaStore.Files.FileColumns.DATA)
            if (hasRelative) add(MediaStore.Files.FileColumns.RELATIVE_PATH)
        }.toTypedArray()
        // Name test, not MIME: office files are often indexed with a null or generic MIME type.
        val selection = DeviceDocumentFilter.EXTENSIONS.joinToString(" OR ", "(", ")") {
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?"
        } + " AND ${MediaStore.Files.FileColumns.SIZE} > 0"
        val args = DeviceDocumentFilter.EXTENSIONS.map { "%.$it" }.toTypedArray()

        val rows = ArrayList<MediaRow>()
        context.contentResolver.query(filesUri, projection, selection, args, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            @Suppress("DEPRECATION") val data = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val rel = if (hasRelative) c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH) else -1
            while (c.moveToNext()) {
                rows += MediaRow(
                    id = c.getLong(id),
                    name = c.getString(name),
                    size = c.getLong(size),
                    modifiedSec = c.getLong(modified),
                    relativePath = if (rel >= 0) c.getString(rel) else null,
                    dataPath = if (data >= 0) c.getString(data) else null
                )
            }
        }
        DeviceDocumentFilter.select(rows).map {
            DeviceDocument(ContentUris.withAppendedId(filesUri, it.id), it.name, it.size, it.modifiedMs, it.folder, it.kind)
        }
    }
}
```

- [ ] **Step 4: Create `DeviceDocumentsViewModel.kt`**

```kotlin
package com.chethan616.clearpdf.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chethan616.clearpdf.data.repository.DeviceDocument
import com.chethan616.clearpdf.data.repository.DeviceDocumentScanner
import com.chethan616.clearpdf.data.repository.StorageAccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DeviceDocsState {
    data object NoAccess : DeviceDocsState
    data object Loading : DeviceDocsState
    data class Ready(val docs: List<DeviceDocument>) : DeviceDocsState
    data object Error : DeviceDocsState
}

/** Activity-scoped so the list survives switching between the main tabs. */
class DeviceDocumentsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<DeviceDocsState>(DeviceDocsState.Loading)
    val state: StateFlow<DeviceDocsState> = _state.asStateFlow()
    private var scanJob: Job? = null

    /** Re-checks access and rescans. A list already on screen stays up while the rescan runs. */
    fun refresh() {
        val app = getApplication<Application>()
        if (!StorageAccess.hasDocumentAccess(app)) {
            scanJob?.cancel()
            _state.value = DeviceDocsState.NoAccess
            return
        }
        if (scanJob?.isActive == true) return
        val previous = _state.value
        if (previous !is DeviceDocsState.Ready) _state.value = DeviceDocsState.Loading
        scanJob = viewModelScope.launch {
            _state.value = runCatching { DeviceDocumentScanner.scan(app) }
                .fold({ DeviceDocsState.Ready(it) }, { previous as? DeviceDocsState.Ready ?: DeviceDocsState.Error })
        }
    }
}
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compilePlayDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/chethan616/clearpdf/data/repository/StorageAccess.kt app/src/main/java/com/chethan616/clearpdf/data/repository/DeviceDocumentScanner.kt app/src/main/java/com/chethan616/clearpdf/ui/viewmodel/DeviceDocumentsViewModel.kt
git commit -m "feat(home): scan device documents via MediaStore with all-files access"
```

---

### Task 3: Flat device-documents section (UI) + strings

**Files:**
- Create: `app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeDeviceDocuments.kt`
- Modify: `app/src/main/res/values/strings.xml` (append before `</resources>`)
- Create: `app/src/main/res/values-vi/strings.xml`
- Modify: `app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeScreen.kt:1332-1359` — change `private fun formatTimestamp`, `private fun formatFileSize`, `private fun kindVisual` to `internal fun` (no other change).

**Interfaces:**
- Consumes: `DeviceDocsState`, `DeviceDocument` (Task 2); `internal fun kindVisual(DocKind)`, `internal fun formatFileSize(Long)`, `internal fun formatTimestamp(Long)` from `HomeScreen.kt`; `LiquidGlassColors.text/secondary(isDark)` (colours only); `ToolAccents.Open`.
- Produces:
  ```kotlin
  internal fun LazyListScope.deviceDocumentsSection(
      state: DeviceDocsState,
      tab: DocKind?,                 // null = All
      onTabChange: (DocKind?) -> Unit,
      query: String,
      isDark: Boolean,
      onGrantAccess: () -> Unit,
      onRetry: () -> Unit,
      onOpen: (DeviceDocument) -> Unit
  )
  ```

- [ ] **Step 1: English strings** — append to `app/src/main/res/values/strings.xml`:

```xml
    <!-- Home: documents on this device -->
    <string name="home_device_docs">On this device</string>
    <string name="home_device_docs_count">On this device (%1$d)</string>
    <string name="home_device_access_title">Show all your documents</string>
    <string name="home_device_access_body">Allow access to files so ClearPDF can list every PDF, Word, Excel and PowerPoint file on this device. Files never leave your phone.</string>
    <string name="home_device_access_grant">Allow access</string>
    <string name="home_device_scanning">Looking for documents…</string>
    <string name="home_device_empty">No documents of this type found</string>
    <string name="home_device_error">Couldn\'t read your files. Tap to try again.</string>
```

- [ ] **Step 2: Vietnamese strings** — create `app/src/main/res/values-vi/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="home_device_docs">Tài liệu trên máy</string>
    <string name="home_device_docs_count">Tài liệu trên máy (%1$d)</string>
    <string name="home_device_access_title">Hiển thị tất cả tài liệu</string>
    <string name="home_device_access_body">Cho phép truy cập tệp để ClearPDF liệt kê mọi tệp PDF, Word, Excel và PowerPoint trên máy. Tệp không bao giờ rời khỏi điện thoại của bạn.</string>
    <string name="home_device_access_grant">Cho phép truy cập</string>
    <string name="home_device_scanning">Đang tìm tài liệu…</string>
    <string name="home_device_empty">Không có tài liệu loại này</string>
    <string name="home_device_error">Không đọc được tệp. Chạm để thử lại.</string>
</resources>
```

- [ ] **Step 3: Make the three helpers `internal`** in `HomeScreen.kt` (`formatTimestamp`, `formatFileSize`, `kindVisual`): replace `private fun` with `internal fun` on those three declarations only.

- [ ] **Step 4: Create `HomeDeviceDocuments.kt`**

```kotlin
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
        DocKind.Ppt to R.string.recents_filter_ppt
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
private fun DeviceDocRow(doc: DeviceDocument, isDark: Boolean, shape: Shape, bottomPad: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
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
```

Before compiling, confirm the import paths of `LiquidGlassColors` and `ToolAccents` by copying them from the import block at the top of `HomeScreen.kt` (search `import .*LiquidGlassColors` / `import .*ToolAccents`), and replace the two `ui.theme` imports above if they differ.

(`visibleDeviceDocs` is not unit-tested: `DeviceDocument` holds an `android.net.Uri`, which plain JVM tests can't construct. Task 5 items 3–4 cover it on device.)

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compilePlayDebugKotlin`
Expected: BUILD SUCCESSFUL (the section is not wired yet; an unused-function warning is fine).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeDeviceDocuments.kt app/src/main/res/values/strings.xml app/src/main/res/values-vi/strings.xml app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeScreen.kt
git commit -m "feat(home): flat device-documents section with type tabs"
```

Note: `HomeScreen.kt` already has unrelated uncommitted edits from before this work. Use `git add -p app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeScreen.kt` and stage only the three `private` → `internal` hunks; if the hunks are interleaved with the user's edits and cannot be separated, stop and ask the user.

---

### Task 4: Wire into Home and navigation

**Files:**
- Modify: `app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeScreen.kt` (signature ~160-169; resume observer ~221-230; header ~261-288; body ~289-495; delete `HomeLaunchpad` ~760-880 and `FormatMark` ~881 if unused after deletion)
- Modify: `app/src/main/java/com/chethan616/clearpdf/ui/navigation/DocsNavGraph.kt` (`composable(ROUTE_HOME)` ~343-370)

**Interfaces:**
- Consumes: `deviceDocumentsSection(...)` (Task 3), `DeviceDocumentsViewModel`, `DeviceDocsState`, `StorageAccess` (Task 2).
- Produces: `HomeScreen(..., deviceDocs: DeviceDocsState = DeviceDocsState.NoAccess, onGrantDeviceAccess: () -> Unit = {}, onRefreshDeviceDocs: () -> Unit = {})` — defaults keep `ScreenPreviews.kt` compiling.

- [ ] **Step 1: Extend the `HomeScreen` signature** — after the `onRecentFileSelected` parameter add:

```kotlin
    onRecentFileSelected: (Uri, String) -> Unit,
    // Documents found on the device (flat section below Recents). Defaults keep previews simple.
    deviceDocs: com.chethan616.clearpdf.ui.viewmodel.DeviceDocsState = com.chethan616.clearpdf.ui.viewmodel.DeviceDocsState.NoAccess,
    onGrantDeviceAccess: () -> Unit = {},
    onRefreshDeviceDocs: () -> Unit = {}
```

Add below the `recentFilter` state:

```kotlin
    // Type tab for the device-documents section. Null = All.
    var deviceTab by rememberSaveable { mutableStateOf<DocKind?>(null) }
```

(import `androidx.compose.runtime.saveable.rememberSaveable` if not already imported.)

- [ ] **Step 2: Rescan on resume and first show** — in the existing `ON_RESUME` branch of the lifecycle observer, after `recents = RecentFilesManager.getRecents(context)`, add `onRefreshDeviceDocs()`. `ON_RESUME` fires on first show too, so no separate `LaunchedEffect` is needed.

- [ ] **Step 3: Always use the search header** — replace the `if (recentFilesEnabled) { GlassSearchHeader(...) } else { GlassScreenHeaderRow(...) }` block with just the `GlassSearchHeader(...)` call (unchanged arguments). Delete the `LaunchedEffect(recentFilesEnabled)` block that clears `searchActive` / `recentQuery` when recents are off, and replace it with one that only closes the filter menu:

```kotlin
    LaunchedEffect(recentFilesEnabled) { if (!recentFilesEnabled) filterMenuOpen = false }
```

- [ ] **Step 4: Always use the LazyColumn** — replace

```kotlin
        ) { contentPadding ->
            if (!recentFilesEnabled) {
                HomeLaunchpad(
                    ...
                )
            } else {
                val lazyListState = rememberLazyListState()
```

with

```kotlin
        ) { contentPadding ->
            run {
                val lazyListState = rememberLazyListState()
```

(the closing braces stay balanced: `run {` replaces `else {`.)

- [ ] **Step 5: Explicit gaps instead of `spacedBy`** — device rows must touch to read as one card. In the LazyColumn change `verticalArrangement = Arrangement.spacedBy(16.dp)` to `verticalArrangement = Arrangement.Top`, then:
  - Hero item: inside `AnimatedVisibility { ... }` wrap the card: `Box(Modifier.padding(bottom = 16.dp)) { HomeHeroCard(...) }` (padding inside the AnimatedVisibility so it collapses with the card while searching).
  - Recents item: wrap with `if (recentFilesEnabled) { item(key = "recents", ...) { Box(Modifier.padding(bottom = 16.dp)) { Column( ...existing... ) } } }`.

- [ ] **Step 6: Append the device section** — immediately before the `item(key = "bottomSpacer", ...)`:

```kotlin
                    deviceDocumentsSection(
                        state = deviceDocs,
                        tab = deviceTab,
                        onTabChange = { deviceTab = it },
                        query = recentQuery,
                        isDark = isDarkMode,
                        onGrantAccess = onGrantDeviceAccess,
                        onRetry = onRefreshDeviceDocs,
                        onOpen = { doc -> onRecentFileSelected(doc.uri, doc.name) }
                    )
```

Change the bottom spacer to `Spacer(Modifier.height(TabBarClearance + 16.dp))` so the last card keeps its gap above the tab bar.

- [ ] **Step 7: Remove dead code** — delete `private fun HomeLaunchpad(...)` and its KDoc. Then run `grep -n "FormatMark(" app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeScreen.kt`; if only the declaration remains, delete `FormatMark` too. Remove imports that become unused only if the compiler/IDE flags them as errors (unused imports are warnings; leave them otherwise).

- [ ] **Step 8: Wire `DocsNavGraph`** — inside `composable(ROUTE_HOME) {`, after `openDocLauncher`, add:

```kotlin
            // Activity-scoped: the scanned list survives switching between the main tabs.
            val deviceDocsVm: DeviceDocumentsViewModel = viewModel(
                viewModelStoreOwner = androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity
            )
            val deviceDocs by deviceDocsVm.state.collectAsState()
            // API 30+: All files access is granted on a settings page; ≤ 29: a runtime permission.
            val allFilesSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                deviceDocsVm.refresh()
            }
            val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                deviceDocsVm.refresh()
            }
```

and pass to `HomeScreen(...)` after `onRecentFileSelected = ...`:

```kotlin
                deviceDocs = deviceDocs,
                onGrantDeviceAccess = {
                    if (StorageAccess.needsSettingsScreen()) {
                        runCatching { allFilesSettings.launch(StorageAccess.settingsIntent(homeContext)) }
                    } else {
                        legacyStorage.launch(StorageAccess.LEGACY_PERMISSION)
                    }
                },
                onRefreshDeviceDocs = deviceDocsVm::refresh
```

Imports: `com.chethan616.clearpdf.ui.viewmodel.DeviceDocumentsViewModel`, `com.chethan616.clearpdf.data.repository.StorageAccess`, `androidx.compose.runtime.collectAsState`, `androidx.compose.runtime.getValue` (skip any already present). `viewModel` and `rememberLauncherForActivityResult` / `ActivityResultContracts` are already imported.

- [ ] **Step 9: Build + unit tests**

Run: `./gradlew :app:testPlayDebugUnitTest :app:assemblePlayDebug :app:assembleFossDebug`
Expected: BUILD SUCCESSFUL, all tests pass (including the 9 from Task 1 and the existing xlsx tests).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/ui/navigation/DocsNavGraph.kt
git add -p app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeScreen.kt
git commit -m "feat(home): list device documents under type tabs below Recents"
```

(`git add -p`: stage only this task's hunks; the user's earlier uncommitted HomeScreen edits stay unstaged. If hunks can't be separated, stop and ask the user.)

---

### Task 5: Device verification (user-run)

**Files:** none.

- [ ] **Step 1: Install** — `./gradlew :app:installPlayDebug` with a device connected.
- [ ] **Step 2: Checklist** (report each result):
  1. Fresh install → Home shows hero, Recents, and "On this device" with the access card; Recents still works.
  2. "Allow access" → settings page opens; enable → back in app the list appears without restarting.
  3. Tabs All / PDF / Word / Excel / Slides filter correctly; counts look plausible versus a file manager.
  4. Search text filters both Recents and the device list.
  5. Open one `.pdf`, `.docx`, `.pptx` (→ PDF viewer) and one `.xlsx` (→ spreadsheet grid). Edit a cell in the xlsx, save over the original, reopen: the edit persists.
  6. Settings → turn off "Remember recent files" → Home still shows hero + device list (no Recents panel).
  7. Revoke access in system settings → return to app → access card is back.
  8. Scroll a long list fast: no stutter in the device rows.
