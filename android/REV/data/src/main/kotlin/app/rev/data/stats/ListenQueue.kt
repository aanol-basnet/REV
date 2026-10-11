package app.rev.data.stats

import androidx.room.withTransaction
import app.rev.data.local.RevDatabase
import app.rev.data.local.stats.PendingListenEntity
import app.rev.data.remote.LibraryApi
import app.rev.data.local.stats.PendingFeedbackEntity
import app.rev.data.local.stats.PendingInteractionEntity
import app.rev.data.remote.InteractionDto
import app.rev.domain.learning.Interaction
import app.rev.data.remote.FeedbackEventDto
import app.rev.data.remote.ListenContextDto
import app.rev.data.remote.ListenDto
import app.rev.data.remote.RevJson
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
 * Plays and skips, then feedback and other interactions, wait here until the server has them, so
 * listening offline (downloads, a dead zone, the server down) still ends up in your history. Sent
 * oldest first, in batches; the server ignores resends, so a lost response is harmless.
 */
class ListenQueue(
    private val db: RevDatabase,
    private val api: LibraryApi,
) {
    private val dao = db.pendingListenDao()
    private val feedbackDao = db.pendingFeedbackDao()
    private val interactionDao = db.pendingInteractionDao()
    private val sending = Mutex()

    internal suspend fun add(trackId: String, startedAt: Long, kind: String, listenedMs: Long?, context: ListenContextDto? = null) {
        val json = context?.let { RevJson.encodeToString(ListenContextDto.serializer(), it) }
        dao.insert(PendingListenEntity(trackId, startedAt, kind, listenedMs, json))
        flush()
    }

    /** A play again, now that it has ended: the server updates the one it has. */
    internal suspend fun update(trackId: String, startedAt: Long, kind: String, listenedMs: Long?, context: ListenContextDto?) {
        val json = context?.let { RevJson.encodeToString(ListenContextDto.serializer(), it) }
        dao.upsert(PendingListenEntity(trackId, startedAt, kind, listenedMs, json))
        flush()
    }

    suspend fun addFeedback(trackId: String, listenStartedAt: Long, kind: String, at: Long, positionMs: Long?) {
        feedbackDao.insert(PendingFeedbackEntity(trackId, listenStartedAt, kind, at, positionMs))
        flush()
    }

    suspend fun addInteractions(events: List<Interaction>) {
        interactionDao.insert(events.map { PendingInteractionEntity(it.kind.wire, it.trackId, it.at) })
        flush()
    }

    /** Sends whatever is waiting; stops at the first failure and keeps the rest for later. */
    suspend fun flush() {
        if (!sending.tryLock()) return // already sending; it picks up new rows too
        try {
            // Listens first: feedback is about them.
            while (true) {
                val batch = dao.oldest(BATCH)
                if (batch.isEmpty()) break
                val result = api.listens(batch.map { ListenDto(it.trackId, it.startedAt, it.kind, it.listenedMs, it.contextDto()) })
                when {
                    result is Result.Ok -> remove(batch)
                    // The server will never take these (e.g. rejected as invalid): don't block the rest.
                    result is Result.Err && result.error is LibraryError.Unexpected -> remove(batch)
                    else -> return
                }
            }
            while (true) {
                val batch = feedbackDao.oldest(BATCH)
                if (batch.isEmpty()) break
                val result = api.feedback(batch.map { FeedbackEventDto(it.trackId, it.listenStartedAt, it.kind, it.at, it.positionMs) })
                when {
                    result is Result.Ok || (result is Result.Err && result.error is LibraryError.Unexpected) -> db.withTransaction {
                        batch.forEach { feedbackDao.delete(it.trackId, it.listenStartedAt, it.kind) }
                    }
                    else -> return
                }
            }
            while (true) {
                val batch = interactionDao.oldest(BATCH)
                if (batch.isEmpty()) return
                val result = api.interactions(batch.map { InteractionDto(it.kind, it.trackId, it.at) })
                when {
                    result is Result.Ok || (result is Result.Err && result.error is LibraryError.Unexpected) -> db.withTransaction {
                        batch.forEach { interactionDao.delete(it.kind, it.trackId, it.at) }
                    }
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

    private fun PendingListenEntity.contextDto(): ListenContextDto? =
        context?.let { runCatching { RevJson.decodeFromString(ListenContextDto.serializer(), it) }.getOrNull() }

    private suspend fun remove(batch: List<PendingListenEntity>) = db.withTransaction {
        batch.forEach { dao.delete(it.trackId, it.startedAt) }
    }

    private companion object {
        const val BATCH = 200
    }
}
