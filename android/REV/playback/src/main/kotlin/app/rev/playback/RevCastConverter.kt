package app.rev.playback

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import app.rev.domain.playback.CastLinks
import com.google.android.gms.cast.MediaQueueItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts queue items for a Cast receiver: `rev:track/<id>` becomes a signed stream URL the
 * receiver can fetch from REV, with a MIME type and signed artwork. Converting back restores the
 * original item, so returning to the phone keeps full track details and local-first playback.
 */
@OptIn(UnstableApi::class)
internal class RevCastConverter(private val links: CastLinks) : MediaItemConverter {
    private val delegate = DefaultMediaItemConverter()
    private val originals = ConcurrentHashMap<String, MediaItem>()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        originals[mediaItem.mediaId] = mediaItem
        val track = mediaItem.toTrack()
        val castable = mediaItem.buildUpon()
            .setUri(links.streamUrl(mediaItem.mediaId)?.toUri() ?: mediaItem.localConfiguration?.uri)
            .setMimeType(mimeTypeFor(track.format))
            .setMediaMetadata(
                mediaItem.mediaMetadata.buildUpon()
                    .setArtworkUri(track.coverArtId?.let(links::artworkUrl)?.toUri())
                    .build(),
            )
            .build()
        return delegate.toMediaQueueItem(castable)
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val converted = delegate.toMediaItem(mediaQueueItem)
        return originals[converted.mediaId] ?: converted.withRevUri()
    }

    private fun mimeTypeFor(format: String?): String = when (format?.lowercase()) {
        "flac" -> MimeTypes.AUDIO_FLAC
        "mp3" -> MimeTypes.AUDIO_MPEG
        "m4a", "aac", "mp4", "alac" -> MimeTypes.AUDIO_MP4
        "ogg", "opus", "oga" -> MimeTypes.AUDIO_OGG
        "wav" -> MimeTypes.AUDIO_WAV
        else -> MimeTypes.AUDIO_UNKNOWN
    }
}
