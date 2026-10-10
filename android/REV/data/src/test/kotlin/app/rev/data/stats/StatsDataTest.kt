package app.rev.data.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.rev.data.FakeSessionStore
import app.rev.data.local.RevDatabase
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.RevHttpClient
import app.rev.data.session
import app.rev.domain.model.ReportPeriodKind
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import app.rev.domain.model.TimelineUnit
import app.rev.domain.repository.LibraryError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatsDataTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val server = (ServerUrl.parse("100.64.0.1:4600") as Result.Ok).value
    private val db = Room.inMemoryDatabaseBuilder(context, RevDatabase::class.java).allowMainThreadQueries().build()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("no handler") }
    private val requests = mutableListOf<HttpRequestData>()
    private val api = LibraryApi(
        RevHttpClient(MockEngine { requests += it; handler(it) }, FakeSessionStore(session())) { server },
    ) { server }

    @After
    fun close() = db.close()

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, json)

    private fun HttpRequestData.text(): String = when (val b = body) {
        is TextContent -> b.text
        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
        else -> error("unexpected body $b")
    }

    @Test
    fun `keeps plays and skips while the server is unreachable and sends them once it's back`() = runTest {
        val queue = ListenQueue(db, api)
        handler = { throw IOException("offline") }
        queue.add("tr-1", 1_000, "play", null)
        queue.add("tr-2", 2_000, "skip", 4_000)
        queue.add("tr-2", 2_000, "skip", 4_000) // the same listen twice is kept once
        assertEquals(2, db.pendingListenDao().count())

        handler = { ok("""{"recorded":2,"duplicates":0,"unknown":0}""") }
        requests.clear()
        queue.flush()

        assertEquals(0, db.pendingListenDao().count())
        assertEquals(1, requests.size)
        assertEquals("/api/stats/listens", requests.single().url.encodedPath)
        val body = requests.single().text()
        assertTrue(body, body.contains(""""trackId":"tr-1","startedAt":1000,"kind":"play""""))
        assertTrue(body, body.contains(""""trackId":"tr-2","startedAt":2000,"kind":"skip","listenedMs":4000"""))
        assertTrue("a play leaves the length to the server", !body.contains(""""listenedMs":null"""))
    }

    @Test
    fun `keeps listens while the music server is down, drops ones the server rejects`() = runTest {
        val queue = ListenQueue(db, api)
        handler = { respond("""{"error":{"code":"NAVIDROME_UNAVAILABLE","message":"down"}}""", HttpStatusCode.BadGateway, json) }
        queue.add("tr-1", 1_000, "play", null)
        assertEquals(1, db.pendingListenDao().count())

        handler = { respond("""{"error":{"code":"VALIDATION_ERROR","message":"bad"}}""", HttpStatusCode.BadRequest, json) }
        queue.flush()
        assertEquals(0, db.pendingListenDao().count())
    }

    private val report = """
        {"period":{"kind":"month","offset":0,"timeZone":"Europe/Berlin","from":1,"to":2,"elapsedDays":9,"hasEarlier":true},
         "totals":{"plays":12,"skips":3,"listeningMs":3600000,"tracks":8,"artists":4,"albums":5,"genres":3,"activeDays":6,
                   "dailyAverageMs":400000,"skipRate":0.2},
         "previous":null,
         "persona":{"kind":"night-owl","title":"Night owl","detail":"40% after 10 pm."},
         "highlights":[{"kind":"top-genre","title":"Metal was your genre of the month","detail":"7 plays"}],
         "topGenres":[{"name":"Metal","plays":7,"listeningMs":1,"share":0.6,"previousRank":2}],
         "topArtists":[{"id":"ar-1","name":"Tool","coverArtId":"c1","isNew":false,"plays":7,"listeningMs":1,"share":0.6,"previousRank":null}],
         "topAlbums":[{"id":null,"name":"Lateralus","artist":"Tool","artistId":"ar-1","coverArtId":"c1","year":2001,"plays":7,"listeningMs":1,"share":0.6}],
         "topTracks":[{"track":{"id":"t1","title":"Schism","artist":"Tool","artistId":"ar-1","album":"Lateralus","albumId":"al-1","coverArtId":"c1","durationMs":400000},
                       "plays":5,"listeningMs":1,"share":0.4,"previousRank":1}],
         "onRepeat":[{"track":{"id":"t1","title":"Schism","artist":"Tool","album":"Lateralus","durationMs":400000},"plays":5,"peakDay":10,"peakDayPlays":4,"days":2}],
         "mostSkipped":[],
         "discoveries":{"artists":[],"artistCount":0,"newTrackShare":0.25,"newTracks":2},
         "rhythm":{"byHour":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,12],"byWeekday":[12,0,0,0,0,0,0],
                   "heatmap":[[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,12],[],[],[],[],[],[]],"peakHour":23,"peakWeekday":0},
         "timeline":{"unit":"day","points":[{"start":1,"plays":12,"listeningMs":3600000,"topGenre":"Metal","topArtist":"Tool"}]},
         "streaks":{"longestDays":3,"longestStart":1,"currentDays":1},
         "sessions":{"count":2,"averageMs":1800000,"longestMs":2400000,"longestStart":1,"longestPlays":8},
         "variety":{"score":40,"topArtistShare":0.6,"singlePlayShare":0.3},
         "trackingSince":1,
         "somethingNew":{"ignored":true}}
    """.trimIndent()

    @Test
    fun `reads reports and keeps the current one for offline`() = runTest {
        val dir = File(context.filesDir, "stats-test").apply { deleteRecursively() }
        val repo = StatsRepositoryImpl(api, dir)
        handler = { ok(report) }

        val result = repo.report(ReportPeriodKind.Month, 0, "Europe/Berlin")
        val r = (result as Result.Ok).value
        val query = requests.single().url.parameters
        assertEquals(listOf("month", "0", "Europe/Berlin"), listOf(query["period"], query["offset"], query["tz"]))
        assertEquals(ReportPeriodKind.Month, r.period.kind)
        assertEquals("Night owl", r.persona?.title)
        assertEquals(2, r.topGenres.single().ranking.previousRank)
        assertNull(r.topAlbums.single().id)
        assertEquals("Schism", r.onRepeat.single().track.title)
        assertEquals(TimelineUnit.Day, r.timeline.unit)
        assertEquals(23, r.rhythm.peakHour)

        handler = { throw IOException("offline") }
        assertEquals(Result.Err(LibraryError.Unreachable), repo.report(ReportPeriodKind.Month, 0, "Europe/Berlin"))
        assertNotNull(repo.cachedReport(ReportPeriodKind.Month))
        assertNull(repo.cachedReport(ReportPeriodKind.Week))

        // Older periods aren't kept.
        handler = { ok(report.replace("\"offset\":0", "\"offset\":3")) }
        repo.report(ReportPeriodKind.Week, 3, "Europe/Berlin")
        assertNull(repo.cachedReport(ReportPeriodKind.Week))
    }
}
