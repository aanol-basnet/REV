package app.rev.feature.stats

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.rev.core.format.formatTotalDuration
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.SectionHeader
import app.rev.core.ui.components.message
import app.rev.core.ui.components.text
import app.rev.core.ui.theme.artworkGradient
import app.rev.core.ui.theme.rememberArtworkAccent
import app.rev.domain.model.ListeningReport
import app.rev.domain.model.RankedAlbum
import app.rev.domain.model.RankedArtist
import app.rev.domain.model.RankedTrack
import app.rev.domain.model.Ranking
import app.rev.domain.model.ReportHighlight
import app.rev.domain.model.ReportPeriodKind
import app.rev.domain.model.SmartPlaylist
import app.rev.domain.model.StatsTrack
import app.rev.domain.model.TimelineUnit
import kotlinx.serialization.Serializable
import app.rev.core.R as CoreR

@Serializable
data object StatsRoute

class StatsNavigator(
    val toArtist: (String) -> Unit,
    val toAlbum: (String) -> Unit,
    val toGenre: (String) -> Unit,
    val toPlaylist: (String) -> Unit,
    /** Year in review for the year [offset] years back. */
    val toYearInReview: (offset: Int) -> Unit,
    val back: () -> Unit,
)

fun NavGraphBuilder.statsScreen(navigator: StatsNavigator) {
    composable<StatsRoute> { StatsScreen(navigator) }
    composable<YearInReviewRoute> { YearInReviewScreen(navigator) }
}

