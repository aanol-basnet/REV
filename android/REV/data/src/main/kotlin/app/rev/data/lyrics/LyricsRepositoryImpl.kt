package app.rev.data.lyrics

import app.rev.data.local.lyrics.LyricsDao
import app.rev.data.local.lyrics.LyricsEntity
import app.rev.data.remote.LibraryApi
import app.rev.data.remote.LyricLineDto
import app.rev.data.remote.RevJson
import app.rev.domain.model.LyricLine
import app.rev.domain.model.Lyrics
import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LyricsRepository
import kotlinx.serialization.builtins.ListSerializer

class LyricsRepositoryImpl(
    private val api: LibraryApi,
    private val dao: LyricsDao,
    private val now: () -> Long = System::currentTimeMillis,
) : LyricsRepository {

    private val linesSerializer = ListSerializer(LyricLineDto.serializer())

    override suspend fun lyrics(trackId: String): Result<Lyrics, LibraryError> {
        val cached = dao.get(trackId)
        if (cached != null && !cached.isStaleNegative()) return Result.Ok(cached.toDomain())
        return when (val remote = api.lyrics(trackId)) {
            is Result.Ok -> {
                val entity = LyricsEntity(
                    trackId = trackId,
                    status = remote.value.status,
                    syncedJson = remote.value.synced?.let { RevJson.encodeToString(linesSerializer, it) },
                    plain = remote.value.plain,
                    fetchedAt = now(),
                )
                dao.upsert(entity)
                Result.Ok(entity.toDomain())
            }
            // Offline with a stale "none" cached: still a reasonable answer.
            is Result.Err -> cached?.let { Result.Ok(it.toDomain()) } ?: remote
        }
    }

    override suspend fun prefetch(trackId: String) {
        lyrics(trackId)
    }

    /** "No lyrics" is worth asking about again later; real lyrics never go stale. */
    private fun LyricsEntity.isStaleNegative() = status == "not_found" && now() - fetchedAt > NOT_FOUND_TTL_MS

    private fun LyricsEntity.toDomain(): Lyrics {
        val lines = syncedJson?.let { json ->
            runCatching { RevJson.decodeFromString(linesSerializer, json) }.getOrNull()
        }
        return when {
            !lines.isNullOrEmpty() -> Lyrics.Synced(lines.map { LyricLine(it.startMs, it.text) })
            !plain.isNullOrBlank() -> Lyrics.Plain(plain)
            status == "instrumental" -> Lyrics.Instrumental
            else -> Lyrics.None
        }
    }

    private companion object {
        const val NOT_FOUND_TTL_MS = 7L * 24 * 3_600_000
    }
}
