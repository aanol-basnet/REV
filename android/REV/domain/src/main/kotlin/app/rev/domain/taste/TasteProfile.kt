package app.rev.domain.taste

/**
 * What the listening history says about you (built by the server from REV's listening log, cached
 * on the phone). Scores run from -1 (mostly skipped) to 1 (played a lot); missing means unknown, 0.
 */
data class TasteProfile(
    val artists: Map<String, Double> = emptyMap(),
    /** Keyed by lowercase genre name. */
    val genres: Map<String, Double> = emptyMap(),
    val decades: Map<Int, Double> = emptyMap(),
    val tracks: Map<String, TrackHistory> = emptyMap(),
    /** Parts of the day, by the hour they start, with what's played more or less then. */
    val dayparts: List<Daypart> = emptyList(),
    /** Listening modes by id, with what's played more or less in them. */
    val modes: Map<String, ModeTaste> = emptyMap(),
) {
    fun artist(id: String?): Double = id?.let(artists::get) ?: 0.0
    fun genre(name: String?): Double = name?.lowercase()?.let(genres::get) ?: 0.0
    fun decade(year: Int?): Double = year?.let { decades[decadeOf(it)] } ?: 0.0

    /** -1 for a song never played, up to 1 for one played many times. */
    fun familiarity(trackId: String): Double {
        val plays = tracks[trackId]?.plays ?: 0
        return when {
            plays == 0 -> -1.0
            plays < FAMILIAR_PLAYS -> 0.0
            plays < WELL_KNOWN_PLAYS -> 0.6
            else -> 1.0
        }
    }

    /** How often you skip this song, 0–1; 0 until it's been skipped a few times. */
    fun skipRate(trackId: String): Double {
        val history = tracks[trackId] ?: return 0.0
        if (history.skips < MIN_SKIPS) return 0.0
        return history.skips.toDouble() / (history.plays + history.skips)
    }

    /** The part of the day [hour] falls in. */
    fun daypart(hour: Int): Daypart? = dayparts.sortedBy { it.fromHour }.lastOrNull { it.fromHour <= hour }

    val isEmpty: Boolean get() = artists.isEmpty() && genres.isEmpty() && tracks.isEmpty()

    private companion object {
        const val FAMILIAR_PLAYS = 3
        const val WELL_KNOWN_PLAYS = 10
        const val MIN_SKIPS = 3
    }
}

data class TrackHistory(val plays: Int, val skips: Int)

/** A listening mode: genres (lowercase) and artists played more (up to 1) or less (down to -1) than usual in it. */
data class ModeTaste(val genres: Map<String, Double>, val artists: Map<String, Double>)

/** A part of the day: genres (lowercase) and artists played more (up to 1) or less (down to -1) than usual. */
data class Daypart(val fromHour: Int, val genres: Map<String, Double>, val artists: Map<String, Double>)

fun decadeOf(year: Int): Int = year / 10 * 10

fun interface TasteRepository {
    /** The newest profile available: fetched when it's a few hours old, else the cached one; empty before the first. */
    suspend fun profile(): TasteProfile
}
