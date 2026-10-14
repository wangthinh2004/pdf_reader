package com.chethan616.clearpdf.core.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** Whether Home may scan the device for documents, and how to ask for it. */
object StorageAccess {

    /**
     * The runtime permission to ask for on Android 9 and below. WRITE (same storage group, so it
     * grants read too) so edited spreadsheets can be saved back in place.
     */
    const val LEGACY_PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE

    /**
     * Android 10 is the one release where neither path works: scoped storage hides other apps'
     * documents from READ_EXTERNAL_STORAGE, All files access doesn't exist yet, and opting out with
     * requestLegacyExternalStorage would change how every tool saves into Downloads there.
     */
    fun canListDocuments(): Boolean = Build.VERSION.SDK_INT != Build.VERSION_CODES.Q

    fun hasDocumentAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** API 30+: access is granted on a system settings page, not a runtime dialog. */
    fun needsSettingsScreen(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The app's own All-files-access page, or the global list when a ROM lacks the per-app one. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun settingsIntent(context: Context): Intent {
        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        return if (perApp.resolveActivity(context.packageManager) != null) perApp
        else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}
