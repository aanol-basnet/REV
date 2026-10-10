package app.rev.data.remote

import app.rev.data.FakeSessionStore
import app.rev.data.session
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RevHttpClientTest {
    private val server = (ServerUrl.parse("http://100.64.0.1:4600") as Result.Ok).value
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val challenge = headersOf(
        HttpHeaders.ContentType to listOf("application/json"),
        HttpHeaders.WWWAuthenticate to listOf("Bearer"),
    )
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(
        store: FakeSessionStore,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = RevHttpClient(
        MockEngine { request -> requests += request; handler(request) },
        store,
    ) { server }

    private fun MockRequestHandleScope.tokens(access: String, refresh: String) = respond(
        """{"accessToken":"$access","accessTokenExpiresAt":1,"refreshToken":"$refresh",
           "refreshTokenExpiresAt":2,"user":{"id":1,"username":"alice"}}""",
        HttpStatusCode.OK,
        jsonHeaders,
    )

    private fun HttpRequestData.bearer(): String? = headers[HttpHeaders.Authorization]

    @Test
    fun `attaches the token only to authenticated routes on the configured server`() = runTest {
        val http = client(FakeSessionStore(session())) { respond("{}", HttpStatusCode.OK, jsonHeaders) }

        http.client.get("${server.value}/api/auth/session")
        http.client.get("${server.value}/api/system/health")
        http.client.get("http://evil.example/api/auth/session")

        assertEquals("Bearer rev_at_old", requests[0].bearer())
        assertNull(requests[1].bearer())
        assertNull(requests[2].bearer())
    }

    @Test
    fun `refreshes once on 401 and retries with the new token`() = runTest {
        val store = FakeSessionStore(session())
        val http = client(store) { request ->
            when {
                request.url.encodedPath == "/api/auth/refresh" -> tokens("rev_at_new", "rev_rt_new")
                request.bearer() == "Bearer rev_at_new" -> respond("{}", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.Unauthorized, challenge)
            }
        }

        val response = http.client.get("${server.value}/api/auth/session")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("rev_at_new", store.current()?.accessToken)
        assertEquals("rev_rt_new", store.current()?.refreshToken)
        val refresh = requests.single { it.url.encodedPath == "/api/auth/refresh" }
        assertNull("refresh call must not carry the access token", refresh.bearer())
    }

    @Test
    fun `a rejected refresh token signs the user out`() = runTest {
        val store = FakeSessionStore(session())
        val http = client(store) { respond("{}", HttpStatusCode.Unauthorized, challenge) }

        val response = http.client.get("${server.value}/api/auth/session")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(store.current())
    }

    @Test
    fun `a bearer challenge from another host never triggers a refresh or leaks a token`() = runTest {
        val store = FakeSessionStore(session())
        val http = client(store) { respond("{}", HttpStatusCode.Unauthorized, challenge) }

        http.client.get("http://evil.example/api/anything")

        assertEquals(1, requests.size)
        assertNull(requests.single().bearer())
        assertEquals("rev_at_old", store.current()?.accessToken)
    }

    // ---- Two addresses: home network and remote ----------------------------------------------

    private val home = (ServerUrl.parse("http://192.168.1.69:4600") as Result.Ok).value
    private val remote = (ServerUrl.parse("https://rev.example.com") as Result.Ok).value

    private fun twoAddressClient(
        store: FakeSessionStore,
        active: () -> ServerUrl,
        unreachable: MutableList<String> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = RevHttpClient(
        MockEngine { request -> requests += request; handler(request) },
        store,
        trustedServers = { listOf(remote, home) },
        onUnreachable = { unreachable += it },
        currentServer = active,
    )

    @Test
    fun `the same session works through either address, and nowhere else`() = runTest {
        val http = twoAddressClient(FakeSessionStore(session()), { home }) { respond("{}", HttpStatusCode.OK, jsonHeaders) }

        http.client.get("${home.value}/api/auth/session")
        http.client.get("${remote.value}/api/auth/session")
        http.client.get("http://192.168.1.70:4600/api/auth/session")
        http.client.get("http://192.168.1.69:46000/api/auth/session")

        assertEquals("Bearer rev_at_old", requests[0].bearer())
        assertEquals("Bearer rev_at_old", requests[1].bearer())
        assertNull("another LAN host", requests[2].bearer())
        assertNull("same host, other port", requests[3].bearer())
        assertEquals("rev_at_old", http.accessTokenFor("${home.value}/api/library/tracks/t1/stream"))
        assertNull(http.accessTokenFor("http://192.168.1.70:4600/api/library/tracks/t1/stream"))
    }

    @Test
    fun `redirects are never followed, so a token can't be steered elsewhere`() = runTest {
        val http = twoAddressClient(FakeSessionStore(session()), { home }) { request ->
            if (request.url.host == "192.168.1.69") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://evil.example/steal"))
            } else {
                respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val response = http.client.get("${home.value}/api/auth/session")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a refresh goes to the active address`() = runTest {
        val store = FakeSessionStore(session())
        var active = remote
        val http = twoAddressClient(store, { active }) { request ->
            when {
                request.url.encodedPath == "/api/auth/refresh" -> tokens("rev_at_new", "rev_rt_new")
                request.bearer() == "Bearer rev_at_new" -> respond("{}", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.Unauthorized, challenge)
            }
        }
        active = home

        assertEquals(HttpStatusCode.OK, http.client.get("${remote.value}/api/auth/session").status)
        assertEquals("192.168.1.69", requests.single { it.url.encodedPath == "/api/auth/refresh" }.url.host)
    }

    @Test
    fun `connection failures are reported so the route can be re-checked`() = runTest {
        val unreachable = mutableListOf<String>()
        val http = twoAddressClient(FakeSessionStore(session()), { home }, unreachable) { throw java.io.IOException("no route") }

        runCatching { http.client.get("${home.value}/api/auth/session") }

        assertEquals(listOf("${home.value}/api/auth/session"), unreachable)
    }
}
