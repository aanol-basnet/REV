package app.rev.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Renderers whose audio sink runs decoded audio through [processor] (ReplayGain). Audio offload is
 * left off on purpose: offloaded audio bypasses processors. Gapless playback is ExoPlayer's
 * default for consecutive queue items (encoder delay/padding from FLAC, MP3 LAME/iTunes and AAC).
 */
@OptIn(UnstableApi::class)
internal fun Context.replayGainRenderers(processor: ReplayGainAudioProcessor): DefaultRenderersFactory =
    object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(processor))
            .build()
    }
