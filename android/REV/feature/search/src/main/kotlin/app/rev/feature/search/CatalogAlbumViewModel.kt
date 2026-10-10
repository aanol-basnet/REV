package app.rev.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.CatalogAlbumStatus
import app.rev.domain.model.Result
import app.rev.domain.repository.AcquisitionRepository
import app.rev.domain.repository.DownloadsRepository
import app.rev.domain.repository.ServerEvents
import app.rev.domain.repository.LibraryError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CatalogAlbumUiState(
    val status: CatalogAlbumStatus? = null,
    val loadError: LibraryError? = null,
    /** A request or cancellation is being sent. */
    val busy: Boolean = false,
)

@HiltViewModel
class CatalogAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val acquisition: AcquisitionRepository,
    private val downloads: DownloadsRepository,
    private val events: ServerEvents,
) : ViewModel() {
    val catalogAlbumId: String = checkNotNull(savedStateHandle[CatalogAlbumRoute::catalogAlbumId.name])

    private val _state = MutableStateFlow(CatalogAlbumUiState())
    val state: StateFlow<CatalogAlbumUiState> = _state.asStateFlow()

    private val _errors = Channel<LibraryError>(Channel.BUFFERED)
    val errors: Flow<LibraryError> = _errors.receiveAsFlow()

    private var polling: Job? = null

    init {
        load()
        // Live job updates (WebSocket → cache) drive this page; re-read the full status when the
        // job's state changes so availability and the playable album id follow.
        viewModelScope.launch {
            downloads.observeLatestJob(catalogAlbumId)
                .map { it?.id to it?.state }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
        viewModelScope.launch {
            downloads.observeLatestJob(catalogAlbumId).collect { job ->
                if (job != null) _state.update { s -> s.copy(status = s.status?.copy(job = job)) }
            }
        }
    }

    fun load() {
        viewModelScope.launch { refresh() }
    }

    fun download() = act { acquisition.request(catalogAlbumId) }

    fun cancel() {
        val jobId = _state.value.status?.job?.takeIf { it.isActive }?.id ?: return
        act { acquisition.cancel(jobId) }
    }

    private fun act(call: suspend () -> Result<*, LibraryError>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = call()
            if (result is Result.Err) _errors.send(result.error)
            refresh()
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun refresh() {
        when (val result = acquisition.albumStatus(catalogAlbumId)) {
            is Result.Ok -> {
                _state.update { it.copy(status = result.value, loadError = null) }
                updatePolling(result.value)
            }
            is Result.Err -> _state.update { it.copy(loadError = result.error) }
        }
    }

    /**
     * Fallback for when live updates aren't flowing: while a request is in progress, re-check
     * periodically. With the live connection up, events do the work.
     */
    private fun updatePolling(status: CatalogAlbumStatus) {
        val inProgress = status.job?.isActive == true
        if (!inProgress) {
            polling?.cancel()
            polling = null
            return
        }
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!events.connected.value) refresh()
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }
}
