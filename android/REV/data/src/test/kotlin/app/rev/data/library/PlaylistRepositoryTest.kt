package app.rev.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.rev.data.FakeSessionStore
import app.rev.data.local.RevDatabase
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.RevHttpClient
import app.rev.data.session
import app.rev.domain.model.Album
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.OfflineKind
import app.rev.domain.model.Playlist
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import app.rev.domain.model.SmartDownloadSettings
import app.rev.domain.model.StorageUsage
import app.rev.domain.model.Track
import app.rev.domain.repository.AutomaticCollection
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.OfflineRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistRepositoryTest {
    private val server = (ServerUrl.parse("100.64.0.1:4600") as Result.Ok).value
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RevDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("no handler") }
    private val requests = mutableListOf<HttpRequestData>()
    private val api = LibraryApi(
        RevHttpClient(MockEngine { requests += it; handler(it) }, FakeSessionStore(session())) { server },
    ) { server }
    private val library = LibraryRepositoryImpl(api, db.libraryDao())
    private val offline = FakeOffline()
    private val repo = PlaylistRepositoryImpl(api, db, library, offline)

    @After
    fun close() = db.close()

    private fun MockRequestHandleScope.ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, json)

    private fun HttpRequestData.text(): String = when (val b = body) {
        is TextContent -> b.text
        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
        else -> ""
    }

    private fun track(id: String) =
        """{"id":"$id","title":"Song $id","album":"Lateralus","albumId":"al-1","artist":"Tool","durationMs":1000,"starred":false}"""

    private fun detail(id: String, name: String, vararg songs: String, canEdit: Boolean = true) =
        """{"id":"$id","name":"$name","trackCount":${songs.size},"durationMs":${songs.size * 1000},"canEdit":$canEdit,
           "tracks":[${songs.joinToString(",") { track(it) }}]}"""

    @Test
    fun `creating a playlist caches it and puts it in the list`() = runTest {
        handler = { request ->
            when {
                request.method == HttpMethod.Post -> ok(detail("pl-1", "Gym", "a", "b"), HttpStatusCode.Created)
                else -> ok("""{"playlists":[{"id":"pl-1","name":"Gym","trackCount":2,"durationMs":2000,"canEdit":true}]}""")
            }
        }
        val created = (repo.create("Gym", listOf("a", "b")) as Result.Ok).value
        assertEquals("Gym", created.playlist.name)
        assertTrue(created.playlist.canEdit)
        assertTrue(requests.first().text().contains(""""trackIds":["a","b"]"""))

        assertEquals(listOf("Gym"), library.observePlaylists().first().map { it.name })
        assertEquals(listOf("a", "b"), library.observePlaylist("pl-1").first()?.tracks?.map { it.id })
    }

    @Test
    fun `adding, reordering and renaming update the cached playlist`() = runTest {
        handler = { ok("""{"playlist":${detail("pl-1", "Gym", "a", "b", "c")},"added":1,"skipped":1}""") }
        val added = (repo.add("pl-1", listOf("b", "c")) as Result.Ok).value
        assertEquals(1, added.added)
        assertEquals(1, added.skipped)
        assertEquals("/api/library/playlists/pl-1/tracks", requests.last().url.encodedPath)

        handler = { ok(detail("pl-1", "Gym", "c", "a")) }
        repo.setTracks("pl-1", listOf("c", "a"))
        assertEquals(HttpMethod.Put, requests.last().method)
        assertEquals(listOf("c", "a"), library.observePlaylist("pl-1").first()?.tracks?.map { it.id })

        handler = { ok(detail("pl-1", "Leg day", "c", "a")) }
        repo.update("pl-1", name = "Leg day")
        assertEquals(HttpMethod.Patch, requests.last().method)
        assertEquals("""{"name":"Leg day"}""", requests.last().text())
        assertEquals("Leg day", library.observePlaylist("pl-1").first()?.playlist?.name)
    }

    @Test
    fun `a downloaded playlist follows its edits, and goes when it's deleted`() = runTest {
        offline.collections += OfflineCollection.idFor(OfflineKind.Playlist, "pl-1")
        handler = { ok(detail("pl-1", "Gym", "a", "d")) }
        repo.setTracks("pl-1", listOf("a", "d"))
        assertEquals(listOf("a", "d"), offline.playlistDownloads.single().map { it.id })

        handler = { respond("", HttpStatusCode.NoContent) }
        assertEquals(Result.Ok(Unit), repo.delete("pl-1"))
        assertEquals(listOf("playlist:pl-1"), offline.removed)
        assertNull(library.observePlaylist("pl-1").first()?.tracks?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun `refusals from the server come back as errors and change nothing`() = runTest {
        handler = {
            ok("""{"error":{"code":"PLAYLIST_READ_ONLY","message":"belongs to alex"}}""", HttpStatusCode.Forbidden)
        }
        val result = repo.setTracks("pl-2", emptyList())
        assertEquals(Result.Err(LibraryError.Unexpected("belongs to alex")), result)
        assertFalse(offline.playlistDownloads.isNotEmpty())
    }

    private class FakeOffline : OfflineRepository {
        val collections = mutableSetOf<String>()
        val playlistDownloads = mutableListOf<List<Track>>()
        val removed = mutableListOf<String>()
        override fun observeCollection(id: String): Flow<OfflineCollection?> = flowOf(
            if (id in collections) OfflineCollection(id, OfflineKind.Playlist, id.substringAfter(':'), "t", null, null, 1, 1, 0, 1, 1, 1) else null,
        )
        override suspend fun downloadPlaylist(playlist: Playlist, tracks: List<Track>) { playlistDownloads += tracks }
        override suspend fun remove(collectionId: String) { removed += collectionId }
        override fun observeCollections(): Flow<List<OfflineCollection>> = flowOf(emptyList())
        override fun observeDownloadedTrackIds(): Flow<Set<String>> = flowOf(emptySet())
        override fun observeDownloadedTracks(): Flow<List<Track>> = flowOf(emptyList())
        override fun observeDownloadedTracks(artistId: String): Flow<List<Track>> = flowOf(emptyList())
        override fun observeStorage(): Flow<StorageUsage> = flowOf(StorageUsage(0, 0, 0))
        override val wifiOnly: Flow<Boolean> = flowOf(false)
        override suspend fun setWifiOnly(enabled: Boolean) = Unit
        override suspend fun downloadAlbum(album: Album, tracks: List<Track>) = Unit
        override suspend fun downloadTrack(track: Track) = Unit
        override suspend fun retry(collectionId: String) = Unit
        override suspend fun removeAll() = Unit
        override val smartDownloads: Flow<SmartDownloadSettings> = flowOf(SmartDownloadSettings())
        override suspend fun setSmartDownloads(settings: SmartDownloadSettings) = Unit
        override suspend fun syncAutomatic(wanted: List<AutomaticCollection>) = Unit
    }
}
