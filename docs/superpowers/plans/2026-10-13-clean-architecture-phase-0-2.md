# Clean Architecture Phases 0–2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put Hilt, the `core/` + `domain/` + `data/` + `presentation/base/` layers in place and move two reference screens (Compress, Extract Text + AI Summary) fully onto them, with no visible behaviour change.

**Architecture:** Package layering inside `:app`. `presentation` → `domain` (use cases, interfaces, pure models) ← `data` (implementations over `:pdf-core`, `:ocr-core`, SharedPreferences, Gemini). Hilt wires `data` to `domain`. Unmigrated screens keep working on the old `ui/` code until later phases.

**Tech Stack:** Kotlin 2.3, AGP 9.0, Gradle 9.3, Hilt 2.60.1 + KSP 2.3.12, ViewBinding/Fragments, Coroutines/Flow, JUnit 4 + kotlinx-coroutines-test + Turbine.

**Spec:** `docs/superpowers/specs/2026-10-13-clean-architecture-design.md`

## Global Constraints

- Pure refactor: layouts, strings, navigation and behaviour stay identical (exceptions listed under "Deliberate deviations").
- `domain/` imports nothing from `android.*`, `androidx.*`, `com.kyant.*`, `data/` or `presentation/`. It may import `core/result/`.
- ViewModels take no `Context`, `Uri`, or `android.*` types; they depend on use cases only.
- Fragments never import `data/`.
- `:pdf-core`, `:ocr-core`, `:office_engine` are not modified.
- pdf-core/ocr-core instances come from `PdfServiceLocator` (single shared instances) until phase 6.
- Every task ends with `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:testFossDebugUnitTest` green.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Device smoke tests use adb at `/c/Users/Thinh Cong Tua/AppData/Local/Android/Sdk/platform-tools/adb.exe` (Xiaomi `dc6c1445`); never pick a sensitive document for the AI summary test.

## Deliberate deviations from the spec (tell the user at hand-off)

- `AppResult<T>` is dropped: repositories throw typed `AppError`, `BaseViewModel.launch` maps them. One error mechanism instead of two.
- `BaseActivity` and `BaseDialog` are created in phase 4 together with their first users (`MainActivity`, `SaveDialog`/`SignatureDialog`/`ShareDialog`), instead of shipping unused in phase 0. `MainActivity` only gains `@AndroidEntryPoint` here.
- The Compress "saved" message becomes a one-shot event: same card, same 3 s auto-hide, but it no longer reappears after rotation. The Searchable PDF "saved" line keeps today's behaviour (stays in state, survives rotation, no auto-hide).

## Review Focus

1. **Two PdfViewer instances.** `PdfViewerImpl` serializes page access with its own lock; if Hilt built a second instance, migrated and unmigrated screens could render the same file concurrently and crash. Expectation: one instance app-wide. Pinned by Task 5 Step 3 (providers delegate to `PdfServiceLocator`) and checked in its review step.
2. **AI mode + consent declined.** User opens AI Summary, declines consent. Expectation: no dialog loop, no auto-retry for that file; tapping "Summarize with AI" asks again. Test in Task 10.
3. **AI mode on a scanned PDF.** Text only appears after OCR. Expectation (current behaviour): summary starts automatically once OCR text arrives. Test in Task 10.
4. **Gemini quota / offline.** Expectation: specific quota message on 429, raw failure reason otherwise, button usable again. Tests in Task 10.
5. **Save folder no longer writable.** Custom SAF folder revoked. Expectation: output silently falls back to Downloads (today's behaviour). Covered by code in Task 4 Step 2 and the device smoke test in Task 7 (verify output appears; revocation itself is checked manually only if the user has a custom folder set).

---

## File Structure

```
app/src/main/java/com/chethan616/clearpdf/
  ClearPdfApp.kt                                  @HiltAndroidApp
  core/di/DispatchersModule.kt                    dispatcher providers
  core/di/PdfCoreModule.kt                        pdf-core/ocr-core providers (delegate to PdfServiceLocator)
  core/di/RepositoryModule.kt                     @Binds data → domain
  core/dispatchers/Dispatchers.kt                 @IoDispatcher @DefaultDispatcher @PdfDispatcher
  core/platform/DocumentRefUri.kt                 DocumentRef <-> android.net.Uri (used by data + presentation)
  core/result/AppError.kt                         typed errors + runCatchingCancellable
  domain/model/Document.kt                        DocumentRef, DocumentInfo, OutputDocument
  domain/model/CompressionLevel.kt
  domain/model/OcrWord.kt
  domain/model/Summary.kt                         Summary, SummaryAvailability
  domain/repository/*.kt                          7 interfaces
  domain/usecase/document/OpenDocumentUseCase.kt
  domain/usecase/compress/*.kt                    Prepare/Estimate/CompressPdf
  domain/usecase/text/*.kt                        ExtractText/RecognizeText/MakeSearchablePdf
  domain/usecase/summary/*.kt                     SummarizeText/GetSummaryAvailability/GrantSummaryConsent
  data/mapper/PdfCoreMappers.kt
  data/repository/*Impl.kt                        7 implementations
  data/source/remote/GeminiApi.kt
  presentation/base/BaseViewModel.kt              + UiState/UiEvent markers
  presentation/base/BaseFragment.kt
  presentation/base/BaseToolFragment.kt
  presentation/base/BaseListAdapter.kt            + BaseViewHolder, simpleDiff
  presentation/common/UiText.kt
  presentation/feature/tool/compress/CompressPdfFragment.kt, CompressPdfViewModel.kt, CompressPdfContract.kt
  presentation/feature/tool/extracttext/ExtractTextFragment.kt, ExtractTextViewModel.kt, ExtractTextContract.kt
Deleted: ui/viewmodel/CompressPdfViewModel.kt, ui/fragment/tool/CompressPdfFragment.kt,
         domain/usecase/CompressPdfUseCase.kt, ui/viewmodel/ExtractTextViewModel.kt,
         ui/fragment/tool/ExtractTextFragment.kt, data/ai/GeminiSummarizer.kt
Modified: ui/fragment/tool/ImageGridAdapter.kt (BaseListAdapter), ui/fragment/tools/ToolsFragment.kt,
          MainActivity.kt (@AndroidEntryPoint), AndroidManifest.xml, nav_graph.xml, build files
app/src/test/java/com/chethan616/clearpdf/
  testing/MainDispatcherRule.kt, testing/Fakes.kt
  presentation/base/BaseViewModelTest.kt
  domain/model/CompressionLevelTest.kt
  domain/usecase/compress/CompressUseCasesTest.kt
  domain/usecase/text/TextUseCasesTest.kt
  domain/usecase/summary/SummarizeTextUseCaseTest.kt
  presentation/feature/tool/compress/CompressPdfViewModelTest.kt
  presentation/feature/tool/extracttext/ExtractTextViewModelTest.kt
```

Paths below are relative to the repo root; `$PKG` = `app/src/main/java/com/chethan616/clearpdf`, `$TEST` = `app/src/test/java/com/chethan616/clearpdf`.

---

### Task 1: Hilt + KSP + test libraries

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml:35`, `$PKG/MainActivity.kt:33`
- Create: `$PKG/ClearPdfApp.kt`

**Interfaces:**
- Produces: Hilt graph rooted at `ClearPdfApp`; `MainActivity` is `@AndroidEntryPoint` so any hosted Fragment may be `@AndroidEntryPoint`. Test deps `kotlinx-coroutines-test`, `turbine`.

- [ ] **Step 1: Version catalog.** In `gradle/libs.versions.toml` add under `[versions]`:

```toml
hilt = "2.60.1"
ksp = "2.3.12"
turbine = "1.2.1"
```

under `[libraries]`:

```toml
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
```

under `[plugins]`:

```toml
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: Root build.** In `build.gradle.kts` `plugins { }` add:

```kotlin
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
```

- [ ] **Step 3: App build.** In `app/build.gradle.kts` `plugins { }` add `alias(libs.plugins.ksp)` and `alias(libs.plugins.hilt)`. In `dependencies { }` add:

```kotlin
    // Dependency injection (see core/di).
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
```

- [ ] **Step 4: Application class.** Create `$PKG/ClearPdfApp.kt`:

```kotlin
package com.chethan616.clearpdf

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Root of the Hilt dependency graph. */
@HiltAndroidApp
class ClearPdfApp : Application()
```

In `app/src/main/AndroidManifest.xml` add `android:name=".ClearPdfApp"` as the first attribute of `<application`.

- [ ] **Step 5: Entry point.** In `$PKG/MainActivity.kt` add `import dagger.hilt.android.AndroidEntryPoint` and annotate `class MainActivity` with `@AndroidEntryPoint`.

- [ ] **Step 6: Build.** Run `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:testFossDebugUnitTest --console=plain -q`. Expected: exit 0. **If Hilt or KSP fails to configure with AGP 9 built-in Kotlin, stop and report the exact error to the user (spec risk); do not try other DI approaches.**

- [ ] **Step 7: Device smoke.** `./gradlew :app:installFossDebug -Pandroid.injected.build.abi=arm64-v8a`, launch, open Home, Tools, Settings, one tool. Expected: no crash (check `adb logcat -d -s AndroidRuntime:E` is empty).

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/chethan616/clearpdf/ClearPdfApp.kt app/src/main/java/com/chethan616/clearpdf/MainActivity.kt
git commit -m "build: add Hilt, KSP and coroutine test libraries"
```

---

### Task 2: core/ + BaseViewModel + UiText

**Files:**
- Create: `$PKG/core/dispatchers/Dispatchers.kt`, `$PKG/core/di/DispatchersModule.kt`, `$PKG/core/result/AppError.kt`, `$PKG/presentation/common/UiText.kt`, `$PKG/presentation/base/BaseViewModel.kt`
- Test: `$TEST/testing/MainDispatcherRule.kt`, `$TEST/presentation/base/BaseViewModelTest.kt`

**Interfaces:**
- Produces:
  - `@IoDispatcher`, `@DefaultDispatcher`, `@PdfDispatcher` qualifiers on `CoroutineDispatcher`.
  - `sealed class AppError : Exception` with `Storage(cause)`, `Network(cause)`, `QuotaExceeded`, `Remote(message)`, `Unknown(cause)`; `AppError.from(Throwable)`; `inline fun <T> runCatchingCancellable(block: () -> T): Result<T>`.
  - `sealed interface UiText { Raw(value); Res(id, args) }`, `UiText.res(id, vararg args)`, `UiText.resolve(context)`.
  - `interface UiState`, `interface UiEvent`, `abstract class BaseViewModel<S : UiState, E : UiEvent>(initialState: S)` with `state: StateFlow<S>`, `events: Flow<E>`, protected `currentState`, `setState { }`, `sendEvent(e)`, `launch(onError = ::onError) { }`, `open fun onError(AppError)`.
  - Test util `MainDispatcherRule`.

- [ ] **Step 1: Write the failing test.** Create `$TEST/testing/MainDispatcherRule.kt`:

```kotlin
package com.chethan616.clearpdf.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Routes Dispatchers.Main (and so viewModelScope) to a test dispatcher. */
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

Create `$TEST/presentation/base/BaseViewModelTest.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.base

import app.cash.turbine.test
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class BaseViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private data class CounterState(val count: Int = 0) : UiState
    private data class Toast(val text: String) : UiEvent

    private class CounterViewModel : BaseViewModel<CounterState, Toast>(CounterState()) {
        var lastError: AppError? = null
        val gate = CompletableDeferred<Unit>()
        fun increment() = setState { copy(count = count + 1) }
        fun toast(text: String) = sendEvent(Toast(text))
        fun fail(t: Throwable) = launch { throw t }
        fun waitThenIncrement() = launch { gate.await(); increment() }
        override fun onError(error: AppError) { lastError = error }
    }

    @Test fun setState_reducesCurrentState() {
        val vm = CounterViewModel()
        vm.increment(); vm.increment()
        assertEquals(2, vm.state.value.count)
    }

    @Test fun events_areDeliveredOnce() = runTest {
        val vm = CounterViewModel()
        vm.toast("saved")
        vm.events.test {
            assertEquals(Toast("saved"), awaitItem())
            expectNoEvents()
        }
    }

    @Test fun launch_mapsThrowablesToAppError() {
        val vm = CounterViewModel()
        vm.fail(IOException("disk"))
        assertTrue(vm.lastError is AppError.Unknown)
        assertEquals("disk", vm.lastError?.message)
    }

    @Test fun launch_keepsTypedAppErrors() {
        val vm = CounterViewModel()
        vm.fail(AppError.QuotaExceeded())
        assertTrue(vm.lastError is AppError.QuotaExceeded)
    }

    @Test fun launch_runsSuspendingWork() {
        val vm = CounterViewModel()
        vm.waitThenIncrement()
        assertEquals(0, vm.state.value.count)
        vm.gate.complete(Unit)
        assertEquals(1, vm.state.value.count)
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testFossDebugUnitTest --tests "*BaseViewModelTest" --console=plain -q`. Expected: compilation failure (`BaseViewModel` unresolved).

- [ ] **Step 3: Implement.** Create `$PKG/core/dispatchers/Dispatchers.kt`:

```kotlin
package com.chethan616.clearpdf.core.dispatchers

import javax.inject.Qualifier

/** Dispatchers.IO: file-system queries, preferences, network. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher

/** Dispatchers.Default: CPU-bound work. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher

/** Bounded pool for heavy PDF rendering / compression (see AppDispatchers.pdf). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PdfDispatcher
```

Create `$PKG/core/di/DispatchersModule.kt`:

```kotlin
package com.chethan616.clearpdf.core.di

import com.chethan616.clearpdf.core.dispatchers.DefaultDispatcher
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.core.dispatchers.PdfDispatcher
import com.chethan616.clearpdf.ui.utils.AppDispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @IoDispatcher fun io(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun default(): CoroutineDispatcher = Dispatchers.Default
    // The same pool the not-yet-migrated screens use, so PDF work stays bounded app-wide.
    @Provides @PdfDispatcher fun pdf(): CoroutineDispatcher = AppDispatchers.pdf
}
```

Create `$PKG/core/result/AppError.kt`:

```kotlin
package com.chethan616.clearpdf.core.result

import kotlin.coroutines.cancellation.CancellationException

/** Failures the app knows how to describe. Repositories throw these; BaseViewModel maps the rest. */
sealed class AppError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class Storage(cause: Throwable? = null) : AppError(cause?.message, cause)
    class Network(cause: Throwable? = null) : AppError(cause?.message, cause)
    /** The free Gemini quota is used up (HTTP 429). */
    class QuotaExceeded : AppError("Free quota exceeded")
    /** A remote service answered with an error it described. */
    class Remote(message: String) : AppError(message)
    class Unknown(cause: Throwable) : AppError(cause.message, cause)

    companion object {
        fun from(t: Throwable): AppError = t as? AppError ?: Unknown(t)
    }
}

/** [runCatching] that lets coroutine cancellation propagate instead of swallowing it. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Result.failure(t)
}
```

Create `$PKG/presentation/common/UiText.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.common

import android.content.Context
import androidx.annotation.StringRes

/** Text a ViewModel hands to the UI without needing a Context. */
sealed interface UiText {
    data class Raw(val value: String) : UiText
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    fun resolve(context: Context): String = when (this) {
        is Raw -> value
        is Res -> context.getString(id, *args.toTypedArray())
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}
```

Create `$PKG/presentation/base/BaseViewModel.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chethan616.clearpdf.core.result.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Marker for a screen's immutable state. */
interface UiState

/** Marker for a one-shot effect (message, dialog, navigation) that must not replay. */
interface UiEvent

/**
 * Holds a screen's [state] and one-shot [events]. Subclasses change state only through
 * [setState] and run work through [launch], which turns failures into [AppError]s.
 */
abstract class BaseViewModel<S : UiState, E : UiEvent>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _events = Channel<E>(Channel.BUFFERED)
    val events: Flow<E> = _events.receiveAsFlow()

    protected val currentState: S get() = _state.value

    protected fun setState(reduce: S.() -> S) = _state.update(reduce)

    protected fun sendEvent(event: E) {
        _events.trySend(event)
    }

    protected fun launch(
        onError: (AppError) -> Unit = ::onError,
        block: suspend CoroutineScope.() -> Unit
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onError(AppError.from(t))
        }
    }

    /** Fallback for [launch] calls that don't pass their own handler. */
    protected open fun onError(error: AppError) {}
}
```

- [ ] **Step 4: Run tests.** `./gradlew :app:testFossDebugUnitTest --tests "*BaseViewModelTest" --console=plain -q`. Expected: PASS (5 tests).

- [ ] **Step 5: Full build.** `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:testFossDebugUnitTest --console=plain -q`. Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/core app/src/main/java/com/chethan616/clearpdf/presentation app/src/test/java/com/chethan616/clearpdf/testing app/src/test/java/com/chethan616/clearpdf/presentation
git commit -m "feat(arch): core dispatchers/errors, UiText and BaseViewModel"
```

---

### Task 3: BaseFragment, BaseToolFragment, BaseListAdapter (+ first adapter)

**Files:**
- Create: `$PKG/presentation/base/BaseFragment.kt`, `$PKG/presentation/base/BaseToolFragment.kt`, `$PKG/presentation/base/BaseListAdapter.kt`
- Modify: `$PKG/ui/fragment/tool/ImageGridAdapter.kt` (whole file)

**Interfaces:**
- Consumes: `UiText` (Task 2).
- Produces:
  - `abstract class BaseFragment<VB : ViewBinding>(inflate: (LayoutInflater, ViewGroup?, Boolean) -> VB)`: `protected val binding`, hooks `initViews(savedInstanceState)`, `initListeners()`, `observeViewModel()`, `protected fun <T> Flow<T>.collectOnStarted(block)`, `protected fun showMessage(text: UiText)`.
  - `abstract class BaseToolFragment<B : ViewBinding>(inflateBody: (LayoutInflater, ViewGroup?, Boolean) -> B) : BaseFragment<FragmentToolBinding>`: `protected val body: B`, `abstract val titleRes: Int`, `open fun onBackClicked()`, `protected fun showStatus(status: ViewToolStatusBinding, text: UiText, isError: Boolean)`, `protected fun viewOutput(uri: Uri)`.
  - `class BaseViewHolder<VB : ViewBinding>(val binding: VB)`, `abstract class BaseListAdapter<T : Any, VB : ViewBinding>(inflate, diff)` with `abstract fun bind(binding, item, position)`, `open fun onViewHolderCreated(holder)`; `fun <T : Any, K> simpleDiff(key: (T) -> K): DiffUtil.ItemCallback<T>`.

These classes are Android view code; they are verified by the build, the Images-to-PDF smoke test here, and the reference screens in Tasks 7 and 11.

- [ ] **Step 1: BaseFragment.** Create `$PKG/presentation/base/BaseFragment.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.chethan616.clearpdf.presentation.common.UiText
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Owns a ViewBinding for the view's lifetime (cleared in onDestroyView to avoid leaking the
 * view hierarchy) and splits setup into [initViews], [initListeners] and [observeViewModel].
 */
