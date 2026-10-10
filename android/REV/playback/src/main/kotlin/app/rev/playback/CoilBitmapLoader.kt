package app.rev.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import app.rev.domain.playback.ArtworkUrls
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

/**
 * Loads notification / lock-screen artwork through the app's image loader, which authenticates
 * against the REV server and serves cached covers offline. Media3's default loader can't do either.
 */
@OptIn(UnstableApi::class)
internal class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val artworkUrls: ArtworkUrls,
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("Undecodable artwork")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        // Our own artwork provider: go straight to the server image rather than through it.
        val data = if (uri.authority == ArtworkProvider.AUTHORITY) {
            uri.lastPathSegment?.let { artworkUrls.url(it, SYSTEM_ARTWORK_SIZE) } ?: uri.toString()
        } else {
            uri.toString()
        }
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(SYSTEM_ARTWORK_SIZE)
            .allowHardware(false) // Bitmaps cross into system UI.
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        (result as? SuccessResult)?.image?.toBitmap() ?: throw IOException("Artwork unavailable")
    }
}
