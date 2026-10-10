package app.rev.domain.taste

import app.rev.domain.model.Track
import app.rev.domain.playback.Autoplay
import kotlin.random.Random

/** Picks the smart queue's candidates from downloaded songs, for when there's no server. */
object Candidates {
    /** How many songs to fetch for ranking; the best [QUEUE_SIZE] go into the queue. */
    const val POOL_SIZE = 60
    const val QUEUE_SIZE = 25

    fun fromDownloads(request: CandidateRequest, downloaded: List<Track>, random: Random = Random): List<Track> {
        val excluded = request.exclude.toSet()
        val fresh = downloaded.filter { it.id !in excluded }
        return when (request) {
            is CandidateRequest.NextAlbum -> Autoplay.fromDownloads(
                Autoplay.Plan.NextAlbum(request.albumId, request.artistId, request.genre, request.excludeAlbums),
                fresh,
                random,
            )
            is CandidateRequest.ArtistSongs -> {
                val skipAlbums = request.excludeAlbums.toSet()
                val theirs = fresh.filter { it.artistId == request.artistId && it.albumId !in skipAlbums }
                theirs.ifEmpty {
                    val genres = request.genres.map { it.lowercase() }.toSet()
                    fresh.filter { it.artistId != request.artistId && it.genre?.lowercase() in genres }
                }.shuffled(random).take(POOL_SIZE)
            }
            is CandidateRequest.LikeTrack -> {
                val seed = request.seed
                val allowed = withoutArtists(fresh.filter { it.id != seed.id }, request.avoidArtists)
                val genre = seed.genre?.lowercase()
                val sameArtist = allowed.filter { seed.artistId != null && it.artistId == seed.artistId }.shuffled(random).take(MAX_SAME_ARTIST)
                val sameGenre = allowed.filter { it !in sameArtist && genre != null && it.genre?.lowercase() == genre }
                val sameEra = allowed.filter { seed.year != null && it.year != null && decadeOf(it.year) == decadeOf(seed.year) }
                (sameArtist + sameGenre.shuffled(random) + sameEra.shuffled(random) + allowed.shuffled(random))
                    .distinctBy { it.id }.take(POOL_SIZE)
            }
            is CandidateRequest.Mood -> {
                val allowed = withoutArtists(fresh, request.avoidArtists)
                val genres = request.genres.map { it.lowercase() }.toSet()
                val (related, rest) = allowed.partition { it.artistId in request.artistIds || it.genre?.lowercase() in genres }
                (related.shuffled(random) + rest.shuffled(random)).take(POOL_SIZE)
            }
        }
    }

    /** [tracks] without [artists], unless that leaves too few to make a queue. */
    fun withoutArtists(tracks: List<Track>, artists: List<String>): List<Track> {
        if (artists.isEmpty()) return tracks
        val avoid = artists.toSet()
        val kept = tracks.filter { it.artistId == null || it.artistId !in avoid }
        return if (kept.size >= MIN_KEPT || kept.size == tracks.size) kept else tracks
    }

    private const val MAX_SAME_ARTIST = 6
    private const val MIN_KEPT = 10
}