abstract class BaseFragment<VB : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup?, Boolean) -> VB
) : Fragment() {

    private var _binding: VB? = null
    protected val binding: VB
        get() = checkNotNull(_binding) { "Binding used outside the fragment's view lifecycle" }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViews(savedInstanceState)
        initListeners()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    protected open fun initViews(savedInstanceState: Bundle?) {}

    protected open fun initListeners() {}

    protected open fun observeViewModel() {}

    /** Collects while the view is at least STARTED; stops in the background, restarts on return. */
    protected fun <T> Flow<T>.collectOnStarted(block: suspend (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { block(it) }
            }
        }
    }

    protected fun showMessage(text: UiText) {
        Snackbar.make(binding.root, text.resolve(requireContext()), Snackbar.LENGTH_LONG).show()
    }
}
```

- [ ] **Step 2: BaseToolFragment.** Create `$PKG/presentation/base/BaseToolFragment.kt` (same behaviour as `ui/fragment/tool/ToolFragment.kt`, which stays for unmigrated tools until phase 6):

```kotlin
package com.chethan616.clearpdf.presentation.base

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.viewbinding.ViewBinding
import com.chethan616.clearpdf.databinding.FragmentToolBinding
import com.chethan616.clearpdf.databinding.ViewToolStatusBinding
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Base for every tool screen: a back + title bar over a scrolling column. Subclasses pass their
 * body layout's inflate function and bind it through [body].
 */
abstract class BaseToolFragment<B : ViewBinding>(
    private val inflateBody: (LayoutInflater, ViewGroup?, Boolean) -> B
) : BaseFragment<FragmentToolBinding>(FragmentToolBinding::inflate) {

    private var _body: B? = null
    protected val body: B
        get() = checkNotNull(_body) { "Body used outside the fragment's view lifecycle" }

    @get:StringRes
    protected abstract val titleRes: Int

    private var statusJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        _body = inflateBody(inflater, binding.content, false)
        if (body.root.parent == null) binding.content.addView(body.root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applySystemBarPadding(top = true)
        binding.scroll.applySystemBarPadding(bottom = true, imeBottom = true)
        binding.toolbar.setTitle(titleRes)
        binding.toolbar.setNavigationOnClickListener { onBackClicked() }
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroyView() {
        _body = null
        super.onDestroyView()
    }

    /** Back arrow in the bar. Screens with unsaved work override this to confirm first. */
    protected open fun onBackClicked() {
        findNavController().popBackStack()
    }

    /** Shows a success/error card that hides itself after 3 s or when dismissed. */
    protected fun showStatus(status: ViewToolStatusBinding, text: UiText, isError: Boolean) {
        statusJob?.cancel()
        val hide = { ToolUi.status(status, null, isError = false) }
        ToolUi.status(status, text.resolve(requireContext()), isError, onDismiss = hide)
        statusJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(STATUS_VISIBLE_MS)
            hide()
        }
    }

    /** Opens a tool's output in the PDF viewer. */
    protected fun viewOutput(uri: Uri) = AppNavigator.openPdfViewer(findNavController(), uri)

    private companion object {
        const val STATUS_VISIBLE_MS = 3_000L
    }
}
```

- [ ] **Step 3: BaseListAdapter.** Create `$PKG/presentation/base/BaseListAdapter.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.base

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

class BaseViewHolder<VB : ViewBinding>(val binding: VB) : RecyclerView.ViewHolder(binding.root)

/**
 * A [ListAdapter] over one ViewBinding item layout. Lists are diffed on a background thread, so
 * only changed rows rebind (no notifyDataSetChanged). Wire click listeners once in
 * [onViewHolderCreated], bind data in [bind].
 */
abstract class BaseListAdapter<T : Any, VB : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
    diff: DiffUtil.ItemCallback<T>
) : ListAdapter<T, BaseViewHolder<VB>>(diff) {

    final override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder<VB> =
        BaseViewHolder(inflate(LayoutInflater.from(parent.context), parent, false)).also(::onViewHolderCreated)

    final override fun onBindViewHolder(holder: BaseViewHolder<VB>, position: Int) =
        bind(holder.binding, getItem(position), position)

    protected open fun onViewHolderCreated(holder: BaseViewHolder<VB>) {}

    protected abstract fun bind(binding: VB, item: T, position: Int)
}

/** Diff callback that treats items with the same [key] as the same row and compares the rest by equals. */
fun <T : Any, K> simpleDiff(key: (T) -> K): DiffUtil.ItemCallback<T> = object : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T) = key(oldItem) == key(newItem)

    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(oldItem: T, newItem: T) = oldItem == newItem
}
```

- [ ] **Step 4: First adapter on the base.** Replace `$PKG/ui/fragment/tool/ImageGridAdapter.kt` with (public API `ImageGridAdapter(onRemove)` + `submit(List<Uri>)` unchanged, so `ImagesToPdfFragment` needs no edit; the index lives in the item so "Image N" labels rebind after a removal):

```kotlin
package com.chethan616.clearpdf.ui.fragment.tool

import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ItemImageThumbBinding
import com.chethan616.clearpdf.presentation.base.BaseListAdapter
import com.chethan616.clearpdf.presentation.base.BaseViewHolder
import com.chethan616.clearpdf.presentation.base.simpleDiff

/** Grid of picked images; the corner button removes one. */
class ImageGridAdapter(private val onRemove: (Int) -> Unit) :
    BaseListAdapter<ImageGridAdapter.Item, ItemImageThumbBinding>(ItemImageThumbBinding::inflate, simpleDiff { it.uri }) {

    data class Item(val index: Int, val uri: Uri)

    fun submit(uris: List<Uri>) = submitList(uris.mapIndexed(::Item))

    override fun onViewHolderCreated(holder: BaseViewHolder<ItemImageThumbBinding>) {
        holder.binding.remove.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRemove(pos)
        }
    }

    override fun bind(binding: ItemImageThumbBinding, item: Item, position: Int) {
        binding.image.load(item.uri)
        binding.image.contentDescription = binding.root.context.getString(R.string.image_number, item.index + 1)
    }
}
```

- [ ] **Step 5: Build.** `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:testFossDebugUnitTest --console=plain -q`. Expected: exit 0.

- [ ] **Step 6: Device smoke.** Install fossDebug, open Tools → Images to PDF, add 3 images, remove the middle one. Expected: 2 thumbnails remain, the remaining images are labelled "Image 1"/"Image 2" (TalkBack content description; check with `adb shell uiautomator dump` + grep `Image 2`), no crash.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/presentation/base app/src/main/java/com/chethan616/clearpdf/ui/fragment/tool/ImageGridAdapter.kt
git commit -m "feat(arch): BaseFragment, BaseToolFragment and BaseListAdapter"
```

---

### Task 4: Domain models, repository interfaces, document/settings/recents data

**Files:**
- Create: `$PKG/domain/model/Document.kt`, `$PKG/domain/model/CompressionLevel.kt`, `$PKG/domain/model/OcrWord.kt`, `$PKG/domain/model/Summary.kt`
- Create: `$PKG/domain/repository/DocumentRepository.kt`, `SettingsRepository.kt`, `RecentFilesRepository.kt`, `EngagementRepository.kt`, `PdfRepository.kt`, `OcrRepository.kt`, `SummaryRepository.kt`
- Create: `$PKG/core/platform/DocumentRefUri.kt`
- Create: `$PKG/data/repository/DocumentRepositoryImpl.kt`, `SettingsRepositoryImpl.kt`, `RecentFilesRepositoryImpl.kt`, `EngagementRepositoryImpl.kt`
- Test: `$TEST/domain/model/CompressionLevelTest.kt`

**Interfaces:**
- Produces (domain, pure Kotlin):

