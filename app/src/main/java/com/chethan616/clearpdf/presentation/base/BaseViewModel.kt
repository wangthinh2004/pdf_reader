package com.chethan616.clearpdf.presentation.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chethan616.clearpdf.core.result.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Marker for a screen's immutable state. */
interface UiState

/** Marker for a one-shot effect (message, dialog, navigation) that must not replay. */
interface UiEvent

/**
 * Holds a screen's [state] and one-shot [events]. Subclasses change state only through
 * [setState] and run work through [launch], which turns failures into [AppError]s.
 */
abstract class BaseViewModel<S : UiState, E : UiEvent>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _events = Channel<E>(Channel.BUFFERED)
    val events: Flow<E> = _events.receiveAsFlow()

    protected val currentState: S get() = _state.value

    protected fun setState(reduce: S.() -> S) = _state.update(reduce)

    protected fun sendEvent(event: E) {
        _events.trySend(event)
    }

    protected fun launch(
        onError: (AppError) -> Unit = ::onError,
        block: suspend CoroutineScope.() -> Unit
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onError(AppError.from(t))
        }
    }

    /** Fallback for [launch] calls that don't pass their own handler. */
    protected open fun onError(error: AppError) {}
}
