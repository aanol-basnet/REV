package app.rev.data.remote

import app.rev.domain.model.AlbumAvailability
import app.rev.domain.model.CatalogAlbumStatus
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.DownloadJobState
import kotlinx.serialization.Serializable

// Wire types for /api/downloads and /api/catalog, mirroring shared/schemas/src/downloads.ts.

@Serializable
internal data class DownloadJobDto(
    val id: String,
    val catalogAlbumId: String,
    val catalogArtistId: String? = null,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val state: String,
    val progress: Float? = null,
    val error: String? = null,
    val libraryAlbumId: String? = null,
    val startedAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
) {
    fun toDomain() = DownloadJob(
        id, catalogAlbumId, catalogArtistId, title, artist, year,
        // A state this app version doesn't know is treated as still in progress.
        DownloadJobState.entries.firstOrNull { it.name == state } ?: DownloadJobState.CHECKING,
        progress, error, libraryAlbumId, startedAt, updatedAt, completedAt,
    )
}

@Serializable
internal data class DownloadJobListDto(val jobs: List<DownloadJobDto>)

@Serializable
internal data class CreateDownloadDto(val catalogAlbumId: String)

@Serializable
internal data class CatalogAlbumStatusDto(
    val catalogAlbumId: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val albumType: String? = null,
    val trackCount: Int? = null,
    val availability: String,
    val libraryAlbumId: String? = null,
    val job: DownloadJobDto? = null,
) {
    fun toDomain() = CatalogAlbumStatus(
        catalogAlbumId, title, artist, year, albumType, trackCount,
        AlbumAvailability.entries.firstOrNull { it.name == availability } ?: AlbumAvailability.MISSING,
        libraryAlbumId, job?.toDomain(),
    )
}
