package app.rev.data.connection

import app.rev.domain.model.ActiveEndpoint
import app.rev.domain.model.ConnectionRoute
import app.rev.domain.model.Result
import app.rev.domain.model.ServerConfig
import app.rev.domain.model.ServerInfo
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.ProbeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides which address of the configured REV server requests go to: the home-network address
 * when it answers as this same installation, otherwise the remote (public) address. Everything
 * else in the app asks [activeUrl] and never knows which one it got.
 *
 * Switching rules, chosen so the route stays stable:
 * - Remote → Local needs a successful health check that reports the configured instance id, and
 *   not within [Timings.switchBackCooldownMs] of having left Local on the same network.
 * - Local → Remote needs [Timings.localFailureThreshold] failed checks in a row; one hiccup
 *   doesn't move anything. A failed request to the local address only triggers an early check.
 * - The remote address is the fallback and is never abandoned for anything else; when it fails,
 *   the usual request retries apply.
 * - A new network (or a changed configuration) is fresh evidence: Local is tried right away.
 * - When the home address doesn't answer (or none is set), the server is asked for its current one
 *   at most every [Timings.discoveryIntervalMs] — a new DHCP lease is followed automatically. A
 *   found address is adopted only if it answers as this same installation.
 *
 * Changing the route never interrupts anything: requests already under way (an audio stream, a
 * download) keep their connection; only new requests use the new address.
 */
