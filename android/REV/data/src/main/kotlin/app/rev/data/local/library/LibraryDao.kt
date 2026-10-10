package app.rev.data.local.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    // ---- Upserts -----------------------------------------------------------------------------

    @Upsert
    suspend fun upsertArtists(artists: List<ArtistEntity>)

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Upsert
    suspend fun upsertAlbumRows(albums: List<AlbumEntity>)

    /** Keeps an album's tracks-cached marker, which list responses don't know about. */
    @Transaction
    suspend fun upsertAlbums(albums: List<AlbumEntity>) {
        if (albums.isEmpty()) return
        val cachedAt = tracksCachedAt(albums.map { it.id }).associate { it.id to it.tracksCachedAt }
        upsertAlbumRows(albums.map { it.copy(tracksCachedAt = it.tracksCachedAt ?: cachedAt[it.id]) })
    }

    @Query("SELECT id, tracksCachedAt FROM albums WHERE id IN (:ids)")
    suspend fun tracksCachedAt(ids: List<String>): List<AlbumTracksCachedAt>

    // ---- Lists -------------------------------------------------------------------------------

    @Query("DELETE FROM list_entries WHERE listKey = :listKey")
    suspend fun clearList(listKey: String)

    @Insert
    suspend fun insertListEntries(entries: List<ListEntryEntity>)

    @Query("SELECT itemId FROM list_entries WHERE listKey = :listKey ORDER BY position")
    suspend fun listItemIds(listKey: String): List<String>

    @Transaction
    suspend fun replaceList(listKey: String, itemIds: List<String>) {
        clearList(listKey)
        insertListEntries(itemIds.mapIndexed { i, id -> ListEntryEntity(listKey, i, id) })
    }

    @Query(
        """SELECT artists.* FROM artists
           INNER JOIN list_entries ON list_entries.itemId = artists.id
           WHERE list_entries.listKey = :listKey ORDER BY list_entries.position""",
    )
    fun observeArtistList(listKey: String): Flow<List<ArtistEntity>>

    @Query(
        """SELECT albums.* FROM albums
           INNER JOIN list_entries ON list_entries.itemId = albums.id
           WHERE list_entries.listKey = :listKey ORDER BY list_entries.position""",
    )
    fun observeAlbumList(listKey: String): Flow<List<AlbumEntity>>

    @Query(
        """SELECT tracks.* FROM tracks
           INNER JOIN list_entries ON list_entries.itemId = tracks.id
           WHERE list_entries.listKey = :listKey ORDER BY list_entries.position""",
    )
    fun observeTrackList(listKey: String): Flow<List<TrackEntity>>

    @Query(
        """SELECT playlists.* FROM playlists
           INNER JOIN list_entries ON list_entries.itemId = playlists.id
           WHERE list_entries.listKey = :listKey ORDER BY list_entries.position""",
    )
    fun observePlaylistList(listKey: String): Flow<List<PlaylistEntity>>

    @Upsert
    suspend fun upsertPlaylists(playlists: List<PlaylistEntity>)

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: String): Flow<PlaylistEntity?>

    // ---- Single items ------------------------------------------------------------------------

    @Query("SELECT * FROM artists WHERE id = :id")
    fun observeArtist(id: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM albums WHERE id = :id")
    fun observeAlbum(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY COALESCE(discNumber, 1), COALESCE(trackNumber, 0), title")
    fun observeAlbumTracks(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun track(id: String): TrackEntity?

    /** Replaces an album's cached tracks, dropping any the server no longer lists. */
    @Transaction
    suspend fun replaceAlbumTracks(albumId: String, tracks: List<TrackEntity>) {
        deleteAlbumTracksExcept(albumId, tracks.map { it.id })
        upsertTracks(tracks)
    }

    @Query("DELETE FROM tracks WHERE albumId = :albumId AND id NOT IN (:keep)")
    suspend fun deleteAlbumTracksExcept(albumId: String, keep: List<String>)

    // ---- Genres ------------------------------------------------------------------------------

    @Query("SELECT * FROM genres ORDER BY name COLLATE NOCASE")
    fun observeGenres(): Flow<List<GenreEntity>>

    @Query("DELETE FROM genres")
    suspend fun clearGenres()

    @Insert
    suspend fun insertGenres(genres: List<GenreEntity>)

    @Transaction
    suspend fun replaceGenres(genres: List<GenreEntity>) {
        clearGenres()
        insertGenres(genres)
    }

    // ---- Maintenance -------------------------------------------------------------------------

    @Query("DELETE FROM artists")
    suspend fun clearArtists()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("DELETE FROM list_entries")
    suspend fun clearLists()

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    /** Forgets everything cached from the server, e.g. when switching to a different server. */
    @Transaction
    suspend fun clearAll() {
        clearLists()
        clearTracks()
        clearAlbums()
        clearArtists()
        clearGenres()
        clearPlaylists()
    }

    // ---- Favorites ---------------------------------------------------------------------------

    @Query("SELECT * FROM artists WHERE starred = 1 ORDER BY name COLLATE NOCASE")
    fun observeStarredArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM albums WHERE starred = 1 ORDER BY name COLLATE NOCASE")
    fun observeStarredAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM tracks WHERE starred = 1 ORDER BY title COLLATE NOCASE")
    fun observeStarredTracks(): Flow<List<TrackEntity>>

    @Query("UPDATE artists SET starred = :starred WHERE id = :id")
    suspend fun setArtistStarred(id: String, starred: Boolean)

    @Query("UPDATE albums SET starred = :starred WHERE id = :id")
    suspend fun setAlbumStarred(id: String, starred: Boolean)

    @Query("UPDATE tracks SET starred = :starred WHERE id = :id")
    suspend fun setTrackStarred(id: String, starred: Boolean)

    @Query("UPDATE artists SET starred = 0 WHERE starred = 1 AND id NOT IN (:keep)")
    suspend fun unstarArtistsExcept(keep: List<String>)

    @Query("UPDATE albums SET starred = 0 WHERE starred = 1 AND id NOT IN (:keep)")
    suspend fun unstarAlbumsExcept(keep: List<String>)

    @Query("UPDATE tracks SET starred = 0 WHERE starred = 1 AND id NOT IN (:keep)")
    suspend fun unstarTracksExcept(keep: List<String>)

    /** Makes the cached starred flags match the server's favorites exactly. */
    @Transaction
    suspend fun replaceFavorites(artists: List<ArtistEntity>, albums: List<AlbumEntity>, tracks: List<TrackEntity>) {
        upsertArtists(artists)
        upsertAlbums(albums)
        upsertTracks(tracks)
        unstarArtistsExcept(artists.map { it.id })
        unstarAlbumsExcept(albums.map { it.id })
        unstarTracksExcept(tracks.map { it.id })
    }
}

data class AlbumTracksCachedAt(val id: String, val tracksCachedAt: Long?)
