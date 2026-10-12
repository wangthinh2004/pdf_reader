# HWP / HWPX and TXT documents — Design

Date: 2026-10-09
Status: Approved in chat, awaiting written-spec review

## Goal

Open Korean Hangul word-processor files (`.hwp`, `.hwpx`) read-only, and list HWP and TXT files on
Home with their own tabs: **All | PDF | Word | Excel | PPT | HWP | TXT**.

### Decisions made with the user
- HWP approach: extract text with `kr.dogfoot:hwplib` / `kr.dogfoot:hwpxlib`, lay it out as a PDF
  with the existing block renderer, open it in the existing PDF viewer ("approach 1").
- Two separate tabs: HWP (covers `.hwp` + `.hwpx`) and TXT.

### Assumptions (stated in chat, not contradicted)
- View only. Formatting, fonts, colours, images and page layout of HWP files are not preserved;
  paragraph text and table-cell text are.
- TXT tab lists `.txt` only. `.csv/.log/.md` keep opening as today but are not listed.
- Recents filter menu on Home also gains HWP and TXT, for consistency.
- TXT files that are not valid UTF-8 are decoded as CP949 (covers EUC-KR Korean text).

### Out of scope
- Editing or saving HWP. Images, styles, headers/footers fidelity.
- HWP 3.0 and older (hwplib reads HWP 5.x only).
- Ebook formats.
- Routing HWP through the optional LibreOffice engine.

## Libraries

| Artifact | Version | Licence | Runtime deps |
|---|---|---|---|
| `kr.dogfoot:hwplib` | 1.1.11 | Apache-2.0 | none |
| `kr.dogfoot:hwpxlib` | 1.0.9 | Apache-2.0 | none |

Verified 2026-10-09 by a JVM spike: both libraries write and read back Korean text. hwplib shades
its own POI copy (`kr.dogfoot.hwplib.org.apache.poi`), so no clash with the app's POI 3.17. The
only Android-missing API referenced is `javax.xml.bind` in `HWPReader.fromBase64String`, which we
never call; R8 needs `-dontwarn javax.xml.bind.**`.

Both target Java 7. Added to `gradle/libs.versions.toml` and `app/build.gradle.kts`; credited in
`THIRD_PARTY_NOTICES.md` and Settings → Licences alongside the other Apache-2.0 entries.

Risk: either library may touch a JDK API missing on Android (e.g. `java.awt`). The first
implementation step is a build plus a real-file smoke test; if a library fails on Android, stop
and report rather than work around it silently.

## Architecture

### 1. Classification — `utils/DocKind.kt`
- `enum class DocKind { Pdf, Word, Excel, Ppt, Hwp, Txt, Image, Other }`
- `docKindOf`: `.hwp`, `.hwpx` → `Hwp`; `.txt` → `Txt`. Everything else unchanged.
- Every exhaustive `when (kind)` is updated (`kindVisual`, any others the compiler flags).
  Routing in `DocsNavGraph` and `MainActivity` already sends non-Excel/non-Image kinds to the PDF
  viewer, so Hwp/Txt need no new route.

### 2. HWP text extraction — `utils/HwpTextExtractor.kt` (new)
```kotlin
object HwpTextExtractor {
    /** Paragraph texts in reading order. Throw on unreadable / encrypted / unsupported files. */
    fun extractHwp(input: InputStream): List<String>
    fun extractHwpx(file: File): List<String>   // hwpxlib only reads from a File (zip)
}
```
- The converter copies an `.hwpx` uri to a temp file in `cacheDir`, extracts, then deletes it.
- Library failures can surface as `Error`s (e.g. `NoClassDefFoundError`); the converter rethrows
  any `Throwable` from extraction as `IllegalStateException`, because the viewer only catches
  `Exception`.
- `.hwp`: hwplib reader → its text extractor, with control text (tables) inserted between
  paragraphs.
- `.hwpx`: hwpxlib reader → its text extractor, same mode.
- Output split on line breaks into paragraphs; blank runs collapsed to a single empty paragraph.
- Pure JVM code, no Android types, so it can be unit-tested with a fixture file.

### 3. Converter — `utils/UniversalDocumentConverter.kt`
- New branches before the text fallback:
  `name.endsWith(".hwp") || name.endsWith(".hwpx") -> convertHwpToPdf(context, uri)`.
- `convertHwpToPdf` reads the stream, calls `HwpTextExtractor`, maps each paragraph to
  `DocBlock.Para` with the default body paint, and writes through the existing
  `renderBlocks` / `writePdf` path (title tag "Hwp").
- Empty extraction (e.g. image-only document) → one paragraph explaining no text was found,
  rather than a blank PDF.
- `convertTextToPdf`: decode bytes as UTF-8 with a reporting decoder; on malformed input decode as
  CP949. Strip a UTF-8 BOM. Logic lives in a small pure function (`decodeText(bytes): String`) so it
  is unit-testable.

### 4. Home
- `DeviceDocumentFilter`: `EXTENSIONS += hwp, hwpx, txt`; `listedKinds += Hwp, Txt`.
  The MediaStore selection is built from `EXTENSIONS`, so the scanner picks them up.
- `HomeDeviceDocuments.KindTabs`: add `Hwp` and `Txt` entries (row already scrolls horizontally).
- `HomeScreen` recents filter menu (`categories`): add Hwp and Txt entries.
- `kindVisual`: `Hwp` → `Icons.Rounded.Article`, teal `0xFF00897B`, label "HWP";
  `Txt` → `Icons.Rounded.TextSnippet`, grey `0xFF607D8B`, label "TXT".
- Strings: `recents_filter_hwp` ("HWP"), `recents_filter_txt` ("TXT") in `values/`; `values-vi/`
  gets the same labels. Other locales fall back to English.

### 5. Opening from outside the app
- `DocsNavGraph.OPEN_DOC_MIMES` += `application/x-hwp`, `application/haansofthwp`,
  `application/vnd.hancom.hwp`, `application/vnd.hancom.hwpx`, `application/haansofthwpx`.
  Because many file managers report HWP as `application/octet-stream`, also add
  `application/octet-stream` only if testing shows the picker otherwise greys HWP files out.
- `AndroidManifest.xml`: one `VIEW` intent-filter carrying the HWP MIME types above.
- `MainActivity.isSupportedDocumentIntent`: add `.hwp`, `.hwpx` to the extension list and accept
  MIME types containing `hwp`.

## Error handling
- Extraction throws (corrupt, password-protected, HWP 3.0) → the viewer's existing conversion
  error path shows the message; no crash.
- Very large HWP: extraction happens on the converter's existing background dispatcher, same as
  `.doc`.

## Testing
- Unit (`app/src/test/...`):
  - `DocKindTest`: hwp/hwpx/txt/HWP (case) → expected kinds; csv stays Other.
  - `DeviceDocumentFilter` test: hwp, hwpx, txt rows accepted with the right kind.
  - `decodeText`: UTF-8, UTF-8 with BOM, CP949 Korean bytes.
  - `HwpTextExtractor`: the test builds `.hwp` / `.hwpx` files at runtime with the libraries'
    `BlankFileMaker` + writers (no binary fixtures), then asserts the Korean text comes back.
- Build: `./gradlew :app:assemblePlayDebug :app:testPlayDebugUnitTest`.
- Manual on device (user): Home shows HWP/TXT tabs; open a `.hwp`, a `.hwpx`, a Korean `.txt`.