class RevConnectionManager(
    private val config: Flow<ServerConfig?>,
    network: NetworkMonitor,
    private val probe: suspend (ServerUrl) -> Result<ServerInfo, ProbeError>,
    scope: CoroutineScope,
    private val isForeground: () -> Boolean = { true },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timings: Timings = Timings(),
    /** Asks the server (over the active address) for its current home-network addresses. */
    private val discover: (suspend () -> List<ServerUrl>)? = null,
    /** Saves a home-network address found by [discover]. */
    private val adopt: suspend (ServerUrl) -> Unit = {},
) {
    data class Timings(
        /** How often the local address is re-checked while in use. */
        val localCheckMs: Long = 30_000,
        /** Delay before confirming a failed local check. */
        val localRetryMs: Long = 3_000,
        val localFailureThreshold: Int = 2,
        /** How often to look for the local address while on the remote one, app in foreground. */
        val remoteCheckMs: Long = 60_000,
        /** Same, with the app in the background (downloads, playback): rarer, to save battery. */
        val remoteCheckBackgroundMs: Long = 300_000,
        /** After leaving Local, don't return to it on the same network sooner than this. */
        val switchBackCooldownMs: Long = 60_000,
        /** How often to ask the server whether its home-network address changed, while it isn't answering. */
        val discoveryIntervalMs: Long = 600_000,
    )

    private val configSnapshot = config.stateIn(scope, SharingStarted.Eagerly, null)

    private val _endpoint = MutableStateFlow<ActiveEndpoint?>(null)

    /** Where requests go now; null until a server is configured. */
    val endpoint: StateFlow<ActiveEndpoint?> = _endpoint.asStateFlow()

    private val checkRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Whether the route for the current configuration and network has been decided once. */
    private val settled = MutableStateFlow(false)

    init {
        scope.launch {
            combine(configSnapshot, network.currentNetwork) { c, n -> c to n }
                .distinctUntilChanged()
                .collectLatest { (c, networkId) -> manage(c, networkId) }
        }
    }

    /** The base URL for new requests. Cheap; called for every request. */
    fun activeUrl(): ServerUrl? = _endpoint.value?.url ?: configSnapshot.value?.url

    /**
     * Like [activeUrl], for work that starts in a fresh process (a background download): waits
     * for the configuration to load and, briefly, for the first local check, so it doesn't go the
     * long way round when the home address is about to be confirmed.
     */
    suspend fun awaitActiveUrl(): ServerUrl? {
        if (config.first() == null) return null
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) { settled.first { it } }
        return activeUrl()
    }

    /** The configured remote address, whatever the route. */
    fun remoteUrl(): ServerUrl? = configSnapshot.value?.url

    /**
     * Addresses that may receive the session token: both addresses of the configured server
     * (a request started on one keeps working after a switch), nothing else.
     */
    fun trustedUrls(): List<ServerUrl> = configSnapshot.value?.let { listOfNotNull(it.url, it.localUrl) }.orEmpty()

    /** A request to [url] failed to connect. If that's the local address in use, check it soon. */
    fun reportUnreachable(url: String) {
        val active = _endpoint.value ?: return
        if (active.route == ConnectionRoute.Local && url.startsWith(active.url.value + "/")) checkRequests.tryEmit(Unit)
    }

    /** Asks for an immediate check (e.g. the user tapped "Check now"). */
    fun recheck() {
        checkRequests.tryEmit(Unit)
    }

    private suspend fun manage(config: ServerConfig?, networkId: String?) {
        settled.value = false
        if (config == null) {
            _endpoint.value = null
            return
        }
        val remote = ActiveEndpoint(config.url, ConnectionRoute.Remote)
        val localUrl = config.localUrl
        val current = _endpoint.value
        // Start on the remote address unless we're already on an address of this configuration.
        if (current == null || current.url !in listOfNotNull(config.url, localUrl)) _endpoint.value = remote
        val canDiscover = config.localDiscovery && discover != null
        if (localUrl == null && !canDiscover) {
            _endpoint.value = remote
            settled.value = true
            return
        }
        // Offline: nothing to learn; keep the route until a network appears.
        if (networkId == null) {
            settled.value = true
            return
        }

        var failures = 0
        var leftLocalAt: Long? = null
        var discoveredAt: Long? = null
        while (true) {
            if (localUrl != null && _endpoint.value?.route == ConnectionRoute.Local) {
                if (localAnswers(localUrl, config.instanceId)) {
                    failures = 0
                    settled.value = true
                    waitFor(timings.localCheckMs)
                } else if (++failures >= timings.localFailureThreshold) {
                    failures = 0
                    leftLocalAt = now()
                    _endpoint.value = remote
                } else {
                    waitFor(timings.localRetryMs)
                }
                continue
            }
            val coolingDown = leftLocalAt?.let { now() - it < timings.switchBackCooldownMs } ?: false
            if (localUrl != null && !coolingDown && localAnswers(localUrl, config.instanceId)) {
                _endpoint.value = ActiveEndpoint(localUrl, ConnectionRoute.Local)
                settled.value = true
                continue
            }
            // The home address isn't answering (or isn't known): perhaps the server moved.
            if (canDiscover && discoveredAt.let { it == null || now() - it >= timings.discoveryIntervalMs }) {
                discoveredAt = now()
                val moved = discoverMoved(config)
                if (moved != null) {
                    // Saving it changes the configuration, which restarts this loop on the new address.
                    adopt(moved)
                    return
                }
            }
            settled.value = true
            waitFor(if (isForeground()) timings.remoteCheckMs else timings.remoteCheckBackgroundMs)
        }
    }

    /** A home-network address the server reports that answers as this installation, if any. */
    private suspend fun discoverMoved(config: ServerConfig): ServerUrl? {
        val candidates = try {
            discover?.invoke().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return candidates
            // Plain HTTP only on private networks, and not the address that just failed.
            .filter { !it.isInsecure && it != config.localUrl }
            .firstOrNull { localAnswers(it, config.instanceId) }
    }

    /** Only the configured installation counts; another device now at that address does not. */
    private suspend fun localAnswers(url: ServerUrl, instanceId: String): Boolean =
        (probe(url) as? Result.Ok)?.value?.instanceId == instanceId

    private suspend fun waitFor(ms: Long) {
        withTimeoutOrNull(ms) { checkRequests.first() }
    }

    private companion object {
        const val SETTLE_TIMEOUT_MS = 4_000L
    }
}
