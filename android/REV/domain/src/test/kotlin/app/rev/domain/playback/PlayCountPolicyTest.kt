package app.rev.domain.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCountPolicyTest {
    @Test
    fun `half the track counts`() {
        assertFalse(PlayCountPolicy.counts(durationMs = 200_000, listenedMs = 99_000))
        assertTrue(PlayCountPolicy.counts(durationMs = 200_000, listenedMs = 100_000))
    }

    @Test
    fun `four minutes counts for long tracks`() {
        assertFalse(PlayCountPolicy.counts(durationMs = 1_200_000, listenedMs = 239_000))
        assertTrue(PlayCountPolicy.counts(durationMs = 1_200_000, listenedMs = 240_000))
    }

    @Test
    fun `very short tracks never count`() {
        assertFalse(PlayCountPolicy.counts(durationMs = 25_000, listenedMs = 25_000))
    }

    @Test
    fun `leaving a track early is a skip, but only once it was heard`() {
        assertTrue(PlayCountPolicy.isSkip(durationMs = 200_000, listenedMs = 5_000))
        assertFalse("never heard", PlayCountPolicy.isSkip(durationMs = 200_000, listenedMs = 0))
        assertFalse("already a play", PlayCountPolicy.isSkip(durationMs = 200_000, listenedMs = 100_000))
        assertFalse("too short to ever count", PlayCountPolicy.isSkip(durationMs = 25_000, listenedMs = 5_000))
        assertTrue("length unknown", PlayCountPolicy.isSkip(durationMs = 0, listenedMs = 5_000))
    }
}
