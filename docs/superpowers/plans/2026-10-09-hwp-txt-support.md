# HWP / HWPX and TXT Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open `.hwp` / `.hwpx` read-only (text → PDF → existing viewer) and list HWP and TXT files on Home under their own tabs.

**Architecture:** A pure-JVM `HwpTextExtractor` wraps hwplib/hwpxlib; `UniversalDocumentConverter` gains an HWP branch that feeds the extracted paragraphs to its existing `renderBlocks`. `DocKind` gains `Hwp` and `Txt`, which flows into the Home device filter, tabs, recents menu and kind visuals. TXT decoding becomes UTF-8-with-CP949-fallback.

**Tech Stack:** Kotlin, Jetpack Compose, `kr.dogfoot:hwplib:1.1.11`, `kr.dogfoot:hwpxlib:1.0.9`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-09-hwp-txt-support-design.md`

## Global Constraints

- Libraries: `kr.dogfoot:hwplib:1.1.11`, `kr.dogfoot:hwpxlib:1.0.9` (Apache-2.0, no runtime deps).
- View only; no editing/saving of HWP. HWP 3.0 and older are out of scope.
- Tabs, in order: All | PDF | Word | Excel | PPT | HWP | TXT. HWP tab = `.hwp` + `.hwpx`; TXT tab = `.txt` only.
- New strings: English in `values/strings.xml`, Vietnamese in `values-vi/strings.xml`.
- **Do not commit.** The working tree already holds the user's uncommitted edits in
  `app/build.gradle.kts`, `gradle/libs.versions.toml`, `HomeScreen.kt` and others; staging those
  files would sweep their work into our commits. Leave all changes unstaged; the user commits.
- Build/test commands (run from repo root, Git Bash):
  - Unit tests: `./gradlew :app:testPlayDebugUnitTest`
  - Single test class: `./gradlew :app:testPlayDebugUnitTest --tests "<fqcn>"`
  - Debug build: `./gradlew :app:assemblePlayDebug`
  - Release (R8) build: `./gradlew :app:assemblePlayRelease` (falls back to debug signing).

## Review Focus

1. A library `Error` (e.g. `NoClassDefFoundError` on Android) during extraction → user sees the normal "couldn't open" message, not a crash. Task 4 wraps every `Throwable` as `IllegalStateException`.
2. An HWP with no text (image-only) → a one-line "no text" PDF, not a blank/zero-page PDF. Covered by `HwpTextExtractorTest.blankDocumentYieldsNoParagraphs` + converter fallback in Task 4.
3. A Korean `.txt` saved as EUC-KR/CP949 → readable Hangul, not mojibake. Covered by `TextDecodingTest`.
4. Release build with R8 → HWP still opens (reflection/missing-class warnings). Task 1 adds keep/dontwarn rules; Task 7 builds release.
5. Upper-case extensions (`REPORT.HWP`, `NOTE.TXT`) → classified like lower-case. Covered in `DocKindTest`.

---

### Task 1: Add libraries and `HwpTextExtractor`

**Files:**
- Modify: `gradle/libs.versions.toml` (versions + libraries)
- Modify: `app/build.gradle.kts` (dependencies block, after the POI lines ~184)
- Modify: `app/proguard-rules.pro` (append)
- Modify: `THIRD_PARTY_NOTICES.md` (new section after "Apache POI")
- Modify: `app/src/main/java/com/chethan616/clearpdf/ui/screen/SettingsScreen.kt:614` (Credit list)
- Create: `app/src/main/java/com/chethan616/clearpdf/utils/HwpTextExtractor.kt`
- Test: `app/src/test/java/com/chethan616/clearpdf/utils/HwpTextExtractorTest.kt`

**Interfaces:**
- Produces:
  - `object HwpTextExtractor { fun extractHwp(input: java.io.InputStream): List<String>; fun extractHwpx(file: java.io.File): List<String>; internal fun toParagraphs(raw: String): List<String> }`
  - Paragraph list: trimmed lines, runs of blank lines collapsed to one `""`, no leading/trailing `""`.

- [ ] **Step 1: Add dependencies**

`gradle/libs.versions.toml` — under `[versions]` add:
```toml
hwplib = "1.1.11"
hwpxlib = "1.0.9"
```
under `[libraries]` add:
```toml
hwplib = { group = "kr.dogfoot", name = "hwplib", version.ref = "hwplib" }
hwpxlib = { group = "kr.dogfoot", name = "hwpxlib", version.ref = "hwpxlib" }
```
`app/build.gradle.kts` — directly after `implementation("org.apache.poi:poi-scratchpad:3.17")`:
```kotlin
    // Korean Hangul word-processor files: text extraction for .hwp (5.x) and .hwpx.
    // Apache-2.0, pure Java, no transitive deps; see THIRD_PARTY_NOTICES.md.
    implementation(libs.hwplib)
    implementation(libs.hwpxlib)
