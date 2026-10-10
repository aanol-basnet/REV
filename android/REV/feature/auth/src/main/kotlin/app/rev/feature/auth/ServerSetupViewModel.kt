package app.rev.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.ProbeError
import app.rev.domain.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ServerSetupError { Empty, Invalid, UnsupportedScheme, Unreachable, NotRev, LocalInvalid, LocalNotLocal, LocalDifferentServer }

data class ServerSetupUiState(
    val url: String = "",
    /** Optional home-network address of the same server, used automatically when it answers. */
    val localUrl: String = "",
    val isChecking: Boolean = false,
    val error: ServerSetupError? = null,
    /** Plain HTTP to a host outside Tailscale/LAN. A warning, not a blocker. */
    val isInsecure: Boolean = false,
)

@HiltViewModel
class ServerSetupViewModel @Inject constructor(
    private val servers: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerSetupUiState())
    val state: StateFlow<ServerSetupUiState> = _state.asStateFlow()

    fun onUrlChange(url: String) {
        val parsed = ServerUrl.parse(url)
        _state.update {
            it.copy(url = url, error = null, isInsecure = parsed is Result.Ok && parsed.value.isInsecure)
        }
    }

    fun onLocalUrlChange(url: String) {
        _state.update { it.copy(localUrl = url, error = null) }
    }

    /** On success the configured server changes, which moves the app on to sign-in. */
    fun connect() {
        if (_state.value.isChecking) return
        val url = when (val parsed = ServerUrl.parse(_state.value.url)) {
            is Result.Ok -> parsed.value
            is Result.Err -> {
                _state.update { it.copy(error = parsed.error.toUiError()) }
                return
            }
        }
        val localInput = _state.value.localUrl
        val localUrl = if (localInput.isBlank()) {
            null
        } else {
            when (val parsed = ServerUrl.parse(localInput)) {
                is Result.Ok -> parsed.value
                is Result.Err -> {
                    _state.update { it.copy(error = ServerSetupError.LocalInvalid) }
                    return
                }
            }
        }
        // The home address is plain HTTP on a trusted network; never to a public host.
        if (localUrl != null && localUrl.isInsecure) {
            _state.update { it.copy(error = ServerSetupError.LocalNotLocal) }
            return
        }
        _state.update { it.copy(isChecking = true, error = null) }
        viewModelScope.launch {
            when (val probe = servers.probe(url)) {
                is Result.Ok -> {
                    // Unreachable is fine (maybe not at home now); another installation is not.
                    val local = localUrl?.let { servers.probe(it) }
                    if (local is Result.Ok && local.value.instanceId != probe.value.instanceId) {
                        _state.update { it.copy(isChecking = false, error = ServerSetupError.LocalDifferentServer) }
                        return@launch
                    }
                    servers.setServer(url, probe.value)
                    if (localUrl != null) servers.setLocalUrl(localUrl)
                    _state.update { it.copy(isChecking = false) }
                }
                is Result.Err -> _state.update {
                    it.copy(isChecking = false, error = probe.error.toUiError())
                }
            }
        }
    }

    private fun ServerUrl.ParseError.toUiError() = when (this) {
        ServerUrl.ParseError.Empty -> ServerSetupError.Empty
        ServerUrl.ParseError.Invalid -> ServerSetupError.Invalid
        ServerUrl.ParseError.UnsupportedScheme -> ServerSetupError.UnsupportedScheme
    }

    private fun ProbeError.toUiError() = when (this) {
        ProbeError.Unreachable -> ServerSetupError.Unreachable
        ProbeError.NotRev -> ServerSetupError.NotRev
    }
}
