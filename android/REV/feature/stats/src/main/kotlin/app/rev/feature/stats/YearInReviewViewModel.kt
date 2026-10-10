package app.rev.feature.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.ListeningReport
import app.rev.domain.model.ReportPeriodKind
import app.rev.domain.model.Result
import app.rev.domain.model.SavedSmartPlaylist
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Year in review for the year [offset] years back (0 is this year). */
@Serializable
data class YearInReviewRoute(val offset: Int = 0)

data class YearInReviewState(
    val report: ListeningReport? = null,
    val isLoading: Boolean = true,
    val error: LibraryError? = null,
    /** Fetching the year's top songs to play. */
    val starting: Boolean = false,
    val saving: Boolean = false,
    /** The Navidrome playlist the year's top songs were saved to in this visit. */
    val saved: SavedSmartPlaylist? = null,
) {
    /** The calendar year, in the report's time zone. */
    val year: Int? get() = report?.period?.let { Instant.ofEpochMilli(it.from).atZone(ZoneId.of(it.timeZone)).year }
}

/** A year's listening as a story, ending with the year's top songs to play or keep. */
@HiltViewModel
class YearInReviewViewModel(
    private val offset: Int,
    private val stats: StatsRepository,
    private val queue: PlaybackQueue,
    private val timeZone: () -> String,
) : ViewModel() {
    @Inject constructor(savedStateHandle: SavedStateHandle, stats: StatsRepository, queue: PlaybackQueue) :
        this(savedStateHandle.get<Int>(YearInReviewRoute::offset.name) ?: 0, stats, queue, { ZoneId.systemDefault().id })

    private val _state = MutableStateFlow(YearInReviewState())
    val state: StateFlow<YearInReviewState> = _state.asStateFlow()

    private val _errors = Channel<LibraryError>(Channel.BUFFERED)
    val errors: Flow<LibraryError> = _errors.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = stats.report(ReportPeriodKind.Year, offset, timeZone())) {
                is Result.Ok -> _state.update { it.copy(report = result.value, isLoading = false) }
                is Result.Err -> _state.update { it.copy(isLoading = false, error = result.error) }
            }
        }
    }

    fun playTopSongs() {
        val id = smartId() ?: return
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            when (val result = stats.smartPlaylistTracks(id, timeZone())) {
                is Result.Ok -> if (result.value.isNotEmpty()) queue.play(result.value)
                is Result.Err -> _errors.send(result.error)
            }
            _state.update { it.copy(starting = false) }
        }
    }

    fun saveTopSongs() {
        val id = smartId() ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val result = stats.saveSmartPlaylist(id, timeZone())) {
                is Result.Ok -> _state.update { it.copy(saved = result.value) }
                is Result.Err -> _errors.send(result.error)
            }
            _state.update { it.copy(saving = false) }
        }
    }

    /** The smart playlist of the year's top songs. */
    private fun smartId(): String? = _state.value.year?.let { "year-$it" }
}
