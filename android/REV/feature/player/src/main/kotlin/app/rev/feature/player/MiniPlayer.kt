package app.rev.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.Glass
import app.rev.core.ui.components.followSwipe
import app.rev.core.ui.components.rememberSwipeState
import app.rev.core.ui.components.swipeGestures
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import app.rev.core.ui.icons.RevIcons
import app.rev.core.ui.theme.rememberArtworkAccent
import app.rev.domain.model.Track
import app.rev.playback.PlaybackProgress

/**
 * Persistent strip above the bottom navigation while something is queued. Tap or swipe up to
 * expand; swipe left for the next track, right for the previous one.
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val current = state.current
    // Keep the last track around while the bar animates away.
    var shown by remember { mutableStateOf(current) }
    if (current != null) shown = current
    val accent = rememberArtworkAccent(shown?.coverArtId)

    AnimatedVisibility(
        visible = current != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val track = shown ?: return@AnimatedVisibility
        MiniPlayerBar(track, state.isPlaying, state.hasNext, state.hasPrevious, progress, accent, onExpand, viewModel, shape, modifier)
    }
}

@Composable
private fun MiniPlayerBar(
    track: Track,
    isPlaying: Boolean,
    hasNext: Boolean,
    hasPrevious: Boolean,
    progress: PlaybackProgress,
    accent: Color,
    onExpand: () -> Unit,
    viewModel: PlayerViewModel,
    shape: Shape,
    modifier: Modifier,
) {
    val swipe = rememberSwipeState()
    Surface(
        color = Glass.container(accent),
        border = Glass.hairline,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .swipeGestures(
                state = swipe,
                onSwipeLeft = if (hasNext) viewModel::next else null,
                onSwipeRight = if (hasPrevious) viewModel::previous else null,
                onSwipeUp = onExpand,
            )
            .clickable(onClickLabel = stringResource(R.string.player_open), onClick = onExpand),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { if (progress.durationMs > 0) progress.positionMs.toFloat() / progress.durationMs else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row(
                // The track slides a little with the finger; the bar itself stays put.
                modifier = Modifier
                    .followSwipe(swipe)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = viewModel::togglePlayPause) {
                    Icon(
                        if (isPlaying) RevIcons.Pause else RevIcons.Play,
                        contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                    )
                }
                IconButton(onClick = viewModel::next, enabled = hasNext) {
                    Icon(RevIcons.SkipNext, contentDescription = stringResource(R.string.player_next))
                }
            }
        }
    }
}
