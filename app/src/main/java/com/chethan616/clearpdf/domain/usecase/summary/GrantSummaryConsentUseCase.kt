package com.chethan616.clearpdf.domain.usecase.summary

import com.chethan616.clearpdf.domain.repository.SummaryRepository
import javax.inject.Inject

/** The user agreed to send document text to Gemini; remembered across launches. */
class GrantSummaryConsentUseCase @Inject constructor(private val summaries: SummaryRepository) {
    operator fun invoke() = summaries.grantConsent()
}
