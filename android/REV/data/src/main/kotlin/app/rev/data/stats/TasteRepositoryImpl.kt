package app.rev.data.stats

import app.rev.data.remote.LibraryApi
import app.rev.data.remote.RevJson
import app.rev.data.remote.TasteProfileDto
import app.rev.domain.model.Result
import app.rev.domain.taste.TasteProfile
import app.rev.domain.taste.TasteRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The taste profile from the server, refreshed when it's a few hours old and kept in a file so
 * the smart queue knows your taste offline too.
 */
class TasteRepositoryImpl(
    private val api: LibraryApi,
    private val cacheFile: File,
    private val timeZone: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : TasteRepository {
    private val lock = Mutex()
    private var profile: TasteProfile? = null
    private var fetchedAt = 0L
    private var triedAt = 0L

    override suspend fun profile(): TasteProfile = lock.withLock {
        val now = clock()
        if (profile == null) {
            readCache()?.let { (cached, at) ->
                profile = cached
                fetchedAt = at
            }
        }
        // Don't retry an unreachable server on every skip.
        if (now - fetchedAt > MAX_AGE_MS && now - triedAt > RETRY_MS) {
            triedAt = now
            val result = api.taste(timeZone())
            if (result is Result.Ok) {
                profile = result.value.toDomain()
                fetchedAt = now
                writeCache(result.value)
            }
        }
        profile ?: TasteProfile()
    }

    /** Forgets the profile (signed out, or another server). */
    suspend fun clear() = lock.withLock {
        profile = null
        fetchedAt = 0
        triedAt = 0
        withContext(Dispatchers.IO) { cacheFile.delete() }
    }

    private suspend fun readCache(): Pair<TasteProfile, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            RevJson.decodeFromString(TasteProfileDto.serializer(), cacheFile.readText()).toDomain() to cacheFile.lastModified()
        }.getOrNull()
    }

    private suspend fun writeCache(dto: TasteProfileDto) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(RevJson.encodeToString(TasteProfileDto.serializer(), dto))
            tmp.renameTo(cacheFile)
        }
    }

    private companion object {
        const val MAX_AGE_MS = 6 * 3_600_000L
        const val RETRY_MS = 10 * 60_000L
    }
}
