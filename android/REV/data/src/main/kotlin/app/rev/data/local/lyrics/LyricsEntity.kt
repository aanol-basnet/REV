package app.rev.data.local.lyrics

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** Device copy of a track's lyrics (§33), so cached lyrics work with no server. */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val trackId: String,
    /** found, instrumental or not_found */
    val status: String,
    /** JSON array of { startMs, text }, when synced. */
    val syncedJson: String?,
    val plain: String?,
    val fetchedAt: Long,
)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    suspend fun get(trackId: String): LyricsEntity?

    @Upsert
    suspend fun upsert(lyrics: LyricsEntity)
}
