# MVVM + Clean Architecture refactor — Design

Date: 2026-10-13
Status: Approved in chat (3 sections), awaiting written-spec review
Branch: `refactor/clean-architecture` (from `feature/xml-migration`)

## Goal

Restructure the `:app` module into a layered MVVM + Clean Architecture codebase that reads like
senior-level production code, without changing what the user sees or can do.

### Decisions made with the user
- Purpose: a real product that keeps evolving (maintainability over ceremony).
- Dependency injection: **Hilt** (with KSP).
- Layering: **packages inside `:app`** (`core/`, `domain/`, `data/`, `presentation/`), not new
  Gradle modules. `:pdf-core`, `:ocr-core` and `:office_engine` stay as they are.
- Dedicated base classes for Activity, Fragment, ViewModel, Adapter (and dialogs).
- Work in phases; each phase builds, passes tests and is smoke-tested on a device.

### Assumptions (stated in chat, not contradicted)
- Pure refactor: layouts, strings, navigation and feature behaviour stay identical. Bugs found
  along the way are noted, not fixed, unless they block the refactor.
- The design covers all phases; the first implementation plan covers phases 0–2 only. Phases
  3–6 get their own plan after the user reviews the phase-2 reference screens.

### Out of scope
- New features, UI redesign, Jetpack Compose.
- Splitting into Gradle feature/core modules (possible later once `domain/` is clean).
- Changes inside `:pdf-core`, `:ocr-core`, `:office_engine`.
- Replacing the BuildConfig Gemini key with Firebase AI Logic / a backend (separate task).

## Current state (measured 2026-10-13)

- `:app`: 143 Kotlin files, ~25k lines. 22 ViewModels, 8 non-tool Fragments, 15 tool Fragments
  on `ToolFragment`, 7 RecyclerView adapters (5 are `inner class PagesAdapter`).
- 19 of 22 ViewModels take `Context` as a method parameter; 17 call `contentResolver` /
  `MediaStore` directly.
- `createDownloadUri` / `createOutputUri` duplicated in 13 files; `queryFileName` in 6.
- 12 ViewModels created with `by viewModels()` and no injected dependencies.
- `domain/usecase` holds 5 pass-through use cases that take `Context`.
- `data/repository` is 9 `object` singletons with no interfaces; `PdfServiceLocator` is a manual
  service locator.
- Largest files: `PdfViewerViewModel` 1019 lines, `PdfViewerFragment` 952.
- Both `util/` and `utils/` packages exist.
- 8 unit-test files.

## Target architecture

```
com.chethan616.clearpdf
├── ClearPdfApp.kt            @HiltAndroidApp
├── core/
│   ├── di/                   Hilt modules: DispatchersModule, PdfCoreModule, OcrModule,
│   │                         RepositoryModule (@Binds), NetworkModule
│   ├── dispatchers/          @IoDispatcher, @DefaultDispatcher, @PdfDispatcher qualifiers
│   └── result/               AppResult<T> (Success | Failure(AppError)), AppError (sealed)
├── domain/                   Pure Kotlin — no android.* imports
│   ├── model/                DocumentRef, DocumentInfo, OutputDocument, OcrWord, Summary, ...
│   ├── repository/           Interfaces: DocumentRepository, PdfRepository, OcrRepository,
│   │                         SettingsRepository, RecentFilesRepository, SummaryRepository, ...
│   └── usecase/              One class per action, `operator fun invoke(...)`
├── data/                     Implementations; Android allowed
│   ├── repository/           *RepositoryImpl
│   ├── source/local/         ContentResolver/MediaStore, SharedPreferences, OCR page cache
│   ├── source/remote/        GeminiApi (split out of GeminiSummarizer)
│   ├── source/office/        Office/XLSX/HWP readers (today's util/ + utils/)
│   └── mapper/               pdf-core / ocr-core models <-> domain models
└── presentation/             Replaces ui/
    ├── base/                 BaseActivity, BaseFragment, BaseToolFragment, BaseViewModel,
    │                         BaseListAdapter, BaseViewHolder, BaseDialog
    ├── common/               View extensions, shared dialogs, UiText
    └── feature/<name>/       Fragment + ViewModel + UiState/UiEvent + Adapter per feature
                              (home, tools, viewer, sheet, imageeditor, scan, settings,
                               onboarding, tool/compress, tool/merge, tool/extracttext, ...)
```

### Dependency rules
- `presentation` → `domain` only. Fragments never touch repositories; ViewModels never receive
  `Context`, `Uri` handling or Android resources.
- `data` → `domain` (implements its interfaces) and may use `:pdf-core`, `:ocr-core`, Android.
- `domain` → nothing (Kotlin stdlib + coroutines only).
- Hilt in `core/di` wires `data` implementations to `domain` interfaces.

### Key changes
1. **No `Context` in ViewModels.** File naming, output creation, settings and preferences move to
   repositories. `@ApplicationContext` is injected only into `data/`.
2. **De-duplicated I/O.** One `DocumentRepository` with `getInfo(ref)`,
   `createOutput(name, mimeType)`, `takePersistablePermission(ref)` replaces the 13 + 6 copies.
   It honours the user's save location (`SaveLocationManager` behaviour) and falls back to
   Downloads exactly as today.
