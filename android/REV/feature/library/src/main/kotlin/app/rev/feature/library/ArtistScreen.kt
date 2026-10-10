package app.rev.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.ui.components.AlbumCard
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.ContentScaffold
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.FavoriteButton
import app.rev.core.ui.components.SectionHeader
import app.rev.core.ui.components.TrackRow
import app.rev.domain.model.ReleaseKind

/**
 * An artist: their top five songs, a way to everything of theirs you've liked or downloaded, then
 * their releases in horizontal rows by kind — albums, EPs, singles, live, compilations.
 */
@Composable
internal fun ArtistScreen(navigator: LibraryNavigator, viewModel: ArtistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val releases by viewModel.releases.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    QueueMessagesEffect(viewModel.queueMessages, snackbar)
    val addToPlaylist = rememberAddToPlaylist()
    AddToPlaylistHost(addToPlaylist, snackbar)
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }

    DetailScaffold(onBack = navigator.back, snackbar = snackbar) {
        ContentScaffold(state, viewModel::refresh, isEmpty = { false }, emptyMessage = "") { detail ->
            val artist = detail.artist
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(
                            artist.coverArtId,
                            ArtworkSize.CARD,
                            contentDescription = null,
                            modifier = Modifier.size(160.dp),
                            shape = CircleShape,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            artist.name,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        FavoriteButton(artist.starred, viewModel::setFavorite)
                    }
                    if (songs.tracks.isNotEmpty()) {
                        AlbumActions(
                            onPlay = { viewModel.play() },
                            onShuffle = viewModel::shuffle,
                            onQueue = viewModel::queueAll,
                            onAddToPlaylist = { addToPlaylist.open(viewModel.allTrackIds()) },
                        )
                    }
                }

                item(key = "songs-header") { SectionHeader(stringResource(R.string.artist_top_songs)) }
                when {
                    songs.tracks.isEmpty() && songs.loading -> item(key = "songs-loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    songs.tracks.isEmpty() && songs.yourSongsCount == 0 -> item(key = "songs-empty") {
                        Text(
                            stringResource(if (songs.failed) R.string.artist_songs_unavailable else R.string.artist_songs_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    else -> {
                        itemsIndexed(songs.tracks, key = { _, t -> "song:${t.id}" }) { index, track ->
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
                if (songs.yourSongsCount > 0) {
                    item(key = "songs-more") {
                        // Everything of theirs you've liked or downloaded, on its own screen.
                        TextButton(
                            onClick = { navigator.toArtistSongs(artist.id, artist.name) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text(pluralStringResource(R.plurals.artist_load_more, songs.yourSongsCount, songs.yourSongsCount))
                        }
                    }
                }

                releases.forEach { (kind, albums) ->
                    item(key = "releases:${kind.name}") {
                        SectionHeader(stringResource(kind.title()))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(albums, key = { it.id }) { album ->
                                // The artist is obvious here; the card shows the year instead.
                                AlbumCard(
                                    album,
                                    onClick = { navigator.toAlbum(album.id) },
                                    modifier = Modifier.width(140.dp),
                                    showArtist = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    songs.tracks.firstOrNull { it.id == detailsId }?.let { track ->
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

private fun ReleaseKind.title(): Int = when (this) {
    ReleaseKind.Album -> R.string.artist_albums
    ReleaseKind.Ep -> R.string.artist_eps
    ReleaseKind.Single -> R.string.artist_singles
    ReleaseKind.Live -> R.string.artist_live
    ReleaseKind.Compilation -> R.string.artist_compilations
}
