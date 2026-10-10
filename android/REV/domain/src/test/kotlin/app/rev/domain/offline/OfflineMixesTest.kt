package app.rev.domain.offline

import app.rev.domain.model.Mix
import app.rev.domain.model.MixKind
import app.rev.domain.model.Track
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMixesTest {
    private fun track(id: String, artist: String, genre: String? = null) = Track(
        id = id, title = id, album = "Album", albumId = "al-$artist", artist = artist, artistId = "ar-$artist",
        trackNumber = null, discNumber = null, year = null, genre = genre, coverArtId = "cov-$artist",
        durationMs = 1000, starred = false, format = null, bitRateKbps = null, sampleRateHz = null,
        bitDepth = null, sizeBytes = null,
    )

    private val tool = (1..8).map { track("tool$it", "Tool", "Progressive Metal") }
    private val apc = (1..4).map { track("apc$it", "A Perfect Circle", "Progressive Metal") }
    private val adele = (1..6).map { track("adele$it", "Adele", "Pop") }
    private val downloaded = tool + apc + adele

    @Test
    fun `offers everything, liked songs, top artists and genres`() {
        val liked = (tool.take(3) + adele.take(3)).map { it.id }.toSet()
        val mixes = OfflineMixes.available(downloaded, liked)
        assertEquals(
            listOf(
                OfflineMixes.ALL_ID,
                OfflineMixes.FAVORITES_ID,
                "offline:artist:ar-Tool",
                "offline:artist:ar-Adele",
                "offline:genre:Progressive Metal",
                "offline:genre:Pop",
            ),
            mixes.map { it.id },
        )
        assertTrue(mixes.all { it.isOffline })
        // Tool's radio features A Perfect Circle, who share its genre.
        assertEquals(listOf("A Perfect Circle"), mixes.first { it.name == "Tool" }.featuring)
    }

    @Test
    fun `nothing to offer with only a few songs`() {
        assertEquals(emptyList<Mix>(), OfflineMixes.available(tool.take(3), emptySet()))
    }

    @Test
    fun `rebuilds a server artist radio from downloads, artist every third song`() {
        val mix = Mix("artist:ar-Tool", MixKind.Artist, "Tool", listOf("Adele"), emptyList())
        val songs = OfflineMixes.tracks(mix, downloaded, emptySet(), Random(1))
        assertEquals(downloaded.size, songs.size)
        assertEquals(songs.size, songs.map { it.id }.toSet().size)
        songs.forEachIndexed { i, t -> if (i % 3 == 0 && i < 24) assertEquals("Tool", t.artist) }
        // Featured by name even outside the genre.
        assertTrue(songs.any { it.artist == "Adele" })
    }

    @Test
    fun `genre and liked mixes only use matching songs`() {
        val genre = OfflineMixes.tracks(Mix("genre:pop", MixKind.Genre, "Pop", emptyList(), emptyList()), downloaded, emptySet())
        assertEquals(adele.map { it.id }.toSet(), genre.map { it.id }.toSet())
        val liked = OfflineMixes.tracks(Mix("favorites", MixKind.Favorites, null, emptyList(), emptyList()), downloaded, setOf("apc1"))
        assertEquals(listOf("apc1"), liked.map { it.id })
    }

    @Test
    fun `spreads an artist out`() {
        val songs = listOf(track("a1", "A"), track("a2", "A"), track("a3", "A"), track("b1", "B"), track("c1", "C"), track("d1", "D"), track("b2", "B"))
        assertEquals(
            listOf("a1", "b1", "c1", "d1", "a2", "b2", "a3"),
            OfflineMixes.spreadArtists(songs).map { it.id },
        )
    }
}