```kotlin
@JvmInline value class DocumentRef(val value: String)
data class DocumentInfo(val ref: DocumentRef, val name: String, val sizeBytes: Long)
data class OutputDocument(val ref: DocumentRef, val name: String, val locationLabel: String)
enum class CompressionLevel { LOW, MEDIUM, HIGH } // + fraction, companion fromFraction(Float)
data class OcrWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class Summary(val text: String, val truncated: Boolean)
enum class SummaryAvailability { UNAVAILABLE, NEEDS_CONSENT, READY }

interface DocumentRepository {
    suspend fun open(ref: DocumentRef): DocumentInfo
    suspend fun createOutput(prefix: String, extension: String, mimeType: String): OutputDocument
}
interface SettingsRepository { fun defaultCompressionFraction(): Float }
interface RecentFilesRepository { suspend fun add(document: OutputDocument, sizeBytes: Long) }
interface EngagementRepository { suspend fun recordPdfInteraction() }
interface PdfRepository {
    suspend fun estimateCompressedSize(source: DocumentInfo, level: CompressionLevel): Long
    suspend fun compress(source: DocumentInfo, level: CompressionLevel, output: OutputDocument): Long
    suspend fun extractText(source: DocumentInfo): String
    suspend fun pageCount(source: DocumentInfo): Int
    suspend fun stampSearchableText(source: DocumentInfo, output: OutputDocument, wordsByPage: Map<Int, List<OcrWord>>)
}
interface OcrRepository { suspend fun recognizePage(source: DocumentInfo, pageIndex: Int): List<OcrWord> }
interface SummaryRepository {
    fun availability(): SummaryAvailability
    fun grantConsent()
    suspend fun summarize(text: String): String
}
```

- Produces (platform): `fun Uri.toDocumentRef(): DocumentRef`, `fun DocumentRef.toUri(): Uri`.

- [ ] **Step 1: Write the failing test.** Create `$TEST/domain/model/CompressionLevelTest.kt`:

```kotlin
package com.chethan616.clearpdf.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CompressionLevelTest {
    @Test fun fromFraction_usesSliderThresholds() {
        assertEquals(CompressionLevel.LOW, CompressionLevel.fromFraction(0f))
        assertEquals(CompressionLevel.LOW, CompressionLevel.fromFraction(0.329f))
        assertEquals(CompressionLevel.MEDIUM, CompressionLevel.fromFraction(0.33f))
        assertEquals(CompressionLevel.MEDIUM, CompressionLevel.fromFraction(0.659f))
        assertEquals(CompressionLevel.HIGH, CompressionLevel.fromFraction(0.66f))
        assertEquals(CompressionLevel.HIGH, CompressionLevel.fromFraction(1f))
    }

    @Test fun fraction_roundTripsToSameLevel() {
        CompressionLevel.entries.forEach { assertEquals(it, CompressionLevel.fromFraction(it.fraction)) }
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testFossDebugUnitTest --tests "*CompressionLevelTest" --console=plain -q`. Expected: compilation failure.

- [ ] **Step 3: Domain models.** Create `$PKG/domain/model/Document.kt`:

```kotlin
package com.chethan616.clearpdf.domain.model

/** Opaque reference to a document (a content/file URI string at the data layer). */
@JvmInline
value class DocumentRef(val value: String)

data class DocumentInfo(
    val ref: DocumentRef,
    val name: String,
    /** -1 when the provider doesn't report a size. */
    val sizeBytes: Long
)

/** A newly created, still-empty output file and where the user will find it. */
data class OutputDocument(
    val ref: DocumentRef,
    val name: String,
    /** "Downloads (default)" or the user's chosen folder, as shown in messages. */
    val locationLabel: String
)
```

Create `$PKG/domain/model/CompressionLevel.kt`:

```kotlin
package com.chethan616.clearpdf.domain.model

enum class CompressionLevel(
    /** Slider position this level snaps to when chosen directly. */
    val fraction: Float
) {
    LOW(0.15f),
    MEDIUM(0.5f),
    HIGH(0.85f);

    companion object {
        /** Maps a 0..1 quality slider (or the Default Quality setting) to a level. */
        fun fromFraction(fraction: Float): CompressionLevel = when {
            fraction < 0.33f -> LOW
            fraction < 0.66f -> MEDIUM
            else -> HIGH
        }
    }
}
```

Create `$PKG/domain/model/OcrWord.kt`:

```kotlin
package com.chethan616.clearpdf.domain.model

/** A recognized word with its box normalized to the page (0..1). */
data class OcrWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
```

Create `$PKG/domain/model/Summary.kt`:

```kotlin
package com.chethan616.clearpdf.domain.model

data class Summary(
    val text: String,
    /** True when only the first part of a long document was summarized. */
    val truncated: Boolean
)

enum class SummaryAvailability {
    /** No API key in this build or no network permission: hide the feature. */
    UNAVAILABLE,
    /** Available, but the user hasn't agreed to send text to Gemini yet. */
    NEEDS_CONSENT,
    READY
}
```

- [ ] **Step 4: Repository interfaces.** Create one file per interface in `$PKG/domain/repository/`, each `package com.chethan616.clearpdf.domain.repository` importing the models it uses, with exactly the signatures in the Interfaces block above. Add a one-line KDoc to each:
  - `DocumentRepository` — "Reads document metadata and creates output files in the user's save location." Document `open`: "Keeps read access across restarts and returns name ("Unknown.pdf" if the provider gives none) and size." Document `createOutput`: "Creates `<prefix>_<yyyyMMdd_HHmmss>.<extension>` in the chosen folder, falling back to Downloads."
  - `SettingsRepository` — "User preferences used by tools." `defaultCompressionFraction`: "Default Quality setting, 0..1."
  - `RecentFilesRepository` — "Recent files list on Home."
  - `EngagementRepository` — "Counts completed PDF actions for the GitHub star prompt."
  - `PdfRepository` — "PDF operations backed by :pdf-core." `compress` returns "size of the written file in bytes". `pageCount`: "0 when the file can't be opened as a PDF".
  - `OcrRepository` — "On-device text recognition of rendered PDF pages." `recognizePage`: "Empty when the page can't be rendered."
  - `SummaryRepository` — "AI summary via Gemini. Throws AppError.QuotaExceeded / Network / Remote."

- [ ] **Step 5: Run the model test.** `./gradlew :app:testFossDebugUnitTest --tests "*CompressionLevelTest" --console=plain -q`. Expected: PASS.

- [ ] **Step 6: Uri mapping.** Create `$PKG/core/platform/DocumentRefUri.kt`:

```kotlin
package com.chethan616.clearpdf.core.platform

import android.net.Uri
import com.chethan616.clearpdf.domain.model.DocumentRef

/** The only place DocumentRef meets android.net.Uri; used at the data and view boundaries. */
fun Uri.toDocumentRef(): DocumentRef = DocumentRef(toString())

fun DocumentRef.toUri(): Uri = Uri.parse(value)
```

- [ ] **Step 7: DocumentRepositoryImpl.** Create `$PKG/data/repository/DocumentRepositoryImpl.kt`. This is the single home of the logic duplicated today in 13 ViewModels (`createOutputUri`/`createDownloadUri`) and 6 (`queryFileName`); copy it from `ui/viewmodel/CompressPdfViewModel.kt` with the MIME type parameterized:

```kotlin
package com.chethan616.clearpdf.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.core.platform.toDocumentRef
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : DocumentRepository {

    override suspend fun open(ref: DocumentRef): DocumentInfo = withContext(io) {
        val uri = ref.toUri()
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        DocumentInfo(ref = ref, name = displayName(uri) ?: DEFAULT_NAME, sizeBytes = size(uri))
    }

    override suspend fun createOutput(prefix: String, extension: String, mimeType: String): OutputDocument =
        withContext(io) {
            val name = "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$extension"
            val uri = try {
                createInSaveLocation(name, mimeType) ?: createInDownloads(name, mimeType)
            } catch (e: Exception) {
                throw AppError.Storage(e)
            }
            OutputDocument(uri.toDocumentRef(), name, SaveLocationManager.getSavePathDisplay(context))
        }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun size(uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull() ?: -1L

    /** The user's chosen folder; null (→ Downloads) when unset or no longer writable. */
    private fun createInSaveLocation(name: String, mimeType: String): Uri? {
        val tree = SaveLocationManager.getSaveUri(context) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, tree)?.createFile(mimeType, name)?.uri }.getOrNull()
    }

    private fun createInDownloads(name: String, mimeType: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create output in Downloads")
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, name).apply { if (!exists()) createNewFile() }
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }

    private companion object {
        const val DEFAULT_NAME = "Unknown.pdf"
    }
}
```

- [ ] **Step 8: Settings, recents, engagement.** Create `$PKG/data/repository/SettingsRepositoryImpl.kt`:

```kotlin
package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Reads the same "clearpdf_settings" preferences as AppSettingsManager. */
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {
    override fun defaultCompressionFraction(): Float = AppSettingsManager.getDefaultQuality(context)
}
```

Create `$PKG/data/repository/RecentFilesRepositoryImpl.kt`:

```kotlin
package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RecentFilesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : RecentFilesRepository {
    override suspend fun add(document: OutputDocument, sizeBytes: Long): Unit = withContext(io) {
        RecentFilesManager.addRecent(
            context,
            RecentFile(
                name = document.name,
                uriString = document.ref.value,
                timestamp = System.currentTimeMillis(),
                sizeBytes = sizeBytes
            )
        )
    }
}
```

Create `$PKG/data/repository/EngagementRepositoryImpl.kt`:

```kotlin
package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.domain.repository.EngagementRepository
import com.chethan616.clearpdf.ui.utils.StarPromptEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class EngagementRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : EngagementRepository {
    // MainActivity listens on StarPromptEventBus; it moves behind an interface with MainActivity in phase 4.
    override suspend fun recordPdfInteraction() {
        val showPrompt = withContext(io) { GitHubStarPromptManager.recordPdfInteraction(context) }
        if (showPrompt) StarPromptEventBus.requestPrompt()
    }
}
```

