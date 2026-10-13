package com.chethan616.clearpdf.presentation.common

import android.content.Context
import androidx.annotation.StringRes

/** Text a ViewModel hands to the UI without needing a Context. */
sealed interface UiText {
    data class Raw(val value: String) : UiText
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    fun resolve(context: Context): String = when (this) {
        is Raw -> value
        is Res -> context.getString(id, *args.toTypedArray())
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}
