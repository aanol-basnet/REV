package app.rev.domain.repository

import app.rev.domain.model.CatalogAlbumStatus
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.Result

/** Getting albums the user doesn't have yet. Everything goes through the REV server. */
interface AcquisitionRepository {
    suspend fun albumStatus(catalogAlbumId: String): Result<CatalogAlbumStatus, LibraryError>

    /** Requests the album. Requesting an album that already has an active request returns that request. */
    suspend fun request(catalogAlbumId: String): Result<DownloadJob, LibraryError>

    suspend fun cancel(jobId: String): Result<DownloadJob, LibraryError>
}
