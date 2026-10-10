package app.rev.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.playback.EqualizerCapabilities
import app.rev.domain.playback.EqualizerInfo
import app.rev.domain.playback.EqualizerPreset
import app.rev.domain.playback.EqualizerSettings
import app.rev.domain.playback.PlaybackSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EqualizerUiState(
    /** What this phone's equalizer offers; null while checking, or when it has none. */
    val info: EqualizerInfo? = null,
    val checking: Boolean = true,
    val settings: EqualizerSettings = EqualizerSettings(),
)

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val playbackSettings: PlaybackSettingsRepository,
    private val capabilities: EqualizerCapabilities,
) : ViewModel() {
    private val _state = MutableStateFlow(EqualizerUiState())
    val state: StateFlow<EqualizerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val info = capabilities.describe()
            _state.update { it.copy(info = info, checking = false) }
        }
        viewModelScope.launch {
            playbackSettings.settings.collect { s -> _state.update { it.copy(settings = s.equalizer) } }
        }
    }

    fun setEnabled(enabled: Boolean) = save { it.copy(enabled = enabled) }

    fun selectPreset(preset: EqualizerPreset) = save { it.copy(bandLevelsMb = preset.bandLevelsMb, preset = preset.name) }

    /** Back to flat: every band at 0 dB, no bass boost. */
    fun reset() = save { it.copy(bandLevelsMb = emptyList(), preset = null, bassBoost = 0) }

    fun setBand(band: Int, levelMb: Int) {
        val bands = _state.value.info?.bands?.size ?: return
        save { current ->
            val levels = List(bands) { i -> if (i == band) levelMb else current.bandLevelsMb.getOrElse(i) { 0 } }
            current.copy(bandLevelsMb = levels, preset = null)
        }
    }

    fun setBassBoost(strength: Int) = save { it.copy(bassBoost = strength.coerceIn(0, 1000)) }

    private fun save(change: (EqualizerSettings) -> EqualizerSettings) {
        val next = change(_state.value.settings)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch { playbackSettings.setEqualizer(next) }
    }
}
