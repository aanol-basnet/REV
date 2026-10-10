package app.rev.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.core.ui.state.ContentState
import app.rev.core.ui.state.Refresher
import app.rev.core.ui.state.cacheBacked
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.Mix
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.Result
import app.rev.domain.model.StorageUsage
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.repository.AcquisitionRepository
import app.rev.domain.repository.DownloadsRepository
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.OfflineMixRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.repository.ServerEvents
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadSections(
    /** Music saved (or being saved) on this device. */
    val onDevice: List<OfflineCollection>,
    /** Server requests still being acquired. */
    val inProgress: List<DownloadJob>,
    val finished: List<DownloadJob>,
) {
    val isEmpty: Boolean get() = onDevice.isEmpty() && inProgress.isEmpty() && finished.isEmpty()
}

/** An artist with songs on this device. */
data class DownloadedArtist(val id: String?, val name: String, val coverArtId: String?, val songCount: Int)

/** What the downloads page lists below its summary. */
enum class DownloadsView { Collections, Songs, Artists }

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadsRepository,
    private val acquisition: AcquisitionRepository,
    private val offline: OfflineRepository,
    private val offlineMixes: OfflineMixRepository,
    private val queue: PlaybackQueue,
    events: ServerEvents,
) : ViewModel() {
    private val refresher = Refresher(viewModelScope) { downloads.refresh() }

    val state: StateFlow<ContentState<DownloadSections>> = viewModelScope.cacheBacked(
        combine(downloads.observeJobs(), offline.observeCollections()) { jobs, collections ->
            val (active, done) = jobs.partition { it.isActive }
            DownloadSections(collections, active, done.take(FINISHED_SHOWN))
        },
        refresher,
    ) { it.isEmpty }

    /** Every song on the device, newest first. */
    val songs: StateFlow<List<Track>> = offline.observeDownloadedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<DownloadedArtist>> = songs
        .map(::artistsOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val storage: StateFlow<StorageUsage?> = offline.observeStorage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val mixes: StateFlow<List<Mix>> = offlineMixes.observeMixes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _view = MutableStateFlow(DownloadsView.Collections)
    val view: StateFlow<DownloadsView> = _view.asStateFlow()

    /** The mix being put together, to show progress on its card. */
    private val _startingMix = MutableStateFlow<String?>(null)
    val startingMix: StateFlow<String?> = _startingMix.asStateFlow()

    /** Whether live updates are flowing; when not, the list only changes on refresh. */
    val live: StateFlow<Boolean> = events.connected

    private val _errors = Channel<LibraryError>(Channel.BUFFERED)
    val errors: Flow<LibraryError> = _errors.receiveAsFlow()

    init {
        refresher.refresh()
    }

    fun refresh() = refresher.refresh()

    fun showView(view: DownloadsView) {
        _view.value = view
    }

    /** Plays every downloaded song, in order from [startIndex] or shuffled. */
    fun playSongs(startIndex: Int = 0, shuffle: Boolean = false) {
        val all = songs.value
        if (all.isNotEmpty()) queue.play(all, startIndex.coerceIn(all.indices), shuffle)
    }

    fun playNext(track: Track) = queue.playNext(listOf(track))

    fun addToQueue(track: Track) = queue.addToQueue(listOf(track))

    fun playMix(mix: Mix) {
        if (_startingMix.value != null) return
        _startingMix.value = mix.id
        viewModelScope.launch {
            val tracks = offlineMixes.tracks(mix)
            if (tracks.isNotEmpty()) queue.play(tracks)
            _startingMix.value = null
        }
    }

    fun removeOffline(collection: OfflineCollection) = viewModelScope.launch { offline.remove(collection.id) }

    fun retryOffline(collection: OfflineCollection) = viewModelScope.launch { offline.retry(collection.id) }

    fun cancel(job: DownloadJob) = act { acquisition.cancel(job.id) }

    fun clearFinished() = act { downloads.clearFinished() }

    /** A failed or timed-out album is simply requested again. */
    fun retry(job: DownloadJob) = act { acquisition.request(job.catalogAlbumId) }

    private fun act(call: suspend () -> Result<*, LibraryError>) {
        viewModelScope.launch {
            val result = call()
            if (result is Result.Err) _errors.send(result.error)
        }
    }

    private companion object {
        const val FINISHED_SHOWN = 50
    }
}

/** Artists by how many of their songs are downloaded, most first. */
internal fun artistsOf(songs: List<Track>): List<DownloadedArtist> =
    songs.groupBy { it.artistId ?: it.artist.lowercase() }
        .values
        .map { tracks ->
            val first = tracks.first()
            DownloadedArtist(first.artistId, first.artist, tracks.firstNotNullOfOrNull { it.coverArtId }, tracks.size)
        }
        .sortedWith(compareByDescending<DownloadedArtist> { it.songCount }.thenBy { it.name.lowercase() })
