package app.rev.domain.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningSessionTest {
    @Test
    fun `skips count against an artist and genre, plays and staying for them`() {
        val session = ListeningSession { 0 }
        session.record(song("a", artist = "Adele", genre = "Pop"), Outcome.QuickSkip)
        session.record(song("b", artist = "Adele", genre = "Pop"), Outcome.QuickSkip)
        session.record(song("c", artist = "Tool", genre = "Metal"), Outcome.Stayed)
        val taste = session.taste(TasteProfile())
        assertTrue(taste.artist("ar-Adele") < -0.75)
        assertTrue(taste.genre("POP") < -0.75)
        assertTrue(taste.artist("ar-Tool") > 0.5)
        assertEquals(setOf("a", "b", "c"), taste.heard)
    }

    @Test
    fun `newer signals count more than older ones`() {
        val session = ListeningSession { 0 }
        session.record(song("a", artist = "X"), Outcome.Played)
        session.record(song("b", artist = "X"), Outcome.QuickSkip)
        assertTrue(session.taste(TasteProfile()).artist("ar-X") < 0)
    }

    @Test
    fun `after half an hour without listening a new session starts`() {
        var now = 0L
        val session = ListeningSession { now }
        session.record(song("a"), Outcome.QuickSkip)
        now += ListeningSession.IDLE_MS + 1
        assertEquals(emptyList<Signal>(), session.signals())
    }

    @Test
    fun `skipping songs you know and staying on new ones means you want something new`() {
        val profile = TasteProfile(tracks = mapOf("known1" to TrackHistory(20, 0), "known2" to TrackHistory(15, 1)))
        val session = ListeningSession { 0 }
        session.record(song("known1"), Outcome.QuickSkip)
        session.record(song("known2"), Outcome.QuickSkip)
        session.record(song("new1"), Outcome.Stayed)
        assertTrue(session.taste(profile).familiarity < -0.5)
    }
}
