package app.rev.data.downloads

import app.rev.data.local.downloads.DownloadJobDao
import app.rev.data.local.downloads.toEntity
import app.rev.data.remote.LibraryApi
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.Result
import app.rev.domain.model.SystemStatus
import app.rev.domain.repository.DownloadsRepository
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.RefreshResult
import app.rev.domain.repository.SystemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DownloadsRepositoryImpl(
    private val api: LibraryApi,
    private val dao: DownloadJobDao,
) : DownloadsRepository {
    override fun observeJobs(): Flow<List<DownloadJob>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override fun observeLatestJob(catalogAlbumId: String): Flow<DownloadJob?> =
        dao.observeLatest(catalogAlbumId).map { it?.toDomain() }.distinctUntilChanged()

    override suspend fun refresh(): RefreshResult = when (val result = api.downloads(activeOnly = false)) {
        is Result.Ok -> {
            dao.replaceAll(result.value.map { it.toEntity() })
            Result.Ok(Unit)
        }
        is Result.Err -> result
    }

    override suspend fun clearFinished(): Result<Unit, LibraryError> = when (val result = api.clearFinishedDownloads()) {
        is Result.Ok -> {
            dao.deleteFinished()
            Result.Ok(Unit)
        }
        is Result.Err -> result
    }

    /** Records a job the server just returned (request, cancel, live event). */
    suspend fun store(job: DownloadJob) = dao.upsertIfNewer(job.toEntity())
}

class SystemRepositoryImpl(private val api: LibraryApi) : SystemRepository {
    override suspend fun status(): Result<SystemStatus, LibraryError> = api.systemStatus()
}
