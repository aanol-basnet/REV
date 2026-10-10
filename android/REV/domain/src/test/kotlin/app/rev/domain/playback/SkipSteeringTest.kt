package app.rev.domain.playback

import app.rev.domain.model.Track
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSteeringTest {
    private fun track(id: String, artist: String = "A", genre: String? = "Rock", durationMs: Long = 200_000) = Track(
        id = id, title = id, album = "", albumId = "al-$id", artist = artist, artistId = "ar-$artist",
        trackNumber = 1, discNumber = 1, year = null, genre = genre, coverArtId = null, durationMs = durationMs,
        starred = false, format = null, bitRateKbps = null, sampleRateHz = null, bitDepth = null, sizeBytes = null,
    )

    @Test
    fun `three early skips in a row make a streak, listening on breaks it`() {
        val streak = SkipStreak()
        streak.left(track("a"), 5_000)
        streak.left(track("b"), 10_000)
        assertFalse(streak.ready)
        streak.left(track("c"), 60_000) // heard for a minute: not a skip
        assertFalse(streak.ready)
        listOf("d", "e", "f").forEach { streak.left(track(it), 1_000) }
        assertTrue(streak.ready)
        assertEquals(listOf("d", "e", "f"), streak.skipped.map { it.id })
        streak.reset()
        assertFalse(streak.ready)
    }

    @Test
    fun `a short song counts as skipped only in its first half`() {
        assertTrue(SkipStreak.isQuickSkip(durationMs = 40_000, positionMs = 15_000))
        assertFalse(SkipStreak.isQuickSkip(durationMs = 40_000, positionMs = 25_000))
    }

    @Test
    fun `the plan follows the song's artist and genre and leaves out the queue`() {
        val seed = track("s", artist = "Tool", genre = "Metal")
        val plan = SkipSteering.plan(seed, listOf(track("a"), seed))
        assertEquals(listOf("ar-Tool"), plan.artistIds)
        assertEquals(listOf("Metal"), plan.genres)
        assertEquals(listOf("a", "s"), plan.excludeTracks)
    }

    @Test
    fun `skipped artists are left out, unless that leaves too little`() {
        val seed = track("s", artist = "Tool")
        val skipped = listOf(track("k", artist = "Adele"), track("l", artist = "Tool"))
        val many = (1..12).map { track("ok$it", artist = "Opeth") } + track("no", artist = "Adele") + track("own", artist = "Tool")
        val kept = SkipSteering.avoidSkipped(many, seed, skipped)
        assertFalse(kept.any { it.artist == "Adele" })
        assertTrue(kept.any { it.id == "own" }) // the song you stayed on is by them too
        val few = listOf(track("x", artist = "Adele"), track("y", artist = "Opeth"))
        assertEquals(few, SkipSteering.avoidSkipped(few, seed, skipped))
    }

    @Test
    fun `offline, songs by the same artist and genre come first`() {
        val seed = track("s", artist = "Tool", genre = "Metal")
        val downloaded = listOf(
            track("p1", artist = "Adele", genre = "Pop"),
            track("m1", artist = "Opeth", genre = "Metal"),
            track("t1", artist = "Tool", genre = "Metal"),
            track("s", artist = "Tool", genre = "Metal"),
        )
        val songs = SkipSteering.fromDownloads(SkipSteering.plan(seed, listOf(seed)), seed, downloaded, Random(1))
        assertEquals(setOf("t1", "m1"), songs.take(2).map { it.id }.toSet())
        assertEquals(listOf("p1"), songs.drop(2).map { it.id })
    }
}
