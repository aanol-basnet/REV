package app.rev.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSyncTest {
    private val lines = listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(5_000, "b2"), LyricLine(9_000, "c"))

    @Test
    fun `finds the line being sung`() {
        assertEquals(-1, LyricsSync.currentLine(lines, 0))
        assertEquals(0, LyricsSync.currentLine(lines, 1_000))
        assertEquals(0, LyricsSync.currentLine(lines, 4_999))
        assertEquals(2, LyricsSync.currentLine(lines, 5_000))
        assertEquals(3, LyricsSync.currentLine(lines, 60_000))
        assertEquals(-1, LyricsSync.currentLine(emptyList(), 5_000))
    }
}
