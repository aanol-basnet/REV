package app.rev.wear

import app.rev.wearprotocol.TrackHeader
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLibraryTest {
    private val root: File = Files.createTempDirectory("watch").toFile()

    private fun header(trackId: String, number: Int, bytes: Int, albumId: String = "album:1") = TrackHeader(
        albumId = albumId, albumTitle = "Lateralus", artist = "Tool", trackId = trackId, title = "Track $number",
        trackNumber = number, discNumber = 1, durationMs = 1000, extension = "flac", sizeBytes = bytes.toLong(),
        index = number - 1, total = 2,
    )

    @Test
    fun `stores tracks and indexes them in order`() {
        val library = WatchLibrary(root)
        library.receive(header("t2", 2, 3), ByteArray(3).inputStream())
        library.receive(header("t1", 1, 4), ByteArray(4).inputStream())

        val album = library.albums.value.single()
        assertEquals("Lateralus", album.title)
        assertEquals(listOf("t1", "t2"), album.sortedTracks.map { it.trackId })
        assertEquals(4, library.fileOf(album, album.sortedTracks[0]).length())
        // A fresh instance reads the same index from disk.
        assertEquals(album, WatchLibrary(root).albums.value.single())
    }

    @Test
    fun `receiving the same track again replaces it`() {
        val library = WatchLibrary(root)
        library.receive(header("t1", 1, 2), ByteArray(2).inputStream())
        library.receive(header("t1", 1, 5), ByteArray(5).inputStream())
        val album = library.albums.value.single()
        assertEquals(1, album.tracks.size)
        assertEquals(5, library.fileOf(album, album.tracks[0]).length())
    }

    @Test
    fun `a truncated transfer is discarded`() {
        val library = WatchLibrary(root)
        val result = runCatching { library.receive(header("t1", 1, 10), ByteArray(4).inputStream()) }
        assertTrue(result.isFailure)
        assertTrue(library.albums.value.isEmpty())
        assertTrue(root.walk().none { it.isFile })
    }

    @Test
    fun `ids can't escape the library folder`() {
        val library = WatchLibrary(root)
        library.receive(header("../../evil", 1, 1, albumId = "../x"), ByteArray(1).inputStream())
        assertTrue(root.walk().filter { it.isFile }.all { it.canonicalPath.startsWith(root.canonicalPath) })
        library.delete("../x")
        assertTrue(library.albums.value.isEmpty())
    }
}
