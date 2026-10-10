package app.rev.domain.repository

import app.rev.domain.model.Result
import app.rev.domain.model.ServerConfig
import app.rev.domain.model.ServerInfo
import app.rev.domain.model.ServerUrl
import kotlinx.coroutines.flow.Flow

sealed interface ProbeError {
    /** Nothing answered at that address. */
    data object Unreachable : ProbeError

    /** Something answered, but it isn't a REV server. */
    data object NotRev : ProbeError
}

interface ServerRepository {
    /** Null until a server has been configured. */
    val serverConfig: Flow<ServerConfig?>

    /** Checks whether [url] is a reachable REV server. Does not change any configuration. */
    suspend fun probe(url: ServerUrl): Result<ServerInfo, ProbeError>

    /** Makes [url] the configured server. Signs out of any previous server. */
    suspend fun setServer(url: ServerUrl, info: ServerInfo)

    /** Forgets the configured server and signs out. */
    suspend fun clearServer()

    /**
     * Sets (or with null, removes) the server's home-network address. Keeps the session: it is
     * the same installation, only reached a different way. Used only once it answers as this
     * installation.
     */
    suspend fun setLocalUrl(url: ServerUrl?) {}

    /** Lets the app find the home-network address by itself (again). */
    suspend fun setLocalDiscovery(enabled: Boolean) {}
}
