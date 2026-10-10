package app.rev.data.events

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.rev.data.FakeSessionStore
import app.rev.data.downloads.DownloadsRepositoryImpl
import app.rev.data.local.RevDatabase
import app.rev.data.playback.authenticatedMediaHttpClient
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.RevHttpClient
import app.rev.data.session
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.DownloadJobState
import app.rev.domain.model.Result
import app.rev.domain.model.ServerEvent
import app.rev.domain.model.ServerUrl
import app.rev.domain.model.ServiceState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun jobJson(state: String, updatedAt: Long = 2) =
    """{"id":"j1","catalogAlbumId":"mb1","catalogArtistId":null,"title":"Lateralus","artist":"Tool","year":2001,
       "state":"$state","progress":0.5,"error":null,"libraryAlbumId":null,"startedAt":1,"updatedAt":$updatedAt,"completedAt":null}"""

class EventParsingTest {
    @Test
    fun `parses download, library and system events`() {
        val download = parseEvent("""{"type":"download.updated","data":${jobJson("DOWNLOADING")}}""")
        assertEquals(DownloadJobState.DOWNLOADING, (download as ServerEvent.DownloadChanged).job.state)

        assertEquals(
            ServerEvent.LibraryUpdated("al-1"),
            parseEvent("""{"type":"library.updated","data":{"libraryAlbumId":"al-1"}}"""),
        )

        val system = parseEvent(
            """{"type":"system.slskd.status","data":{"status":"degraded","version":"0.24","detail":"Not connected","checkedAt":1}}""",
        ) as ServerEvent.ServiceChanged
        assertEquals("slskd", system.service)
        assertEquals(ServiceState.Degraded, system.health.state)
    }

    @Test
    fun `ignores unknown or malformed messages`() {
        assertNull(parseEvent("""{"type":"something.new","data":{}}"""))
        assertNull(parseEvent("not json"))
        assertNull(parseEvent("""{"type":"download.updated","data":{"id":1}}"""))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerEventsClientTest {

    @Test
    fun `connects with the session token while allowed, delivers events, disconnects when not`(): Unit = runBlocking {
        val server = MockWebServer().apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            server.enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"download.created","data":${jobJson("ADDED_TO_LIDARR")}}""")
                    }
                }).build(),
            )
            val url = (ServerUrl.parse(server.url("/").toString()) as Result.Ok).value
            val http = RevHttpClient(MockEngine { respond("") }, FakeSessionStore(session(access = "rev_at_live"))) { url }
            val allowed = MutableStateFlow(true)
            val client = ServerEventsClient(authenticatedMediaHttpClient(http), { url }, allowed)

            val received = scope.async { client.events.take(2).toList() }
            client.start(scope)

            val events = withTimeout(10_000) { received.await() }
            assertEquals(ServerEvent.Connected, events[0])
            assertEquals("j1", (events[1] as ServerEvent.DownloadChanged).job.id)

            val upgrade = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("/api/events", upgrade?.url?.encodedPath)
            assertEquals("Bearer rev_at_live", upgrade?.headers?.get("Authorization"))
            assertTrue(client.connected.value)

            allowed.value = false
            withTimeout(10_000) { client.connected.first { !it } }
        } finally {
            scope.cancel()
            server.close()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerEventsEndpointSwitchTest {

    @Test
    fun `moves the live connection to the new address when the route changes`(): Unit = runBlocking {
        val remote = MockWebServer().apply { start() }
        val home = MockWebServer().apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            for (server in listOf(remote, home)) {
                server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())
            }
            val remoteUrl = (ServerUrl.parse(remote.url("/").toString()) as Result.Ok).value
            val homeUrl = (ServerUrl.parse(home.url("/").toString()) as Result.Ok).value
            val active = MutableStateFlow(remoteUrl)
            val http = RevHttpClient(
                MockEngine { respond("") },
                FakeSessionStore(session(access = "rev_at_live")),
                trustedServers = { listOf(remoteUrl, homeUrl) },
            ) { active.value }
            val client = ServerEventsClient(authenticatedMediaHttpClient(http), { active.value }, MutableStateFlow(true), active)
            client.start(scope)

            assertEquals("/api/events", remote.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
            withTimeout(10_000) { client.connected.first { it } }

            active.value = homeUrl
            val upgrade = home.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("/api/events", upgrade?.url?.encodedPath)
            assertEquals("Bearer rev_at_live", upgrade?.headers?.get("Authorization"))
            withTimeout(10_000) { client.connected.first { it } }
        } finally {
            scope.cancel()
            remote.close()
            home.close()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadCacheTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RevDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private fun job(state: DownloadJobState, updatedAt: Long) =
        DownloadJob("j1", "mb1", null, "Lateralus", "Tool", 2001, state, null, null, null, 1, updatedAt, null)

    @Test
    fun `out-of-order updates never roll a job back`() = runTest {
        val url = (ServerUrl.parse("100.64.0.1:4600") as Result.Ok).value
        val repo = DownloadsRepositoryImpl(
            LibraryApi(RevHttpClient(MockEngine { respond("") }, FakeSessionStore()) { url }) { url },
            db.downloadJobDao(),
        )
        repo.store(job(DownloadJobState.DOWNLOADING, updatedAt = 20))
        repo.store(job(DownloadJobState.SEARCHING, updatedAt = 10))
        assertEquals(DownloadJobState.DOWNLOADING, repo.observeLatestJob("mb1").first()?.state)

        repo.store(job(DownloadJobState.IMPORTING, updatedAt = 30))
        assertEquals(DownloadJobState.IMPORTING, repo.observeJobs().first().single().state)
        db.close()
    }
}
