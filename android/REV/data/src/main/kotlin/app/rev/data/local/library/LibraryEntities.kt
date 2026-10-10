package app.rev.data.local.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.rev.domain.model.Album
import app.rev.domain.model.Artist
import app.rev.domain.model.Genre
import app.rev.domain.model.Playlist
import app.rev.domain.model.ReplayGain
import app.rev.domain.model.Track

// On-device cache of library metadata from the REV server. Navidrome remains the source of truth;
// these tables only make browsing fast and keep previously seen items available offline.

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val albumCount: Int,
    val coverArtId: String?,
    val starred: Boolean,
) {
    fun toDomain() = Artist(id, name, albumCount, coverArtId, starred)
}

@Entity(tableName = "albums", indices = [Index("artistId")])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val artist: String,
    val artistId: String?,
    val year: Int?,
    val genre: String?,
    val coverArtId: String?,
    val trackCount: Int,
    val durationMs: Long,
    val starred: Boolean,
    val createdAt: Long?,
    val playedAt: Long?,
    /** Set once the album's tracks have been cached. */
    val tracksCachedAt: Long?,
    /** Release types, comma-separated. */
    @ColumnInfo(defaultValue = "") val releaseTypes: String = "",
) {
    fun toDomain() = Album(
        id, name, artist, artistId, year, genre, coverArtId, trackCount, durationMs, starred, createdAt, playedAt,
        releaseTypes.split(',').filter { it.isNotBlank() },
    )
}

@Entity(tableName = "tracks", indices = [Index("albumId")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val album: String,
    val albumId: String?,
    val artist: String,
    val artistId: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val genre: String?,
    val coverArtId: String?,
    val durationMs: Long,
    val starred: Boolean,
    val format: String?,
    val bitRateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val sizeBytes: Long?,
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
) {
    fun toDomain() = Track(
        id, title, album, albumId, artist, artistId, trackNumber, discNumber, year, genre, coverArtId,
        durationMs, starred, format, bitRateKbps, sampleRateHz, bitDepth, sizeBytes,
        replayGainOf(trackGainDb, albumGainDb, trackPeak, albumPeak),
    )
}

internal fun replayGainOf(trackGainDb: Double?, albumGainDb: Double?, trackPeak: Double?, albumPeak: Double?) =
    if (trackGainDb == null && albumGainDb == null) null else ReplayGain(trackGainDb, albumGainDb, trackPeak, albumPeak)

@Entity(tableName = "genres")
data class GenreEntity(
    @PrimaryKey val name: String,
    val albumCount: Int,
    val trackCount: Int,
) {
    fun toDomain() = Genre(name, albumCount, trackCount)
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val comment: String?,
    val owner: String?,
    val trackCount: Int,
    val durationMs: Long,
    val coverArtId: String?,
    val updatedAt: Long?,
    @ColumnInfo(defaultValue = "0") val canEdit: Boolean = false,
) {
    fun toDomain() = Playlist(id, name, comment, owner, trackCount, durationMs, coverArtId, updatedAt, canEdit)
}

fun Playlist.toEntity() = PlaylistEntity(id, name, comment, owner, trackCount, durationMs, coverArtId, updatedAt, canEdit)

/**
 * Ordered membership of a cached list, e.g. all artists A–Z, recently added albums, or the albums
 * shown on one artist's page. Keys are built by [ListKeys].
 */
@Entity(tableName = "list_entries", primaryKeys = ["listKey", "position"], indices = [Index("itemId")])
data class ListEntryEntity(
    val listKey: String,
    val position: Int,
    val itemId: String,
)

object ListKeys {
    const val ARTISTS = "artists"
    fun albums(type: String) = "albums:$type"
    fun artistAlbums(artistId: String) = "artist:$artistId"
    const val PLAYLISTS = "playlists"
    fun playlistTracks(playlistId: String) = "playlist:$playlistId"
}

fun Artist.toEntity() = ArtistEntity(id, name, albumCount, coverArtId, starred)

fun Album.toEntity(tracksCachedAt: Long? = null) = AlbumEntity(
    id, name, artist, artistId, year, genre, coverArtId, trackCount, durationMs, starred, createdAt, playedAt,
    tracksCachedAt, releaseTypes.joinToString(","),
)

fun Track.toEntity() = TrackEntity(
    id, title, album, albumId, artist, artistId, trackNumber, discNumber, year, genre, coverArtId,
    durationMs, starred, format, bitRateKbps, sampleRateHz, bitDepth, sizeBytes,
    replayGain?.trackGainDb, replayGain?.albumGainDb, replayGain?.trackPeak, replayGain?.albumPeak,
)

fun Genre.toEntity() = GenreEntity(name, albumCount, trackCount)
