package app.rev.feature.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.format.formatTotalDuration
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.message
import app.rev.core.ui.theme.artworkGradient
import app.rev.core.ui.theme.rememberArtworkAccent
import app.rev.domain.model.Discoveries
import app.rev.domain.model.ListeningReport
import app.rev.domain.model.RankedAlbum
import app.rev.domain.model.RankedArtist
import app.rev.domain.model.RankedGenre
import app.rev.domain.model.RankedTrack
import app.rev.domain.model.ReportHighlight
import app.rev.domain.model.TimelinePoint
import app.rev.domain.model.TimelineUnit
import kotlinx.coroutines.launch
import app.rev.core.R as CoreR

/** The way into year in review, on the Year tab of the stats screen. */
@Composable
internal fun YearInReviewCard(report: ListeningReport, format: StatsFormat, onOpen: () -> Unit) {
    val cover = report.topArtists.firstOrNull()?.coverArtId ?: report.topTracks.firstOrNull()?.track?.coverArtId
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(cover, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.review_cta, format.year(report.period.from)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.review_cta_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** One page of the story. [art] tints the background. */
private sealed interface Slide {
    val art: String?

    data class Intro(val report: ListeningReport) : Slide {
        override val art get() = report.topArtists.firstOrNull()?.coverArtId
    }
    data class Persona(val persona: ReportHighlight, override val art: String?) : Slide
    data class TopSong(val track: RankedTrack) : Slide {
        override val art get() = track.track.coverArtId
    }
    data class TopArtists(val artists: List<RankedArtist>) : Slide {
        override val art get() = artists.first().coverArtId
    }
    data class TopAlbums(val albums: List<RankedAlbum>) : Slide {
        override val art get() = albums.first().coverArtId
    }
    data class TopGenres(val genres: List<RankedGenre>, override val art: String?) : Slide
    data class Rhythm(val report: ListeningReport, val peakMonth: TimelinePoint?, override val art: String?) : Slide
    data class Found(val discoveries: Discoveries) : Slide {
        override val art get() = discoveries.artists.firstOrNull()?.coverArtId
    }
    data class Outro(val report: ListeningReport) : Slide {
        override val art get() = report.topTracks.firstOrNull()?.track?.coverArtId
    }
}

private const val LIST = 5

/** The pages there's something to say on, in story order. */
private fun slidesFor(report: ListeningReport): List<Slide> = buildList {
    val art = report.topArtists.firstOrNull()?.coverArtId
    add(Slide.Intro(report))
    report.persona?.let { add(Slide.Persona(it, art)) }
    report.topTracks.firstOrNull()?.let { add(Slide.TopSong(it)) }
    if (report.topArtists.isNotEmpty()) add(Slide.TopArtists(report.topArtists.take(LIST)))
    if (report.topAlbums.isNotEmpty()) add(Slide.TopAlbums(report.topAlbums.take(LIST)))
    if (report.topGenres.isNotEmpty()) add(Slide.TopGenres(report.topGenres.take(LIST), report.topAlbums.firstOrNull()?.coverArtId ?: art))
    val peakMonth = report.timeline.points.takeIf { report.timeline.unit == TimelineUnit.Month }?.maxByOrNull { it.listeningMs }
    if (report.rhythm.peakHour != null || peakMonth != null || report.streaks.longestDays > 1) {
        add(Slide.Rhythm(report, peakMonth, report.topAlbums.getOrNull(1)?.coverArtId ?: art))
    }
    if (report.discoveries.artistCount > 0) add(Slide.Found(report.discoveries))
    add(Slide.Outro(report))
}

@Composable
internal fun YearInReviewScreen(navigator: StatsNavigator, viewModel: YearInReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    val report = state.report
    val background = MaterialTheme.colorScheme.background
    val error = state.error

    when {
        report == null && error != null -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
            CenteredMessage(error.message()) {
                TextButton(onClick = viewModel::load) { Text(stringResource(CoreR.string.action_retry)) }
            }
            CloseButton(navigator.back, Modifier.align(Alignment.TopEnd))
        }
        report == null -> LoadingView()
        report.totals.plays == 0 -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
            CenteredMessage(stringResource(R.string.review_empty, state.year ?: 0))
            CloseButton(navigator.back, Modifier.align(Alignment.TopEnd))
        }
        else -> {
            val format = remember(report.period.timeZone) { StatsFormat(report.period.timeZone) }
            val slides = remember(report) { slidesFor(report) }
            val pager = rememberPagerState { slides.size }
            val scope = rememberCoroutineScope()
            val accent = rememberArtworkAccent(slides[pager.currentPage].art)
            Box(Modifier.fillMaxSize().artworkGradient(accent, background, reach = 1f)) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            // Tap the left edge to go back, anywhere else to go on, as in a story.
                            .pointerInput(page) {
                                detectTapGestures { offset ->
                                    val target = if (offset.x < size.width * 0.3f) page - 1 else page + 1
                                    if (target in slides.indices) scope.launch { pager.animateScrollToPage(target) }
                                }
                            }
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SlideContent(slides[page], state, format, viewModel, navigator)
                    }
                }
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Progress(pager.currentPage, slides.size)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.review_title, state.year ?: 0),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                        CloseButton(navigator.back)
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.review_close))
    }
}

