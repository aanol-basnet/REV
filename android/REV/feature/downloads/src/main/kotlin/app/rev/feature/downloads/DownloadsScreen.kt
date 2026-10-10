package app.rev.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.rev.core.R as CoreR
import app.rev.core.format.formatFileSize
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkImage
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.CatalogCover
import app.rev.core.ui.components.ContentScaffold
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.MixCard
import app.rev.core.ui.components.SectionHeader
import app.rev.core.ui.components.TrackRow
import app.rev.core.ui.components.label
import app.rev.core.ui.icons.RevIcons
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.DownloadJobState
import app.rev.domain.model.Mix
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.OfflineKind
import app.rev.domain.model.OfflineState
import app.rev.domain.model.StorageUsage
import app.rev.domain.model.Track
import kotlinx.serialization.Serializable

@Serializable
data object DownloadsRoute

class DownloadsNavigator(
    val toCatalogAlbum: (catalogAlbumId: String) -> Unit,
    val toAlbum: (libraryAlbumId: String) -> Unit,
    val toPlaylist: (playlistId: String) -> Unit,
    val toArtist: (artistId: String) -> Unit,
    /** Every song by an artist that's on the device (works offline). */
    val toArtistSongs: (artistId: String, name: String) -> Unit,
)

fun NavGraphBuilder.downloadsScreen(navigator: DownloadsNavigator) {
    composable<DownloadsRoute> { DownloadsScreen(navigator) }
}

/**
 * §48: music on this device, ready to play with no connection: everything at once, offline
 * mixes, and the downloads by album/playlist, song or artist. Albums still being added to the
 * library by the server follow the downloaded albums.
 */
@Composable
internal fun DownloadsScreen(navigator: DownloadsNavigator, viewModel: DownloadsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val mixes by viewModel.mixes.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val startingMix by viewModel.startingMix.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.downloads_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
            )
            ContentScaffold(
                state,
                viewModel::refresh,
                { it.isEmpty && songs.isEmpty() },
                stringResource(R.string.downloads_empty),
            ) { sections ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (songs.isNotEmpty()) {
                        item(key = "summary") {
                            Summary(
                                songCount = songs.size,
                                storage = storage,
                                onPlay = { viewModel.playSongs() },
                                onShuffle = { viewModel.playSongs(shuffle = true) },
                            )
                        }
                    }
                    if (mixes.isNotEmpty()) {
                        item(key = "mixes") { Mixes(mixes, startingMix, viewModel::playMix) }
                    }
                    item(key = "views") {
                        ViewChips(
                            selected = view,
                            collections = sections.onDevice.size,
                            songs = songs.size,
                            artists = artists.size,
                            onSelect = viewModel::showView,
                        )
                    }
                    when (view) {
                        DownloadsView.Collections -> collections(sections, live, navigator, viewModel)
                        DownloadsView.Songs -> songs(songs, onPlay = { viewModel.playSongs(it) }, onMore = { actionsFor = it.id })
                        DownloadsView.Artists -> artists(artists, navigator)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    songs.firstOrNull { it.id == actionsFor }?.let { track ->
        SongActions(
            track = track,
            onDismiss = { actionsFor = null },
            onPlayNext = { actionsFor = null; viewModel.playNext(track) },
            onAddToQueue = { actionsFor = null; viewModel.addToQueue(track) },
            onGoToAlbum = track.albumId?.let { id -> { actionsFor = null; navigator.toAlbum(id) } },
            onGoToArtist = track.artistId?.let { id -> { actionsFor = null; navigator.toArtist(id) } },
        )
    }
}

/** How much is on the device, with one tap to play it all. */
@Composable
private fun Summary(songCount: Int, storage: StorageUsage?, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(RevIcons.Downloaded, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pluralStringResource(R.plurals.downloads_song_count, songCount, songCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    storage?.let {
                        Text(
                            stringResource(R.string.downloads_storage, formatFileSize(it.bytes), formatFileSize(it.availableBytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(RevIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.downloads_play))
                }
                FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                    Icon(RevIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.downloads_shuffle))
                }
            }
        }
    }
}

@Composable
private fun Mixes(mixes: List<Mix>, startingMix: String?, onPlay: (Mix) -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.downloads_mixes))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(mixes, key = { it.id }) { mix ->
                MixCard(mix, starting = startingMix == mix.id, onClick = { onPlay(mix) })
            }
        }
    }
}

@Composable
private fun ViewChips(selected: DownloadsView, collections: Int, songs: Int, artists: Int, onSelect: (DownloadsView) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(DownloadsView.entries) { view ->
            val (label, count) = when (view) {
                DownloadsView.Collections -> R.string.downloads_view_collections to collections
                DownloadsView.Songs -> R.string.downloads_view_songs to songs
                DownloadsView.Artists -> R.string.downloads_view_artists to artists
            }
            FilterChip(
                selected = selected == view,
                onClick = { onSelect(view) },
                label = { Text(if (count > 0) "${stringResource(label)} · $count" else stringResource(label)) },
            )
        }
    }
}

