package app.rev.domain.learning

/**
 * What the skip model knows about one listen: the song, the situation, the session and your
 * history with the song. Turned into named [features] exactly like the server does
 * (server/src/model/features.ts); both are checked against shared/model/feature-fixtures.json.
 */
data class FeatureInput(
    val trackId: String,
    val artistId: String?,
    val albumId: String?,
    val genres: List<String>,
    val year: Int?,
    val localHour: Int,
    /** 0 = Monday. */
    val weekday: Int,
    val source: String?,
    val queueShape: String?,
    val shuffle: Boolean?,
    val output: String?,
    val network: String?,
    val charging: Boolean?,
    val favorite: Boolean?,
    /** play, skip or none for the song before; null at the start of a session. */
    val previousOutcome: String?,
    val sessionSkips: Int?,
    val sessionPosition: Int?,
    val trackPlays: Int,
    val trackSkips: Int,
)

object SkipFeatures {
    private const val MAX_GENRES = 3

    fun of(input: FeatureInput): List<String> {
        val out = ArrayList<String>(40)
        val genres = input.genres.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.take(MAX_GENRES)
        val daypart = input.localHour / 4
        fun flag(b: Boolean) = if (b) "1" else "0"

        out += "t:${input.trackId}"
        input.artistId?.takeIf(String::isNotEmpty)?.let { out += "a:$it" }
        input.albumId?.takeIf(String::isNotEmpty)?.let { out += "al:$it" }
        genres.forEach { out += "g:$it" }
        input.year?.let { out += "d:${Math.floorDiv(it, 10) * 10}" }
        out += listOf("h:${input.localHour}", "p:$daypart", "w:${input.weekday}", "we:${flag(input.weekday >= 5)}")
        input.source?.takeIf(String::isNotEmpty)?.let { out += "src:$it" }
        input.queueShape?.takeIf(String::isNotEmpty)?.let { out += "qs:$it" }
        input.shuffle?.let { out += "sh:${flag(it)}" }
        input.output?.takeIf(String::isNotEmpty)?.let { out += "o:$it" }
        input.network?.takeIf(String::isNotEmpty)?.let { out += "n:$it" }
        input.charging?.let { out += "c:${flag(it)}" }
        input.favorite?.let { out += "f:${flag(it)}" }
        input.previousOutcome?.takeIf(String::isNotEmpty)?.let { out += "po:$it" }
        input.sessionSkips?.let { out += "ss:${countBucket(it)}" }
        input.sessionPosition?.let { out += "sp:${positionBucket(it)}" }
        out += listOf("tp:${countBucket(input.trackPlays)}", "tk:${countBucket(input.trackSkips)}")
        // Taste depends on the situation: an artist in the car, a genre late at night.
        input.artistId?.takeIf(String::isNotEmpty)?.let { artist ->
            input.output?.takeIf(String::isNotEmpty)?.let { out += "a:$artist|o:$it" }
            out += "a:$artist|p:$daypart"
        }
        genres.forEach { g ->
            out += "g:$g|p:$daypart"
            input.output?.takeIf(String::isNotEmpty)?.let { out += "g:$g|o:$it" }
            input.previousOutcome?.takeIf(String::isNotEmpty)?.let { out += "g:$g|po:$it" }
        }
        return out
    }

    /** 0, 1, 2–3, 4–7, 8+. */
    fun countBucket(n: Int): String = when {
        n <= 0 -> "0"
        n == 1 -> "1"
        n <= 3 -> "2-3"
        n <= 7 -> "4-7"
        else -> "8+"
    }

    /** 0, 1–2, 3–5, 6–10, 11+. */
    fun positionBucket(n: Int): String = when {
        n <= 0 -> "0"
        n <= 2 -> "1-2"
        n <= 5 -> "3-5"
        n <= 10 -> "6-10"
        else -> "11+"
    }
}
