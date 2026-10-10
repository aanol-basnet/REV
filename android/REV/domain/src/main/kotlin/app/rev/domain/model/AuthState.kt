package app.rev.domain.model

sealed interface AuthState {
    /** Stored configuration has not been read yet. */
    data object Loading : AuthState

    data object NeedsServer : AuthState

    data class SignedOut(val server: ServerConfig) : AuthState

    data class SignedIn(val server: ServerConfig, val user: User) : AuthState
}
