package app.rev.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import app.rev.domain.model.Track
import app.rev.domain.playback.AutoplayRepository
import app.rev.domain.playback.QueueSource
import app.rev.domain.taste.CandidateRequest
import app.rev.domain.taste.ListeningSession
import app.rev.domain.taste.Outcome
import app.rev.domain.taste.QueueChange
import app.rev.domain.taste.QueueDirector
import app.rev.domain.taste.TasteProfile
import app.rev.domain.taste.TasteRanker
import app.rev.domain.taste.TasteRepository
import kotlin.random.Random
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartQueueTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val player = ExoPlayer.Builder(context).build()
    private val session = ListeningSession { 0 }
    private val notes = mutableListOf<QueueNote>()

    @After
    fun release() = player.release()

    private fun track(id: String, artist: String = "Artist $id", album: String = "al-$id", n: Int = 1) = Track(
        id, "Title $id", "Album $id", album, artist, "ar-$artist", n, 1, 2001, "Metal", null,
        200_000, false, "flac", 1024, 44_100, 16, 1_000,
    )

    private class Fake(private val songs: List<Track>) : AutoplayRepository {
        val asked = mutableListOf<CandidateRequest>()
        override suspend fun continuation(queue: List<Track>) = emptyList<Track>()
        override suspend fun candidates(request: CandidateRequest): List<Track> {
            asked += request
            return songs
        }
    }

    private val noTaste = TasteRepository { TasteProfile() }

    private fun TestScope.smartQueue(repo: AutoplayRepository) = SmartQueue(
        player, backgroundScope, repo, session, noTaste, TasteRanker(session, noTaste, { 12 }, Random(1)), QueueDirector(),
        enabled = { true }, toItem = { track, change -> track.toMediaItem(null, QueueSource.Smart, change.name) }, onChange = { notes += it },
    ).also { it.start() }

    private fun queueIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private fun upcomingIds(): List<String> {
        val out = mutableListOf<String>()
        var i = player.currentMediaItemIndex
        while (true) {
            i = player.currentTimeline.getNextWindowIndex(i, androidx.media3.common.Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i < 0) return out
            out += player.getMediaItemAt(i).mediaId
        }
    }

    private fun TestScope.skip(times: Int) {
        repeat(times) { player.seekToNextMediaItem() }
        runCurrent()
    }

    private fun TestScope.stay() {
        player.seekTo(player.currentMediaItemIndex, 31_000)
        advanceTimeBy(2_000)
        runCurrent()
    }

    @Test
    fun `skipping through a playlist and staying on a song fills the rest with songs like it`() = runTest {
        val repo = Fake(listOf(track("x"), track("y")))
        smartQueue(repo)
        player.setMediaItems(listOf("a", "b", "c", "d", "e", "f").map { track(it).toMediaItem(null) })
        skip(3)
        stay()
        assertEquals(listOf("a", "b", "c", "d"), queueIds().take(4))
        assertEquals(setOf("x", "y"), upcomingIds().toSet())
        assertEquals(QueueChange.MoreLikeThis, notes.last().change)
        assertEquals("d", (repo.asked.last() as CandidateRequest.LikeTrack).seed.id)
        assertEquals(
            listOf(Outcome.QuickSkip, Outcome.QuickSkip, Outcome.QuickSkip, Outcome.Stayed),
            session.signals().map { it.outcome },
        )
    }

    @Test
    fun `three skips on shuffle reorder the same songs`() = runTest {
        smartQueue(Fake(emptyList()))
        val ids = (1..10).map { "s$it" }
        player.setMediaItems(ids.map { track(it).toMediaItem(null) })
        player.setShuffleOrder(QueueShuffleOrder.startingWith(0, ids.size, Random(3)))
        player.shuffleModeEnabled = true
        val before = upcomingIds()
        skip(3)
        assertEquals(QueueChange.Reshuffled, notes.last().change)
        assertEquals(before.drop(3).toSet(), upcomingIds().toSet())
        assertEquals(ids.size, player.mediaItemCount)
    }

    @Test
    fun `going back keeps the streak, and a song heard for a while ends it`() = runTest {
        smartQueue(Fake(listOf(track("x"))))
        player.setMediaItems(listOf("a", "b", "c", "d", "e", "f").map { track(it).toMediaItem(null) })
        skip(2)
        player.seekToPreviousMediaItem() // back to b: no verdict on c
        player.seekTo(player.currentMediaItemIndex, 60_000)
        player.seekToNextMediaItem() // left b after a minute: a late skip
        runCurrent()
        assertEquals(Outcome.LateSkip, session.signals().last().outcome)
        skip(1)
        stay()
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `an album played in order is left alone when you skip to a song`() = runTest {
        smartQueue(Fake(listOf(track("x"))))
        val album = (1..8).map { track("t$it", artist = "Tool", album = "lateralus", n = it) }
        player.setMediaItems(album.map { it.toMediaItem(null) })
        skip(3)
        stay()
        assertEquals(album.map { it.id }, queueIds())
        assertTrue(notes.isEmpty())
    }
}
