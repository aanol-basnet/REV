package app.rev.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.ListeningReport
import app.rev.domain.model.ReportPeriodKind
import app.rev.domain.model.Result
import app.rev.domain.model.SavedSmartPlaylist
import app.rev.domain.model.SmartPlaylist
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUiState(
    val period: ReportPeriodKind = ReportPeriodKind.Week,
    /** 0 is the current period, 1 the one before, and so on. */
    val offset: Int = 0,
    val report: ListeningReport? = null,
    val isLoading: Boolean = true,
    /** The last load failed; [report], if any, is the copy saved on the device. */
    val error: LibraryError? = null,
) {
    val canGoEarlier: Boolean get() = period != ReportPeriodKind.All && report?.period?.hasEarlier == true
    val canGoLater: Boolean get() = offset > 0
}

data class SmartPlaylistsState(
    val playlists: List<SmartPlaylist> = emptyList(),
    /** The playlist whose songs are being fetched to play. */
    val starting: String? = null,
    /** The playlist being saved to Navidrome. */
    val saving: String? = null,
)

sealed interface SmartPlaylistEvent {
    data class Saved(val playlist: SmartPlaylist, val result: SavedSmartPlaylist) : SmartPlaylistEvent
    data class Failed(val error: LibraryError) : SmartPlaylistEvent
}

/**
 * Listening reports by week, month, year or all time, stepping back through earlier periods; and
 * the smart playlists made from the same log, to play or save to Navidrome.
 */
@HiltViewModel
class StatsViewModel(
    private val stats: StatsRepository,
    private val queue: PlaybackQueue,
    private val timeZone: () -> String,
) : ViewModel() {
    @Inject constructor(stats: StatsRepository, queue: PlaybackQueue) : this(stats, queue, { ZoneId.systemDefault().id })

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()
    private var load: Job? = null

    private val _smart = MutableStateFlow(SmartPlaylistsState())
    val smart: StateFlow<SmartPlaylistsState> = _smart.asStateFlow()
    private val _smartEvents = Channel<SmartPlaylistEvent>(Channel.BUFFERED)
    val smartEvents: Flow<SmartPlaylistEvent> = _smartEvents.receiveAsFlow()

    init {
        reload()
        loadSmartPlaylists()
    }

    fun selectPeriod(period: ReportPeriodKind) {
        if (period == _state.value.period && _state.value.offset == 0) return
        _state.update { StatsUiState(period = period, offset = 0) }
        reload()
    }

    fun earlier() {
        if (!_state.value.canGoEarlier) return
        _state.update { it.copy(offset = it.offset + 1, report = null) }
        reload()
    }

    fun later() {
        if (!_state.value.canGoLater) return
        _state.update { it.copy(offset = it.offset - 1, report = null) }
        reload()
    }

    fun refresh() {
        reload()
        loadSmartPlaylists()
    }

    fun playSmartPlaylist(playlist: SmartPlaylist, shuffle: Boolean = false) {
        if (_smart.value.starting != null) return
        _smart.update { it.copy(starting = playlist.id) }
        viewModelScope.launch {
            when (val result = stats.smartPlaylistTracks(playlist.id, timeZone())) {
                is Result.Ok -> if (result.value.isNotEmpty()) queue.play(result.value, shuffle = shuffle)
                is Result.Err -> _smartEvents.send(SmartPlaylistEvent.Failed(result.error))
            }
            _smart.update { it.copy(starting = null) }
        }
    }

    fun saveSmartPlaylist(playlist: SmartPlaylist) {
        if (_smart.value.saving != null) return
        _smart.update { it.copy(saving = playlist.id) }
        viewModelScope.launch {
            when (val result = stats.saveSmartPlaylist(playlist.id, timeZone())) {
                is Result.Ok -> {
                    _smart.update { s ->
                        s.copy(playlists = s.playlists.map { if (it.id == playlist.id) it.copy(savedPlaylistId = result.value.playlistId) else it })
                    }
                    _smartEvents.send(SmartPlaylistEvent.Saved(playlist, result.value))
                }
                is Result.Err -> _smartEvents.send(SmartPlaylistEvent.Failed(result.error))
            }
            _smart.update { it.copy(saving = null) }
        }
    }

    private fun loadSmartPlaylists() {
        viewModelScope.launch {
            // Not worth an error of its own: the report already says when the server is away.
            (stats.smartPlaylists(timeZone()) as? Result.Ok)?.let { ok -> _smart.update { it.copy(playlists = ok.value) } }
        }
    }

    private fun reload() {
        load?.cancel()
        val (period, offset) = _state.value.let { it.period to it.offset }
        _state.update { it.copy(isLoading = true, error = null) }
        load = viewModelScope.launch {
            // The current period opens on the saved copy while the fresh one loads.
            if (offset == 0 && _state.value.report?.period?.kind != period) {
                stats.cachedReport(period)?.let { cached -> _state.update { it.copy(report = cached) } }
            }
            when (val result = stats.report(period, offset, timeZone())) {
                is Result.Ok -> _state.update { it.copy(report = result.value, isLoading = false, error = null) }
                is Result.Err -> _state.update {
                    // An older period has no saved copy: don't leave a different period's report up.
                    val keep = it.report?.takeIf { r -> r.period.kind == period && r.period.offset == offset }
                    it.copy(report = keep, isLoading = false, error = result.error)
                }
            }
        }
    }
}
