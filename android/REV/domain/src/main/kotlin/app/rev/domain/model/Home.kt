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

/** [Downloads] is every song on the device; it exists only as an offline mix. */
enum class MixKind { Artist, Genre, Favorites, Discovery, Downloads }

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
