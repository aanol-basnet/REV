package app.rev.data.home

import app.rev.data.remote.HomeFeedDto
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.RevJson
import app.rev.domain.model.HomeFeed
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.repository.HomeRepository
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.RefreshResult
import app.rev.domain.model.forHour
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the last Home feed in a file, as the server sent it, so Home opens instantly and still
 * shows something offline. It's one document that is always replaced whole, so a database table
 * would add nothing.
 */
class HomeRepositoryImpl(
    private val api: LibraryApi,
    private val cacheFile: File,
    private val timeZone: () -> String = { TimeZone.getDefault().id },
    private val hour: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
) : HomeRepository {
    private val feed = MutableStateFlow<HomeFeed?>(null)
    private val loadLock = Mutex()
    private var loaded = false

    /** The time-of-day mix only while it's that time: a cached morning feed doesn't offer it in the evening. */
    override fun observeFeed(): Flow<HomeFeed?> = feed.map { it?.forHour(hour()) }.onStart { loadCache() }

    override suspend fun refresh(force: Boolean): RefreshResult = when (val result = api.home(force, timeZone())) {
        is Result.Ok -> {
            loadLock.withLock { loaded = true }
            feed.value = result.value.toDomain()
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(cacheFile.path + ".tmp")
                    tmp.writeText(RevJson.encodeToString(HomeFeedDto.serializer(), result.value))
                    tmp.renameTo(cacheFile)
                }
            }
            Result.Ok(Unit)
        }
        is Result.Err -> result
    }

    override suspend fun mixTracks(mixId: String): Result<List<Track>, LibraryError> = api.mixTracks(mixId, timeZone())

    /** Forgets the cached feed (another server's recommendations). */
    suspend fun clear() {
        loadLock.withLock { loaded = true }
        feed.value = null
        withContext(Dispatchers.IO) { cacheFile.delete() }
    }

    private suspend fun loadCache() = loadLock.withLock {
        if (loaded) return@withLock
        loaded = true
        val cached = withContext(Dispatchers.IO) {
            runCatching { RevJson.decodeFromString(HomeFeedDto.serializer(), cacheFile.readText()).toDomain() }.getOrNull()
        }
        if (feed.value == null) feed.value = cached
    }
}
