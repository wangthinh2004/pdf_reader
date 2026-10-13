package com.chethan616.clearpdf.presentation.base

import app.cash.turbine.test
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class BaseViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private data class CounterState(val count: Int = 0) : UiState
    private data class Toast(val text: String) : UiEvent

    private class CounterViewModel : BaseViewModel<CounterState, Toast>(CounterState()) {
        var lastError: AppError? = null
        val gate = CompletableDeferred<Unit>()
        fun increment() = setState { copy(count = count + 1) }
        fun toast(text: String) = sendEvent(Toast(text))
        fun fail(t: Throwable) = launch { throw t }
        fun waitThenIncrement() = launch { gate.await(); increment() }
        override fun onError(error: AppError) { lastError = error }
    }

    @Test fun setState_reducesCurrentState() {
        val vm = CounterViewModel()
        vm.increment(); vm.increment()
        assertEquals(2, vm.state.value.count)
    }

    @Test fun events_areDeliveredOnce() = runTest {
        val vm = CounterViewModel()
        vm.toast("saved")
        vm.events.test {
            assertEquals(Toast("saved"), awaitItem())
            expectNoEvents()
        }
    }

    @Test fun launch_mapsThrowablesToAppError() {
        val vm = CounterViewModel()
        vm.fail(IOException("disk"))
        assertTrue(vm.lastError is AppError.Unknown)
        assertEquals("disk", vm.lastError?.message)
    }

    @Test fun launch_keepsTypedAppErrors() {
        val vm = CounterViewModel()
        vm.fail(AppError.QuotaExceeded())
        assertTrue(vm.lastError is AppError.QuotaExceeded)
    }

    @Test fun launch_runsSuspendingWork() {
        val vm = CounterViewModel()
        vm.waitThenIncrement()
        assertEquals(0, vm.state.value.count)
        vm.gate.complete(Unit)
        assertEquals(1, vm.state.value.count)
    }
}
