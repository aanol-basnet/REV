package app.rev.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.AuthState
import app.rev.domain.model.ConnectionState
import app.rev.domain.repository.AuthRepository
import app.rev.domain.playback.PlaybackSettings
import app.rev.domain.playback.PlaybackSettingsRepository
import app.rev.domain.playback.ReplayGainMode
import app.rev.domain.repository.AppTheme
import app.rev.domain.repository.AppearanceRepository
import app.rev.domain.repository.ConnectionRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.model.SmartDownloadSettings
import app.rev.domain.model.StorageUsage
import app.rev.domain.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import app.rev.domain.model.Result
import app.rev.domain.model.ServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Outcome of saving the home-network address, shown under the field. */
sealed interface LocalAddressFeedback {
    data object Checking : LocalAddressFeedback
    /** Saved; [reachableNow] tells whether it answered just now (it may simply be away from home). */
    data class Saved(val reachableNow: Boolean) : LocalAddressFeedback
    data object Removed : LocalAddressFeedback
    data object Automatic : LocalAddressFeedback
    data object Invalid : LocalAddressFeedback
    /** Plain HTTP to a public address: refused, the session would travel unencrypted. */
    data object NotLocal : LocalAddressFeedback
    data object DifferentServer : LocalAddressFeedback
}

data class SettingsUiState(
    val serverUrl: String? = null,
    /** Home-network address, if set. */
    val localUrl: String? = null,
    /** Whether REV finds (and follows) the home-network address by itself. */
    val localDiscovery: Boolean = true,
    val localFeedback: LocalAddressFeedback? = null,
    val username: String? = null,
    val connection: ConnectionState = ConnectionState.Checking,
    val storage: StorageUsage? = null,
    val wifiOnly: Boolean = true,
    val amoled: Boolean = false,
    val playback: PlaybackSettings = PlaybackSettings(),
    val smart: SmartDownloadSettings = SmartDownloadSettings(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val servers: ServerRepository,
    private val connection: ConnectionRepository,
    private val offline: OfflineRepository,
    private val appearance: AppearanceRepository,
    private val playbackSettings: PlaybackSettingsRepository,
) : ViewModel() {

    private val localFeedback = MutableStateFlow<LocalAddressFeedback?>(null)

    val state: StateFlow<SettingsUiState> =
        combine(
            auth.authState,
            connection.connectionState,
            offline.observeStorage(),
            offline.wifiOnly,
            appearance.theme,
        ) { authState, connectionState, storage, wifiOnly, theme ->
            val signedIn = authState as? AuthState.SignedIn
            SettingsUiState(
                serverUrl = signedIn?.server?.url?.value,
                localUrl = signedIn?.server?.localUrl?.value,
                localDiscovery = signedIn?.server?.localDiscovery ?: true,
                username = signedIn?.user?.username,
                connection = connectionState,
                storage = storage,
                wifiOnly = wifiOnly,
                amoled = theme == AppTheme.Amoled,
            )
        }.combine(playbackSettings.settings) { state, playback -> state.copy(playback = playback) }
            .combine(offline.smartDownloads) { state, smart -> state.copy(smart = smart) }
            .combine(localFeedback) { state, feedback -> state.copy(localFeedback = feedback) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Saves the server's home-network address (blank removes it). The session is kept. */
    fun saveLocalUrl(input: String) {
        viewModelScope.launch {
            if (input.isBlank()) {
                servers.setLocalUrl(null)
                localFeedback.value = LocalAddressFeedback.Removed
                connection.recheck()
                return@launch
            }
            val url = (ServerUrl.parse(input) as? Result.Ok)?.value
            if (url == null) {
                localFeedback.value = LocalAddressFeedback.Invalid
                return@launch
            }
            if (url.isInsecure) {
                localFeedback.value = LocalAddressFeedback.NotLocal
                return@launch
            }
            val config = servers.serverConfig.first() ?: return@launch
            localFeedback.value = LocalAddressFeedback.Checking
            localFeedback.value = when (val probe = servers.probe(url)) {
                is Result.Ok -> if (probe.value.instanceId == config.instanceId) {
                    servers.setLocalUrl(url)
                    LocalAddressFeedback.Saved(reachableNow = true)
                } else {
                    LocalAddressFeedback.DifferentServer
                }
                // Probably just not at home right now; it's only used once it answers as this server.
                is Result.Err -> {
                    servers.setLocalUrl(url)
                    LocalAddressFeedback.Saved(reachableNow = false)
                }
            }
            connection.recheck()
        }
    }

    fun useAutomaticLocalAddress() {
        viewModelScope.launch {
            servers.setLocalDiscovery(true)
            localFeedback.value = LocalAddressFeedback.Automatic
            connection.recheck()
        }
    }

    fun setSmartDownloads(settings: SmartDownloadSettings) {
        viewModelScope.launch { offline.setSmartDownloads(settings) }
    }

    fun setReplayGain(mode: ReplayGainMode) {
        viewModelScope.launch { playbackSettings.setReplayGain(mode) }
    }

    fun setCrossfade(seconds: Int) {
        viewModelScope.launch { playbackSettings.setCrossfadeSeconds(seconds) }
    }

    fun setAutoplay(enabled: Boolean) {
        viewModelScope.launch { playbackSettings.setAutoplay(enabled) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { offline.setWifiOnly(enabled) }
    }

    fun setAmoled(enabled: Boolean) {
        viewModelScope.launch { appearance.setTheme(if (enabled) AppTheme.Amoled else AppTheme.Dark) }
    }

    fun removeAllDownloads() {
        viewModelScope.launch { offline.removeAll() }
    }

    fun recheck() = connection.recheck()

    fun signOut() {
        viewModelScope.launch { auth.logout() }
    }

    fun changeServer() {
        viewModelScope.launch { servers.clearServer() }
    }
}
