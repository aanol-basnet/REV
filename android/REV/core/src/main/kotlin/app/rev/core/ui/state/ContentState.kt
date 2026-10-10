package app.rev.core.ui.state

import app.rev.domain.repository.LibraryError

/**
 * What a cache-backed screen shows: the cached [content] (null until anything is known), whether a
 * refresh is running, and the last refresh failure. Content and error can coexist: cached data is
 * still shown when the server can't be reached.
 */
data class ContentState<T>(
    val content: T? = null,
    val isRefreshing: Boolean = false,
    val error: LibraryError? = null,
)

sealed interface RefreshStatus {
    data object Idle : RefreshStatus
    data object Refreshing : RefreshStatus
    data class Failed(val error: LibraryError) : RefreshStatus
}

fun <T> contentState(content: T?, status: RefreshStatus) = ContentState(
    content = content,
    isRefreshing = status == RefreshStatus.Refreshing,
    error = (status as? RefreshStatus.Failed)?.error,
)
