package app.rev.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.R as CoreR
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.message
import app.rev.domain.model.ServiceHealth
import app.rev.domain.model.ServiceState
import app.rev.domain.model.SoularrState
import app.rev.domain.model.SystemStatus
import kotlinx.serialization.Serializable

@Serializable
data object SystemStatusRoute

/** §39: health of the self-hosted stack, for when something isn't working. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemStatusScreen(onBack: () -> Unit, viewModel: SystemStatusViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreR.string.navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val status = state.status
            val error = state.error
            when {
                status != null -> PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::refresh) {
                    StatusContent(status)
                }
                error != null -> CenteredMessage(error.message()) {
                    TextButton(onClick = viewModel::refresh) { Text(stringResource(CoreR.string.action_retry)) }
                }
                else -> LoadingView()
            }
        }
    }
}

@Composable
private fun StatusContent(status: SystemStatus) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Card(stringResource(R.string.system_services)) {
            ServiceRow(stringResource(R.string.system_music_server), "Navidrome", status.navidrome)
            ServiceRow(stringResource(R.string.system_library_manager), "Lidarr", status.lidarr)
            ServiceRow(stringResource(R.string.system_download_engine), "slskd", status.slskd)
            SoularrRow(status)
        }
        Spacer(Modifier.height(20.dp))
        Card(stringResource(R.string.system_library)) {
            CountRow(stringResource(R.string.system_artists), status.libraryArtists)
            CountRow(stringResource(R.string.system_albums), status.libraryAlbums)
            CountRow(stringResource(R.string.system_tracks), status.libraryTracks)
        }
        Spacer(Modifier.height(20.dp))
        Card(stringResource(R.string.system_downloads)) {
            CountRow(stringResource(R.string.system_active_downloads), status.activeDownloads)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** This page is for debugging the stack, so it names the actual services under friendly roles. */
@Composable
private fun ServiceRow(role: String, service: String, health: ServiceHealth) {
    val (label, color) = when (health.state) {
        ServiceState.Online -> R.string.service_online to Green
        ServiceState.Degraded -> R.string.service_degraded to Amber
        ServiceState.Offline -> R.string.service_offline to Red
        ServiceState.NotConfigured -> R.string.service_not_configured to MaterialTheme.colorScheme.outline
    }
    StatusLine(
        title = "$role · $service",
        subtitle = listOfNotNull(health.version?.let { "v$it" }, health.detail).joinToString(" · ").ifEmpty { null },
        status = stringResource(label),
        color = color,
    )
}

@Composable
private fun SoularrRow(status: SystemStatus) {
    val (label, color) = when (status.soularr) {
        SoularrState.Active -> R.string.soularr_active to Green
        SoularrState.Idle -> R.string.soularr_idle to Amber
        SoularrState.Unknown -> R.string.soularr_unknown to MaterialTheme.colorScheme.outline
    }
    StatusLine(
        title = "${stringResource(R.string.system_acquisition)} · Soularr",
        subtitle = status.soularrLastActivityAt?.let {
            stringResource(R.string.soularr_last_activity, DateUtils.getRelativeTimeSpanString(it).toString())
        },
        status = stringResource(label),
        color = color,
    )
}

@Composable
private fun StatusLine(title: String, subtitle: String?, status: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CountRow(label: String, value: Int?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value?.let { "%,d".format(it) } ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}

private val Green = Color(0xFF5FB98A)
private val Amber = Color(0xFFD1A85A)
private val Red = Color(0xFFD9655F)
