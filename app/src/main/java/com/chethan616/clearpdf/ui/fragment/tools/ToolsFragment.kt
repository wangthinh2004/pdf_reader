package com.chethan616.clearpdf.ui.fragment.tools

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.FragmentToolsBinding
import com.chethan616.clearpdf.databinding.ItemToolTileBinding
import com.chethan616.clearpdf.databinding.ItemToolWideBinding
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.SearchHeader
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground

/** Tools tab: every PDF tool, grouped in sections, with search. */
class ToolsFragment : Fragment() {

    private data class Tool(
        @StringRes val title: Int,
        @StringRes val subtitle: Int,
        @ColorRes val accent: Int,
        @DrawableRes val icon: Int,
        @IdRes val destination: Int
    )

    private data class Section(@StringRes val label: Int, val tools: List<Tool>)

    private val openPdf = Tool(R.string.tool_open_pdf, R.string.tool_open_pdf_sub, R.color.tool_open, R.drawable.ic_file_open, R.id.pdf_viewer)

    // Web/HTML to PDF stays unlisted in the privacy-focused release; its route is kept.
    private val sections = listOf(
        Section(
            R.string.tools_section_organize, listOf(
                Tool(R.string.tool_merge, R.string.tool_merge_sub, R.color.tool_merge, R.drawable.ic_call_merge, R.id.merge_pdf),
                Tool(R.string.tool_split, R.string.tool_split_sub, R.color.tool_split, R.drawable.ic_call_split, R.id.split_pdf),
                Tool(R.string.tool_organize, R.string.tool_organize_sub, R.color.tool_organize, R.drawable.ic_reorder, R.id.organize_pdf),
                Tool(R.string.tool_extract_pages, R.string.tool_extract_pages_sub, R.color.tool_extract_pages, R.drawable.ic_content_cut, R.id.extract_pages)
            )
        ),
        Section(
            R.string.tools_section_convert, listOf(
                Tool(R.string.tool_images, R.string.tool_images_sub, R.color.tool_images_to_pdf, R.drawable.ic_image, R.id.images_to_pdf),
                Tool(R.string.tool_pdf_to_images, R.string.tool_pdf_to_images_sub, R.color.tool_pdf_to_images, R.drawable.ic_collections, R.id.pdf_to_images),
                Tool(R.string.tool_extract, R.string.tool_extract_sub, R.color.tool_extract_text, R.drawable.ic_text_snippet, R.id.extract_text),
                Tool(R.string.tool_create, R.string.tool_create_sub, R.color.tool_create, R.drawable.ic_note_add, R.id.create_pdf)
            )
        ),
        Section(
            R.string.tools_section_edit, listOf(
                Tool(R.string.tool_watermark, R.string.tool_watermark_sub, R.color.tool_watermark, R.drawable.ic_branding_watermark, R.id.watermark_pdf),
                Tool(R.string.tool_page_numbers, R.string.tool_page_numbers_sub, R.color.tool_page_numbers, R.drawable.ic_numbers, R.id.page_numbers),
                Tool(R.string.tool_fill_form, R.string.tool_fill_form_sub, R.color.tool_fill_form, R.drawable.ic_edit_note, R.id.fill_form),
                Tool(R.string.tool_image_tools, R.string.tool_image_tools_sub, R.color.tool_image_tools, R.drawable.ic_photo_size_select_large, R.id.image_tools)
            )
        ),
        Section(
            R.string.tools_section_optimize, listOf(
                Tool(R.string.tool_compress, R.string.tool_compress_sub, R.color.tool_compress, R.drawable.ic_compress, R.id.compress_pdf),
                Tool(R.string.tool_flatten, R.string.tool_flatten_sub, R.color.tool_flatten, R.drawable.ic_layers, R.id.flatten_pdf),
                Tool(R.string.tool_encrypt_pdf, R.string.tool_encrypt_pdf_sub, R.color.tool_encrypt, R.drawable.ic_lock, R.id.encrypt_pdf),
                Tool(R.string.tool_decrypt_pdf, R.string.tool_decrypt_pdf_sub, R.color.tool_decrypt, R.drawable.ic_lock_open, R.id.decrypt_pdf)
            )
        )
    )

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!
    private var query = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applySystemBarPadding(top = true)
        SearchHeader(
            binding.header,
            title = getString(R.string.tools_title),
            hint = getString(R.string.tools_search_hint),
            onQueryChange = { query = it.trim(); render() }
        )
        render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render() {
        val content = binding.content
        content.removeAllViews()
        if (query.isNotEmpty()) {
            val results = (listOf(openPdf) + sections.flatMap { it.tools }).filter {
                getString(it.title).contains(query, ignoreCase = true) || getString(it.subtitle).contains(query, ignoreCase = true)
            }
            if (results.isEmpty()) {
                content.addView(TextView(requireContext()).apply {
                    setText(R.string.tools_no_matches)
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(context.color(R.color.app_text_secondary))
                    setPadding(0, context.dp(24), 0, context.dp(24))
                })
            } else {
                val card = card()
                results.forEachIndexed { i, tool -> card.addView(wideTile(tool, card, topMargin = if (i == 0) 0 else 10)) }
                content.addView(card)
            }
            return
        }

        content.addView(wideTile(openPdf, content, topMargin = 0))
        sections.forEach { section ->
            content.addView(TextView(requireContext()).apply {
                setText(section.label)
                setTextAppearance(R.style.TextAppearance_ClearPDF_SectionLabel)
                setPadding(context.dp(6), context.dp(22), 0, context.dp(10))
            })
            val card = card()
            section.tools.chunked(2).forEachIndexed { rowIdx, pair ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (rowIdx > 0) topMargin = context.dp(12)
                    }
                }
                pair.forEachIndexed { colIdx, tool ->
                    val tile = gridTile(tool, row)
                    if (colIdx > 0) tile.updateLayoutParams<LinearLayout.LayoutParams> { marginStart = requireContext().dp(12) }
                    row.addView(tile)
                }
                // A lone trailing tile keeps half width instead of stretching.
                if (pair.size == 1) row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 0, 1f).apply {
                    marginStart = requireContext().dp(12)
                })
                card.addView(row)
            }
            content.addView(card)
        }
    }

    private fun card(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        val pad = context.dp(12)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun gridTile(tool: Tool, parent: ViewGroup): View {
        val b = ItemToolTileBinding.inflate(layoutInflater, parent, false)
        val ctx = requireContext()
        val accent = ctx.color(tool.accent)
        b.tile.tintBackground(ctx.accentWash(accent, 0.10f, 0.15f))
        b.iconTile.tintBackground(ctx.accentWash(accent, 0.18f, 0.28f))
        b.icon.setImageResource(tool.icon)
        b.icon.tint(accent)
        b.title.setText(tool.title)
        b.subtitle.setText(tool.subtitle)
        b.tile.setOnClickListener { openTool(tool) }
        return b.root
    }

    private fun wideTile(tool: Tool, parent: ViewGroup, topMargin: Int): View {
        val b = ItemToolWideBinding.inflate(layoutInflater, parent, false)
        val ctx = requireContext()
        val accent = ctx.color(tool.accent)
        b.tile.tintBackground(ctx.accentWash(accent, 0.12f, 0.18f))
        b.iconTile.tintBackground(ctx.accentWash(accent, 0.18f, 0.28f))
        b.icon.setImageResource(tool.icon)
        b.icon.tint(accent)
        b.title.setText(tool.title)
        b.subtitle.setText(tool.subtitle)
        b.tile.setOnClickListener { openTool(tool) }
        b.root.updateLayoutParams<ViewGroup.MarginLayoutParams> { this.topMargin = ctx.dp(topMargin) }
        return b.root
    }

    private fun openTool(tool: Tool) {
        val nav = findNavController()
        if (tool.destination == R.id.pdf_viewer) AppNavigator.openPdfViewer(nav) else AppNavigator.open(nav, tool.destination)
    }
}