private const val SHORT_LIST = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsScreen(navigator: StatsNavigator, viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val smart by viewModel.smart.collectAsStateWithLifecycle()
    val report = state.report
    val accent = rememberArtworkAccent(report?.topArtists?.firstOrNull()?.coverArtId)
    val background = MaterialTheme.colorScheme.background
    val snackbar = remember { SnackbarHostState() }
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }

    val savedText = stringResource(R.string.smart_saved)
    val updatedText = stringResource(R.string.smart_updated)
    val openText = stringResource(R.string.smart_open_saved)
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.smartEvents.collect { event ->
            when (event) {
                is SmartPlaylistEvent.Saved -> {
                    sheetFor = null
                    val shown = snackbar.showSnackbar(
                        if (event.result.created) savedText else updatedText,
                        actionLabel = openText,
                        duration = SnackbarDuration.Short,
                    )
                    if (shown == SnackbarResult.ActionPerformed) navigator.toPlaylist(event.result.playlistId)
                }
                is SmartPlaylistEvent.Failed -> snackbar.showSnackbar(event.error.text(resources))
            }
        }
    }
    smart.playlists.firstOrNull { it.id == sheetFor }?.let { playlist ->
        SmartPlaylistSheet(
            playlist = playlist,
            saving = smart.saving == playlist.id,
            onPlay = { shuffle ->
                sheetFor = null
                viewModel.playSmartPlaylist(playlist, shuffle)
            },
            onSave = { viewModel.saveSmartPlaylist(playlist) },
            onOpenSaved = { id ->
                sheetFor = null
                navigator.toPlaylist(id)
            },
            onDismiss = { sheetFor = null },
        )
    }

    Scaffold(
        modifier = Modifier.artworkGradient(accent, background, reach = 0.5f),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = navigator.back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreR.string.navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PeriodTabs(state.period, viewModel::selectPeriod)
            PeriodStepper(state, onEarlier = viewModel::earlier, onLater = viewModel::later)
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (state.isLoading && report != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val error = state.error
            when {
                report == null && error != null -> CenteredMessage(error.message()) {
                    TextButton(onClick = viewModel::refresh) { Text(stringResource(CoreR.string.action_retry)) }
                }
                report == null -> LoadingView()
                else -> PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ReportContent(
                        report,
                        staleError = error?.message(),
                        navigator,
                        smart = smart,
                        onOpenSmart = { sheetFor = it.id },
                        onPlaySmart = { viewModel.playSmartPlaylist(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodTabs(selected: ReportPeriodKind, onSelect: (ReportPeriodKind) -> Unit) {
    val periods = ReportPeriodKind.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        periods.forEachIndexed { index, period ->
            SegmentedButton(
                selected = selected == period,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(index, periods.size),
                icon = {},
            ) {
                Text(
                    stringResource(
                        when (period) {
                            ReportPeriodKind.Week -> R.string.stats_period_week
                            ReportPeriodKind.Month -> R.string.stats_period_month
                            ReportPeriodKind.Year -> R.string.stats_period_year
                            ReportPeriodKind.All -> R.string.stats_period_all
                        },
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PeriodStepper(state: StatsUiState, onEarlier: () -> Unit, onLater: () -> Unit) {
    val period = state.report?.period?.takeIf { it.kind == state.period && it.offset == state.offset }
    val format = remember(period?.timeZone) { StatsFormat(period?.timeZone ?: java.time.ZoneId.systemDefault().id) }
    val title = when {
        state.period == ReportPeriodKind.All -> stringResource(R.string.stats_period_all)
        period == null -> ""
        else -> when (state.period) {
            ReportPeriodKind.Week -> when (state.offset) {
                0 -> stringResource(R.string.stats_this_week)
                1 -> stringResource(R.string.stats_last_week)
                else -> format.range(period.from, period.to)
            }
            ReportPeriodKind.Month -> format.month(period.from)
            ReportPeriodKind.Year -> format.year(period.from)
            ReportPeriodKind.All -> ""
        }
    }
    val subtitle = when {
        period == null -> null
        state.period == ReportPeriodKind.All -> state.report.trackingSince?.let { stringResource(R.string.stats_since, format.day(it)) }
        state.period == ReportPeriodKind.Week && state.offset <= 1 -> format.range(period.from, period.to)
        state.offset == 0 && state.period == ReportPeriodKind.Month -> stringResource(R.string.stats_this_month)
        state.offset == 0 && state.period == ReportPeriodKind.Year -> stringResource(R.string.stats_this_year)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val stepping = state.period != ReportPeriodKind.All
        IconButton(onClick = onEarlier, enabled = state.canGoEarlier) {
            if (stepping) Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.stats_earlier))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        IconButton(onClick = onLater, enabled = state.canGoLater) {
            if (stepping) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.stats_later))
        }
    }
}

@Composable
private fun ReportContent(
    report: ListeningReport,
    staleError: String?,
    navigator: StatsNavigator,
    smart: SmartPlaylistsState,
    onOpenSmart: (SmartPlaylist) -> Unit,
    onPlaySmart: (SmartPlaylist) -> Unit,
) {
    val format = remember(report.period.timeZone) { StatsFormat(report.period.timeZone) }
    val empty = report.totals.plays == 0 && report.totals.skips == 0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (staleError != null) {
            item(key = "stale") {
                Text(
                    stringResource(R.string.stats_showing_saved, staleError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (empty) {
            item(key = "empty") {
                CenteredMessage(
                    stringResource(if (report.trackingSince == null) R.string.stats_empty_new else R.string.stats_empty_period),
                    Modifier.height(320.dp),
                )
            }
            item(key = "smart") { SmartPlaylistRow(smart, onOpenSmart, onPlaySmart) }
            return@LazyColumn
        }
        item(key = "hero") { Hero(report, format) }
        if (report.period.kind == ReportPeriodKind.Year) {
            item(key = "review") { YearInReviewCard(report, format) { navigator.toYearInReview(report.period.offset) } }
        }
        if (report.highlights.isNotEmpty()) item(key = "highlights") { Highlights(report.highlights) }
        item(key = "numbers") { Numbers(report, format) }
        item(key = "smart") { SmartPlaylistRow(smart, onOpenSmart, onPlaySmart) }
        genres(report, navigator)
        artists(report, navigator)
        if (report.topAlbums.isNotEmpty()) item(key = "albums") { Albums(report.topAlbums, navigator) }
        tracks(report, navigator, format)
        item(key = "timeline") { Timeline(report, format) }
        if (report.timeline.unit == TimelineUnit.Month && report.timeline.points.count { it.topGenre != null } > 1) {
            item(key = "genre-by-month") { GenreByMonth(report, format) }
        }
        item(key = "rhythm") { Rhythm(report, format) }
        discoveries(report, navigator, format)
        skipped(report, navigator)
        report.trackingSince?.let { since ->
            item(key = "since") {
                Text(
                    stringResource(R.string.stats_tracking_since, format.day(since)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
        }
    }
}

// --- Headline ------------------------------------------------------------------------------------

@Composable
private fun Hero(report: ListeningReport, format: StatsFormat) {
    val totals = report.totals
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        report.persona?.let { persona ->
            Text(persona.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(persona.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            HeroNumber(
                label = stringResource(R.string.stats_listening_time),
                value = formatTotalDuration(totals.listeningMs),
                change = report.previous?.let { format.change(totals.listeningMs, it.listeningMs) },
            )
            HeroNumber(
                label = stringResource(R.string.stats_plays),
                value = totals.plays.toString(),
                change = report.previous?.let { format.change(totals.plays.toLong(), it.plays.toLong()) },
            )
        }
    }
}

@Composable
private fun HeroNumber(label: String, value: String, change: Int?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        if (change != null && change != 0) {
            val amount = "${kotlin.math.abs(change)}%"
            Text(
                stringResource(
                    R.string.stats_vs_previous,
                    stringResource(if (change > 0) R.string.stats_more else R.string.stats_less, amount),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Highlights(highlights: List<ReportHighlight>) {
    SectionHeader(stringResource(R.string.stats_highlights))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(highlights, key = { it.kind }) { h ->
            Card(
                Modifier.width(240.dp).height(132.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        h.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Numbers(report: ListeningReport, format: StatsFormat) {
    val t = report.totals
    val days = @Composable { n: Int -> pluralStringResource(R.plurals.stats_days, n, n) }
    val tiles = buildList {
        add(stringResource(R.string.stats_tracks) to t.tracks.toString())
        add(stringResource(R.string.stats_artists) to t.artists.toString())
        add(stringResource(R.string.stats_albums) to t.albums.toString())
        add(stringResource(R.string.stats_genres) to t.genres.toString())
        add(stringResource(R.string.stats_active_days) to stringResource(R.string.stats_of_days, t.activeDays, report.period.elapsedDays))
        add(stringResource(R.string.stats_daily_average) to formatTotalDuration(t.dailyAverageMs))
        add(stringResource(R.string.stats_new_songs) to format.percent(report.discoveries.newTrackShare))
        add(stringResource(R.string.stats_variety) to stringResource(R.string.stats_variety_value, report.variety.score))
        if (report.streaks.longestDays > 0) add(stringResource(R.string.stats_longest_streak) to days(report.streaks.longestDays))
        if (report.period.offset == 0 && report.streaks.currentDays > 0) {
            add(stringResource(R.string.stats_current_streak) to days(report.streaks.currentDays))
        }
        if (report.sessions.longestMs > 0) add(stringResource(R.string.stats_longest_session) to formatTotalDuration(report.sessions.longestMs))
        if (t.skips > 0) add(stringResource(R.string.stats_skip_rate) to format.percent(t.skipRate))
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

// --- Rankings ------------------------------------------------------------------------------------

/** Movement against the period before: ▲2, ▼1, NEW or nothing. */
@Composable
private fun Movement(rank: Int, ranking: Ranking, comparable: Boolean, isNew: Boolean = false) {
    val previous = ranking.previousRank
    val text = when {
        isNew -> stringResource(R.string.stats_new)
        !comparable -> null
        previous == null -> stringResource(R.string.stats_new)
        previous > rank -> "▲${previous - rank}"
        previous < rank -> "▼${rank - previous}"
        else -> null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
private fun RankNumber(rank: Int) {
    Text(
        rank.toString(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(28.dp),
    )
}

@Composable
private fun ShowAll(expanded: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle) {
        Text(stringResource(if (expanded) R.string.stats_show_less else R.string.stats_show_all))
    }
}

private fun LazyListScope.genres(report: ListeningReport, navigator: StatsNavigator) {
    if (report.topGenres.isEmpty()) return
    item(key = "genres") {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val shown = if (expanded) report.topGenres else report.topGenres.take(SHORT_LIST)
        val top = report.topGenres.first().ranking.share.toFloat().coerceAtLeast(0.01f)
        SectionHeader(stringResource(R.string.stats_top_genres)) {
            if (report.topGenres.size > SHORT_LIST) ShowAll(expanded) { expanded = !expanded }
        }
        Column {
            shown.forEachIndexed { i, genre ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navigator.toGenre(genre.name) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RankNumber(i + 1)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(genre.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Movement(i + 1, genre.ranking, comparable = report.previous != null)
                        }
                        Spacer(Modifier.height(4.dp))
                        // Bars are scaled to the top genre so differences stay visible.
                        ShareBar(genre.ranking.share.toFloat() / top, Modifier.fillMaxWidth())
                    }
                    Text(
                        "${(genre.ranking.share * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp).width(36.dp),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.artists(report: ListeningReport, navigator: StatsNavigator) {
    if (report.topArtists.isEmpty()) return
    item(key = "artists") {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val shown = if (expanded) report.topArtists else report.topArtists.take(SHORT_LIST)
        SectionHeader(stringResource(R.string.stats_top_artists)) {
            if (report.topArtists.size > SHORT_LIST) ShowAll(expanded) { expanded = !expanded }
        }
        Column { shown.forEachIndexed { i, artist -> ArtistRankRow(i + 1, artist, report.previous != null, navigator) } }
    }
}

@Composable
private fun ArtistRankRow(rank: Int, artist: RankedArtist, comparable: Boolean, navigator: StatsNavigator) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = artist.id != null) { artist.id?.let(navigator.toArtist) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankNumber(rank)
        Artwork(artist.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(44.dp), shape = CircleShape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${pluralStringResource(R.plurals.stats_play_count, artist.ranking.plays, artist.ranking.plays)} · ${formatTotalDuration(artist.ranking.listeningMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Movement(rank, artist.ranking, comparable, isNew = artist.isNew)
    }
}

@Composable
private fun Albums(albums: List<RankedAlbum>, navigator: StatsNavigator) {
    SectionHeader(stringResource(R.string.stats_top_albums))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums.take(15).withIndex().toList(), key = { (_, a) -> "${a.id}:${a.name}" }) { (i, album) ->
            Column(
                Modifier
                    .width(136.dp)
                    .clickable(enabled = album.id != null) { album.id?.let(navigator.toAlbum) },
            ) {
                Box {
                    Artwork(album.coverArtId, ArtworkSize.CARD, contentDescription = null)
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(album.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${album.artist} · ${pluralStringResource(R.plurals.stats_play_count, album.ranking.plays, album.ranking.plays)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun LazyListScope.tracks(report: ListeningReport, navigator: StatsNavigator, format: StatsFormat) {
    if (report.topTracks.isNotEmpty()) {
        item(key = "tracks") {
            var expanded by rememberSaveable { mutableStateOf(false) }
            val shown = if (expanded) report.topTracks else report.topTracks.take(SHORT_LIST)
            SectionHeader(stringResource(R.string.stats_top_tracks)) {
                if (report.topTracks.size > SHORT_LIST) ShowAll(expanded) { expanded = !expanded }
            }
            Column {
                shown.forEachIndexed { i, ranked -> TrackRankRow(i + 1, ranked, report.previous != null, navigator) }
            }
        }
    }
    if (report.onRepeat.isNotEmpty()) {
        item(key = "repeat") {
            SectionHeader(stringResource(R.string.stats_on_repeat))
            Column {
                report.onRepeat.forEach { r ->
                    TrackLine(
                        r.track,
                        stringResource(
                            R.string.stats_on_repeat_detail,
                            r.peakDayPlays,
                            format.weekdayDay(r.peakDay),
                            pluralStringResource(R.plurals.stats_play_count, r.plays, r.plays),
                        ),
                        navigator,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRankRow(rank: Int, ranked: RankedTrack, comparable: Boolean, navigator: StatsNavigator) {
    TrackLine(
        ranked.track,
        "${ranked.track.artist} · ${pluralStringResource(R.plurals.stats_play_count, ranked.ranking.plays, ranked.ranking.plays)}",
        navigator,
        leading = { RankNumber(rank) },
        trailing = { Movement(rank, ranked.ranking, comparable) },
    )
}

/** A song; tapping it opens its album. */
@Composable
private fun TrackLine(
    track: StatsTrack,
    detail: String,
    navigator: StatsNavigator,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = track.albumId != null) { track.albumId?.let(navigator.toAlbum) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Artwork(track.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

// --- Time ----------------------------------------------------------------------------------------

@Composable
private fun Timeline(report: ListeningReport, format: StatsFormat) {
    val points = report.timeline.points
    if (points.isEmpty()) return
    val byMonth = report.timeline.unit == TimelineUnit.Month
    var selected by rememberSaveable(report.period.kind, report.period.offset) {
        mutableIntStateOf(points.indices.maxByOrNull { points[it].listeningMs } ?: 0)
    }
    val spansYears = byMonth && format.year(points.first().start) != format.year(points.last().start)
    // Label a handful of bars so the axis stays readable.
    val every = when {
        points.size <= 7 -> 1
        points.size <= 12 -> if (byMonth) 1 else 2
        points.size <= 31 -> 5
        else -> (points.size + 5) / 6
    }
    val labels = points.mapIndexed { i, p -> if (i % every == 0) format.axisLabel(p.start, byMonth, spansYears) else null }
    SectionHeader(stringResource(R.string.stats_over_time))
    Column(Modifier.padding(horizontal = 16.dp)) {
        BarChart(
            values = points.map { it.listeningMs },
            selected = selected,
            onSelect = { selected = it },
            labels = labels,
            description = stringResource(R.string.stats_over_time),
        )
        points.getOrNull(selected)?.let { p ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.stats_point_detail,
                    if (byMonth) format.month(p.start) else format.weekdayDay(p.start),
                    formatTotalDuration(p.listeningMs),
                    pluralStringResource(R.plurals.stats_play_count, p.plays, p.plays),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            listOfNotNull(p.topArtist, p.topGenre).takeIf { it.isNotEmpty() }?.let {
                Text(
                    stringResource(R.string.stats_point_top, it.joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GenreByMonth(report: ListeningReport, format: StatsFormat) {
    SectionHeader(stringResource(R.string.stats_genre_by_month))
    Column(Modifier.padding(horizontal = 16.dp)) {
        report.timeline.points.reversed().filter { it.topGenre != null }.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    format.month(p.start),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(140.dp),
                )
                Text(p.topGenre.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                p.topArtist?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Rhythm(report: ListeningReport, format: StatsFormat) {
    val rhythm = report.rhythm
    if (rhythm.byHour.sum() == 0) return
    var cell by remember(report.period.kind, report.period.offset) { mutableStateOf<Pair<Int, Int>?>(null) }
    SectionHeader(stringResource(R.string.stats_when))
    Column(Modifier.padding(horizontal = 16.dp)) {
        val peakHour = rhythm.peakHour
        val peakDay = rhythm.peakWeekday
        if (peakHour != null && peakDay != null) {
            Text(
                stringResource(R.string.stats_peak, format.weekday(peakDay), format.hour(peakHour)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
        }
        Heatmap(
            heatmap = rhythm.heatmap,
            selected = cell,
            onSelect = { cell = it },
            format = format,
            description = stringResource(R.string.stats_when),
        )
        cell?.let { (day, hour) ->
            val plays = rhythm.heatmap.getOrNull(day)?.getOrNull(hour) ?: 0
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.stats_cell_detail,
                    format.weekday(day),
                    format.hour(hour),
                    pluralStringResource(R.plurals.stats_play_count, plays, plays),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- New and skipped -----------------------------------------------------------------------------

private fun LazyListScope.discoveries(report: ListeningReport, navigator: StatsNavigator, format: StatsFormat) {
    val d = report.discoveries
    if (report.period.kind == ReportPeriodKind.All || (d.artistCount == 0 && d.newTracks == 0)) return
    item(key = "discoveries") {
        SectionHeader(stringResource(R.string.stats_discoveries))
        Text(
            stringResource(
                R.string.stats_discoveries_detail,
                pluralStringResource(R.plurals.stats_new_artists, d.artistCount, d.artistCount),
                pluralStringResource(R.plurals.stats_new_song_count, d.newTracks, d.newTracks),
                format.percent(d.newTrackShare),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Column {
            d.artists.forEachIndexed { i, artist -> ArtistRankRow(i + 1, artist, comparable = false, navigator = navigator) }
        }
    }
}

private fun LazyListScope.skipped(report: ListeningReport, navigator: StatsNavigator) {
    if (report.mostSkipped.isEmpty()) return
    item(key = "skipped") {
        SectionHeader(stringResource(R.string.stats_most_skipped))
        Column {
            report.mostSkipped.forEach { s ->
                TrackLine(
                    s.track,
                    stringResource(
                        R.string.stats_skipped_detail,
                        pluralStringResource(R.plurals.stats_skip_count, s.skips, s.skips),
                        s.plays,
                    ),
                    navigator,
                )
            }
        }
    }
}
