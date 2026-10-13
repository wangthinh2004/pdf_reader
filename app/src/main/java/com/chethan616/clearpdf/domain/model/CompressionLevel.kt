package com.chethan616.clearpdf.domain.model

enum class CompressionLevel(
    /** Slider position this level snaps to when chosen directly. */
    val fraction: Float
) {
    LOW(0.15f),
    MEDIUM(0.5f),
    HIGH(0.85f);

    companion object {
        /** Maps a 0..1 quality slider (or the Default Quality setting) to a level. */
        fun fromFraction(fraction: Float): CompressionLevel = when {
            fraction < 0.33f -> LOW
            fraction < 0.66f -> MEDIUM
            else -> HIGH
        }
    }
}