3. **Android-free domain models.** `DocumentRef(value: String)` replaces `android.net.Uri` in
   domain; mappers convert at the data boundary.
4. **`UiText` for messages.** ViewModels emit `UiText.Res(id, args)` or `UiText.Raw(text)`;
   Fragments resolve them. Using `R.string` ids in presentation is allowed.
5. **Singletons → injected interfaces.** `RecentFilesManager`, `Preferences`/`AppSettingsManager`,
   `SignatureManager`, `OnboardingManager`, `PdfPageBookmarksManager`, `LocalDocumentMirror`,
   `DeviceDocumentScanner`, `StorageAccess`, `GeminiSummarizer` consent → `@Singleton` repository
   implementations behind domain interfaces.
6. **`util/` + `utils/` merged** into `data/source/office/` (document readers/renderers).
7. **Errors.** Repositories return `AppResult` or throw typed `AppError`s; `BaseViewModel.launch`
   catches and maps them, so ViewModels stop hand-rolling try/catch.

## Base classes (`presentation/base/`)

**`BaseActivity<VB : ViewBinding>`** — owns `binding`, `attachBaseContext` (locale via
`LocaleHelper`), `enableEdgeToEdge()`, `setContentView(binding.root)`; hooks
`initViews(savedInstanceState)`, `observeState()`. `MainActivity` keeps only navigation/intent
handling.

**`BaseFragment<VB : ViewBinding>(inflate: (LayoutInflater, ViewGroup?, Boolean) -> VB)`** —
nullable backing binding cleared in `onDestroyView`; hooks `initViews`, `initListeners`,
`observeViewModel`; `Flow<T>.collectOnStarted { }` (`repeatOnLifecycle(STARTED)`);
`showMessage(UiText)` (Snackbar). Removes the 18 hand-written `_binding` blocks.

**`BaseToolFragment<VB>`** — extends `BaseFragment`; replaces `ToolFragment` (title bar, back,
scrolling body, system-bar/IME padding, overridable `onBackClicked()` for unsaved-changes).

**`BaseViewModel<S : UiState, E : UiEvent>(initial: S)`** — `state: StateFlow<S>`,
`events: Flow<E>` (backed by `Channel(BUFFERED)`, one-shot), `setState { copy(...) }`,
`sendEvent(e)`, `launch { }` with shared error mapping to an `onError(AppError)` hook. One-shot
messages ("Saved to…", errors shown as toasts) move from state to events so they don't replay
after rotation; errors that are part of the screen (inline error text) stay in state.

**`BaseListAdapter<T : Any, VB : ViewBinding>(diff, inflate)`** — `ListAdapter` +
`BaseViewHolder<VB>`; abstract `bind(binding, item, position)`; `simpleDiff { it.id }` helper.
Replaces `notifyDataSetChanged` adapters; the 5 inner `PagesAdapter`s move to their own files.
`HomeAdapter` keeps multiple view types but reuses the shared ViewHolder/diff helpers.

**`BaseDialog`** — shared `MaterialAlertDialogBuilder` + binding pattern for `SaveDialog`,
`SignatureDialog`, `ShareDialog`.

## Phases

| Phase | Content | Exit criteria |
|---|---|---|
| 0. Foundation | Hilt + KSP (verify versions compatible with AGP 9.0 / Kotlin 2.3 first), `ClearPdfApp`, `core/` (dispatchers, `AppResult`, `UiText`), all base classes | No screen migrated; app behaves as before |
| 1. Shared data | `DocumentRepository`, `SettingsRepository`, `RecentFilesRepository`; Hilt modules for pdf-core/ocr-core | Repositories unit-tested; old code still compiles |
| 2. Reference screens | **Compress** and **Extract Text + AI Summary** fully on the new architecture | User reviews them as the template |
| 3. Remaining tools | The other 13 tool screens, following the template | |
| 4. Main screens | Home, Tools, Settings, Onboarding, Scan; `MainActivity` on `BaseActivity` | |
| 5. Heavy screens | `PdfViewerViewModel` split into delegates (annotations, OCR, search, bookmarks); Spreadsheet; Image Editor | No file over ~400 lines |
| 6. Cleanup | Delete `PdfServiceLocator`, old singletons, `ui/`; merge `util/`+`utils/`; `ARCHITECTURE.md`; update `PLAN.md` (AI summary is opt-in network AI) | |

## Testing

- **Use cases & repositories:** JUnit with hand-written fakes (no mocking library).
- **ViewModels:** `kotlinx-coroutines-test` + Turbine; assert state and events for success and
  failure (e.g. Gemini quota exceeded, no text layer, consent declined).
- Existing 8 test files keep passing.
- Per phase: `assembleFossDebug` + `assemblePlayDebug`, `testFossDebugUnitTest`, and a manual
  smoke test on the connected device of every migrated screen's main action.

## Risks

- **Hilt on AGP 9.0 / Kotlin 2.3.** Very new toolchain; phase 0 starts by confirming a Hilt + KSP
  version that builds. If none does, stop and report before changing anything else.
- **Behaviour drift during moves.** Mitigated by small commits, one screen at a time, and a device
  smoke test per phase.
- **Merge conflicts with `feature/xml-migration`.** That branch should not receive new work while
  the refactor runs, or be merged into the refactor branch between phases.
