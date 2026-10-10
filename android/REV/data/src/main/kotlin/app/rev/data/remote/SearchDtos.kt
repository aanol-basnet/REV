package app.rev.data.remote

import app.rev.domain.model.AlbumAvailability
import app.rev.domain.model.SearchAlbum
import app.rev.domain.model.SearchArtist
import app.rev.domain.model.SearchResults
import app.rev.domain.model.SourceStatus
import kotlinx.serialization.Serializable

// Wire types for /api/search, mirroring shared/schemas/src/search.ts.

@Serializable
internal data class SearchArtistDto(
    val name: String,
    val disambiguation: String? = null,
    val libraryId: String? = null,
    val coverArtId: String? = null,
    val catalogId: String? = null,
) {
    fun toDomain() = SearchArtist(name, disambiguation, libraryId, coverArtId, catalogId)
}

@Serializable
internal data class SearchAlbumDto(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val albumType: String? = null,
    val availability: String,
    val libraryId: String? = null,
    val coverArtId: String? = null,
    val catalogId: String? = null,
    val trackCount: Int? = null,
) {
    fun toDomain() = SearchAlbum(
        key, title, artist, year, albumType,
        // An unknown future state is shown as missing rather than crashing an older app.
        AlbumAvailability.entries.firstOrNull { it.name == availability } ?: AlbumAvailability.MISSING,
        libraryId, coverArtId, catalogId, trackCount,
    )
}

@Serializable
internal data class SearchSourcesDto(val library: String, val catalog: String)

@Serializable
internal data class SearchResponseDto(
    val query: String,
    val artists: List<SearchArtistDto>,
    val albums: List<SearchAlbumDto>,
    val tracks: List<TrackDto>,
    val sources: SearchSourcesDto,
) {
    fun toDomain() = SearchResults(
        query = query,
        artists = artists.map { it.toDomain() },
        albums = albums.map { it.toDomain() },
        tracks = tracks.map { it.toDomain() },
        library = sources.library.toSourceStatus(),
        catalog = sources.catalog.toSourceStatus(),
    )
}

private fun String.toSourceStatus() = when (this) {
    "ok" -> SourceStatus.Ok
    "not_configured" -> SourceStatus.NotConfigured
    else -> SourceStatus.Unavailable
}
