package app.rev.wear

import app.rev.wearprotocol.TrackHeader
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WatchTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    /** File name inside the album's folder. */
    val file: String,
)

@Serializable
data class WatchAlbum(val id: String, val title: String, val artist: String, val tracks: List<WatchTrack>) {
    val sortedTracks: List<WatchTrack>
        get() = tracks.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title }))
}

/**
 * Music copied to the watch: one folder per album holding the audio files and an `album.json`
 * index. Plain files, so it survives app updates and needs no database.
 */
class WatchLibrary(private val root: File) {
    private val _albums = MutableStateFlow(load())
    val albums: StateFlow<List<WatchAlbum>> = _albums.asStateFlow()

    fun album(id: String): WatchAlbum? = _albums.value.firstOrNull { it.id == id }

    fun fileOf(album: WatchAlbum, track: WatchTrack): File = File(folder(album.id), track.file)

    /** Stores one incoming track. The audio lands under a temporary name until fully received. */
    @Synchronized
    fun receive(header: TrackHeader, audio: InputStream) {
        val folder = folder(header.albumId).apply { mkdirs() }
        val name = "${header.trackId.safe()}.${header.extension.safe().ifEmpty { "audio" }}"
        val partial = File(folder, "$name.part")
        partial.outputStream().use { audio.copyTo(it) }
        check(header.sizeBytes <= 0 || partial.length() == header.sizeBytes) {
            partial.delete()
            "incomplete transfer of ${header.trackId}"
        }
        val target = File(folder, name)
        target.delete()
        check(partial.renameTo(target)) { "could not store ${header.trackId}" }

        val track = WatchTrack(header.trackId, header.title, header.artist, header.trackNumber, header.discNumber, header.durationMs, name)
        val existing = readIndex(folder)
        val album = WatchAlbum(
            id = header.albumId,
            title = header.albumTitle,
            artist = existing?.artist ?: header.artist,
            tracks = (existing?.tracks.orEmpty().filterNot { it.trackId == track.trackId }) + track,
        )
        File(folder, INDEX).writeText(json.encodeToString(WatchAlbum.serializer(), album))
        _albums.value = load()
    }

    @Synchronized
    fun delete(albumId: String) {
        folder(albumId).deleteRecursively()
        _albums.value = load()
    }

    fun sizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun load(): List<WatchAlbum> =
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { readIndex(it) }
            .sortedBy { it.title.lowercase() }

    private fun readIndex(folder: File): WatchAlbum? =
        File(folder, INDEX).takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(WatchAlbum.serializer(), it.readText()) }.getOrNull() }

    private fun folder(albumId: String) = File(root, albumId.safe())

    private fun String.safe() = replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val INDEX = "album.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
