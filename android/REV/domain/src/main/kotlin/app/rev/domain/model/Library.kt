package app.rev.domain.model

// REV's library model, mirroring shared/schemas/src/library.ts. Durations in ms, timestamps epoch ms.

data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int,
    val coverArtId: String?,
    val starred: Boolean,
)

data class Album(
    val id: String,
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
    /** Lowercased release types from the tags: `album`, `ep`, `single`, `compilation`, `live`… */
    val releaseTypes: List<String> = emptyList(),
) {
    /** How an artist page groups this release. */
    val releaseKind: ReleaseKind
        get() = when {
            "compilation" in releaseTypes -> ReleaseKind.Compilation
            "live" in releaseTypes -> ReleaseKind.Live
            "ep" in releaseTypes -> ReleaseKind.Ep
            "single" in releaseTypes -> ReleaseKind.Single
            else -> ReleaseKind.Album
        }
}

/** Groups on an artist page, in display order. Untagged releases count as albums. */
enum class ReleaseKind { Album, Ep, Single, Live, Compilation }

data class Track(
    val id: String,
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
    /** Loudness data from the file's tags; null when the file has none. */
    val replayGain: ReplayGain? = null,
)

/** ReplayGain values: gains in dB, peaks as linear sample amplitude (1.0 = full scale). */
data class ReplayGain(
    val trackGainDb: Double?,
    val albumGainDb: Double?,
    val trackPeak: Double?,
    val albumPeak: Double?,
)

data class Genre(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
)

data class ArtistDetail(val artist: Artist, val albums: List<Album>)

data class AlbumDetail(val album: Album, val tracks: List<Track>) {
    /** True when tracks span more than one disc, so the UI should show disc headers. */
    val isMultiDisc: Boolean get() = tracks.mapNotNull { it.discNumber }.distinct().size > 1
}

data class Favorites(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/** Album lists cached for browsing. */
enum class AlbumListType {
    /** The whole library, A–Z. */
    Alphabetical,

    /** Recently added. */
    Newest,

    /** Recently played. */
    Recent,
}

enum class FavoriteKind { Artist, Album, Track }

data class Page<T>(val items: List<T>, val offset: Int, val hasMore: Boolean)

data class Playlist(
    val id: String,
    val name: String,
    val comment: String?,
    val owner: String?,
    val trackCount: Int,
    val durationMs: Long,
    val coverArtId: String?,
    val updatedAt: Long?,
    /** It belongs to REV's Navidrome account, so it can be changed; other users' are read-only. */
    val canEdit: Boolean = false,
)

data class PlaylistDetail(val playlist: Playlist, val tracks: List<Track>)
