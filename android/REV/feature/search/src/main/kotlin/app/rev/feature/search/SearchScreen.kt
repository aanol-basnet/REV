package app.rev.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.rev.core.R as CoreR
import app.rev.core.ui.components.ArtworkImage
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.CoverArt
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.SectionHeader
import app.rev.core.ui.components.TrackRow
import app.rev.core.ui.components.message
import app.rev.domain.model.SearchAlbum
import app.rev.domain.model.SearchArtist
import app.rev.domain.model.SearchResults
import app.rev.domain.model.SourceStatus
import kotlinx.serialization.Serializable

@Serializable
data object SearchRoute

class SearchNavigator(
    val toArtist: (libraryId: String) -> Unit,
    val toAlbum: (libraryId: String) -> Unit,
    val back: () -> Unit,
)

fun NavGraphBuilder.searchScreens(navigator: SearchNavigator) {
    composable<SearchRoute> { SearchScreen(navigator) }
    // Albums requested earlier, opened from Downloads.
    composable<CatalogAlbumRoute> { CatalogAlbumScreen(onBack = navigator.back, onOpenAlbum = navigator.toAlbum) }
}

@Composable
internal fun SearchScreen(navigator: SearchNavigator, viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnce && query.isEmpty()) focus.requestFocus()
        focusedOnce = true
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focus),
        )
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            SearchState.Idle -> CenteredMessage(stringResource(R.string.search_prompt))
            SearchState.Loading -> LoadingView()
            is SearchState.Failed -> CenteredMessage(s.error.message()) {
                TextButton(onClick = viewModel::retry) { Text(stringResource(CoreR.string.action_retry)) }
            }
            is SearchState.Results ->
                if (s.results.isEmpty) {
                    CenteredMessage(stringResource(R.string.search_no_results, s.results.query))
                } else {
                    // A kind with no results for this query falls back to showing everything.
                    val shown = filter.takeIf { s.results.count(it) != 0 } ?: SearchFilter.All
                    Column {
                        FilterChips(shown, s.results, viewModel::setFilter)
                        Results(
                            results = s.results,
                            filter = shown,
                            onFilter = viewModel::setFilter,
                            onArtist = { artist -> artist.libraryId?.let(navigator.toArtist) },
                            onAlbum = { album -> album.libraryId?.let(navigator.toAlbum) },
                            onTrack = viewModel::playTracks,
                        )
                    }
                }
        }
    }

    // Coming back from an album page: badges may have changed (e.g. just requested).
    LifecycleResumeEffect(Unit) {
        viewModel.refreshResults()
        onPauseOrDispose { }
    }
}

@Composable
private fun FilterChips(selected: SearchFilter, results: SearchResults, onSelect: (SearchFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SearchFilter.entries) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                enabled = results.count(filter) != 0,
                label = { Text(stringResource(filter.label)) },
            )
        }
    }
}

/** How many results of a kind; null for [SearchFilter.All]. */
private fun SearchResults.count(filter: SearchFilter): Int? = when (filter) {
    SearchFilter.All -> null
    SearchFilter.Songs -> tracks.size
    SearchFilter.Albums -> albums.size
    SearchFilter.Artists -> artists.size
}

private val SearchFilter.label: Int
    get() = when (this) {
        SearchFilter.All -> R.string.search_filter_all
        SearchFilter.Songs -> R.string.search_tracks
        SearchFilter.Albums -> R.string.search_albums
        SearchFilter.Artists -> R.string.search_artists
    }

/** "All" shows the first few of each kind, with a way to see the rest; a filter shows one kind. */
@Composable
private fun Results(
    results: SearchResults,
    filter: SearchFilter,
    onFilter: (SearchFilter) -> Unit,
    onArtist: (SearchArtist) -> Unit,
    onAlbum: (SearchAlbum) -> Unit,
    onTrack: (Int) -> Unit,
) {
    val all = filter == SearchFilter.All
    LazyColumn(Modifier.fillMaxSize()) {
        sourceNotice(results)?.let { notice ->
            item { SourceNotice(notice) }
        }
        if (results.tracks.isNotEmpty() && (all || filter == SearchFilter.Songs)) {
            val shown = if (all) results.tracks.take(ALL_TRACKS) else results.tracks
            if (all) item { KindHeader(R.string.search_tracks, results.tracks.size > shown.size) { onFilter(SearchFilter.Songs) } }
            itemsIndexed(shown, key = { _, t -> "tr:${t.id}" }) { index, track ->
                TrackRow(track, onClick = { onTrack(index) }, onMore = { onTrack(index) })
            }
        }
        if (results.artists.isNotEmpty() && (all || filter == SearchFilter.Artists)) {
            val shown = if (all) results.artists.take(ALL_ARTISTS) else results.artists
            if (all) item { KindHeader(R.string.search_artists, results.artists.size > shown.size) { onFilter(SearchFilter.Artists) } }
            items(shown, key = { "ar:${it.libraryId}:${it.name}" }) { artist ->
                ArtistResult(artist, onClick = { onArtist(artist) })
            }
        }
        if (results.albums.isNotEmpty() && (all || filter == SearchFilter.Albums)) {
            val shown = if (all) results.albums.take(ALL_ALBUMS) else results.albums
            if (all) item { KindHeader(R.string.search_albums, results.albums.size > shown.size) { onFilter(SearchFilter.Albums) } }
            items(shown, key = { it.key }) { album -> AlbumResult(album, onClick = { onAlbum(album) }) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun KindHeader(title: Int, more: Boolean, onSeeAll: () -> Unit) {
    SectionHeader(stringResource(title)) {
        if (more) TextButton(onClick = onSeeAll) { Text(stringResource(R.string.search_see_all)) }
    }
}

private const val ALL_TRACKS = 5
private const val ALL_ARTISTS = 4
private const val ALL_ALBUMS = 6

private fun sourceNotice(results: SearchResults) = when {
    results.onDevice -> R.string.search_on_device
    results.library == SourceStatus.Unavailable -> R.string.search_library_unavailable
    else -> null
}

@Composable
private fun SourceNotice(messageRes: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(messageRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ArtistResult(artist: SearchArtist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = artist.libraryId != null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            artist.coverArtId?.let { CoverArt(it, ArtworkSize.THUMBNAIL) },
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist.disambiguation?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AlbumResult(album: SearchAlbum, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(album.artworkModel(), contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(album.artist, album.year?.toString(), album.albumType?.takeIf { it != "Album" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun SearchAlbum.artworkModel(): Any? = coverArtId?.let { CoverArt(it, ArtworkSize.THUMBNAIL) }
