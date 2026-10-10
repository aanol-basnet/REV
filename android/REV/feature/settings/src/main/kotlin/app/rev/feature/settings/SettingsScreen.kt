package app.rev.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.format.formatFileSize
import app.rev.core.ui.theme.RevTheme
import app.rev.domain.model.ConnectionRoute
import app.rev.domain.model.ConnectionState
import app.rev.domain.model.ServerInfo
import app.rev.domain.model.SmartDownloadSettings
import app.rev.domain.playback.ReplayGainMode

@Composable
fun SettingsRoute(onOpenSystemStatus: () -> Unit, onOpenEqualizer: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    SettingsScreen(
        state = state,
        appVersion = appVersion,
        onRecheck = viewModel::recheck,
        onSignOut = viewModel::signOut,
        onChangeServer = viewModel::changeServer,
        onOpenSystemStatus = onOpenSystemStatus,
        onOpenEqualizer = onOpenEqualizer,
        onWifiOnlyChange = viewModel::setWifiOnly,
        onRemoveAllDownloads = viewModel::removeAllDownloads,
        onAmoledChange = viewModel::setAmoled,
        onReplayGainChange = viewModel::setReplayGain,
        onCrossfadeChange = viewModel::setCrossfade,
        onAutoplayChange = viewModel::setAutoplay,
        onSmartChange = viewModel::setSmartDownloads,
        onSaveLocalUrl = viewModel::saveLocalUrl,
        onAutomaticLocalUrl = viewModel::useAutomaticLocalAddress,
    )
}

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String? = null,
    onRecheck: () -> Unit,
    onSignOut: () -> Unit,
    onChangeServer: () -> Unit,
    onOpenSystemStatus: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onWifiOnlyChange: (Boolean) -> Unit = {},
    onRemoveAllDownloads: () -> Unit = {},
    onAmoledChange: (Boolean) -> Unit = {},
    onReplayGainChange: (ReplayGainMode) -> Unit = {},
    onCrossfadeChange: (Int) -> Unit = {},
    onAutoplayChange: (Boolean) -> Unit = {},
    onSmartChange: (SmartDownloadSettings) -> Unit = {},
    onSaveLocalUrl: (String) -> Unit = {},
    onAutomaticLocalUrl: () -> Unit = {},
) {
    var confirmRemoveAll by remember { mutableStateOf(false) }
    var editLocal by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(28.dp))

        Section(stringResource(R.string.settings_section_server)) {
            Field(stringResource(R.string.settings_address), state.serverUrl ?: "—")
            Field(stringResource(R.string.settings_local_address)) {
                TextButton(onClick = { editLocal = true }) {
                    Text(
                        state.localUrl ?: stringResource(
                            if (state.localDiscovery) R.string.settings_local_address_add else R.string.settings_local_address_off,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            localFeedbackText(state.localFeedback)?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Field(stringResource(R.string.settings_status)) {
                ConnectionBadge(state.connection, showRoute = state.localUrl != null)
            }
            (state.connection as? ConnectionState.Online)?.let {
                Field(stringResource(R.string.settings_version), it.info.version)
            }
            connectionHint(state.connection)?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRecheck) { Text(stringResource(R.string.settings_check_now)) }
                TextButton(onClick = onChangeServer) { Text(stringResource(R.string.settings_change_server)) }
            }
            TextButton(onClick = onOpenSystemStatus) { Text(stringResource(R.string.settings_system_status)) }
        }

        Spacer(Modifier.height(20.dp))

        Section(stringResource(R.string.settings_section_playback)) {
            Text(stringResource(R.string.playback_replaygain), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = ReplayGainMode.entries
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.playback.replayGain == mode,
                        onClick = { onReplayGainChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) {
                        Text(
                            stringResource(
                                when (mode) {
                                    ReplayGainMode.Off -> R.string.playback_replaygain_off
                                    ReplayGainMode.Track -> R.string.playback_replaygain_track
                                    ReplayGainMode.Album -> R.string.playback_replaygain_album
                                },
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            var crossfade by remember(state.playback.crossfadeSeconds) { mutableFloatStateOf(state.playback.crossfadeSeconds.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.playback_crossfade), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    if (crossfade.toInt() == 0) {
                        stringResource(R.string.playback_crossfade_off)
                    } else {
                        stringResource(R.string.playback_crossfade_seconds, crossfade.toInt())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = crossfade,
                onValueChange = { crossfade = it },
                onValueChangeFinished = { onCrossfadeChange(crossfade.toInt()) },
                valueRange = 0f..12f,
                steps = 11,
            )
            Text(
                stringResource(R.string.playback_crossfade_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.playback_autoplay), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.playback_autoplay_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.playback.autoplay, onCheckedChange = onAutoplayChange)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenEqualizer)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.equalizer_title), style = MaterialTheme.typography.bodyMedium)
                    val eq = state.playback.equalizer
                    val preset = eq.preset
                    Text(
                        when {
                            !eq.enabled -> stringResource(R.string.equalizer_off)
                            preset != null -> stringResource(R.string.equalizer_on_preset, preset)
                            else -> stringResource(R.string.equalizer_on)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Section(stringResource(R.string.settings_section_appearance)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.appearance_amoled), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.appearance_amoled_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.amoled, onCheckedChange = onAmoledChange)
            }
        }

        Spacer(Modifier.height(20.dp))

        Section(stringResource(R.string.settings_section_storage)) {
            val storage = state.storage
            Field(
                stringResource(R.string.storage_used),
                storage?.let { pluralStringResource(R.plurals.storage_tracks, it.trackCount, formatFileSize(it.bytes), it.trackCount) } ?: "—",
            )
            Field(stringResource(R.string.storage_free), storage?.let { formatFileSize(it.availableBytes) } ?: "—")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.storage_wifi_only), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = state.wifiOnly, onCheckedChange = onWifiOnlyChange)
            }
            if ((storage?.trackCount ?: 0) > 0) {
                TextButton(onClick = { confirmRemoveAll = true }) { Text(stringResource(R.string.storage_remove_all)) }
            }
            SmartDownloadControls(state.smart, onSmartChange)
        }

        Spacer(Modifier.height(20.dp))

        Section(stringResource(R.string.settings_section_account)) {
            Field(stringResource(R.string.settings_signed_in_as), state.username ?: "—")
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_sign_out))
            }
        }

        if (appVersion != null) {
            Spacer(Modifier.height(28.dp))
            TextButton(onClick = { showChangelog = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(
                    stringResource(R.string.settings_app_version, appVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showChangelog) {
        ChangelogSheet(installedVersion = appVersion, onDismiss = { showChangelog = false })
    }
    if (editLocal) {
        LocalAddressDialog(
            current = state.localUrl,
            automaticOff = !state.localDiscovery,
            onSave = {
                onSaveLocalUrl(it)
                editLocal = false
            },
            onAutomatic = {
                onAutomaticLocalUrl()
                editLocal = false
            },
            onDismiss = { editLocal = false },
        )
    }
    if (confirmRemoveAll) {
        RemoveAllDialog(
            onConfirm = {
                confirmRemoveAll = false
                onRemoveAllDownloads()
            },
            onDismiss = { confirmRemoveAll = false },
        )
    }
}

/** Smart downloads: favorites, recent albums, and how much space they may use. */
@Composable
private fun SmartDownloadControls(smart: SmartDownloadSettings, onChange: (SmartDownloadSettings) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.smart_title), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.smart_favorites), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = smart.favorites, onCheckedChange = { onChange(smart.copy(favorites = it)) })
    }
    Text(stringResource(R.string.smart_recent), style = MaterialTheme.typography.bodyMedium)
    val recentOptions = listOf(0, 5, 10, 20)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        recentOptions.forEachIndexed { index, count ->
            SegmentedButton(
                selected = smart.recentAlbums == count,
                onClick = { onChange(smart.copy(recentAlbums = count)) },
                shape = SegmentedButtonDefaults.itemShape(index, recentOptions.size),
            ) { Text(if (count == 0) stringResource(R.string.smart_recent_off) else count.toString()) }
        }
    }
    if (smart.enabled) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.smart_limit), style = MaterialTheme.typography.bodyMedium)
        val limits = listOf(2, 5, 10, 20)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            limits.forEachIndexed { index, gb ->
                SegmentedButton(
                    selected = smart.limitBytes == gb * GIB,
                    onClick = { onChange(smart.copy(limitBytes = gb * GIB)) },
                    shape = SegmentedButtonDefaults.itemShape(index, limits.size),
                ) { Text(stringResource(R.string.smart_limit_value, gb)) }
            }
        }
    }
    Text(
        stringResource(R.string.smart_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

private const val GIB = 1024L * 1024 * 1024

@Composable
private fun LocalAddressDialog(
    current: String?,
    automaticOff: Boolean,
    onSave: (String) -> Unit,
    onAutomatic: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_local_address)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_local_address_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("http://192.168.1.10:4600") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.settings_local_address_save)) } },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.settings_local_address_remove)) }
                } else if (automaticOff) {
                    TextButton(onClick = onAutomatic) { Text(stringResource(R.string.settings_local_address_automatic)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_cancel)) }
            }
        },
    )
}

