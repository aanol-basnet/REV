package app.rev.data.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.rev.data.FakeSessionStore
import app.rev.data.local.RevDatabase
import app.rev.data.local.offline.OfflineTrackEntity
import app.rev.data.remote.RevHttpClient
import app.rev.data.session
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import app.rev.domain.model.Track
import app.rev.domain.playback.QueueSnapshot
import app.rev.domain.playback.RepeatMode
import app.rev.domain.playback.TrackSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackDataTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, RevDatabase::class.java).allowMainThreadQueries().build()
    private var server: ServerUrl? = (ServerUrl.parse("100.64.0.1:4600") as Result.Ok).value

    @After
    fun close() = db.close()

    private fun offline(trackId: String, path: String) = OfflineTrackEntity(
        trackId = trackId, localUri = path, title = "t", artist = "a", album = "b",
        durationMs = 1, fileSize = 1, downloadedAt = 1,
    )

    @Test
    fun `prefers a downloaded file, then the server stream, else nothing`() = runTest {
        val file = File(context.filesDir, "tr-1.flac").apply { writeText("audio") }
        db.offlineTrackDao().upsert(offline("tr-1", file.path))
        db.offlineTrackDao().upsert(offline("tr-2", File(context.filesDir, "missing.flac").path))
        val resolver = TrackSourceResolverImpl(db.offlineTrackDao()) { server }

        assertEquals(TrackSource.Local(file.path), resolver.resolve("tr-1"))
        assertEquals(
            "a download whose file vanished falls back to streaming",
            TrackSource.Remote("http://100.64.0.1:4600/api/library/tracks/tr-2/stream"),
            resolver.resolve("tr-2"),
        )

        server = null
        assertEquals(TrackSource.Local(file.path), resolver.resolve("tr-1"))
        assertNull(resolver.resolve("tr-3"))
    }

    @Test
    fun `each track load uses the address active at that moment`() = runTest {
        var active: ServerUrl? = (ServerUrl.parse("https://rev.example.com") as Result.Ok).value
        val resolver = TrackSourceResolverImpl(db.offlineTrackDao()) { active }
        assertEquals(TrackSource.Remote("https://rev.example.com/api/library/tracks/tr-9/stream"), resolver.resolve("tr-9"))

        // Got home: the stream already playing keeps its connection; the next load goes direct.
        active = (ServerUrl.parse("http://192.168.1.69:4600") as Result.Ok).value
        assertEquals(TrackSource.Remote("http://192.168.1.69:4600/api/library/tracks/tr-9/stream"), resolver.resolve("tr-9"))
    }

    @Test
    fun `queue snapshots round-trip`() = runTest {
        val store = QueueStoreImpl(db.playbackQueueDao())
        val track = Track(
            "tr-1", "The Grudge", "Lateralus", "al-1", "Tool", "ar-1", 1, 1, 2001, "Metal", "al-1_0",
            516_000, true, "flac", 1024, 44_100, 16, 66_000_000,
        )
        val snapshot = QueueSnapshot(listOf(track, track.copy(id = "tr-2")), 1, 42_000, true, RepeatMode.One)

        store.save(snapshot)
        assertEquals(snapshot, store.load())

        store.save(snapshot.copy(tracks = emptyList()))
        assertNull(store.load())
    }

    @Test
    fun `media requests carry the token only to the configured server and refresh once on 401`() {
        val rev = MockWebServer().apply { start() }
        val other = MockWebServer().apply { start() }
        try {
            val revUrl = (ServerUrl.parse(rev.url("/").toString()) as Result.Ok).value
            val sessions = FakeSessionStore(session(access = "rev_at_old", refresh = "rev_rt_old"))
            val refreshEngine = MockEngine {
                respond(
                    """{"accessToken":"rev_at_new","accessTokenExpiresAt":1,"refreshToken":"rev_rt_new",
                       "refreshTokenExpiresAt":2,"user":{"id":1,"username":"alice"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val client = authenticatedMediaHttpClient(RevHttpClient(refreshEngine, sessions) { revUrl })

            rev.enqueue(MockResponse.Builder().code(401).build())
            rev.enqueue(MockResponse.Builder().code(200).body("audio").build())
            other.enqueue(MockResponse.Builder().code(401).build())

            val ok = client.newCall(Request.Builder().url(rev.url("/api/library/tracks/tr-1/stream")).build()).execute()
            assertEquals(200, ok.code)
            assertEquals("Bearer rev_at_old", rev.takeRequest().headers["Authorization"])
            assertEquals("Bearer rev_at_new", rev.takeRequest().headers["Authorization"])
            assertEquals("rev_at_new", runBlocking { sessions.current() }?.accessToken)

            val foreign = client.newCall(Request.Builder().url(other.url("/stream")).build()).execute()
            assertEquals(401, foreign.code)
            assertNull(other.takeRequest().headers["Authorization"])
            assertEquals(1, other.requestCount)
        } finally {
            rev.close()
            other.close()
        }
    }
}
