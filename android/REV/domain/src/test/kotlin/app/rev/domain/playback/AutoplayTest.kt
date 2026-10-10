package app.rev.domain.playback

import app.rev.domain.model.Track
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayTest {
    private fun track(id: String, album: String?, artist: String, n: Int = 1, genre: String? = null) = Track(
        id = id, title = id, album = album.orEmpty(), albumId = album, artist = artist, artistId = "ar-$artist",
        trackNumber = n, discNumber = 1, year = null, genre = genre, coverArtId = null, durationMs = 1000,
        starred = false, format = null, bitRateKbps = null, sampleRateHz = null, bitDepth = null, sizeBytes = null,
    )

    private fun album(id: String, artist: String, size: Int = 5, genre: String? = null) =
        (1..size).map { track("$id-$it", id, artist, it, genre) }

    @Test
    fun `an album, or albums one after another, continue with another album`() {
        val plan = Autoplay.plan(album("lateralus", "Tool") + album("10k", "Tool"))
        assertEquals(Autoplay.Plan.NextAlbum("10k", "ar-Tool", null, listOf("lateralus", "10k")), plan)
        // A two-song single on its own still counts.
        assertTrue(Autoplay.plan(album("single", "Tool", size = 2)) is Autoplay.Plan.NextAlbum)
    }

    @Test
    fun `a playlist continues with similar songs from its main artists and genres`() {
        val playlist = listOf(
            track("a", "x", "Tool", genre = "Metal"),
            track("b", "y", "Tool", genre = "Metal"),
            track("c", "z", "Adele", genre = "Pop"),
            track("d", "w", "Tool", genre = "Metal"),
        )
        val plan = Autoplay.plan(playlist) as Autoplay.Plan.Radio
        assertEquals("ar-Tool", plan.artistIds.first())
        assertEquals("Metal", plan.genres.first())
        assertEquals(listOf("a", "b", "c", "d"), plan.excludeTracks)
        // A playlist ending in a few songs from one album is still a playlist.
        assertTrue(Autoplay.plan(playlist + album("lateralus", "Tool", size = 3)) is Autoplay.Plan.Radio)
        assertNull(Autoplay.plan(emptyList()))
    }

    @Test
    fun `offline, the next album is another downloaded one, same artist first, in track order`() {
        val downloaded = album("aenima", "Tool").shuffled(Random(3)) + album("adele21", "Adele") + album("lateralus", "Tool")
        val next = Autoplay.fromDownloads(Autoplay.Plan.NextAlbum("lateralus", "ar-Tool", null, listOf("lateralus")), downloaded)
        assertEquals((1..5).map { "aenima-$it" }, next.map { it.id })
    }

    @Test
    fun `offline radio prefers related songs and skips what's queued`() {
        val downloaded = album("aenima", "Tool") + album("adele21", "Adele")
        val songs = Autoplay.fromDownloads(Autoplay.Plan.Radio(listOf("ar-Tool"), emptyList(), listOf("aenima-1")), downloaded, Random(1))
        assertEquals(9, songs.size)
        assertTrue("aenima-1" !in songs.map { it.id })
        assertEquals("Tool", songs.first().artist)
    }
}
