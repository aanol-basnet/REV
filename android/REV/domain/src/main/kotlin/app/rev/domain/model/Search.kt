package app.rev.domain.model

/** Normalized album availability, computed by the server. Mirrors shared/schemas `AlbumAvailability`. */
enum class AlbumAvailability {
    AVAILABLE,
    IN_LIBRARY,
    MISSING,
    REQUESTED,
    DOWNLOADING,
    IMPORTING,
    INDEXING,
    FAILED,
}

data class SearchArtist(
    val name: String,
    val disambiguation: String?,
    /** Set when the artist is in the playable library. */
    val libraryId: String?,
    val coverArtId: String?,
    /** MusicBrainz artist id from the catalog. */
    val catalogId: String?,
)

data class SearchAlbum(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val albumType: String?,
    val availability: AlbumAvailability,
    /** Set when playable. */
    val libraryId: String?,
    val coverArtId: String?,
    /** MusicBrainz release-group id; needed to request the album. */
    val catalogId: String?,
    val trackCount: Int?,
)

enum class SourceStatus { Ok, Unavailable, NotConfigured }

data class SearchResults(
    val query: String,
    val artists: List<SearchArtist>,
    val albums: List<SearchAlbum>,
    val tracks: List<Track>,
    val library: SourceStatus,
    val catalog: SourceStatus,
    /** Searched on the device (cached library, downloads) because the server couldn't be reached. */
    val onDevice: Boolean = false,
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}
