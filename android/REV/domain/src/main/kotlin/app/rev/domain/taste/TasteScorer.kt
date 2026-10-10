package app.rev.domain.taste

import app.rev.domain.model.Track
import app.rev.domain.offline.OfflineMixes
import kotlin.math.ln
import kotlin.random.Random
import app.rev.domain.learning.CurrentSituation
import app.rev.domain.learning.ListenFeatures
import app.rev.domain.learning.PersonalModel
import app.rev.domain.playback.QueueSource
import java.time.LocalDate

/**
 * How well a song fits right now: mostly the session's mood, then your long-term taste, the time
 * of day, whether you're after familiar songs or new ones, and songs you always skip.
 */
class TasteScorer(
    private val session: SessionTaste,
    private val profile: TasteProfile,
    hour: Int,
    /** The personal skip model's chance of skipping each song (by id), where it has one. */
    private val skipChances: Map<String, Double> = emptyMap(),
    /** How much the model counts, growing with how much it has learned. */
    private val modelWeight: Double = 0.0,
) {
    private val daypart = profile.daypart(hour)

    /** The hand-tuned score plus, once it has learned something, the personal model's say. */
    fun score(track: Track): Double {
        val chance = skipChances[track.id] ?: return handScore(track)
        return handScore(track) + modelWeight * (1 - 2 * chance)
    }

    /** The hand-tuned rules alone (logged with each listen to compare against the model). */
    fun handScore(track: Track): Double {
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

        /** The model's full say once trusted: a sure skip costs as much as two skipped songs by the artist. */
        const val MODEL_WEIGHT = 1.5
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

/**
 * Ranks songs by the current session, taste profile and, once it has learned enough, the personal
 * skip [model] in the [situation] right now.
 */
class TasteRanker(
    private val session: ListeningSession,
    private val taste: TasteRepository,
    private val hour: () -> Int,
    private val random: Random = Random,
    private val model: PersonalModel? = null,
    private val situation: CurrentSituation? = null,
    private val weekday: () -> Int = { LocalDate.now().dayOfWeek.value - 1 },
) {
    /** A scorer for [tracks] coming from [source] (smart or autoplay). */
    suspend fun scorer(tracks: List<Track> = emptyList(), source: String = QueueSource.Smart.wire): TasteScorer {
        val profile = profile()
        val confidence = model?.let { runCatching { it.confidence() }.getOrDefault(0.0) } ?: 0.0
        val chances = if (model == null || confidence <= 0.0) {
            emptyMap()
        } else {
            tracks.mapNotNull { t -> model.skipChance(input(t, profile, source))?.let { t.id to it } }.toMap()
        }
        return TasteScorer(session.taste(profile), profile, hour(), chances, TasteScorer.MODEL_WEIGHT * confidence)
    }

    suspend fun rank(tracks: List<Track>, spread: Boolean = true, source: String = QueueSource.Smart.wire): List<Track> =
        scorer(tracks, source).rank(tracks, random, spread = spread)

    /** The model's chance of skipping [track] if it played now; null before it has learned anything. */
    suspend fun skipChance(track: Track, source: String): Double? =
        model?.skipChance(input(track, profile(), source))

    suspend fun sessionTaste(): SessionTaste = session.taste(profile())

    private suspend fun profile(): TasteProfile = runCatching { taste.profile() }.getOrDefault(TasteProfile())

    private fun input(track: Track, profile: TasteProfile, source: String) =
        ListenFeatures.candidate(track, situation?.value, source, hour(), weekday(), profile)
}
