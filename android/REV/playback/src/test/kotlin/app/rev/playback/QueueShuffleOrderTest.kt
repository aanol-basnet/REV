package app.rev.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueShuffleOrderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = mutableListOf<ExoPlayer>()

    @After
    fun release() = players.forEach { it.release() }

    /** A shuffled player holding songs s0…s(n-1), as the playback service sets it up. */
    private fun shuffledPlayer(count: Int): ExoPlayer = ExoPlayer.Builder(context).build().also { player ->
        players += player
        player.setShuffleOrder(QueueShuffleOrder(Random(7)))
        player.shuffleModeEnabled = true
        player.setMediaItems(items(0 until count), true)
    }

    private fun items(ids: Iterable<Int>, prefix: String = "s") = ids.map { MediaItem.Builder().setMediaId("$prefix$it").setUri("rev:track/$prefix$it").build() }

    private fun Player.playOrder(): List<String> =
        playbackOrder(currentTimeline, shuffleModeEnabled).map { getMediaItemAt(it).mediaId }

    private fun Player.upNext(): List<String> = playOrder().let { it.subList(it.indexOf(currentMediaItem!!.mediaId) + 1, it.size) }

    @Test
    fun `a shuffled queue starts at the first song of the play order, so every song plays`() {
        val player = shuffledPlayer(20)
        assertEquals(player.playOrder().first(), player.currentMediaItem!!.mediaId)
        assertNotEquals((0 until 20).map { "s$it" }, player.playOrder())
    }

    @Test
    fun `play next plays right after the current song, in the given order`() {
        val player = shuffledPlayer(10)
        player.seekTo(player.currentTimeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, true), 0)
        val before = player.upNext()
        player.addMediaItems(player.currentMediaItemIndex + 1, items(0..2, prefix = "n"))
        assertEquals(listOf("n0", "n1", "n2") + before, player.upNext())
    }

    @Test
    fun `adding to the queue plays after everything else`() {
        val player = shuffledPlayer(10)
        val before = player.playOrder()
        player.addMediaItems(player.endOfPlayOrder(), items(0..1, prefix = "a"))
        assertEquals(before + listOf("a0", "a1"), player.playOrder())
    }

    @Test
    fun `dragging in the shuffled queue moves the song in play order`() {
        val player = shuffledPlayer(8)
        for ((from, to) in listOf(1 to 5, 6 to 0, 3 to 7, 7 to 2, 0 to 1)) {
            val expected = player.playOrder().toMutableList().apply { add(to, removeAt(from)) }
            val (index, newIndex) = queueMove(playbackOrder(player.currentTimeline, true), from, to)!!
            player.moveMediaItem(index, newIndex)
            assertEquals("move $from → $to", expected, player.playOrder())
        }
    }

    @Test
    fun `dragging without shuffle moves the song in the queue`() {
        val player = shuffledPlayer(6).apply { shuffleModeEnabled = false }
        val (index, newIndex) = queueMove(playbackOrder(player.currentTimeline, false), 4, 1)!!
        player.moveMediaItem(index, newIndex)
        assertEquals(listOf("s0", "s4", "s1", "s2", "s3", "s5"), player.playOrder())
        assertNull(queueMove(listOf(0, 1, 2), 1, 1))
    }

    @Test
    fun `removing songs keeps the rest of the play order`() {
        val player = shuffledPlayer(8)
        val before = player.playOrder()
        player.removeMediaItems(2, 5)
        assertEquals(before - setOf("s2", "s3", "s4"), player.playOrder())
    }

    @Test
    fun `shuffling from mid-queue starts with the current song`() {
        val order = QueueShuffleOrder.startingWith(3, 6, Random(1))
        assertEquals(3, order.firstIndex)
        val seen = generateSequence(order.firstIndex) { order.getNextIndex(it).takeIf { next -> next >= 0 } }.toList()
        assertEquals((0 until 6).toSet(), seen.toSet())
        assertEquals(6, seen.size)
    }
}
