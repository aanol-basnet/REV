package app.rev.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rev.core.R
import app.rev.core.ui.icons.RevIcons
import app.rev.domain.model.Mix
import app.rev.domain.model.MixKind
import app.rev.domain.model.TimeOfDay

/** A mix: a collage of what's in it, its name and who features. Tapping opens it; the play button plays it. */
@Composable
fun MixCard(mix: Mix, starting: Boolean, onClick: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val title = mixTitle(mix)
    CollageCard(
        coverArtIds = mix.coverArtIds,
        label = mixLabel(mix),
        title = title,
        subtitle = mix.featuring.takeIf { it.isNotEmpty() }?.let {
            stringResource(R.string.mix_featuring, it.joinToString(", "))
        },
        playDescription = stringResource(R.string.mix_play, title),
        starting = starting,
        onClick = onClick,
        modifier = modifier,
        onPlay = onPlay,
        badge = if (mix.isOffline) {
            { OfflineBadge() }
        } else {
            null
        },
    )
}

/**
 * A generated track list as a card: a collage of its covers with [label] printed on it and a play
 * button, then [title] and [subtitle]. Tapping the card calls [onClick]; the play button calls
 * [onPlay], or [onClick] when there is none.
 */
@Composable
fun CollageCard(
    coverArtIds: List<String>,
    label: String,
    title: String,
    subtitle: String?,
    playDescription: String,
    starting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .width(156.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium),
        ) {
            CoverCollage(coverArtIds)
            // Darken the bottom so the label reads on any artwork.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))),
            )
            if (badge != null) {
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { badge() }
            }
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, end = 52.dp, bottom = 10.dp),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .then(if (onPlay != null) Modifier.clip(CircleShape).clickable(onClick = onPlay) else Modifier),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (starting) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            RevIcons.Play,
                            contentDescription = playDescription,
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            subtitle.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OfflineBadge() {
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RevIcons.Downloaded, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.mix_offline), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

/** One cover, or a 2×2 grid of four. */
@Composable
fun CoverCollage(coverArtIds: List<String>) {
    if (coverArtIds.size < 4) {
        Artwork(coverArtIds.firstOrNull(), ArtworkSize.CARD, null, shape = RectangleShape)
        return
    }
    Column {
        coverArtIds.take(4).chunked(2).forEach { row ->
            Row {
                row.forEach { id ->
                    Box(Modifier.weight(1f)) {
                        Artwork(id, ArtworkSize.THUMBNAIL, null, shape = RectangleShape)
                    }
                }
            }
        }
    }
}

@Composable
fun mixTitle(mix: Mix): String = when (mix.kind) {
    MixKind.Artist -> stringResource(R.string.mix_artist, mix.name.orEmpty())
    MixKind.Genre -> stringResource(R.string.mix_genre, mix.name.orEmpty())
    MixKind.Favorites -> stringResource(R.string.mix_favorites)
    MixKind.Discovery -> stringResource(R.string.mix_discovery)
    MixKind.Downloads -> stringResource(R.string.mix_downloads)
    MixKind.Time -> stringResource(
        when (TimeOfDay.fromWire(mix.name)) {
            TimeOfDay.Morning -> R.string.mix_time_morning
            TimeOfDay.Afternoon -> R.string.mix_time_afternoon
            TimeOfDay.Evening -> R.string.mix_time_evening
            else -> R.string.mix_time_night
        },
    )
}

/** The short label printed on the card itself. */
@Composable
private fun mixLabel(mix: Mix): String = when (mix.kind) {
    MixKind.Artist, MixKind.Genre -> mix.name.orEmpty()
    MixKind.Favorites -> stringResource(R.string.mix_favorites_short)
    MixKind.Discovery -> stringResource(R.string.mix_discovery_short)
    MixKind.Downloads -> stringResource(R.string.mix_downloads_short)
    MixKind.Time -> stringResource(
        when (TimeOfDay.fromWire(mix.name)) {
            TimeOfDay.Morning -> R.string.mix_time_morning_short
            TimeOfDay.Afternoon -> R.string.mix_time_afternoon_short
            TimeOfDay.Evening -> R.string.mix_time_evening_short
            else -> R.string.mix_time_night_short
        },
    )
}
