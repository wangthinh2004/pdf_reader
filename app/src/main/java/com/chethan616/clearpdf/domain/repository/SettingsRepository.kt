package com.chethan616.clearpdf.domain.repository

/** User preferences used by tools. */
interface SettingsRepository {
    /** Default Quality setting, 0..1. */
    fun defaultCompressionFraction(): Float
}