- [ ] **Step 9: Build + tests.** `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:testFossDebugUnitTest --console=plain -q`. Expected: exit 0 (implementations aren't bound yet; Task 5 binds them).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/domain/model app/src/main/java/com/chethan616/clearpdf/domain/repository app/src/main/java/com/chethan616/clearpdf/core/platform app/src/main/java/com/chethan616/clearpdf/data/repository/*RepositoryImpl.kt app/src/test/java/com/chethan616/clearpdf/domain
git commit -m "feat(arch): domain models and repository interfaces; document, settings and recents data"
```

---

### Task 5: PDF, OCR and summary data + Hilt bindings

**Files:**
- Create: `$PKG/data/mapper/PdfCoreMappers.kt`, `$PKG/data/repository/PdfRepositoryImpl.kt`, `$PKG/data/repository/OcrRepositoryImpl.kt`, `$PKG/data/source/remote/GeminiApi.kt`, `$PKG/data/repository/SummaryRepositoryImpl.kt`, `$PKG/core/di/PdfCoreModule.kt`, `$PKG/core/di/RepositoryModule.kt`

**Interfaces:**
- Consumes: domain interfaces and models (Task 4), qualifiers + `AppError` (Task 2).
- Produces: Hilt bindings for all 7 domain repositories; `GeminiApi.generate(systemPrompt: String, text: String): String`.

- [ ] **Step 1: Mappers.** Create `$PKG/data/mapper/PdfCoreMappers.kt`:

```kotlin
package com.chethan616.clearpdf.data.mapper

import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.kyant.pdfcore.model.CompressionQuality
import com.kyant.pdfcore.model.PdfDocument
import com.kyant.pdfcore.searchable.InvisibleWord
import com.kyant.ocrcore.OcrWord as CoreOcrWord

fun DocumentInfo.toPdfDocument() = PdfDocument(uri = ref.toUri(), name = name, sizeBytes = sizeBytes)

fun CompressionLevel.toQuality(): CompressionQuality = when (this) {
    CompressionLevel.LOW -> CompressionQuality.LOW
    CompressionLevel.MEDIUM -> CompressionQuality.MEDIUM
    CompressionLevel.HIGH -> CompressionQuality.HIGH
}

fun CoreOcrWord.toDomain() = OcrWord(text, left, top, right, bottom)

fun OcrWord.toInvisibleWord() = InvisibleWord(text, left, top, right, bottom)
```

- [ ] **Step 2: PdfRepositoryImpl and OcrRepositoryImpl.** Create `$PKG/data/repository/PdfRepositoryImpl.kt`:

```kotlin
package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.PdfDispatcher
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.data.mapper.toInvisibleWord
import com.chethan616.clearpdf.data.mapper.toPdfDocument
import com.chethan616.clearpdf.data.mapper.toQuality
import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.kyant.pdfcore.compressor.PdfCompressor
import com.kyant.pdfcore.converter.PdfConverter
import com.kyant.pdfcore.searchable.PdfSearchableStamper
import com.kyant.pdfcore.viewer.PdfViewer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PdfRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val compressor: PdfCompressor,
    private val converter: PdfConverter,
    private val viewer: PdfViewer,
    @PdfDispatcher private val pdf: CoroutineDispatcher
) : PdfRepository {

    override suspend fun estimateCompressedSize(source: DocumentInfo, level: CompressionLevel): Long =
        withContext(pdf) { compressor.estimateSize(source.toPdfDocument(), level.toQuality()) }

    override suspend fun compress(source: DocumentInfo, level: CompressionLevel, output: OutputDocument): Long =
        withContext(pdf) {
            compressor.compress(context, source.toPdfDocument(), level.toQuality(), output.ref.toUri()).sizeBytes
        }

    override suspend fun extractText(source: DocumentInfo): String =
        withContext(pdf) { converter.extractText(context, source.toPdfDocument()) }

    override suspend fun pageCount(source: DocumentInfo): Int = withContext(pdf) {
        val document = runCatching { viewer.open(context, source.ref.toUri()) }.getOrNull() ?: return@withContext 0
        try {
            document.pageCount.coerceAtLeast(0)
        } finally {
            runCatching { viewer.close(document) }
        }
    }

    override suspend fun stampSearchableText(
        source: DocumentInfo,
        output: OutputDocument,
        wordsByPage: Map<Int, List<OcrWord>>
    ) {
        withContext(pdf) {
            PdfSearchableStamper.stamp(
                context,
                source.ref.toUri(),
                output.ref.toUri(),
                wordsByPage.mapValues { (_, words) -> words.map { it.toInvisibleWord() } }
            )
        }
    }
}
```

Check `PdfSearchableStamper.stamp`'s fourth parameter type in `pdf-core/src/main/java/com/kyant/pdfcore/searchable/PdfSearchableStamper.kt:29` before compiling; it is `Map<Int, List<InvisibleWord>>` in today's caller (`ExtractTextViewModel.makeSearchablePdf`).

Create `$PKG/data/repository/OcrRepositoryImpl.kt`:

```kotlin
package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.PdfDispatcher
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.data.mapper.toDomain
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.repository.OcrRepository
import com.kyant.ocrcore.OcrService
import com.kyant.pdfcore.raster.PdfRasterizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class OcrRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrService: OcrService,
    @PdfDispatcher private val pdf: CoroutineDispatcher
) : OcrRepository {
    override suspend fun recognizePage(source: DocumentInfo, pageIndex: Int): List<OcrWord> = withContext(pdf) {
        val bitmap = PdfRasterizer.rasterizePageBitmap(context, source.ref.toUri(), pageIndex)
            ?: return@withContext emptyList()
        try {
            ocrService.recognize(context, bitmap).words.map { it.toDomain() }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
```

- [ ] **Step 3: Hilt modules.** Create `$PKG/core/di/PdfCoreModule.kt`:

```kotlin
package com.chethan616.clearpdf.core.di

import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.kyant.ocrcore.OcrService
import com.kyant.pdfcore.compressor.PdfCompressor
import com.kyant.pdfcore.converter.PdfConverter
import com.kyant.pdfcore.viewer.PdfViewer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * pdf-core / ocr-core services. They delegate to PdfServiceLocator so migrated and
 * not-yet-migrated screens share ONE instance each — PdfViewerImpl serializes page access with
 * its own lock, and a second instance would break that. Phase 6 moves construction here.
 */
@Module
@InstallIn(SingletonComponent::class)
object PdfCoreModule {
    @Provides fun pdfViewer(): PdfViewer = PdfServiceLocator.pdfViewer
    @Provides fun pdfCompressor(): PdfCompressor = PdfServiceLocator.pdfCompressor
    @Provides fun pdfConverter(): PdfConverter = PdfServiceLocator.pdfConverter
    @Provides fun ocrService(): OcrService = PdfServiceLocator.ocrService
}
```

- [ ] **Step 4: Gemini API source.** Create `$PKG/data/source/remote/GeminiApi.kt` (HTTP part of `data/ai/GeminiSummarizer.kt`, unchanged request/response format):

```kotlin
package com.chethan616.clearpdf.data.source.remote

import com.chethan616.clearpdf.BuildConfig
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.core.result.AppError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini generateContent over plain HTTPS. The key comes from BuildConfig (local.properties),
 * which is fine for testing but extractable from an APK — a release must go through a backend.
 */
@Singleton
class GeminiApi @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotBlank()

    suspend fun generate(systemPrompt: String, text: String): String = withContext(io) {
        val body = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", text) } }
                }
            }
            putJsonObject("generationConfig") { put("temperature", 0.3) }
        }.toString()

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${BuildConfig.GEMINI_MODEL}:generateContent")
        val conn = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            }
        } catch (e: IOException) {
            throw AppError.Network(e)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 429) throw AppError.QuotaExceeded()
            if (code !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw AppError.Remote("Gemini HTTP $code: ${errorMessage(error) ?: "request failed"}")
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            parseText(response) ?: throw AppError.Remote("Gemini returned no summary")
        } catch (e: IOException) {
            throw AppError.Network(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseText(response: String): String? = runCatching {
        json.parseToJsonElement(response).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun errorMessage(body: String): String? = runCatching {
        (json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
```

- [ ] **Step 5: SummaryRepositoryImpl.** Create `$PKG/data/repository/SummaryRepositoryImpl.kt`. Prefs name and key are the ones `GeminiSummarizer` uses today, so users who already agreed aren't asked again:

```kotlin
package com.chethan616.clearpdf.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.chethan616.clearpdf.data.source.remote.GeminiApi
import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.repository.SummaryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class SummaryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GeminiApi
) : SummaryRepository {

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun availability(): SummaryAvailability = when {
        !api.isConfigured -> SummaryAvailability.UNAVAILABLE
        context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED ->
            SummaryAvailability.UNAVAILABLE
        prefs.getBoolean(KEY_CONSENT, false) -> SummaryAvailability.READY
        else -> SummaryAvailability.NEEDS_CONSENT
    }

    override fun grantConsent() {
        prefs.edit().putBoolean(KEY_CONSENT, true).apply()
    }

    override suspend fun summarize(text: String): String = api.generate(SYSTEM_PROMPT, text)

    private companion object {
        const val PREFS_NAME = "clearpdf_ai"
        const val KEY_CONSENT = "summary_consent"

        val SYSTEM_PROMPT = """
            You summarize documents for a PDF reader app.
            Write the summary in the same language as the document.
            Start with one or two sentences giving the overall gist, then list the key points as short
            bullet lines starting with "• ". Keep names, numbers, dates and amounts exactly as written.
            Use plain text only, no Markdown headings or bold. Never invent facts that are not in the text.
        """.trimIndent()
    }
}
```

- [ ] **Step 6: Repository bindings.** Create `$PKG/core/di/RepositoryModule.kt`:

```kotlin
package com.chethan616.clearpdf.core.di

import com.chethan616.clearpdf.data.repository.DocumentRepositoryImpl
import com.chethan616.clearpdf.data.repository.EngagementRepositoryImpl
import com.chethan616.clearpdf.data.repository.OcrRepositoryImpl
import com.chethan616.clearpdf.data.repository.PdfRepositoryImpl
import com.chethan616.clearpdf.data.repository.RecentFilesRepositoryImpl
import com.chethan616.clearpdf.data.repository.SettingsRepositoryImpl
import com.chethan616.clearpdf.data.repository.SummaryRepositoryImpl
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.EngagementRepository
import com.chethan616.clearpdf.domain.repository.OcrRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import com.chethan616.clearpdf.domain.repository.SettingsRepository
import com.chethan616.clearpdf.domain.repository.SummaryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun documents(impl: DocumentRepositoryImpl): DocumentRepository
    @Binds @Singleton abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds @Singleton abstract fun recentFiles(impl: RecentFilesRepositoryImpl): RecentFilesRepository
    @Binds @Singleton abstract fun engagement(impl: EngagementRepositoryImpl): EngagementRepository
    @Binds @Singleton abstract fun pdf(impl: PdfRepositoryImpl): PdfRepository
    @Binds @Singleton abstract fun ocr(impl: OcrRepositoryImpl): OcrRepository
    @Binds @Singleton abstract fun summary(impl: SummaryRepositoryImpl): SummaryRepository
}
```

- [ ] **Step 7: Build.** `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:testFossDebugUnitTest --console=plain -q`. Expected: exit 0. Hilt validates the graph at compile time; a missing binding fails here.

- [ ] **Step 8: Review check.** `grep -rn "Impl()" app/src/main/java/com/chethan616/clearpdf/core/di` must print nothing (no second pdf-core instance).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/data app/src/main/java/com/chethan616/clearpdf/core/di
git commit -m "feat(arch): PDF, OCR and Gemini data sources behind domain repositories"
```

---

### Task 6: Compress use cases

**Files:**
- Create: `$PKG/domain/usecase/document/OpenDocumentUseCase.kt`, `$PKG/domain/usecase/compress/PrepareCompressionUseCase.kt`, `$PKG/domain/usecase/compress/EstimateCompressedSizeUseCase.kt`, `$PKG/domain/usecase/compress/CompressPdfUseCase.kt`
- Test: `$TEST/testing/Fakes.kt`, `$TEST/domain/usecase/compress/CompressUseCasesTest.kt`

**Interfaces:**
- Consumes: Task 4 interfaces/models.
- Produces:

```kotlin
class OpenDocumentUseCase(documents: DocumentRepository) { suspend operator fun invoke(ref: DocumentRef): DocumentInfo }
data class CompressionSetup(val level: CompressionLevel, val sliderFraction: Float, val estimatedBytes: Long)
class PrepareCompressionUseCase(settings: SettingsRepository, pdf: PdfRepository) { suspend operator fun invoke(source: DocumentInfo): CompressionSetup }
class EstimateCompressedSizeUseCase(pdf: PdfRepository) { suspend operator fun invoke(source: DocumentInfo, level: CompressionLevel): Long }
data class CompressionResult(val output: OutputDocument, val originalBytes: Long, val compressedBytes: Long) { val reductionPercent: Int }
class CompressPdfUseCase(documents, pdf, recentFiles, engagement) { suspend operator fun invoke(source: DocumentInfo, level: CompressionLevel): CompressionResult }
```

All use cases have `@Inject constructor`. Fakes in `testing/Fakes.kt` are reused by Tasks 8–11.

- [ ] **Step 1: Write the fakes.** Create `$TEST/testing/Fakes.kt`:

```kotlin
package com.chethan616.clearpdf.testing

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.EngagementRepository
import com.chethan616.clearpdf.domain.repository.OcrRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import com.chethan616.clearpdf.domain.repository.SettingsRepository
import com.chethan616.clearpdf.domain.repository.SummaryRepository

val SAMPLE_REF = DocumentRef("content://docs/sample.pdf")
val SAMPLE_DOC = DocumentInfo(SAMPLE_REF, "sample.pdf", 200 * 1024L)

class FakeDocumentRepository : DocumentRepository {
    var openError: Throwable? = null
    val createdPrefixes = mutableListOf<String>()
    override suspend fun open(ref: DocumentRef): DocumentInfo {
        openError?.let { throw it }
        return DocumentInfo(ref, "sample.pdf", 200 * 1024L)
    }
    override suspend fun createOutput(prefix: String, extension: String, mimeType: String): OutputDocument {
        createdPrefixes += prefix
        return OutputDocument(DocumentRef("content://out/$prefix.$extension"), "$prefix.$extension", "Downloads (default)")
    }
}

class FakeSettingsRepository(var fraction: Float = 0.7f) : SettingsRepository {
    override fun defaultCompressionFraction() = fraction
}

class FakeRecentFilesRepository : RecentFilesRepository {
    val added = mutableListOf<Pair<OutputDocument, Long>>()
    override suspend fun add(document: OutputDocument, sizeBytes: Long) { added += document to sizeBytes }
}

class FakeEngagementRepository : EngagementRepository {
    var interactions = 0
    override suspend fun recordPdfInteraction() { interactions++ }
}

class FakePdfRepository : PdfRepository {
    var estimates = mapOf(CompressionLevel.LOW to 50 * 1024L, CompressionLevel.MEDIUM to 100 * 1024L, CompressionLevel.HIGH to 150 * 1024L)
    var compressedBytes = 100 * 1024L
    var compressError: Throwable? = null
    var text = ""
    var pages = 0
    var pageCountError: Throwable? = null
    val stamped = mutableListOf<Map<Int, List<OcrWord>>>()
    override suspend fun estimateCompressedSize(source: DocumentInfo, level: CompressionLevel) = estimates.getValue(level)
    override suspend fun compress(source: DocumentInfo, level: CompressionLevel, output: OutputDocument): Long {
        compressError?.let { throw it }
        return compressedBytes
    }
    override suspend fun extractText(source: DocumentInfo) = text
    override suspend fun pageCount(source: DocumentInfo): Int {
        pageCountError?.let { throw it }
        return pages
    }
    override suspend fun stampSearchableText(source: DocumentInfo, output: OutputDocument, wordsByPage: Map<Int, List<OcrWord>>) {
        stamped += wordsByPage
    }
}

class FakeOcrRepository : OcrRepository {
    /** Words per page index; a page mapped to an exception throws. */
    val pages = mutableMapOf<Int, Any>()
    override suspend fun recognizePage(source: DocumentInfo, pageIndex: Int): List<OcrWord> {
        val page = pages[pageIndex] ?: return emptyList()
        if (page is Throwable) throw page
        @Suppress("UNCHECKED_CAST") return page as List<OcrWord>
    }
}

class FakeSummaryRepository(var availability: SummaryAvailability = SummaryAvailability.READY) : SummaryRepository {
    var result = "• point"
    var error: Throwable? = null
    val requests = mutableListOf<String>()
    override fun availability() = availability
    override fun grantConsent() { availability = SummaryAvailability.READY }
    override suspend fun summarize(text: String): String {
        requests += text
        error?.let { throw it }
        return result
    }
}

fun word(text: String) = OcrWord(text, 0f, 0f, 0.1f, 0.1f)
```

- [ ] **Step 2: Write the failing tests.** Create `$TEST/domain/usecase/compress/CompressUseCasesTest.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.testing.FakeDocumentRepository
import com.chethan616.clearpdf.testing.FakeEngagementRepository
import com.chethan616.clearpdf.testing.FakePdfRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.FakeSettingsRepository
import com.chethan616.clearpdf.testing.SAMPLE_DOC
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CompressUseCasesTest {
    private val documents = FakeDocumentRepository()
    private val pdf = FakePdfRepository()
    private val recents = FakeRecentFilesRepository()
    private val engagement = FakeEngagementRepository()

    @Test fun prepare_startsFromDefaultQualitySetting() = runTest {
        val setup = PrepareCompressionUseCase(FakeSettingsRepository(fraction = 0.2f), pdf)(SAMPLE_DOC)
        assertEquals(CompressionLevel.LOW, setup.level)
        assertEquals(0.2f, setup.sliderFraction)
        assertEquals(50 * 1024L, setup.estimatedBytes)
    }

    @Test fun compress_writesTimestampedOutputAndRecordsIt() = runTest {
        val result = CompressPdfUseCase(documents, pdf, recents, engagement)(SAMPLE_DOC, CompressionLevel.MEDIUM)
        assertEquals(listOf("ClearPDF_Compressed"), documents.createdPrefixes)
        assertEquals(result.output to 100 * 1024L, recents.added.single())
        assertEquals(1, engagement.interactions)
        assertEquals(200 * 1024L, result.originalBytes)
        assertEquals(100 * 1024L, result.compressedBytes)
        assertEquals(50, result.reductionPercent)
    }

    @Test fun reductionPercent_isZeroWhenOriginalSizeUnknown() {
        val result = CompressionResult(output = OUTPUT, originalBytes = -1, compressedBytes = 10)
        assertEquals(0, result.reductionPercent)
    }

    @Test fun compress_failureRecordsNothing() = runTest {
        pdf.compressError = IOException("broken pdf")
        val failure = runCatching { CompressPdfUseCase(documents, pdf, recents, engagement)(SAMPLE_DOC, CompressionLevel.HIGH) }
        assertTrue(failure.isFailure)
        assertTrue(recents.added.isEmpty())
        assertEquals(0, engagement.interactions)
    }

    private companion object {
        val OUTPUT = com.chethan616.clearpdf.domain.model.OutputDocument(
            com.chethan616.clearpdf.domain.model.DocumentRef("content://out/x.pdf"), "x.pdf", "Downloads (default)"
        )
    }
}
```

- [ ] **Step 3: Run to verify it fails.** `./gradlew :app:testFossDebugUnitTest --tests "*CompressUseCasesTest" --console=plain -q`. Expected: compilation failure (use cases unresolved).

- [ ] **Step 4: Implement.** Create `$PKG/domain/usecase/document/OpenDocumentUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.document

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import javax.inject.Inject

/** A document the user just picked: keep access to it and read its name and size. */
class OpenDocumentUseCase @Inject constructor(private val documents: DocumentRepository) {
    suspend operator fun invoke(ref: DocumentRef): DocumentInfo = documents.open(ref)
}
```

Create `$PKG/domain/usecase/compress/PrepareCompressionUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.SettingsRepository
import javax.inject.Inject

data class CompressionSetup(val level: CompressionLevel, val sliderFraction: Float, val estimatedBytes: Long)

/** Starting point for a newly picked file: the user's Default Quality setting and its estimate. */
class PrepareCompressionUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val pdf: PdfRepository
) {
    suspend operator fun invoke(source: DocumentInfo): CompressionSetup {
        val fraction = settings.defaultCompressionFraction()
        val level = CompressionLevel.fromFraction(fraction)
        return CompressionSetup(level, fraction, pdf.estimateCompressedSize(source, level))
    }
}
```

Create `$PKG/domain/usecase/compress/EstimateCompressedSizeUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.repository.PdfRepository
import javax.inject.Inject

class EstimateCompressedSizeUseCase @Inject constructor(private val pdf: PdfRepository) {
    suspend operator fun invoke(source: DocumentInfo, level: CompressionLevel): Long =
        pdf.estimateCompressedSize(source, level)
}
```

Create `$PKG/domain/usecase/compress/CompressPdfUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.EngagementRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import javax.inject.Inject

data class CompressionResult(val output: OutputDocument, val originalBytes: Long, val compressedBytes: Long) {
    /** Size saved, in whole percent; 0 when the original size is unknown. */
    val reductionPercent: Int
        get() = if (originalBytes > 0) (100 - compressedBytes * 100 / originalBytes).toInt() else 0
}

/** Writes a compressed copy next to the user's other outputs and lists it in Recents. */
class CompressPdfUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val pdf: PdfRepository,
    private val recentFiles: RecentFilesRepository,
    private val engagement: EngagementRepository
) {
    suspend operator fun invoke(source: DocumentInfo, level: CompressionLevel): CompressionResult {
        val output = documents.createOutput(prefix = "ClearPDF_Compressed", extension = "pdf", mimeType = "application/pdf")
        val compressedBytes = pdf.compress(source, level, output)
        recentFiles.add(output, compressedBytes)
        engagement.recordPdfInteraction()
        return CompressionResult(output, source.sizeBytes, compressedBytes)
    }
}
```

- [ ] **Step 5: Run tests.** `./gradlew :app:testFossDebugUnitTest --tests "*CompressUseCasesTest" --console=plain -q`. Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/domain/usecase/document app/src/main/java/com/chethan616/clearpdf/domain/usecase/compress app/src/test/java/com/chethan616/clearpdf/testing/Fakes.kt app/src/test/java/com/chethan616/clearpdf/domain/usecase
git commit -m "feat(domain): compress use cases"
```

---

### Task 7: Compress screen on the new architecture (reference screen 1)

**Files:**
- Create: `$PKG/presentation/feature/tool/compress/CompressPdfContract.kt`, `CompressPdfViewModel.kt`, `CompressPdfFragment.kt`
- Modify: `app/src/main/res/navigation/nav_graph.xml` (the `compress_pdf` fragment's `android:name`)
- Delete: `$PKG/ui/viewmodel/CompressPdfViewModel.kt`, `$PKG/ui/fragment/tool/CompressPdfFragment.kt`, `$PKG/domain/usecase/CompressPdfUseCase.kt`
- Test: `$TEST/presentation/feature/tool/compress/CompressPdfViewModelTest.kt`

**Interfaces:**
- Consumes: Task 6 use cases, `BaseViewModel`, `BaseToolFragment`, `UiText`, `toDocumentRef()`/`toUri()`.
- Produces: `CompressPdfViewModel` with `onFilePicked(ref: DocumentRef)`, `onSliderChanged(value: Float)`, `onCompressClicked()`; `CompressPdfUiState`, `CompressPdfUiEvent.ShowStatus(text: UiText, isError: Boolean)`.

- [ ] **Step 1: Write the failing test.** Create `$TEST/presentation/feature/tool/compress/CompressPdfViewModelTest.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.feature.tool.compress

import app.cash.turbine.test
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.usecase.compress.CompressPdfUseCase
import com.chethan616.clearpdf.domain.usecase.compress.EstimateCompressedSizeUseCase
import com.chethan616.clearpdf.domain.usecase.compress.PrepareCompressionUseCase
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.testing.FakeDocumentRepository
import com.chethan616.clearpdf.testing.FakeEngagementRepository
import com.chethan616.clearpdf.testing.FakePdfRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.FakeSettingsRepository
import com.chethan616.clearpdf.testing.MainDispatcherRule
import com.chethan616.clearpdf.testing.SAMPLE_REF
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class CompressPdfViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val documents = FakeDocumentRepository()
    private val pdf = FakePdfRepository()

    private fun viewModel() = CompressPdfViewModel(
        OpenDocumentUseCase(documents),
        PrepareCompressionUseCase(FakeSettingsRepository(fraction = 0.5f), pdf),
        EstimateCompressedSizeUseCase(pdf),
        CompressPdfUseCase(documents, pdf, FakeRecentFilesRepository(), FakeEngagementRepository())
    )

    @Test fun pickingAFile_showsItWithDefaultQualityAndEstimate() {
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        val state = vm.state.value
        assertEquals("sample.pdf", state.source?.name)
        assertEquals(CompressionLevel.MEDIUM, state.level)
        assertEquals(0.5f, state.sliderFraction)
        assertEquals(100 * 1024L, state.estimatedBytes)
    }

    @Test fun movingSliderAcrossALevel_reEstimates() {
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onSliderChanged(0.9f)
        assertEquals(CompressionLevel.HIGH, vm.state.value.level)
        assertEquals(150 * 1024L, vm.state.value.estimatedBytes)
    }

    @Test fun compressSuccess_showsOutputAndOneSuccessMessage() = runTest {
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onCompressClicked()
        assertFalse(vm.state.value.isCompressing)
        assertEquals("ClearPDF_Compressed.pdf", vm.state.value.output?.name)
        vm.events.test {
            assertEquals(
                CompressPdfUiEvent.ShowStatus(UiText.res(R.string.compression_success, 200L, 100L, 50, "Downloads (default)"), isError = false),
                awaitItem()
            )
            expectNoEvents()
        }
    }

    @Test fun compressFailure_showsErrorAndRestoresButton() = runTest {
        pdf.compressError = IOException("broken")
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onCompressClicked()
        assertFalse(vm.state.value.isCompressing)
        assertNull(vm.state.value.output)
        vm.events.test {
            assertEquals(CompressPdfUiEvent.ShowStatus(UiText.res(R.string.compression_failed), isError = true), awaitItem())
        }
    }

    @Test fun unreadableFile_showsOpenError() = runTest {
        documents.openError = IOException("gone")
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        assertNull(vm.state.value.source)
        vm.events.test {
            assertEquals(CompressPdfUiEvent.ShowStatus(UiText.res(R.string.open_pdf_failed), isError = true), awaitItem())
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testFossDebugUnitTest --tests "*CompressPdfViewModelTest" --console=plain -q`. Expected: compilation failure.

- [ ] **Step 3: Contract + ViewModel.** Create `$PKG/presentation/feature/tool/compress/CompressPdfContract.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.feature.tool.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.presentation.base.UiEvent
import com.chethan616.clearpdf.presentation.base.UiState
import com.chethan616.clearpdf.presentation.common.UiText

data class CompressPdfUiState(
    val source: DocumentInfo? = null,
    val level: CompressionLevel = CompressionLevel.MEDIUM,
    val sliderFraction: Float = 0.5f,
    /** -1 until an estimate is known. */
    val estimatedBytes: Long = -1,
    val isCompressing: Boolean = false,
    /** The last compressed file, for the "Open PDF" button. */
    val output: OutputDocument? = null
) : UiState

sealed interface CompressPdfUiEvent : UiEvent {
    data class ShowStatus(val text: UiText, val isError: Boolean) : CompressPdfUiEvent
}
```

Create `$PKG/presentation/feature/tool/compress/CompressPdfViewModel.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.feature.tool.compress

import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.usecase.compress.CompressPdfUseCase
import com.chethan616.clearpdf.domain.usecase.compress.EstimateCompressedSizeUseCase
import com.chethan616.clearpdf.domain.usecase.compress.PrepareCompressionUseCase
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.presentation.base.BaseViewModel
import com.chethan616.clearpdf.presentation.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CompressPdfViewModel @Inject constructor(
    private val openDocument: OpenDocumentUseCase,
    private val prepareCompression: PrepareCompressionUseCase,
    private val estimateSize: EstimateCompressedSizeUseCase,
    private val compressPdf: CompressPdfUseCase
) : BaseViewModel<CompressPdfUiState, CompressPdfUiEvent>(CompressPdfUiState()) {

    fun onFilePicked(ref: DocumentRef) {
        launch(onError = { showError(R.string.open_pdf_failed) }) {
            val source = openDocument(ref)
            val setup = prepareCompression(source)
            setState {
                CompressPdfUiState(
                    source = source,
                    level = setup.level,
                    sliderFraction = setup.sliderFraction,
                    estimatedBytes = setup.estimatedBytes
                )
            }
        }
    }

    fun onSliderChanged(value: Float) {
        val previous = currentState.level
        val level = CompressionLevel.fromFraction(value)
        setState { copy(sliderFraction = value, level = level) }
        val source = currentState.source ?: return
        if (level == previous) return
        // A failed estimate just leaves the previous one on screen, as before.
        launch(onError = {}) {
            val estimate = estimateSize(source, level)
            setState { copy(estimatedBytes = estimate) }
        }
    }

    fun onCompressClicked() {
        val source = currentState.source ?: return
        if (currentState.isCompressing) return
        setState { copy(isCompressing = true, output = null) }
        launch(onError = {
            setState { copy(isCompressing = false) }
            showError(R.string.compression_failed)
        }) {
            val result = compressPdf(source, currentState.level)
            setState { copy(isCompressing = false, output = result.output) }
            sendEvent(
                CompressPdfUiEvent.ShowStatus(
                    UiText.res(
                        R.string.compression_success,
                        result.originalBytes / 1024,
                        result.compressedBytes / 1024,
                        result.reductionPercent,
                        result.output.locationLabel
                    ),
                    isError = false
                )
            )
        }
    }

    private fun showError(message: Int) = sendEvent(CompressPdfUiEvent.ShowStatus(UiText.res(message), isError = true))
}
```

- [ ] **Step 4: Run tests.** `./gradlew :app:testFossDebugUnitTest --tests "*CompressPdfViewModelTest" --console=plain -q`. Expected: PASS (5 tests).

- [ ] **Step 5: Fragment.** Create `$PKG/presentation/feature/tool/compress/CompressPdfFragment.kt` (same views and texts as the old `ui/fragment/tool/CompressPdfFragment.kt`):

```kotlin
package com.chethan616.clearpdf.presentation.feature.tool.compress

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.platform.toDocumentRef
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.databinding.ToolCompressBinding
import com.chethan616.clearpdf.presentation.base.BaseToolFragment
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CompressPdfFragment : BaseToolFragment<ToolCompressBinding>(ToolCompressBinding::inflate) {

    override val titleRes = R.string.tool_compress

    private val viewModel: CompressPdfViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toDocumentRef())
    }

    private val accent get() = requireContext().color(R.color.tool_compress)

    override fun initViews(savedInstanceState: Bundle?) {
        val ctx = requireContext()
        ToolUi.hero(
            body.hero, R.drawable.ic_compress, accent,
            getString(R.string.tool_compress), getString(R.string.tool_compress_sub),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }
        body.qualityIcon.tint(accent)
        body.qualityValue.setTextColor(accent)
        body.qualityValue.tintBackground(ctx.accentWash(accent))
        ToolUi.primaryButton(body.compress, accent)
    }

    override fun initListeners() {
        body.qualitySlider.addOnChangeListener { _, value, fromUser -> if (fromUser) viewModel.onSliderChanged(value) }
        body.compress.setOnClickListener { viewModel.onCompressClicked() }
    }

    override fun observeViewModel() {
        viewModel.state.collectOnStarted(::render)
        viewModel.events.collectOnStarted { event ->
            when (event) {
                is CompressPdfUiEvent.ShowStatus -> showStatus(body.status, event.text, event.isError)
            }
        }
    }

    private fun render(state: CompressPdfUiState) {
        val source = state.source
        ToolUi.file(body.file, source?.name, getString(R.string.size_kb, (source?.sizeBytes ?: 0) / 1024), accent)
        body.options.isVisible = source != null
        body.compress.isVisible = source != null
        val slider = state.sliderFraction.coerceIn(0f, 1f)
        if (body.qualitySlider.value != slider) body.qualitySlider.value = slider
        body.qualityValue.text = "${(state.sliderFraction * 100).toInt()}%"
        body.estimate.isVisible = state.estimatedBytes > 0
        if (source != null && state.estimatedBytes > 0) {
            body.estimate.text = "${getString(R.string.estimated_output)}  ${source.sizeBytes / 1024} KB  →  " +
                "${getString(R.string.approximation)} ${state.estimatedBytes / 1024} KB"
        }
        ToolUi.setBusy(
            body.compress, state.isCompressing, R.drawable.ic_compress,
            getString(if (state.isCompressing) R.string.compress_working else R.string.compress_action)
        )
        ToolUi.openOutput(body.openOutput.root, state.output?.ref?.toUri(), accent, ::viewOutput)
    }
}
```

Note: `ToolUi.file` hides itself when the name is null/empty, matching the old `""` initial name.

- [ ] **Step 6: Route + delete old code.** In `app/src/main/res/navigation/nav_graph.xml`, change the `compress_pdf` fragment's `android:name` to `com.chethan616.clearpdf.presentation.feature.tool.compress.CompressPdfFragment`. Delete `$PKG/ui/viewmodel/CompressPdfViewModel.kt`, `$PKG/ui/fragment/tool/CompressPdfFragment.kt`, `$PKG/domain/usecase/CompressPdfUseCase.kt`. Then `grep -rn "ui.viewmodel.CompressPdf\|ui.fragment.tool.CompressPdfFragment\|domain.usecase.CompressPdfUseCase" app/src` must print nothing.

- [ ] **Step 7: Build + tests.** `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:testFossDebugUnitTest --console=plain -q`. Expected: exit 0.

- [ ] **Step 8: Device smoke.** Install fossDebug. Tools → Compress PDF → pick `sample-local-pdf2.pdf`. Expected: file card shows name + size KB, slider at the Default Quality %, estimate line visible. Drag slider from ~50% to ~90%: estimate changes. Tap Compress: busy spinner, then green "compressed … saved to …" card that hides after 3 s, "Open PDF" button appears and opens the output in the viewer. Rotate the device after the card hid: it does not come back; file and Open PDF remain. Output file appears in Home's Recents.

- [ ] **Step 9: Commit**

```bash
git add -A app/src/main/java/com/chethan616/clearpdf/presentation/feature/tool/compress app/src/main/res/navigation/nav_graph.xml app/src/test/java/com/chethan616/clearpdf/presentation/feature app/src/main/java/com/chethan616/clearpdf/ui/viewmodel/CompressPdfViewModel.kt app/src/main/java/com/chethan616/clearpdf/ui/fragment/tool/CompressPdfFragment.kt app/src/main/java/com/chethan616/clearpdf/domain/usecase/CompressPdfUseCase.kt
git commit -m "refactor(compress): move Compress PDF onto MVVM + Clean Architecture"
```

---

### Task 8: Text and summary use cases

**Files:**
- Create: `$PKG/domain/usecase/text/ExtractTextUseCase.kt`, `RecognizeTextUseCase.kt`, `MakeSearchablePdfUseCase.kt`; `$PKG/domain/usecase/summary/SummarizeTextUseCase.kt`, `GetSummaryAvailabilityUseCase.kt`, `GrantSummaryConsentUseCase.kt`
- Test: `$TEST/domain/usecase/text/TextUseCasesTest.kt`, `$TEST/domain/usecase/summary/SummarizeTextUseCaseTest.kt`

**Interfaces:**
- Consumes: Task 4 interfaces, `runCatchingCancellable` (Task 2), fakes (Task 6).
- Produces:

```kotlin
class ExtractTextUseCase { suspend operator fun invoke(source: DocumentInfo): String }            // trimmed
data class RecognizedText(val text: String, val wordsByPage: Map<Int, List<OcrWord>>)
class RecognizeTextUseCase { suspend operator fun invoke(source: DocumentInfo, onProgress: (page: Int, total: Int) -> Unit): RecognizedText }
class MakeSearchablePdfUseCase { suspend operator fun invoke(source: DocumentInfo, wordsByPage: Map<Int, List<OcrWord>>): OutputDocument }
class SummarizeTextUseCase { suspend operator fun invoke(text: String): Summary; companion { const val MAX_INPUT_CHARS = 120_000 } }
class GetSummaryAvailabilityUseCase { operator fun invoke(): SummaryAvailability }
class GrantSummaryConsentUseCase { operator fun invoke() }
```

- [ ] **Step 1: Write the failing tests.** Create `$TEST/domain/usecase/text/TextUseCasesTest.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.text

import com.chethan616.clearpdf.testing.FakeDocumentRepository
import com.chethan616.clearpdf.testing.FakeOcrRepository
import com.chethan616.clearpdf.testing.FakePdfRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.SAMPLE_DOC
import com.chethan616.clearpdf.testing.word
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TextUseCasesTest {
    private val pdf = FakePdfRepository()
    private val ocr = FakeOcrRepository()

    @Test fun extract_trimsText() = runTest {
        pdf.text = "\n  Hello world \n"
        assertEquals("Hello world", ExtractTextUseCase(pdf)(SAMPLE_DOC))
    }

    @Test fun recognize_joinsPagesAndReportsProgress() = runTest {
        pdf.pages = 3
        ocr.pages[0] = listOf(word("Page"), word("one"))
        ocr.pages[2] = listOf(word("three"))
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = RecognizeTextUseCase(pdf, ocr)(SAMPLE_DOC) { page, total -> progress += page to total }
        assertEquals("Page one\n\nthree", result.text)
        assertEquals(setOf(0, 2), result.wordsByPage.keys)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3), progress)
    }

    @Test fun recognize_skipsPagesThatFail() = runTest {
        pdf.pages = 2
        ocr.pages[0] = IOException("render failed")
        ocr.pages[1] = listOf(word("ok"))
        val result = RecognizeTextUseCase(pdf, ocr)(SAMPLE_DOC) { _, _ -> }
        assertEquals("ok", result.text)
    }

    @Test fun recognize_unreadablePdfGivesEmptyResult() = runTest {
        pdf.pageCountError = IOException("not a pdf")
        val result = RecognizeTextUseCase(pdf, ocr)(SAMPLE_DOC) { _, _ -> }
        assertEquals("", result.text)
        assertTrue(result.wordsByPage.isEmpty())
    }

    @Test fun searchable_stampsWordsIntoNewOutputAndListsIt() = runTest {
        val documents = FakeDocumentRepository()
        val recents = FakeRecentFilesRepository()
        val words = mapOf(0 to listOf(word("hi")))
        val output = MakeSearchablePdfUseCase(documents, pdf, recents)(SAMPLE_DOC, words)
        assertEquals(listOf("ClearPDF_Searchable"), documents.createdPrefixes)
        assertEquals(listOf(words), pdf.stamped)
        assertEquals(output to 0L, recents.added.single())
    }
}
```

Create `$TEST/domain/usecase/summary/SummarizeTextUseCaseTest.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.summary

import com.chethan616.clearpdf.testing.FakeSummaryRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizeTextUseCaseTest {
    private val repo = FakeSummaryRepository()

    @Test fun shortText_isSentWhole() = runTest {
        val summary = SummarizeTextUseCase(repo)("short doc")
        assertEquals(listOf("short doc"), repo.requests)
        assertEquals("• point", summary.text)
        assertFalse(summary.truncated)
    }

    @Test fun longText_isCutToTheLimitAndFlagged() = runTest {
        val text = "a".repeat(SummarizeTextUseCase.MAX_INPUT_CHARS + 5)
        val summary = SummarizeTextUseCase(repo)(text)
        assertEquals(SummarizeTextUseCase.MAX_INPUT_CHARS, repo.requests.single().length)
        assertTrue(summary.truncated)
    }

    @Test fun textExactlyAtTheLimit_isNotFlagged() = runTest {
        val summary = SummarizeTextUseCase(repo)("a".repeat(SummarizeTextUseCase.MAX_INPUT_CHARS))
        assertFalse(summary.truncated)
    }
}
```

- [ ] **Step 2: Run to verify they fail.** `./gradlew :app:testFossDebugUnitTest --tests "*TextUseCasesTest" --tests "*SummarizeTextUseCaseTest" --console=plain -q`. Expected: compilation failure.

- [ ] **Step 3: Implement text use cases.** Create `$PKG/domain/usecase/text/ExtractTextUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.text

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.repository.PdfRepository
import javax.inject.Inject

/** The PDF's own text layer; empty for scans. */
class ExtractTextUseCase @Inject constructor(private val pdf: PdfRepository) {
    suspend operator fun invoke(source: DocumentInfo): String = pdf.extractText(source).trim()
}
```

Create `$PKG/domain/usecase/text/RecognizeTextUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.text

import com.chethan616.clearpdf.core.result.runCatchingCancellable
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.repository.OcrRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import javax.inject.Inject

data class RecognizedText(val text: String, val wordsByPage: Map<Int, List<OcrWord>>)

/**
 * On-device OCR for scanned PDFs, page by page. A page that can't be rendered or recognized is
 * skipped rather than failing the whole document.
 */
class RecognizeTextUseCase @Inject constructor(
    private val pdf: PdfRepository,
    private val ocr: OcrRepository
) {
    suspend operator fun invoke(source: DocumentInfo, onProgress: (page: Int, total: Int) -> Unit): RecognizedText {
        val total = runCatchingCancellable { pdf.pageCount(source) }.getOrDefault(0)
        val wordsByPage = linkedMapOf<Int, List<OcrWord>>()
        for (page in 0 until total) {
            onProgress(page, total)
            val words = runCatchingCancellable { ocr.recognizePage(source, page) }.getOrDefault(emptyList())
            if (words.isNotEmpty()) wordsByPage[page] = words
        }
        val text = wordsByPage.values.joinToString("\n\n") { words -> words.joinToString(" ") { it.text } }
        return RecognizedText(text.trim(), wordsByPage)
    }
}
```

Create `$PKG/domain/usecase/text/MakeSearchablePdfUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.text

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import javax.inject.Inject

/** Copies the PDF with the OCR words as an invisible, selectable text layer. */
class MakeSearchablePdfUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val pdf: PdfRepository,
    private val recentFiles: RecentFilesRepository
) {
    suspend operator fun invoke(source: DocumentInfo, wordsByPage: Map<Int, List<OcrWord>>): OutputDocument {
        val output = documents.createOutput(prefix = "ClearPDF_Searchable", extension = "pdf", mimeType = "application/pdf")
        pdf.stampSearchableText(source, output, wordsByPage)
        recentFiles.add(output, sizeBytes = 0)
        return output
    }
}
```

- [ ] **Step 4: Implement summary use cases.** Create `$PKG/domain/usecase/summary/SummarizeTextUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.summary

