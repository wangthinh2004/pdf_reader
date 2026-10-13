package com.chethan616.clearpdf.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.chethan616.clearpdf.data.source.remote.GeminiApi
import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.repository.SummaryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class SummaryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GeminiApi
) : SummaryRepository {

    // Same prefs file and key the first AI summary build used, so earlier consent is kept.
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun availability(): SummaryAvailability = when {
        !api.isConfigured -> SummaryAvailability.UNAVAILABLE
        context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED ->
            SummaryAvailability.UNAVAILABLE
        prefs.getBoolean(KEY_CONSENT, false) -> SummaryAvailability.READY
        else -> SummaryAvailability.NEEDS_CONSENT
    }

    override fun grantConsent() {
        prefs.edit().putBoolean(KEY_CONSENT, true).apply()
    }

    override suspend fun summarize(text: String): String = api.generate(SYSTEM_PROMPT, text)

    private companion object {
        const val PREFS_NAME = "clearpdf_ai"
        const val KEY_CONSENT = "summary_consent"

        val SYSTEM_PROMPT = """
            You summarize documents for a PDF reader app.
            Write the summary in the same language as the document.
            Start with one or two sentences giving the overall gist, then list the key points as short
            bullet lines starting with "• ". Keep names, numbers, dates and amounts exactly as written.
            Use plain text only, no Markdown headings or bold. Never invent facts that are not in the text.
        """.trimIndent()
    }
}
