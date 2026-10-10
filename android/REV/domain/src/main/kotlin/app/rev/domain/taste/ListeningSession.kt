package app.rev.domain.taste

import app.rev.domain.model.Track
import kotlin.math.pow
import kotlin.math.tanh

/** What happened to a song, and how much it says you want more like it. */
enum class Outcome(val weight: Double) {
    /** Left within the first few seconds: not what you're in the mood for. */
    QuickSkip(-1.0),

    /** Left partway through: tired of the song more than the mood. */
    LateSkip(-0.3),
    Played(1.0),

    /** Skipped to (or went back to) and stayed on: what you were looking for. */
    Stayed(1.5),

    /** The "Not now" button. */
    NotNow(-2.0),

    /** The "More like this" button. */
    MoreLikeThis(2.0),
}

data class Signal(val track: Track, val outcome: Outcome, val at: Long)

/**
 * The current listening session: what you played and skipped lately, newest counting most. It
 * starts over after half an hour without listening. Shared by the smart queue and autoplay.
 */
class ListeningSession(private val clock: () -> Long = System::currentTimeMillis) {
    private val signals = ArrayDeque<Signal>()

    @Synchronized
    fun record(track: Track, outcome: Outcome) {
        expire()
        signals.addLast(Signal(track, outcome, clock()))
        while (signals.size > MAX_SIGNALS) signals.removeFirst()
    }

    /** Starts the session's mood over, e.g. when you switch to another listening mode. */
    @Synchronized
    fun reset() = signals.clear()

    @Synchronized
    fun signals(): List<Signal> {
        expire()
        return signals.toList()
    }

    fun taste(profile: TasteProfile): SessionTaste = SessionTaste.of(signals(), profile)

    private fun expire() {
        val last = signals.lastOrNull() ?: return
        if (clock() - last.at > IDLE_MS) signals.clear()
    }

    companion object {
        const val MAX_SIGNALS = 60
        const val IDLE_MS = 30 * 60_000L
    }
}

/**
 * The session's mood, -1 to 1 per artist, genre (lowercase), decade and album, and whether you're
 * after songs you know (towards 1) or new ones (towards -1).
 */
data class SessionTaste(
    val artists: Map<String, Double> = emptyMap(),
    val genres: Map<String, Double> = emptyMap(),
    val decades: Map<Int, Double> = emptyMap(),
    val albums: Map<String, Double> = emptyMap(),
    val familiarity: Double = 0.0,
    /** Songs already heard (or skipped) this session. */
    val heard: Set<String> = emptySet(),
) {
    fun artist(id: String?): Double = id?.let(artists::get) ?: 0.0
    fun genre(name: String?): Double = name?.lowercase()?.let(genres::get) ?: 0.0
    fun decade(year: Int?): Double = year?.let { decades[decadeOf(it)] } ?: 0.0
    fun album(id: String?): Double = id?.let(albums::get) ?: 0.0

    companion object {
        /** Each older signal counts this much less than the one after it. */
        const val DECAY = 0.85
        private const val SCALE = 1.5

        fun of(signals: List<Signal>, profile: TasteProfile): SessionTaste {
            val artists = HashMap<String, Double>()
            val genres = HashMap<String, Double>()
            val decades = HashMap<Int, Double>()
            val albums = HashMap<String, Double>()
            var familiarity = 0.0
            signals.asReversed().forEachIndexed { age, signal ->
                val w = DECAY.pow(age) * signal.outcome.weight
                val t = signal.track
                t.artistId?.let { artists.merge(it, w, Double::plus) }
                t.genre?.takeIf(String::isNotBlank)?.let { genres.merge(it.lowercase(), w, Double::plus) }
                t.year?.let { decades.merge(decadeOf(it), w, Double::plus) }
                t.albumId?.let { albums.merge(it, w, Double::plus) }
                familiarity += w * profile.familiarity(t.id)
            }
            fun <K> Map<K, Double>.squash() = mapValues { tanh(it.value / SCALE) }
            return SessionTaste(
                artists = artists.squash(),
                genres = genres.squash(),
                decades = decades.squash(),
                albums = albums.squash(),
                familiarity = if (profile.isEmpty) 0.0 else tanh(familiarity / SCALE),
                heard = signals.map { it.track.id }.toSet(),
            )
        }
    }
}