import com.chethan616.clearpdf.domain.model.Summary
import com.chethan616.clearpdf.domain.repository.SummaryRepository
import javax.inject.Inject

/** Summarizes document text, sending at most [MAX_INPUT_CHARS] to stay inside free-tier limits. */
class SummarizeTextUseCase @Inject constructor(private val summaries: SummaryRepository) {
    suspend operator fun invoke(text: String): Summary =
        Summary(text = summaries.summarize(text.take(MAX_INPUT_CHARS)), truncated = text.length > MAX_INPUT_CHARS)

    companion object {
        const val MAX_INPUT_CHARS = 120_000
    }
}
```

Create `$PKG/domain/usecase/summary/GetSummaryAvailabilityUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.summary

import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.repository.SummaryRepository
import javax.inject.Inject

class GetSummaryAvailabilityUseCase @Inject constructor(private val summaries: SummaryRepository) {
    operator fun invoke(): SummaryAvailability = summaries.availability()
}
```

Create `$PKG/domain/usecase/summary/GrantSummaryConsentUseCase.kt`:

```kotlin
package com.chethan616.clearpdf.domain.usecase.summary

import com.chethan616.clearpdf.domain.repository.SummaryRepository
import javax.inject.Inject

/** The user agreed to send document text to Gemini; remembered across launches. */
class GrantSummaryConsentUseCase @Inject constructor(private val summaries: SummaryRepository) {
    operator fun invoke() = summaries.grantConsent()
}
```

- [ ] **Step 5: Run tests.** `./gradlew :app:testFossDebugUnitTest --tests "*TextUseCasesTest" --tests "*SummarizeTextUseCaseTest" --console=plain -q`. Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/domain/usecase/text app/src/main/java/com/chethan616/clearpdf/domain/usecase/summary app/src/test/java/com/chethan616/clearpdf/domain/usecase/text app/src/test/java/com/chethan616/clearpdf/domain/usecase/summary
git commit -m "feat(domain): text extraction, OCR, searchable PDF and summary use cases"
```

