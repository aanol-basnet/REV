package app.rev.feature.library

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import app.rev.domain.model.Result
import app.rev.domain.model.ReleaseKind
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.core.ui.state.ContentState
import app.rev.core.ui.state.PagedList
import app.rev.core.ui.state.PagedState
import app.rev.core.ui.state.Refresher
import app.rev.core.ui.state.cacheBacked
import app.rev.domain.model.Album
import app.rev.domain.model.AlbumDetail
import app.rev.domain.model.ArtistDetail
import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Track
import app.rev.domain.repository.LibraryError
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.OfflineKind
import app.rev.domain.model.PlaylistDetail
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.repository.PlaylistRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.rev.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    playbackQueue: PlaybackQueue,
    private val offline: OfflineRepository,
) : ViewModel() {
    private val id: String = savedStateHandle.requireArg(ArtistRoute::id.name)
    private val refresher = Refresher(viewModelScope) { library.refreshArtist(id) }
    private val favorites = FavoriteActions(viewModelScope, library)
    val state: StateFlow<ContentState<ArtistDetail>> = viewModelScope.cacheBacked(library.observeArtist(id), refresher)

    /** The artist's releases grouped by kind (albums, EPs, singles…), newest first. */
    val releases: StateFlow<List<Pair<ReleaseKind, List<Album>>>> = state
        .map { s -> s.content?.albums.orEmpty().groupByKind() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val topTracks = MutableStateFlow<TopTracksState>(TopTracksState.Loading)
    private val likedIds: Flow<Set<String>> = library.observeFavorites().map { f -> f.tracks.mapTo(HashSet()) { it.id } }
    val downloadedIds: StateFlow<Set<String>> = offline.downloadedIds(viewModelScope)

    /** The artist's top songs (the server's ranking), and how many of their songs you liked or downloaded. */
    val songs: StateFlow<ArtistSongs> = combine(
        topTracks,
        likedIds,
        yourArtistSongs(library, offline, id),
    ) { top, liked, yours ->
        ArtistSongs(
            tracks = (top as? TopTracksState.Loaded)?.tracks.orEmpty()
                .take(TOP_SONGS)
                .map { it.copy(starred = it.id in liked) },
            yourSongsCount = yours.size,
            loading = top is TopTracksState.Loading,
            failed = top is TopTracksState.Failed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArtistSongs())

    val errors: Flow<LibraryError> = favorites.errors

    private val queue = QueueActions(playbackQueue)
    internal val queueMessages: Flow<QueueMessage> = queue.messages

    init {
        refresh()
        // Keeps the hearts on the songs list current.
        viewModelScope.launch { library.refreshFavorites() }
    }

    fun refresh() {
        refresher.refresh()
        viewModelScope.launch {
            topTracks.value = when (val result = library.artistTopTracks(id)) {
                is Result.Ok -> TopTracksState.Loaded(result.value)
                is Result.Err -> if (topTracks.value is TopTracksState.Loaded) topTracks.value else TopTracksState.Failed
            }
        }
    }

    fun setFavorite(favorite: Boolean) = favorites.set(FavoriteKind.Artist, id, favorite)
    fun setTrackFavorite(track: Track, favorite: Boolean) = favorites.set(FavoriteKind.Track, track.id, favorite)

    fun play(startIndex: Int = 0) = queue.play(songs.value.tracks, startIndex)
    fun shuffle() = queue.play(songs.value.tracks, shuffle = true)
    fun queueAll() = queue.addToQueue(songs.value.tracks)
    fun allTrackIds(): List<String> = songs.value.tracks.map { it.id }
    fun playNext(track: Track) = queue.playNext(listOf(track))
    fun addToQueue(track: Track) = queue.addToQueue(listOf(track))
    fun downloadTrack(track: Track) = viewModelScope.launch { offline.downloadTrack(track) }
}

internal sealed interface TopTracksState {
    data object Loading : TopTracksState
    data object Failed : TopTracksState
    data class Loaded(val tracks: List<Track>) : TopTracksState
}

data class ArtistSongs(
    /** The top few songs. */
    val tracks: List<Track> = emptyList(),
    /** Songs by this artist you've liked or downloaded (shown on their own screen). */
    val yourSongsCount: Int = 0,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

private const val TOP_SONGS = 5

/**
 * Songs by [artistId] you've liked or downloaded: liked ones first (newest likes first, as the
 * server lists them), then downloads (newest first). Marked starred where liked.
 */
internal fun yourArtistSongs(library: LibraryRepository, offline: OfflineRepository, artistId: String): Flow<List<Track>> =
    combine(library.observeFavorites(), offline.observeDownloadedTracks(artistId)) { favorites, downloaded ->
        val liked = favorites.tracks.filter { it.artistId == artistId }.map { it.copy(starred = true) }
        val likedIds = favorites.tracks.mapTo(HashSet()) { it.id }
        val seen = liked.mapTo(HashSet()) { it.id }
        liked + downloaded.filter { seen.add(it.id) }.map { it.copy(starred = it.id in likedIds) }
    }

/** All the songs by one artist that you've liked or downloaded. */
@HiltViewModel
class ArtistSongsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    playbackQueue: PlaybackQueue,
    private val offline: OfflineRepository,
) : ViewModel() {
    private val id: String = savedStateHandle.requireArg(ArtistSongsRoute::id.name)
    val artistName: String = savedStateHandle.get<String>(ArtistSongsRoute::name.name).orEmpty()
    private val favorites = FavoriteActions(viewModelScope, library)

    /** Null until loaded. */
    val songs: StateFlow<List<Track>?> = yourArtistSongs(library, offline, id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val downloadedIds: StateFlow<Set<String>> = offline.downloadedIds(viewModelScope)
    val errors: Flow<LibraryError> = favorites.errors

    private val queue = QueueActions(playbackQueue)
    internal val queueMessages: Flow<QueueMessage> = queue.messages

    init {
        viewModelScope.launch { library.refreshFavorites() }
    }

    fun play(startIndex: Int = 0) = queue.play(songs.value.orEmpty(), startIndex)
    fun shuffle() = queue.play(songs.value.orEmpty(), shuffle = true)
    fun queueAll() = queue.addToQueue(songs.value.orEmpty())
    fun playNext(track: Track) = queue.playNext(listOf(track))
    fun addToQueue(track: Track) = queue.addToQueue(listOf(track))
    fun setTrackFavorite(track: Track, favorite: Boolean) = favorites.set(FavoriteKind.Track, track.id, favorite)
    fun downloadTrack(track: Track) = viewModelScope.launch { offline.downloadTrack(track) }
}

internal fun List<Album>.groupByKind(): List<Pair<ReleaseKind, List<Album>>> =
    groupBy { it.releaseKind }
        .map { (kind, albums) -> kind to albums.sortedWith(compareByDescending<Album> { it.year ?: 0 }.thenBy { it.name }) }
        .sortedBy { it.first.ordinal }

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    playbackQueue: PlaybackQueue,
    private val offline: OfflineRepository,
) : ViewModel() {
    private val id: String = savedStateHandle.requireArg(AlbumRoute::id.name)
    private val refresher = Refresher(viewModelScope) { library.refreshAlbum(id) }
    private val favorites = FavoriteActions(viewModelScope, library)
    val state: StateFlow<ContentState<AlbumDetail>> = viewModelScope.cacheBacked(library.observeAlbum(id), refresher)
    val errors: Flow<LibraryError> = favorites.errors

    init { refresher.refresh() }

    fun refresh() = refresher.refresh()

    fun setFavorite(favorite: Boolean) = favorites.set(FavoriteKind.Album, id, favorite)

    fun setTrackFavorite(track: Track, favorite: Boolean) = favorites.set(FavoriteKind.Track, track.id, favorite)

    private val queue = QueueActions(playbackQueue)
    internal val queueMessages: Flow<QueueMessage> = queue.messages
    private val tracks: List<Track> get() = state.value.content?.tracks.orEmpty()

    fun play(startIndex: Int = 0) = queue.play(tracks, startIndex)
    fun shuffle() = queue.play(tracks, shuffle = true)
    fun queueAlbum() = queue.addToQueue(tracks)
    fun playNext(track: Track) = queue.playNext(listOf(track))
    fun addToQueue(track: Track) = queue.addToQueue(listOf(track))

    private val offlineId = OfflineCollection.idFor(OfflineKind.Album, id)
    val offlineCollection: StateFlow<OfflineCollection?> = offline.observeCollection(offlineId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val downloadedIds: StateFlow<Set<String>> = offline.downloadedIds(viewModelScope)

    fun downloadAlbum() {
        val detail = state.value.content ?: return
        if (detail.tracks.isEmpty()) return
        viewModelScope.launch { offline.downloadAlbum(detail.album, detail.tracks) }
    }

    fun retryDownload() = viewModelScope.launch { offline.retry(offlineId) }
    fun removeDownload() = viewModelScope.launch { offline.remove(offlineId) }
    fun downloadTrack(track: Track) = viewModelScope.launch { offline.downloadTrack(track) }
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    playbackQueue: PlaybackQueue,
    private val offline: OfflineRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {
    private val id: String = savedStateHandle.requireArg(PlaylistRoute::id.name)
    private val refresher = Refresher(viewModelScope) { library.refreshPlaylist(id) }
    val state: StateFlow<ContentState<PlaylistDetail>> = viewModelScope.cacheBacked(library.observePlaylist(id), refresher)

    private val queue = QueueActions(playbackQueue)
    internal val queueMessages: Flow<QueueMessage> = queue.messages
    private val tracks: List<Track> get() = state.value.content?.tracks.orEmpty()

    private val favorites = FavoriteActions(viewModelScope, library)
    private val playlistErrors = Channel<LibraryError>(Channel.BUFFERED)
    val errors: Flow<LibraryError> = merge(favorites.errors, playlistErrors.receiveAsFlow())
    fun setTrackFavorite(track: Track, favorite: Boolean) = favorites.set(FavoriteKind.Track, track.id, favorite)

    private val _duplicating = MutableStateFlow(false)
    val duplicating: StateFlow<Boolean> = _duplicating.asStateFlow()
    private val _duplicated = Channel<String>(Channel.BUFFERED)
    /** Id of the copy just made, to open it. */
    val duplicated: Flow<String> = _duplicated.receiveAsFlow()

    fun duplicate() {
        if (_duplicating.value) return
        _duplicating.value = true
        viewModelScope.launch {
            when (val result = playlists.duplicate(id)) {
                is Result.Ok -> _duplicated.send(result.value.playlist.id)
                is Result.Err -> playlistErrors.send(result.error)
            }
            _duplicating.value = false
        }
    }

    private val offlineId = OfflineCollection.idFor(OfflineKind.Playlist, id)
    val offlineCollection: StateFlow<OfflineCollection?> = offline.observeCollection(offlineId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val downloadedIds: StateFlow<Set<String>> = offline.downloadedIds(viewModelScope)

    init { refresher.refresh() }

    fun refresh() = refresher.refresh()
    fun play(startIndex: Int = 0) = queue.play(tracks, startIndex)
    fun shuffle() = queue.play(tracks, shuffle = true)
    fun playNext(track: Track) = queue.playNext(listOf(track))
    fun addToQueue(track: Track) = queue.addToQueue(listOf(track))

    fun download() {
        val detail = state.value.content ?: return
        if (detail.tracks.isEmpty()) return
        viewModelScope.launch { offline.downloadPlaylist(detail.playlist, detail.tracks) }
    }

    fun retryDownload() = viewModelScope.launch { offline.retry(offlineId) }
    fun removeDownload() = viewModelScope.launch { offline.remove(offlineId) }
    fun downloadTrack(track: Track) = viewModelScope.launch { offline.downloadTrack(track) }
}

@HiltViewModel
class GenreAlbumsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
) : ViewModel() {
    val genre: String = savedStateHandle.requireArg(GenreRoute::name.name)
    private val paged = PagedList(viewModelScope, pageSize = 100) { offset, size ->
        library.albumsByGenre(genre, offset, size)
    }
    val state: StateFlow<PagedState<Album>> = paged.state

    init { paged.loadMore() }

    fun loadMore() = paged.loadMore()

    fun reload() = paged.reload()
}

/**
 * Type-safe navigation stores route properties in the SavedStateHandle under their names; reading
 * them directly (rather than via toRoute) keeps these ViewModels testable without Android classes.
 */
internal fun SavedStateHandle.requireArg(name: String): String =
    checkNotNull(get<String>(name)) { "missing navigation argument '$name'" }
