package app.rev.wearprotocol

import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The phone ↔ watch protocol over the Wearable Data Layer. Shared by both apps so they can't
 * drift apart. Both apps use the application id `app.rev`, which the Data Layer requires.
 */
object WearPaths {
    /** DataItem published by the phone: what's playing there. */
    const val NOW_PLAYING = "/rev/now-playing"
    /** Message watch → phone; payload is a [Control] name. */
    const val CONTROL = "/rev/control"
    /** Message watch → phone: list albums downloaded on the phone. */
    const val ALBUMS_REQUEST = "/rev/albums/request"
    /** Message phone → watch; payload is [encodeAlbums]. */
    const val ALBUMS_LIST = "/rev/albums/list"
    /** Message watch → phone; payload is the album's collection id. */
    const val ALBUM_SEND = "/rev/albums/send"
    /** Channel phone → watch carrying one track: a header line, then the audio bytes. */
    const val TRACK_CHANNEL = "/rev/track"
}

/** Keys of the [WearPaths.NOW_PLAYING] DataMap. */
object NowPlayingKeys {
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val PLAYING = "playing"
    const val HAS_NEXT = "hasNext"
    const val ARTWORK = "artwork"
    const val UPDATED_AT = "updatedAt"
}

enum class Control { PlayPause, Next, Previous }

/** An album downloaded on the phone that can be copied to the watch. */
@Serializable
data class PhoneAlbum(
    val collectionId: String,
    val title: String,
    val artist: String?,
    val trackCount: Int,
    val sizeBytes: Long,
)

/** Precedes each track's bytes on a [WearPaths.TRACK_CHANNEL]. */
@Serializable
data class TrackHeader(
    val albumId: String,
    val albumTitle: String,
    val artist: String,
    val trackId: String,
    val title: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val extension: String,
    val sizeBytes: Long,
    /** Position of this track in the transfer, and how many there are. */
    val index: Int,
    val total: Int,
)

private val json = Json { ignoreUnknownKeys = true }

fun encodeAlbums(albums: List<PhoneAlbum>): ByteArray =
    json.encodeToString(ListSerializer(PhoneAlbum.serializer()), albums).toByteArray()

fun decodeAlbums(bytes: ByteArray): List<PhoneAlbum> =
    json.decodeFromString(ListSerializer(PhoneAlbum.serializer()), bytes.decodeToString())

/** Writes the header as one UTF-8 JSON line. */
fun writeHeader(out: OutputStream, header: TrackHeader) {
    out.write((json.encodeToString(TrackHeader.serializer(), header) + "\n").toByteArray())
}

/** Reads the header line byte by byte, leaving the stream positioned at the audio. */
fun readHeader(input: InputStream): TrackHeader {
    val line = java.io.ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        require(b >= 0) { "stream ended before the header" }
        if (b == '\n'.code) break
        line.write(b)
        require(line.size() < MAX_HEADER_BYTES) { "header too long" }
    }
    return json.decodeFromString(TrackHeader.serializer(), line.toString(Charsets.UTF_8))
}

private const val MAX_HEADER_BYTES = 16 * 1024
