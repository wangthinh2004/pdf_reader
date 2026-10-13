package com.chethan616.clearpdf.domain.model

data class Summary(
    val text: String,
    /** True when only the first part of a long document was summarized. */
    val truncated: Boolean
)

enum class SummaryAvailability {
    /** No API key in this build or no network permission: hide the feature. */
    UNAVAILABLE,
    /** Available, but the user hasn't agreed to send text to Gemini yet. */
    NEEDS_CONSENT,
    READY
}
