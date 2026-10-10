package app.rev.core.ui.state

import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Runs a refresh at most once at a time and exposes its status. */
class Refresher(
    private val scope: CoroutineScope,
    private val refresh: suspend () -> Result<Unit, LibraryError>,
) {
    private val _status = MutableStateFlow<RefreshStatus>(RefreshStatus.Idle)
    val status: StateFlow<RefreshStatus> = _status.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            _status.value = RefreshStatus.Refreshing
            _status.value = when (val result = refresh.invoke()) {
                is Result.Ok -> RefreshStatus.Idle
                is Result.Err -> RefreshStatus.Failed(result.error)
            }
        }
    }
}
