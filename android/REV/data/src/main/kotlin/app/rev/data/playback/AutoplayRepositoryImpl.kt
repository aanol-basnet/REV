package app.rev.data.playback

import app.rev.data.remote.LibraryApi
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.playback.Autoplay
import app.rev.domain.playback.AutoplayRepository
import app.rev.domain.playback.QueueSource
import app.rev.domain.taste.CandidateRequest
import app.rev.domain.taste.Candidates
import app.rev.domain.taste.TasteRanker
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.OfflineRepository
import kotlinx.coroutines.flow.first

/**
 * Asks the server what to play next; without a server (or an answer), picks from downloads. Similar
 * songs are ranked by the listening session and taste profile ([TasteRanker]).
 */
class AutoplayRepositoryImpl(
    private val api: LibraryApi,
    private val offline: OfflineRepository,
    private val ranker: TasteRanker,
) : AutoplayRepository {
    override suspend fun continuation(queue: List<Track>): List<Track> {
        val plan = Autoplay.plan(queue) ?: return emptyList()
        val online = when (plan) {
            is Autoplay.Plan.NextAlbum -> api.nextAlbum(plan.albumId, plan.artistId, plan.genre, plan.excludeAlbums)
            // A bigger pool than needed, so the best fits for the session can be picked.
            is Autoplay.Plan.Radio -> api.radio(plan.artistIds, plan.genres, plan.excludeTracks, size = Candidates.POOL_SIZE)
            // A server from before artist continuations answers with an error: a radio instead.
            is Autoplay.Plan.Artist -> api.artistContinuation(plan.artistId, plan.genres, plan.excludeTracks)
                .let { if (it is Result.Err && it.error != LibraryError.Unreachable) api.radio(listOf(plan.artistId), plan.genres, plan.excludeTracks) else it }
        }
        val songs = (online as? Result.Ok)?.value?.takeIf { it.isNotEmpty() }
            ?: Autoplay.fromDownloads(plan, offline.observeDownloadedTracks().first())
        // Similar songs go in the order that fits the session best; an album plays in order.
        return if (plan is Autoplay.Plan.NextAlbum) songs else ranker.rank(songs, source = QueueSource.Autoplay.wire).take(Autoplay.RADIO_SIZE)
    }

    override suspend fun candidates(request: CandidateRequest): List<Track> {
        val size = Candidates.POOL_SIZE
        val online = when (request) {
            is CandidateRequest.LikeTrack -> api.radio(
                listOfNotNull(request.seed.artistId),
                listOfNotNull(request.seed.genre?.takeIf(String::isNotBlank)),
                request.exclude,
                request.avoidArtists,
                size,
            )
            is CandidateRequest.ArtistSongs ->
                api.artistContinuation(request.artistId, request.genres, request.exclude, request.excludeAlbums, size)
            is CandidateRequest.Mood -> api.radio(request.artistIds, request.genres, request.exclude, request.avoidArtists, size)
            is CandidateRequest.NextAlbum -> api.nextAlbum(request.albumId, request.artistId, request.genre, request.excludeAlbums)
        }
        val songs = (online as? Result.Ok)?.value?.takeIf { it.isNotEmpty() }
            ?: Candidates.fromDownloads(request, offline.observeDownloadedTracks().first())
        // An older server ignores avoidArtists.
        return when (request) {
            is CandidateRequest.LikeTrack -> Candidates.withoutArtists(songs, request.avoidArtists)
            is CandidateRequest.Mood -> Candidates.withoutArtists(songs, request.avoidArtists)
            else -> songs
        }
    }
}
