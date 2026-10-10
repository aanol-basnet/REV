package app.rev.feature.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.rev.core.R as CoreR
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.message
import app.rev.core.ui.icons.RevIcons
import app.rev.core.ui.theme.artworkGradient
import app.rev.core.ui.theme.rememberArtworkAccent
import androidx.compose.ui.semantics.Role
import app.rev.domain.model.LyricLine
import app.rev.domain.model.Lyrics
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data object LyricsRoute

fun NavGraphBuilder.lyricsScreen(onClose: () -> Unit) {
    composable<LyricsRoute> { LyricsScreen(onClose) }
}

@Composable
internal fun LyricsScreen(onClose: () -> Unit, viewModel: LyricsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentLine by viewModel.currentLine.collectAsStateWithLifecycle()

    val playing = (state as? LyricsUiState.Loaded)?.track ?: (state as? LyricsUiState.Failed)?.track
    val accent = rememberArtworkAccent(playing?.coverArtId)
    Column(
        Modifier
            .fillMaxSize()
            .artworkGradient(accent, MaterialTheme.colorScheme.background, reach = 1f)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(RevIcons.Collapse, contentDescription = stringResource(R.string.lyrics_close)) }
            val track = (state as? LyricsUiState.Loaded)?.track ?: (state as? LyricsUiState.Failed)?.track
            Column(Modifier.weight(1f)) {
                Text(track?.title ?: stringResource(R.string.lyrics_title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                track?.let {
                    Text(it.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (val s = state) {
                LyricsUiState.NothingPlaying -> CenteredMessage(stringResource(R.string.lyrics_nothing_playing))
                LyricsUiState.Loading -> LoadingView()
                is LyricsUiState.Failed -> CenteredMessage(s.error.message()) {
                    TextButton(onClick = viewModel::retry) { Text(stringResource(CoreR.string.action_retry)) }
                }
                is LyricsUiState.Loaded -> when (val lyrics = s.lyrics) {
                    is Lyrics.Synced -> SyncedLyrics(lyrics.lines, currentLine, onSeek = viewModel::seekTo)
                    is Lyrics.Plain -> PlainLyrics(lyrics.text)
                    Lyrics.Instrumental -> CenteredMessage("♪  " + stringResource(R.string.lyrics_instrumental))
                    Lyrics.None -> CenteredMessage(stringResource(R.string.lyrics_none))
                }
            }
        }
    }
}

/** How long auto-scroll waits after the user scrolls the lyrics themselves. */
private const val USER_SCROLL_PAUSE_MS = 4_000L

/**
 * §31: the current line stays centred and emphasised, neighbours are dimmed, and tapping a line
 * seeks there. Auto-scroll steps aside while the user is browsing the lyrics.
 */
@Composable
private fun SyncedLyrics(lines: List<LyricLine>, currentLine: Int, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragging) { if (dragging) lastUserScroll = System.currentTimeMillis() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportPx = with(LocalDensity.current) { maxHeight.toPx() }
        val edge = maxHeight / 2 // lets the first and last lines reach the centre

        LaunchedEffect(currentLine) {
            if (currentLine < 0) return@LaunchedEffect
            val sinceUser = System.currentTimeMillis() - lastUserScroll
            if (dragging || sinceUser < USER_SCROLL_PAUSE_MS) return@LaunchedEffect
            // Scroll so the current line sits a little above the middle.
            listState.animateScrollToItem(currentLine, scrollOffset = -(viewportPx * 0.4f).toInt())
        }
        // After the user stops browsing, settle back onto the current line.
        LaunchedEffect(lastUserScroll) {
            if (lastUserScroll == 0L) return@LaunchedEffect
            delay(USER_SCROLL_PAUSE_MS)
            if (!dragging && currentLine >= 0) {
                listState.animateScrollToItem(currentLine, scrollOffset = -(viewportPx * 0.4f).toInt())
            }
        }

        LazyColumn(state = listState, contentPadding = PaddingValues(top = edge * 0.4f, bottom = edge), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lines, key = { index, line -> "$index:${line.startMs}" }) { index, line ->
                LyricRow(line, isCurrent = index == currentLine, isPast = index < currentLine, onClick = { onSeek(line.startMs) })
            }
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, isCurrent: Boolean, isPast: Boolean, onClick: () -> Unit) {
    val base = MaterialTheme.colorScheme.onBackground
    val color by animateColorAsState(
        targetValue = when {
            isCurrent -> base
            isPast -> base.copy(alpha = 0.35f)
            else -> base.copy(alpha = 0.5f)
        },
        animationSpec = tween(durationMillis = 300),
        label = "lyric",
    )
    Text(
        text = line.text.ifEmpty { "♪" },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.lyrics_seek_hint), role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 10.dp),
    )
}

@Composable
private fun PlainLyrics(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        lineHeight = MaterialTheme.typography.titleMedium.lineHeight * 1.3f,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 16.dp),
    )
}
