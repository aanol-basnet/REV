package app.rev.data.connection

import app.rev.domain.model.ActiveEndpoint
import app.rev.domain.model.ConnectionRoute
import app.rev.domain.model.Result
import app.rev.domain.model.ServerConfig
import app.rev.domain.model.ServerInfo
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.ProbeError
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RevConnectionManagerTest {
    private val remote = (ServerUrl.parse("https://rev.example.com") as Result.Ok).value
    private val local = (ServerUrl.parse("http://192.168.1.69:4600") as Result.Ok).value
    private val config = MutableStateFlow<ServerConfig?>(ServerConfig(remote, "abc", local))
    private val network = FakeNetwork()

    /** What the home address answers: this installation, another one, or nothing. */
    private var localAnswer: Result<ServerInfo, ProbeError> = Result.Ok(ServerInfo("0.1.0", "abc"))
    private val probed = mutableListOf<ServerUrl>()

    private class FakeNetwork : NetworkMonitor {
        val network = MutableStateFlow<String?>("home-wifi")
        override val isOnline = MutableStateFlow(true)
        override val currentNetwork get() = network
    }

    private val timings = RevConnectionManager.Timings()

    /** What other addresses answer (for discovery); anything absent is unreachable. */
    private val answers = mutableMapOf<ServerUrl, Result<ServerInfo, ProbeError>>()
    /** What the server reports as its home-network addresses. */
    private var reported: List<ServerUrl> = emptyList()
    private var discoveries = 0

    private fun TestScope.manager(withDiscovery: Boolean = false) = RevConnectionManager(
        config = config,
        network = network,
        probe = { url ->
            probed += url
            when (url) {
                local -> localAnswer
                remote -> Result.Ok(ServerInfo("0.1.0", "abc"))
                else -> answers[url] ?: Result.Err(ProbeError.Unreachable)
            }
        },
        scope = backgroundScope,
        now = { testScheduler.currentTime },
        discover = if (withDiscovery) {
            {
                discoveries++
                reported
            }
        } else {
            null
        },
        adopt = { url -> config.value = config.value?.copy(localUrl = url) },
    )

    private val moved = (ServerUrl.parse("http://192.168.1.42:4600") as Result.Ok).value

    private val localEndpoint = ActiveEndpoint(local, ConnectionRoute.Local)
    private val remoteEndpoint = ActiveEndpoint(remote, ConnectionRoute.Remote)

    @Test
    fun `home address answering as this server is used`() = runTest {
        val m = manager()
        runCurrent()
        assertEquals(localEndpoint, m.endpoint.value)
        assertEquals(local, m.activeUrl())
    }

    @Test
    fun `home address unreachable means the remote one`() = runTest {
        localAnswer = Result.Err(ProbeError.Unreachable)
        val m = manager()
        runCurrent()
        assertEquals(remoteEndpoint, m.endpoint.value)
        assertEquals(remote, m.activeUrl())
    }

    @Test
    fun `switches to the home address once it answers`() = runTest {
        localAnswer = Result.Err(ProbeError.Unreachable)
        val m = manager()
        runCurrent()
        assertEquals(remoteEndpoint, m.endpoint.value)

        localAnswer = Result.Ok(ServerInfo("0.1.0", "abc"))
        advanceTimeBy(timings.remoteCheckMs - 1)
        assertEquals("not before the next check", remoteEndpoint, m.endpoint.value)
        advanceTimeBy(2)
        assertEquals(localEndpoint, m.endpoint.value)
    }

    @Test
    fun `one failed check doesn't leave the home address, consecutive failures do`() = runTest {
        val m = manager()
        runCurrent()
        localAnswer = Result.Err(ProbeError.Unreachable)
        advanceTimeBy(timings.localCheckMs + 1)
        assertEquals("a single failure is tolerated", localEndpoint, m.endpoint.value)

        localAnswer = Result.Ok(ServerInfo("0.1.0", "abc"))
        advanceTimeBy(timings.localRetryMs)
        assertEquals(localEndpoint, m.endpoint.value)

        localAnswer = Result.Err(ProbeError.Unreachable)
        advanceTimeBy(timings.localCheckMs)
        advanceTimeBy(timings.localRetryMs)
        assertEquals(remoteEndpoint, m.endpoint.value)
    }

    @Test
    fun `doesn't flap back to the home address right after leaving it`() = runTest {
        val m = manager()
        runCurrent()
        localAnswer = Result.Err(ProbeError.Unreachable)
        advanceTimeBy(timings.localCheckMs + timings.localRetryMs + 1)
        assertEquals(remoteEndpoint, m.endpoint.value)

        // It answers again at once, and someone asks for a check: still within the cooldown.
        localAnswer = Result.Ok(ServerInfo("0.1.0", "abc"))
        m.recheck()
        runCurrent()
        assertEquals(remoteEndpoint, m.endpoint.value)

        advanceTimeBy(timings.switchBackCooldownMs + timings.remoteCheckMs)
        assertEquals(localEndpoint, m.endpoint.value)
    }

    @Test
    fun `a new network is checked right away, without the cooldown`() = runTest {
        localAnswer = Result.Err(ProbeError.Unreachable)
        val m = manager()
        runCurrent()
        assertEquals(remoteEndpoint, m.endpoint.value)

        // Back home: the phone joins the home Wi-Fi.
        localAnswer = Result.Ok(ServerInfo("0.1.0", "abc"))
        network.network.value = "home-wifi-2"
        runCurrent()
        assertEquals(localEndpoint, m.endpoint.value)
    }

    @Test
    fun `leaving home falls back to the remote address within seconds`() = runTest {
        val m = manager()
        runCurrent()
        // Wi-Fi → cellular: the home address no longer answers.
        localAnswer = Result.Err(ProbeError.Unreachable)
        network.network.value = "cellular"
        runCurrent()
        advanceTimeBy(timings.localRetryMs + 1)
        assertEquals(remoteEndpoint, m.endpoint.value)
    }

    @Test
    fun `a failed request to the home address brings the next check forward`() = runTest {
        val m = manager()
        runCurrent()
        localAnswer = Result.Err(ProbeError.Unreachable)
        m.reportUnreachable("${local.value}/api/library/tracks/t1/stream")
        runCurrent()
        assertEquals("one failure isn't enough", localEndpoint, m.endpoint.value)
        advanceTimeBy(timings.localRetryMs + 1)
        assertEquals(remoteEndpoint, m.endpoint.value)
    }

    @Test
    fun `another installation at the home address is never used`() = runTest {
        localAnswer = Result.Ok(ServerInfo("0.1.0", "someone-else"))
        val m = manager()
        runCurrent()
        advanceTimeBy(timings.remoteCheckMs * 3)
        assertEquals(remoteEndpoint, m.endpoint.value)
    }

    @Test
    fun `without a home address only the remote one is used and nothing is probed`() = runTest {
        config.value = ServerConfig(remote, "abc")
        val m = manager()
        runCurrent()
        advanceTimeBy(timings.remoteCheckMs * 3)
        assertEquals(remoteEndpoint, m.endpoint.value)
        assertTrue(probed.isEmpty())
        assertEquals(listOf(remote), m.trustedUrls())
    }

    @Test
    fun `remote failures never move the route anywhere else`() = runTest {
        localAnswer = Result.Err(ProbeError.Unreachable)
        val m = manager()
        runCurrent()
        advanceTimeBy(timings.remoteCheckMs * 5)
        assertEquals(remoteEndpoint, m.endpoint.value)
        // The manager only ever probes the home address; remote failures are left to request retries.
        assertTrue(probed.all { it == local })
    }

    @Test
    fun `offline, the route is kept and nothing is probed`() = runTest {
        val m = manager()
        runCurrent()
        val before = probed.size
        network.network.value = null
        runCurrent()
        advanceTimeBy(timings.localCheckMs * 3)
        assertEquals(localEndpoint, m.endpoint.value)
        assertEquals(before, probed.size)
    }

    @Test
    fun `both addresses are trusted with the session, nothing else`() = runTest {
        val m = manager()
        runCurrent()
        assertEquals(listOf(remote, local), m.trustedUrls())
        config.value = null
        runCurrent()
        assertTrue(m.trustedUrls().isEmpty())
        assertNull(m.activeUrl())
    }

    @Test
    fun `background work waits for the first decision`() = runTest {
        val m = manager()
        val url = async { m.awaitActiveUrl() }
        runCurrent()
        assertEquals(local, url.await())
    }

    @Test
    fun `a fresh start rediscovers the home address`() = runTest {
        // First run: away from home.
        localAnswer = Result.Err(ProbeError.Unreachable)
        val first = manager()
        runCurrent()
        assertEquals(remoteEndpoint, first.endpoint.value)
        // Next app start, at home: nothing remembered, the home address is found again.
        localAnswer = Result.Ok(ServerInfo("0.1.0", "abc"))
        val second = manager()
        runCurrent()
        assertEquals(localEndpoint, second.endpoint.value)
        assertFalse(second.endpoint.value == first.endpoint.value)
    }

    @Test
    fun `follows the server to a new home address after a new lease`() = runTest {
        localAnswer = Result.Err(ProbeError.Unreachable)
        reported = listOf(moved)
        answers[moved] = Result.Ok(ServerInfo("0.1.0", "abc"))
        val m = manager(withDiscovery = true)
        runCurrent()
        assertEquals(moved, config.value?.localUrl)
        assertEquals(ActiveEndpoint(moved, ConnectionRoute.Local), m.endpoint.value)
    }

    @Test
    fun `a reported address that isn't this server is not adopted`() = runTest {
        localAnswer = Result.Err(ProbeError.Unreachable)
        val impostor = (ServerUrl.parse("http://192.168.1.50:4600") as Result.Ok).value
        val public = (ServerUrl.parse("http://203.0.113.7:4600") as Result.Ok).value
        reported = listOf(impostor, public)
        answers[impostor] = Result.Ok(ServerInfo("0.1.0", "someone-else"))
        answers[public] = Result.Ok(ServerInfo("0.1.0", "abc"))
        val m = manager(withDiscovery = true)
        runCurrent()
        assertEquals(local, config.value?.localUrl)
        assertEquals(remoteEndpoint, m.endpoint.value)
        assertFalse("plain HTTP to a public host is never tried", public in probed)
    }

    @Test
    fun `finds the home address when none was set`() = runTest {
        config.value = ServerConfig(remote, "abc")
        reported = listOf(moved)
        answers[moved] = Result.Ok(ServerInfo("0.1.0", "abc"))
        val m = manager(withDiscovery = true)
        runCurrent()
        assertEquals(ActiveEndpoint(moved, ConnectionRoute.Local), m.endpoint.value)
    }

    @Test
    fun `discovery stays off once the user removed the address`() = runTest {
        config.value = ServerConfig(remote, "abc", localDiscovery = false)
        reported = listOf(moved)
        answers[moved] = Result.Ok(ServerInfo("0.1.0", "abc"))
        val m = manager(withDiscovery = true)
        runCurrent()
        advanceTimeBy(timings.discoveryIntervalMs * 2)
        assertEquals(remoteEndpoint, m.endpoint.value)
        assertEquals(0, discoveries)
    }

    @Test
    fun `asks the server at most every ten minutes while away`() = runTest {
        localAnswer = Result.Err(ProbeError.Unreachable)
        manager(withDiscovery = true)
        runCurrent()
        assertEquals(1, discoveries)
        advanceTimeBy(timings.discoveryIntervalMs - 1)
        assertEquals(1, discoveries)
        advanceTimeBy(timings.remoteCheckMs)
        assertEquals(2, discoveries)
    }
}
