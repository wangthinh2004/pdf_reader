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
