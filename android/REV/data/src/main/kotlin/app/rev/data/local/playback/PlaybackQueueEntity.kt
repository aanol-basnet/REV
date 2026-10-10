package app.rev.data.local.playback

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** The single persisted playback queue. Tracks are stored as JSON so the queue restores offline. */
@Entity(tableName = "playback_queue")
data class PlaybackQueueEntity(
    @PrimaryKey val id: Int = 0,
    val tracksJson: String,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: String,
    val updatedAt: Long,
)

@Dao
interface PlaybackQueueDao {
    @Upsert
    suspend fun save(queue: PlaybackQueueEntity)

    @Query("SELECT * FROM playback_queue WHERE id = 0")
    suspend fun load(): PlaybackQueueEntity?

    @Query("DELETE FROM playback_queue")
    suspend fun clear()
}
