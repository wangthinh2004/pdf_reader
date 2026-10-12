# Home: device documents list with type tabs — Design

Date: 2026-10-08
Status: Approved in chat, awaiting written-spec review

## Goal

When the app opens, Home lists every PDF / Word / Excel / PowerPoint document on the device, with
tabs **All | PDF | Word | Excel | PPT**. Tapping a file opens it in the viewer that matches its type
(the existing routing). Today Home only shows *recents* — files previously opened in the app.

### Decisions made with the user
- Permission: request **All files access** (`MANAGE_EXTERNAL_STORAGE`) so documents owned by other
  apps are visible on Android 11+. Denial must leave Home working exactly as it does today.
- Layout: keep the hero card and the Recents panel; add a new **"On this device"** section below them.
- Scan method: query **MediaStore** (`MediaStore.Files`), not a recursive file walk.
- The new section must **not** use the existing glass/backdrop effects (they make the app lag);
  flat Compose surfaces only. Existing glass elsewhere on Home is left as-is.

### Assumption (not explicitly confirmed)
- New strings get an English default plus a Vietnamese translation (`values-vi`). The app has no
  `values-vi` yet; only the new strings are translated.

### Out of scope
- Auditing / adding Word, Excel, PPT editing features (separate follow-up).
- HWP and ebook formats.
- Recursive file-system walk (only if MediaStore proves to miss files in practice).

## Architecture

### 1. Permission
- `app/src/main/AndroidManifest.xml`: add
  `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />`.
  On Android ≤ 9 the runtime `WRITE_EXTERNAL_STORAGE` permission grants access (and in-place save).
  On those releases DISPLAY_NAME is null for documents, so the query matches on `_data` and rows
  open as `file://` uris. **Android 10 (API 29) does not get the section**: scoped storage hides other
  apps' documents and `requestLegacyExternalStorage` would change how every tool saves to Downloads.
  (Revised after final review.)
- `StorageAccess` helper (new, `data/repository/StorageAccess.kt`):
  - `hasDocumentAccess(context)`: API ≥ 30 → `Environment.isExternalStorageManager()`;
    API ≤ 29 → `READ_EXTERNAL_STORAGE` granted.
  - `requestIntent(context)`: API ≥ 30 → `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`
    with `package:` uri, falling back to `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` if the first
    can't resolve. API ≤ 29 → runtime permission request via an activity-result launcher in Home.
- Home re-checks access on `ON_RESUME` (it already observes resume for recents) and scans when
  access has just been granted.

### 2. Scanner — `data/repository/DeviceDocumentScanner.kt` (new)
```kotlin
data class DeviceDocument(
    val uri: Uri, val name: String, val size: Long,
    val modified: Long, val folder: String, val kind: DocKind
)
object DeviceDocumentScanner {
    suspend fun scan(context: Context): List<DeviceDocument>   // Dispatchers.IO
}
```
- Query `MediaStore.Files.getContentUri("external")`, projection `_ID, DISPLAY_NAME, SIZE,
  DATE_MODIFIED, RELATIVE_PATH (API ≥ 29) / DATA (≤ 28)`, selection on `MIME_TYPE IN (...)` OR
  `DISPLAY_NAME LIKE '%.ext'` for the document extensions (MIME types are often missing for
  office files, so the name test is required).
- Extensions: exactly the set `docKindOf()` already maps to Pdf/Word/Excel/Ppt
  (`pdf; docx doc odt rtf; xlsx xls; pptx ppt`). Kind = `docKindOf(name)`, unchanged. Formats such
  as `xlsm/ods/odp` are deliberately NOT listed: without the optional Office engine the built-in
  pipeline would render them as garbage text, so listing them would offer files that open broken.
- Skip zero-byte entries and paths under `Android/data`, `Android/obb`, and dot-folders.
- Sort by `modified` descending. Uri = `ContentUris.withAppendedId(filesUri, id)`.
- Pure filtering/classification logic lives in an internal function that takes plain rows so it is
  unit-testable without a ContentResolver.

### 3. State — `ui/viewmodel/DeviceDocumentsViewModel.kt` (new)
- `StateFlow<DeviceDocsState>` where state is `NoAccess | Loading | Ready(list) | Error`.
- `refresh(context)` re-scans; called on first composition and on resume. A scan in flight is not
  restarted. The list survives tab switches (ViewModel scope); Ready keeps showing the old list
  during a refresh (no flicker back to Loading).

### 4. Home UI — `ui/screen/HomeScreen.kt` (+ a new `HomeDeviceDocuments.kt` for the section composables)
- **No glass in this section** (user request: the backdrop/glass effects make the app lag). No
  `liquidGlassPanel`, no `LayerBackdrop` sampling, no `GlassSegmentedControl`, no blur/refraction.
  Plain Compose surfaces only: a solid, theme-aware rounded background (light: white ~92% alpha,
  dark: near-black ~85% alpha — a flat fill, no blur), and a simple chip row for tabs (rounded
  pill, accent fill when selected, `clickable` with no custom animation beyond a colour tween).
- New LazyColumn items after `"recents"`:
  - `"deviceHeader"`: title "On this device (N)" and the plain chip row
    All | PDF | Word | Excel | PPT (horizontally scrollable on narrow screens).
  - one item per document (key = uri string), a lightweight row (icon tile from `kindVisual()`,
    name, size · date) — not `RecentRow`, which carries swipe/long-press animation machinery.
    Rows are separate items so thousands of files stay smooth.
  - state items: permission card with "Grant access" button; loading spinner; empty message.
- Search: the existing header query also filters this list by name.
- Section shows when recents are disabled too. Today Home swaps to the centred `HomeLaunchpad` in
  that case, which is a scrolling Column and cannot host thousands of rows. New behaviour: Home
  always uses the LazyColumn (hero card → recents panel only if enabled → device section), and the
  header is always the search header. `HomeLaunchpad` becomes unused and is removed.
- Tap → `onRecentFileSelected(doc.uri, doc.name)` — unchanged routing in `DocsNavGraph`
  (Excel → spreadsheet grid, others → PDF viewer). The spreadsheet viewer already tolerates a
  failed `takePersistableUriPermission`; MediaStore uris are writable with all-files access, so
  in-place Excel save keeps working.

### 5. Strings
`home_device_docs`, `home_device_docs_count`, `home_device_access_title`,
`home_device_access_body`, `home_device_access_grant`, `home_device_scanning`,
`home_device_empty`, tab labels reuse existing `recents_filter_*`. English in `values/`, Vietnamese
in `values-vi/`.

## Error handling
- Query throws (SecurityException, provider failure) → `Error` state shows the empty message with
  a retry tap; Recents unaffected.
- Access revoked while running → next resume moves state to `NoAccess`.
- A file deleted after scanning → opening fails in the viewer as it does for a stale recent; next
  resume rescan drops it.

## Testing
- Unit tests (`app/src/test/.../DeviceDocumentScannerTest.kt`): extension → kind classification
  (including new extensions), excluded folders, zero-byte skip, sort order.
- Build: `./gradlew :app:assemblePlayDebug :app:testPlayDebugUnitTest`.
- Manual on device (user): grant flow, tabs, search, opening one file of each kind, Excel edit + save.
