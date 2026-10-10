package app.rev.data.connection

import app.rev.domain.model.ActiveEndpoint
import app.rev.domain.model.ConnectionRoute
import app.rev.domain.model.ConnectionState
import app.rev.domain.model.Result
import app.rev.domain.model.ServerConfig
import app.rev.domain.model.ServerInfo
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.ProbeError
import app.rev.domain.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionMonitorTest {
    private val url = (ServerUrl.parse("100.64.0.1:4600") as Result.Ok).value
    private val config = ServerConfig(url, instanceId = "abc")

    private class FakeServers(config: ServerConfig?) : ServerRepository {
        override val serverConfig = MutableStateFlow(config)
        var probeResult: Result<ServerInfo, ProbeError> = Result.Ok(ServerInfo("0.1.0", "abc"))
        var probes = 0
        override suspend fun probe(url: ServerUrl): Result<ServerInfo, ProbeError> {
            probes++
            return probeResult
        }
        override suspend fun setServer(url: ServerUrl, info: ServerInfo) = Unit
        override suspend fun clearServer() = Unit
    }

    private class FakeNetwork(online: Boolean) : NetworkMonitor {
        override val isOnline = MutableStateFlow(online)
    }

    @Test
    fun `reports online, then unreachable on the next periodic check`() = runTest {
        val servers = FakeServers(config)
        val monitor = ConnectionMonitor(servers, FakeNetwork(true), backgroundScope, checkIntervalMs = 1_000)
        backgroundScope.launch { monitor.connectionState.collect {} }
        runCurrent()
        assertEquals(ConnectionState.Online(ServerInfo("0.1.0", "abc")), monitor.connectionState.value)

        servers.probeResult = Result.Err(ProbeError.Unreachable)
        advanceTimeBy(1_001)
        assertEquals(ConnectionState.ServerUnreachable, monitor.connectionState.value)
    }

    @Test
    fun `checks the active address and reports the route`() = runTest {
        val servers = FakeServers(config)
        val home = (ServerUrl.parse("192.168.1.69:4600") as Result.Ok).value
        val endpoint = MutableStateFlow<ActiveEndpoint?>(ActiveEndpoint(url, ConnectionRoute.Remote))
        val probed = mutableListOf<ServerUrl>()
        val tracking = object : ServerRepository by servers {
            override suspend fun probe(url: ServerUrl): Result<ServerInfo, ProbeError> {
                probed += url
                return servers.probe(url)
            }
        }
        val monitor = ConnectionMonitor(tracking, FakeNetwork(true), backgroundScope, endpoint = endpoint)
        backgroundScope.launch { monitor.connectionState.collect {} }
        runCurrent()
        assertEquals(ConnectionState.Online(ServerInfo("0.1.0", "abc"), ConnectionRoute.Remote), monitor.connectionState.value)

        endpoint.value = ActiveEndpoint(home, ConnectionRoute.Local)
        runCurrent()
        assertEquals(ConnectionState.Online(ServerInfo("0.1.0", "abc"), ConnectionRoute.Local), monitor.connectionState.value)
        assertEquals(listOf(url, home), probed)
    }

    @Test
    fun `detects a different REV installation at the configured address`() = runTest {
        val servers = FakeServers(config).apply { probeResult = Result.Ok(ServerInfo("0.1.0", "other")) }
        val monitor = ConnectionMonitor(servers, FakeNetwork(true), backgroundScope)
        backgroundScope.launch { monitor.connectionState.collect {} }
        runCurrent()
        assertEquals(ConnectionState.WrongServer(ServerInfo("0.1.0", "other")), monitor.connectionState.value)
    }

    @Test
    fun `does not probe without a network or a configured server`() = runTest {
        val servers = FakeServers(config)
        val network = FakeNetwork(false)
        val monitor = ConnectionMonitor(servers, network, backgroundScope)
        backgroundScope.launch { monitor.connectionState.collect {} }
        runCurrent()
        assertEquals(ConnectionState.NoNetwork, monitor.connectionState.value)

        servers.serverConfig.value = null
        runCurrent()
        assertEquals(ConnectionState.NotConfigured, monitor.connectionState.value)
        assertEquals(0, servers.probes)

        servers.serverConfig.value = config
        network.isOnline.value = true
        runCurrent()
        assertEquals(1, servers.probes)
    }

    @Test
    fun `recheck probes immediately instead of waiting for the interval`() = runTest {
        val servers = FakeServers(config)
        val monitor = ConnectionMonitor(servers, FakeNetwork(true), backgroundScope, checkIntervalMs = 60_000)
        backgroundScope.launch { monitor.connectionState.collect {} }
        runCurrent()
        monitor.recheck()
        runCurrent()
        assertEquals(2, servers.probes)
    }
}
