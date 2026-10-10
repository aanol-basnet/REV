package app.rev.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.rev.domain.playback.ArtworkUrls
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

/**
 * Serves album artwork as `content://app.rev.artwork/<coverArtId>` for system surfaces that load
 * images themselves (Android Auto browse lists), which can't send the REV session token. Images
 * come through the app's authenticated, disk-cached image loader, so they also work offline.
 * Read-only, artwork ids only.
 */
class ArtworkProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun artworkUrls(): ArtworkUrls
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val id = uri.lastPathSegment?.takeIf { ID.matches(it) } ?: throw FileNotFoundException("bad artwork id")
        val context = context ?: throw FileNotFoundException("no context")
        val cached = File(context.cacheDir, "auto-artwork/$id.jpg")
        if (!cached.isFile) {
            val url = EntryPointAccessors.fromApplication(context, Deps::class.java).artworkUrls().url(id, SIZE)
                ?: throw FileNotFoundException("no server")
            val bitmap = runBlocking {
                val request = ImageRequest.Builder(context).data(url).size(SIZE).allowHardware(false).build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            } ?: throw FileNotFoundException("artwork unavailable")
            cached.parentFile?.mkdirs()
            cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "app.rev.artwork"
        private const val SIZE = 512
        private val ID = Regex("[A-Za-z0-9._-]{1,100}")

        fun uriFor(coverArtId: String): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(coverArtId).build()
    }
}
