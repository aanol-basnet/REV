package app.rev.wearprotocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WearProtocolTest {
    @Test
    fun `track header and audio share one stream`() {
        val header = TrackHeader("al", "Lateralus", "Tool", "t1", "Schism", 6, 1, 403_000, "flac", 3, 0, 13)
        val out = ByteArrayOutputStream()
        writeHeader(out, header)
        out.write(byteArrayOf(1, 2, 3))

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(header, readHeader(input))
        assertArrayEquals(byteArrayOf(1, 2, 3), input.readBytes())
    }

    @Test
    fun `album lists round-trip`() {
        val albums = listOf(PhoneAlbum("album:al", "Lateralus", "Tool", 13, 500_000_000))
        assertEquals(albums, decodeAlbums(encodeAlbums(albums)))
    }
}
