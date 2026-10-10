package app.rev.core.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rev.core.R
import app.rev.domain.model.AlbumAvailability

/** Short label for an album's availability, in the user's terms (no Lidarr/slskd vocabulary). */
@Composable
fun AlbumAvailability.label(): String = stringResource(
    when (this) {
        AlbumAvailability.AVAILABLE -> R.string.availability_available
        AlbumAvailability.IN_LIBRARY -> R.string.availability_in_library
        AlbumAvailability.MISSING -> R.string.availability_missing
        AlbumAvailability.REQUESTED -> R.string.availability_requested
        AlbumAvailability.DOWNLOADING -> R.string.availability_downloading
        AlbumAvailability.IMPORTING -> R.string.availability_importing
        AlbumAvailability.INDEXING -> R.string.availability_indexing
        AlbumAvailability.FAILED -> R.string.availability_failed
    },
)

@Composable
fun AvailabilityBadge(availability: AlbumAvailability, modifier: Modifier = Modifier) {
    val (container, content) = when (availability) {
        AlbumAvailability.AVAILABLE ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
        AlbumAvailability.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f) to MaterialTheme.colorScheme.error
        AlbumAvailability.MISSING -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraSmall,
        border = if (availability == AlbumAvailability.MISSING) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        } else {
            null
        },
        modifier = modifier,
    ) {
        Text(
            availability.label().uppercase(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