```
`app/proguard-rules.pro` — append:
```proguard
# ── HWP / HWPX (hwplib, hwpxlib) ──
# hwplib's HWPReader.fromBase64String references javax.xml.bind (absent on Android); we never call it.
-dontwarn javax.xml.bind.**
# Both libraries build their object trees through internal factories; keep them intact.
-keep class kr.dogfoot.** { *; }
```

- [ ] **Step 2: Write the failing test**

`app/src/test/java/com/chethan616/clearpdf/utils/HwpTextExtractorTest.kt`:
```kotlin
package com.chethan616.clearpdf.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HwpTextExtractorTest {

    private fun hwpBytes(text: String?): ByteArray {
        val file = kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker.make()
        if (text != null) {
            val p = file.bodyText.sectionList[0].getParagraph(0)
            if (p.text == null) p.createText()
            p.text.insertString(0, text)
        }
        return ByteArrayOutputStream().also { kr.dogfoot.hwplib.writer.HWPWriter.toStream(file, it) }.toByteArray()
    }

    private fun hwpxFile(text: String?): File {
        val file = kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker.make()
        if (text != null) file.sectionXMLFileList().get(0).getPara(0).getRun(0).addNewT().addText(text)
        return File.createTempFile("test", ".hwpx").apply {
            deleteOnExit()
            writeBytes(kr.dogfoot.hwpxlib.writer.HWPXWriter.toBytes(file))
        }
    }

    @Test fun extractsKoreanTextFromHwp() {
        val paras = HwpTextExtractor.extractHwp(ByteArrayInputStream(hwpBytes("안녕하세요 HWP")))
        assertEquals(listOf("안녕하세요 HWP"), paras)
    }

    @Test fun extractsKoreanTextFromHwpx() {
        val paras = HwpTextExtractor.extractHwpx(hwpxFile("안녕하세요 HWPX"))
        assertEquals(listOf("안녕하세요 HWPX"), paras)
    }

    @Test fun blankDocumentYieldsNoParagraphs() {
        assertTrue(HwpTextExtractor.extractHwp(ByteArrayInputStream(hwpBytes(null))).isEmpty())
        assertTrue(HwpTextExtractor.extractHwpx(hwpxFile(null)).isEmpty())
    }

    @Test(expected = Exception::class)
    fun garbageInputThrows() {
        HwpTextExtractor.extractHwp(ByteArrayInputStream("not a hwp".toByteArray()))
    }

    @Test fun toParagraphsCollapsesBlankRunsAndTrims() {
        assertEquals(
            listOf("a", "", "b", "c"),
            HwpTextExtractor.toParagraphs("\n  a \r\n\n\n b\nc\n\n")
        )
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testPlayDebugUnitTest --tests "com.chethan616.clearpdf.utils.HwpTextExtractorTest"`
Expected: compilation FAIL — `Unresolved reference: HwpTextExtractor`.

- [ ] **Step 4: Implement**

`app/src/main/java/com/chethan616/clearpdf/utils/HwpTextExtractor.kt`:
```kotlin
package com.chethan616.clearpdf.utils

import java.io.File
import java.io.InputStream
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwpxlib.reader.HWPXReader
import kr.dogfoot.hwpxlib.tool.textextractor.TextMarks

/**
 * Plain-text extraction for Korean Hangul word-processor files: `.hwp` (HWP 5.x, via hwplib) and
 * `.hwpx` (via hwpxlib). Table-cell text is kept inline, in reading order; styling, images and
 * layout are dropped. Pure JVM — no Android types — so it is unit-testable.
 */
object HwpTextExtractor {

    fun extractHwp(input: InputStream): List<String> {
        val file = HWPReader.fromInputStream(input)
        val raw = kr.dogfoot.hwplib.tool.textextractor.TextExtractor.extract(
            file, kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText
        )
        return toParagraphs(raw)
    }

    /** hwpxlib reads the zip container from a [File] only, not from a stream. */
    fun extractHwpx(file: File): List<String> {
        val hwpx = HWPXReader.fromFile(file)
        val marks = TextMarks().paraSeparatorAnd("\n").lineBreakAnd("\n").tabAnd("\t")
        val raw = kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor.extract(
            hwpx, kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText,
            true, marks
        )
        return toParagraphs(raw)
    }

    /** One entry per line, trimmed; blank runs collapse to a single "" and none lead or trail. */
    internal fun toParagraphs(raw: String): List<String> {
        val out = ArrayList<String>()
        for (line in raw.split('\n')) {
            val t = line.trim()
            if (t.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += t
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testPlayDebugUnitTest --tests "com.chethan616.clearpdf.utils.HwpTextExtractorTest"`
Expected: 5 tests PASS. If `extractsKoreanTextFromHwpx` fails only on a leading space, that means `trim()` was skipped — fix the implementation, not the test.

- [ ] **Step 6: Licences**

`THIRD_PARTY_NOTICES.md` — insert after the "Apache POI" section:
```markdown
## hwplib / hwpxlib

- Artifacts: `kr.dogfoot:hwplib:1.1.11`, `kr.dogfoot:hwpxlib:1.0.9` (neolord0)
- License: Apache License 2.0
- Source: https://github.com/neolord0/hwplib, https://github.com/neolord0/hwpxlib
- Use: read-only text extraction from Korean Hangul `.hwp` (HWP 5.x) and `.hwpx` documents, which
  the app lays out as a PDF for viewing. hwplib bundles a relocated copy of Apache POI's OLE2 reader
  under `kr.dogfoot.hwplib.org.apache.poi` (also Apache-2.0).
- Notice practice: keep the Apache 2.0 license text available with redistributed builds.
```
`SettingsScreen.kt` — next to the PdfBox-Android `Credit(...)` line (~614), add:
```kotlin
    Credit("hwplib / hwpxlib", "neolord0", "Apache License 2.0 — HWP / HWPX text extraction", "https://github.com/neolord0/hwplib"),
```

---

### Task 2: TXT decoding with CP949 fallback

**Files:**
- Create: `app/src/main/java/com/chethan616/clearpdf/utils/TextDecoding.kt`
- Modify: `app/src/main/java/com/chethan616/clearpdf/utils/UniversalDocumentConverter.kt:83-93` (`convertTextToPdf`)
- Test: `app/src/test/java/com/chethan616/clearpdf/utils/TextDecodingTest.kt`

**Interfaces:**
- Produces: `internal fun decodeText(bytes: ByteArray): String` (top-level, package `com.chethan616.clearpdf.utils`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.chethan616.clearpdf.utils

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Test

class TextDecodingTest {

    @Test fun decodesUtf8() {
        assertEquals("Xin chào 안녕", decodeText("Xin chào 안녕".toByteArray(Charsets.UTF_8)))
    }

    @Test fun stripsUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        assertEquals("hi", decodeText(bytes))
    }

    @Test fun fallsBackToCp949ForKorean() {
        val bytes = "안녕하세요".toByteArray(Charset.forName("EUC-KR"))
        assertEquals("안녕하세요", decodeText(bytes))
    }

    @Test fun emptyInputIsEmptyString() {
        assertEquals("", decodeText(ByteArray(0)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testPlayDebugUnitTest --tests "com.chethan616.clearpdf.utils.TextDecodingTest"`
Expected: compilation FAIL — `Unresolved reference: decodeText`.

- [ ] **Step 3: Implement**

`TextDecoding.kt`:
```kotlin
package com.chethan616.clearpdf.utils

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decode a text file's bytes: UTF-8 (BOM stripped) when valid, otherwise CP949 — the Windows
 * superset of EUC-KR that Korean `.txt` files are usually saved in. Charset names differ between
 * the JVM and Android's ICU, so the first available alias wins.
 */
internal fun decodeText(bytes: ByteArray): String {
    val hasBom = bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    } catch (_: CharacterCodingException) {
        String(body, koreanCharset)
    }
}

private val koreanCharset: Charset by lazy {
    listOf("x-windows-949", "MS949", "windows-949", "EUC-KR")
        .firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }
        ?: Charsets.ISO_8859_1
}
```

In `UniversalDocumentConverter.convertTextToPdf`, replace the `lines` read:
```kotlin
        val lines = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
            decodeText(stream.readBytes()).lines()
        } ?: emptyList()
```
Remove the now-unused `BufferedReader` / `InputStreamReader` imports only if nothing else in the file uses them (grep first).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testPlayDebugUnitTest --tests "com.chethan616.clearpdf.utils.TextDecodingTest"`
Expected: 4 tests PASS.

---

### Task 3: `DocKind.Hwp` / `DocKind.Txt`, Home filter and kind visuals

**Files:**
- Modify: `app/src/main/java/com/chethan616/clearpdf/utils/DocKind.kt`
- Modify: `app/src/main/java/com/chethan616/clearpdf/data/repository/DeviceDocumentFilter.kt:35-37`
- Modify: `app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeScreen.kt:1225-1232` (`kindVisual`)
- Test: Create `app/src/test/java/com/chethan616/clearpdf/utils/DocKindTest.kt`; Modify `app/src/test/java/com/chethan616/clearpdf/data/repository/DeviceDocumentFilterTest.kt`

**Interfaces:**
- Produces: `enum class DocKind { Pdf, Word, Excel, Ppt, Hwp, Txt, Image, Other }`; `docKindOf("x.hwp"|"x.hwpx") == Hwp`, `docKindOf("x.txt") == Txt`.

- [ ] **Step 1: Write the failing tests**

`DocKindTest.kt`:
```kotlin
package com.chethan616.clearpdf.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DocKindTest {
    @Test fun hwpAndHwpxAreHwp() {
        assertEquals(DocKind.Hwp, docKindOf("보고서.hwp"))
        assertEquals(DocKind.Hwp, docKindOf("report.hwpx"))
        assertEquals(DocKind.Hwp, docKindOf("REPORT.HWP"))
    }

    @Test fun txtIsTxt() {
        assertEquals(DocKind.Txt, docKindOf("notes.txt"))
        assertEquals(DocKind.Txt, docKindOf("NOTE.TXT"))
    }

    @Test fun otherTextFormatsStayOther() {
        assertEquals(DocKind.Other, docKindOf("data.csv"))
        assertEquals(DocKind.Other, docKindOf("readme.md"))
        assertEquals(DocKind.Other, docKindOf("a.hwp.zip"))
    }
}
```
`DeviceDocumentFilterTest.kt` — add inside the class:
```kotlin
    @Test fun acceptsHwpAndTxt() {
        assertEquals(DocKind.Hwp, DeviceDocumentFilter.accept(row("a.hwp"))?.kind)
        assertEquals(DocKind.Hwp, DeviceDocumentFilter.accept(row("a.hwpx"))?.kind)
        assertEquals(DocKind.Txt, DeviceDocumentFilter.accept(row("a.txt"))?.kind)
        assertNull(DeviceDocumentFilter.accept(row("a.csv")))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testPlayDebugUnitTest --tests "com.chethan616.clearpdf.utils.DocKindTest" --tests "com.chethan616.clearpdf.data.repository.DeviceDocumentFilterTest"`
Expected: compilation FAIL — `Unresolved reference: Hwp`.

- [ ] **Step 3: Implement**

`DocKind.kt`:
```kotlin
enum class DocKind { Pdf, Word, Excel, Ppt, Hwp, Txt, Image, Other }
```
and in `docKindOf`, after the `Ppt` line:
```kotlin
        n.endsWith(".hwp") || n.endsWith(".hwpx") -> DocKind.Hwp
        n.endsWith(".txt") -> DocKind.Txt
```
`DeviceDocumentFilter.kt`:
```kotlin
    val EXTENSIONS = listOf("pdf", "docx", "doc", "odt", "rtf", "xlsx", "xls", "pptx", "ppt", "hwp", "hwpx", "txt")

    private val listedKinds = setOf(DocKind.Pdf, DocKind.Word, DocKind.Excel, DocKind.Ppt, DocKind.Hwp, DocKind.Txt)
```
Update the KDoc above `object DeviceDocumentFilter` to say "Pdf/Word/Excel/Ppt/Hwp/Txt".

`HomeScreen.kt` `kindVisual` — add two branches (imports `androidx.compose.material.icons.rounded.Article`, `androidx.compose.material.icons.rounded.TextSnippet`):
```kotlin
    DocKind.Hwp   -> Triple(Icons.Rounded.Article,      Color(0xFF00897B), "HWP")
    DocKind.Txt   -> Triple(Icons.Rounded.TextSnippet,  Color(0xFF607D8B), "TXT")
```

- [ ] **Step 4: Compile and fix any other exhaustive `when`**

Run: `./gradlew :app:compilePlayDebugKotlin`
Expected: PASS. If the compiler reports a non-exhaustive `when` on `DocKind` elsewhere, add `Hwp`/`Txt` branches that mirror the `Word` branch's behaviour (they are document-like and route to the PDF viewer).

- [ ] **Step 5: Run tests to verify they pass**

Run: same as Step 2. Expected: all PASS (including the pre-existing filter tests).

---

### Task 4: HWP branch in the converter

**Files:**
- Modify: `app/src/main/java/com/chethan616/clearpdf/utils/UniversalDocumentConverter.kt` (`convertToPdf` `when`, new private fun near the legacy POI section ~line 500)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-vi/strings.xml`

**Interfaces:**
- Consumes: `HwpTextExtractor.extractHwp(InputStream)`, `HwpTextExtractor.extractHwpx(File)` (Task 1).
- Produces: `convertToPdf` returns a PDF uri for `.hwp`/`.hwpx`; throws `IllegalStateException` on any extraction failure.

- [ ] **Step 1: Add string**

`values/strings.xml` (near other viewer strings):
```xml
    <string name="hwp_no_text">This document has no text that can be shown.</string>
```
`values-vi/strings.xml`:
```xml
    <string name="hwp_no_text">Tài liệu này không có nội dung chữ để hiển thị.</string>
```

- [ ] **Step 2: Route in `convertToPdf`**

In the `when`, insert before the `mimeType.startsWith("text/") ...` branch:
```kotlin
            name.endsWith(".hwp")  -> convertHwpToPdf(context, sourceUri, isHwpx = false)
            name.endsWith(".hwpx") -> convertHwpToPdf(context, sourceUri, isHwpx = true)
```

- [ ] **Step 3: Implement `convertHwpToPdf`**

Add under the "Legacy POI formats" section:
```kotlin
    // ── Hangul (hwp / hwpx) ────────────────────────────────────────────────────

    /**
     * Text-only rendering of a Hangul document. Any library failure — including `Error`s such as a
     * missing JDK class on Android — is rethrown as [IllegalStateException]: the viewer catches
     * `Exception` and shows its "couldn't open" message, but an `Error` would crash the app.
     */
    private fun convertHwpToPdf(context: Context, uri: Uri, isHwpx: Boolean): Uri {
        val paras = try {
            if (isHwpx) {
                // hwpxlib reads its zip container from a File only.
                val tmp = File.createTempFile("hwpx_", ".hwpx", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("Cannot open HWPX")
                    HwpTextExtractor.extractHwpx(tmp)
                } finally {
                    tmp.delete()
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { HwpTextExtractor.extractHwp(it) }
                    ?: throw IllegalStateException("Cannot open HWP")
            }
        } catch (t: Throwable) {
            throw IllegalStateException("Unreadable HWP document", t)
        }
        val lines = paras.ifEmpty { listOf(context.getString(com.chethan616.clearpdf.R.string.hwp_no_text)) }
        // A blank paragraph keeps the author's spacing; StaticLayout needs at least one char.
        val blocks = lines.map { DocBlock.Para(SpannableStringBuilder(it.ifEmpty { " " })) }
        return writePdf(context, renderBlocks(blocks, bodyPaint()), "Hwp")
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compilePlayDebugKotlin`
Expected: PASS.

---

### Task 5: Home tabs and recents filter menu

**Files:**
- Modify: `app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeDeviceDocuments.kt:107-114` (`KindTabs`)
- Modify: `app/src/main/java/com/chethan616/clearpdf/ui/screen/HomeScreen.kt:802-809` (`categories`)
- Modify: `app/src/main/res/values/strings.xml:402`, `app/src/main/res/values-vi/strings.xml`

**Interfaces:**
- Consumes: `DocKind.Hwp`, `DocKind.Txt` (Task 3); `kindVisual` icons (Task 3).
- Produces: strings `recents_filter_hwp`, `recents_filter_txt`.

- [ ] **Step 1: Strings**

`values/strings.xml`, after `recents_filter_image`:
```xml
    <string name="recents_filter_hwp">HWP</string>
    <string name="recents_filter_txt">TXT</string>
```
`values-vi/strings.xml` (append inside `<resources>`):
```xml
    <string name="recents_filter_hwp">HWP</string>
    <string name="recents_filter_txt">TXT</string>
```

- [ ] **Step 2: Device tabs**

`HomeDeviceDocuments.kt` `KindTabs` list becomes:
```kotlin
    val tabs = listOf(
        null to R.string.recents_filter_all,
        DocKind.Pdf to R.string.recents_filter_pdf,
        DocKind.Word to R.string.recents_filter_word,
        DocKind.Excel to R.string.recents_filter_excel,
        DocKind.Ppt to R.string.recents_filter_ppt,
        DocKind.Hwp to R.string.recents_filter_hwp,
        DocKind.Txt to R.string.recents_filter_txt
    )
```

- [ ] **Step 3: Recents filter menu**

`HomeScreen.kt` `categories`, insert after the `DocKind.Ppt` entry (before `Image`):
```kotlin
                Triple(DocKind.Hwp, Icons.Rounded.Article, stringResource(R.string.recents_filter_hwp)) to Color(0xFF00897B),
                Triple(DocKind.Txt, Icons.Rounded.TextSnippet, stringResource(R.string.recents_filter_txt)) to Color(0xFF607D8B),
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compilePlayDebugKotlin`
Expected: PASS.

---

### Task 6: Open HWP from other apps and the file picker

**Files:**
- Modify: `app/src/main/java/com/chethan616/clearpdf/ui/navigation/DocsNavGraph.kt:109-119` (`OPEN_DOC_MIMES`)
- Modify: `app/src/main/AndroidManifest.xml` (new intent-filter after the "Open plain text files" one, ~line 140)
- Modify: `app/src/main/java/com/chethan616/clearpdf/MainActivity.kt:92-107` (`isSupportedDocumentIntent`)

- [ ] **Step 1: Picker MIME types**

In `OPEN_DOC_MIMES`, before `"text/*"`:
```kotlin
    "application/x-hwp",                                                        // hwp
    "application/haansofthwp",                                                  // hwp
    "application/vnd.hancom.hwp",                                               // hwp
    "application/vnd.hancom.hwpx",                                              // hwpx
    "application/haansofthwpx",                                                 // hwpx
```

- [ ] **Step 2: Manifest**

After the "Open plain text files" `<intent-filter>`:
```xml
            <!-- Open Hangul (HWP / HWPX) documents -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" />
                <data android:mimeType="application/x-hwp" />
                <data android:mimeType="application/haansofthwp" />
                <data android:mimeType="application/vnd.hancom.hwp" />
                <data android:mimeType="application/vnd.hancom.hwpx" />
                <data android:mimeType="application/haansofthwpx" />
            </intent-filter>
```

- [ ] **Step 3: MainActivity**

In `isSupportedDocumentIntent`, add `".hwp", ".hwpx"` to `supportedExtension`'s list, and add to the boolean chain:
```kotlin
            mimeType?.contains("hwp", ignoreCase = true) == true ||
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compilePlayDebugKotlin`
Expected: PASS.

---

### Task 7: Full verification

- [ ] **Step 1: All unit tests**

Run: `./gradlew :app:testPlayDebugUnitTest`
Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 2: Debug and release builds**

Run: `./gradlew :app:assemblePlayDebug :app:assemblePlayRelease`
Expected: BUILD SUCCESSFUL. An R8 "Missing class" error naming a `kr.dogfoot`-referenced JDK class means the dontwarn list needs that package — add it to the Task 1 proguard block and rebuild.

- [ ] **Step 3: Hand off manual checks to the user**

Report: Home shows HWP and TXT tabs; open a real `.hwp`, a `.hwpx` and a Korean `.txt` on a device (debug and, ideally, release APK); "Open with ClearPDF" from a file manager for an `.hwp`. Note that HWPX parsing uses Android's SAX parser, which JVM tests do not exercise — the on-device open is the real check.
