package app.rev.core.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Combines cached [data] with [refresher] status into screen state. Empty cached data is reported
 * as "nothing known yet" while a refresh is running or has failed, so screens show a spinner or an
 * error instead of briefly claiming the library is empty.
 */
fun <T : Any> CoroutineScope.cacheBacked(
    data: Flow<T?>,
    refresher: Refresher,
    isEmpty: (T) -> Boolean = { false },
): StateFlow<ContentState<T>> =
    combine(data, refresher.status) { value, status ->
        val known = value?.takeUnless { isEmpty(it) && status != RefreshStatus.Idle }
        contentState(known, status)
    }.stateIn(this, SharingStarted.WhileSubscribed(5_000), ContentState(isRefreshing = true))
