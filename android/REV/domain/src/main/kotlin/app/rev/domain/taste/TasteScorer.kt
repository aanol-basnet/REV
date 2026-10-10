package app.rev.domain.taste

import app.rev.domain.model.Track
import app.rev.domain.offline.OfflineMixes
import kotlin.math.ln
import kotlin.random.Random

/**
 * How well a song fits right now: mostly the session's mood, then your long-term taste, the time
 * of day, whether you're after familiar songs or new ones, and songs you always skip.
 */
class TasteScorer(private val session: SessionTaste, private val profile: TasteProfile, hour: Int) {
    private val daypart = profile.daypart(hour)

    fun score(track: Track): Double {
        val genre = track.genre?.lowercase()
        var s = 0.0
        s += SESSION_ARTIST * session.artist(track.artistId)
        s += SESSION_GENRE * session.genre(genre)
        s += SESSION_DECADE * session.decade(track.year)
        s += SESSION_ALBUM * session.album(track.albumId)
        s += PROFILE_ARTIST * profile.artist(track.artistId)
        s += PROFILE_GENRE * profile.genre(genre)
        s += PROFILE_DECADE * profile.decade(track.year)
        daypart?.let { part ->
            s += DAYPART * ((genre?.let(part.genres::get) ?: 0.0) + (track.artistId?.let(part.artists::get) ?: 0.0)) / 2
        }
        s += FAMILIARITY * session.familiarity * profile.familiarity(track.id)
        s -= ALWAYS_SKIPPED * profile.skipRate(track.id)
        if (track.starred) s += FAVORITE
        if (track.id in session.heard) s -= HEARD
        return s
    }

    /**
     * [tracks] best first, with some randomness so it doesn't play the same order every time
     * ([temperature] 0 is strictly by score); [spread] keeps one artist from coming up back to back.
     */
    fun rank(tracks: List<Track>, random: Random = Random, temperature: Double = TEMPERATURE, spread: Boolean = true): List<Track> {
        val ranked = tracks.map { it to score(it) + temperature * gumbel(random) }.sortedByDescending { it.second }.map { it.first }
        return if (spread) OfflineMixes.spreadArtists(ranked) else ranked
    }

    private fun gumbel(random: Random): Double = -ln(-ln(random.nextDouble().coerceIn(1e-9, 1 - 1e-9)))

    companion object {
        const val TEMPERATURE = 0.35
        private const val SESSION_ARTIST = 1.0
        private const val SESSION_GENRE = 0.8
        private const val SESSION_DECADE = 0.4
        private const val SESSION_ALBUM = 0.3
        private const val PROFILE_ARTIST = 0.5
        private const val PROFILE_GENRE = 0.4
        private const val PROFILE_DECADE = 0.15
        private const val DAYPART = 0.3
        private const val FAMILIARITY = 0.5
        private const val ALWAYS_SKIPPED = 0.8
        private const val FAVORITE = 0.3
        private const val HEARD = 1.0
    }
}

/** Ranks songs by the current session and taste profile; used by the smart queue and autoplay. */
class TasteRanker(
    private val session: ListeningSession,
    private val taste: TasteRepository,
    private val hour: () -> Int,
    private val random: Random = Random,
) {
    suspend fun scorer(): TasteScorer {
        val profile = runCatching { taste.profile() }.getOrDefault(TasteProfile())
        return TasteScorer(session.taste(profile), profile, hour())
    }

    suspend fun rank(tracks: List<Track>, spread: Boolean = true): List<Track> = scorer().rank(tracks, random, spread = spread)

    suspend fun sessionTaste(): SessionTaste = session.taste(runCatching { taste.profile() }.getOrDefault(TasteProfile()))
}
