package app.rev.domain.playback

import app.rev.domain.model.ReplayGain
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.flow.Flow

enum class ReplayGainMode { Off, Track, Album }

data class PlaybackSettings(
    val replayGain: ReplayGainMode = ReplayGainMode.Album,
    /** Extra gain applied on top of ReplayGain, in dB. */
    val preampDb: Double = 0.0,
    /** 0 disables crossfade. */
    val crossfadeSeconds: Int = 0,
    /** When the queue runs out, keep playing: the next album, or similar songs. */
    val autoplay: Boolean = true,
    /** Skipping a few songs in a row and staying on one refills the queue with songs like it. */
    val followSkips: Boolean = true,
    val equalizer: EqualizerSettings = EqualizerSettings(),
)

/** The built-in equalizer: the phone's own audio effects, applied to REV's playback only. */
data class EqualizerSettings(
    val enabled: Boolean = false,
    /** Gain per band in millibels, in the order of [EqualizerInfo.bands]; empty is flat. */
    val bandLevelsMb: List<Int> = emptyList(),
    /** The device preset the bands came from; null once they're changed by hand. */
    val preset: String? = null,
    /** Bass boost strength, 0–1000. */
    val bassBoost: Int = 0,
)

/** What this phone's equalizer offers; bands, ranges and presets differ between devices. */
data class EqualizerInfo(
    val bands: List<EqualizerBand>,
    /** Lowest and highest band gain, in millibels. */
    val minLevelMb: Int,
    val maxLevelMb: Int,
    val presets: List<EqualizerPreset>,
    val bassBoostSupported: Boolean,
)

data class EqualizerBand(val centerHz: Int)

data class EqualizerPreset(val name: String, val bandLevelsMb: List<Int>)

/** Asks the phone what its equalizer can do; null when it has none REV can use. */
fun interface EqualizerCapabilities {
    suspend fun describe(): EqualizerInfo?
}

interface PlaybackSettingsRepository {
    val settings: Flow<PlaybackSettings>
    suspend fun setReplayGain(mode: ReplayGainMode)
    suspend fun setCrossfadeSeconds(seconds: Int)
    suspend fun setAutoplay(enabled: Boolean)
    suspend fun setFollowSkips(enabled: Boolean)
    suspend fun setEqualizer(equalizer: EqualizerSettings)
}

/** Turns ReplayGain tags into a linear volume factor. */
object ReplayGainMath {
    /**
     * Album mode prefers album gain (keeps an album's internal dynamics), Track mode prefers track
     * gain; each falls back to the other. The result never lets the track's peak exceed full scale,
     * so a positive gain on a loud master can't clip. Untagged tracks play unchanged.
     */
    fun linearGain(gain: ReplayGain?, mode: ReplayGainMode, preampDb: Double = 0.0): Float {
        if (gain == null || mode == ReplayGainMode.Off) return 1f
        val (db, peak) = when (mode) {
            ReplayGainMode.Album -> (gain.albumGainDb ?: gain.trackGainDb) to (gain.albumPeak ?: gain.trackPeak)
            ReplayGainMode.Track -> (gain.trackGainDb ?: gain.albumGainDb) to (gain.trackPeak ?: gain.albumPeak)
            ReplayGainMode.Off -> null to null
        }
        if (db == null) return 1f
        val linear = 10.0.pow((db + preampDb) / 20.0)
        val limited = if (peak != null && peak > 0) min(linear, 1.0 / peak) else linear
        return limited.toFloat()
    }
}
