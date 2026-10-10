package app.rev.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.LoadingView
import app.rev.domain.playback.EqualizerInfo
import app.rev.domain.playback.EqualizerSettings
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import app.rev.core.R as CoreR

@Serializable
data object EqualizerRoute

/** The built-in equalizer: on/off, the phone's presets, a slider per band and bass boost. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(onBack: () -> Unit, viewModel: EqualizerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.equalizer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreR.string.navigate_back))
                    }
                },
                actions = {
                    if (state.info != null) {
                        TextButton(onClick = viewModel::reset, enabled = state.settings.enabled) {
                            Text(stringResource(R.string.equalizer_reset))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        val info = state.info
        when {
            state.checking -> LoadingView(Modifier.padding(padding))
            info == null -> CenteredMessage(stringResource(R.string.equalizer_unavailable), Modifier.padding(padding))
            else -> EqualizerContent(info, state.settings, viewModel, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerContent(info: EqualizerInfo, settings: EqualizerSettings, viewModel: EqualizerViewModel, modifier: Modifier) {
    val enabled = settings.enabled
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.equalizer_enable), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.equalizer_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
        }

        if (info.presets.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.equalizer_presets), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                info.presets.forEach { preset ->
                    FilterChip(
                        selected = settings.preset == preset.name,
                        onClick = { viewModel.selectPreset(preset) },
                        label = { Text(preset.name) },
                        enabled = enabled,
                    )
                }
                if (settings.preset == null && settings.bandLevelsMb.any { it != 0 }) {
                    FilterChip(selected = true, onClick = {}, label = { Text(stringResource(R.string.equalizer_custom)) }, enabled = enabled)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.equalizer_bands), style = MaterialTheme.typography.titleSmall)
        info.bands.forEachIndexed { index, band ->
            BandSlider(
                label = frequencyLabel(band.centerHz),
                levelMb = settings.bandLevelsMb.getOrElse(index) { 0 },
                range = info.minLevelMb..info.maxLevelMb,
                enabled = enabled,
                onChange = { viewModel.setBand(index, it) },
            )
        }

        if (info.bassBoostSupported) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.equalizer_bass_boost), style = MaterialTheme.typography.titleSmall)
            var bass by remember(settings.bassBoost) { mutableFloatStateOf(settings.bassBoost.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = bass,
                    onValueChange = { bass = it },
                    onValueChangeFinished = { viewModel.setBassBoost(bass.roundToInt()) },
                    valueRange = 0f..1000f,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(bass / 10).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp).padding(start = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BandSlider(label: String, levelMb: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    // Follows the finger while dragging; saved (and heard) when let go.
    var value by remember(levelMb) { mutableFloatStateOf(levelMb.toFloat()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(value.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.equalizer_db, "%+.1f".format(value / 100f)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp).padding(start = 12.dp),
        )
    }
}

private fun frequencyLabel(hz: Int): String =
    if (hz >= 1000) "${"%.1f".format(hz / 1000f).removeSuffix(".0").removeSuffix(",0")} kHz" else "$hz Hz"
