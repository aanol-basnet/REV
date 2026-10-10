package app.rev.feature.player

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.rev.core.ui.icons.RevIcons

@Composable
internal fun PlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.onBackground,
            contentColor = MaterialTheme.colorScheme.background,
        ),
    ) {
        if (isBuffering && !isPlaying) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.4f),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.background,
            )
        } else {
            Icon(
                if (isPlaying) RevIcons.Pause else RevIcons.Play,
                contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}
