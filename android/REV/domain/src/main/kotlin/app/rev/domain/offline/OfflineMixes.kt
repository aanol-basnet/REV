package app.rev.domain.offline

import app.rev.domain.model.Mix
import app.rev.domain.model.MixKind
import app.rev.domain.model.OFFLINE_MIX_PREFIX
import app.rev.domain.model.Track
import kotlin.random.Random

/**
 * Mixes built on the device from downloaded songs, so there is always something to play with no
 * server: one of everything, liked songs, and radios for the artists and genres you've downloaded
 * most. A server mix (from Home) can also be rebuilt from downloads when the server can't be
 * reached.
 */
object OfflineMixes {
    const val SIZE = 50
    const val ALL_ID = "${OFFLINE_MIX_PREFIX}all"
    const val FAVORITES_ID = "${OFFLINE_MIX_PREFIX}favorites"
    private const val ARTIST_PREFIX = "${OFFLINE_MIX_PREFIX}artist:"
    private const val GENRE_PREFIX = "${OFFLINE_MIX_PREFIX}genre:"

    /** A mix with fewer songs than this isn't worth offering. */
    private const val MIN_TRACKS = 6
    private const val ARTIST_MIXES = 3
    private const val GENRE_MIXES = 2

    /** Songs by one artist within this many of each other are spread apart where possible. */
    private const val ARTIST_GAP = 3

    /** The mixes worth offering for [downloaded] songs; [favoriteIds] are the liked ones. */
    fun available(downloaded: List<Track>, favoriteIds: Set<String>): List<Mix> {
        if (downloaded.size < MIN_TRACKS) return emptyList()
        val mixes = mutableListOf(
            Mix(ALL_ID, MixKind.Downloads, null, topArtists(downloaded), covers(downloaded)),
        )
        val liked = downloaded.filter { it.id in favoriteIds }
        if (liked.size >= MIN_TRACKS) {
            mixes += Mix(FAVORITES_ID, MixKind.Favorites, null, topArtists(liked), covers(liked))
        }
        downloaded.filter { it.artistId != null }
            .groupBy { it.artistId!! }
            .filterValues { it.size >= MIN_TRACKS }
            .entries
            .sortedByDescending { it.value.size }
            .take(ARTIST_MIXES)
            .forEach { (artistId, own) ->
                val related = related(artistId, own, emptySet(), downloaded)
                mixes += Mix(
                    "$ARTIST_PREFIX$artistId",
                    MixKind.Artist,
                    own.first().artist,
                    topArtists(related),
                    covers(own + related),
                )
            }
        downloaded.filter { !it.genre.isNullOrBlank() }
            .groupBy { it.genre!!.lowercase() }
            .filterValues { it.size >= MIN_TRACKS }
            .values
            .sortedByDescending { it.size }
            .take(GENRE_MIXES)
            .forEach { songs ->
                val name = songs.first().genre!!
                mixes += Mix("$GENRE_PREFIX$name", MixKind.Genre, name, topArtists(songs), covers(songs))
            }
        return mixes
    }

    /**
     * The songs of [mix] from [downloaded], shuffled. Works for offline mixes and for the server's
     * own mixes (artist radio, genre, liked songs, discovery); empty when nothing downloaded fits.
     */
    fun tracks(mix: Mix, downloaded: List<Track>, favoriteIds: Set<String>, random: Random = Random): List<Track> {
        val id = mix.id.removePrefix(OFFLINE_MIX_PREFIX)
        val songs = when {
            id == "all" || id == "discovery" -> downloaded.shuffled(random)
            id == "favorites" -> downloaded.filter { it.id in favoriteIds }.shuffled(random)
            id.startsWith("artist:") -> return artistRadio(id.removePrefix("artist:"), mix.featuring, downloaded, random)
            id.startsWith("genre:") -> {
                val genre = id.removePrefix("genre:")
                downloaded.filter { it.genre.equals(genre, ignoreCase = true) }.shuffled(random)
            }
            else -> emptyList()
        }
        return spreadArtists(songs).take(SIZE)
    }

    /**
     * The artist every third song; in between, songs by the artists it features and by others in
     * the artist's genres.
     */
    private fun artistRadio(artistId: String, featuring: List<String>, downloaded: List<Track>, random: Random): List<Track> {
        val own = downloaded.filter { it.artistId == artistId }.shuffled(random)
        val featured = featuring.map { it.lowercase() }.toSet()
        val pool = spreadArtists(related(artistId, own, featured, downloaded).shuffled(random))
        val mixed = mutableListOf<Track>()
        var o = 0
        var a = 0
        while (mixed.size < SIZE && (o < pool.size || a < own.size)) {
            val ownTurn = mixed.size % 3 == 0
            mixed += when {
                ownTurn && a < own.size -> own[a++]
                o < pool.size -> pool[o++]
                else -> own[a++]
            }
        }
        return mixed
    }

    /** Downloaded songs by other artists that fit with [own]: featured by name, or sharing a genre. */
    private fun related(artistId: String, own: List<Track>, featured: Set<String>, downloaded: List<Track>): List<Track> {
        val genres = own.mapNotNull { it.genre?.lowercase() }.toSet()
        return downloaded.filter {
            it.artistId != artistId && (it.artist.lowercase() in featured || it.genre?.lowercase() in genres)
        }
    }

    /**
     * Reorders [tracks] so the same artist doesn't come up again within a few songs, keeping the
     * given order otherwise.
     */
    fun spreadArtists(tracks: List<Track>): List<Track> {
        val remaining = tracks.toMutableList()
        val out = ArrayList<Track>(tracks.size)
        while (remaining.isNotEmpty()) {
            val recent = out.takeLast(ARTIST_GAP).map { it.artist.lowercase() }.toSet()
            val index = remaining.indexOfFirst { it.artist.lowercase() !in recent }
            out += remaining.removeAt(index.coerceAtLeast(0))
        }
        return out
    }

    private fun topArtists(tracks: List<Track>): List<String> =
        tracks.groupingBy { it.artist }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }

    private fun covers(tracks: List<Track>): List<String> =
        tracks.mapNotNull { it.coverArtId }.distinct().take(4)
}
