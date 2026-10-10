package app.rev.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.rev.core.ui.components.AlbumCard
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.MixCard
import app.rev.core.ui.components.SectionHeader
import app.rev.core.ui.components.StaleContentBanner
import app.rev.core.ui.components.message
import app.rev.core.ui.icons.RevIcons
import app.rev.core.ui.theme.artworkGradient
import app.rev.core.ui.theme.rememberArtworkAccent
import app.rev.domain.model.Album
import app.rev.domain.model.HomeFeed
import app.rev.domain.model.HomeGenre
import app.rev.domain.model.HomeSection
import app.rev.domain.model.HomeSectionKind
import app.rev.domain.model.Mix
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

class HomeNavigator(
    val toAlbum: (String) -> Unit,
    val toArtist: (String) -> Unit,
    val toGenre: (String) -> Unit,
    val toSearch: () -> Unit,
    val toDownloads: () -> Unit,
    val toStats: () -> Unit,
    val toMix: (mixId: String) -> Unit,
)

fun NavGraphBuilder.homeScreen(navigator: HomeNavigator) {
    composable<HomeRoute> { HomeScreen(navigator) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(navigator: HomeNavigator, viewModel: HomeViewModel = hiltViewModel()) {
    val feedState by viewModel.feed.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val startingMix by viewModel.startingMix.collectAsStateWithLifecycle()
    val downloadMixes by viewModel.downloadMixes.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val greeting = remember { viewModel.greeting() }
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    val fallbackMessage = stringResource(R.string.home_mix_offline_fallback)
    LaunchedEffect(viewModel) {
        viewModel.playedFromDownloads.collect { snackbar.showSnackbar(fallbackMessage) }
    }

    val feed = feedState.content
    // The header takes a wash of colour from what you've been playing.
    val accent = rememberArtworkAccent(feed?.quickPicks?.firstOrNull()?.coverArtId)
    val background = MaterialTheme.colorScheme.background

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = feedState.isRefreshing && feed != null,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "header") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .artworkGradient(accent, background, reach = 1f)
                            .statusBarsPadding(),
                    ) {
                        Header(greeting, navigator.toSearch, navigator.toStats)
                        feed?.quickPicks?.takeIf { it.isNotEmpty() }?.let { QuickPicks(it, navigator.toAlbum) }
                    }
                }
                // Offline, what's on the device comes first; online it follows the server's mixes.
                if (offline && downloadMixes.isNotEmpty()) {
                    item(key = "download-mixes") {
                        DownloadMixes(downloadMixes, startingMix, viewModel::playMix, navigator.toMix, hint = true)
                    }
                }
                val error = feedState.error
                when {
                    feed == null && error != null -> item(key = "error") {
                        Message(error.message(), action = stringResource(R.string.home_retry), onAction = viewModel::refresh)
                    }
                    feed == null -> item(key = "loading") { Box(Modifier.fillMaxWidth().height(320.dp)) { LoadingView() } }
                    else -> {
                        if (error != null) item(key = "stale") { StaleContentBanner(error, Modifier.padding(vertical = 8.dp)) }
                        feedItems(feed, startingMix, viewModel::playMix, navigator)
                    }
                }
                if (!offline && downloadMixes.isNotEmpty()) {
                    item(key = "download-mixes") {
                        DownloadMixes(downloadMixes, startingMix, viewModel::playMix, navigator.toMix, hint = false)
                    }
                }
                if (activeDownloads > 0) {
                    item(key = "downloads") { DownloadsCard(activeDownloads, navigator.toDownloads) }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.feedItems(
    feed: HomeFeed,
    startingMix: String?,
    onPlayMix: (Mix) -> Unit,
    navigator: HomeNavigator,
) {
    val onOpenMix = navigator.toMix
    if (feed.mixes.isNotEmpty()) {
        item(key = "mixes") {
            SectionHeader(stringResource(R.string.home_mixes))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(feed.mixes, key = { it.id }) { mix ->
                    MixCard(mix, starting = startingMix == mix.id, onClick = { onOpenMix(mix.id) }, onPlay = { onPlayMix(mix) })
                }
            }
        }
    }
    // Your genres go after the first few rows, where they break up the list of albums.
    val genresAfter = feed.sections.indexOfFirst { it.kind == HomeSectionKind.Genre }.takeIf { it >= 0 } ?: feed.sections.size
    feed.sections.forEachIndexed { index, section ->
        if (index == genresAfter && feed.genres.isNotEmpty()) {
            item(key = "genres") { GenreChips(feed.genres, navigator.toGenre) }
        }
        item(key = "section:${section.id}") { AlbumSection(section, navigator) }
    }
    if (genresAfter >= feed.sections.size && feed.genres.isNotEmpty()) {
        item(key = "genres") { GenreChips(feed.genres, navigator.toGenre) }
    }
}

