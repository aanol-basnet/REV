package app.rev.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import app.rev.domain.model.Track
import app.rev.domain.playback.AutoplayRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkipFollowerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val player = ExoPlayer.Builder(context).build()

    @After
    fun release() = player.release()

    private fun track(id: String, album: String = "al-$id") = Track(
        id, "Title $id", "Album $id", album, "Artist $id", "ar-$id", 1, 1, 2001, "Metal", null,
        200_000, false, "flac", 1024, 44_100, 16, 1_000,
    )

    private class Fake(private val songs: List<Track>) : AutoplayRepository {
        var asked: Pair<Track, List<Track>>? = null
        override suspend fun continuation(queue: List<Track>) = emptyList<Track>()
        override suspend fun songsLike(seed: Track, queue: List<Track>, skipped: List<Track>): List<Track> {
            asked = seed to skipped
            return songs
        }
    }

    private fun TestScope.follower(repo: AutoplayRepository) =
        SkipFollower(player, this, repo, enabled = { true }, toItem = { it.toMediaItem(null) }).also { it.start() }

    private fun queueIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    /** Skips ahead [times] times right away, then stays on the song reached past the settle point. */
    private fun TestScope.skipThenStay(times: Int) {
        repeat(times) { player.seekToNextMediaItem() }
        player.seekTo(player.currentMediaItemIndex, 31_000)
        advanceTimeBy(2_000)
        runCurrent()
    }

    @Test
    fun `staying on a song after a few skips replaces the rest of the queue with songs like it`() = runTest {
        val repo = Fake(listOf(track("x"), track("y")))
        follower(repo)
        player.setMediaItems(listOf("a", "b", "c", "d", "e", "f").map { track(it).toMediaItem(null) })
        skipThenStay(3)
        assertEquals(listOf("a", "b", "c", "d", "x", "y"), queueIds())
        assertEquals("d", repo.asked?.first?.id)
        assertEquals(listOf("a", "b", "c"), repo.asked?.second?.map { it.id })
    }

    @Test
    fun `with shuffle on, the new songs play right after the current one`() = runTest {
        follower(Fake(listOf(track("x"), track("y"))))
        player.setMediaItems(listOf("a", "b", "c", "d", "e", "f").map { track(it).toMediaItem(null) })
        player.setShuffleOrder(QueueShuffleOrder.startingWith(0, 6))
        player.shuffleModeEnabled = true
        skipThenStay(3)
        val current = player.currentMediaItem?.mediaId
        assertEquals(4 + 2, player.mediaItemCount) // three played, the current one, two new
        val next = player.getMediaItemAt(player.nextMediaItemIndex).mediaId
        assertEquals("x", next)
        assertEquals(true, current !in listOf("x", "y"))
    }

    @Test
    fun `a song listened to past the start breaks the streak`() = runTest {
        follower(Fake(listOf(track("x"))))
        player.setMediaItems(listOf("a", "b", "c", "d", "e", "f").map { track(it).toMediaItem(null) })
        player.seekToNextMediaItem()
        player.seekTo(1, 60_000) // listened to b for a while
        player.seekToNextMediaItem()
        skipThenStay(1)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), queueIds())
    }

    @Test
    fun `skipping through an album in order leaves it alone`() = runTest {
        follower(Fake(listOf(track("x"))))
        val album = (1..6).map { track("t$it", album = "al-1") }
        player.setMediaItems(album.map { it.toMediaItem(null) })
        skipThenStay(3)
        assertEquals(album.map { it.id }, queueIds())
    }
}
