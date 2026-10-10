package app.rev.data.local.offline

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineTrackDao {
    /** A playable local copy; only fully downloaded tracks count. */
    @Query("SELECT * FROM offline_tracks WHERE trackId = :trackId AND state = 'Downloaded'")
    suspend fun findByTrackId(trackId: String): OfflineTrackEntity?

    @Query("SELECT * FROM offline_tracks WHERE trackId = :trackId")
    suspend fun get(trackId: String): OfflineTrackEntity?

    @Query("SELECT * FROM offline_tracks ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<OfflineTrackEntity>>

    @Query("SELECT trackId FROM offline_tracks WHERE state = 'Downloaded'")
    fun observeDownloadedIds(): Flow<List<String>>

    @Query("SELECT COALESCE(SUM(fileSize), 0) AS bytes, COUNT(*) AS tracks FROM offline_tracks WHERE state = 'Downloaded'")
    fun observeUsage(): Flow<UsageRow>

    @Upsert
    suspend fun upsert(track: OfflineTrackEntity)

    @Query("DELETE FROM offline_tracks WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)

    // ---- Download progress (written by the worker) -------------------------------------------

    @Query("UPDATE offline_tracks SET state = 'Downloading', bytesDone = :bytesDone, bytesTotal = :bytesTotal, error = NULL WHERE trackId = :trackId AND state != 'Downloaded'")
    suspend fun updateProgress(trackId: String, bytesDone: Long, bytesTotal: Long)

    @Query("UPDATE offline_tracks SET state = 'Downloaded', localUri = :path, fileSize = :size, bytesDone = :size, bytesTotal = :size, downloadedAt = :at, error = NULL WHERE trackId = :trackId")
    suspend fun markDownloaded(trackId: String, path: String, size: Long, at: Long)

    @Query("UPDATE offline_tracks SET state = 'Failed', error = :error WHERE trackId = :trackId AND state != 'Downloaded'")
    suspend fun markFailed(trackId: String, error: String)

    @Query("UPDATE offline_tracks SET state = 'Queued', error = NULL WHERE trackId = :trackId AND state = 'Failed'")
    suspend fun markRetrying(trackId: String)

    @Query("SELECT trackId FROM offline_tracks WHERE state IN ('Queued', 'Downloading')")
    suspend fun pendingIds(): List<String>

    // ---- Collections -------------------------------------------------------------------------

    @Upsert
    suspend fun upsertCollection(collection: OfflineCollectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracksIfAbsent(tracks: List<OfflineTrackEntity>)

    @Query("DELETE FROM offline_collection_tracks WHERE collectionId = :collectionId")
    suspend fun clearCollectionTracks(collectionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollectionTracks(links: List<OfflineCollectionTrackEntity>)

    /** Creates or updates a collection and makes sure every track has a row (existing downloads are kept). */
    @Transaction
    suspend fun saveCollection(
        collection: OfflineCollectionEntity,
        tracks: List<OfflineTrackEntity>,
    ) {
        upsertCollection(collection)
        insertTracksIfAbsent(tracks)
        clearCollectionTracks(collection.id)
        insertCollectionTracks(tracks.mapIndexed { i, t -> OfflineCollectionTrackEntity(collection.id, t.trackId, i) })
    }

    @Query("SELECT trackId FROM offline_collection_tracks WHERE collectionId = :collectionId")
    suspend fun trackIdsOf(collectionId: String): List<String>

    @Query("DELETE FROM offline_collections WHERE id = :collectionId")
    suspend fun deleteCollection(collectionId: String)

    /** Tracks no collection needs any more. */
    @Query("SELECT * FROM offline_tracks WHERE trackId NOT IN (SELECT trackId FROM offline_collection_tracks)")
    suspend fun orphans(): List<OfflineTrackEntity>

    /** Removes a collection and returns the tracks that are no longer needed by anything. */
    @Transaction
    suspend fun removeCollection(collectionId: String): List<OfflineTrackEntity> {
        clearCollectionTracks(collectionId)
        deleteCollection(collectionId)
        val orphans = orphans()
        orphans.forEach { deleteByTrackId(it.trackId) }
        return orphans
    }

    @Query("SELECT id FROM offline_collections")
    suspend fun collectionIds(): List<String>

    @Query("SELECT id FROM offline_collections WHERE automatic = 1")
    suspend fun automaticIds(): List<String>

    @Query("SELECT automatic FROM offline_collections WHERE id = :id")
    suspend fun isAutomatic(id: String): Boolean?

    @Query(
        """SELECT c.id, c.kind, c.itemId, c.title, c.subtitle, c.coverArtId, c.requestedAt, c.automatic,
                  COUNT(t.trackId) AS totalTracks,
                  COALESCE(SUM(CASE WHEN t.state = 'Downloaded' THEN 1 ELSE 0 END), 0) AS downloadedTracks,
                  COALESCE(SUM(CASE WHEN t.state = 'Failed' THEN 1 ELSE 0 END), 0) AS failedTracks,
                  COALESCE(SUM(t.bytesDone), 0) AS bytesDownloaded,
                  COALESCE(SUM(t.bytesTotal), 0) AS bytesTotal
           FROM offline_collections c
           LEFT JOIN offline_collection_tracks ct ON ct.collectionId = c.id
           LEFT JOIN offline_tracks t ON t.trackId = ct.trackId
           GROUP BY c.id
           ORDER BY c.requestedAt DESC""",
    )
    fun observeCollections(): Flow<List<OfflineCollectionSummary>>

    /** Every downloaded track, newest first, with its genre from the library cache when known. */
    @Query(
        """SELECT t.*, COALESCE(
                  (SELECT genre FROM tracks WHERE id = t.trackId),
                  (SELECT genre FROM albums WHERE id = t.albumId)) AS genre
           FROM offline_tracks t
           WHERE t.state = 'Downloaded'
           ORDER BY t.downloadedAt DESC""",
    )
    fun observeDownloaded(): Flow<List<DownloadedTrackRow>>

    @Query("SELECT * FROM offline_tracks WHERE artistId = :artistId AND state = 'Downloaded' ORDER BY downloadedAt DESC")
    fun observeDownloadedByArtist(artistId: String): Flow<List<OfflineTrackEntity>>

    /** A collection's downloaded tracks, in order (for copying to a watch). */
    @Query("SELECT t.* FROM offline_tracks t INNER JOIN offline_collection_tracks ct ON ct.trackId = t.trackId WHERE ct.collectionId = :collectionId AND t.state = 'Downloaded' ORDER BY ct.position")
    suspend fun downloadedIn(collectionId: String): List<OfflineTrackEntity>

    @Query("SELECT t.* FROM offline_tracks t INNER JOIN offline_collection_tracks ct ON ct.trackId = t.trackId WHERE ct.collectionId = :collectionId AND t.state = 'Failed'")
    suspend fun failedIn(collectionId: String): List<OfflineTrackEntity>
}

data class UsageRow(val bytes: Long, val tracks: Int)

data class DownloadedTrackRow(@Embedded val track: OfflineTrackEntity, val genre: String?) {
    fun toTrack() = track.toTrack().copy(genre = genre)
}
