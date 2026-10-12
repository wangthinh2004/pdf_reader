package com.chethan616.clearpdf.ui.fragment.viewer

import androidx.lifecycle.ViewModel
import com.chethan616.clearpdf.ui.viewmodel.OcrTextBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A markup picked for move / resize / edit: its page and index in that page's list. */
data class MarkupRef(val page: Int, val index: Int)

/** Character position inside the reading-ordered text blocks of a page. */
data class TextPos(val block: Int, val char: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos) = compareValuesBy(this, other, { it.block }, { it.char })
}

/** A text selection on one page, from [anchor] to [focus] (either order). */
data class TextSelection(val page: Int, val anchor: TextPos, val focus: TextPos) {
    val start get() = minOf(anchor, focus)
    val end get() = maxOf(anchor, focus)

    /** The selected range of each block, in reading order: (block, start, end). */
    fun ranges(blocks: List<OcrTextBlock>): List<Triple<OcrTextBlock, Int, Int>> {
        val s = start; val e = end
        return (s.block..e.block).mapNotNull { i ->
            val b = blocks.getOrNull(i) ?: return@mapNotNull null
            val from = if (i == s.block) s.char else 0
            val to = if (i == e.block) e.char else b.text.length
            if (to > from) Triple(b, from, to) else null
        }
    }

    fun text(blocks: List<OcrTextBlock>): String = ranges(blocks).joinToString("\n") { (b, s, e) -> b.text.substring(s, e) }
}

/** Blocks in reading order: top to bottom, then left to right. */
fun List<OcrTextBlock>.readingOrder(): List<OcrTextBlock> = sortedWith(compareBy({ it.top }, { it.left }))

/** Editor state for the open document. Survives configuration changes; cleared for a new document. */
class AnnotationsViewModel : ViewModel() {

    private val pages = mutableMapOf<Int, MutableList<Markup>>()
    private var saved: Map<Int, List<Markup>> = emptyMap()
    /** Pages that gained a markup, newest last — undo removes the last markup of the last entry. */
    private val undoPages = ArrayDeque<Int>()

    private val _version = MutableStateFlow(0)
    /** Bumped on every change so views can redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    var documentKey: String? = null
        private set

    var tool = EditTool.None
    var color: Int = 0xFF00BCD4.toInt()
    /** Stroke width as a fraction of the page width. */
    var strokeWidth = 0.008f
    var selection: MarkupRef? = null
    var textSelection: TextSelection? = null

    fun resetFor(key: String) {
        if (documentKey == key) return
        documentKey = key
        pages.clear(); saved = emptyMap(); undoPages.clear()
        tool = EditTool.None; selection = null; textSelection = null
        bump()
    }

    fun markups(page: Int): List<Markup> = pages[page].orEmpty()

    fun add(page: Int, m: Markup) {
        pages.getOrPut(page) { mutableListOf() }.add(m)
        undoPages.addLast(page)
        bump()
    }

    fun replace(ref: MarkupRef, m: Markup) {
        val list = pages[ref.page] ?: return
        if (ref.index in list.indices) { list[ref.index] = m; bump() }
    }

    fun remove(ref: MarkupRef) {
        val list = pages[ref.page] ?: return
        if (ref.index !in list.indices) return
        val removed = list.removeAt(ref.index)
        // Text markups made by one selection share a group id: removing one removes the set.
        val group = when (removed) {
            is Markup.TextHighlight -> removed.groupId
            is Markup.TextLine -> removed.groupId
            else -> 0L
        }
        if (group != 0L) list.removeAll { (it as? Markup.TextHighlight)?.groupId == group || (it as? Markup.TextLine)?.groupId == group }
        if (selection == ref) selection = null
        bump()
    }

    fun get(ref: MarkupRef): Markup? = pages[ref.page]?.getOrNull(ref.index)

    /** Removes the most recently added markup, wherever it is. */
    fun undo(fallbackPage: Int) {
        while (undoPages.isNotEmpty()) {
            val page = undoPages.removeLast()
            val list = pages[page]
            if (!list.isNullOrEmpty()) { list.removeAt(list.lastIndex); selection = null; bump(); return }
        }
        pages[fallbackPage]?.let { if (it.isNotEmpty()) { it.removeAt(it.lastIndex); selection = null; bump() } }
    }

    fun clearPage(page: Int) {
        pages[page]?.clear()
        undoPages.removeAll { it == page }
        selection = null
        bump()
    }

    fun hasMarkups(): Boolean = pages.values.any { it.isNotEmpty() }

    fun snapshot(): Map<Int, List<Markup>> = pages.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }

    /** True when the markups differ from what was last saved. */
    fun isDirty(): Boolean = snapshot() != saved

    fun markSaved(snapshot: Map<Int, List<Markup>>) { saved = snapshot }

    fun bump() { _version.value++ }
}
