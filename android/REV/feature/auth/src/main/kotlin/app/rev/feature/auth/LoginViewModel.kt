package app.rev.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.AuthState
import app.rev.domain.model.Result
import app.rev.domain.repository.AuthRepository
import app.rev.domain.repository.LoginError
import app.rev.domain.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val serverUrl: String? = null,
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: LoginError? = null,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !isSubmitting
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val servers: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            auth.authState.collect { authState ->
                if (authState is AuthState.SignedOut) {
                    _state.update { it.copy(serverUrl = authState.server.url.value) }
                }
            }
        }
    }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    /** On success the auth state becomes SignedIn and the app navigates away. */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val result = auth.login(current.username, current.password)) {
                is Result.Ok -> _state.update { it.copy(isSubmitting = false, password = "") }
                is Result.Err -> _state.update { it.copy(isSubmitting = false, error = result.error) }
            }
        }
    }

    fun changeServer() {
        viewModelScope.launch { servers.clearServer() }
    }
}
