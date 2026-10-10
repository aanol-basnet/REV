package app.rev.data.auth

import app.rev.data.remote.RevApi
import app.rev.data.remote.toStoredSession
import app.rev.data.server.ServerConfigStore
import app.rev.data.session.SessionStore
import app.rev.domain.model.AuthState
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import app.rev.domain.model.User
import app.rev.domain.repository.AuthRepository
import app.rev.domain.repository.LoginError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class AuthRepositoryImpl(
    private val api: RevApi,
    private val serverConfig: ServerConfigStore,
    private val sessionStore: SessionStore,
    private val terminator: SessionTerminator,
    private val deviceName: String,
    /** Address to sign in through: whichever of the server's addresses is active. */
    private val serverUrl: suspend () -> ServerUrl? = { serverConfig.current()?.url },
) : AuthRepository {

    override val authState: Flow<AuthState> =
        combine(serverConfig.config, sessionStore.session) { config, session ->
            when {
                config == null -> AuthState.NeedsServer
                session == null -> AuthState.SignedOut(config)
                else -> AuthState.SignedIn(config, User(session.userId, session.username))
            }
        }

    override suspend fun login(username: String, password: String): Result<User, LoginError> {
        val server = serverUrl() ?: return Result.Err(LoginError.NoServer)
        return when (val result = api.login(server, username.trim(), password, deviceName)) {
            is Result.Err -> result
            is Result.Ok -> {
                val session = result.value.toStoredSession()
                sessionStore.save(session)
                Result.Ok(User(session.userId, session.username))
            }
        }
    }

    override suspend fun logout() = terminator.endSession()
}
