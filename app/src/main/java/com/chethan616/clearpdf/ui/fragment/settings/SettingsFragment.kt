package com.chethan616.clearpdf.ui.fragment.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.chethan616.clearpdf.MainActivity
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.AppSettingsManager
import com.chethan616.clearpdf.data.repository.GitHubStarPromptManager
import com.chethan616.clearpdf.data.repository.OnboardingManager
import com.chethan616.clearpdf.data.repository.SaveLocationManager
import com.chethan616.clearpdf.databinding.FragmentSettingsBinding
import com.chethan616.clearpdf.databinding.ViewSectionHeaderBinding
import com.chethan616.clearpdf.databinding.ViewSettingToggleBinding
import com.chethan616.clearpdf.office.OfficeEngine
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.DocumentActions
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.utils.LocaleHelper
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings tab: appearance, language, save location, file handling, Office engine, about. */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var officeSection: OfficeEngineSection? = null
    private var qualitySaveJob: Job? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val ctx = requireContext()
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            val displayPath = uri.lastPathSegment?.replace("primary:", "") ?: uri.toString()
            SaveLocationManager.setSaveLocation(ctx, uri, displayPath)
            renderSaveLocation()
        }
    }

    // The engine's progress notification needs POST_NOTIFICATIONS on 13+; the download runs either way.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        officeSection?.install()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applySystemBarPadding(top = true)
        binding.back.setOnClickListener { findNavController().navigateUp() }
        val ctx = requireContext()
        val activity = requireActivity() as MainActivity

        // ── Appearance ──
        header(binding.appearanceHeader, R.drawable.ic_tune, R.string.settings_appearance, R.color.app_text)
        val themeMode = AppSettingsManager.getThemeMode(ctx)
        binding.themeGroup.check(
            when (themeMode) {
                1 -> R.id.theme_light
                2 -> R.id.theme_dark
                else -> R.id.theme_auto
            }
        )
        binding.themeDesc.setText(themeDescription(themeMode))
        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.theme_light -> 1
                R.id.theme_dark -> 2
                else -> 0
            }
            binding.themeDesc.setText(themeDescription(mode))
            activity.setThemeMode(mode)
        }

        // ── Language ──
        header(binding.languageHeader, R.drawable.ic_language, R.string.settings_language, R.color.app_text)
        val selected = OnboardingManager.getSelectedLocale(ctx)
        listOf(
            "en" to R.string.language_english,
            "pt-BR" to R.string.language_portuguese,
            "es" to R.string.language_spanish,
            "it" to R.string.language_italian,
            "ru" to R.string.language_russian
        ).forEach { (code, label) ->
            val chip = (layoutInflater.inflate(R.layout.view_filter_chip, binding.languageGroup, false) as Chip).apply {
                id = View.generateViewId()
                setText(label)
                isChecked = LocaleHelper.normalizeForUi(selected) == code
                setOnClickListener { activity.changeLocale(code) }
            }
            binding.languageGroup.addView(chip)
        }

        // ── Save location ──
        header(binding.saveHeader, R.drawable.ic_folder_open, R.string.settings_save_location, R.color.accent_blue)
        binding.saveIconTile.tintBackground(ctx.accentWash(ctx.color(R.color.accent_blue)))
        binding.changeFolder.setOnClickListener { folderPicker.launch(null) }
        binding.resetFolder.setOnClickListener {
            SaveLocationManager.clearSaveLocation(ctx)
            renderSaveLocation()
        }
        renderSaveLocation()

        // ── File handling ──
        header(binding.filesHeader, R.drawable.ic_description, R.string.settings_file_handling, R.color.app_text)
        toggle(binding.autoCompress, R.drawable.ic_compress, R.string.settings_auto_compress, R.string.settings_auto_compress_desc,
            AppSettingsManager.getAutoCompress(ctx)) { AppSettingsManager.setAutoCompress(ctx, it) }
        toggle(binding.keepOriginal, R.drawable.ic_file_copy, R.string.settings_keep_original, R.string.settings_keep_original_desc,
            AppSettingsManager.getKeepOriginal(ctx)) { AppSettingsManager.setKeepOriginal(ctx, it) }
        toggle(binding.rememberRecents, R.drawable.ic_description, R.string.settings_remember_recent_files,
            R.string.settings_remember_recent_files_desc, AppSettingsManager.getRememberRecentFiles(ctx)) {
            AppSettingsManager.setRememberRecentFiles(ctx, it)
        }
        toggle(binding.keepLocalCopies, R.drawable.ic_folder_open, R.string.settings_keep_local_copies,
            R.string.settings_keep_local_copies_desc, AppSettingsManager.getKeepLocalCopies(ctx)) { value ->
            lifecycleScope.launch(Dispatchers.IO) { AppSettingsManager.setKeepLocalCopies(ctx.applicationContext, value) }
        }

        // ── Default compression quality ──
        header(binding.qualityHeader, R.drawable.ic_high_quality, R.string.settings_compression_quality, R.color.accent_blue)
        binding.qualityValue.tintBackground(ctx.accentWash(ctx.color(R.color.accent_blue)))
        val quality = (AppSettingsManager.getDefaultQuality(ctx) * 100f).toInt() / 100f
        binding.qualitySlider.value = quality.coerceIn(0f, 1f)
        binding.qualityValue.text = "${(quality * 100).toInt()}%"
        binding.qualitySlider.addOnChangeListener { _, value, _ ->
            binding.qualityValue.text = "${(value * 100).toInt()}%"
            qualitySaveJob?.cancel()
            qualitySaveJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(300L)
                AppSettingsManager.setDefaultQuality(ctx, value)
            }
        }

        // ── Personalization ──
        header(binding.personalHeader, R.drawable.ic_auto_awesome, R.string.settings_personalization, R.color.app_text)
        binding.replayOnboarding.setOnClickListener {
            // Clear the flag too, so quitting the replay early doesn't leave the tour marked "seen".
            OnboardingManager.resetOnboarding(ctx)
            AppNavigator.open(findNavController(), R.id.onboarding)
        }

        // ── About ──
        binding.aboutIconTile.tintBackground(ctx.accentWash(ctx.color(R.color.accent_blue)))
        binding.starGithub.setOnClickListener { DocumentActions.openExternalLink(ctx, GitHubStarPromptManager.REPO_URL) }

        // ── Office engine ──
        officeSection = OfficeEngineSection(this, binding.officeEngine) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (OfficeEngine.focusSettingsSection.value) {
            OfficeEngine.focusSettingsSection.value = false
            binding.scroll.postDelayed({
                _binding?.let { it.scroll.smoothScrollTo(0, it.officeEngine.root.top) }
            }, 350L)
        }

        // ── Licences ──
        header(binding.licensesHeader, R.drawable.ic_code, R.string.settings_licenses, R.color.app_text)
        OpenSourceCredits.forEachIndexed { index, credit ->
            if (index > 0) binding.licenses.addView(View(ctx).apply {
                setBackgroundColor(ctx.color(R.color.app_divider))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)).apply {
                    topMargin = ctx.dp(8); bottomMargin = ctx.dp(8)
                }
            })
            binding.licenses.addView(licenseItem(credit))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        officeSection = null
        _binding = null
    }

    private fun themeDescription(mode: Int): Int = when (mode) {
        1 -> R.string.settings_theme_light_desc
        2 -> R.string.settings_theme_dark_desc
        else -> R.string.settings_theme_auto_desc
    }

    private fun renderSaveLocation() {
        val ctx = requireContext()
        val saveUri = SaveLocationManager.getSaveUri(ctx)
        binding.saveTitle.setText(if (saveUri != null) R.string.settings_custom_directory else R.string.settings_default_directory)
        binding.savePath.text = saveUri?.let { it.lastPathSegment?.replace("primary:", "") ?: it.toString() }
            ?: getString(R.string.settings_default_path)
        binding.resetFolder.isVisible = saveUri != null
    }

    private fun header(b: ViewSectionHeaderBinding, @DrawableRes icon: Int, @StringRes title: Int, tintRes: Int) {
        b.icon.setImageResource(icon)
        b.icon.tint(requireContext().color(tintRes))
        b.title.setText(title)
    }

    private fun toggle(
        b: ViewSettingToggleBinding,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes desc: Int,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) = bindToggle(requireContext(), b, icon, getString(title), getString(desc), checked, onChange)

    private fun licenseItem(credit: Credit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = "${credit.name}  ·  ${getString(R.string.settings_license_author, credit.author)}"
                textSize = 14f
                setTextColor(ctx.color(R.color.app_text))
            })
            addView(TextView(ctx).apply {
                text = credit.license
                textSize = 11f
                setTextColor(ctx.color(R.color.app_text_secondary))
            })
            addView(TextView(ctx).apply {
                text = credit.url
                textSize = 11f
                setTextColor(ctx.color(R.color.accent_blue))
                setOnClickListener { DocumentActions.openExternalLink(ctx, credit.url) }
            })
        }
    }

    private class Credit(val name: String, val author: String, val license: String, val url: String)

    /** Ordered roughly by how much of the app each one carries. */
    private val OpenSourceCredits = listOf(
        Credit("PdfBox-Android", "Tom Roush", "Apache License 2.0 — PDF text, forms and annotation export", "https://github.com/TomRoush/PdfBox-Android"),
        Credit("Apache POI", "The Apache Software Foundation", "Apache License 2.0 — legacy .doc / .xls / .ppt reading", "https://poi.apache.org"),
        Credit("hwplib / hwpxlib", "neolord0", "Apache License 2.0 — HWP / HWPX text extraction", "https://github.com/neolord0/hwplib"),
        Credit("ML Kit Text Recognition", "Google", "Apache License 2.0 — bundled on-device OCR, no network", "https://developers.google.com/ml-kit/vision/text-recognition"),
        Credit("Tesseract4Android", "Adaptech s.r.o.", "Apache License 2.0 — offline OCR fallback", "https://github.com/adaptech-cz/Tesseract4Android"),
        Credit("Tesseract OCR", "Google / Tesseract contributors", "Apache License 2.0", "https://github.com/tesseract-ocr/tesseract"),
        Credit("Leptonica", "Dan Bloomberg", "BSD 2-Clause — image processing behind Tesseract", "https://github.com/DanBloomberg/leptonica"),
        Credit("docx-preview", "Volodymyr Baydalka", "Apache License 2.0 — Word document layout", "https://github.com/VolodymyrBaydalka/docxjs"),
        Credit("JSZip", "Stuart Knightley", "MIT License (used under MIT of its MIT/GPLv3 dual licence)", "https://github.com/Stuk/jszip"),
        Credit("ImageToolbox", "T8RIN (Malik Mukhametzyanov)", "Apache License 2.0 — image editor perspective crop and draw engine (adapted)", "https://github.com/T8RIN/ImageToolbox"),
        Credit("GPUImage for Android", "CyberAgent, Inc.", "Apache License 2.0 — image editor adjustments and filters", "https://github.com/cats-oss/android-gpuimage"),
        Credit("ML Kit Subject Segmentation", "Google", "Google APIs Terms — optional background removal via Play services", "https://developers.google.com/ml-kit/vision/subject-segmentation"),
        Credit("Material Components for Android", "Google", "Apache License 2.0 — app interface", "https://github.com/material-components/material-components-android"),
        Credit("LibreOffice", "The Document Foundation", "Mozilla Public License 2.0 — optional Office engine (downloaded on request, not bundled)", "https://www.libreoffice.org/about-us/licenses/"),
        Credit("Pdf_Tools", "Karna14314", "PDF viewer zoom/pan reference", "https://github.com/Karna14314/Pdf_Tools")
    )
}

/** Fills a `view_setting_toggle` row. Tapping anywhere on the row flips the switch. */
fun bindToggle(
    ctx: android.content.Context,
    b: ViewSettingToggleBinding,
    @DrawableRes icon: Int,
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    b.icon.setImageResource(icon)
    b.iconTile.tintBackground(ctx.accentWash(ctx.color(R.color.accent_blue)))
    b.title.text = title
    b.desc.text = desc
    b.desc.isVisible = desc.isNotEmpty()
    b.toggle.setOnCheckedChangeListener(null)
    b.toggle.isChecked = checked
    b.toggle.setOnCheckedChangeListener { _, value -> onChange(value) }
    b.row.setOnClickListener { b.toggle.toggle() }
}
