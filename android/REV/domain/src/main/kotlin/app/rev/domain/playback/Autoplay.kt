package app.rev.domain.playback

import app.rev.domain.model.Track
import app.rev.domain.offline.OfflineMixes
import kotlin.random.Random

/** Keeps the music going when the queue runs out (Settings → Playback → Autoplay). */
interface AutoplayRepository {
    /** What to append after [queue]: the next album, or songs like the queue's. Empty when nothing fits. */
    suspend fun continuation(queue: List<Track>): List<Track>
}

/**
 * Decides how to continue a queue, and continues it from downloads when the server can't help.
 * A queue made of whole albums (each album's songs together) continues with another album; a
 * queue of one artist's songs (their top songs) continues with the rest of their songs, then
 * similar artists; any other queue (a playlist, a mix, search results) continues with similar songs.
 */
object Autoplay {
    const val RADIO_SIZE = 25

    /** An album needs this many songs in a row to count as one, so a playlist isn't mistaken for it. */
    private const val MIN_ALBUM_RUN = 3
    private const val SEED_WINDOW = 30
    private const val MAX_EXCLUDED_TRACKS = 1000

    sealed interface Plan {
        data class NextAlbum(val albumId: String, val artistId: String?, val genre: String?, val excludeAlbums: List<String>) : Plan
        data class Radio(val artistIds: List<String>, val genres: List<String>, val excludeTracks: List<String>) : Plan
        data class Artist(val artistId: String, val genres: List<String>, val excludeTracks: List<String>) : Plan
    }

    fun plan(queue: List<Track>): Plan? {
        val last = queue.lastOrNull() ?: return null
        val runs = runsByAlbum(queue)
        val albums = runs.all { run -> run.first().albumId != null && (run.size >= MIN_ALBUM_RUN || run.size == queue.size && queue.size > 1) } &&
            runs.map { it.first().albumId }.distinct().size == runs.size
        if (albums) {
            return Plan.NextAlbum(last.albumId!!, last.artistId, last.genre, queue.mapNotNull { it.albumId }.distinct())
        }
        val genres = queue.takeLast(SEED_WINDOW).mapNotNull { it.genre?.takeIf(String::isNotBlank) }.mostCommon(2)
        singleArtist(queue)?.let { artistId ->
            return Plan.Artist(artistId, genres, queue.map { it.id }.distinct().takeLast(MAX_EXCLUDED_TRACKS))
        }
        val recent = queue.takeLast(SEED_WINDOW)
        return Plan.Radio(
            artistIds = recent.mapNotNull { it.artistId }.mostCommon(3),
            genres = genres,
            excludeTracks = queue.map { it.id }.distinct().takeLast(MAX_EXCLUDED_TRACKS),
        )
    }

    /** The continuation picked from [downloaded] songs, for when there's no server. */
    fun fromDownloads(plan: Plan, downloaded: List<Track>, random: Random = Random): List<Track> = when (plan) {
        is Plan.NextAlbum -> {
            val albums = downloaded.filter { it.albumId != null && it.albumId !in plan.excludeAlbums }.groupBy { it.albumId!! }.values
            val pick = albums.filter { it.first().artistId != null && it.first().artistId == plan.artistId }.randomOrNull(random)
                ?: albums.filter { plan.genre != null && it.first().genre.equals(plan.genre, ignoreCase = true) }.randomOrNull(random)
                ?: albums.randomOrNull(random)
            pick.orEmpty().sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
        }
        is Plan.Radio -> {
            val excluded = plan.excludeTracks.toSet()
            val genres = plan.genres.map { it.lowercase() }.toSet()
            val fresh = downloaded.filter { it.id !in excluded }
            val (related, rest) = fresh.partition { it.artistId in plan.artistIds || it.genre?.lowercase() in genres }
            OfflineMixes.spreadArtists(related.shuffled(random) + rest.shuffled(random)).take(RADIO_SIZE)
        }
        is Plan.Artist -> {
            val excluded = plan.excludeTracks.toSet()
            val fresh = downloaded.filter { it.id !in excluded }
            val (theirs, others) = fresh.partition { it.artistId == plan.artistId }
            if (theirs.isNotEmpty()) {
                theirs.shuffled(random).take(RADIO_SIZE)
            } else {
                val genres = plan.genres.map { it.lowercase() }.toSet()
                val (sameGenre, rest) = others.partition { it.genre?.lowercase() in genres }
                OfflineMixes.spreadArtists(sameGenre.shuffled(random) + rest.shuffled(random)).take(RADIO_SIZE)
            }
        }
    }

    /**
     * The artist when every song in [queue] is theirs, counting songs credited to them with others
     * ("Artist feat. Someone"). A single song isn't enough to go on.
     */
    private fun singleArtist(queue: List<Track>): String? {
        if (queue.size < 2) return null
        val main = queue.mapNotNull { it.artistId }.mostCommon(1).firstOrNull() ?: return null
        val name = queue.first { it.artistId == main }.artist
        val theirs = queue.all { it.artistId == main || (name.isNotBlank() && it.artist.contains(name, ignoreCase = true)) }
        return main.takeIf { theirs }
    }

    private fun runsByAlbum(queue: List<Track>): List<List<Track>> {
        val runs = mutableListOf<MutableList<Track>>()
        for (track in queue) {
            val run = runs.lastOrNull()
            if (run != null && run.first().albumId == track.albumId) run += track else runs += mutableListOf(track)
        }
        return runs
    }

    private fun List<String>.mostCommon(n: Int): List<String> =
        groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(n).map { it.key }
}
