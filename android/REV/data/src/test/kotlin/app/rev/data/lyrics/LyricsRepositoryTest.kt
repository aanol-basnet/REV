package app.rev.data.lyrics

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.rev.data.FakeSessionStore
import app.rev.data.local.RevDatabase
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.RevHttpClient
import app.rev.domain.model.LyricLine
import app.rev.domain.model.Lyrics
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.LibraryError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RevDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val server = (ServerUrl.parse("100.64.0.1:4600") as Result.Ok).value
    private var online = true
    private var body = ""
    private var calls = 0
    private var now = 1_000_000L
    private val repo = LyricsRepositoryImpl(
        LibraryApi(
            RevHttpClient(
                MockEngine {
                    calls++
                    if (!online) throw IOException("offline")
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                FakeSessionStore(),
            ) { server },
        ) { server },
        db.lyricsDao(),
    ) { now }

    @After
    fun close() = db.close()

    private fun response(status: String, synced: String? = null, plain: String? = null) =
        """{"trackId":"tr-1","status":"$status","source":null,"synced":${synced ?: "null"},
           "plain":${plain?.let { "\"$it\"" } ?: "null"},"fetchedAt":1}"""

    @Test
    fun `fetches once, then serves from the device even offline`() = runTest {
        body = response("found", synced = """[{"startMs":1000,"text":"One"},{"startMs":2000,"text":""}]""", plain = "One")
        val expected = Lyrics.Synced(listOf(LyricLine(1000, "One"), LyricLine(2000, "")))
        assertEquals(Result.Ok(expected), repo.lyrics("tr-1"))

        online = false
        assertEquals(Result.Ok(expected), repo.lyrics("tr-1"))
        assertEquals(1, calls)
    }

    @Test
    fun `maps plain, instrumental and none`() = runTest {
        body = response("found", plain = "Words")
        assertEquals(Result.Ok(Lyrics.Plain("Words")), repo.lyrics("tr-1"))
        db.clearAllTables()
        body = response("instrumental")
        assertEquals(Result.Ok(Lyrics.Instrumental), repo.lyrics("tr-1"))
        db.clearAllTables()
        body = response("not_found")
        assertEquals(Result.Ok(Lyrics.None), repo.lyrics("tr-1"))
    }

    @Test
    fun `re-asks about missing lyrics after a week, keeping the old answer when offline`() = runTest {
        body = response("not_found")
        repo.lyrics("tr-1")
        now += 8L * 24 * 3_600_000
        online = false
        assertEquals(Result.Ok(Lyrics.None), repo.lyrics("tr-1"))
        assertEquals(2, calls)

        online = true
        body = response("found", plain = "Added later")
        assertEquals(Result.Ok(Lyrics.Plain("Added later")), repo.lyrics("tr-1"))
    }

    @Test
    fun `reports failure when nothing is cached and the server is unreachable`() = runTest {
        online = false
        assertEquals(Result.Err(LibraryError.Unreachable), repo.lyrics("tr-1"))
    }
}
