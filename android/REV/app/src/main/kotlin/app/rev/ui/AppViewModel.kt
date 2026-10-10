package app.rev.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.AuthState
import app.rev.domain.model.ConnectionState
import app.rev.domain.repository.AppTheme
import app.rev.domain.repository.AppearanceRepository
import app.rev.domain.repository.AuthRepository
import app.rev.domain.repository.ConnectionRepository
import app.rev.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    auth: AuthRepository,
    playback: PlaybackController,
    appearance: AppearanceRepository,
    connection: ConnectionRepository,
) : ViewModel() {
    val authState: StateFlow<AuthState> =
        auth.authState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthState.Loading)

    val theme: StateFlow<AppTheme> =
        appearance.theme.stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.Dark)

    val connection: StateFlow<ConnectionState> = connection.connectionState

    init {
        // A queue belongs to a signed-in session: without one nothing in it can stream, and the
        // next person to sign in shouldn't inherit it.
        viewModelScope.launch {
            auth.authState
                .filter { it !is AuthState.Loading }
                .map { it is AuthState.SignedIn }
                .distinctUntilChanged()
                .filter { signedIn -> !signedIn }
                .collect { playback.clearQueue() }
        }
    }
}
