package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.domain.repository.EngagementRepository
import com.chethan616.clearpdf.ui.utils.StarPromptEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class EngagementRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : EngagementRepository {
    // MainActivity listens on StarPromptEventBus; it moves behind an interface with MainActivity in phase 4.
    override suspend fun recordPdfInteraction() {
        val showPrompt = withContext(io) { GitHubStarPromptManager.recordPdfInteraction(context) }
        if (showPrompt) StarPromptEventBus.requestPrompt()
    }
}
