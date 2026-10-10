package app.rev.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.core.ui.state.ContentState
import app.rev.core.ui.state.PagedList
import app.rev.core.ui.state.PagedState
import app.rev.core.ui.state.Refresher
import app.rev.core.ui.state.cacheBacked
import app.rev.domain.model.Album
import app.rev.domain.model.AlbumListType
import app.rev.domain.model.Artist
import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Favorites
import app.rev.domain.model.Genre
import app.rev.domain.model.Playlist
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.repository.PlaylistRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import app.rev.domain.repository.SearchRepository
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

@HiltViewModel
class ArtistsViewModel @Inject constructor(library: LibraryRepository) : ViewModel() {
    private val refresher = Refresher(viewModelScope) { library.refreshArtists() }
    val state: StateFlow<ContentState<List<Artist>>> =
        viewModelScope.cacheBacked(library.observeArtists(), refresher) { it.isEmpty() }

    init { refresher.refresh() }

    fun refresh() = refresher.refresh()
}

@HiltViewModel
class AlbumsViewModel @Inject constructor(library: LibraryRepository) : ViewModel() {
    private val refresher = Refresher(viewModelScope) { library.refreshAlbumList(AlbumListType.Alphabetical) }
    val state: StateFlow<ContentState<List<Album>>> =
        viewModelScope.cacheBacked(library.observeAlbumList(AlbumListType.Alphabetical), refresher) { it.isEmpty() }

    init { refresher.refresh() }

    fun refresh() = refresher.refresh()
}

@HiltViewModel
class GenresViewModel @Inject constructor(library: LibraryRepository) : ViewModel() {
    private val refresher = Refresher(viewModelScope) { library.refreshGenres() }
    val state: StateFlow<ContentState<List<Genre>>> =
        viewModelScope.cacheBacked(library.observeGenres(), refresher) { it.isEmpty() }

    init { refresher.refresh() }

    fun refresh() = refresher.refresh()
}

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    library: LibraryRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {
    private val refresher = Refresher(viewModelScope) { library.refreshPlaylists() }
    val state: StateFlow<ContentState<List<Playlist>>> =
        viewModelScope.cacheBacked(library.observePlaylists(), refresher) { it.isEmpty() }

    private val _created = Channel<String>(Channel.BUFFERED)
    /** Ids of playlists just made here, to open them. */
    val created: Flow<String> = _created.receiveAsFlow()
    private val _errors = Channel<LibraryError>(Channel.BUFFERED)
    val errors: Flow<LibraryError> = _errors.receiveAsFlow()

    init { refresher.refresh() }

    fun refresh() = refresher.refresh()

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val result = playlists.create(name.trim())) {
                is Result.Ok -> _created.send(result.value.playlist.id)
                is Result.Err -> _errors.send(result.error)
            }
        }
    }
}

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    library: LibraryRepository,
    playbackQueue: PlaybackQueue,
    private val offline: OfflineRepository,
) : ViewModel() {
    val downloadedIds: StateFlow<Set<String>> = offline.downloadedIds(viewModelScope)
    fun downloadTrack(track: Track) = viewModelScope.launch { offline.downloadTrack(track) }

    private val queue = QueueActions(playbackQueue)
    internal val queueMessages: Flow<QueueMessage> = queue.messages

    private val refresher = Refresher(viewModelScope) { library.refreshFavorites() }
    private val favorites = FavoriteActions(viewModelScope, library)
    val state: StateFlow<ContentState<Favorites>> =
        viewModelScope.cacheBacked(library.observeFavorites(), refresher) { it.isEmpty }
    val errors: Flow<LibraryError> = favorites.errors

    init { refresher.refresh() }

    fun refresh() = refresher.refresh()

    fun setTrackFavorite(track: Track, favorite: Boolean) =
        favorites.set(FavoriteKind.Track, track.id, favorite)

    /** Plays [tracks] (the favorites as shown, possibly filtered) from [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int) = queue.play(tracks, startIndex)
    fun playNext(track: Track) = queue.playNext(listOf(track))
    fun addToQueue(track: Track) = queue.addToQueue(listOf(track))
}

/** Songs matching the Library filter; [loading] while the search runs. */
data class TrackMatches(val query: String, val loading: Boolean, val tracks: List<Track>)

/**
 * All tracks, paged from the server (too many to cache wholesale). The Library filter searches
 * them on the server, or on the device when it can't be reached.
 */
@HiltViewModel
class TracksViewModel @Inject constructor(
    library: LibraryRepository,
    playbackQueue: PlaybackQueue,
    private val offline: OfflineRepository,
    private val search: SearchRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    /** Null when there's no filter. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val matches: StateFlow<TrackMatches?> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0 else FILTER_DEBOUNCE_MS }
        .transformLatest { q ->
            if (q.isEmpty()) {
                emit(null)
                return@transformLatest
            }
            emit(TrackMatches(q, loading = true, tracks = emptyList()))
            val tracks = when (val result = search.search(q)) {
                is Result.Ok -> result.value.tracks
                is Result.Err -> search.searchOnDevice(q).tracks
            }
            emit(TrackMatches(q, loading = false, tracks = tracks))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(value: String) {
        query.value = value
    }

    fun playMatch(startIndex: Int) = queue.play(matches.value?.tracks.orEmpty(), startIndex)

    val downloadedIds: StateFlow<Set<String>> = offline.downloadedIds(viewModelScope)
    fun downloadTrack(track: Track) = viewModelScope.launch { offline.downloadTrack(track) }

    private val queue = QueueActions(playbackQueue)
    internal val queueMessages: Flow<QueueMessage> = queue.messages

    private val paged = PagedList(viewModelScope, pageSize = 200) { offset, size -> library.tracks(offset, size) }
    private val favorites = FavoriteActions(viewModelScope, library)
    val state: StateFlow<PagedState<Track>> = paged.state
    val errors: Flow<LibraryError> = favorites.errors

    init { paged.loadMore() }

    fun loadMore() = paged.loadMore()

    fun reload() = paged.reload()

    fun setTrackFavorite(track: Track, favorite: Boolean) =
        favorites.set(FavoriteKind.Track, track.id, favorite) {
            paged.updateItems { if (it.id == track.id) it.copy(starred = favorite) else it }
        }

    /** Plays from [startIndex] through the tracks loaded so far. */
    fun play(startIndex: Int) = queue.play(state.value.items, startIndex)
    fun playNext(track: Track) = queue.playNext(listOf(track))
    fun addToQueue(track: Track) = queue.addToQueue(listOf(track))
}

private const val FILTER_DEBOUNCE_MS = 300L
