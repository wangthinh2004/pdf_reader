package com.chethan616.clearpdf.ui.components

import androidx.compose.ui.graphics.Color

/**
 * What a glass control draws while its real glass is switched off (during a scroll, see
 * LocalIsScrolling): a light frosted fill plus a hairline rim, so an untinted icon or label never
 * floats bare over the content. Flat colours only — no backdrop capture, no blur pass.
 */
internal object FlatGlass {
    fun fill(isDark: Boolean): Color =
        if (isDark) Color(0xFF2C2C30).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.65f)

    fun rim(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)
}
