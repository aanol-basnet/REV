package app.rev.data.playback

import app.rev.data.local.playback.PlaybackQueueDao
import app.rev.data.local.playback.PlaybackQueueEntity
import app.rev.data.remote.RevJson
import app.rev.data.remote.TrackDto
import app.rev.data.remote.toDto
import app.rev.domain.playback.QueueSnapshot
import app.rev.domain.playback.QueueStore
import app.rev.domain.playback.RepeatMode
import kotlinx.serialization.builtins.ListSerializer

class QueueStoreImpl(
    private val dao: PlaybackQueueDao,
    private val now: () -> Long = System::currentTimeMillis,
) : QueueStore {
    private val tracksSerializer = ListSerializer(TrackDto.serializer())

    override suspend fun save(snapshot: QueueSnapshot) {
        if (snapshot.tracks.isEmpty()) return clear()
        dao.save(
            PlaybackQueueEntity(
                tracksJson = RevJson.encodeToString(tracksSerializer, snapshot.tracks.map { it.toDto() }),
                currentIndex = snapshot.currentIndex,
                positionMs = snapshot.positionMs,
                shuffle = snapshot.shuffle,
                repeat = snapshot.repeat.name,
                updatedAt = now(),
            ),
        )
    }

    override suspend fun load(): QueueSnapshot? {
        val row = dao.load() ?: return null
        val tracks = try {
            RevJson.decodeFromString(tracksSerializer, row.tracksJson).map { it.toDomain() }
        } catch (_: Exception) {
            return null // Written by an incompatible older version; start fresh.
        }
        if (tracks.isEmpty()) return null
        return QueueSnapshot(
            tracks = tracks,
            currentIndex = row.currentIndex.coerceIn(0, tracks.lastIndex),
            positionMs = row.positionMs.coerceAtLeast(0),
            shuffle = row.shuffle,
            repeat = RepeatMode.entries.firstOrNull { it.name == row.repeat } ?: RepeatMode.Off,
        )
    }

    override suspend fun clear() = dao.clear()
}
