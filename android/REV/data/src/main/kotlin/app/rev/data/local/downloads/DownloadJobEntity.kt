package app.rev.data.local.downloads

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.DownloadJobState
import kotlinx.coroutines.flow.Flow

/** Server download jobs, cached so the Downloads screen works offline. */
@Entity(tableName = "download_jobs", indices = [Index("catalogAlbumId")])
data class DownloadJobEntity(
    @PrimaryKey val id: String,
    val catalogAlbumId: String,
    val catalogArtistId: String?,
    val title: String,
    val artist: String,
    val year: Int?,
    val state: String,
    val progress: Float?,
    val error: String?,
    val libraryAlbumId: String?,
    val startedAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
) {
    fun toDomain() = DownloadJob(
        id, catalogAlbumId, catalogArtistId, title, artist, year,
        DownloadJobState.entries.firstOrNull { it.name == state } ?: DownloadJobState.CHECKING,
        progress, error, libraryAlbumId, startedAt, updatedAt, completedAt,
    )
}

fun DownloadJob.toEntity() = DownloadJobEntity(
    id, catalogAlbumId, catalogArtistId, title, artist, year, state.name, progress, error, libraryAlbumId,
    startedAt, updatedAt, completedAt,
)

@Dao
interface DownloadJobDao {
    @Query("SELECT * FROM download_jobs ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE catalogAlbumId = :catalogAlbumId ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(catalogAlbumId: String): Flow<DownloadJobEntity?>

    @Query("SELECT * FROM download_jobs WHERE id = :id")
    suspend fun get(id: String): DownloadJobEntity?

    @Upsert
    suspend fun upsert(jobs: List<DownloadJobEntity>)

    @Query("DELETE FROM download_jobs")
    suspend fun clear()

    /** Live events may arrive out of order; never let an older update overwrite a newer one. */
    @Transaction
    suspend fun upsertIfNewer(job: DownloadJobEntity) {
        val existing = get(job.id)
        if (existing == null || existing.updatedAt <= job.updatedAt) upsert(listOf(job))
    }

    @Query("DELETE FROM download_jobs WHERE state IN ('AVAILABLE', 'FAILED', 'TIMEOUT', 'CANCELLED')")
    suspend fun deleteFinished()

    @Transaction
    suspend fun replaceAll(jobs: List<DownloadJobEntity>) {
        clear()
        upsert(jobs)
    }
}