---

### Task 9: Tools tab reads AI availability through the domain

**Files:**
- Modify: `$PKG/ui/fragment/tools/ToolsFragment.kt`
- (`$PKG/data/ai/GeminiSummarizer.kt` is still used by the old Extract Text screen; Task 11 Step 2 deletes it.)

**Interfaces:**
- Consumes: `GetSummaryAvailabilityUseCase`, `SummaryAvailability` (Task 8).

ToolsFragment gets its own ViewModel in phase 4; until then it injects the use case directly so it no longer touches `data/`.

- [ ] **Step 1: Edit ToolsFragment.** In `$PKG/ui/fragment/tools/ToolsFragment.kt`:
  - Replace `import com.chethan616.clearpdf.data.ai.GeminiSummarizer` with:

```kotlin
import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.usecase.summary.GetSummaryAvailabilityUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
```

  - Annotate the class with `@AndroidEntryPoint` and add as its first member:

```kotlin
    // Phase 4 moves this into a ToolsViewModel.
    @Inject lateinit var getSummaryAvailability: GetSummaryAvailabilityUseCase
```

  - In `render()`, replace `GeminiSummarizer.isAvailable(requireContext())` with `getSummaryAvailability() != SummaryAvailability.UNAVAILABLE`.

- [ ] **Step 2: Build.** `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:testFossDebugUnitTest --console=plain -q`. Expected: exit 0.

