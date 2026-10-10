package app.rev.data.stats

import androidx.room.withTransaction
import app.rev.data.local.RevDatabase
import app.rev.data.local.stats.PendingListenEntity
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.ListenDto
import app.rev.domain.model.ConnectionState
import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plays and skips wait here until the server has them, so listening offline (downloads, a dead
 * zone, the server down) still ends up in your history. Sent oldest first, in batches; the server
 * ignores resends, so a lost response is harmless.
 */
class ListenQueue(
    private val db: RevDatabase,
    private val api: LibraryApi,
) {
    private val dao = db.pendingListenDao()
    private val sending = Mutex()

    suspend fun add(trackId: String, startedAt: Long, kind: String, listenedMs: Long?) {
        dao.insert(PendingListenEntity(trackId, startedAt, kind, listenedMs))
        flush()
    }

    /** Sends whatever is waiting; stops at the first failure and keeps the rest for later. */
    suspend fun flush() {
        if (!sending.tryLock()) return // already sending; it picks up new rows too
        try {
            while (true) {
                val batch = dao.oldest(BATCH)
                if (batch.isEmpty()) return
                val result = api.listens(batch.map { ListenDto(it.trackId, it.startedAt, it.kind, it.listenedMs) })
                when {
                    result is Result.Ok -> remove(batch)
                    // The server will never take these (e.g. rejected as invalid): don't block the rest.
                    result is Result.Err && result.error is LibraryError.Unexpected -> remove(batch)
                    else -> return
                }
            }
        } finally {
            sending.unlock()
        }
    }

    /** Sends waiting listens each time the server comes back. */
    fun start(scope: CoroutineScope, connection: Flow<ConnectionState>) {
        scope.launch {
            connection.filterIsInstance<ConnectionState.Online>().collect { flush() }
        }
    }

    private suspend fun remove(batch: List<PendingListenEntity>) = db.withTransaction {
        batch.forEach { dao.delete(it.trackId, it.startedAt) }
    }

    private companion object {
        const val BATCH = 200
    }
}
