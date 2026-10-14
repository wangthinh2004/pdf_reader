package com.chethan616.clearpdf.ui.fragment.onboarding

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.chethan616.clearpdf.MainActivity
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.AppSettingsManager
import com.chethan616.clearpdf.data.repository.OnboardingManager
import com.chethan616.clearpdf.databinding.FragmentOnboardingBinding
import com.chethan616.clearpdf.databinding.ItemFeatureRowBinding
import com.chethan616.clearpdf.databinding.ItemOnboardingPageBinding
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import com.chethan616.clearpdf.ui.utils.LocaleHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayoutMediator

/**
 * The first-run tour: welcome, language, appearance, file types, tools, editors, ready. Language
 * and appearance are live settings, the rest are illustrated pages.
 */
class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applySystemBarPadding(top = true, bottom = true)
        binding.pager.adapter = PagesAdapter()
        TabLayoutMediator(binding.dots, binding.pager) { tab, _ -> tab.view.isClickable = false }.attach()
        ToolUi.primaryButton(binding.next, requireContext().color(R.color.accent_blue))
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderControls(position)
        })
        binding.next.setOnClickListener {
            val page = binding.pager.currentItem
            if (page < PAGE_COUNT - 1) binding.pager.currentItem = page + 1 else finish()
        }
        binding.skip.setOnClickListener { finish() }
        // Back steps through the pages before it leaves the tour.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val page = binding.pager.currentItem
                if (page > 0) binding.pager.currentItem = page - 1 else finish()
            }
        })
        renderControls(binding.pager.currentItem)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun finish() = (requireActivity() as MainActivity).finishOnboarding()

    private fun renderControls(page: Int) {
        val last = page == PAGE_COUNT - 1
        binding.step.text = getString(R.string.onboarding_step, page + 1, PAGE_COUNT)
        binding.next.setText(if (last) R.string.onboarding_cta_get_started else R.string.onboarding_cta_continue)
        binding.skip.isVisible = !last
    }

    // ── Pages ─────────────────────────────────────────────────────────────────────────────────

    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.Holder>() {
        override fun getItemCount() = PAGE_COUNT
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            buildPage(b, viewType)
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = Unit

        inner class Holder(b: ItemOnboardingPageBinding) : RecyclerView.ViewHolder(b.root)
    }

    private fun buildPage(b: ItemOnboardingPageBinding, page: Int) {
        val (title, subtitle) = when (page) {
            0 -> R.string.onboarding_welcome_title to R.string.onboarding_welcome_subtitle
            1 -> R.string.onboarding_language_title to R.string.onboarding_language_subtitle
            2 -> R.string.onboarding_appearance_title to R.string.onboarding_appearance_subtitle
            3 -> R.string.onboarding_files_title to R.string.onboarding_files_subtitle
            4 -> R.string.onboarding_features_title to R.string.onboarding_features_subtitle
            5 -> R.string.onboarding_editors_title to R.string.onboarding_editors_subtitle
            else -> R.string.onboarding_ready_title to R.string.onboarding_ready_subtitle
        }
        b.title.setText(title)
        b.subtitle.setText(subtitle)
        b.badge.isVisible = page == PAGE_COUNT - 1
        val visual: View = when (page) {
            0 -> illustration(R.mipmap.ic_launcher_round, 128)
            1 -> languageChooser()
            2 -> appearanceChooser()
            3 -> fileKinds()
            4 -> featureList(
                listOf(
                    Feature(R.drawable.ic_draw, R.color.accent_orange, R.string.feature_annotate_title, R.string.feature_annotate_desc),
                    Feature(R.drawable.ic_search, R.color.accent_blue, R.string.feature_search_title, R.string.feature_search_desc),
                    Feature(R.drawable.ic_grid_view, R.color.accent_purple, R.string.feature_tools_title, R.string.feature_tools_desc)
                )
            )
            5 -> featureList(
                listOf(
                    Feature(R.drawable.ic_table_chart, R.color.tool_compress, R.string.onboarding_editor_sheets_title, R.string.onboarding_editor_sheets_desc),
                    Feature(R.drawable.ic_auto_fix_high, R.color.accent_purple, R.string.onboarding_editor_image_title, R.string.onboarding_editor_image_desc),
                    Feature(R.drawable.ic_text_fields, R.color.accent_blue, R.string.onboarding_editor_select_title, R.string.onboarding_editor_select_desc),
                    Feature(R.drawable.ic_description, R.color.accent_orange, R.string.onboarding_editor_office_title, R.string.onboarding_editor_office_desc)
                )
            )
            else -> illustration(R.drawable.ic_onboarding_ready, 200)
        }
        b.visual.addView(visual)
    }

    private fun illustration(@DrawableRes res: Int, sizeDp: Int): View = ImageView(requireContext()).apply {
        setImageResource(res)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = android.widget.FrameLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp), Gravity.CENTER)
    }

    /** The document kinds ClearPDF opens, as a row of format icons. */
    private fun fileKinds(): View {
        val ctx = requireContext()
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        listOf(
            listOf(R.drawable.ic_format_pdf, R.drawable.ic_format_word, R.drawable.ic_format_excel),
            listOf(R.drawable.ic_format_ppt, R.drawable.ic_format_txt, R.drawable.ic_format_image)
        ).forEach { rowIcons ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            rowIcons.forEach { icon ->
                row.addView(ImageView(ctx).apply {
                    setImageResource(icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setBackgroundResource(R.drawable.bg_card)
                    val pad = ctx.dp(14)
                    setPadding(pad, pad, pad, pad)
                }, LinearLayout.LayoutParams(ctx.dp(84), ctx.dp(84)).apply { setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6)) })
            }
            grid.addView(row)
        }
        return grid
    }

    private fun languageChooser(): View {
        val ctx = requireContext()
        val activity = requireActivity() as MainActivity
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(illustration(R.drawable.ic_onboarding_language, 140).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(140), ctx.dp(140))
        })
        val group = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
            chipSpacingHorizontal = ctx.dp(8)
        }
        val current = LocaleHelper.normalizeForUi(OnboardingManager.getSelectedLocale(ctx))
        listOf(
            "en" to R.string.language_english,
            "pt-BR" to R.string.language_portuguese,
            "es" to R.string.language_spanish,
            "it" to R.string.language_italian,
            "ru" to R.string.language_russian
        ).forEach { (code, label) ->
            group.addView((layoutInflater.inflate(R.layout.view_filter_chip, group, false) as Chip).apply {
                id = View.generateViewId()
                setText(label)
                isChecked = code == current
                // Restarts the Activity in the new language; the tour comes back on this page.
                setOnClickListener { activity.changeLocale(code) }
            })
        }
        column.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(20)
        })
        return column
    }

    private fun appearanceChooser(): View {
        val ctx = requireContext()
        val activity = requireActivity() as MainActivity
        val accent = ctx.color(R.color.accent_blue)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val pad = ctx.dp(18)
            setPadding(pad, pad, pad, pad)
        }
        val group = MaterialButtonToggleGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val ids = mutableMapOf<Int, Int>()
        listOf(
            Triple(0, R.string.settings_theme_auto, R.drawable.ic_phone_android),
            Triple(1, R.string.settings_theme_light, R.drawable.ic_light_mode),
            Triple(2, R.string.settings_theme_dark, R.drawable.ic_dark_mode)
        ).forEach { (mode, label, icon) ->
            val button = layoutInflater.inflate(R.layout.view_segment_button, group, false) as MaterialButton
            button.id = View.generateViewId()
            button.setText(label)
            button.setIconResource(icon)
            ToolUi.segment(button, accent)
            group.addView(button)
            ids[button.id] = mode
        }
        val currentMode = AppSettingsManager.getThemeMode(ctx)
        ids.entries.firstOrNull { it.value == currentMode }?.key?.let(group::check)
        group.addOnButtonCheckedListener { _, id, checked -> if (checked) ids[id]?.let(activity::setThemeMode) }
        card.addView(group)

        return card
    }

    private data class Feature(@DrawableRes val icon: Int, @ColorRes val color: Int, @StringRes val title: Int, @StringRes val desc: Int)

    private fun featureList(features: List<Feature>): View {
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        features.forEach { f ->
            val row = ItemFeatureRowBinding.inflate(layoutInflater, column, true)
            val color = ctx.color(f.color)
            row.icon.setImageResource(f.icon)
            row.icon.tint(color)
            row.iconTile.tintBackground(ctx.accentWash(color))
            row.title.setText(f.title)
            row.desc.setText(f.desc)
        }
        return column
    }

    private companion object {
        const val PAGE_COUNT = 7
    }
}
