package app.rev.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import app.rev.core.ui.state.ContentState
import app.rev.domain.model.Playlist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import app.rev.domain.model.Favorites
import app.rev.domain.search.LocalSearch
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.rev.core.R as CoreR
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.ui.components.AlbumCard
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.ArtistRow
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.ContentScaffold
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.SectionHeader
import app.rev.core.ui.components.TrackRow
import app.rev.core.ui.components.message
import app.rev.core.ui.state.PagedState
import app.rev.domain.model.Track

private enum class LibraryTab(val title: Int) {
    Artists(R.string.tab_artists),
    Albums(R.string.tab_albums),
    Playlists(R.string.tab_playlists),
    Tracks(R.string.tab_tracks),
    Genres(R.string.tab_genres),
    Favorites(R.string.tab_favorites),
}

@Composable
internal fun LibraryScreen(navigator: LibraryNavigator) {
    var savedPage by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val tabs = LibraryTab.entries
    // Swipe left/right between the tabs.
    val pager = rememberPagerState(initialPage = savedPage) { tabs.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { savedPage = pager.currentPage }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            stringResource(R.string.library_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp),
        )
        FilterField(query, onQueryChange = { query = it })
        PrimaryScrollableTabRow(
            selectedTabIndex = pager.currentPage,
            edgePadding = 12.dp,
            containerColor = Color.Transparent,
            divider = {},
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = pager.currentPage == index,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    text = { Text(stringResource(tab.title)) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalPager(pager, Modifier.weight(1f), key = { tabs[it].name }) { page ->
            Box(Modifier.fillMaxSize()) {
                when (tabs[page]) {
                    LibraryTab.Artists -> ArtistsTab(navigator, query)
                    LibraryTab.Albums -> AlbumsTab(navigator, query)
                    LibraryTab.Playlists -> PlaylistsTab(navigator, query)
                    LibraryTab.Tracks -> TracksTab(navigator, query)
                    LibraryTab.Genres -> GenresTab(navigator, query)
                    LibraryTab.Favorites -> FavoritesTab(navigator, query)
                }
            }
        }
    }
}

/** Narrows whichever tab is showing; matching ignores case and accents. */
@Composable
private fun FilterField(query: String, onQueryChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.library_filter_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.library_filter_clear))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp),
    )
}

/** [items] matching the filter, best first; all of them when there's no filter. */
@Composable
private fun <T> rememberFiltered(items: List<T>, query: String, fields: (T) -> List<String?>): List<T> =
    remember(items, query) { LocalSearch.filter(items, query, fields) }

@Composable
private fun NoMatches(query: String) {
    CenteredMessage(stringResource(R.string.library_filter_no_matches, query.trim()))
}

@Composable
private fun ArtistsTab(navigator: LibraryNavigator, query: String, viewModel: ArtistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ContentScaffold(state, viewModel::refresh, { it.isEmpty() }, stringResource(R.string.empty_artists)) { all ->
        val artists = rememberFiltered(all, query) { listOf(it.name) }
        if (artists.isEmpty()) return@ContentScaffold NoMatches(query)
        LazyColumn(Modifier.fillMaxSize()) {
            items(artists, key = { it.id }) { artist ->
                ArtistRow(artist, onClick = { navigator.toArtist(artist.id) })
            }
        }
    }
}

@Composable
private fun AlbumsTab(navigator: LibraryNavigator, query: String, viewModel: AlbumsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ContentScaffold(state, viewModel::refresh, { it.isEmpty() }, stringResource(R.string.empty_albums)) { all ->
        val albums = rememberFiltered(all, query) { listOf(it.name, it.artist) }
        if (albums.isEmpty()) return@ContentScaffold NoMatches(query)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(album, onClick = { navigator.toAlbum(album.id) })
            }
        }
    }
}

@Composable
private fun TracksTab(navigator: LibraryNavigator, query: String, viewModel: TracksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    LaunchedEffect(query) { viewModel.setQuery(query) }
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    QueueMessagesEffect(viewModel.queueMessages, snackbar)
    val addToPlaylist = rememberAddToPlaylist()
    AddToPlaylistHost(addToPlaylist, snackbar)
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        val found = matches
        when {
            found == null -> PagedTrackList(
                state = state,
                downloadedIds = downloadedIds,
                emptyMessage = stringResource(R.string.empty_tracks),
                onLoadMore = viewModel::loadMore,
                onRetry = viewModel::reload,
                onPlay = viewModel::play,
                onShowDetails = { detailsId = it.id },
            )
            found.loading -> LoadingView()
            found.tracks.isEmpty() -> NoMatches(found.query)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(found.tracks, key = { _, track -> track.id }) { index, track ->
                    TrackRow(
                        track,
                        onClick = { viewModel.playMatch(index) },
                        onMore = { detailsId = track.id },
                        downloaded = track.id in downloadedIds,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    (state.items + matches?.tracks.orEmpty()).firstOrNull { it.id == detailsId }?.let { track ->
        TrackDetailsSheet(
            track = track,
            onDismiss = { detailsId = null },
            onFavoriteChange = { viewModel.setTrackFavorite(track, it) },
            onGoToAlbum = { detailsId = null; navigator.toAlbum(it) },
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
private fun PagedTrackList(
    state: PagedState<Track>,
    emptyMessage: String,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (Int) -> Unit,
    onShowDetails: (Track) -> Unit,
    downloadedIds: Set<String>,
) {
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, state, onLoadMore)
    when {
        state.items.isEmpty() && state.error != null -> CenteredMessage(state.error?.message().orEmpty()) {
            TextButton(onClick = onRetry) { Text(stringResource(app.rev.core.R.string.action_retry)) }
        }
        state.items.isEmpty() && (state.isLoading || !state.endReached) -> LoadingView()
        state.items.isEmpty() -> CenteredMessage(emptyMessage)
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.items, key = { _, track -> track.id }) { index, track ->
                TrackRow(track, onClick = { onPlay(index) }, onMore = { onShowDetails(track) }, downloaded = track.id in downloadedIds)
            }
            pagingFooter(state, onLoadMore)
        }
    }
}

internal fun LazyListScope.pagingFooter(state: PagedState<*>, onRetry: () -> Unit) {
    if (state.isLoading) {
        item { LoadingView(Modifier.fillMaxWidth().padding(16.dp)) }
    } else if (state.error != null) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.load_more_failed), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text(stringResource(app.rev.core.R.string.action_retry)) }
            }
        }
    }
}

