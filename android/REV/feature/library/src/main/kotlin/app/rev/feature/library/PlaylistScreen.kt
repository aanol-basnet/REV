package app.rev.feature.library

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.R as CoreR
import app.rev.core.format.formatTotalDuration
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.ContentScaffold
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.OfflineButton
import app.rev.core.ui.components.TrackRow
import app.rev.core.ui.icons.RevIcons

@Composable
internal fun PlaylistScreen(navigator: LibraryNavigator, viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offline by viewModel.offlineCollection.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    QueueMessagesEffect(viewModel.queueMessages, snackbar)
    val addToPlaylist = rememberAddToPlaylist()
    AddToPlaylistHost(addToPlaylist, snackbar)
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    var detailsIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val duplicating by viewModel.duplicating.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.duplicated.collect(navigator.toPlaylist) }

    DetailScaffold(onBack = navigator.back, snackbar = snackbar) {
        ContentScaffold(state, viewModel::refresh, isEmpty = { false }, emptyMessage = "") { detail ->
            val playlist = detail.playlist
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Artwork(
                            playlist.coverArtId,
                            ArtworkSize.LARGE,
                            contentDescription = null,
                            modifier = Modifier.widthIn(max = 240.dp),
                            shape = MaterialTheme.shapes.medium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(playlist.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        playlist.comment?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            pluralStringResource(CoreR.plurals.track_count, playlist.trackCount, playlist.trackCount) +
                                " · " + formatTotalDuration(playlist.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.Center) {
                            if (playlist.canEdit) {
                                TextButton(onClick = { navigator.toPlaylistEdit(playlist.id) }) {
                                    Icon(RevIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.playlist_edit))
                                }
                            }
                            TextButton(onClick = viewModel::duplicate, enabled = !duplicating) {
                                Text(
                                    stringResource(
                                        if (playlist.canEdit) R.string.playlist_duplicate else R.string.playlist_duplicate_editable,
                                    ),
                                )
                            }
                        }
                        if (!playlist.canEdit) {
                            Text(
                                playlist.owner?.let { stringResource(R.string.playlist_read_only_owner, it) }
                                    ?: stringResource(R.string.playlist_read_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                if (detail.tracks.isNotEmpty()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = { viewModel.play() }, modifier = Modifier.weight(1f)) {
                                Icon(RevIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_play).uppercase(), maxLines = 1)
                            }
                            OutlinedIconButton(onClick = viewModel::shuffle, modifier = Modifier.size(48.dp)) {
                                Icon(RevIcons.Shuffle, contentDescription = stringResource(R.string.action_shuffle))
                            }
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            OfflineButton(
                                offline,
                                onDownload = viewModel::download,
                                onRetry = { viewModel.retryDownload() },
                                onRemove = { viewModel.removeDownload() },
                            )
                        }
                    }
                    itemsIndexed(detail.tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
                        TrackRow(
                            track,
                            onClick = { viewModel.play(index) },
                            onMore = { detailsIndex = index },
                            downloaded = track.id in downloadedIds,
                        )
                    }
                } else if (state.isRefreshing) {
                    item { LoadingView(Modifier.height(120.dp)) }
                } else if (playlist.trackCount == 0) {
                    item { CenteredMessage(stringResource(R.string.playlist_empty), Modifier.height(160.dp)) }
                } else {
                    item { CenteredMessage(stringResource(R.string.empty_playlist_tracks), Modifier.height(160.dp)) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    detailsIndex?.let { index -> state.content?.tracks?.getOrNull(index) }?.let { track ->
        TrackDetailsSheet(
            track = track,
            onDismiss = { detailsIndex = null },
            onFavoriteChange = { viewModel.setTrackFavorite(track, it) },
            onGoToAlbum = { detailsIndex = null; navigator.toAlbum(it) },
            onGoToArtist = { detailsIndex = null; navigator.toArtist(it) },
            onPlayNext = { detailsIndex = null; viewModel.playNext(track) },
            onAddToQueue = { detailsIndex = null; viewModel.addToQueue(track) },
            downloaded = track.id in downloadedIds,
            onDownload = { detailsIndex = null; viewModel.downloadTrack(track) },
            onAddToPlaylist = { detailsIndex = null; addToPlaylist.open(listOf(track.id)) },
        )
    }
}
