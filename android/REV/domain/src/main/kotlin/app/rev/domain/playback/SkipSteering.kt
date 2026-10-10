package app.rev.domain.playback

import app.rev.domain.model.Track
import app.rev.domain.offline.OfflineMixes
import kotlin.random.Random

/**
 * Follow my skips (Settings → Playback): skipping several songs in a row and then staying on one
 * says "more like this". Once you've listened to it for a bit, the rest of the queue is replaced
 * with songs like it, leaving out the artists you just skipped.
 *
 * Tracks the streak: [skipped] each time you jump ahead early, [listened] when a song plays on
 * past the quick-skip point or ends by itself. Going back to an earlier song doesn't count either way.
 */
class SkipStreak(private val needed: Int = SKIPS_NEEDED) {
    private val skips = mutableListOf<Track>()

    /** The songs skipped in this streak, oldest first. */
    val skipped: List<Track> get() = skips.toList()

    /** Enough skips in a row that the song you stay on should steer the queue. */
    val ready: Boolean get() = skips.size >= needed

    /** You jumped ahead from [track] after [positionMs]; an early jump continues the streak. */
    fun left(track: Track, positionMs: Long) {
        if (isQuickSkip(track.durationMs, positionMs)) skips += track else skips.clear()
    }

    /** A song played through, or the streak was acted on. */
    fun reset() = skips.clear()

    companion object {
        const val SKIPS_NEEDED = 3

        /** How long you stay on a song before the queue follows it. */
        const val SETTLE_MS = 30_000L
        private const val QUICK_SKIP_MS = 30_000L

        /** Leaving within the first 30 seconds (or half of a short song). */
        fun isQuickSkip(durationMs: Long, positionMs: Long): Boolean {
            val limit = if (durationMs > 0) minOf(QUICK_SKIP_MS, durationMs / 2) else QUICK_SKIP_MS
            return positionMs < limit
        }
    }
}

object SkipSteering {
    /** Songs like [seed]: its artist and similar ones, its genre; nothing already in [queue]. */
    fun plan(seed: Track, queue: List<Track>): Autoplay.Plan.Radio = Autoplay.Plan.Radio(
        artistIds = listOfNotNull(seed.artistId),
        genres = listOfNotNull(seed.genre?.takeIf(String::isNotBlank)),
        excludeTracks = (queue.map { it.id } + seed.id).distinct().takeLast(MAX_EXCLUDED_TRACKS),
    )

    /**
     * [candidates] without the artists you skipped (unless it's the seed's own artist), keeping
     * the candidates as they are when that would leave too few.
     */
    fun avoidSkipped(candidates: List<Track>, seed: Track, skipped: List<Track>): List<Track> {
        val avoid = skipped.mapNotNull { it.artistId }.toSet() - setOfNotNull(seed.artistId)
        val kept = candidates.filter { it.artistId == null || it.artistId !in avoid }
        return if (kept.size >= MIN_KEPT || kept.size == candidates.size) kept else candidates
    }

    /** Songs like [seed] from [downloaded], for when there's no server: same artist and genre first. */
    fun fromDownloads(plan: Autoplay.Plan.Radio, seed: Track, downloaded: List<Track>, random: Random = Random): List<Track> {
        val excluded = plan.excludeTracks.toSet()
        val fresh = downloaded.filter { it.id !in excluded }
        val genre = seed.genre?.lowercase()
        val sameArtist = fresh.filter { seed.artistId != null && it.artistId == seed.artistId }
        val (sameGenre, rest) = (fresh - sameArtist.toSet()).partition { genre != null && it.genre?.lowercase() == genre }
        val related = OfflineMixes.spreadArtists((sameArtist.shuffled(random).take(MAX_SAME_ARTIST) + sameGenre).shuffled(random))
        return (related + rest.shuffled(random)).take(Autoplay.RADIO_SIZE)
    }

    private const val MAX_EXCLUDED_TRACKS = 1000
    private const val MAX_SAME_ARTIST = 6
    private const val MIN_KEPT = 10
}
