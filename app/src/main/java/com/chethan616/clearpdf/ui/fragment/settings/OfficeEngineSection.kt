package com.chethan616.clearpdf.ui.fragment.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ViewOfficeEngineSectionBinding
import com.chethan616.clearpdf.office.OfficeEngine
import com.chethan616.clearpdf.office.OfficeEngineManifest
import com.chethan616.clearpdf.office.OfficeEngineState
import com.chethan616.clearpdf.ui.common.DocumentActions
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tintBackground
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The Settings card for the optional Office engine (powered by LibreOffice). Hidden entirely on
 * devices the engine does not support.
 */
class OfficeEngineSection(
    private val fragment: Fragment,
    private val b: ViewOfficeEngineSectionBinding,
    /** Asks for POST_NOTIFICATIONS, then calls [install] whatever the answer. */
    private val requestNotificationPermission: () -> Unit
) {

    private val ctx = fragment.requireContext()
    private val installer = OfficeEngine.installer(ctx)

    fun install() = installer.install()

    init {
        b.iconTile.tintBackground(ctx.accentWash(ctx.color(R.color.accent_blue)))
        b.licences.setOnClickListener { DocumentActions.openExternalLink(ctx, LIBREOFFICE_LICENSES_URL) }
        bindToggle(
            ctx, b.useForOffice, R.drawable.ic_auto_awesome,
            ctx.getString(R.string.office_engine_use_for_office),
            ctx.getString(R.string.office_engine_use_for_office_sub),
            OfficeEngine.useForOfficeFiles(ctx)
        ) { OfficeEngine.setUseForOfficeFiles(ctx, it) }
        installer.refresh()
        fragment.collectWhileStarted(installer.state, ::render)
    }

    private fun render(state: OfficeEngineState) {
        b.root.isVisible = state !is OfficeEngineState.Unsupported
        b.useForOfficeGroup.isVisible = state is OfficeEngineState.Installed
        b.progress.isVisible = state is OfficeEngineState.Downloading || state is OfficeEngineState.Installing
        b.primaryAction.isVisible = false
        b.secondaryAction.isVisible = false
        b.status.setTextColor(ctx.color(R.color.app_text))

        when (state) {
            OfficeEngineState.Unsupported -> Unit
            OfficeEngineState.NotInstalled -> {
                b.status.text = ctx.getString(
                    R.string.office_engine_not_installed,
                    Formatter.formatShortFileSize(ctx, OfficeEngineManifest.APPROX_DOWNLOAD_BYTES)
                )
                primary(R.string.office_engine_download, ::startInstall)
            }
            is OfficeEngineState.Downloading -> {
                b.status.text = when {
                    state.waitingForNetwork -> ctx.getString(R.string.office_engine_waiting_network)
                    state.total > 0 && state.downloaded > 0 -> ctx.getString(
                        R.string.office_engine_downloading_progress,
                        Formatter.formatShortFileSize(ctx, state.downloaded),
                        Formatter.formatShortFileSize(ctx, state.total)
                    )
                    else -> ctx.getString(R.string.office_engine_downloading)
                }
                if (state.total > 0 && state.downloaded > 0) {
                    b.progress.isIndeterminate = false
                    b.progress.setProgressCompat((state.downloaded * 100 / state.total).toInt(), true)
                } else b.progress.isIndeterminate = true
                secondary(R.string.office_engine_cancel) { installer.cancel() }
            }
            OfficeEngineState.Installing -> {
                b.status.setText(R.string.office_engine_installing)
                b.progress.isIndeterminate = true
                secondary(R.string.office_engine_cancel) { installer.cancel() }
            }
            OfficeEngineState.NeedsConfirmation -> {
                b.status.setText(R.string.office_engine_needs_confirmation)
                primary(R.string.office_engine_approve) { installer.confirm(fragment.requireActivity()) }
            }
            is OfficeEngineState.Installed -> {
                val size = Formatter.formatShortFileSize(ctx, state.sizeBytes)
                b.status.text = if (state.updateAvailable) ctx.getString(R.string.office_engine_update_available, size)
                else ctx.getString(R.string.office_engine_installed, size, state.version)
                b.status.setTextColor(ctx.color(if (state.updateAvailable) R.color.app_warning else R.color.app_success))
                if (state.updateAvailable) primary(R.string.office_engine_update, ::startInstall)
                secondary(R.string.office_engine_delete) { confirmDelete(state.sizeBytes) }
                b.secondaryAction.setTextColor(ctx.color(R.color.app_danger))
            }
            is OfficeEngineState.Failed -> {
                b.status.text = ctx.getString(R.string.office_engine_failed, state.message)
                b.status.setTextColor(ctx.color(R.color.app_danger))
                primary(R.string.office_engine_retry, ::startInstall)
            }
        }
    }

    private fun primary(label: Int, onClick: () -> Unit) {
        b.primaryAction.isVisible = true
        b.primaryAction.setText(label)
        b.primaryAction.setOnClickListener { onClick() }
    }

    private fun secondary(label: Int, onClick: () -> Unit) {
        b.secondaryAction.isVisible = true
        b.secondaryAction.setText(label)
        b.secondaryAction.setTextColor(ctx.color(R.color.app_text))
        b.secondaryAction.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onClick()
        }
    }

    private fun startInstall() {
        b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val needsPermission = installer.usesNotification && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) requestNotificationPermission() else installer.install()
    }

    private fun confirmDelete(sizeBytes: Long) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.office_engine_delete_title)
            .setMessage(ctx.getString(R.string.office_engine_delete_message, Formatter.formatShortFileSize(ctx, sizeBytes)))
            .setNegativeButton(R.string.office_engine_cancel, null)
            .setPositiveButton(R.string.office_engine_delete) { _, _ -> installer.uninstall() }
            .show()
    }

    private companion object {
        const val LIBREOFFICE_LICENSES_URL = "https://www.libreoffice.org/about-us/licenses/"
    }
}
