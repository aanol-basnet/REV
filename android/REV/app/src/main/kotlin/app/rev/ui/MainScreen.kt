package app.rev.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavDestination
import app.rev.core.ui.components.Glass
import app.rev.core.ui.components.swipeGestures
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rev.domain.model.ConnectionState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import app.rev.R
import app.rev.core.ui.icons.RevIcons
import app.rev.feature.home.HomeNavigator
import app.rev.feature.home.HomeRoute
import app.rev.feature.home.homeScreen
import app.rev.feature.library.AlbumRoute
import app.rev.feature.library.ArtistRoute
import app.rev.feature.library.ArtistSongsRoute
import app.rev.feature.library.GenreRoute
import app.rev.feature.library.LibraryNavigator
import app.rev.feature.library.LibraryRoute
import app.rev.feature.library.MixRoute
import app.rev.feature.library.PlaylistEditRoute
import app.rev.feature.library.PlaylistRoute
import app.rev.feature.library.libraryScreens
import app.rev.feature.lyrics.LyricsRoute
import app.rev.feature.lyrics.lyricsScreen
import app.rev.feature.player.MiniPlayer
import app.rev.feature.player.PlayerNavigator
import app.rev.feature.player.PlayerRoute
import app.rev.feature.player.QueueRoute
import app.rev.feature.player.playerScreens
import app.rev.feature.search.SearchNavigator
import app.rev.feature.search.SearchRoute
import app.rev.feature.search.CatalogAlbumRoute
import app.rev.feature.search.searchScreens
import app.rev.feature.downloads.DownloadsNavigator
import app.rev.feature.downloads.DownloadsRoute
import app.rev.feature.downloads.downloadsScreen
import app.rev.feature.settings.EqualizerRoute
import app.rev.feature.settings.EqualizerScreen
import app.rev.feature.settings.SettingsRoute
import app.rev.feature.settings.SystemStatusRoute
import app.rev.feature.settings.SystemStatusScreen
import app.rev.feature.stats.StatsNavigator
import app.rev.feature.stats.StatsRoute
import app.rev.feature.stats.YearInReviewRoute
import app.rev.feature.stats.statsScreen
import kotlinx.serialization.Serializable

@Serializable
data object SettingsDestination

/** Top-level sections (§35). */
private enum class Tab(val label: Int, val icon: ImageVector, val route: Any) {
    Home(R.string.tab_home, Icons.Filled.Home, HomeRoute),
    Library(R.string.tab_library, RevIcons.Library, LibraryRoute),
    Search(R.string.tab_search, Icons.Filled.Search, SearchRoute),
    Downloads(R.string.tab_downloads, RevIcons.Download, DownloadsRoute),
    Settings(R.string.tab_settings, Icons.Filled.Settings, SettingsDestination),
}

@Composable
fun MainScreen(connection: ConnectionState) {
    val navController = rememberNavController()
    // Detail screens keep the tab they were opened from highlighted; landing on a tab's own
    // screen (including by going back) highlights that tab.
    var lastTab by rememberSaveable { mutableIntStateOf(Tab.Home.ordinal) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = destination?.tabIndex()?.takeIf { it >= 0 } ?: lastTab
    lastTab = currentTab

    fun selectTab(tab: Tab) {
        val reselected = currentTab == tab.ordinal
        lastTab = tab.ordinal
        // Tapping the tab you're on goes back to its start (e.g. from an album to Home).
        if (reselected && navController.popBackStack(tab.route, inclusive = false)) return
        navController.navigateToTab(tab.route)
    }

    // The full player, its queue and lyrics take the whole screen.
    val fullScreen = destination?.hasRoute<PlayerRoute>() == true ||
        destination?.hasRoute<QueueRoute>() == true ||
        destination?.hasRoute<LyricsRoute>() == true

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Tablets, foldables and landscape: a side rail instead of a bottom bar.
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide && !fullScreen) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Spacer(Modifier.weight(1f))
                    Tab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = currentTab == tab.ordinal,
                            onClick = { selectTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                // Swipe left/right on a top-level screen to move between tabs.
                val swipeTabs = destination?.tabIndex()?.let { it >= 0 } == true
                Box(
                    Modifier
                        .weight(1f)
                        .swipeGestures(
                            onSwipeLeft = if (swipeTabs) ({ Tab.entries.getOrNull(currentTab + 1)?.let(::selectTab) }) else null,
                            onSwipeRight = if (swipeTabs) ({ Tab.entries.getOrNull(currentTab - 1)?.let(::selectTab) }) else null,
                        ),
                ) {
                    RevNavHost(
                        navController = navController,
                        selectTab = ::selectTab,
                        // Bottom chrome below handles the navigation bar inset.
                        modifier = if (fullScreen) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .fillMaxSize()
                                .consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        },
                    )
                }
                if (!fullScreen) {
                    ConnectionBanner(connection)
                    MiniPlayer(
                        onExpand = { navController.navigate(PlayerRoute) { launchSingleTop = true } },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp),
                    )
                    if (wide) {
                        Spacer(Modifier.navigationBarsPadding())
                    } else {
                        FloatingNavBar(currentTab, onSelect = ::selectTab)
                    }
                }
            }
        }
    }
}

