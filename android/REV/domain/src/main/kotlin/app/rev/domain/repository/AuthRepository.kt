package app.rev.domain.repository

import app.rev.domain.model.AuthState
import app.rev.domain.model.Result
import app.rev.domain.model.User
import kotlinx.coroutines.flow.Flow

sealed interface LoginError {
    data object InvalidCredentials : LoginError
    data object RateLimited : LoginError
    data object Unreachable : LoginError
    data object NoServer : LoginError
    data class Unexpected(val message: String) : LoginError
}

interface AuthRepository {
    val authState: Flow<AuthState>

    suspend fun login(username: String, password: String): Result<User, LoginError>

    /** Ends the session on the server if reachable; always clears local credentials. */
    suspend fun logout()
}
