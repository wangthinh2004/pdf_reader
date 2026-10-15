package com.kyant.pdfcore.word

import java.text.Normalizer

/** Turns positioned glyphs into text runs: spaces from gaps, runs split on style changes. */
internal object TextBuilder {

    /** A gap wider than this fraction of the font size is a word space. */
    private const val SPACE_GAP = 0.18f

    fun runs(glyphs: List<Glyph>): List<Run> {
        val sorted = glyphs.sortedBy { it.left }
        val out = ArrayList<Run>()
        val text = StringBuilder()
        var style: TextStyle? = null
        var previous: Glyph? = null

        fun flush() {
            val s = style ?: return
            if (text.isNotEmpty()) out += Run(clean(text.toString()), s)
            text.setLength(0)
        }

        for (g in sorted) {
            val prev = previous
            if (prev != null) {
                val gap = g.left - prev.right
                val wide = gap > SPACE_GAP * maxOf(g.style.size, prev.style.size) || (g.isWord && prev.isWord)
                val needsSpace = wide && !prev.text.endsWith(' ') && !g.text.startsWith(' ')
                // The space takes the earlier run's style, so "bold word" + space + "plain" doesn't
                // start a bold-space run.
                if (needsSpace) text.append(' ')
            }
            val glyphStyle = g.style
            if (style != null && glyphStyle != style) {
                // Whitespace-only glyphs never start a new run on their own.
                if (g.text.isBlank()) {
                    text.append(g.text); previous = g; continue
                }
                flush()
            }
            style = glyphStyle
            text.append(g.text)
            previous = g
        }
        flush()
        return merge(out)
    }

    /** Joins neighbouring runs that ended up with the same style. */
    fun merge(runs: List<Run>): List<Run> {
        val out = ArrayList<Run>(runs.size)
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.style == r.style) out[out.lastIndex] = Run(last.text + r.text, r.style)
            else out += r
        }
        return out
    }

    /**
     * NFC (PDFs often store Vietnamese as base letter + combining mark), private-use bullets mapped
     * to "•", and characters XML 1.0 can't carry dropped.
     */
    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in Normalizer.normalize(s, Normalizer.Form.NFC)) {
            when {
                ch == '\t' || ch == '\n' -> sb.append(ch)
                ch.code < 0x20 || ch == '￾' || ch == '￿' || ch == '�' -> {}
                ch in ''..'' || ch == '' || ch == '' || ch == '' -> sb.append('•')
                ch == ' ' -> sb.append(' ')
                Character.isSurrogate(ch) -> sb.append(ch)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
