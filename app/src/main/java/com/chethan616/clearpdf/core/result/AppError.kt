package com.chethan616.clearpdf.core.result

import kotlin.coroutines.cancellation.CancellationException

/** Failures the app knows how to describe. Repositories throw these; BaseViewModel maps the rest. */
sealed class AppError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class Storage(cause: Throwable? = null) : AppError(cause?.message, cause)
    class Network(cause: Throwable? = null) : AppError(cause?.message, cause)
    /** The free Gemini quota is used up (HTTP 429). */
    class QuotaExceeded : AppError("Free quota exceeded")
    /** A remote service answered with an error it described. */
    class Remote(message: String) : AppError(message)
    /** A file name that is empty or has characters storage won't accept. */
    class InvalidName : AppError("Invalid file name")
    class Unknown(cause: Throwable) : AppError(cause.message, cause)

    companion object {
        fun from(t: Throwable): AppError = t as? AppError ?: Unknown(t)
    }
}

/** [runCatching] that lets coroutine cancellation propagate instead of swallowing it. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Result.failure(t)
}
