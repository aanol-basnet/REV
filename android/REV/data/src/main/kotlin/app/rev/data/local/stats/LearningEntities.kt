package app.rev.data.local.stats

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A listen (or feedback button) the phone's skip model learned from, kept so it can be learned
 * again on top of each newer model from the server: listens made offline count at once, and still
 * count after the server's model replaces the phone's.
 */
@Entity(tableName = "learned_examples", indices = [Index("startedAt")])
data class LearnedExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    /** The model's features, one per line. */
    val features: String,
    val skipped: Boolean,
    val weight: Double,
)

@Dao
interface LearnedExampleDao {
    @Insert
    suspend fun insert(example: LearnedExampleEntity)

    /** Examples newer than [startedAt], oldest first. */
    @Query("SELECT * FROM learned_examples WHERE startedAt > :startedAt ORDER BY startedAt, id")
    suspend fun since(startedAt: Long): List<LearnedExampleEntity>

    @Query("SELECT COUNT(*) FROM learned_examples")
    suspend fun count(): Int

    /** Keeps the newest [keep]. */
    @Query("DELETE FROM learned_examples WHERE id NOT IN (SELECT id FROM learned_examples ORDER BY startedAt DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM learned_examples")
    suspend fun clear()
}

/** A feedback button press the server doesn't have yet. */
@Entity(tableName = "pending_feedback", primaryKeys = ["trackId", "listenStartedAt", "kind"])
data class PendingFeedbackEntity(
    val trackId: String,
    val listenStartedAt: Long,
    /** not_now or more_like_this */
    val kind: String,
    val at: Long,
    val positionMs: Long?,
)

@Dao
interface PendingFeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PendingFeedbackEntity)

    @Query("SELECT * FROM pending_feedback ORDER BY at LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingFeedbackEntity>

    @Query("DELETE FROM pending_feedback WHERE trackId = :trackId AND listenStartedAt = :listenStartedAt AND kind = :kind")
    suspend fun delete(trackId: String, listenStartedAt: Long, kind: String)
}
