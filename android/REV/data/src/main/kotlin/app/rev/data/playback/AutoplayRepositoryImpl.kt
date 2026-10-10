package app.rev.data.playback

import app.rev.data.remote.LibraryApi
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.playback.Autoplay
import app.rev.domain.playback.AutoplayRepository
import app.rev.domain.playback.SkipSteering
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.OfflineRepository
import kotlinx.coroutines.flow.first

/** Asks the server what to play next; without a server (or an answer), picks from downloads. */
class AutoplayRepositoryImpl(
    private val api: LibraryApi,
    private val offline: OfflineRepository,
) : AutoplayRepository {
    override suspend fun continuation(queue: List<Track>): List<Track> {
        val plan = Autoplay.plan(queue) ?: return emptyList()
        val online = when (plan) {
            is Autoplay.Plan.NextAlbum -> api.nextAlbum(plan.albumId, plan.artistId, plan.genre, plan.excludeAlbums)
            is Autoplay.Plan.Radio -> api.radio(plan.artistIds, plan.genres, plan.excludeTracks)
            // A server from before artist continuations answers with an error: a radio instead.
            is Autoplay.Plan.Artist -> api.artistContinuation(plan.artistId, plan.genres, plan.excludeTracks)
                .let { if (it is Result.Err && it.error != LibraryError.Unreachable) api.radio(listOf(plan.artistId), plan.genres, plan.excludeTracks) else it }
        }
        if (online is Result.Ok && online.value.isNotEmpty()) return online.value
        return Autoplay.fromDownloads(plan, offline.observeDownloadedTracks().first())
    }

    override suspend fun songsLike(seed: Track, queue: List<Track>, skipped: List<Track>): List<Track> {
        val plan = SkipSteering.plan(seed, queue)
        val online = api.radio(plan.artistIds, plan.genres, plan.excludeTracks)
        val songs = if (online is Result.Ok && online.value.isNotEmpty()) {
            online.value
        } else {
            SkipSteering.fromDownloads(plan, seed, offline.observeDownloadedTracks().first())
        }
        return SkipSteering.avoidSkipped(songs, seed, skipped)
    }
}
