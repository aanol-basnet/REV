package app.rev.domain.repository

import app.rev.domain.model.Lyrics
import app.rev.domain.model.Result

/**
 * Lyrics for a track: from the device cache when present (so they work offline), otherwise from
 * the REV server, then cached. The server does provider lookup and matching.
 */
interface LyricsRepository {
    suspend fun lyrics(trackId: String): Result<Lyrics, LibraryError>

    /** Fetches and caches lyrics ahead of time, e.g. for downloaded tracks. Failures are ignored. */
    suspend fun prefetch(trackId: String)
}
