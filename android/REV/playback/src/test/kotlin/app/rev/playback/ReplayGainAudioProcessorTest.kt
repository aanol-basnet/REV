package app.rev.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import app.rev.domain.model.Track
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainAudioProcessorTest {
    private fun processor(encoding: Int, gain: Float) = ReplayGainAudioProcessor().apply {
        setGain(gain)
        configure(AudioFormat(48_000, 2, encoding))
        flush() // settles the ramp at the target gain
    }

    private fun shorts(vararg values: Short) =
        ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder()).apply {
            values.forEach { putShort(it) }
            flip()
        }

    private fun ReplayGainAudioProcessor.process(input: ByteBuffer): ByteBuffer {
        queueInput(input)
        return output
    }

    @Test
    fun `scales 16-bit audio and clips instead of wrapping`() {
        val out = processor(C.ENCODING_PCM_16BIT, 0.5f).process(shorts(1000, -1000, 20000, -20000))
        assertEquals(500, out.short.toInt())
        assertEquals(-500, out.short.toInt())

        val loud = processor(C.ENCODING_PCM_16BIT, 2f).process(shorts(20000, -20000))
        assertEquals(Short.MAX_VALUE, loud.short)
        assertEquals(Short.MIN_VALUE, loud.short)
    }

    @Test
    fun `scales float audio within full scale`() {
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).apply {
            putFloat(0.25f)
            putFloat(0.8f)
            flip()
        }
        val out = processor(C.ENCODING_PCM_FLOAT, 1.5f).process(input)
        assertEquals(0.375f, out.float, 1e-6f)
        assertEquals(1f, out.float, 1e-6f)
    }

    @Test
    fun `ramps gain changes instead of jumping`() {
        val p = processor(C.ENCODING_PCM_16BIT, 1f)
        p.setGain(0.25f)
        val out = p.process(shorts(10000, 10000))
        assertTrue("first frame is still near the old gain", out.short > 9000)
    }

    @Test
    fun `passes through formats it does not handle`() {
        val p = ReplayGainAudioProcessor()
        p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT))
        assertFalse(p.isActive)
    }
}

class GaplessSequenceTest {
    private fun track(album: String?, disc: Int?, no: Int?) = Track(
        "t", "T", "A", album, "Ar", null, no, disc, null, null, null, 1, false, null, null, null, null, null,
    )

    @Test
    fun `consecutive tracks of one album are a gapless sequence`() {
        assertTrue(isGaplessSequence(track("al", 1, 3), track("al", 1, 4)))
        assertTrue(isGaplessSequence(track("al", 1, 12), track("al", 2, 1)))
        assertFalse(isGaplessSequence(track("al", 1, 3), track("al", 1, 7)))
        assertFalse(isGaplessSequence(track("al", 1, 3), track("other", 1, 4)))
        assertFalse(isGaplessSequence(track(null, 1, 3), track(null, 1, 4)))
    }
}