@Composable
private fun DownloadMixes(mixes: List<Mix>, startingMix: String?, onPlayMix: (Mix) -> Unit, onOpenMix: (String) -> Unit, hint: Boolean) {
    SectionHeader(stringResource(R.string.home_offline_mixes))
    if (hint) {
        Text(
            stringResource(R.string.home_offline_mixes_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(mixes, key = { it.id }) { mix ->
            MixCard(mix, starting = startingMix == mix.id, onClick = { onOpenMix(mix.id) }, onPlay = { onPlayMix(mix) })
        }
    }
}

@Composable
private fun Header(greeting: Greeting, onSearch: () -> Unit, onStats: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(
                when (greeting) {
                    Greeting.Morning -> R.string.greeting_morning
                    Greeting.Afternoon -> R.string.greeting_afternoon
                    Greeting.Evening -> R.string.greeting_evening
                    Greeting.Night -> R.string.greeting_night
                },
            ),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        androidx.compose.material3.IconButton(onClick = onStats) {
            Icon(RevIcons.Insights, contentDescription = stringResource(R.string.home_your_listening))
        }
        androidx.compose.material3.IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.home_search))
        }
    }
}

/** Two columns of compact tiles: what you've been playing, one tap away. */
@Composable
private fun QuickPicks(albums: List<Album>, onAlbumClick: (String) -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        albums.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { album ->
                    Surface(
                        onClick = { onAlbumClick(album.id) },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(
                                album.coverArtId,
                                ArtworkSize.THUMBNAIL,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                shape = androidx.compose.ui.graphics.RectangleShape,
                            )
                            Text(
                                album.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp),
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AlbumSection(section: HomeSection, navigator: HomeNavigator) {
    val basedOn = section.basedOn.orEmpty()
    val title = when (section.kind) {
        HomeSectionKind.Recent -> stringResource(R.string.home_recently_played)
        HomeSectionKind.Frequent -> stringResource(R.string.home_on_repeat)
        HomeSectionKind.Similar -> stringResource(R.string.home_because, basedOn)
        HomeSectionKind.Genre -> stringResource(R.string.home_more_genre, basedOn)
        HomeSectionKind.Rediscover -> stringResource(R.string.home_rediscover)
        HomeSectionKind.Unheard -> stringResource(R.string.home_unheard)
        HomeSectionKind.Newest -> stringResource(R.string.home_recently_added)
    }
    val subtitle = when (section.kind) {
        HomeSectionKind.Rediscover -> stringResource(R.string.home_rediscover_hint)
        HomeSectionKind.Unheard -> stringResource(R.string.home_unheard_hint)
        else -> null
    }
    val link: (() -> Unit)? = section.basedOnId?.let { id ->
        when (section.kind) {
            HomeSectionKind.Similar -> { -> navigator.toArtist(id) }
            HomeSectionKind.Genre -> { -> navigator.toGenre(id) }
            else -> null
        }
    }
    SectionHeader(title) {
        if (link != null) {
            TextButton(onClick = link) {
                Text(
                    stringResource(
                        if (section.kind == HomeSectionKind.Similar) R.string.home_open_artist else R.string.home_see_all,
                    ),
                )
            }
        }
    }
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(section.albums, key = { it.id }) { album ->
            AlbumCard(album, onClick = { navigator.toAlbum(album.id) }, modifier = Modifier.width(140.dp))
        }
    }
}

@Composable
private fun GenreChips(genres: List<HomeGenre>, onGenreClick: (String) -> Unit) {
    SectionHeader(stringResource(R.string.home_your_genres))
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { genre ->
            Surface(
                onClick = { onGenreClick(genre.name) },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape,
            ) {
                Text(
                    genre.name,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DownloadsCard(count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RevIcons.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(pluralStringResource(R.plurals.home_downloading, count, count), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action) }
    }
}
