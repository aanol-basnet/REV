package app.rev.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.rev.data.FakeSessionStore
import app.rev.data.local.RevDatabase
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.RevHttpClient
import app.rev.data.session
import app.rev.domain.model.AlbumListType
import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import app.rev.domain.repository.LibraryError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.flow.first
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
class LibraryRepositoryImplTest {
    private val server = (ServerUrl.parse("100.64.0.1:4600") as Result.Ok).value
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RevDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** Responds per request; replace to change server behaviour mid-test. */
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        error("no handler")
    }
    private val requests = mutableListOf<HttpRequestData>()
    private val repo = LibraryRepositoryImpl(
        LibraryApi(
            RevHttpClient(MockEngine { requests += it; handler(it) }, FakeSessionStore(session())) { server },
        ) { server },
        db.libraryDao(),
        now = { 42L },
    )

    @After
    fun close() = db.close()

    private fun album(id: String, name: String = "Album $id", starred: Boolean = false) =
        """{"id":"$id","name":"$name","artist":"Tool","artistId":"ar-1","year":2001,"trackCount":2,
           "durationMs":1000,"starred":$starred,"coverArtId":"al-$id"}"""

    private fun track(id: String, no: Int, disc: Int = 1, starred: Boolean = false) =
        """{"id":"$id","title":"Track $id","album":"Lateralus","albumId":"al-1","artist":"Tool","trackNumber":$no,
           "discNumber":$disc,"durationMs":500,"starred":$starred}"""

    private fun albumDetail(vararg tracks: String) =
        album("al-1", "Lateralus").dropLast(1) + ""","tracks":[${tracks.joinToString(",")}]}"""

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, json)

    @Test
    fun `caches album details with tracks in disc and track order`() = runTest {
        handler = { ok(albumDetail(track("t2", 1, disc = 2), track("t1", 2), track("t0", 1))) }

        assertEquals(Result.Ok(Unit), repo.refreshAlbum("al-1"))

        val detail = repo.observeAlbum("al-1").first()!!
        assertEquals("Lateralus", detail.album.name)
        assertEquals(listOf("t0", "t1", "t2"), detail.tracks.map { it.id })
        assertTrue(detail.isMultiDisc)
    }

    @Test
    fun `drops tracks the server no longer lists`() = runTest {
        handler = { ok(albumDetail(track("t1", 1), track("t2", 2))) }
        repo.refreshAlbum("al-1")
        handler = { ok(albumDetail(track("t1", 1))) }
        repo.refreshAlbum("al-1")

        assertEquals(listOf("t1"), repo.observeAlbum("al-1").first()!!.tracks.map { it.id })
    }

    @Test
    fun `cached data stays available when the server is unreachable`() = runTest {
        handler = { ok(albumDetail(track("t1", 1))) }
        repo.refreshAlbum("al-1")

        handler = { throw IOException("offline") }
        assertEquals(Result.Err(LibraryError.Unreachable), repo.refreshAlbum("al-1"))
        assertEquals(1, repo.observeAlbum("al-1").first()!!.tracks.size)
        assertEquals("Track t1", (repo.track("t1") as Result.Ok).value.title)
    }

    @Test
    fun `pages through the whole library for the A-Z album list`() = runTest {
        handler = { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            val size = request.url.parameters["size"]!!.toInt()
            val ids = (offset until minOf(offset + size, 1_200)).map { album("a$it") }
            ok("""{"albums":[${ids.joinToString(",")}],"offset":$offset,"hasMore":${offset + size < 1_200}}""")
        }

        repo.refreshAlbumList(AlbumListType.Alphabetical)

        val albums = repo.observeAlbumList(AlbumListType.Alphabetical).first()
        assertEquals(1_200, albums.size)
        assertEquals("a0", albums.first().id)
        assertEquals(3, requests.size)
    }

    @Test
    fun `refreshing a list keeps albums usable offline`() = runTest {
        handler = { ok(albumDetail(track("t1", 1))) }
        repo.refreshAlbum("al-1")
        handler = { ok("""{"albums":[${album("al-1", "Lateralus")}],"offset":0,"hasMore":false}""") }
        repo.refreshAlbumList(AlbumListType.Newest)

        assertEquals(listOf("Lateralus"), repo.observeAlbumList(AlbumListType.Newest).first().map { it.name })
        assertEquals(1, repo.observeAlbum("al-1").first()!!.tracks.size)
    }

    @Test
    fun `favorites refresh mirrors the server exactly`() = runTest {
        handler = { ok(albumDetail(track("t1", 1, starred = true), track("t2", 2, starred = true))) }
        repo.refreshAlbum("al-1")

        handler = { ok("""{"artists":[],"albums":[${album("al-9", starred = true)}],"tracks":[${track("t2", 2, starred = true)}]}""") }
        repo.refreshFavorites()

        val favorites = repo.observeFavorites().first()
        assertEquals(listOf("t2"), favorites.tracks.map { it.id })
        assertEquals(listOf("al-9"), favorites.albums.map { it.id })
    }

    @Test
    fun `favoriting is optimistic and reverts when the server refuses`() = runTest {
        handler = { ok(albumDetail(track("t1", 1))) }
        repo.refreshAlbum("al-1")

        handler = { respond("", HttpStatusCode.NoContent) }
        assertEquals(Result.Ok(Unit), repo.setFavorite(FavoriteKind.Album, "al-1", true))
        assertTrue(repo.observeAlbum("al-1").first()!!.album.starred)
        assertEquals(HttpMethod.Put, requests.last().method)
        assertEquals("/api/library/favorites/albums/al-1", requests.last().url.encodedPath)

        handler = { respond("""{"error":{"code":"NAVIDROME_UNAVAILABLE","message":"Music server unavailable"}}""", HttpStatusCode.ServiceUnavailable, json) }
        val result = repo.setFavorite(FavoriteKind.Album, "al-1", false)
        assertEquals(Result.Err(LibraryError.MusicServerUnavailable("Music server unavailable")), result)
        assertTrue("reverted", repo.observeAlbum("al-1").first()!!.album.starred)
    }

    @Test
    fun `artist page lists that artist's albums`() = runTest {
        handler = {
            ok("""{"id":"ar-1","name":"Tool","albumCount":2,"starred":false,"albums":[${album("a1")},${album("a2")}]}""")
        }
        repo.refreshArtist("ar-1")
        val detail = repo.observeArtist("ar-1").first()!!
        assertEquals("Tool", detail.artist.name)
        assertEquals(listOf("a1", "a2"), detail.albums.map { it.id })
        assertNull(repo.observeArtist("missing").first())
        assertFalse(detail.artist.starred)
    }
}
