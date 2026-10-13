package com.chethan616.clearpdf.domain.usecase.summary

import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.repository.SummaryRepository
import javax.inject.Inject

class GetSummaryAvailabilityUseCase @Inject constructor(private val summaries: SummaryRepository) {
    operator fun invoke(): SummaryAvailability = summaries.availability()
}
