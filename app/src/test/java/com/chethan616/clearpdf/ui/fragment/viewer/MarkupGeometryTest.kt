package com.chethan616.clearpdf.ui.fragment.viewer

import com.chethan616.clearpdf.ui.viewmodel.OcrTextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupGeometryTest {

    @Test
    fun partialCharacterGeometryKeepsHighlightWithinSelectedWord() {
        val text = "A full word remains selected"
        val block = OcrTextBlock(
            id = "line-1", text = text, left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.3f,
            // An incomplete glyph map must not widen the range to the whole line.
            charLefts = floatArrayOf(0.10f, 0.13f, 0.16f),
            charRights = floatArrayOf(0.12f, 0.15f, 0.18f)
        )
        val start = text.indexOf("word")
        val (left, right) = textRangeEdges(block, start, start + "word".length)
        assertTrue("starts inside the line", left > 0.1f)
        assertTrue("ends before the line ends", right < 0.9f)
        assertEquals(4f * 0.8f / text.length, right - left, 0.0001f)
    }

    @Test
    fun completeCharacterGeometryUsesExclusiveRangeEnd() {
        val block = OcrTextBlock(
            id = "line-2", text = "hello world", left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.3f,
            charLefts = FloatArray(11) { 0.1f + it * 0.05f },
            charRights = FloatArray(11) { 0.15f + it * 0.05f }
        )
        val (left, right) = textRangeEdges(block, 6, 11)
        assertEquals(0.4f, left, 0.0001f)
        assertEquals(0.65f, right, 0.0001f)
    }

    @Test
    fun textSelectionRangesSpanBlocksInOrder() {
        val blocks = listOf(
            OcrTextBlock("a", "first line", 0f, 0f, 1f, 0.1f),
            OcrTextBlock("b", "second line", 0f, 0.1f, 1f, 0.2f)
        )
        val sel = TextSelection(0, anchor = TextPos(1, 6), focus = TextPos(0, 6))
        assertEquals("line\nsecond", sel.text(blocks))
    }
}
