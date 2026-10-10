package app.rev.data.stats

import app.rev.data.remote.LibraryApi
import app.rev.data.remote.ListeningReportDto
import app.rev.data.remote.RevJson
import app.rev.data.remote.wire
import app.rev.domain.model.ListeningReport
import app.rev.domain.model.ReportPeriodKind
import app.rev.domain.model.Result
import app.rev.domain.model.SavedSmartPlaylist
import app.rev.domain.model.SmartPlaylist
import app.rev.domain.model.Track
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.StatsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reports come from the server. The latest report for each current period is kept in a file, as the
 * server sent it, so it can still be read offline.
 */
class StatsRepositoryImpl(
    private val api: LibraryApi,
    private val cacheDir: File,
) : StatsRepository {

    override suspend fun cachedReport(period: ReportPeriodKind): ListeningReport? = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile(period)
            if (!file.exists()) return@runCatching null
            RevJson.decodeFromString(ListeningReportDto.serializer(), file.readText()).toDomain()
        }.getOrNull()
    }

    override suspend fun report(period: ReportPeriodKind, offset: Int, timeZone: String): Result<ListeningReport, LibraryError> =
        when (val result = api.listeningReport(period.wire(), offset, timeZone)) {
            is Result.Ok -> {
                val report = result.value.toDomain()
                    ?: return Result.Err(LibraryError.Unexpected("Unsupported report"))
                if (offset == 0) save(period, result.value)
                Result.Ok(report)
            }
            is Result.Err -> result
        }

    override suspend fun smartPlaylists(timeZone: String): Result<List<SmartPlaylist>, LibraryError> =
        when (val result = api.smartPlaylists(timeZone)) {
            is Result.Ok -> Result.Ok(result.value.playlists.mapNotNull { it.toDomain() })
            is Result.Err -> result
        }

    override suspend fun smartPlaylistTracks(id: String, timeZone: String): Result<List<Track>, LibraryError> =
        api.smartPlaylistTracks(id, timeZone)

    override suspend fun saveSmartPlaylist(id: String, timeZone: String): Result<SavedSmartPlaylist, LibraryError> =
        when (val result = api.saveSmartPlaylist(id, timeZone)) {
            is Result.Ok -> Result.Ok(result.value.toDomain())
            is Result.Err -> result
        }

    private suspend fun save(period: ReportPeriodKind, dto: ListeningReportDto) = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            val file = cacheFile(period)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(RevJson.encodeToString(ListeningReportDto.serializer(), dto))
            tmp.renameTo(file)
        }
    }

    private fun cacheFile(period: ReportPeriodKind) = File(cacheDir, "report-${period.wire()}.json")
}
