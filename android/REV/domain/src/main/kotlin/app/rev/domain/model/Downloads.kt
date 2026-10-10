package app.rev.domain.model

/** An album acquisition on the server. Mirrors shared/schemas `DownloadJob`. */
data class DownloadJob(
    val id: String,
    val catalogAlbumId: String,
    val catalogArtistId: String?,
    val title: String,
    val artist: String,
    val year: Int?,
    val state: DownloadJobState,
    /** 0–1 while downloading, when known. */
    val progress: Float?,
    val error: String?,
    val libraryAlbumId: String?,
    val startedAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
) {
    val isActive: Boolean get() = !state.isTerminal
}

/** A catalog album's page: what it is, whether the user has it, and its latest request. */
data class CatalogAlbumStatus(
    val catalogAlbumId: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val albumType: String?,
    val trackCount: Int?,
    val availability: AlbumAvailability,
    val libraryAlbumId: String?,
    val job: DownloadJob?,
)
