package app.rev.playback.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import app.rev.domain.playback.ArtworkUrls
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The widget's cover and card colour for a track. The cover is loaded through the app's image
 * loader (so cached covers work offline), saved small to a file the widget reads, and a dark colour
 * picked from it for the card. Only the current cover's file is kept.
 */
internal class WidgetArtwork(private val context: Context, private val urls: ArtworkUrls) {
    data class Art(val path: String, val accent: Int)

    private var last: Pair<String, Art>? = null

    suspend fun forCover(coverArtId: String?): Art? {
        if (coverArtId == null) return null
        last?.let { (id, art) -> if (id == coverArtId && File(art.path).exists()) return art }
        val bitmap = load(coverArtId) ?: return null
        val art = withContext(Dispatchers.IO) {
            val dir = widgetArtworkDir(context).apply { mkdirs() }
            val file = File(dir, "cover-${coverArtId.hashCode().toUInt()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
            Art(file.path, accentOf(bitmap))
        }
        last = coverArtId to art
        return art
    }

    private suspend fun load(coverArtId: String): Bitmap? {
        val url = urls.url(coverArtId, SIZE) ?: return null
        val request = ImageRequest.Builder(context).data(url).size(SIZE).allowHardware(false).build()
        return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    /** A dark, slightly saturated colour from the cover: tints the card without fighting white text. */
    private fun accentOf(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val swatch = palette.darkVibrantSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch
        val color = swatch?.rgb?.let { Color(it) } ?: return BASE.toArgb()
        return lerp(BASE, color, 0.55f).toArgb()
    }

    private companion object {
        const val SIZE = 300
        val BASE = Color(0xFF121214)
    }
}
