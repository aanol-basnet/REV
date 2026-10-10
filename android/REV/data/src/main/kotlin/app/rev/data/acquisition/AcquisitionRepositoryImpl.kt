package app.rev.data.acquisition

import app.rev.data.downloads.DownloadsRepositoryImpl
import app.rev.data.remote.LibraryApi
import app.rev.domain.model.CatalogAlbumStatus
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.Result
import app.rev.domain.repository.AcquisitionRepository
import app.rev.domain.repository.LibraryError

/** Server calls for requesting albums; every job the server returns is also cached for the Downloads screen. */
class AcquisitionRepositoryImpl(
    private val api: LibraryApi,
    private val downloads: DownloadsRepositoryImpl,
) : AcquisitionRepository {
    override suspend fun albumStatus(catalogAlbumId: String): Result<CatalogAlbumStatus, LibraryError> =
        api.catalogAlbumStatus(catalogAlbumId).also { result ->
            if (result is Result.Ok) result.value.job?.let { downloads.store(it) }
        }

    override suspend fun request(catalogAlbumId: String): Result<DownloadJob, LibraryError> =
        api.requestDownload(catalogAlbumId).alsoStore()

    override suspend fun cancel(jobId: String): Result<DownloadJob, LibraryError> =
        api.cancelDownload(jobId).alsoStore()

    private suspend fun Result<DownloadJob, LibraryError>.alsoStore() = also {
        if (it is Result.Ok) downloads.store(it.value)
    }
}
