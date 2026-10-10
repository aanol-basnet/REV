package app.rev.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.Result
import app.rev.domain.model.SystemStatus
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SystemUiState(
    val status: SystemStatus? = null,
    val error: LibraryError? = null,
    val loading: Boolean = false,
)

@HiltViewModel
class SystemStatusViewModel @Inject constructor(
    private val system: SystemRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SystemUiState())
    val state: StateFlow<SystemUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            _state.update {
                when (val result = system.status()) {
                    is Result.Ok -> SystemUiState(status = result.value)
                    is Result.Err -> it.copy(error = result.error, loading = false)
                }
            }
        }
    }
}
