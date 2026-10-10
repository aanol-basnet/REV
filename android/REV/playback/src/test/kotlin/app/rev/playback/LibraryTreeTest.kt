package app.rev.playback

import app.rev.domain.model.Album
import app.rev.domain.model.AlbumDetail
import app.rev.domain.model.AlbumListType
import app.rev.domain.model.Artist
import app.rev.domain.model.ArtistDetail
import app.rev.domain.model.Favorites
import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Genre
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.Page
import app.rev.domain.model.Playlist
import app.rev.domain.model.PlaylistDetail
import app.rev.domain.model.Result
import app.rev.domain.model.SearchResults
import app.rev.domain.model.SmartDownloadSettings
import app.rev.domain.model.StorageUsage
import app.rev.domain.model.Track
import app.rev.domain.repository.AutomaticCollection
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.repository.SearchRepository
import app.rev.domain.model.SourceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryTreeTest {
    private fun track(id: String, no: Int) = Track(
        id, "Track $id", "Lateralus", "al-1", "Tool", "ar-1", no, 1, 2001, null, "cov", 1000, false, null, null, null, null, null,
    )

    private val album = Album("al-1", "Lateralus", "Tool", "ar-1", 2001, null, "cov", 3, 3000, false, null, null)
    private val tracks = listOf(track("t1", 1), track("t2", 2), track("t3", 3))

    private val library = object : LibraryRepository {
        override fun observeAlbumList(type: AlbumListType): Flow<List<Album>> = flowOf(listOf(album))
        override suspend fun refreshAlbumList(type: AlbumListType) = Result.Ok(Unit)
        override fun observeAlbum(id: String): Flow<AlbumDetail?> = flowOf(AlbumDetail(album, tracks))
        override suspend fun refreshAlbum(id: String) = Result.Ok(Unit)
        override fun observeArtists(): Flow<List<Artist>> = flowOf(emptyList())
        override suspend fun refreshArtists() = Result.Ok(Unit)
        override fun observeArtist(id: String): Flow<ArtistDetail?> = flowOf(null)
        override suspend fun refreshArtist(id: String) = Result.Ok(Unit)
        override fun observePlaylists(): Flow<List<Playlist>> = flowOf(emptyList())
        override suspend fun refreshPlaylists() = Result.Ok(Unit)
        override fun observePlaylist(id: String): Flow<PlaylistDetail?> = flowOf(null)
        override suspend fun refreshPlaylist(id: String) = Result.Ok(Unit)
        override fun observeGenres(): Flow<List<Genre>> = flowOf(emptyList())
        override suspend fun refreshGenres() = Result.Ok(Unit)
        override fun observeFavorites(): Flow<Favorites> = flowOf(Favorites(emptyList(), emptyList(), listOf(tracks[1])))
        override suspend fun refreshFavorites() = Result.Ok(Unit)
        override suspend fun artistTopTracks(artistId: String): Result<List<Track>, LibraryError> = Result.Ok(emptyList())
        override suspend fun track(id: String): Result<Track, LibraryError> = Result.Ok(tracks.first { it.id == id })
        override suspend fun tracks(offset: Int, size: Int): Result<Page<Track>, LibraryError> = Result.Err(LibraryError.NotFound)
        override suspend fun albumsByGenre(genre: String, offset: Int, size: Int): Result<Page<Album>, LibraryError> =
            Result.Err(LibraryError.NotFound)
        override suspend fun setFavorite(kind: FavoriteKind, id: String, favorite: Boolean) = Result.Ok(Unit)
    }

    private val offline = object : OfflineRepository {
        override fun observeCollections(): Flow<List<OfflineCollection>> = flowOf(emptyList())
        override fun observeCollection(id: String): Flow<OfflineCollection?> = flowOf(null)
        override fun observeDownloadedTrackIds(): Flow<Set<String>> = flowOf(emptySet())
        override fun observeDownloadedTracks(): Flow<List<Track>> = flowOf(emptyList())
        override fun observeDownloadedTracks(artistId: String): Flow<List<Track>> = flowOf(emptyList())
        override fun observeStorage(): Flow<StorageUsage> = flowOf(StorageUsage(0, 0, 0))
        override val wifiOnly: Flow<Boolean> = flowOf(true)
        override suspend fun setWifiOnly(enabled: Boolean) = Unit
        override suspend fun downloadAlbum(album: Album, tracks: List<Track>) = Unit
        override suspend fun downloadPlaylist(playlist: Playlist, tracks: List<Track>) = Unit
        override suspend fun downloadTrack(track: Track) = Unit
        override suspend fun retry(collectionId: String) = Unit
        override suspend fun remove(collectionId: String) = Unit
        override suspend fun removeAll() = Unit
        override val smartDownloads: Flow<SmartDownloadSettings> = flowOf(SmartDownloadSettings())
        override suspend fun setSmartDownloads(settings: SmartDownloadSettings) = Unit
        override suspend fun syncAutomatic(wanted: List<AutomaticCollection>) = Unit
    }

    private val search = object : SearchRepository {
        override suspend fun search(query: String): Result<SearchResults, LibraryError> = Result.Err(LibraryError.Unreachable)
        override suspend fun searchOnDevice(query: String) =
            SearchResults(query, emptyList(), emptyList(), emptyList(), SourceStatus.Unavailable, SourceStatus.NotConfigured, onDevice = true)
    }

    private val tree = LibraryTree(library, offline, search, LibraryTree.Labels("Recent", "New", "Playlists", "Downloaded", "Favorites"))

    @Test
    fun `root lists the top-level sections`() = runTest {
        assertEquals(listOf("recent", "newest", "playlists", "downloaded", "favorites"), tree.children(LibraryTree.ROOT).map { it.mediaId })
    }

    @Test
    fun `albums are browsable with artwork the car can load`() = runTest {
        val item = tree.children(LibraryTree.RECENT).single()
        assertEquals("album:al-1", item.mediaId)
        assertTrue(item.mediaMetadata.isBrowsable == true && item.mediaMetadata.isPlayable == true)
        assertEquals("content://app.rev.artwork/cov", item.mediaMetadata.artworkUri.toString())
    }

    @Test
    fun `playing a track queues its whole album from that track`() = runTest {
        val track = tree.children("album:al-1")[1]
        val (queue, start) = tree.resolve(track.mediaId)
        assertEquals(listOf("t1", "t2", "t3"), queue.map { it.id })
        assertEquals(1, start)
        assertEquals(0, tree.resolve("album:al-1").second)
    }

    @Test
    fun `favorites play in context`() = runTest {
        val fav = tree.children(LibraryTree.FAVORITES).single()
        assertEquals(listOf("t2") to 0, tree.resolve(fav.mediaId).let { (q, s) -> q.map { it.id } to s })
    }
}