/** Requests the next page when the user scrolls within a few items of the end. */
@Composable
internal fun LoadMoreEffect(listState: LazyListState, state: PagedState<*>, onLoadMore: () -> Unit) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 20
        }
    }
    LaunchedEffect(nearEnd, state.items.size, state.endReached, state.error) {
        if (nearEnd && !state.endReached && !state.isLoading && state.error == null) onLoadMore()
    }
}

@Composable
private fun PlaylistsTab(navigator: LibraryNavigator, query: String, viewModel: PlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    var creating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.created.collect(navigator.toPlaylist) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NewPlaylistRow(onClick = { creating = true })
            PlaylistList(navigator, query, state, viewModel::refresh)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.playlist_new)) },
            text = { NewPlaylistField(onCreate = { creating = false; viewModel.create(it) }) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.playlist_cancel)) } },
        )
    }
}

@Composable
private fun NewPlaylistRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null) }
        }
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.playlist_new), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PlaylistList(navigator: LibraryNavigator, query: String, state: ContentState<List<Playlist>>, onRefresh: () -> Unit) {
    ContentScaffold(state, onRefresh, { it.isEmpty() }, stringResource(R.string.empty_playlists)) { all ->
        val playlists = rememberFiltered(all, query) { listOf(it.name) }
        if (playlists.isEmpty()) return@ContentScaffold NoMatches(query)
        LazyColumn(Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navigator.toPlaylist(playlist.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(playlist.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            pluralStringResource(CoreR.plurals.track_count, playlist.trackCount, playlist.trackCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenresTab(navigator: LibraryNavigator, query: String, viewModel: GenresViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ContentScaffold(state, viewModel::refresh, { it.isEmpty() }, stringResource(R.string.empty_genres)) { all ->
        val genres = rememberFiltered(all, query) { listOf(it.name) }
        if (genres.isEmpty()) return@ContentScaffold NoMatches(query)
        LazyColumn(Modifier.fillMaxSize()) {
            items(genres, key = { it.name }) { genre ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navigator.toGenre(genre.name) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(genre.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        pluralStringResource(CoreR.plurals.album_count, genre.albumCount, genre.albumCount) + " · " +
                            pluralStringResource(CoreR.plurals.track_count, genre.trackCount, genre.trackCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoritesTab(navigator: LibraryNavigator, query: String, viewModel: FavoritesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    QueueMessagesEffect(viewModel.queueMessages, snackbar)
    val addToPlaylist = rememberAddToPlaylist()
    AddToPlaylistHost(addToPlaylist, snackbar)
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        ContentScaffold(state, viewModel::refresh, { it.isEmpty }, stringResource(R.string.empty_favorites)) { all ->
            val fav = remember(all, query) {
                Favorites(
                    LocalSearch.filter(all.artists, query) { listOf(it.name) },
                    LocalSearch.filter(all.albums, query) { listOf(it.name, it.artist) },
                    LocalSearch.filter(all.tracks, query) { listOf(it.title, it.artist, it.album) },
                )
            }
            if (fav.isEmpty) return@ContentScaffold NoMatches(query)
            LazyColumn(Modifier.fillMaxSize()) {
                if (fav.artists.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.favorites_artists)) }
                    items(fav.artists, key = { "ar-${it.id}" }) { ArtistRow(it, onClick = { navigator.toArtist(it.id) }) }
                }
                if (fav.albums.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.favorites_albums)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(fav.albums, key = { it.id }) { album ->
                                AlbumCard(album, onClick = { navigator.toAlbum(album.id) }, modifier = Modifier.width(140.dp))
                            }
                        }
                    }
                }
                if (fav.tracks.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.favorites_tracks)) }
                    itemsIndexed(fav.tracks, key = { _, track -> "tr-${track.id}" }) { index, track ->
                        TrackRow(
                            track,
                            onClick = { viewModel.play(fav.tracks, index) },
                            onMore = { detailsId = track.id },
                            downloaded = track.id in downloadedIds,
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    state.content?.tracks?.firstOrNull { it.id == detailsId }?.let { track ->
        TrackDetailsSheet(
            track = track,
            onDismiss = { detailsId = null },
            onFavoriteChange = { viewModel.setTrackFavorite(track, it) },
            onGoToAlbum = { detailsId = null; navigator.toAlbum(it) },
            onGoToArtist = { detailsId = null; navigator.toArtist(it) },
            onPlayNext = { detailsId = null; viewModel.playNext(track) },
            onAddToQueue = { detailsId = null; viewModel.addToQueue(track) },
            downloaded = track.id in downloadedIds,
            onDownload = { detailsId = null; viewModel.downloadTrack(track) },
            onAddToPlaylist = { detailsId = null; addToPlaylist.open(listOf(track.id)) },
        )
    }
}
