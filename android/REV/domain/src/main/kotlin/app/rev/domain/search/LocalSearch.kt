package app.rev.domain.search

import app.rev.domain.model.Album
import app.rev.domain.model.AlbumAvailability
import app.rev.domain.model.Artist
import app.rev.domain.model.SearchAlbum
import app.rev.domain.model.SearchArtist
import app.rev.domain.model.SearchResults
import app.rev.domain.model.SourceStatus
import app.rev.domain.model.Track
import java.text.Normalizer

/**
 * Matching on the device: for filtering library lists, and for searching what's cached and
 * downloaded when the server can't be reached. Case and accents are ignored ("aenima" finds
 * "Ænima", "bjork" finds "Björk"), every word of the query must appear, and names that start
 * with the query rank above those that merely contain it.
 */
object LocalSearch {
    private const val ARTISTS = 20
    private const val ALBUMS = 40
    private const val TRACKS = 50

    /** Lower-cased, accents and punctuation folded away, single-spaced. */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text.lowercase().replace("æ", "ae").replace("ø", "o").replace("œ", "oe").replace("ß", "ss"), Normalizer.Form.NFD)
        return folded.replace(COMBINING, "").replace(NON_WORD, " ").trim().replace(SPACES, " ")
    }

    /**
     * How well [query] matches a [primary] name (lower is better), also searching [secondary]
     * fields (artist, album) for the query's words; null when it doesn't match.
     */
    fun score(query: String, primary: String, vararg secondary: String?): Int? {
        val q = normalize(query)
        if (q.isEmpty()) return 0
        val name = normalize(primary)
        val all = (listOf(name) + secondary.filterNotNull().map(::normalize)).joinToString(" ")
        if (q.split(' ').any { it !in all }) return null
        return when {
            name == q -> 0
            name.startsWith(q) -> 1
            " $q" in " $name" -> 2
            q in name -> 3
            else -> 4
        }
    }

    /** [items] matching [query], best first, keeping the given order among equals. */
    fun <T> filter(items: List<T>, query: String, fields: (T) -> List<String?>): List<T> {
        if (normalize(query).isEmpty()) return items
        return items.mapNotNull { item ->
            val f = fields(item)
            score(query, f.firstOrNull().orEmpty(), *f.drop(1).toTypedArray())?.let { item to it }
        }.sortedBy { it.second }.map { it.first }
    }

    /** Results like the server's, from what's on the device. */
    fun search(query: String, artists: List<Artist>, albums: List<Album>, tracks: List<Track>): SearchResults =
        SearchResults(
            query = query,
            artists = filter(artists, query) { listOf(it.name) }.take(ARTISTS).map {
                SearchArtist(it.name, null, it.id, it.coverArtId, null)
            },
            albums = filter(albums, query) { listOf(it.name, it.artist) }.take(ALBUMS).map {
                SearchAlbum("lib:${it.id}", it.name, it.artist, it.year, null, AlbumAvailability.AVAILABLE, it.id, it.coverArtId, null, it.trackCount)
            },
            tracks = filter(tracks.distinctBy { it.id }, query) { listOf(it.title, it.artist, it.album) }.take(TRACKS),
            library = SourceStatus.Unavailable,
            catalog = SourceStatus.NotConfigured,
            onDevice = true,
        )

    private val COMBINING = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex(" +")
}
