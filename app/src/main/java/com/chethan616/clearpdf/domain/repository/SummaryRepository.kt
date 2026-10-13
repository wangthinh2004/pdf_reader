package com.chethan616.clearpdf.domain.repository

import com.chethan616.clearpdf.domain.model.SummaryAvailability

/** AI summary via Gemini. Throws AppError.QuotaExceeded / Network / Remote. */
interface SummaryRepository {
    fun availability(): SummaryAvailability

    fun grantConsent()

    suspend fun summarize(text: String): String
}