/** One bar per page; the current one fills as it's reached. */
@Composable
private fun Progress(current: Int, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            val filled by animateFloatAsState(if (i <= current) 1f else 0f, label = "progress")
            LinearProgressIndicator(
                progress = { filled },
                modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun SlideContent(
    slide: Slide,
    state: YearInReviewState,
    format: StatsFormat,
    viewModel: YearInReviewViewModel,
    navigator: StatsNavigator,
) {
    when (slide) {
        is Slide.Intro -> Centered {
            val totals = slide.report.totals
            Kicker(stringResource(R.string.review_intro_kicker, state.year ?: 0))
            Big(formatTotalDuration(totals.listeningMs))
            Body(stringResource(R.string.review_intro_detail))
            Spacer(Modifier.height(24.dp))
            Body(
                stringResource(
                    R.string.review_intro_numbers,
                    pluralStringResource(R.plurals.stats_play_count, totals.plays, totals.plays),
                    pluralStringResource(R.plurals.review_artist_count, totals.artists, totals.artists),
                    pluralStringResource(R.plurals.stats_days, totals.activeDays, totals.activeDays),
                ),
            )
        }
        is Slide.Persona -> Centered {
            Kicker(stringResource(R.string.review_persona_kicker))
            Big(slide.persona.title)
            Body(slide.persona.detail)
        }
        is Slide.TopSong -> Centered {
            Kicker(stringResource(R.string.review_top_song_kicker))
            Artwork(
                slide.track.track.coverArtId,
                ArtworkSize.LARGE,
                contentDescription = null,
                modifier = Modifier.widthIn(max = 280.dp).padding(vertical = 16.dp),
                shape = MaterialTheme.shapes.large,
            )
            Title(slide.track.track.title)
            Body(slide.track.track.artist)
            Spacer(Modifier.height(12.dp))
            Body(pluralStringResource(R.plurals.review_played_times, slide.track.ranking.plays, slide.track.ranking.plays))
        }
        is Slide.TopArtists -> Ranked(
            kicker = stringResource(R.string.review_top_artists_kicker),
            hero = slide.artists.first().let { Triple(it.coverArtId, it.name, formatTotalDuration(it.ranking.listeningMs)) },
            rows = slide.artists.drop(1).map { it.name to formatTotalDuration(it.ranking.listeningMs) },
            onHero = slide.artists.first().id?.let { id -> { navigator.toArtist(id) } },
        )
        is Slide.TopAlbums -> Ranked(
            kicker = stringResource(R.string.review_top_albums_kicker),
            hero = slide.albums.first().let { Triple(it.coverArtId, it.name, it.artist) },
            rows = slide.albums.drop(1).map { it.name to it.artist },
            onHero = slide.albums.first().id?.let { id -> { navigator.toAlbum(id) } },
        )
        is Slide.TopGenres -> Column(Modifier.fillMaxWidth()) {
            Kicker(stringResource(R.string.review_top_genres_kicker))
            Spacer(Modifier.height(16.dp))
            slide.genres.forEachIndexed { i, genre ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(genre.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(
                            progress = { genre.ranking.share.toFloat() / slide.genres.first().ranking.share.toFloat().coerceAtLeast(0.01f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            drawStopIndicator = {},
                        )
                    }
                    Text(format.percent(genre.ranking.share), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        is Slide.Rhythm -> Centered {
            Kicker(stringResource(R.string.review_rhythm_kicker))
            slide.peakMonth?.let { month ->
                Big(format.monthName(month.start))
                Body(stringResource(R.string.review_peak_month, formatTotalDuration(month.listeningMs)))
                Spacer(Modifier.height(24.dp))
            }
            val weekday = slide.report.rhythm.peakWeekday
            val hour = slide.report.rhythm.peakHour
            if (weekday != null && hour != null) {
                Title(stringResource(R.string.review_peak_time, format.weekday(weekday), format.hour(hour)))
                Spacer(Modifier.height(16.dp))
            }
            if (slide.report.streaks.longestDays > 1) {
                Body(
                    stringResource(
                        R.string.review_streak,
                        pluralStringResource(R.plurals.stats_days, slide.report.streaks.longestDays, slide.report.streaks.longestDays),
                    ),
                )
            }
            if (slide.report.sessions.longestMs > 0) {
                Body(stringResource(R.string.review_longest_session, formatTotalDuration(slide.report.sessions.longestMs)))
            }
        }
        is Slide.Found -> Centered {
            val d = slide.discoveries
            Kicker(stringResource(R.string.review_discoveries_kicker))
            Big(d.artistCount.toString())
            Body(pluralStringResource(R.plurals.review_new_artists, d.artistCount))
            d.artists.firstOrNull()?.let { first ->
                Artwork(
                    first.coverArtId,
                    ArtworkSize.CARD,
                    contentDescription = null,
                    modifier = Modifier.widthIn(max = 160.dp).padding(top = 24.dp, bottom = 8.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                )
                Body(stringResource(R.string.review_top_discovery, first.name))
            }
            Spacer(Modifier.height(12.dp))
            Body(stringResource(R.string.review_new_song_share, format.percent(d.newTrackShare)))
        }
        is Slide.Outro -> Column(Modifier.fillMaxWidth()) {
            Kicker(stringResource(R.string.review_outro_kicker, state.year ?: 0))
            Spacer(Modifier.height(12.dp))
            slide.report.topTracks.take(LIST).forEachIndexed { i, ranked ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp),
                    )
                    Artwork(ranked.track.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ranked.track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            ranked.track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = viewModel::playTopSongs, enabled = !state.starting, modifier = Modifier.fillMaxWidth()) {
                if (state.starting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(stringResource(R.string.review_play_top))
                }
            }
            Spacer(Modifier.height(8.dp))
            val saved = state.saved
            if (saved == null) {
                OutlinedButton(onClick = viewModel::saveTopSongs, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    if (state.saving) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text(stringResource(R.string.review_save_top))
                    }
                }
            } else {
                OutlinedButton(onClick = { navigator.toPlaylist(saved.playlistId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_open_saved))
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

@Composable
private fun Kicker(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
}

@Composable
private fun Big(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A number one shown large, then the runners-up. */
@Composable
private fun Ranked(
    kicker: String,
    hero: Triple<String?, String, String>,
    rows: List<Pair<String, String>>,
    onHero: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Kicker(kicker)
        val (cover, name, detail) = hero
        Artwork(
            cover,
            ArtworkSize.LARGE,
            contentDescription = null,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .padding(vertical = 16.dp)
                .then(if (onHero != null) Modifier.clickable(onClick = onHero) else Modifier),
            shape = MaterialTheme.shapes.large,
        )
        Title(name)
        Body(detail)
        Spacer(Modifier.height(20.dp))
        rows.forEachIndexed { i, (rowName, rowDetail) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 2}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp),
                )
                Text(rowName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    rowDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).widthIn(max = 140.dp),
                )
            }
        }
    }
}
