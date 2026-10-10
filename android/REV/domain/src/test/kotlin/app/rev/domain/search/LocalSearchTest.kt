package app.rev.domain.search

import app.rev.domain.model.Artist
import app.rev.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchTest {
    @Test
    fun `ignores case, accents and punctuation`() {
        assertEquals("aenima", LocalSearch.normalize("Ænima"))
        assertEquals("bjork", LocalSearch.normalize("Björk"))
        assertEquals("ac dc", LocalSearch.normalize("AC/DC"))
        assertEquals(0, LocalSearch.score("aenima", "Ænima"))
    }

    @Test
    fun `ranks exact, then prefix, then word, then anywhere`() {
        assertEquals(0, LocalSearch.score("tool", "Tool"))
        assertEquals(1, LocalSearch.score("tool", "Toolbox"))
        assertEquals(2, LocalSearch.score("tool", "The Tool Shed"))
        assertEquals(3, LocalSearch.score("ool", "Tool"))
        assertNull(LocalSearch.score("tool", "Adele"))
    }

    @Test
    fun `every word must appear, in the name or the other fields`() {
        assertEquals(4, LocalSearch.score("schism tool", "Schism", "Tool", "Lateralus"))
        assertNull(LocalSearch.score("schism adele", "Schism", "Tool", "Lateralus"))
    }

    @Test
    fun `filters lists best first and searches downloads offline`() {
        val artists = listOf("Toolbox", "Adele", "Tool").map { Artist("ar-$it", it, 1, null, false) }
        assertEquals(listOf("Tool", "Toolbox"), LocalSearch.filter(artists, "tool") { listOf(it.name) }.map { it.name })
        assertEquals(artists, LocalSearch.filter(artists, "  ") { listOf(it.name) })

        val schism = Track("t1", "Schism", "Lateralus", "al-1", "Tool", "ar-1", 6, 1, 2001, null, null, 403_000, false, null, null, null, null, null)
        val results = LocalSearch.search("tool", artists, emptyList(), listOf(schism, schism))
        assertTrue(results.onDevice)
        assertEquals(listOf("t1"), results.tracks.map { it.id })
        assertEquals("ar-Tool", results.artists.first().libraryId)
    }
}
