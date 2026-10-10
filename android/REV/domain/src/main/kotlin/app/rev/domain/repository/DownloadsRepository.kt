package app.rev.domain.repository

import app.rev.domain.model.DownloadJob
import app.rev.domain.model.Result
import app.rev.domain.model.SystemStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Server-side album downloads, cached on the device and kept current by live events. */
interface DownloadsRepository {
    fun observeJobs(): Flow<List<DownloadJob>>

    /** The latest job for a catalog album, if any. */
    fun observeLatestJob(catalogAlbumId: String): Flow<DownloadJob?>

    suspend fun refresh(): RefreshResult

    /** Removes finished jobs (added, failed, cancelled) from the history; active ones stay. */
    suspend fun clearFinished(): Result<Unit, LibraryError>
}

interface SystemRepository {
    suspend fun status(): Result<SystemStatus, LibraryError>
}

interface ServerEvents {
    /** True while the live connection to the server is open. */
    val connected: StateFlow<Boolean>
}
