package app.rev.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage

/**
 * Identifies artwork on the REV server. The app's image loader maps this to an authenticated
 * artwork URL for the configured server, so UI code never builds URLs or handles tokens.
 */
data class CoverArt(val id: String, val size: Int)

/** Cover of a catalog album the user doesn't own yet (MusicBrainz release group), via the REV server. */
data class CatalogCover(val releaseGroupId: String)

object ArtworkSize {
    const val THUMBNAIL = 160
    const val CARD = 400
    const val LARGE = 1000
}

/** Square artwork with a neutral placeholder while loading, when missing, or when unavailable offline. */
@Composable
fun Artwork(
    coverArtId: String?,
    size: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) = ArtworkImage(coverArtId?.let { CoverArt(it, size) }, contentDescription, modifier, shape)

/** Square artwork for any supported image model ([CoverArt], [CatalogCover]); null shows the placeholder. */
@Composable
fun ArtworkImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    val boxModifier = modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    if (model == null) {
        Box(boxModifier)
        return
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = boxModifier,
        loading = { Box(Modifier.fillMaxSize()) },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {} },
    )
}
