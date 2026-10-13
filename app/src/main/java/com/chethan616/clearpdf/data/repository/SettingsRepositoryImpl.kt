package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Reads the same "clearpdf_settings" preferences as AppSettingsManager. */
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {
    override fun defaultCompressionFraction(): Float = AppSettingsManager.getDefaultQuality(context)
}
