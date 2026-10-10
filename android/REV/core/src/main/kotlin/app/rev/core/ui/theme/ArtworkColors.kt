package app.rev.core.ui.theme

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import app.rev.core.ui.components.CoverArt
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val accentCache = LruCache<String, Int>(200)

/**
 * A restrained accent colour taken from an album cover: the cover's dominant hue, desaturated and
 * darkened so it tints the smoky UI rather than painting it. Null until known (or without art).
 * Animates when the artwork changes.
 */
@Composable
fun rememberArtworkAccent(coverArtId: String?): Color {
    val context = LocalContext.current
    var accent by remember(coverArtId) { mutableStateOf(coverArtId?.let { accentCache.get(it) }?.let(::Color)) }
    LaunchedEffect(coverArtId) {
        if (coverArtId == null || accent != null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(CoverArt(coverArtId, 160))
            .allowHardware(false) // Palette needs to read pixels
            .build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return@LaunchedEffect
        val color = withContext(Dispatchers.Default) { restrainedAccent(bitmap) } ?: return@LaunchedEffect
        accentCache.put(coverArtId, color)
        accent = Color(color)
    }
    val target = accent ?: Color.Transparent
    return animateColorAsState(target, animationSpec = tween(600), label = "artworkAccent").value
}

private fun restrainedAccent(bitmap: Bitmap): Int? {
    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
    val swatch = palette.darkVibrantSwatch ?: palette.vibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
    val rgb = swatch?.rgb ?: return null
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(rgb, hsl)
    hsl[1] = hsl[1].coerceAtMost(0.5f) // no neon
    hsl[2] = hsl[2].coerceIn(0.22f, 0.38f) // dark enough for white text on top
    return ColorUtils.HSLToColor(hsl)
}

/** A soft wash of [accent] fading into the background from the top; nothing when unknown. */
fun Modifier.artworkGradient(accent: Color, background: Color, reach: Float = 0.75f): Modifier =
    if (accent.alpha == 0f) {
        background(background)
    } else {
        background(
            Brush.verticalGradient(
                0f to accent.copy(alpha = 0.55f * accent.alpha).compositeOver(background),
                reach to background,
            ),
        )
    }

private fun Color.compositeOver(background: Color): Color =
    Color(ColorUtils.compositeColors(toArgb(), background.toArgb()))
