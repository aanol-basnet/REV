package app.rev.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import app.rev.domain.playback.EqualizerBand
import app.rev.domain.playback.EqualizerCapabilities
import app.rev.domain.playback.EqualizerInfo
import app.rev.domain.playback.EqualizerPreset
import app.rev.domain.playback.EqualizerSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The equalizer and bass boost on the player's audio session. Effects are only created once
 * turned on, and follow the session if the player gets a new one. The session is also announced
 * to equalizer apps (e.g. Wavelet), as music players do. Audio effects can fail on some devices;
 * playback carries on without them.
 */
@OptIn(UnstableApi::class)
internal class AudioEffects(private val context: Context) {
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var settings = EqualizerSettings()

    fun attach(sessionId: Int) {
        if (sessionId == this.sessionId) return
        release()
        this.sessionId = sessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        announce(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        apply(settings)
    }

    fun apply(settings: EqualizerSettings) {
        this.settings = settings
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        safely("equalizer") {
            if (settings.enabled) {
                val eq = equalizer ?: Equalizer(0, sessionId).also { equalizer = it }
                val (min, max) = eq.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
                for (band in 0 until eq.numberOfBands) {
                    val level = (settings.bandLevelsMb.getOrNull(band) ?: 0).coerceIn(min, max)
                    eq.setBandLevel(band.toShort(), level.toShort())
                }
                eq.enabled = true
            } else {
                equalizer?.enabled = false
            }
        }
        safely("bass boost") {
            if (settings.enabled && settings.bassBoost > 0) {
                val boost = bassBoost ?: BassBoost(0, sessionId).also { bassBoost = it }
                if (boost.strengthSupported) boost.setStrength(settings.bassBoost.toShort())
                boost.enabled = true
            } else {
                bassBoost?.enabled = false
            }
        }
    }

    fun release() {
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) announce(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        equalizer?.release()
        bassBoost?.release()
        equalizer = null
        bassBoost = null
        sessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun announce(action: String) {
        context.sendBroadcast(
            Intent(action)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't set up the $what", e)
        }
    }

    private companion object {
        const val TAG = "AudioEffects"
    }
}

/** Reads the phone's equalizer from a throwaway audio session. */
class DeviceEqualizerCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
) : EqualizerCapabilities {
    override suspend fun describe(): EqualizerInfo? = withContext(Dispatchers.Default) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return@withContext null
        val session = audio.generateAudioSessionId()
        val eq = runCatching { Equalizer(0, session) }.getOrNull() ?: return@withContext null
        try {
            val bands = (0 until eq.numberOfBands).map { EqualizerBand(eq.getCenterFreq(it.toShort()) / 1000) }
            val presets = (0 until eq.numberOfPresets).map { preset ->
                eq.usePreset(preset.toShort())
                EqualizerPreset(eq.getPresetName(preset.toShort()), bands.indices.map { eq.getBandLevel(it.toShort()).toInt() })
            }
            val bass = runCatching { BassBoost(0, session) }.getOrNull()
            val bassSupported = bass?.strengthSupported == true
            bass?.release()
            EqualizerInfo(bands, eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt(), presets, bassSupported)
        } catch (_: RuntimeException) {
            null
        } finally {
            eq.release()
        }
    }
}