private fun localFeedbackText(feedback: LocalAddressFeedback?): Int? = when (feedback) {
    null -> null
    LocalAddressFeedback.Checking -> R.string.local_feedback_checking
    is LocalAddressFeedback.Saved ->
        if (feedback.reachableNow) R.string.local_feedback_saved_reachable else R.string.local_feedback_saved_unreachable
    LocalAddressFeedback.Removed -> R.string.local_feedback_removed
    LocalAddressFeedback.Automatic -> R.string.local_feedback_automatic
    LocalAddressFeedback.Invalid -> R.string.local_feedback_invalid
    LocalAddressFeedback.NotLocal -> R.string.local_feedback_not_local
    LocalAddressFeedback.DifferentServer -> R.string.local_feedback_different_server
}

@Composable
private fun RemoveAllDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.storage_remove_all_confirm)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.storage_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_cancel)) } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Field(label: String, value: String) = Field(label) {
    Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Field(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        value()
    }
}

@Composable
private fun ConnectionBadge(connection: ConnectionState, showRoute: Boolean) {
    val (labelRes, color) = when (connection) {
        is ConnectionState.Online -> when {
            !showRoute -> R.string.connection_online
            connection.route == ConnectionRoute.Local -> R.string.connection_home_network
            else -> R.string.connection_remote
        } to StatusGreen
        ConnectionState.Checking -> R.string.connection_checking to StatusAmber
        ConnectionState.NoNetwork -> R.string.connection_no_network to StatusRed
        ConnectionState.ServerUnreachable -> R.string.connection_unreachable to StatusRed
        is ConnectionState.WrongServer -> R.string.connection_wrong_server to StatusRed
        ConnectionState.NotConfigured -> R.string.connection_not_configured to StatusAmber
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun connectionHint(connection: ConnectionState): Int? = when (connection) {
    ConnectionState.NoNetwork -> R.string.connection_hint_no_network
    ConnectionState.ServerUnreachable -> R.string.connection_hint_unreachable
    is ConnectionState.WrongServer -> R.string.connection_hint_wrong_server
    else -> null
}

// Muted status colours: legible on charcoal without turning the UI into a dashboard.
private val StatusGreen = Color(0xFF5FB98A)
private val StatusAmber = Color(0xFFD1A85A)
private val StatusRed = Color(0xFFD9655F)

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C)
@Composable
private fun SettingsPreview() {
    RevTheme {
        SettingsScreen(
            SettingsUiState(
                serverUrl = "http://100.64.0.1:4600",
                username = "alice",
                connection = ConnectionState.Online(ServerInfo("0.1.0", "abc")),
            ),
            appVersion = "0.2.0",
            onRecheck = {},
            onSignOut = {},
            onChangeServer = {},
            onOpenSystemStatus = {},
        )
    }
}
