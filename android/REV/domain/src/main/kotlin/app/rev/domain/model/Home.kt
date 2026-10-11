package app.rev.domain.model

/** Home recommendations, built by the server from your listening history and the library. */
data class HomeFeed(
    val generatedAt: Long,
    /** A handful of albums for quick access. */
    val quickPicks: List<Album>,
    val mixes: List<Mix>,
    val sections: List<HomeSection>,
    /** Genres you listen to most. */
    val genres: List<HomeGenre>,
)

/**
 * [Downloads] is every song on the device; it exists only as an offline mix. [Time] is what you
 * play at this time of day; its name is the [TimeOfDay]'s [TimeOfDay.wire].
 */
enum class MixKind { Artist, Genre, Favorites, Discovery, Downloads, Time }

/** Parts of the day for time-of-day mixes, local time: morning 5–12, afternoon 12–17, evening 17–22, night 22–5. */
enum class TimeOfDay(val wire: String) {
    Morning("morning"),
    Afternoon("afternoon"),
    Evening("evening"),
    Night("night"),
    ;

    companion object {
        fun of(hour: Int): TimeOfDay = when (hour) {
            in 5..11 -> Morning
            in 12..16 -> Afternoon
            in 17..21 -> Evening
            else -> Night
        }

        fun fromWire(wire: String?): TimeOfDay? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The feed without a time-of-day mix for another time than [hour]'s: a feed fetched in the
 * morning and shown again in the evening (from the cache) shouldn't offer the morning mix.
 */
fun HomeFeed.forHour(hour: Int): HomeFeed {
    val now = TimeOfDay.of(hour)
    val stale = mixes.filter { it.kind == MixKind.Time && TimeOfDay.fromWire(it.name) != now }
    return if (stale.isEmpty()) this else copy(mixes = mixes - stale.toSet())
}

/** A generated, playable track list. */
data class Mix(
    val id: String,
    val kind: MixKind,
    /** The artist or genre it's built around. */
    val name: String?,
    val featuring: List<String>,
    val coverArtIds: List<String>,
) {
    /** Built on the device from downloaded songs rather than by the server. */
    val isOffline: Boolean get() = id.startsWith(OFFLINE_MIX_PREFIX)
}

const val OFFLINE_MIX_PREFIX = "offline:"

enum class HomeSectionKind { Recent, Frequent, Similar, Genre, Rediscover, Unheard, Newest }

data class HomeSection(
    val id: String,
    val kind: HomeSectionKind,
    /** Artist name ([HomeSectionKind.Similar]) or genre ([HomeSectionKind.Genre]). */
    val basedOn: String?,
    /** Artist id or genre name, for linking. */
    val basedOnId: String?,
    val albums: List<Album>,
)

data class HomeGenre(val name: String, val albumCount: Int)
