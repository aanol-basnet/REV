package app.rev.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.rev.core.format.formatTotalDuration
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.ContentScaffold
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.FavoriteButton
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.OfflineButton
import app.rev.core.ui.components.SectionHeader
import app.rev.core.ui.components.TrackRow
import app.rev.core.ui.icons.RevIcons
import app.rev.core.ui.theme.artworkGradient
import app.rev.core.ui.theme.rememberArtworkAccent
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.rev.domain.model.Album
import app.rev.domain.model.AlbumDetail

@Composable
internal fun AlbumScreen(navigator: LibraryNavigator, viewModel: AlbumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offline by viewModel.offlineCollection.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    QueueMessagesEffect(viewModel.queueMessages, snackbar)
    val addToPlaylist = rememberAddToPlaylist()
    AddToPlaylistHost(addToPlaylist, snackbar)
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }

    DetailScaffold(onBack = navigator.back, snackbar = snackbar) {
        ContentScaffold(state, viewModel::refresh, isEmpty = { false }, emptyMessage = "") { detail ->
            AlbumContent(
                detail = detail,
                tracksLoading = state.isRefreshing,
                onArtistClick = { detail.album.artistId?.let(navigator.toArtist) },
                onFavoriteChange = viewModel::setFavorite,
                onPlay = { viewModel.play() },
                onShuffle = viewModel::shuffle,
                onQueue = viewModel::queueAlbum,
                onAddToPlaylist = { addToPlaylist.open(detail.tracks.map { it.id }) },
                onTrackClick = { id -> viewModel.play(detail.tracks.indexOfFirst { it.id == id }) },
                onTrackDetails = { detailsId = it },
                downloadedIds = downloadedIds,
                offlineControl = {
                    OfflineButton(
                        offline,
                        onDownload = viewModel::downloadAlbum,
                        onRetry = { viewModel.retryDownload() },
                        onRemove = { viewModel.removeDownload() },
                    )
                },
            )
        }
    }
    state.content?.tracks?.firstOrNull { it.id == detailsId }?.let { track ->
        TrackDetailsSheet(
            track = track,
            onDismiss = { detailsId = null },
            onFavoriteChange = { viewModel.setTrackFavorite(track, it) },
            onGoToAlbum = null,
            onGoToArtist = { detailsId = null; navigator.toArtist(it) },
            onPlayNext = { detailsId = null; viewModel.playNext(track) },
            onAddToQueue = { detailsId = null; viewModel.addToQueue(track) },
            downloaded = track.id in downloadedIds,
            onDownload = { detailsId = null; viewModel.downloadTrack(track) },
            onAddToPlaylist = { detailsId = null; addToPlaylist.open(listOf(track.id)) },
        )
    }
}

@Composable
private fun AlbumContent(
    detail: AlbumDetail,
    tracksLoading: Boolean,
    onArtistClick: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onTrackClick: (String) -> Unit,
    onTrackDetails: (String) -> Unit,
    downloadedIds: Set<String>,
    offlineControl: @Composable () -> Unit,
) {
    val album = detail.album
    LazyColumn(Modifier.fillMaxSize()) {
        item { AlbumHeader(album, onArtistClick, onFavoriteChange) }
        if (detail.tracks.isNotEmpty()) {
            item { AlbumActions(onPlay, onShuffle, onQueue, onAddToPlaylist) }
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { offlineControl() }
            }
        }
        when {
            detail.tracks.isEmpty() && tracksLoading -> item { LoadingView(Modifier.height(120.dp)) }
            detail.tracks.isEmpty() -> item {
                CenteredMessage(stringResource(R.string.empty_album_tracks), Modifier.height(160.dp))
            }
            else -> {
                val discs = detail.tracks.groupBy { it.discNumber ?: 1 }
                discs.forEach { (disc, tracks) ->
                    if (detail.isMultiDisc) item(key = "disc-$disc") { SectionHeader(stringResource(R.string.disc_title, disc)) }
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            leadingNumber = true,
                            // Only worth repeating the artist when it differs (compilations, features).
                            subtitle = track.artist.takeIf { it != album.artist },
                            onClick = { onTrackClick(track.id) },
                            onMore = { onTrackDetails(track.id) },
                            downloaded = track.id in downloadedIds,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AlbumHeader(album: Album, onArtistClick: () -> Unit, onFavoriteChange: (Boolean) -> Unit) {
    val accent = rememberArtworkAccent(album.coverArtId)
    Column(
        Modifier
            .fillMaxWidth()
            .artworkGradient(accent, MaterialTheme.colorScheme.background, reach = 1f)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(
            album.coverArtId,
            ArtworkSize.LARGE,
            contentDescription = null,
            modifier = Modifier.widthIn(max = 280.dp),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            album.name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            album.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = album.artistId != null, onClick = onArtistClick),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                album.year?.toString(),
                pluralStringResource(app.rev.core.R.plurals.track_count, album.trackCount, album.trackCount),
                formatTotalDuration(album.durationMs),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FavoriteButton(album.starred, onFavoriteChange)
    }
}

@Composable
internal fun AlbumActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
) {
    // One wide primary action; secondary actions as labelled icon buttons, so nothing wraps on
    // narrow screens or with large text.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
            Icon(RevIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_play).uppercase(), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        OutlinedIconButton(onClick = onShuffle, modifier = Modifier.size(48.dp)) {
            Icon(RevIcons.Shuffle, contentDescription = stringResource(R.string.action_shuffle))
        }
        OutlinedIconButton(onClick = onQueue, modifier = Modifier.size(48.dp)) {
            Icon(RevIcons.QueueAdd, contentDescription = stringResource(R.string.action_add_to_queue))
        }
        if (onAddToPlaylist != null) {
            OutlinedIconButton(onClick = onAddToPlaylist, modifier = Modifier.size(48.dp)) {
                Icon(RevIcons.PlaylistAdd, contentDescription = stringResource(R.string.action_add_to_playlist))
            }
        }
    }
}
