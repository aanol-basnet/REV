package app.rev.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.R as CoreR
import app.rev.core.ui.components.ArtworkImage
import app.rev.core.ui.components.CatalogCover
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.label
import app.rev.core.ui.components.message
import app.rev.domain.model.AlbumAvailability
import app.rev.domain.model.CatalogAlbumStatus
import app.rev.domain.model.DownloadJobState
import kotlinx.serialization.Serializable

@Serializable
data class CatalogAlbumRoute(val catalogAlbumId: String)

/** A catalog album's page: §37 "Not in your library / DOWNLOAD", progress while acquiring. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogAlbumScreen(
    onBack: () -> Unit,
    onOpenAlbum: (libraryId: String) -> Unit,
    viewModel: CatalogAlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreR.string.navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val status = state.status
            val loadError = state.loadError
            when {
                status != null -> AlbumStatusContent(
                    status = status,
                    catalogAlbumId = viewModel.catalogAlbumId,
                    busy = state.busy,
                    onDownload = viewModel::download,
                    onCancel = viewModel::cancel,
                    onOpenAlbum = onOpenAlbum,
                )
                loadError != null -> CenteredMessage(loadError.message()) {
                    TextButton(onClick = viewModel::load) { Text(stringResource(CoreR.string.action_retry)) }
                }
                else -> LoadingView()
            }
        }
    }
}

@Composable
private fun AlbumStatusContent(
    status: CatalogAlbumStatus,
    catalogAlbumId: String,
    busy: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkImage(
            CatalogCover(catalogAlbumId),
            contentDescription = null,
            modifier = Modifier.widthIn(max = 260.dp),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(20.dp))
        Text(status.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(status.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            listOfNotNull(
                status.year?.toString(),
                status.albumType,
                status.trackCount?.let { pluralStringResource(CoreR.plurals.track_count, it, it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        val job = status.job
        val libraryId = status.libraryAlbumId
        when {
            status.availability == AlbumAvailability.AVAILABLE && libraryId != null ->
                Button(onClick = { onOpenAlbum(libraryId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.catalog_open_album).uppercase())
                }

            job != null && job.isActive -> Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(job.state.label(), style = MaterialTheme.typography.titleMedium)
                val progress = job.progress
                if (job.state == DownloadJobState.DOWNLOADING && progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.catalog_progress, (progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.catalog_cancel)) }
            }

            status.availability == AlbumAvailability.IN_LIBRARY ->
                Text(stringResource(R.string.catalog_in_library), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)

            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val failedJob = job?.takeIf { it.state == DownloadJobState.FAILED || it.state == DownloadJobState.TIMEOUT }
                val failed = failedJob != null
                Text(
                    failedJob?.let { it.error ?: it.state.label() } ?: stringResource(CoreR.string.availability_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onDownload, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    if (busy) {
                        LoadingView(Modifier.size(20.dp))
                    } else {
                        Text(stringResource(if (failed) R.string.catalog_try_again else R.string.catalog_download).uppercase())
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
