package app.rev.data.remote

import app.rev.data.FakeSessionStore
import app.rev.domain.model.AlbumAvailability
import app.rev.domain.model.Result
import app.rev.domain.model.SourceStatus
import app.rev.domain.model.ServerInfo
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.LoginError
import app.rev.domain.repository.ProbeError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class RevApiTest {
    private val server = (ServerUrl.parse("100.64.0.1:4600") as Result.Ok).value
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: suspend MockRequestHandleScope.() -> HttpResponseData) =
        RevApi(RevHttpClient(MockEngine { handler() }, FakeSessionStore()) { server })

    @Test
    fun `health recognises a REV server`() = runTest {
        val api = api {
            respond(
                """{"status":"ok","name":"REV","version":"0.1.0","instanceId":"abc","uptimeSeconds":3}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        assertEquals(Result.Ok(ServerInfo("0.1.0", "abc")), api.health(server))
    }

    @Test
    fun `health distinguishes other services from unreachable hosts`() = runTest {
        val html = api { respond("<html>Navidrome</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        val notFound = api { respond("", HttpStatusCode.NotFound) }
        val down = api { throw IOException("connection refused") }

        assertEquals(Result.Err(ProbeError.NotRev), html.health(server))
        assertEquals(Result.Err(ProbeError.NotRev), notFound.health(server))
        assertEquals(Result.Err(ProbeError.Unreachable), down.health(server))
    }

    @Test
    fun `search results parse, tolerating states this app version doesn't know`() = runTest {
        val body = """{"query":"tool","artists":[{"name":"Tool","disambiguation":null,"libraryId":"ar-1","coverArtId":null,"catalogId":"mb-1"}],
            "albums":[{"key":"mb:x","title":"Opiate","artist":"Tool","year":1992,"albumType":"EP","availability":"MISSING","libraryId":null,"coverArtId":null,"catalogId":"x","trackCount":null},
                      {"key":"mb:y","title":"Future","artist":"Tool","year":null,"albumType":null,"availability":"SOMETHING_NEW","libraryId":null,"coverArtId":null,"catalogId":"y","trackCount":null}],
            "tracks":[],"sources":{"library":"ok","catalog":"unavailable"}}"""
        val api = LibraryApi(RevHttpClient(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) }, FakeSessionStore()) { server }) { server }

        val results = (api.search("tool") as Result.Ok).value
        assertEquals("ar-1", results.artists.single().libraryId)
        assertEquals(listOf(AlbumAvailability.MISSING, AlbumAvailability.MISSING), results.albums.map { it.availability })
        assertEquals(SourceStatus.Unavailable, results.catalog)
    }

    @Test
    fun `login maps server responses to typed errors`() = runTest {
        val error = """{"error":{"code":"X","message":"Something broke"}}"""
        suspend fun loginWith(status: HttpStatusCode) =
            api { respond(error, status, jsonHeaders) }.login(server, "alice", "pw", null)

        assertEquals(Result.Err(LoginError.InvalidCredentials), loginWith(HttpStatusCode.Unauthorized))
        assertEquals(Result.Err(LoginError.RateLimited), loginWith(HttpStatusCode.TooManyRequests))
        assertEquals(Result.Err(LoginError.Unexpected("Something broke")), loginWith(HttpStatusCode.InternalServerError))
        assertEquals(
            Result.Err(LoginError.Unreachable),
            api { throw IOException("timeout") }.login(server, "alice", "pw", null),
        )
    }
}
