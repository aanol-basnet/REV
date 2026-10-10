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

    @Test
    fun `the personal model's say grows with what it has learned`() {
        val songs = listOf(song("a", artist = "A"), song("b", artist = "B"))
        val chances = mapOf("a" to 0.9, "b" to 0.1)
        val unsure = TasteScorer(SessionTaste(), TasteProfile(), 12, chances, modelWeight = 0.0)
        assertEquals(unsure.score(songs[0]), unsure.score(songs[1]), 1e-9)
        val trusted = TasteScorer(SessionTaste(), TasteProfile(), 12, chances, modelWeight = TasteScorer.MODEL_WEIGHT)
        assertEquals(listOf("b", "a"), trusted.rank(songs, temperature = 0.0).map { it.id })
        // The hand-tuned score, logged to compare against, leaves the model out.
        assertEquals(trusted.handScore(songs[0]), trusted.handScore(songs[1]), 1e-9)
    }

    @Test
    fun `a listening mode leans to what you play in it, and to familiar or new songs`() {
        val profile = TasteProfile(
            tracks = mapOf("old" to TrackHistory(plays = 30, skips = 0)),
            modes = mapOf("driving_family" to ModeTaste(genres = mapOf("pop" to 1.0), artists = emptyMap())),
        )
        val family = TasteScorer(SessionTaste(), profile, 12, mode = app.rev.domain.modes.BuiltInModes.DrivingFamily)
        assertTrue(family.score(song("p", genre = "Pop")) > family.score(song("m", genre = "Metal")))
        assertTrue(family.score(song("old", genre = "Metal")) > family.score(song("new", genre = "Metal")))
        val jam = TasteScorer(SessionTaste(), profile, 12, mode = app.rev.domain.modes.BuiltInModes.Jam)
        assertTrue(jam.score(song("old", genre = "Metal")) < jam.score(song("new", genre = "Metal")))
    }
}
