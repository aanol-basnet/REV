package app.rev.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rev.domain.playback.EqualizerSettings
import app.rev.domain.playback.PlaybackSettings
import app.rev.domain.playback.PlaybackSettingsRepository
import app.rev.domain.playback.ReplayGainMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PlaybackSettingsStore(private val dataStore: DataStore<Preferences>) : PlaybackSettingsRepository {
    override val settings: Flow<PlaybackSettings> = dataStore.data.map { prefs ->
        PlaybackSettings(
            replayGain = ReplayGainMode.entries.firstOrNull { it.name == prefs[REPLAY_GAIN] } ?: ReplayGainMode.Album,
            crossfadeSeconds = (prefs[CROSSFADE] ?: 0).coerceIn(0, MAX_CROSSFADE_SECONDS),
            autoplay = prefs[AUTOPLAY] ?: true,
            equalizer = EqualizerSettings(
                enabled = prefs[EQ_ENABLED] ?: false,
                bandLevelsMb = prefs[EQ_BANDS].orEmpty().split(',').mapNotNull { it.toIntOrNull() },
                preset = prefs[EQ_PRESET],
                bassBoost = (prefs[EQ_BASS] ?: 0).coerceIn(0, MAX_BASS_BOOST),
            ),
        )
    }.distinctUntilChanged()

    override suspend fun setReplayGain(mode: ReplayGainMode) {
        dataStore.edit { it[REPLAY_GAIN] = mode.name }
    }

    override suspend fun setCrossfadeSeconds(seconds: Int) {
        dataStore.edit { it[CROSSFADE] = seconds.coerceIn(0, MAX_CROSSFADE_SECONDS) }
    }

    override suspend fun setAutoplay(enabled: Boolean) {
        dataStore.edit { it[AUTOPLAY] = enabled }
    }

    override suspend fun setEqualizer(equalizer: EqualizerSettings) {
        dataStore.edit {
            it[EQ_ENABLED] = equalizer.enabled
            it[EQ_BANDS] = equalizer.bandLevelsMb.joinToString(",")
            val preset = equalizer.preset
            if (preset != null) it[EQ_PRESET] = preset else it.remove(EQ_PRESET)
            it[EQ_BASS] = equalizer.bassBoost.coerceIn(0, MAX_BASS_BOOST)
        }
    }

    companion object {
        const val MAX_CROSSFADE_SECONDS = 12
        const val MAX_BASS_BOOST = 1000
        private val REPLAY_GAIN = stringPreferencesKey("replay_gain")
        private val CROSSFADE = intPreferencesKey("crossfade_seconds")
        private val AUTOPLAY = booleanPreferencesKey("autoplay")
        private val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val EQ_BANDS = stringPreferencesKey("eq_bands_mb")
        private val EQ_PRESET = stringPreferencesKey("eq_preset")
        private val EQ_BASS = intPreferencesKey("eq_bass_boost")
    }
}
