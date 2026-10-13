package com.chethan616.clearpdf.domain.usecase.summary

import com.chethan616.clearpdf.domain.model.Summary
import com.chethan616.clearpdf.domain.repository.SummaryRepository
import javax.inject.Inject

/** Summarizes document text, sending at most [MAX_INPUT_CHARS] to stay inside free-tier limits. */
class SummarizeTextUseCase @Inject constructor(private val summaries: SummaryRepository) {
    suspend operator fun invoke(text: String): Summary =
        Summary(text = summaries.summarize(text.take(MAX_INPUT_CHARS)), truncated = text.length > MAX_INPUT_CHARS)

    companion object {
        const val MAX_INPUT_CHARS = 120_000
    }
}
