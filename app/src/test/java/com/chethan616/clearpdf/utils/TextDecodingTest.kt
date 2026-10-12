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

    @Test fun readCappedReturnsWholeSmallInput() {
        val (bytes, truncated) = readCapped("a\nb".toByteArray().inputStream(), maxBytes = 100)
        assertEquals("a\nb", String(bytes))
        assertEquals(false, truncated)
    }

    @Test fun readCappedCutsAtLastNewlineSoMultibyteCharsStayWhole() {
        // "가" is 3 bytes in UTF-8; a cap of 6 would split the second line's character.
        val input = "가\n가나".toByteArray(Charsets.UTF_8)
        val (bytes, truncated) = readCapped(input.inputStream(), maxBytes = 6)
        assertEquals("가\n", decodeText(bytes))
        assertEquals(true, truncated)
    }

    @Test fun emptyInputIsEmptyString() {
        assertEquals("", decodeText(ByteArray(0)))
    }
}
