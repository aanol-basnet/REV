package app.rev.feature.library

import androidx.lifecycle.SavedStateHandle
import app.rev.domain.model.Album
import app.rev.domain.model.ReleaseKind
import app.rev.domain.model.AlbumDetail
import app.rev.domain.model.AlbumListType
import app.rev.domain.model.Artist
import app.rev.domain.model.ArtistDetail
import app.rev.domain.model.Favorites
import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Genre
import app.rev.domain.model.Page
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.Playlist
import app.rev.domain.model.PlaylistDetail
import app.rev.domain.model.StorageUsage
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.repository.SearchRepository
import app.rev.domain.model.SearchResults
import app.rev.domain.model.SourceStatus
import app.rev.domain.repository.AutomaticCollection
import app.rev.domain.model.SmartDownloadSettings
import kotlinx.coroutines.flow.flowOf
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.AddedToPlaylist
import app.rev.domain.repository.PlaylistRepository
import app.rev.domain.repository.RefreshResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun aTrack(id: String, starred: Boolean = false) = Track(
        id, "Track $id", "Lateralus", "al-1", "Tool", "ar-1", 1, 1, 2001, null, null, 1000, starred,
        null, null, null, null, null,
    )

    private val album = Album("al-1", "Lateralus", "Tool", "ar-1", 2001, null, null, 1, 1000, false, null, null)

    private class FakeQueue : PlaybackQueue {
        val played = mutableListOf<Triple<List<String>, Int, Boolean>>()
        val queued = mutableListOf<List<String>>()
        override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
            played += Triple(tracks.map { it.id }, startIndex, shuffle)
        }
        override fun playNext(tracks: List<Track>) { queued += tracks.map { it.id } }
        override fun addToQueue(tracks: List<Track>) { queued += tracks.map { it.id } }
    }

    private val queue = FakeQueue()

    private object NoSearch : SearchRepository {
        private fun empty(q: String) = SearchResults(q, emptyList(), emptyList(), emptyList(), SourceStatus.Ok, SourceStatus.NotConfigured)
        override suspend fun search(query: String): Result<SearchResults, LibraryError> = Result.Ok(empty(query))
        override suspend fun searchOnDevice(query: String) = empty(query)
    }

    private class FakeOffline : OfflineRepository {
        val albums = mutableListOf<String>()
        override fun observeCollections(): Flow<List<OfflineCollection>> = flowOf(emptyList())
        override fun observeCollection(id: String): Flow<OfflineCollection?> = flowOf(null)
        val downloadedIds = MutableStateFlow<Set<String>>(emptySet())
        val downloadedByArtist = MutableStateFlow<List<Track>>(emptyList())
        override fun observeDownloadedTrackIds(): Flow<Set<String>> = downloadedIds
        override fun observeDownloadedTracks(): Flow<List<Track>> = flowOf(emptyList())
        override fun observeDownloadedTracks(artistId: String): Flow<List<Track>> = downloadedByArtist
        override fun observeStorage(): Flow<StorageUsage> = flowOf(StorageUsage(0, 0, 0))
        override val wifiOnly: Flow<Boolean> = flowOf(true)
        override suspend fun setWifiOnly(enabled: Boolean) = Unit
        override suspend fun downloadAlbum(album: Album, tracks: List<Track>) {
            albums += album.id
        }
        override suspend fun downloadPlaylist(playlist: Playlist, tracks: List<Track>) = Unit
        override suspend fun downloadTrack(track: Track) = Unit
        override suspend fun retry(collectionId: String) = Unit
        override suspend fun remove(collectionId: String) = Unit
        override suspend fun removeAll() = Unit
        override val smartDownloads: Flow<SmartDownloadSettings> = flowOf(SmartDownloadSettings())
        override suspend fun setSmartDownloads(settings: SmartDownloadSettings) = Unit
        override suspend fun syncAutomatic(wanted: List<AutomaticCollection>) = Unit
    }

    private val offline = FakeOffline()

    private class FakeLibrary : LibraryRepository {
        val albumDetail = MutableStateFlow<AlbumDetail?>(null)
        var albumRefresh: RefreshResult = Result.Ok(Unit)
        var favoriteResult: Result<Unit, LibraryError> = Result.Ok(Unit)
        val favoriteCalls = mutableListOf<Triple<FavoriteKind, String, Boolean>>()
        var trackPages: (Int, Int) -> Result<Page<Track>, LibraryError> = { o, _ -> Result.Ok(Page(emptyList(), o, false)) }

        override fun observeAlbum(id: String): Flow<AlbumDetail?> = albumDetail
        override suspend fun refreshAlbum(id: String) = albumRefresh
        override suspend fun tracks(offset: Int, size: Int) = trackPages(offset, size)
        override suspend fun setFavorite(kind: FavoriteKind, id: String, favorite: Boolean): Result<Unit, LibraryError> {
            favoriteCalls += Triple(kind, id, favorite)
            return favoriteResult
        }

        override fun observeArtists(): Flow<List<Artist>> = flowOf(emptyList())
        override suspend fun refreshArtists() = Result.Ok(Unit)
        val artistDetail = MutableStateFlow<ArtistDetail?>(null)
        override fun observeArtist(id: String): Flow<ArtistDetail?> = artistDetail
        override suspend fun refreshArtist(id: String) = Result.Ok(Unit)
        override fun observeAlbumList(type: AlbumListType): Flow<List<Album>> = flowOf(emptyList())
        override suspend fun refreshAlbumList(type: AlbumListType) = Result.Ok(Unit)
        override fun observeGenres(): Flow<List<Genre>> = flowOf(emptyList())
        override suspend fun refreshGenres() = Result.Ok(Unit)
        val favorites = MutableStateFlow(Favorites(emptyList(), emptyList(), emptyList()))
        override fun observeFavorites(): Flow<Favorites> = favorites
        override suspend fun refreshFavorites() = Result.Ok(Unit)
        var topTracks: Result<List<Track>, LibraryError> = Result.Ok(emptyList())
        override suspend fun artistTopTracks(artistId: String): Result<List<Track>, LibraryError> = topTracks
        override suspend fun track(id: String): Result<Track, LibraryError> = Result.Err(LibraryError.NotFound)
        override suspend fun albumsByGenre(genre: String, offset: Int, size: Int): Result<Page<Album>, LibraryError> =
            Result.Ok(Page(emptyList(), offset, false))
        override fun observePlaylists(): Flow<List<Playlist>> = flowOf(emptyList())
        override suspend fun refreshPlaylists() = Result.Ok(Unit)
        val playlistDetail = MutableStateFlow<PlaylistDetail?>(null)
        override fun observePlaylist(id: String): Flow<PlaylistDetail?> = playlistDetail
        override suspend fun refreshPlaylist(id: String) = Result.Ok(Unit)
    }

    private class FakePlaylists : PlaylistRepository {
        val calls = mutableListOf<String>()
        var fail: LibraryError? = null
        private fun <T> answer(value: T): Result<T, LibraryError> = fail?.let { Result.Err(it) } ?: Result.Ok(value)
        private val empty = PlaylistDetail(Playlist("pl-1", "x", null, null, 0, 0, null, null, canEdit = true), emptyList())
        override suspend fun create(name: String, trackIds: List<String>) = answer(empty).also { calls += "create $name $trackIds" }
        override suspend fun update(id: String, name: String?, comment: String?) =
            answer(empty).also { calls += "update $name $comment" }
        override suspend fun add(id: String, trackIds: List<String>) = answer(AddedToPlaylist(empty, trackIds.size, 0))
        override suspend fun setTracks(id: String, trackIds: List<String>) = answer(empty).also { calls += "tracks $trackIds" }
        override suspend fun delete(id: String) = answer(Unit).also { calls += "delete" }
        override suspend fun duplicate(id: String) = answer(empty).also { calls += "duplicate" }
    }

    private fun editor(library: FakeLibrary, playlists: FakePlaylists): PlaylistEditViewModel {
        library.playlistDetail.value = PlaylistDetail(
            Playlist("pl-1", "Gym", "loud", "rev", 3, 3000, null, null, canEdit = true),
            listOf(aTrack("a"), aTrack("b"), aTrack("a")),
        )
        return PlaylistEditViewModel(SavedStateHandle(mapOf("id" to "pl-1")), library, playlists)
    }

    @Test
    fun `the playlist editor saves name, description and song order together`() = runTest {
        val playlists = FakePlaylists()
        val vm = editor(FakeLibrary(), playlists)
        val events = mutableListOf<PlaylistEditEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()
        assertFalse(vm.state.value.hasChanges)

        vm.setName("  Leg day ")
        vm.move(0, 2)
        // Copies of the same song are told apart: only one of them goes.
        vm.remove(vm.state.value.songs.last { it.track.id == "a" }.key)
        assertTrue(vm.state.value.canSave)
        vm.save()
        runCurrent()

        assertEquals(listOf("update Leg day null", "tracks [b, a]"), playlists.calls)
        assertEquals(listOf(PlaylistEditEvent.Saved), events)
    }

    @Test
    fun `the playlist editor keeps your changes when saving fails, and can't save a blank name`() = runTest {
        val playlists = FakePlaylists().apply { fail = LibraryError.Unreachable }
        val vm = editor(FakeLibrary(), playlists)
        val errors = mutableListOf<LibraryError>()
        backgroundScope.launch { vm.errors.collect { errors += it } }
        runCurrent()

        vm.move(2, 0)
        vm.save()
        runCurrent()
        assertEquals(listOf(LibraryError.Unreachable), errors)
        assertTrue(vm.state.value.songsChanged)
        assertFalse(vm.state.value.busy)

        vm.setName(" ")
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `deleting a playlist from the editor`() = runTest {
        val playlists = FakePlaylists()
        val vm = editor(FakeLibrary(), playlists)
        val events = mutableListOf<PlaylistEditEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()
        vm.delete()
        runCurrent()
        assertEquals(listOf("delete"), playlists.calls)
        assertEquals(listOf(PlaylistEditEvent.Deleted), events)
    }

    @Test
    fun `album screen shows cached detail and keeps it when refresh fails`() = runTest {
        val library = FakeLibrary().apply {
            albumDetail.value = AlbumDetail(album, listOf(aTrack("t1")))
            albumRefresh = Result.Err(LibraryError.Unreachable)
        }
        val vm = AlbumViewModel(SavedStateHandle(mapOf("id" to "al-1")), library, queue, offline)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        val state = vm.state.value
        assertEquals("Lateralus", state.content?.album?.name)
        assertEquals(LibraryError.Unreachable, state.error)
    }

    @Test
    fun `failed favorite toggles are reported`() = runTest {
        val library = FakeLibrary().apply { favoriteResult = Result.Err(LibraryError.Unreachable) }
        val vm = AlbumViewModel(SavedStateHandle(mapOf("id" to "al-1")), library, queue, offline)
        val errors = mutableListOf<LibraryError>()
        backgroundScope.launch { vm.errors.collect { errors += it } }
        runCurrent()

        vm.setFavorite(true)
        runCurrent()

        assertEquals(listOf(Triple(FavoriteKind.Album, "al-1", true)), library.favoriteCalls)
        assertEquals(listOf<LibraryError>(LibraryError.Unreachable), errors)
    }

    @Test
    fun `album playback uses the whole album from the tapped track`() = runTest {
        val library = FakeLibrary().apply {
            albumDetail.value = AlbumDetail(album, listOf(aTrack("t1"), aTrack("t2"), aTrack("t3")))
        }
        val vm = AlbumViewModel(SavedStateHandle(mapOf("id" to "al-1")), library, queue, offline)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        vm.play(1)
        vm.shuffle()
        vm.queueAlbum()

        assertEquals(
            listOf(Triple(listOf("t1", "t2", "t3"), 1, false), Triple(listOf("t1", "t2", "t3"), 0, true)),
            queue.played,
        )
        assertEquals(listOf(listOf("t1", "t2", "t3")), queue.queued)

        vm.downloadAlbum()
        runCurrent()
        assertEquals(listOf("al-1"), offline.albums)
    }

    @Test
    fun `starring a track in the paged list updates that row`() = runTest {
        val library = FakeLibrary().apply {
            trackPages = { o, _ -> Result.Ok(Page(listOf(aTrack("t1"), aTrack("t2")), o, false)) }
        }
        val vm = TracksViewModel(library, queue, offline, NoSearch)
        vm.setTrackFavorite(vm.state.first().items.first(), true)

        val items = vm.state.value.items
        assertTrue(items.first { it.id == "t1" }.starred)
        assertEquals(false, items.first { it.id == "t2" }.starred)
    }

    private fun artistVm(library: FakeLibrary) = ArtistViewModel(SavedStateHandle(mapOf("id" to "ar-1")), library, queue, offline)

    @Test
    fun `artist page shows the top five and counts your liked or downloaded songs`() = runTest {
        val library = FakeLibrary().apply {
            topTracks = Result.Ok((1..8).map { aTrack("t$it") })
            favorites.value = Favorites(emptyList(), emptyList(), listOf(aTrack("t7"), aTrack("t2")))
        }
        offline.downloadedIds.value = setOf("t2", "d9")
        offline.downloadedByArtist.value = listOf(aTrack("t2"), aTrack("d9"))
        val vm = artistVm(library)
        backgroundScope.launch { vm.songs.collect {} }
        runCurrent()

        val songs = vm.songs.value
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), songs.tracks.map { it.id })
        assertEquals(listOf("t2"), songs.tracks.filter { it.starred }.map { it.id })
        // t7 and t2 liked, d9 downloaded (t2 counted once).
        assertEquals(3, songs.yourSongsCount)

        vm.play(2)
        assertEquals(Triple(listOf("t1", "t2", "t3", "t4", "t5"), 2, false), queue.played.last())
    }

    @Test
    fun `offline, the way to your downloaded songs stays`() = runTest {
        val library = FakeLibrary().apply { topTracks = Result.Err(LibraryError.Unreachable) }
        offline.downloadedByArtist.value = listOf(aTrack("d1"))
        val vm = artistVm(library)
        backgroundScope.launch { vm.songs.collect {} }
        runCurrent()

        assertEquals(emptyList<Track>(), vm.songs.value.tracks)
        assertEquals(1, vm.songs.value.yourSongsCount)
        assertEquals(true, vm.songs.value.failed)
    }

    @Test
    fun `your songs screen lists liked songs then downloads, and plays them`() = runTest {
        val library = FakeLibrary().apply {
            favorites.value = Favorites(
                emptyList(),
                emptyList(),
                listOf(aTrack("l1"), aTrack("other").copy(artistId = "ar-2"), aTrack("l2")),
            )
        }
        offline.downloadedIds.value = setOf("l2", "d1")
        offline.downloadedByArtist.value = listOf(aTrack("d1"), aTrack("l2"))
        val vm = ArtistSongsViewModel(SavedStateHandle(mapOf("id" to "ar-1", "name" to "Tool")), library, queue, offline)
        backgroundScope.launch { vm.songs.collect {} }
        runCurrent()

        assertEquals("Tool", vm.artistName)
        assertEquals(listOf("l1", "l2", "d1"), vm.songs.value?.map { it.id })
        assertEquals(listOf(true, true, false), vm.songs.value?.map { it.starred })

        vm.shuffle()
        assertEquals(Triple(listOf("l1", "l2", "d1"), 0, true), queue.played.last())
    }

    @Test
    fun `releases are grouped by kind, newest first`() = runTest {
        fun release(id: String, year: Int, vararg types: String) = album.copy(id = id, year = year, releaseTypes = types.toList())
        val library = FakeLibrary().apply {
            artistDetail.value = ArtistDetail(
                Artist("ar-1", "Tool", 5, null, false),
                listOf(
                    release("ep1", 1992, "ep"),
                    release("al1", 1996, "album"),
                    release("al2", 2001),
                    release("best", 2010, "album", "compilation"),
                    release("s1", 2019, "single"),
                ),
            )
        }
        val vm = artistVm(library)
        backgroundScope.launch { vm.releases.collect {} }
        runCurrent()

        assertEquals(
            listOf(
                ReleaseKind.Album to listOf("al2", "al1"),
                ReleaseKind.Ep to listOf("ep1"),
                ReleaseKind.Single to listOf("s1"),
                ReleaseKind.Compilation to listOf("best"),
            ),
            vm.releases.value.map { (kind, albums) -> kind to albums.map { it.id } },
        )
    }
}
