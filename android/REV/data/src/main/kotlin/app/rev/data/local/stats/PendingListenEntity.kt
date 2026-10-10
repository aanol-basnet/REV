package app.rev.data.local.stats

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** A play or skip the server hasn't confirmed yet (offline, or it couldn't be reached). */
@Entity(tableName = "pending_listens", primaryKeys = ["trackId", "startedAt"])
data class PendingListenEntity(
    val trackId: String,
    val startedAt: Long,
    /** play or skip */
    val kind: String,
    /** How long it was heard; null for a play (the server counts the track's length). */
    val listenedMs: Long?,
    /** The situation it played in, as the JSON sent to the server (training data). */
    @ColumnInfo(defaultValue = "NULL") val context: String? = null,
)

@Dao
interface PendingListenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(listen: PendingListenEntity)

    @Query("SELECT * FROM pending_listens ORDER BY startedAt LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingListenEntity>

    @Query("DELETE FROM pending_listens WHERE trackId = :trackId AND startedAt = :startedAt")
    suspend fun delete(trackId: String, startedAt: Long)

    @Query("SELECT COUNT(*) FROM pending_listens")
    suspend fun count(): Int
}
