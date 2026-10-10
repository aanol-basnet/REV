package app.rev.data.connection

import app.rev.domain.model.ActiveEndpoint
import app.rev.domain.model.ConnectionRoute
import app.rev.domain.model.ConnectionState
import app.rev.domain.model.Result
import app.rev.domain.model.ServerConfig
import app.rev.domain.repository.ConnectionRepository
import app.rev.domain.repository.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks whether the configured server is reachable through the active address ([endpoint]: home
 * network or remote, chosen by [RevConnectionManager]). Checks immediately when the network,
 * server or route changes, then periodically while anyone is observing.
 */
class ConnectionMonitor(
    private val servers: ServerRepository,
    network: NetworkMonitor,
    scope: CoroutineScope,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    endpoint: Flow<ActiveEndpoint?> = servers.serverConfig.map { c -> c?.let { ActiveEndpoint(it.url, ConnectionRoute.Remote) } },
    /** Also re-evaluates the route when the user asks for a check. */
    private val onRecheck: () -> Unit = {},
) : ConnectionRepository {

    private val recheckRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val connectionState: StateFlow<ConnectionState> =
        combine(servers.serverConfig, endpoint, network.isOnline) { config, active, online -> Triple(config, active, online) }
            .distinctUntilChanged()
            .flatMapLatest { (config, active, online) ->
                when {
                    config == null || active == null -> flowOf(ConnectionState.NotConfigured)
                    !online -> flowOf(ConnectionState.NoNetwork)
                    else -> periodicChecks(config, active)
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConnectionState.Checking)

    override fun recheck() {
        onRecheck()
        recheckRequests.tryEmit(Unit)
    }

    private fun periodicChecks(config: ServerConfig, active: ActiveEndpoint): Flow<ConnectionState> = flow {
        while (true) {
            emit(check(config, active))
            withTimeoutOrNull(checkIntervalMs) { recheckRequests.first() }
        }
    }

    private suspend fun check(config: ServerConfig, active: ActiveEndpoint): ConnectionState =
        when (val result = servers.probe(active.url)) {
            is Result.Err -> ConnectionState.ServerUnreachable
            is Result.Ok -> if (result.value.instanceId == config.instanceId) {
                ConnectionState.Online(result.value, active.route)
            } else {
                ConnectionState.WrongServer(result.value)
            }
        }

    companion object {
        const val DEFAULT_CHECK_INTERVAL_MS = 30_000L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
