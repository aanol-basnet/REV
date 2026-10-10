package app.rev.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.core.ui.state.ContentState
import app.rev.core.ui.state.Refresher
import app.rev.core.ui.state.cacheBacked
import app.rev.domain.model.ConnectionState
import app.rev.domain.model.HomeFeed
import app.rev.domain.model.Mix
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.repository.ConnectionRepository
import app.rev.domain.repository.DownloadsRepository
import app.rev.domain.repository.HomeRepository
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.OfflineMixRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Greeting { Morning, Afternoon, Evening, Night }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val home: HomeRepository,
    downloads: DownloadsRepository,
    private val queue: PlaybackQueue,
    private val offlineMixes: OfflineMixRepository,
    connection: ConnectionRepository,
) : ViewModel() {
    /** The server can't be reached: Home leads with mixes from downloads. */
    val offline: StateFlow<Boolean> = connection.connectionState
        .map { it == ConnectionState.NoNetwork || it == ConnectionState.ServerUnreachable }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Mixes that play from music on this device. */
    val downloadMixes: StateFlow<List<Mix>> = offlineMixes.observeMixes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Albums currently being fetched for the library. */
    val activeDownloads: StateFlow<Int> = downloads.observeJobs()
        .map { jobs -> jobs.count { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var forceNext = false
    private val refresher = Refresher(viewModelScope) {
        val force = forceNext
        forceNext = false
        home.refresh(force)
    }

    val feed: StateFlow<ContentState<HomeFeed>> =
        viewModelScope.cacheBacked(home.observeFeed(), refresher) { it.sections.isEmpty() && it.mixes.isEmpty() }

    /** The mix being fetched to play, to show progress on its card. */
    private val _startingMix = MutableStateFlow<String?>(null)
    val startingMix: StateFlow<String?> = _startingMix.asStateFlow()

    private val _errors = Channel<LibraryError>(Channel.BUFFERED)
    val errors: Flow<LibraryError> = _errors.receiveAsFlow()

    /** A server mix that couldn't be fetched was played from downloads instead. */
    private val _playedFromDownloads = Channel<Unit>(Channel.CONFLATED)
    val playedFromDownloads: Flow<Unit> = _playedFromDownloads.receiveAsFlow()

    init {
        refresher.refresh()
    }

    /** Pull to refresh: ask the server for fresh recommendations, not its recent result. */
    fun refresh() {
        forceNext = true
        refresher.refresh()
    }

    fun playMix(mix: Mix) {
        if (_startingMix.value != null) return
        _startingMix.value = mix.id
        viewModelScope.launch {
            if (mix.isOffline) {
                playTracks(offlineMixes.tracks(mix))
            } else {
                when (val result = home.mixTracks(mix.id)) {
                    is Result.Ok -> playTracks(result.value)
                    // Nothing to stream from: the same kind of mix from what's downloaded, if anything fits.
                    is Result.Err -> {
                        val noServer = result.error == LibraryError.Unreachable ||
                            result.error is LibraryError.MusicServerUnavailable
                        val fallback = if (noServer) offlineMixes.tracks(mix) else emptyList()
                        if (fallback.isEmpty()) {
                            _errors.send(result.error)
                        } else {
                            queue.play(fallback)
                            _playedFromDownloads.send(Unit)
                        }
                    }
                }
            }
            _startingMix.value = null
        }
    }

    private fun playTracks(tracks: List<Track>) {
        if (tracks.isNotEmpty()) queue.play(tracks)
    }

    fun greeting(now: LocalTime = LocalTime.now()): Greeting = when (now.hour) {
        in 5..11 -> Greeting.Morning
        in 12..17 -> Greeting.Afternoon
        in 18..22 -> Greeting.Evening
        else -> Greeting.Night
    }
}
