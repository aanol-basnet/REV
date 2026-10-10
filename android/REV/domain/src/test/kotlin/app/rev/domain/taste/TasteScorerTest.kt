package app.rev.domain.taste

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteScorerTest {
    private fun session(vararg signals: Pair<app.rev.domain.model.Track, Outcome>, profile: TasteProfile = TasteProfile()): SessionTaste {
        val s = ListeningSession { 0 }
        signals.forEach { (t, o) -> s.record(t, o) }
        return s.taste(profile)
    }

    @Test
    fun `songs like what you stayed on beat songs like what you skipped`() {
        val mood = session(song("x", artist = "Adele", genre = "Pop") to Outcome.QuickSkip, song("y", artist = "Tool", genre = "Metal") to Outcome.Stayed)
        val scorer = TasteScorer(mood, TasteProfile(), hour = 12)
        val ranked = scorer.rank(
            listOf(song("pop", artist = "Adele", genre = "Pop"), song("metal", artist = "Opeth", genre = "Metal")),
            temperature = 0.0,
        )
        assertEquals(listOf("metal", "pop"), ranked.map { it.id })
    }

    @Test
    fun `history counts when the session has nothing to say`() {
        val profile = TasteProfile(artists = mapOf("ar-Loved" to 0.9, "ar-Meh" to -0.6))
        val scorer = TasteScorer(SessionTaste(), profile, hour = 12)
        assertTrue(scorer.score(song("a", artist = "Loved")) > scorer.score(song("b", artist = "Meh")))
    }

    @Test
    fun `songs you nearly always skip and songs already heard sink`() {
        val profile = TasteProfile(tracks = mapOf("skippy" to TrackHistory(plays = 1, skips = 9)))
        val scorer = TasteScorer(session(song("heard") to Outcome.Played, profile = profile), profile, hour = 12)
        val fresh = scorer.score(song("fresh", artist = "Z"))
        assertTrue(scorer.score(song("skippy", artist = "Z")) < fresh)
        assertTrue(scorer.score(song("heard", artist = "Z")) < fresh)
    }

    @Test
    fun `the time of day leans towards what you usually play then`() {
        val profile = TasteProfile(dayparts = listOf(Daypart(20, genres = mapOf("ambient" to 1.0), artists = emptyMap()), Daypart(8, mapOf("metal" to 1.0), emptyMap())))
        val night = TasteScorer(SessionTaste(), profile, hour = 22)
        assertTrue(night.score(song("a", genre = "Ambient")) > night.score(song("b", genre = "Metal")))
        val morning = TasteScorer(SessionTaste(), profile, hour = 9)
        assertTrue(morning.score(song("a", genre = "Ambient")) < morning.score(song("b", genre = "Metal")))
    }

    @Test
    fun `ranking keeps every song and is varied`() {
        val songs = (1..20).map { song("s$it", artist = "A$it") }
        val scorer = TasteScorer(SessionTaste(), TasteProfile(), hour = 12)
        val one = scorer.rank(songs, Random(1))
        assertEquals(songs.toSet(), one.toSet())
        assertTrue(one != scorer.rank(songs, Random(2)))
    }
}