- [ ] **Step 3: Device smoke.** Install fossDebug, open Tools. Expected: "AI" section with the AI Summary tile (key present in `local.properties`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/ui/fragment/tools/ToolsFragment.kt
git commit -m "refactor(tools): read AI summary availability through the domain layer"
```

---

### Task 10: Extract Text + AI Summary ViewModel

**Files:**
- Create: `$PKG/presentation/feature/tool/extracttext/ExtractTextContract.kt`, `ExtractTextViewModel.kt`
- Test: `$TEST/presentation/feature/tool/extracttext/ExtractTextViewModelTest.kt`

**Interfaces:**
- Consumes: Task 6 `OpenDocumentUseCase`, Task 8 use cases, `BaseViewModel`, `AppError`.
- Produces: `ExtractTextViewModel(savedStateHandle, …)` with `onFilePicked(ref)`, `onRecognizeClicked()`, `onMakeSearchableClicked()`, `onSummarizeClicked()`, `onSummaryConsentGranted()`; `ExtractTextUiState`; `ExtractTextUiEvent.ShowSummaryConsent`; `const val ARG_AI_MODE = "ai_mode"` (matches `nav_graph.xml`).

Behaviour to preserve (from today's `ExtractTextViewModel` + `ExtractTextFragment`):
- Pick → extract text layer. Empty → `canRecognize` + inline error `extract_no_selectable_text`. Failure → inline error `text_extraction_failed`.
- OCR → progress `(page, total)`; result replaces text; empty result → same inline error; `canMakeSearchable` if any words.
- Searchable failure → inline error with the failure's message, or "Couldn't create a searchable PDF".
- Summary: quota → `ai_summary_quota`; other failure → `ai_summary_failed` with the message.
- AI mode: as soon as text exists for a file (after extraction or after OCR), summarize once per file; NEEDS_CONSENT shows the dialog once per file; declining doesn't re-ask until the user taps the button.

- [ ] **Step 1: Write the failing test.** Create `$TEST/presentation/feature/tool/extracttext/ExtractTextViewModelTest.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.feature.tool.extracttext

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.domain.usecase.summary.GetSummaryAvailabilityUseCase
import com.chethan616.clearpdf.domain.usecase.summary.GrantSummaryConsentUseCase
import com.chethan616.clearpdf.domain.usecase.summary.SummarizeTextUseCase
import com.chethan616.clearpdf.domain.usecase.text.ExtractTextUseCase
import com.chethan616.clearpdf.domain.usecase.text.MakeSearchablePdfUseCase
import com.chethan616.clearpdf.domain.usecase.text.RecognizeTextUseCase
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.testing.FakeDocumentRepository
import com.chethan616.clearpdf.testing.FakeOcrRepository
import com.chethan616.clearpdf.testing.FakePdfRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.FakeSummaryRepository
import com.chethan616.clearpdf.testing.MainDispatcherRule
import com.chethan616.clearpdf.testing.SAMPLE_REF
import com.chethan616.clearpdf.testing.word
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ExtractTextViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val documents = FakeDocumentRepository()
    private val pdf = FakePdfRepository()
    private val ocr = FakeOcrRepository()
    private val summaries = FakeSummaryRepository()

    private fun viewModel(aiMode: Boolean = false) = ExtractTextViewModel(
        SavedStateHandle(mapOf(ARG_AI_MODE to aiMode)),
        OpenDocumentUseCase(documents),
        ExtractTextUseCase(pdf),
        RecognizeTextUseCase(pdf, ocr),
        MakeSearchablePdfUseCase(documents, pdf, FakeRecentFilesRepository()),
        SummarizeTextUseCase(summaries),
        GetSummaryAvailabilityUseCase(summaries),
        GrantSummaryConsentUseCase(summaries)
    )

    @Test fun textLayer_isShown() {
        pdf.text = "Hello"
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        assertEquals("Hello", vm.state.value.text)
        assertFalse(vm.state.value.canRecognize)
        assertNull(vm.state.value.error)
    }

    @Test fun scannedPdf_offersOcr() {
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        assertTrue(vm.state.value.canRecognize)
        assertEquals(UiText.res(R.string.extract_no_selectable_text), vm.state.value.error)
    }

    @Test fun ocr_replacesTextAndEnablesSearchable() {
        pdf.pages = 1
        ocr.pages[0] = listOf(word("Scanned"))
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onRecognizeClicked()
        val state = vm.state.value
        assertEquals("Scanned", state.text)
        assertFalse(state.canRecognize)
        assertTrue(state.canMakeSearchable)
        assertNull(state.recognizeProgress)
    }

    @Test fun searchable_savesOutput() {
        pdf.pages = 1
        ocr.pages[0] = listOf(word("Scanned"))
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onRecognizeClicked()
        vm.onMakeSearchableClicked()
        assertEquals("ClearPDF_Searchable.pdf", vm.state.value.searchableOutput?.name)
    }

    @Test fun aiMode_summarizesAutomaticallyOnce() {
        pdf.text = "Body"
        val vm = viewModel(aiMode = true)
        vm.onFilePicked(SAMPLE_REF)
        assertEquals("• point", vm.state.value.summary?.text)
        assertEquals(listOf("Body"), summaries.requests)
    }

    @Test fun aiMode_onScan_summarizesAfterOcr() {
        pdf.pages = 1
        ocr.pages[0] = listOf(word("Scanned"))
        val vm = viewModel(aiMode = true)
        vm.onFilePicked(SAMPLE_REF)
        assertTrue(summaries.requests.isEmpty())
        vm.onRecognizeClicked()
        assertEquals(listOf("Scanned"), summaries.requests)
    }

    @Test fun aiMode_needsConsent_asksOnceAndNotAgainAfterDecline() = runTest {
        summaries.availability = SummaryAvailability.NEEDS_CONSENT
        pdf.text = "Body"
        val vm = viewModel(aiMode = true)
        vm.events.test {
            vm.onFilePicked(SAMPLE_REF)
            assertEquals(ExtractTextUiEvent.ShowSummaryConsent, awaitItem())
            // User declined (no onSummaryConsentGranted). State changes must not re-ask.
            vm.onRecognizeClicked()
            expectNoEvents()
        }
        assertTrue(summaries.requests.isEmpty())
    }

    @Test fun consentGranted_summarizes() {
        summaries.availability = SummaryAvailability.NEEDS_CONSENT
        pdf.text = "Body"
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onSummaryConsentGranted()
        assertEquals("• point", vm.state.value.summary?.text)
        assertEquals(SummaryAvailability.READY, summaries.availability)
    }

    @Test fun quotaExceeded_showsQuotaMessageAndAllowsRetry() {
        summaries.error = AppError.QuotaExceeded()
        pdf.text = "Body"
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onSummarizeClicked()
        assertEquals(UiText.res(R.string.ai_summary_quota), vm.state.value.summaryError)
        assertFalse(vm.state.value.isSummarizing)
    }

    @Test fun networkFailure_showsReason() {
        summaries.error = AppError.Network(IOException("Unable to resolve host"))
        pdf.text = "Body"
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onSummarizeClicked()
        assertEquals(UiText.res(R.string.ai_summary_failed, "Unable to resolve host"), vm.state.value.summaryError)
    }

    @Test fun summaryHiddenWhenUnavailable() {
        summaries.availability = SummaryAvailability.UNAVAILABLE
        pdf.text = "Body"
        val vm = viewModel(aiMode = true)
        vm.onFilePicked(SAMPLE_REF)
        assertFalse(vm.state.value.summaryAvailable)
        assertTrue(summaries.requests.isEmpty())
    }

    @Test fun pickingANewFile_clearsPreviousSummary() {
        pdf.text = "Body"
        val vm = viewModel(aiMode = true)
        vm.onFilePicked(SAMPLE_REF)
        pdf.text = ""
        vm.onFilePicked(SAMPLE_REF)
        assertNull(vm.state.value.summary)
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testFossDebugUnitTest --tests "*ExtractTextViewModelTest" --console=plain -q`. Expected: compilation failure.

- [ ] **Step 3: Contract.** Create `$PKG/presentation/feature/tool/extracttext/ExtractTextContract.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.feature.tool.extracttext

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.model.Summary
import com.chethan616.clearpdf.presentation.base.UiEvent
import com.chethan616.clearpdf.presentation.base.UiState
import com.chethan616.clearpdf.presentation.common.UiText

/** Navigation argument set by the AI Summary tool (see nav_graph.xml). */
const val ARG_AI_MODE = "ai_mode"

data class ExtractTextUiState(
    /** Opened from the AI Summary tool: summarize as soon as text is ready. */
    val aiMode: Boolean = false,
    val source: DocumentInfo? = null,
    val isExtracting: Boolean = false,
    val text: String = "",
    val hasResult: Boolean = false,
    /** Inline warning under the file name. */
    val error: UiText? = null,
    /** No text layer was found: offer on-device OCR. */
    val canRecognize: Boolean = false,
    val isRecognizing: Boolean = false,
    /** (zero-based page, total) while OCR runs. */
    val recognizeProgress: Pair<Int, Int>? = null,
    val canMakeSearchable: Boolean = false,
    val isSavingSearchable: Boolean = false,
    val searchableOutput: OutputDocument? = null,
    /** The build can reach Gemini (key + INTERNET). */
    val summaryAvailable: Boolean = false,
    val isSummarizing: Boolean = false,
    val summary: Summary? = null,
    val summaryError: UiText? = null
) : UiState

sealed interface ExtractTextUiEvent : UiEvent {
    /** Ask before the first upload of document text to Gemini. */
    data object ShowSummaryConsent : ExtractTextUiEvent
}
```

- [ ] **Step 4: ViewModel.** Create `$PKG/presentation/feature/tool/extracttext/ExtractTextViewModel.kt`:

```kotlin
package com.chethan616.clearpdf.presentation.feature.tool.extracttext

import androidx.lifecycle.SavedStateHandle
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.domain.usecase.summary.GetSummaryAvailabilityUseCase
import com.chethan616.clearpdf.domain.usecase.summary.GrantSummaryConsentUseCase
import com.chethan616.clearpdf.domain.usecase.summary.SummarizeTextUseCase
import com.chethan616.clearpdf.domain.usecase.text.ExtractTextUseCase
import com.chethan616.clearpdf.domain.usecase.text.MakeSearchablePdfUseCase
import com.chethan616.clearpdf.domain.usecase.text.RecognizeTextUseCase
import com.chethan616.clearpdf.presentation.base.BaseViewModel
import com.chethan616.clearpdf.presentation.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ExtractTextViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val openDocument: OpenDocumentUseCase,
    private val extractText: ExtractTextUseCase,
    private val recognizeText: RecognizeTextUseCase,
    private val makeSearchablePdf: MakeSearchablePdfUseCase,
    private val summarizeText: SummarizeTextUseCase,
    private val getSummaryAvailability: GetSummaryAvailabilityUseCase,
    private val grantSummaryConsent: GrantSummaryConsentUseCase
) : BaseViewModel<ExtractTextUiState, ExtractTextUiEvent>(
    ExtractTextUiState(aiMode = savedStateHandle.get<Boolean>(ARG_AI_MODE) == true)
) {
    /** OCR word boxes, kept so "Save as Searchable PDF" doesn't re-run OCR. */
    private var wordsByPage: Map<Int, List<OcrWord>> = emptyMap()

    /** AI mode asks for a summary at most once per picked file. */
    private var autoSummaryRequested = false

    init {
        setState { copy(summaryAvailable = getSummaryAvailability() != SummaryAvailability.UNAVAILABLE) }
    }

    fun onFilePicked(ref: DocumentRef) {
        wordsByPage = emptyMap()
        autoSummaryRequested = false
        setState { ExtractTextUiState(aiMode = aiMode, summaryAvailable = summaryAvailable, isExtracting = true) }
        launch(onError = {
            setState { copy(isExtracting = false, error = UiText.res(R.string.text_extraction_failed)) }
        }) {
            val source = openDocument(ref)
            val text = extractText(source)
            setState {
                copy(
                    source = source,
                    isExtracting = false,
                    text = text,
                    hasResult = true,
                    canRecognize = text.isEmpty(),
                    error = if (text.isEmpty()) UiText.res(R.string.extract_no_selectable_text) else null
                )
            }
            summarizeIfAiMode()
        }
    }

    fun onRecognizeClicked() {
        val source = currentState.source ?: return
        if (currentState.isRecognizing) return
        setState { copy(isRecognizing = true, recognizeProgress = null) }
        launch(onError = { setState { copy(isRecognizing = false, recognizeProgress = null) } }) {
            val result = recognizeText(source) { page, total -> setState { copy(recognizeProgress = page to total) } }
            wordsByPage = result.wordsByPage
            setState {
                copy(
                    isRecognizing = false,
                    recognizeProgress = null,
                    text = result.text,
                    hasResult = true,
                    canRecognize = false,
                    canMakeSearchable = result.wordsByPage.isNotEmpty(),
                    error = if (result.text.isEmpty()) UiText.res(R.string.extract_no_selectable_text) else null
                )
            }
            summarizeIfAiMode()
        }
    }

    fun onMakeSearchableClicked() {
        val source = currentState.source ?: return
        if (wordsByPage.isEmpty() || currentState.isSavingSearchable) return
        setState { copy(isSavingSearchable = true, error = null, searchableOutput = null) }
        launch(onError = { e ->
            setState {
                copy(isSavingSearchable = false, error = UiText.Raw(e.message ?: "Couldn't create a searchable PDF"))
            }
        }) {
            val output = makeSearchablePdf(source, wordsByPage)
            setState { copy(isSavingSearchable = false, searchableOutput = output) }
        }
    }

    fun onSummarizeClicked() {
        if (currentState.text.isBlank() || currentState.isSummarizing) return
        when (getSummaryAvailability()) {
            SummaryAvailability.UNAVAILABLE -> Unit
            SummaryAvailability.NEEDS_CONSENT -> sendEvent(ExtractTextUiEvent.ShowSummaryConsent)
            SummaryAvailability.READY -> summarize()
        }
    }

    fun onSummaryConsentGranted() {
        grantSummaryConsent()
        summarize()
    }

    private fun summarizeIfAiMode() {
        if (!currentState.aiMode || autoSummaryRequested || currentState.text.isEmpty()) return
        autoSummaryRequested = true
        onSummarizeClicked()
    }

    private fun summarize() {
        val text = currentState.text
        if (text.isBlank() || currentState.isSummarizing) return
        setState { copy(isSummarizing = true, summary = null, summaryError = null) }
        launch(onError = { e ->
            val message = if (e is AppError.QuotaExceeded) {
                UiText.res(R.string.ai_summary_quota)
            } else {
                UiText.res(R.string.ai_summary_failed, e.message.orEmpty())
            }
            setState { copy(isSummarizing = false, summaryError = message) }
        }) {
            val summary = summarizeText(text)
            setState { copy(isSummarizing = false, summary = summary) }
        }
    }
}
```

- [ ] **Step 5: Run tests.** `./gradlew :app:testFossDebugUnitTest --tests "*ExtractTextViewModelTest" --console=plain -q`. Expected: PASS (12 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/chethan616/clearpdf/presentation/feature/tool/extracttext app/src/test/java/com/chethan616/clearpdf/presentation/feature/tool/extracttext
git commit -m "feat(extract-text): ViewModel on use cases, AI mode and consent in the ViewModel"
```

---

### Task 11: Extract Text + AI Summary screen (reference screen 2)

**Files:**
- Create: `$PKG/presentation/feature/tool/extracttext/ExtractTextFragment.kt`
- Modify: `app/src/main/res/navigation/nav_graph.xml` (`extract_text` and `ai_summary` `android:name`)
- Delete: `$PKG/ui/viewmodel/ExtractTextViewModel.kt`, `$PKG/ui/fragment/tool/ExtractTextFragment.kt`, `$PKG/data/ai/GeminiSummarizer.kt`

**Interfaces:**
- Consumes: Task 10 ViewModel/contract, `BaseToolFragment`, `UiText`, `toDocumentRef()`/`toUri()`.

- [ ] **Step 1: Fragment.** Create `$PKG/presentation/feature/tool/extracttext/ExtractTextFragment.kt` (same views/texts as today's `ui/fragment/tool/ExtractTextFragment.kt`; the auto-summary and consent logic now live in the ViewModel):

```kotlin
package com.chethan616.clearpdf.presentation.feature.tool.extracttext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.platform.toDocumentRef
import com.chethan616.clearpdf.databinding.ToolExtractTextBinding
import com.chethan616.clearpdf.presentation.base.BaseToolFragment
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ExtractTextFragment : BaseToolFragment<ToolExtractTextBinding>(ToolExtractTextBinding::inflate) {

    private val viewModel: ExtractTextViewModel by viewModels()

    private val aiMode get() = arguments?.getBoolean(ARG_AI_MODE) == true

    override val titleRes get() = if (aiMode) R.string.tool_ai_summary else R.string.tool_extract

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toDocumentRef())
    }

    override fun initViews(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_extract_text)
        ToolUi.primaryButton(body.pick, accent)
        ToolUi.primaryButton(body.recognize, accent)
        ToolUi.primaryButton(body.summarize, accent)
        ToolUi.primaryButton(body.searchable, requireContext().color(R.color.app_success))
    }

    override fun initListeners() {
        body.pick.setOnClickListener { picker.launch(arrayOf("application/pdf")) }
        body.copy.setOnClickListener { copyToClipboard(getString(R.string.tool_extract), viewModel.state.value.text) }
        body.share.setOnClickListener { shareText(viewModel.state.value.text) }
        body.recognize.setOnClickListener { viewModel.onRecognizeClicked() }
        body.searchable.setOnClickListener { viewModel.onMakeSearchableClicked() }
        body.summarize.setOnClickListener { viewModel.onSummarizeClicked() }
        body.summaryCopy.setOnClickListener {
            copyToClipboard(getString(R.string.ai_summary_title), viewModel.state.value.summary?.text.orEmpty())
        }
    }

    override fun observeViewModel() {
        viewModel.state.collectOnStarted(::render)
        viewModel.events.collectOnStarted { event ->
            when (event) {
                ExtractTextUiEvent.ShowSummaryConsent -> showConsentDialog()
            }
        }
    }

    private fun showConsentDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_summary_consent_title)
            .setMessage(R.string.ai_summary_consent_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_summary_consent_accept) { _, _ -> viewModel.onSummaryConsentGranted() }
            .show()
    }

    private fun render(state: ExtractTextUiState) {
        val ctx = requireContext()
        val hasText = state.text.isNotEmpty()
        body.copy.isVisible = hasText
        body.share.isVisible = hasText
        body.sourceName.text = state.source?.name
        body.sourceName.isVisible = state.source != null
        body.error.text = state.error?.resolve(ctx)
        body.error.isVisible = state.error != null
        body.recognize.isVisible = state.canRecognize && !state.isRecognizing
        body.searchable.isVisible = state.canMakeSearchable
        ToolUi.setBusy(body.searchable, state.isSavingSearchable, null, getString(R.string.extract_make_searchable))
        body.searchableSaved.isVisible = state.searchableOutput != null
        body.searchableSaved.text = getString(R.string.extract_searchable_saved, state.searchableOutput?.locationLabel.orEmpty())

        body.summarize.isVisible = hasText && state.summaryAvailable
        ToolUi.setBusy(
            body.summarize, state.isSummarizing, R.drawable.ic_auto_awesome,
            getString(if (state.isSummarizing) R.string.ai_summary_working else R.string.ai_summary_action)
        )
        body.summaryCard.isVisible = state.summary != null
        val summaryText = state.summary?.text.orEmpty()
        if (body.summaryText.text.toString() != summaryText) body.summaryText.text = summaryText
        body.summaryNote.setText(if (state.summary?.truncated == true) R.string.ai_summary_truncated_note else R.string.ai_summary_note)
        body.summaryError.text = state.summaryError?.resolve(ctx)
        body.summaryError.isVisible = state.summaryError != null

        val busy = state.isExtracting || state.isRecognizing
        body.text.isVisible = hasText && !busy
        if (body.text.text.toString() != state.text) body.text.text = state.text
        body.placeholder.isVisible = !hasText || busy
        body.spinner.isVisible = busy
        val progress = state.recognizeProgress
        body.placeholderText.text = when {
            state.isExtracting -> null
            state.isRecognizing && progress != null -> getString(R.string.extract_recognizing, progress.first + 1, progress.second)
            state.isRecognizing -> getString(R.string.extract_recognize_text)
            state.hasResult -> getString(R.string.extract_no_text)
            state.aiMode -> getString(R.string.ai_summary_pick_hint)
            else -> getString(R.string.extract_pick_hint)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.extract_share_text))) }
    }
}
```

`titleRes` is read in `onViewCreated`, after arguments are attached, so the getter is safe.

- [ ] **Step 2: Route + delete old code.** In `nav_graph.xml` set both the `extract_text` and `ai_summary` fragments' `android:name` to `com.chethan616.clearpdf.presentation.feature.tool.extracttext.ExtractTextFragment`. Delete `$PKG/ui/viewmodel/ExtractTextViewModel.kt`, `$PKG/ui/fragment/tool/ExtractTextFragment.kt`, `$PKG/data/ai/GeminiSummarizer.kt`. Then `grep -rn "GeminiSummarizer\|ui.viewmodel.ExtractText\|ui.fragment.tool.ExtractTextFragment" app/src` must print nothing.

- [ ] **Step 3: Build + all tests.** `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:testFossDebugUnitTest --console=plain -q`. Expected: exit 0.

- [ ] **Step 4: Device smoke — Extract Text.** Install fossDebug. Tools → Extract Text → pick `sample-local-pdf2.pdf`: text appears, Copy/Share work, "Summarize with AI" visible. Pick a scanned PDF (or any image-only PDF on the device): warning + "Recognize Text (OCR)"; run it: progress "(1/N)", text appears, "Save as Searchable PDF" works and shows the saved line.

- [ ] **Step 5: Device smoke — AI Summary.** Clear consent first: `adb shell run-as com.chethan616.clearpdf rm shared_prefs/clearpdf_ai.xml` then force-stop. Tools → AI Summary → pick `sample-local-pdf2.pdf`: consent dialog appears once; Cancel → no dialog loop, button still visible; tap the button → dialog again → accept → summary card appears. Pick the same file again: summary runs without a dialog. Rotate: summary stays, no dialog replays.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/java/com/chethan616/clearpdf/presentation/feature/tool/extracttext app/src/main/res/navigation/nav_graph.xml app/src/main/java/com/chethan616/clearpdf/ui/viewmodel/ExtractTextViewModel.kt app/src/main/java/com/chethan616/clearpdf/ui/fragment/tool/ExtractTextFragment.kt app/src/main/java/com/chethan616/clearpdf/data/ai/GeminiSummarizer.kt
git commit -m "refactor(extract-text): move Extract Text + AI Summary onto MVVM + Clean Architecture"
```

---

### Task 12: Phase 0–2 verification and hand-off

**Files:** none new.

- [ ] **Step 1: Layer rules.** Each must print nothing:

```bash
grep -rln "import android\.\|import androidx\.\|import com.kyant\|clearpdf.data\.\|clearpdf.presentation\." app/src/main/java/com/chethan616/clearpdf/domain
grep -rln "import android.content.Context\|import android.net.Uri\|clearpdf.data\." app/src/main/java/com/chethan616/clearpdf/presentation/feature --include=*ViewModel.kt
grep -rln "clearpdf.data\." app/src/main/java/com/chethan616/clearpdf/presentation
```

- [ ] **Step 2: Full build + tests both flavors.** `./gradlew :app:assembleFossDebug :app:assemblePlayDebug :app:assemblePlayRelease :app:testFossDebugUnitTest :app:testPlayDebugUnitTest --console=plain -q`. Expected: exit 0 (release proves R8 keeps Hilt-generated classes).

- [ ] **Step 3: Regression smoke of untouched screens.** On device: Home lists files, open a PDF in the viewer, Merge two PDFs, Images to PDF (grid from Task 3), Settings opens. `adb logcat -d -s AndroidRuntime:E` empty.

- [ ] **Step 4: Report to the user** (in Vietnamese): what changed, the three deliberate deviations, test counts, and ask them to review the two reference screens (`presentation/feature/tool/compress`, `presentation/feature/tool/extracttext`) before the phase 3–6 plan is written.
