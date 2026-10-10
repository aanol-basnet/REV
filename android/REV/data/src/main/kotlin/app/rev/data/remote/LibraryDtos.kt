package app.rev.data.remote

import app.rev.domain.model.Album
import app.rev.domain.model.AlbumDetail
import app.rev.domain.model.Artist
import app.rev.domain.model.ArtistDetail
import app.rev.domain.model.Favorites
import app.rev.domain.model.Genre
import app.rev.domain.model.Playlist
import app.rev.domain.model.ReplayGain
import app.rev.domain.model.PlaylistDetail
import app.rev.domain.model.Track
import kotlinx.serialization.Serializable

// Wire types for /api/library, mirroring shared/schemas/src/library.ts.

@Serializable
internal data class ArtistDto(
    val id: String,
    val name: String,
    val albumCount: Int,
    val coverArtId: String? = null,
    val starred: Boolean,
) {
    fun toDomain() = Artist(id, name, albumCount, coverArtId, starred)
}

@Serializable
internal data class AlbumDto(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArtId: String? = null,
    val trackCount: Int,
    val durationMs: Long,
    val starred: Boolean,
    val createdAt: Long? = null,
    val playedAt: Long? = null,
    val releaseTypes: List<String> = emptyList(),
) {
    fun toDomain() = Album(
        id, name, artist, artistId, year, genre, coverArtId, trackCount, durationMs, starred, createdAt, playedAt,
        releaseTypes,
    )
}

@Serializable
internal data class TopTracksDto(val tracks: List<TrackDto>)

@Serializable
internal data class ReplayGainDto(
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
) {
    fun toDomain() = ReplayGain(trackGainDb, albumGainDb, trackPeak, albumPeak)
}

internal fun ReplayGain.toDto() = ReplayGainDto(trackGainDb, albumGainDb, trackPeak, albumPeak)

@Serializable
internal data class TrackDto(
    val id: String,
    val title: String,
    val album: String,
    val albumId: String? = null,
    val artist: String,
    val artistId: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArtId: String? = null,
    val durationMs: Long,
    val starred: Boolean,
    val format: String? = null,
    val bitRateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val sizeBytes: Long? = null,
    val replayGain: ReplayGainDto? = null,
) {
    fun toDomain() = Track(
        id, title, album, albumId, artist, artistId, trackNumber, discNumber, year, genre, coverArtId,
        durationMs, starred, format, bitRateKbps, sampleRateHz, bitDepth, sizeBytes, replayGain?.toDomain(),
    )
}

internal fun Track.toDto() = TrackDto(
    id, title, album, albumId, artist, artistId, trackNumber, discNumber, year, genre, coverArtId,
    durationMs, starred, format, bitRateKbps, sampleRateHz, bitDepth, sizeBytes, replayGain?.toDto(),
)

@Serializable
internal data class ScrobbleRequestDto(val submission: Boolean, val playedAt: Long? = null)

@Serializable
internal data class CreatePlaylistDto(val name: String, val trackIds: List<String>)

@Serializable
internal data class UpdatePlaylistDto(val name: String? = null, val comment: String? = null)

@Serializable
internal data class AddPlaylistTracksDto(val trackIds: List<String>, val skipDuplicates: Boolean = true)

@Serializable
internal data class AddPlaylistTracksResultDto(val playlist: PlaylistDetailDto, val added: Int, val skipped: Int)

@Serializable
internal data class SetPlaylistTracksDto(val trackIds: List<String>)

@Serializable
internal data class GenreDto(val name: String, val albumCount: Int, val trackCount: Int) {
    fun toDomain() = Genre(name, albumCount, trackCount)
}

@Serializable
internal data class ArtistDetailDto(
    val id: String,
    val name: String,
    val albumCount: Int,
    val coverArtId: String? = null,
    val starred: Boolean,
    val albums: List<AlbumDto>,
) {
    fun toDomain() = ArtistDetail(
        Artist(id, name, albumCount, coverArtId, starred),
        albums.map { it.toDomain() },
    )
}

@Serializable
internal data class AlbumDetailDto(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArtId: String? = null,
    val trackCount: Int,
    val durationMs: Long,
    val starred: Boolean,
    val createdAt: Long? = null,
    val playedAt: Long? = null,
    val tracks: List<TrackDto>,
) {
    fun toDomain() = AlbumDetail(
        Album(id, name, artist, artistId, year, genre, coverArtId, trackCount, durationMs, starred, createdAt, playedAt),
        tracks.map { it.toDomain() },
    )
}

@Serializable
internal data class ArtistListDto(val artists: List<ArtistDto>)

@Serializable
internal data class GenreListDto(val genres: List<GenreDto>)

@Serializable
internal data class AlbumPageDto(val albums: List<AlbumDto>, val offset: Int, val hasMore: Boolean)

@Serializable
internal data class TrackPageDto(val tracks: List<TrackDto>, val offset: Int, val hasMore: Boolean)

@Serializable
internal data class FavoritesDto(
    val artists: List<ArtistDto>,
    val albums: List<AlbumDto>,
    val tracks: List<TrackDto>,
) {
    fun toDomain() = Favorites(artists.map { it.toDomain() }, albums.map { it.toDomain() }, tracks.map { it.toDomain() })
}

@Serializable
internal data class PlaylistDto(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val trackCount: Int,
    val durationMs: Long,
    val coverArtId: String? = null,
    val updatedAt: Long? = null,
    /** Absent from servers that can't edit playlists. */
    val canEdit: Boolean = false,
) {
    fun toDomain() = Playlist(id, name, comment, owner, trackCount, durationMs, coverArtId, updatedAt, canEdit)
}

@Serializable
internal data class PlaylistListDto(val playlists: List<PlaylistDto>)

@Serializable
internal data class PlaylistDetailDto(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val trackCount: Int,
    val durationMs: Long,
    val coverArtId: String? = null,
    val updatedAt: Long? = null,
    val canEdit: Boolean = false,
    val tracks: List<TrackDto>,
) {
    fun toDomain() = PlaylistDetail(
        Playlist(id, name, comment, owner, trackCount, durationMs, coverArtId, updatedAt, canEdit),
        tracks.map { it.toDomain() },
    )
}
