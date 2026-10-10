package app.rev.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.TrackRow

/** Every song by one artist that you've liked or downloaded; works offline for downloads. */
@Composable
internal fun ArtistSongsScreen(navigator: LibraryNavigator, viewModel: ArtistSongsViewModel = hiltViewModel()) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    QueueMessagesEffect(viewModel.queueMessages, snackbar)
    val addToPlaylist = rememberAddToPlaylist()
    AddToPlaylistHost(addToPlaylist, snackbar)
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }

    DetailScaffold(onBack = navigator.back, snackbar = snackbar) {
        val tracks = songs
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) {
                    Text(
                        stringResource(R.string.artist_your_songs),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        viewModel.artistName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!tracks.isNullOrEmpty()) {
                    AlbumActions(onPlay = { viewModel.play() }, onShuffle = viewModel::shuffle, onQueue = viewModel::queueAll)
                }
            }
            when {
                tracks == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                tracks.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(R.string.artist_your_songs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
                else -> itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                    TrackRow(
                        track = track,
                        onClick = { viewModel.play(index) },
                        onMore = { detailsId = track.id },
                        subtitle = track.album,
                        downloaded = track.id in downloadedIds,
                        liked = track.starred,
                    )
                }
            }
        }
    }

    songs?.firstOrNull { it.id == detailsId }?.let { track ->
        TrackDetailsSheet(
            track = track,
            onDismiss = { detailsId = null },
            onFavoriteChange = { viewModel.setTrackFavorite(track, it) },
            onGoToAlbum = { detailsId = null; navigator.toAlbum(it) },
            onGoToArtist = null,
            onPlayNext = { detailsId = null; viewModel.playNext(track) },
            onAddToQueue = { detailsId = null; viewModel.addToQueue(track) },
            downloaded = track.id in downloadedIds,
            onDownload = { detailsId = null; viewModel.downloadTrack(track) },
            onAddToPlaylist = { detailsId = null; addToPlaylist.open(listOf(track.id)) },
        )
    }
}
