package com.chethan616.clearpdf.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chethan616.clearpdf.data.repository.DeviceDocument
import com.chethan616.clearpdf.data.repository.DeviceDocumentScanner
import com.chethan616.clearpdf.data.repository.StorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DeviceDocsState {
    /** This Android release can't list other apps' documents (see StorageAccess.canListDocuments). */
    data object Unsupported : DeviceDocsState
    data object NoAccess : DeviceDocsState
    data object Loading : DeviceDocsState
    data class Ready(val docs: List<DeviceDocument>) : DeviceDocsState
    data object Error : DeviceDocsState
}

/** Activity-scoped so the list survives switching between the main tabs. */
class DeviceDocumentsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<DeviceDocsState>(DeviceDocsState.Loading)
    val state: StateFlow<DeviceDocsState> = _state.asStateFlow()
    private var scanJob: Job? = null

    /** Re-checks access and rescans. A list already on screen stays up while the rescan runs. */
    fun refresh() {
        val app = getApplication<Application>()
        if (!StorageAccess.canListDocuments()) {
            _state.value = DeviceDocsState.Unsupported
            return
        }
        if (!StorageAccess.hasDocumentAccess(app)) {
            scanJob?.cancel()
            _state.value = DeviceDocsState.NoAccess
            return
        }
        if (scanJob?.isActive == true) return
        val previous = _state.value
        if (previous !is DeviceDocsState.Ready) _state.value = DeviceDocsState.Loading
        scanJob = viewModelScope.launch {
            _state.value = try {
                DeviceDocsState.Ready(DeviceDocumentScanner.scan(app))
            } catch (e: CancellationException) {
                // Cancelled by a refresh that found access revoked; it already set the state.
                throw e
            } catch (e: Exception) {
                previous as? DeviceDocsState.Ready ?: DeviceDocsState.Error
            }
        }
    }
}
