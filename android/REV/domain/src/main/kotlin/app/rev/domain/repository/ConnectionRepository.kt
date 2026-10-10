package app.rev.domain.repository

import app.rev.domain.model.ConnectionState
import kotlinx.coroutines.flow.StateFlow

interface ConnectionRepository {
    val connectionState: StateFlow<ConnectionState>

    /** Re-checks the server now instead of waiting for the next scheduled check. */
    fun recheck()
}
