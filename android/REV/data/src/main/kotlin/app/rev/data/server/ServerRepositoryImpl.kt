package app.rev.data.server

import app.rev.data.auth.SessionTerminator
import app.rev.domain.model.Result
import app.rev.domain.model.ServerConfig
import app.rev.domain.model.ServerInfo
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.ProbeError
import app.rev.domain.repository.ServerRepository
import app.rev.data.remote.RevApi
import kotlinx.coroutines.flow.Flow

class ServerRepositoryImpl(
    private val api: RevApi,
    private val store: ServerConfigStore,
    private val sessions: SessionTerminator,
    /** Forgets everything cached from the previous server. */
    private val clearServerCaches: suspend () -> Unit,
) : ServerRepository {

    override val serverConfig: Flow<ServerConfig?> = store.config

    override suspend fun probe(url: ServerUrl): Result<ServerInfo, ProbeError> = api.health(url)

    override suspend fun setServer(url: ServerUrl, info: ServerInfo) {
        val previous = store.current()
        sessions.endSession()
        // Cached library data belongs to one server installation.
        if (previous?.instanceId != info.instanceId) clearServerCaches()
        // A home-network address belongs to one installation; keep it only for the same one.
        val same = previous?.instanceId == info.instanceId
        val localUrl = previous?.localUrl?.takeIf { same }
        store.set(ServerConfig(url, info.instanceId, localUrl, localDiscovery = if (same) previous.localDiscovery else true))
    }

    override suspend fun setLocalUrl(url: ServerUrl?) = store.setLocalUrl(url)

    override suspend fun setLocalDiscovery(enabled: Boolean) = store.setLocalDiscovery(enabled)

    override suspend fun clearServer() {
        sessions.endSession()
        store.clear()
    }
}
