package app.rev.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.rev.domain.playback.TrackSource
import app.rev.domain.playback.TrackSourceResolver
import java.io.File
import java.io.IOException
import okhttp3.Call

/** Thrown when a track is neither downloaded nor streamable (e.g. offline). */
class TrackUnavailableException(trackId: String) : IOException("Track $trackId is not available offline")

/**
 * One data source for both local and remote playback. `rev:track/<id>` URIs are resolved per load:
 * downloaded file → `file://`, otherwise the authenticated server stream. Everything else passes
 * through unchanged.
 */
@OptIn(UnstableApi::class)
internal fun revDataSourceFactory(
    context: Context,
    callFactory: Call.Factory,
    resolver: TrackSourceResolver,
): DataSource.Factory {
    val http = OkHttpDataSource.Factory(callFactory)
    val upstream = DefaultDataSource.Factory(context, http)
    return ResolvingDataSource.Factory(upstream) { dataSpec ->
        val trackId = RevUris.trackId(dataSpec.uri) ?: return@Factory dataSpec
        when (val source = resolver.resolve(trackId)) {
            is TrackSource.Local -> dataSpec.withUri(Uri.fromFile(File(source.path)))
            is TrackSource.Remote -> dataSpec.withUri(source.url.toUri())
            null -> throw TrackUnavailableException(trackId)
        }
    }
}
