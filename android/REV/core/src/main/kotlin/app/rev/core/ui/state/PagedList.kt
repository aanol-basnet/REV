package app.rev.core.ui.state

import app.rev.domain.model.Page
import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PagedState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val error: LibraryError? = null,
    val endReached: Boolean = false,
)

/** Incrementally loads a server-paged collection. Call [loadMore] as the user nears the end. */
class PagedList<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int = 100,
    private val load: suspend (offset: Int, size: Int) -> Result<Page<T>, LibraryError>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()
    private var job: Job? = null

    fun loadMore() {
        val current = _state.value
        if (job?.isActive == true || current.endReached) return
        _state.update { it.copy(isLoading = true, error = null) }
        job = scope.launch {
            when (val result = load(current.items.size, pageSize)) {
                is Result.Ok -> _state.update {
                    it.copy(
                        items = it.items + result.value.items,
                        isLoading = false,
                        endReached = !result.value.hasMore,
                    )
                }
                is Result.Err -> _state.update { it.copy(isLoading = false, error = result.error) }
            }
        }
    }

    /** Applies [transform] to loaded items, e.g. to reflect a favorite toggled elsewhere. */
    fun updateItems(transform: (T) -> T) = _state.update { it.copy(items = it.items.map(transform)) }

    /** Discards loaded items and starts again from the first page. */
    fun reload() {
        job?.cancel()
        _state.value = PagedState()
        loadMore()
    }
}
