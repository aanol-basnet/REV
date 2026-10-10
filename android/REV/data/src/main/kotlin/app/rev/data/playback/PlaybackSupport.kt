package app.rev.data.playback

import app.rev.data.remote.toDto
import app.rev.domain.playback.ListenContext
import app.rev.data.local.offline.OfflineTrackDao
import app.rev.data.remote.LibraryApi
import app.rev.data.stats.ListenQueue
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import app.rev.domain.playback.ArtworkUrls
import app.rev.domain.playback.PlaybackReporter
import app.rev.domain.playback.TrackSource
import app.rev.domain.playback.TrackSourceResolver
import io.ktor.http.encodeURLPathPart
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Local first, then the server: a downloaded copy always wins, so a queue of downloaded tracks
 * keeps playing with no connection at all. Decided per track as it starts loading, so switching
 * between online and offline needs no "mode".
 */
class TrackSourceResolverImpl(
    private val offlineTracks: OfflineTrackDao,
    private val currentServer: () -> ServerUrl?,
) : TrackSourceResolver {
    override fun resolve(trackId: String): TrackSource? {
        val downloaded = runBlocking { offlineTracks.findByTrackId(trackId) }
        if (downloaded != null && File(downloaded.localUri).isFile) return TrackSource.Local(downloaded.localUri)
        val server = currentServer() ?: return null
        return TrackSource.Remote("${server.value}/api/library/tracks/${trackId.encodeURLPathPart()}/stream")
    }
}

class ArtworkUrlsImpl(private val currentServer: () -> ServerUrl?) : ArtworkUrls {
    override fun url(coverArtId: String, size: Int): String? =
        currentServer()?.let { "${it.value}/api/library/artwork/${coverArtId.encodeURLPathPart()}?size=$size" }
}

/**
 * "Now playing" is best effort; plays and skips go through [ListenQueue], so they reach the server
 * even when it can't be reached at the time. Listening never fails because of a report.
 */
class PlaybackReporterImpl(private val api: LibraryApi, private val listens: ListenQueue) : PlaybackReporter {
    override suspend fun nowPlaying(trackId: String) {
        if (api.scrobble(trackId, submission = false, playedAt = null) is Result.Ok) listens.flush()
    }

    override suspend fun played(trackId: String, startedAt: Long, context: ListenContext?) {
        listens.add(trackId, startedAt, kind = "play", listenedMs = null, context = context?.toDto())
    }

    override suspend fun skipped(trackId: String, startedAt: Long, listenedMs: Long, context: ListenContext?) {
        listens.add(trackId, startedAt, kind = "skip", listenedMs = listenedMs, context = context?.toDto())
    }
}
