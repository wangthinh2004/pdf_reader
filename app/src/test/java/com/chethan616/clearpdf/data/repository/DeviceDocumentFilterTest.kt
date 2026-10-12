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

    @Test fun acceptsHwpAndTxt() {
        assertEquals(DocKind.Hwp, DeviceDocumentFilter.accept(row("a.hwp"))?.kind)
        assertEquals(DocKind.Hwp, DeviceDocumentFilter.accept(row("a.hwpx"))?.kind)
        assertEquals(DocKind.Txt, DeviceDocumentFilter.accept(row("a.txt"))?.kind)
        assertNull(DeviceDocumentFilter.accept(row("a.csv")))
    }

    @Test fun classifiesCaseInsensitively() {
        assertEquals(DocKind.Pdf, DeviceDocumentFilter.accept(row("REPORT.PDF"))?.kind)
        assertEquals(DocKind.Excel, DeviceDocumentFilter.accept(row("Budget.XLSX"))?.kind)
    }

    @Test fun rejectsUnsupportedNames() {
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

    // Before Android 10 MediaProvider leaves _display_name NULL for non-media files; only _data is set.
    @Test fun nameFallsBackToDataPathWhenDisplayNameMissing() {
        val c = DeviceDocumentFilter.accept(row(null, rel = null, data = "/storage/emulated/0/Download/Report.DOCX"))
        assertEquals("Report.DOCX", c?.name)
        assertEquals(DocKind.Word, c?.kind)
        assertEquals("Download", c?.folder)
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
