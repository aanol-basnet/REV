package app.rev.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Mix
import app.rev.domain.model.OFFLINE_MIX_PREFIX
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.repository.HomeRepository
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineMixRepository
import app.rev.domain.repository.OfflineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface MixSongs {
    data object Loading : MixSongs
    /** [fromDownloads]: the server couldn't be reached, so these are the downloaded songs that fit. */
    data class Loaded(val tracks: List<Track>, val fromDownloads: Boolean = false) : MixSongs
    data class Failed(val error: LibraryError) : MixSongs
}

/**
 * One mix's songs, fetched once when opened so what you see is what plays. A server mix falls back
 * to the same kind of mix from downloads when the server can't be reached.
 */
@HiltViewModel
class MixViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val home: HomeRepository,
    private val offlineMixes: OfflineMixRepository,
    library: LibraryRepository,
    private val offline: OfflineRepository,
    playbackQueue: PlaybackQueue,
) : ViewModel() {
    private val id: String = savedStateHandle.requireArg(MixRoute::id.name)

    private val found: Flow<Mix?> = combine(home.observeFeed(), offlineMixes.observeMixes()) { feed, onDevice ->
        feed?.mixes.orEmpty().firstOrNull { it.id == id } ?: onDevice.firstOrNull { it.id == id }
    }

    /** The mix from Home or downloads; null until found. */
    val mix: StateFlow<Mix?> = found.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _songs = MutableStateFlow<MixSongs>(MixSongs.Loading)
    val songs: StateFlow<MixSongs> = _songs.asStateFlow()

    val downloadedIds: StateFlow<Set<String>> = offline.downloadedIds(viewModelScope)
    private val favorites = FavoriteActions(viewModelScope, library)
    val errors: Flow<LibraryError> = favorites.errors
    private val queue = QueueActions(playbackQueue)
    internal val queueMessages: Flow<QueueMessage> = queue.messages

    init { reload() }

    fun reload() {
        _songs.value = MixSongs.Loading
        viewModelScope.launch { _songs.value = load() }
    }

    private suspend fun load(): MixSongs {
        if (id.startsWith(OFFLINE_MIX_PREFIX)) return MixSongs.Loaded(offlineSongs())
        return when (val result = home.mixTracks(id)) {
            is Result.Ok -> MixSongs.Loaded(result.value)
            is Result.Err -> {
                val noServer = result.error == LibraryError.Unreachable || result.error is LibraryError.MusicServerUnavailable
                val fallback = if (noServer) offlineSongs() else emptyList()
                if (fallback.isEmpty()) MixSongs.Failed(result.error) else MixSongs.Loaded(fallback, fromDownloads = true)
            }
        }
    }

    private suspend fun offlineSongs(): List<Track> {
        val mix = found.first() ?: return emptyList()
        return offlineMixes.tracks(mix)
    }

    private val tracks: List<Track> get() = (songs.value as? MixSongs.Loaded)?.tracks.orEmpty()

    fun play(startIndex: Int = 0) = queue.play(tracks, startIndex)
    fun shuffle() = queue.play(tracks, shuffle = true)
    fun queueAll() = queue.addToQueue(tracks)
    fun playNext(track: Track) = queue.playNext(listOf(track))
    fun addToQueue(track: Track) = queue.addToQueue(listOf(track))
    fun setTrackFavorite(track: Track, favorite: Boolean) = favorites.set(FavoriteKind.Track, track.id, favorite)
    fun downloadTrack(track: Track) = viewModelScope.launch { offline.downloadTrack(track) }
}
