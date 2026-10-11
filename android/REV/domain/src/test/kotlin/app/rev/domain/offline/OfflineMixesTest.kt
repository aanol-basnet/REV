package app.rev.domain.offline

import app.rev.domain.model.Mix
import app.rev.domain.model.MixKind
import app.rev.domain.model.HomeFeed
import app.rev.domain.model.TimeOfDay
import app.rev.domain.model.Track
import app.rev.domain.model.forHour
import app.rev.domain.taste.TasteProfile
import app.rev.domain.taste.TimeOfDayTaste
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

    private val evenings = TasteProfile(
        timesOfDay = mapOf(
            TimeOfDay.Evening to TimeOfDayTaste(
                tracks = adele.take(4).associate { it.id to 2.0 },
                genres = mapOf("pop" to 0.8),
                artists = emptyMap(),
            ),
        ),
    )

    @Test
    fun `offers the mix for this time of day first, from your songs then and ones that fit`() {
        val mixes = OfflineMixes.available(downloaded, emptySet(), evenings, hour = 19)
        val time = mixes.first()
        assertEquals("offline:time:evening", time.id)
        assertEquals(MixKind.Time, time.kind)
        assertEquals("evening", time.name)
        // Nothing known about mornings: no mix then.
        assertTrue(OfflineMixes.available(downloaded, emptySet(), evenings, hour = 8).none { it.kind == MixKind.Time })

        val songs = OfflineMixes.tracks(time, downloaded, emptySet(), Random(1), evenings)
        // Your four evening songs, and the other Pop songs that fit; no Tool.
        assertEquals(adele.map { it.id }.toSet(), songs.map { it.id }.toSet())
        // Two of yours, then one that fits.
        val yours = setOf("adele1", "adele2", "adele3", "adele4")
        assertTrue(songs.take(2).all { it.id in yours })
        assertTrue(songs[2].id !in yours)
    }

    @Test
    fun `a cached feed only offers the time-of-day mix at that time`() {
        val mix = Mix("time:morning", MixKind.Time, "morning", emptyList(), emptyList())
        val other = Mix("favorites", MixKind.Favorites, null, emptyList(), emptyList())
        val feed = HomeFeed(0, emptyList(), listOf(mix, other), emptyList(), emptyList())
        assertEquals(listOf(mix, other), feed.forHour(9).mixes)
        assertEquals(listOf(other), feed.forHour(20).mixes)
        assertEquals(listOf(TimeOfDay.Night, TimeOfDay.Morning, TimeOfDay.Afternoon, TimeOfDay.Evening, TimeOfDay.Night), listOf(4, 5, 12, 17, 22).map(TimeOfDay::of))
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
