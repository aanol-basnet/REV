package app.rev.domain.taste

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatesTest {
    private val downloads = (1..12).map { song("o$it", artist = "Opeth", genre = "Metal", album = "o-al${it % 3}") } +
        listOf(song("p1", artist = "Adele", genre = "Pop"), song("t1", artist = "Tool", genre = "Metal", album = "lateralus"),
            song("t2", artist = "Tool", genre = "Metal", album = "aenima"))

    @Test
    fun `offline, a mood leaves out the artists you skipped`() {
        val songs = Candidates.fromDownloads(CandidateRequest.Mood(listOf("ar-Tool"), listOf("Metal"), listOf("ar-Adele"), emptyList()), downloads, Random(1))
        assertFalse(songs.any { it.artist == "Adele" })
        assertEquals(14, songs.size)
    }

    @Test
    fun `offline, an artist's other albums`() {
        val songs = Candidates.fromDownloads(CandidateRequest.ArtistSongs("ar-Tool", listOf("Metal"), listOf("lateralus"), emptyList()), downloads)
        assertEquals(listOf("t2"), songs.map { it.id })
    }

    @Test
    fun `offline, songs like one put its artist and genre first`() {
        val seed = song("t1", artist = "Tool", genre = "Metal")
        val songs = Candidates.fromDownloads(CandidateRequest.LikeTrack(seed, emptyList(), listOf("t1")), downloads, Random(1))
        assertEquals("t2", songs.first().id)
        assertTrue(songs.indexOfFirst { it.id == "p1" } > songs.indexOfFirst { it.artist == "Opeth" })
    }

    @Test
    fun `avoiding artists never empties the pool`() {
        val few = listOf(song("x", artist = "Adele"), song("y", artist = "Muse"))
        assertEquals(few, Candidates.withoutArtists(few, listOf("ar-Adele")))
    }
}