private fun LazyListScope.collections(
    sections: DownloadSections,
    live: Boolean,
    navigator: DownloadsNavigator,
    viewModel: DownloadsViewModel,
) {
    if (sections.onDevice.isEmpty()) {
        item(key = "no-collections") { Hint(R.string.downloads_no_collections) }
    }
    items(sections.onDevice, key = { it.id }) { collection ->
        OfflineRow(
            collection,
            onOpen = {
                when (collection.kind) {
                    OfflineKind.Album -> navigator.toAlbum(collection.itemId)
                    OfflineKind.Playlist -> navigator.toPlaylist(collection.itemId)
                    OfflineKind.Track, OfflineKind.Favorites -> viewModel.showView(DownloadsView.Songs)
                }
            },
            onRetry = { viewModel.retryOffline(collection) },
            onRemove = { viewModel.removeOffline(collection) },
        )
    }
    if (sections.inProgress.isNotEmpty()) {
        item(key = "in-progress") { SectionHeader(stringResource(R.string.downloads_in_progress)) }
        if (!live) {
            item(key = "live-off") {
                Text(
                    stringResource(R.string.downloads_live_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        items(sections.inProgress, key = { "job:${it.id}" }) { job ->
            JobRow(job, navigator, onCancel = { viewModel.cancel(job) }, onRetry = {})
        }
    }
    if (sections.finished.isNotEmpty()) {
        item(key = "finished") {
            SectionHeader(stringResource(R.string.downloads_finished)) {
                TextButton(onClick = viewModel::clearFinished) { Text(stringResource(R.string.downloads_clear)) }
            }
        }
        items(sections.finished, key = { "job:${it.id}" }) { job ->
            JobRow(job, navigator, onCancel = {}, onRetry = { viewModel.retry(job) })
        }
    }
}

private fun LazyListScope.songs(songs: List<Track>, onPlay: (Int) -> Unit, onMore: (Track) -> Unit) {
    if (songs.isEmpty()) {
        item(key = "no-songs") { Hint(R.string.downloads_no_songs) }
    }
    itemsIndexed(songs, key = { _, t -> "song:${t.id}" }) { index, track ->
        TrackRow(track, onClick = { onPlay(index) }, onMore = { onMore(track) })
    }
}

private fun LazyListScope.artists(artists: List<DownloadedArtist>, navigator: DownloadsNavigator) {
    if (artists.isEmpty()) {
        item(key = "no-artists") { Hint(R.string.downloads_no_songs) }
    }
    items(artists, key = { "artist:${it.id ?: it.name}" }) { artist ->
        val id = artist.id
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = id != null) { id?.let { navigator.toArtistSongs(it, artist.name) } }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artist.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(48.dp), shape = CircleShape)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.downloads_artist_songs, artist.songCount, artist.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongActions(
    track: Track,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onGoToAlbum: (() -> Unit)?,
    onGoToArtist: (() -> Unit)?,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            ListItem(
                headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${track.artist} · ${track.album}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Artwork(track.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(48.dp)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            SheetAction(R.string.downloads_play_next, onPlayNext)
            SheetAction(R.string.downloads_add_to_queue, onAddToQueue)
            onGoToAlbum?.let { SheetAction(R.string.downloads_go_to_album, it) }
            onGoToArtist?.let { SheetAction(R.string.downloads_go_to_artist, it) }
        }
    }
}

@Composable
private fun SheetAction(label: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun JobRow(job: DownloadJob, navigator: DownloadsNavigator, onCancel: () -> Unit, onRetry: () -> Unit) {
    val libraryId = job.libraryAlbumId
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                if (job.state == DownloadJobState.AVAILABLE && libraryId != null) {
                    navigator.toAlbum(libraryId)
                } else {
                    navigator.toCatalogAlbum(job.catalogAlbumId)
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(CatalogCover(job.catalogAlbumId), contentDescription = null, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                job.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val progress = job.progress
            if (job.state == DownloadJobState.DOWNLOADING && progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                Text(
                    "${job.state.label()} ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val failed = job.state == DownloadJobState.FAILED || job.state == DownloadJobState.TIMEOUT
                Text(
                    if (failed) job.error ?: job.state.label() else job.state.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            job.isActive -> TextButton(onClick = onCancel) { Text(stringResource(R.string.downloads_cancel)) }
            job.state == DownloadJobState.FAILED || job.state == DownloadJobState.TIMEOUT ->
                TextButton(onClick = onRetry) { Text(stringResource(R.string.downloads_retry)) }
            job.state == DownloadJobState.AVAILABLE && libraryId != null ->
                TextButton(onClick = { navigator.toAlbum(libraryId) }) { Text(stringResource(R.string.downloads_open)) }
        }
    }
}

/** §48: "Lateralus · Tool · ██████░ 82%", "Downloaded", "Queued". */
@Composable
private fun OfflineRow(collection: OfflineCollection, onOpen: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(collection.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(collection.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(
                collection.subtitle,
                stringResource(R.string.offline_automatic).takeIf { collection.automatic },
            ).joinToString(" · ").ifEmpty { null }
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (collection.state) {
                OfflineState.Downloading, OfflineState.Queued -> {
                    LinearProgressIndicator(
                        progress = { collection.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                    Text(
                        if (collection.state == OfflineState.Queued) {
                            stringResource(CoreR.string.offline_queued)
                        } else {
                            pluralStringResource(R.plurals.offline_tracks_progress, collection.totalTracks, collection.downloadedTracks, collection.totalTracks)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OfflineState.Downloaded -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        RevIcons.Downloaded,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        pluralStringResource(CoreR.plurals.track_count, collection.totalTracks, collection.totalTracks) +
                            " · " + formatFileSize(collection.bytesDownloaded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OfflineState.Failed -> Text(
                    pluralStringResource(R.plurals.offline_failed_tracks, collection.failedTracks, collection.failedTracks),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(CoreR.string.more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (collection.state == OfflineState.Failed) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.downloads_retry)) },
                        onClick = { menu = false; onRetry() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(CoreR.string.offline_remove)) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}
