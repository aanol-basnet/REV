package app.rev.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import app.rev.domain.model.Track
import app.rev.domain.playback.QueueSnapshot
import app.rev.domain.playback.QueueStore
import app.rev.domain.playback.RepeatMode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = mutableListOf<ExoPlayer>()

    @After
    fun release() = players.forEach { it.release() }

    private fun player() = ExoPlayer.Builder(context).build().also { players += it }

    private fun track(id: String) = Track(
        id, "Title $id", "Lateralus", "al-1", "Tool", "ar-1", 3, 1, 2001, "Metal", "cov-$id",
        200_000, false, "flac", 1024, 44_100, 16, 1_000,
    )

    private class MemoryStore : QueueStore {
        var saved: QueueSnapshot? = null
        override suspend fun save(snapshot: QueueSnapshot) { saved = snapshot }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }

    @Test
    fun `tracks survive the trip through a media item`() {
        val original = track("tr-1").copy(starred = true, bitDepth = null)
        val item = original.toMediaItem("http://server/art.jpg")
        assertEquals(original, item.toTrack())
        assertEquals("tr-1", RevUris.trackId(item.localConfiguration!!.uri))
        assertEquals("http://server/art.jpg", item.mediaMetadata.artworkUri.toString())
    }

    @Test
    fun `system surfaces get artwork from REV's own provider, not the server`() {
        // Android Auto loads the cover itself and can't authenticate against the server.
        assertEquals("content://app.rev.artwork/cov-1", track("tr-1").copy(coverArtId = "cov-1").systemArtworkUri())
        assertNull(track("tr-2").copy(coverArtId = null).systemArtworkUri())
    }

    @Test
    fun `rev uris resolve only for track items`() {
        assertEquals("al-1_x.y", RevUris.trackId(RevUris.track("al-1_x.y")))
        assertNull(RevUris.trackId(android.net.Uri.parse("https://server/track/1")))
    }

    @Test
    fun `queue position and modes persist and restore`() = runTest {
        val store = MemoryStore()
        val source = player().apply {
            setMediaItems(listOf(track("a"), track("b"), track("c")).map { it.toMediaItem(null) }, 1, 12_345)
            shuffleModeEnabled = true
            repeatMode = Player.REPEAT_MODE_ALL
        }
        QueuePersister(source, store, TestScope()) { null }.saveNow()
        assertEquals(listOf("a", "b", "c"), store.saved?.tracks?.map { it.id })
        assertEquals(RepeatMode.All, store.saved?.repeat)

        val restored = player()
        QueuePersister(restored, store, TestScope()) { "art/${it.coverArtId}" }.restoreInto(restored, playWhenReady = false)
        assertEquals(3, restored.mediaItemCount)
        assertEquals(1, restored.currentMediaItemIndex)
        assertEquals(12_345, restored.currentPosition)
        assertEquals(true, restored.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, restored.repeatMode)
        assertEquals(false, restored.playWhenReady)
        assertEquals("art/cov-b", restored.currentMediaItem?.mediaMetadata?.artworkUri.toString())
    }

    @Test
    fun `restore never overwrites a queue a controller already set`() = runTest {
        val store = MemoryStore().apply {
            saved = QueueSnapshot(listOf(track("old")), 0, 0, false, RepeatMode.Off)
        }
        val p = player().apply { setMediaItem(track("new").toMediaItem(null)) }
        QueuePersister(p, store, TestScope()) { null }.restoreInto(p, playWhenReady = false)
        assertEquals("new", p.currentMediaItem?.mediaId)
    }

    @Test
    fun `an emptied queue clears what was saved`() = runTest {
        val store = MemoryStore().apply {
            saved = QueueSnapshot(listOf(track("old")), 0, 0, false, RepeatMode.Off)
        }
        QueuePersister(player(), store, TestScope()) { null }.saveNow()
        assertNull(store.saved)
    }

    @Test
    fun `playback order follows the shuffle order`() {
        val p = player().apply {
            setMediaItems((1..5).map { track("t$it").toMediaItem(null) })
        }
        assertEquals(listOf(0, 1, 2, 3, 4), playbackOrder(p.currentTimeline, shuffle = false))
        val shuffled = playbackOrder(p.currentTimeline, shuffle = true)
        assertEquals((0..4).toSet(), shuffled.toSet())
        assertEquals(5, shuffled.size)
    }
}
