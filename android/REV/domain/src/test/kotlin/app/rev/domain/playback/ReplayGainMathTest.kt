package app.rev.domain.playback

import app.rev.domain.model.ReplayGain
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayGainMathTest {
    // Real values from the reference library (Tool – Lateralus).
    private val lateralus = ReplayGain(trackGainDb = -8.15, albumGainDb = -6.63, trackPeak = 0.988678, albumPeak = 1.0)
    private val parabol = ReplayGain(trackGainDb = 5.18, albumGainDb = -6.63, trackPeak = 0.728058, albumPeak = 1.0)

    @Test
    fun `album mode uses album gain, track mode uses track gain`() {
        assertEquals(0.4660f, ReplayGainMath.linearGain(lateralus, ReplayGainMode.Album), 1e-3f)
        assertEquals(0.3917f, ReplayGainMath.linearGain(lateralus, ReplayGainMode.Track), 1e-3f)
    }

    @Test
    fun `positive gain is limited by the peak so it cannot clip`() {
        // +5.18 dB would be ×1.816, but the peak 0.728 allows at most ×1.373.
        assertEquals(1f / 0.728058f, ReplayGainMath.linearGain(parabol, ReplayGainMode.Track), 1e-4f)
    }

    @Test
    fun `falls back between track and album, and leaves untagged or disabled alone`() {
        assertEquals(
            ReplayGainMath.linearGain(ReplayGain(-3.0, null, null, null), ReplayGainMode.Track),
            ReplayGainMath.linearGain(ReplayGain(-3.0, null, null, null), ReplayGainMode.Album),
        )
        assertEquals(1f, ReplayGainMath.linearGain(null, ReplayGainMode.Album))
        assertEquals(1f, ReplayGainMath.linearGain(lateralus, ReplayGainMode.Off))
        assertEquals(1f, ReplayGainMath.linearGain(ReplayGain(null, null, 0.5, 0.5), ReplayGainMode.Album))
    }
}
