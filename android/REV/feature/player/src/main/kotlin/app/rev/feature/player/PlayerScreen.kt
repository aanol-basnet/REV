package app.rev.feature.player

import app.rev.feature.library.AddToPlaylistHost
import app.rev.feature.library.rememberAddToPlaylist

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.format.formatTrackDuration
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.followSwipe
import app.rev.core.ui.components.rememberSwipeState
import app.rev.core.ui.components.swipeGestures
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.FavoriteButton
import androidx.media3.cast.MediaRouteButton
import androidx.media3.cast.rememberMediaRouteButtonState
import app.rev.core.ui.components.ModePicker
import app.rev.core.ui.icons.RevIcons
import app.rev.core.ui.theme.artworkGradient
import app.rev.core.ui.theme.rememberArtworkAccent
import app.rev.domain.model.Track
import app.rev.playback.PlayerState
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.rev.domain.playback.RepeatMode
import app.rev.playback.PlaybackProgress

@Composable
internal fun PlayerScreen(navigator: PlayerNavigator, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val favorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    QueueNoteSnackbarEffect(state, snackbar)
    val addToPlaylist = rememberAddToPlaylist()
    AddToPlaylistHost(addToPlaylist, snackbar)
    val track = state.current
    // The cover tints the screen, softly; the controls stay neutral.
    val accent = rememberArtworkAccent(track?.coverArtId)
    // Swipe down anywhere to close; swipe the artwork left/right to change track.
    val screenSwipe = rememberSwipeState()
    val artworkSwipe = rememberSwipeState()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .artworkGradient(accent, MaterialTheme.colorScheme.background)
            .swipeGestures(state = screenSwipe, onSwipeDown = navigator.back)
            .followSwipe(screenSwipe, damping = 0.6f)
            .safeDrawingPadding(),
    ) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            TopBar(navigator)
            ModePicker(viewModel.mode, Modifier.align(Alignment.CenterHorizontally))
            if (track == null) {
                CenteredMessage(stringResource(R.string.player_nothing))
                return@Column
            }
            val artwork = @Composable { modifier: Modifier ->
                Artwork(
                    track.coverArtId,
                    ArtworkSize.LARGE,
                    contentDescription = null,
                    modifier = modifier
                        .swipeGestures(
                            state = artworkSwipe,
                            onSwipeLeft = if (state.hasNext) viewModel::next else null,
                            onSwipeRight = if (state.hasPrevious) viewModel::previous else null,
                        )
                        .followSwipe(artworkSwipe),
                    shape = MaterialTheme.shapes.medium,
                )
            }
            val details = @Composable {
                TrackInfo(track, favorite, navigator, viewModel::setFavorite, onAddToPlaylist = { addToPlaylist.open(listOf(track.id)) })
                Spacer(Modifier.height(16.dp))
                SeekBar(progress, onSeek = viewModel::seekTo)
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Transport(state, viewModel)
                BottomActions(onNotNow = viewModel::notNow, onLyrics = navigator.openLyrics, onMoreLikeThis = viewModel::moreLikeThis)
            }
            if (landscape) {
                // Wide screens: artwork beside the controls instead of above them.
                Row(Modifier.weight(1f).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        artwork(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) { details() }
                }
            } else {
                Spacer(Modifier.weight(0.5f))
                artwork(Modifier.widthIn(max = 420.dp).align(Alignment.CenterHorizontally))
                Spacer(Modifier.weight(0.5f))
                details()
                Spacer(Modifier.weight(0.3f))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TopBar(navigator: PlayerNavigator) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = navigator.back) {
            Icon(RevIcons.Collapse, contentDescription = stringResource(R.string.player_collapse))
        }
        Text(
            stringResource(R.string.player_now_playing).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
            textAlign = TextAlign.Center,
        )
        // Cast to a Chromecast / Google speaker on the home network.
        MediaRouteButton(Modifier.size(48.dp), rememberMediaRouteButtonState())
        IconButton(onClick = navigator.openQueue) {
            Icon(RevIcons.Queue, contentDescription = stringResource(R.string.player_open_queue))
        }
    }
}

@Composable
private fun TrackInfo(
    track: Track,
    favorite: Boolean,
    navigator: PlayerNavigator,
    onFavoriteChange: (Boolean) -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                track.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    enabled = track.artistId != null,
                    onClickLabel = stringResource(R.string.player_go_to_artist),
                ) { track.artistId?.let(navigator.toArtist) },
            )
            Text(
                track.album,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    enabled = track.albumId != null,
                    onClickLabel = stringResource(R.string.player_go_to_album),
                ) { track.albumId?.let(navigator.toAlbum) },
            )
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(
                RevIcons.PlaylistAdd,
                contentDescription = stringResource(R.string.player_add_to_playlist),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FavoriteButton(favorite, onFavoriteChange)
    }
}

/** Feedback that teaches REV your taste, either side of the lyrics. */
@Composable
private fun BottomActions(onNotNow: () -> Unit, onLyrics: () -> Unit, onMoreLikeThis: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onNotNow) {
            Icon(RevIcons.ThumbDown, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.player_not_now), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onLyrics) {
            Text(stringResource(R.string.player_lyrics).uppercase(), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onMoreLikeThis) {
            Icon(RevIcons.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.player_more_like_this), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Transport(state: PlayerState, viewModel: PlayerViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::toggleShuffle) {
            Icon(
                RevIcons.Shuffle,
                contentDescription = stringResource(if (state.shuffle) R.string.player_shuffle_on else R.string.player_shuffle_off),
                tint = toggleTint(state.shuffle),
            )
        }
        IconButton(onClick = viewModel::previous, enabled = state.hasPrevious, modifier = Modifier.size(56.dp)) {
            Icon(RevIcons.SkipPrevious, contentDescription = stringResource(R.string.player_previous), modifier = Modifier.size(36.dp))
        }
        PlayPauseButton(state.isPlaying, state.isBuffering, viewModel::togglePlayPause, size = 72.dp)
        IconButton(onClick = viewModel::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
            Icon(RevIcons.SkipNext, contentDescription = stringResource(R.string.player_next), modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = viewModel::cycleRepeat) {
            Icon(
                if (state.repeat == RepeatMode.One) RevIcons.RepeatOne else RevIcons.Repeat,
                contentDescription = stringResource(
                    when (state.repeat) {
                        RepeatMode.Off -> R.string.player_repeat_off
                        RepeatMode.All -> R.string.player_repeat_all
                        RepeatMode.One -> R.string.player_repeat_one
                    },
                ),
                tint = toggleTint(state.repeat != RepeatMode.Off),
            )
        }
    }
}

@Composable
private fun toggleTint(active: Boolean) =
    if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

/** Follows playback, except while the user drags; seeks once on release. */
@Composable
private fun SeekBar(progress: PlaybackProgress, onSeek: (Long) -> Unit) {
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val duration = progress.durationMs.coerceAtLeast(1)
    val shown = dragPosition ?: (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column {
        Slider(
            value = shown,
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { onSeek((it * duration).toLong()) }
                dragPosition = null
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onBackground,
                activeTrackColor = MaterialTheme.colorScheme.onBackground,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTrackDuration((shown * duration).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatTrackDuration(progress.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
