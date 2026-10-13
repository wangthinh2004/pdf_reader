package com.chethan616.clearpdf

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.chethan616.clearpdf.data.repository.AppSettingsManager
import com.chethan616.clearpdf.data.repository.GitHubStarPromptManager
import com.chethan616.clearpdf.data.repository.OnboardingManager
import com.chethan616.clearpdf.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.utils.LocaleHelper
import com.chethan616.clearpdf.ui.utils.StarPromptEventBus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single Activity. Hosts every screen as a Fragment in [R.navigation.nav_graph] and shows the
 * bottom navigation on the three main tabs (Home, Tools, Settings).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var startDestination = R.id.home
    lateinit var navController: NavController
        private set

    override fun attachBaseContext(newBase: Context) {
        val savedLang = OnboardingManager.getSelectedLocale(newBase)
        super.attachBaseContext(LocaleHelper.getLocalizedContext(newBase, savedLang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyNightMode(AppSettingsManager.getThemeMode(this))
        LocaleHelper.applyLocale(this, OnboardingManager.getSelectedLocale(this), recreate = false, updateAppCompat = false)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Android 10-14 can draw a translucent scrim behind the navigation bar; the app paints its
        // own background everywhere, so that scrim would only show as a grey band.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            window.isNavigationBarContrastEnforced = false
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        navController = host.navController

        val incomingUri = incomingDocumentUri()
        val shortcutDest = shortcutDestination()
        // A first launch that arrives through a share or a shortcut skips the tour and goes
        // straight to the document; onboarding still shows on the next ordinary start. The choice
        // is kept across recreation so a restored back stack sits on the same graph.
        startDestination = savedInstanceState?.getInt(KEY_START_DEST)
            ?: if (OnboardingManager.shouldShowOnboarding(this) && incomingUri == null && shortcutDest == null) {
                R.id.onboarding
            } else R.id.home
        // Set on every creation: the NavController restores its saved back stack onto this graph.
        navController.graph = navController.navInflater.inflate(R.navigation.nav_graph).apply {
            setStartDestination(startDestination)
        }
        if (savedInstanceState == null) {
            if (incomingUri != null) {
                AppNavigator.openDocument(navController, this, incomingUri)
            } else if (shortcutDest != null) {
                if (shortcutDest == R.id.pdf_viewer) AppNavigator.openPdfViewer(navController)
                else AppNavigator.open(navController, shortcutDest)
            }
        }

        setupBottomNav()
        refreshWallpaper()
        setupStarPrompt(firstStart = savedInstanceState == null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_START_DEST, startDestination)
    }

    // ── Bottom navigation ─────────────────────────────────────────────────────────────────────

    private val tabDestinations = setOf(R.id.home, R.id.tools, R.id.settings)

    private fun setupBottomNav() {
        val nav = binding.bottomNav
        nav.setOnItemSelectedListener { item ->
            if (navController.currentDestination?.id == item.itemId) return@setOnItemSelectedListener true
            val options = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setEnterAnim(R.anim.fade_in)
                .setExitAnim(R.anim.fade_out)
                .setPopUpTo(R.id.home, inclusive = item.itemId == R.id.home)
                .build()
            runCatching { navController.navigate(item.itemId, null, options) }
            true
        }
        nav.setOnItemReselectedListener { }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isTab = destination.id in tabDestinations
            nav.isVisible = isTab
            if (isTab && nav.selectedItemId != destination.id) {
                nav.menu.findItem(destination.id)?.isChecked = true
            }
        }
    }

    // ── Appearance ────────────────────────────────────────────────────────────────────────────

    /** Theme mode: 0 = follow system, 1 = light, 2 = dark. Persists and re-themes the Activity. */
    fun setThemeMode(mode: Int) {
        AppSettingsManager.setThemeMode(this, mode)
        applyNightMode(mode)
    }

    private fun applyNightMode(mode: Int) {
        val night = when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != night) AppCompatDelegate.setDefaultNightMode(night)
    }

    /** Persists [code] as the app language and restarts the Activity so every string follows. */
    fun changeLocale(code: String) {
        val normalized = LocaleHelper.normalizeForUi(code)
        if (normalized == OnboardingManager.getSelectedLocale(this)) return
        LocaleHelper.applyLocale(this, normalized, recreate = false, updateAppCompat = false)
        recreate()
    }

    /** Shows the built-in or user-picked background picture, or hides it when turned off. */
    fun refreshWallpaper() {
        val view = binding.wallpaper
        if (!AppSettingsManager.getShowWallpaper(this)) {
            view.isVisible = false
            view.setImageDrawable(null)
            return
        }
        view.isVisible = true
        val custom = AppSettingsManager.getCustomWallpaper(this)
        if (custom == null) {
            view.setImageResource(if (isNightMode()) R.drawable.wallpaper_dark else R.drawable.wallpaper_light)
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    contentResolver.openInputStream(Uri.parse(custom))?.use { BitmapFactory.decodeStream(it, null, opts) }
                }.getOrNull()
            }
            if (bitmap != null) view.setImageBitmap(bitmap)
            else view.setImageResource(if (isNightMode()) R.drawable.wallpaper_dark else R.drawable.wallpaper_light)
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // ── Onboarding ────────────────────────────────────────────────────────────────────────────

    /**
     * Leaves the tour. On a first run it is the start destination, so go Home and drop it; on a
     * replay from Settings simply pop back to Settings.
     */
    fun finishOnboarding() {
        OnboardingManager.setOnboardingComplete(this)
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        } else {
            navController.navigate(
                R.id.home, null,
                NavOptions.Builder().setPopUpTo(R.id.onboarding, inclusive = true).setLaunchSingleTop(true)
                    .setEnterAnim(R.anim.fade_in).setExitAnim(R.anim.fade_out).build()
            )
        }
    }

    // ── GitHub star prompt ────────────────────────────────────────────────────────────────────

    private fun setupStarPrompt(firstStart: Boolean) {
        if (firstStart && GitHubStarPromptManager.shouldShowPrompt(this)) showStarPrompt()
        lifecycleScope.launch {
            StarPromptEventBus.promptRequests.collect {
                if (GitHubStarPromptManager.shouldShowPrompt(this@MainActivity)) showStarPrompt()
            }
        }
    }

    private fun showStarPrompt() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_star)
            .setTitle(R.string.star_prompt_title)
            .setMessage(R.string.star_prompt_message)
            .setPositiveButton(R.string.star_prompt_accept) { _, _ ->
                GitHubStarPromptManager.onPromptAccepted(this)
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubStarPromptManager.REPO_URL)))
                }
            }
            .setNegativeButton(R.string.star_prompt_later) { _, _ -> GitHubStarPromptManager.onPromptDismissed(this) }
            .setNeutralButton(R.string.star_prompt_never) { _, _ -> GitHubStarPromptManager.setNeverShowAgain(this) }
            .setOnCancelListener { GitHubStarPromptManager.onPromptDismissed(this) }
            .show()
    }

    // ── Incoming intents ──────────────────────────────────────────────────────────────────────

    /** Deep links from app shortcuts (clearpdf://open, merge, compress, create). */
    private fun shortcutDestination(): Int? = when (intent?.data?.takeIf { it.scheme == "clearpdf" }?.host) {
        "open" -> R.id.pdf_viewer
        "merge" -> R.id.merge_pdf
        "compress" -> R.id.compress_pdf
        "create" -> R.id.create_pdf
        else -> null
    }

    /** A supported document handed to us by another app (VIEW / SEND). */
    private fun incomingDocumentUri(): Uri? {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme != "clearpdf" }
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        return uri?.takeIf { isSupportedDocumentIntent(intent?.type, it.toString()) }
    }

    private fun isSupportedDocumentIntent(mimeType: String?, uriString: String): Boolean {
        val lower = uriString.lowercase()
        val supportedExtension = listOf(
            ".pdf", ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx", ".csv", ".txt", ".rtf",
            ".odt", ".ods", ".odp", ".hwp", ".hwpx", ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif", ".heic"
        ).any(lower::contains)
        return mimeType == null ||
            mimeType == "application/pdf" ||
            mimeType.startsWith("image/") ||
            mimeType.startsWith("text/") ||
            mimeType.contains("word", ignoreCase = true) ||
            mimeType.contains("excel", ignoreCase = true) ||
            mimeType.contains("spreadsheet", ignoreCase = true) ||
            mimeType.contains("presentation", ignoreCase = true) ||
            mimeType.contains("hwp", ignoreCase = true) ||
            supportedExtension
    }

    private companion object {
        const val KEY_START_DEST = "start_destination"
    }
}
