package app.rev.data.playback

import app.rev.data.remote.LibraryApi
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.playback.Autoplay
import app.rev.domain.playback.AutoplayRepository
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
        }
        if (online is Result.Ok && online.value.isNotEmpty()) return online.value
        return Autoplay.fromDownloads(plan, offline.observeDownloadedTracks().first())
    }
}
