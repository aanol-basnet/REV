package app.rev.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.R as CoreR
import app.rev.core.format.formatTotalDuration
import app.rev.core.ui.components.CoverCollage
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.TrackRow
import app.rev.core.ui.components.message
import app.rev.core.ui.components.mixTitle

/** A mix opened like a playlist: its songs, with play and shuffle. */
@Composable
internal fun MixScreen(navigator: LibraryNavigator, viewModel: MixViewModel = hiltViewModel()) {
    val mix by viewModel.mix.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    QueueMessagesEffect(viewModel.queueMessages, snackbar)
    val addToPlaylist = rememberAddToPlaylist()
    AddToPlaylistHost(addToPlaylist, snackbar)
    var detailsIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val tracks = (songs as? MixSongs.Loaded)?.tracks.orEmpty()

    DetailScaffold(onBack = navigator.back, snackbar = snackbar) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .widthIn(max = 240.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium),
                    ) {
                        CoverCollage(mix?.coverArtIds.orEmpty())
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        mix?.let { mixTitle(it) }.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    mix?.featuring?.takeIf { it.isNotEmpty() }?.let {
                        Text(
                            stringResource(CoreR.string.mix_featuring, it.joinToString(", ")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (tracks.isNotEmpty()) {
                        Text(
                            pluralStringResource(CoreR.plurals.track_count, tracks.size, tracks.size) +
                                " · " + formatTotalDuration(tracks.sumOf { it.durationMs }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if ((songs as? MixSongs.Loaded)?.fromDownloads == true) {
                        Text(
                            stringResource(R.string.mix_from_downloads),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (tracks.isNotEmpty()) {
                    AlbumActions(onPlay = { viewModel.play() }, onShuffle = viewModel::shuffle, onQueue = viewModel::queueAll)
                }
            }
            when (val state = songs) {
                MixSongs.Loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                is MixSongs.Failed -> item(key = "failed") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.error.message(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = viewModel::reload) { Text(stringResource(CoreR.string.action_retry)) }
                    }
                }
                is MixSongs.Loaded -> if (state.tracks.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.mix_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                } else {
                    itemsIndexed(state.tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
                        TrackRow(
                            track = track,
                            onClick = { viewModel.play(index) },
                            onMore = { detailsIndex = index },
                            downloaded = track.id in downloadedIds,
                        )
                    }
                }
            }
        }
    }

    detailsIndex?.let { tracks.getOrNull(it) }?.let { track ->
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
