package app.rev.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Applies a gain to decoded PCM for ReplayGain. Unlike player volume, this can also *raise* quiet
 * tracks (the gain itself is already capped by the track's peak, see ReplayGainMath). Gain changes
 * are ramped over ~40 ms so switching tracks or settings never clicks. Formats other than 16-bit
 * and float PCM pass through untouched.
 */
@OptIn(UnstableApi::class)
class ReplayGainAudioProcessor : BaseAudioProcessor() {
    @Volatile private var targetGain = 1f
    private var currentGain = 1f
    private var rampStep = 0f

    /** Thread-safe; takes effect on the audio being processed next. */
    fun setGain(gain: Float) {
        targetGain = gain.coerceIn(0f, MAX_GAIN)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET // inactive: passthrough
        }
        rampStep = 1f / (inputAudioFormat.sampleRate * RAMP_SECONDS)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val channels = inputAudioFormat.channelCount
        val target = targetGain
        var gain = currentGain
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = channels * if (float) 4 else 2

        while (inputBuffer.remaining() >= frameBytes) {
            // Ramp once per frame so all channels get the same gain.
            if (gain != target) {
                gain = if (abs(target - gain) <= rampStep * MAX_GAIN) target else gain + rampStep * MAX_GAIN * sign(target - gain)
            }
            repeat(channels) {
                if (float) {
                    output.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
                } else {
                    output.putShort((inputBuffer.short * gain).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort())
                }
            }
        }
        // Any trailing partial frame (shouldn't happen) is passed through unchanged.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        currentGain = gain
        output.flip()
    }

    /** After a seek or stream change there's nothing to blend with: start at the target gain. */
    override fun onFlush() {
        currentGain = targetGain
    }

    override fun onReset() {
        currentGain = targetGain
    }

    private fun sign(value: Float) = if (value < 0) -1f else 1f

    private companion object {
        const val RAMP_SECONDS = 0.04f
        /** ~+12 dB; ReplayGain rarely asks for more and the peak cap usually binds first. */
        const val MAX_GAIN = 4f
    }
}
