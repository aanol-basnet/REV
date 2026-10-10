package app.rev.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.rev.domain.model.ReplayGain
import app.rev.domain.model.Track
import app.rev.domain.playback.QueueSource

/**
 * Queue items carry a `rev:track/<id>` URI rather than a concrete URL. The actual source (local
 * file or server stream) is chosen by [RevDataSourceFactory] when the track starts loading.
 */
internal object RevUris {
    private const val SCHEME = "rev"
    private const val TRACK_PREFIX = "track/"

    fun track(trackId: String): Uri = Uri.Builder().scheme(SCHEME).opaquePart(TRACK_PREFIX + trackId).build()

    fun trackId(uri: Uri): String? =
        if (uri.scheme == SCHEME) uri.schemeSpecificPart?.removePrefix(TRACK_PREFIX)?.takeIf { it.isNotEmpty() } else null
}

private object Extras {
    const val ALBUM_ID = "rev.albumId"
    const val ARTIST_ID = "rev.artistId"
    const val COVER_ART_ID = "rev.coverArtId"
    const val DURATION_MS = "rev.durationMs"
    const val STARRED = "rev.starred"
    const val FORMAT = "rev.format"
    const val BIT_RATE = "rev.bitRateKbps"
    const val SAMPLE_RATE = "rev.sampleRateHz"
    const val BIT_DEPTH = "rev.bitDepth"
    const val SIZE = "rev.sizeBytes"
    const val GENRE = "rev.genre"
    const val RG_TRACK = "rev.rg.trackGain"
    const val RG_ALBUM = "rev.rg.albumGain"
    const val RG_TRACK_PEAK = "rev.rg.trackPeak"
    const val RG_ALBUM_PEAK = "rev.rg.albumPeak"
    const val SOURCE = "rev.source"
    const val SMART_CHANGE = "rev.smartChange"
}

/** Artwork size requested for system surfaces (notification, lock screen, car displays). */
internal const val SYSTEM_ARTWORK_SIZE = 600

/**
 * Artwork address for system surfaces: REV's own `content://` provider. Android Auto (and other
 * controllers in other apps) load the now-playing cover themselves and can't send REV's session
 * token, so a server URL would show no cover in the car.
 */
internal fun Track.systemArtworkUri(): String? = coverArtId?.let { ArtworkProvider.uriFor(it).toString() }

/**
 * Builds a queue item. Everything needed to show and persist the track travels in its metadata,
 * including how it got into the queue ([source], and [smartChange] for the smart queue's songs).
 */
fun Track.toMediaItem(artworkUrl: String?, source: QueueSource = QueueSource.Unknown, smartChange: String? = null): MediaItem {
    val extras = Bundle().apply {
        putString(Extras.SOURCE, source.wire)
        smartChange?.let { putString(Extras.SMART_CHANGE, it) }
        albumId?.let { putString(Extras.ALBUM_ID, it) }
        artistId?.let { putString(Extras.ARTIST_ID, it) }
        coverArtId?.let { putString(Extras.COVER_ART_ID, it) }
        putLong(Extras.DURATION_MS, durationMs)
        putBoolean(Extras.STARRED, starred)
        format?.let { putString(Extras.FORMAT, it) }
        bitRateKbps?.let { putInt(Extras.BIT_RATE, it) }
        sampleRateHz?.let { putInt(Extras.SAMPLE_RATE, it) }
        bitDepth?.let { putInt(Extras.BIT_DEPTH, it) }
        sizeBytes?.let { putLong(Extras.SIZE, it) }
        genre?.let { putString(Extras.GENRE, it) }
        replayGain?.let { rg ->
            rg.trackGainDb?.let { putDouble(Extras.RG_TRACK, it) }
            rg.albumGainDb?.let { putDouble(Extras.RG_ALBUM, it) }
            rg.trackPeak?.let { putDouble(Extras.RG_TRACK_PEAK, it) }
            rg.albumPeak?.let { putDouble(Extras.RG_ALBUM_PEAK, it) }
        }
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setTrackNumber(trackNumber)
        .setDiscNumber(discNumber)
        .setReleaseYear(year)
        .setGenre(genre)
        .setDurationMs(durationMs.takeIf { it > 0 })
        .setArtworkUri(artworkUrl?.toUri())
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(RevUris.track(id))
        .setMediaMetadata(metadata)
        .build()
}

/** Restores the [Track] a queue item was built from. */
fun MediaItem.toTrack(): Track {
    val m = mediaMetadata
    val extras = m.extras ?: Bundle.EMPTY
    fun intOrNull(key: String) = if (extras.containsKey(key)) extras.getInt(key) else null
    fun doubleOrNull(key: String) = if (extras.containsKey(key)) extras.getDouble(key) else null
    val trackGain = doubleOrNull(Extras.RG_TRACK)
    val albumGain = doubleOrNull(Extras.RG_ALBUM)
    return Track(
        id = mediaId,
        title = m.title?.toString().orEmpty(),
        album = m.albumTitle?.toString().orEmpty(),
        albumId = extras.getString(Extras.ALBUM_ID),
        artist = m.artist?.toString().orEmpty(),
        artistId = extras.getString(Extras.ARTIST_ID),
        trackNumber = m.trackNumber,
        discNumber = m.discNumber,
        year = m.releaseYear,
        genre = extras.getString(Extras.GENRE),
        coverArtId = extras.getString(Extras.COVER_ART_ID),
        durationMs = extras.getLong(Extras.DURATION_MS),
        starred = extras.getBoolean(Extras.STARRED),
        format = extras.getString(Extras.FORMAT),
        bitRateKbps = intOrNull(Extras.BIT_RATE),
        sampleRateHz = intOrNull(Extras.SAMPLE_RATE),
        bitDepth = intOrNull(Extras.BIT_DEPTH),
        sizeBytes = if (extras.containsKey(Extras.SIZE)) extras.getLong(Extras.SIZE) else null,
        replayGain = if (trackGain == null && albumGain == null) {
            null
        } else {
            ReplayGain(trackGain, albumGain, doubleOrNull(Extras.RG_TRACK_PEAK), doubleOrNull(Extras.RG_ALBUM_PEAK))
        },
    )
}

/** How the item got into the queue, and what the smart queue did if it added it. */
internal fun MediaItem.queueSource(): Pair<QueueSource, String?> {
    val extras = mediaMetadata.extras ?: return QueueSource.Unknown to null
    val source = QueueSource.entries.firstOrNull { it.wire == extras.getString(Extras.SOURCE) } ?: QueueSource.Unknown
    return source to extras.getString(Extras.SMART_CHANGE)
}

/** Items arriving from a controller lose their URI; give them back the resolvable one. */
internal fun MediaItem.withRevUri(): MediaItem = buildUpon().setUri(RevUris.track(mediaId)).build()
