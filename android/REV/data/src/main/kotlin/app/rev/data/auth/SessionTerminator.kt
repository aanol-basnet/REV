package app.rev.data.auth

import app.rev.data.remote.RevApi
import app.rev.data.server.ServerConfigStore
import app.rev.data.session.SessionStore
import app.rev.domain.model.ServerUrl
import kotlinx.coroutines.withTimeoutOrNull

/** Ends the current session: revokes it on the server when reachable, and always forgets it locally. */
class SessionTerminator(
    private val api: RevApi,
    private val serverConfig: ServerConfigStore,
    private val sessionStore: SessionStore,
    /** Address to revoke through: whichever of the server's addresses is active. */
    private val serverUrl: suspend () -> ServerUrl? = { serverConfig.current()?.url },
) {
    suspend fun endSession() {
        val server = serverUrl()
        if (server != null && sessionStore.current() != null) {
            withTimeoutOrNull(REVOKE_TIMEOUT_MS) { api.logout(server) }
        }
        sessionStore.clear()
    }

    private companion object {
        const val REVOKE_TIMEOUT_MS = 5_000L
    }
}
