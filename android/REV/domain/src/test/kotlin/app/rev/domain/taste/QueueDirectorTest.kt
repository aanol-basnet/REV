package app.rev.domain.taste

import app.rev.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDirectorTest {
    private val director = QueueDirector(hour = { 22 })

    private fun decide(
        trigger: Trigger,
        queue: List<Track>,
        current: Track,
        shuffle: Boolean = true,
        session: SessionTaste = SessionTaste(),
        profile: TasteProfile = TasteProfile(),
        moved: Boolean = false,
    ): Move? {
        val upcoming = queue.dropWhile { it != current }.drop(1)
        return director.decide(trigger, QueueShape.of(queue, shuffle), queue, current, upcoming, session, profile, moved, shuffle)
    }

    private val mixed = listOf(
        song("a", "Adele", genre = "Pop"), song("b", "Tool", genre = "Metal"), song("c", "Bjork", genre = "Electronic"),
        song("d", "Opeth", genre = "Metal"), song("e", "Adele", genre = "Pop"), song("f", "Muse", genre = "Rock"),
        song("g", "Bjork", genre = "Electronic"), song("h", "Tool", genre = "Metal"),
    )

    // Scenario: shuffling everything.

    @Test
    fun `three skips on shuffle reshuffle what's next by mood`() {
        val move = decide(Trigger.Skipped(mixed.take(3)), mixed, current = mixed[3])
        assertEquals(Move.Reorder(QueueChange.Reshuffled), move)
        assertNull(decide(Trigger.Skipped(mixed.take(1)), mixed, current = mixed[1]))
    }

    @Test
    fun `staying on a song after skips brings songs like it, without the skipped artists`() {
        val move = decide(Trigger.Stayed(mixed[3], mixed.take(3)), mixed, current = mixed[3]) as Move.Replace
        assertEquals(QueueChange.MoreLikeThis, move.change)
        val request = move.request as CandidateRequest.LikeTrack
        assertEquals("d", request.seed.id)
        assertEquals(listOf("ar-Adele", "ar-Tool", "ar-Bjork"), request.avoidArtists)
        assertEquals(mixed.map { it.id }, request.exclude)
    }

    @Test
    fun `staying on a song you got to with one skip is just listening`() {
        assertNull(decide(Trigger.Stayed(mixed[1], mixed.take(1)), mixed, current = mixed[1]))
    }

    @Test
    fun `skipping one artist or genre twice moves the rest of it back`() {
        val session = ListeningSession { 0 }.apply {
            record(mixed[1], Outcome.QuickSkip) // Tool, Metal
            record(mixed[3], Outcome.QuickSkip) // Opeth, Metal
        }.taste(TasteProfile())
        val queue = listOf(mixed[1], mixed[3], mixed[5], mixed[7])
        val move = decide(Trigger.Skipped(queue.take(2)), queue, current = queue[2], session = session)
        assertEquals(Move.Reorder(QueueChange.MoodShift), move)
        // Not with the queue in order: that's someone's playlist.
        assertNull(decide(Trigger.Skipped(queue.take(2)), queue, current = queue[2], session = session, shuffle = false))
    }

    @Test
    fun `six skips start fresh from what you liked, else what you play at this hour`() {
        val profile = TasteProfile(dayparts = listOf(Daypart(20, mapOf("jazz" to 0.9), mapOf("ar-Coltrane" to 0.8))))
        val move = decide(Trigger.Skipped(mixed.take(6)), mixed, current = mixed[6], profile = profile) as Move.Replace
        assertEquals(QueueChange.FreshStart, move.change)
        val request = move.request as CandidateRequest.Mood
        assertEquals(listOf("ar-Coltrane"), request.artistIds)
        assertEquals(listOf("jazz"), request.genres)
        assertTrue(request.avoidArtists.containsAll(listOf("ar-Adele", "ar-Tool", "ar-Bjork", "ar-Opeth")))
    }

    // Scenario: one artist's songs.

    private val toolTop = listOf(
        song("t1", "Tool", album = "lateralus"), song("t2", "Tool", album = "lateralus"), song("t3", "Tool", album = "aenima"),
        song("t4", "Tool", album = "lateralus"), song("t5", "Tool", album = "10k"), song("t6", "Tool", album = "fear"),
    )

    @Test
    fun `skipping one album of an artist's moves on to their other albums`() {
        val skipped = listOf(toolTop[0], toolTop[1], toolTop[2])
        val move = decide(Trigger.Skipped(skipped), toolTop, current = toolTop[3]) as Move.Replace
        assertEquals(QueueChange.OtherAlbum, move.change)
        assertEquals(listOf("lateralus"), (move.request as CandidateRequest.ArtistSongs).excludeAlbums)
    }

    @Test
    fun `skipping across an artist's albums moves on to similar artists`() {
        val queue = listOf(toolTop[0], toolTop[2], toolTop[4], toolTop[5])
        val move = decide(Trigger.Skipped(queue.take(3)), queue, current = queue[3]) as Move.Replace
        assertEquals(QueueChange.OtherArtist, move.change)
        val request = move.request as CandidateRequest.Mood
        assertEquals(listOf("ar-Tool"), request.artistIds)
        assertEquals(listOf("ar-Tool"), request.avoidArtists)
    }

    @Test
    fun `staying on one of their songs after skips keeps to the artist`() {
        val move = decide(Trigger.Stayed(toolTop[3], toolTop.take(3)), toolTop, current = toolTop[3]) as Move.Replace
        assertEquals(QueueChange.MoreLikeThis, move.change)
        val request = move.request as CandidateRequest.ArtistSongs
        assertEquals(listOf("aenima"), request.excludeAlbums) // not the album you stayed on
    }

    // Scenario: an album in order.

    private val album = (1..10).map { song("l$it", "Tool", album = "lateralus", n = it) }

    @Test
    fun `skipping to a song on an album is picking it`() {
        assertNull(decide(Trigger.Skipped(album.take(3)), album, current = album[3], shuffle = false))
        assertNull(decide(Trigger.Stayed(album[3], album.take(3)), album, current = album[3], shuffle = false))
    }

    @Test
    fun `skipping through most of an album lines up a different one`() {
        val move = decide(Trigger.Skipped(album.take(4)), album, current = album[4], shuffle = false) as Move.Replace
        assertEquals(QueueChange.NextAlbum, move.change)
        assertEquals("lateralus", (move.request as CandidateRequest.NextAlbum).albumId)
    }

    @Test
    fun `queue shapes`() {
        assertEquals(QueueShape.Album, QueueShape.of(album, shuffle = false))
        assertEquals(QueueShape.Artist("ar-Tool"), QueueShape.of(album, shuffle = true))
        assertEquals(QueueShape.Artist("ar-Tool"), QueueShape.of(toolTop, shuffle = false))
        assertEquals(QueueShape.Mixed, QueueShape.of(mixed, shuffle = false))
    }
}
