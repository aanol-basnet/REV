package app.rev.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.Result
import app.rev.domain.model.SearchResults
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/** Which kind of result the Search screen lists. */
enum class SearchFilter { All, Songs, Albums, Artists }

sealed interface SearchState {
    /** No query yet. */
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val results: SearchResults) : SearchState
    data class Failed(val error: LibraryError) : SearchState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val playbackQueue: PlaybackQueue,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Bumped to re-run the current query (retry). */
    private val attempt = MutableStateFlow(0)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val state: StateFlow<SearchState> =
        combine(
            _query.map { it.trim() }.distinctUntilChanged().debounce { if (it.isEmpty()) 0 else DEBOUNCE_MS },
            attempt,
        ) { q, _ -> q }
            .transformLatest { q ->
                if (q.isEmpty()) {
                    emit(SearchState.Idle)
                    return@transformLatest
                }
                emit(SearchState.Loading)
                emit(
                    when (val result = searchRepository.search(q)) {
                        is Result.Ok -> SearchState.Results(result.value)
                        is Result.Err -> onDevice(q, result.error)
                    },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState.Idle)

    private val _filter = MutableStateFlow(SearchFilter.All)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    fun setFilter(value: SearchFilter) {
        _filter.value = value
    }

    /** Without a server, what's cached and downloaded can still be found. */
    private suspend fun onDevice(query: String, error: LibraryError): SearchState {
        val noServer = error == LibraryError.Unreachable || error is LibraryError.MusicServerUnavailable
        if (!noServer) return SearchState.Failed(error)
        val local = searchRepository.searchOnDevice(query)
        return if (local.isEmpty) SearchState.Failed(error) else SearchState.Results(local)
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun retry() {
        attempt.value++
    }

    /** Re-runs the current search if results are showing (they may be stale). */
    fun refreshResults() {
        if (state.value is SearchState.Results) attempt.value++
    }

    fun playTracks(index: Int) {
        val results = (state.value as? SearchState.Results)?.results ?: return
        playbackQueue.play(results.tracks, index)
    }

    private companion object {
        const val DEBOUNCE_MS = 350L
    }
}
