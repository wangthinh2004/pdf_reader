package com.chethan616.clearpdf.domain.repository

/** Counts completed PDF actions for the GitHub star prompt. */
interface EngagementRepository {
    suspend fun recordPdfInteraction()
}