/** §74: say plainly what still works when the server can't be reached. */
@Composable
private fun ConnectionBanner(connection: ConnectionState) {
    val message = when (connection) {
        ConnectionState.NoNetwork -> R.string.banner_offline
        ConnectionState.ServerUnreachable -> R.string.banner_server_unreachable
        else -> null
    }
    AnimatedVisibility(visible = message != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Text(
                message?.let { stringResource(it) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun RevNavHost(navController: NavHostController, selectTab: (Tab) -> Unit, modifier: Modifier) {
    val navigator = LibraryNavigator(
        toArtist = { navController.navigate(ArtistRoute(it)) },
        toAlbum = { navController.navigate(AlbumRoute(it)) },
        toGenre = { navController.navigate(GenreRoute(it)) },
        toPlaylist = { navController.navigate(PlaylistRoute(it)) },
        toArtistSongs = { id, name -> navController.navigate(ArtistSongsRoute(id, name)) },
        back = { navController.popBackStack() },
        toPlaylistEdit = { navController.navigate(PlaylistEditRoute(it)) { launchSingleTop = true } },
        // Back past the deleted playlist's page as well as the editor.
        playlistDeleted = { id ->
            if (!navController.popBackStack(PlaylistRoute(id), inclusive = true)) navController.popBackStack()
        },
    )
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
        // Moving between tabs slides toward the tab's side, following a swipe; everything else is a
        // short fade.
        enterTransition = {
            when (val dir = tabDirection(initialState.destination, targetState.destination)) {
                0 -> fadeIn(tween(TRANSITION_MS))
                else -> slideInHorizontally(tween(TAB_SLIDE_MS)) { it / 4 * dir } + fadeIn(tween(TAB_SLIDE_MS))
            }
        },
        exitTransition = {
            when (val dir = tabDirection(initialState.destination, targetState.destination)) {
                0 -> fadeOut(tween(TRANSITION_MS))
                else -> slideOutHorizontally(tween(TAB_SLIDE_MS)) { -it / 4 * dir } + fadeOut(tween(TAB_SLIDE_MS))
            }
        },
        popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = { fadeOut(tween(TRANSITION_MS)) },
    ) {
        homeScreen(
            HomeNavigator(
                toAlbum = navigator.toAlbum,
                toArtist = navigator.toArtist,
                toGenre = navigator.toGenre,
                toSearch = { selectTab(Tab.Search) },
                toDownloads = { selectTab(Tab.Downloads) },
                toStats = { navController.navigate(StatsRoute) { launchSingleTop = true } },
                toMix = { navController.navigate(MixRoute(it)) },
            ),
        )
        statsScreen(
            StatsNavigator(
                toArtist = navigator.toArtist,
                toAlbum = navigator.toAlbum,
                toGenre = navigator.toGenre,
                toPlaylist = navigator.toPlaylist,
                toYearInReview = { offset -> navController.navigate(YearInReviewRoute(offset)) },
                back = { navController.popBackStack() },
            ),
        )
        downloadsScreen(
            DownloadsNavigator(
                toCatalogAlbum = { navController.navigate(CatalogAlbumRoute(it)) },
                toAlbum = navigator.toAlbum,
                toPlaylist = navigator.toPlaylist,
                toArtist = navigator.toArtist,
                toArtistSongs = navigator.toArtistSongs,
                toMix = { navController.navigate(MixRoute(it)) },
            ),
        )
        libraryScreens(navigator)
        searchScreens(
            SearchNavigator(
                toArtist = navigator.toArtist,
                toAlbum = navigator.toAlbum,
                back = { navController.popBackStack() },
            ),
        )
        playerScreens(
            PlayerNavigator(
                openQueue = { navController.navigate(QueueRoute) { launchSingleTop = true } },
                openLyrics = { navController.navigate(LyricsRoute) { launchSingleTop = true } },
                toArtist = { navController.navigate(ArtistRoute(it)) },
                toAlbum = { navController.navigate(AlbumRoute(it)) },
                back = { navController.popBackStack() },
            ),
        )
        lyricsScreen(onClose = { navController.popBackStack() })
        composable<SettingsDestination> {
            SettingsRoute(
                onOpenSystemStatus = { navController.navigate(SystemStatusRoute) },
                onOpenEqualizer = { navController.navigate(EqualizerRoute) },
            )
        }
        composable<SystemStatusRoute> { SystemStatusScreen(onBack = { navController.popBackStack() }) }
        composable<EqualizerRoute> { EqualizerScreen(onBack = { navController.popBackStack() }) }
    }
}

private const val TRANSITION_MS = 200
private const val TAB_SLIDE_MS = 260

private fun NavDestination.tabIndex(): Int = Tab.entries.indexOfFirst { hasRoute(it.route::class) }

/** 1 when going to a tab on the right, -1 to the left, 0 when not moving between two tabs. */
private fun tabDirection(from: NavDestination, to: NavDestination): Int {
    val a = from.tabIndex()
    val b = to.tabIndex()
    return if (a < 0 || b < 0 || a == b) 0 else if (b > a) 1 else -1
}

/**
 * A floating glass pill over the content: icons only, except the current tab, which also shows
 * its name in an inverted capsule.
 */
@Composable
private fun FloatingNavBar(current: Int, onSelect: (Tab) -> Unit) {
    Surface(
        shape = CircleShape,
        color = Glass.container(),
        border = Glass.hairline,
        shadowElevation = 8.dp,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth(),
    ) {
        Row(
            Modifier
                .padding(6.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                val selected = current == tab.ordinal
                val weight by animateFloatAsState(if (selected) 2.4f else 1f, label = "tabWeight")
                NavPill(tab, selected, onClick = { onSelect(tab) }, Modifier.weight(weight))
            }
        }
    }
}

@Composable
private fun NavPill(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val label = stringResource(tab.label)
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
        label = "tabBackground",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tabContent",
    )
    Box(
        modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(background)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(tab.icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
