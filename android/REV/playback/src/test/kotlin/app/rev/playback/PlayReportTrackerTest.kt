package app.rev.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.Player
import app.rev.domain.learning.InteractionKind
import app.rev.domain.learning.ListenOutcome
import app.rev.domain.learning.RecentInteractions
import app.rev.domain.model.Track
import app.rev.domain.playback.AudioOutput
import app.rev.domain.playback.DeviceSnapshot
import app.rev.domain.playback.EndReason
import app.rev.domain.playback.ListenContext
import app.rev.domain.playback.PlaybackReporter
import app.rev.domain.playback.QueueSource
import kotlinx.coroutines.flow.MutableSharedFlow
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
class PlayReportTrackerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val player = ExoPlayer.Builder(context).build()
    private val favorites = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 4)

    @After
    fun release() = player.release()

    private data class Report(val kind: String, val trackId: String, val context: ListenContext?)

    private class Reporter : PlaybackReporter {
        val reports = mutableListOf<Report>()
        override suspend fun nowPlaying(trackId: String) = Unit
        override suspend fun played(trackId: String, startedAt: Long, context: ListenContext?) {
            reports += Report("play", trackId, context)
        }
        override suspend fun skipped(trackId: String, startedAt: Long, listenedMs: Long, context: ListenContext?) {
            reports += Report("skip", trackId, context)
        }
        override suspend fun ended(trackId: String, startedAt: Long, listenedMs: Long, context: ListenContext?) {
            reports += Report("ended", trackId, context)
        }
    }

    private fun track(id: String) = Track(
        id, "Title $id", "Album $id", "al-$id", "Artist $id", "ar-$id", 1, 1, 2001, "Metal", null,
        200_000, false, "flac", 1024, 44_100, 16, 1_000,
    )

    private fun TestScope.tracker(reporter: Reporter): PlayReportTracker = PlayReportTracker(
        player, reporter, backgroundScope,
        clock = { testScheduler.currentTime },
        device = { DeviceSnapshot(AudioOutput.Bluetooth, outputName = "Car Kit", network = "cellular", volume = 0.5) },
        predict = { 0.42 },
        modeAtStart = { _, _ -> app.rev.domain.modes.ActiveMode(app.rev.domain.modes.BuiltInModes.Work, app.rev.domain.modes.ModeSource.Auto, 0) },
        favoriteChanges = favorites,
        utcOffsetMinutes = { 120 },
    ).also { it.start() }

    /** The test player never really plays; listening time is driven by hand. */
    private fun TestScope.listen(tracker: PlayReportTracker, ms: Long) {
        tracker.onIsPlayingChanged(true)
        advanceTimeBy(ms)
        runCurrent()
        tracker.onIsPlayingChanged(false)
    }

    @Test
    fun `each listen carries its situation and what you did during it`() = runTest {
        val reporter = Reporter()
        val tracker = tracker(reporter)
        player.setMediaItems(listOf("a", "b", "c", "d", "e").map { track(it).toMediaItem(null, QueueSource.Picked) })
        runCurrent()

        // a: seek ahead, favorite it, then skip to the next song after 5 s.
        listen(tracker, 5_000)
        player.seekTo(0, 60_000)
        favorites.tryEmit("a" to true)
        runCurrent()
        player.seekToNextMediaItem()
        runCurrent()
        val skipA = reporter.reports.single()
        assertEquals(Report("skip", "a", skipA.context), skipA)
        val a = skipA.context!!
        assertEquals(QueueSource.Picked, a.source)
        assertEquals(EndReason.Next, a.endReason)
        assertEquals("mixed", a.queueShape)
        assertEquals(0 to 5, a.queuePosition to a.queueLength)
        assertEquals(0, a.sessionPosition)
        assertEquals(1, a.behaviour.seeksForward)
        assertEquals(60_000L, a.behaviour.seekedForwardMs)
        assertEquals(60_000L, a.behaviour.endPositionMs)
        assertEquals(true, a.behaviour.favoritedDuring)
        assertEquals(AudioOutput.Bluetooth, a.output)
        assertEquals("Car Kit", a.device?.outputName)
        assertEquals(120, a.utcOffsetMinutes)
        assertEquals(0.42, a.predictedScore)
        assertEquals(Triple("work", "At work", "auto"), Triple(a.mode, a.modeName, a.modeSource))

        // b: listened long enough to count; it knows the song before was skipped.
        listen(tracker, 101_000)
        val playB = reporter.reports.last()
        assertEquals("play", playB.kind)
        assertEquals("a" to "skip", playB.context?.previousTrackId to playB.context?.previousOutcome)
        assertEquals(1 to 1, playB.context?.sessionPosition to playB.context?.sessionSkips)
        assertEquals(1, playB.context?.queuePosition)

        // c: jump to e in the queue, then e: replaced by a new queue.
        player.seekToNextMediaItem()
        listen(tracker, 2_000)
        player.seekTo(4, 0)
        listen(tracker, 3_000)
        player.setMediaItems(listOf(track("z").toMediaItem(null, QueueSource.Picked)))
        runCurrent()
        assertEquals(
            listOf("b" to EndReason.Next, "c" to EndReason.Jump, "e" to EndReason.NewQueue),
            reporter.reports.drop(2).map { it.trackId to it.context?.endReason },
        )
        // b was a play: sent again when you moved on, with how it ended (well before the end: an outro skip).
        assertEquals("ended" to ListenOutcome.Outro, reporter.reports[2].kind to reporter.reports[2].context?.outcome)
    }

    @Test
    fun `tells interruptions and browsing from skips, and an outro skip from boredom`() = runTest {
        val reporter = Reporter()
        val learned = mutableListOf<Pair<String, ListenOutcome>>()
        val interactions = RecentInteractions(clock = { testScheduler.currentTime })
        val tracker = PlayReportTracker(
            player, reporter, backgroundScope,
            clock = { testScheduler.currentTime },
            onListen = { track, _, _, outcome -> learned += track.id to outcome },
            interactions = interactions,
        ).also { it.start() }
        player.setMediaItems(listOf("a", "b", "c", "d", "e").map { track(it).toMediaItem(null, QueueSource.Picked) })
        runCurrent()

        // a: 40 s in, paused to talk to someone for 3 minutes, then moved on while still paused.
        listen(tracker, 40_000)
        tracker.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        advanceTimeBy(180_000)
        player.seekToNextMediaItem()
        runCurrent()
        // b: skipped after 3 s while putting a playlist together.
        interactions.record(InteractionKind.PlaylistAdd, listOf("x"))
        listen(tracker, 3_000)
        player.seekToNextMediaItem()
        runCurrent()
        // c: counted, then skipped with 50 s of outro left.
        listen(tracker, 101_000)
        player.seekTo(150_000)
        runCurrent()
        player.seekToNextMediaItem()
        runCurrent()
        // d: skipped after 2 s, 11 minutes after the playlist add: a real rejection.
        advanceTimeBy(11 * 60_000)
        listen(tracker, 2_000)
        player.seekToNextMediaItem()
        runCurrent()

        val outcomes = reporter.reports.map { Triple(it.kind, it.trackId, it.context?.outcome) }
        assertEquals(
            listOf(
                Triple("skip", "a", ListenOutcome.Interrupted),
                Triple("skip", "b", ListenOutcome.Browsing),
                Triple("play", "c", null),
                Triple("ended", "c", ListenOutcome.Outro),
                Triple("skip", "d", ListenOutcome.Rejected),
            ),
            outcomes,
        )
        val a = reporter.reports.first().context!!.behaviour
        assertEquals(true to 180_000L, a.pausedAtEnd to a.lastPauseMs)
        // Interruptions and browsing teach nothing, and don't count as skips in the session.
        assertEquals(listOf("c" to ListenOutcome.Outro, "d" to ListenOutcome.Rejected), learned)
        assertEquals(0, reporter.reports.last().context?.sessionSkips)
        assertEquals("next", reporter.reports.last().context?.arrivedBy)
    }

    @Test
    fun `songs remember how they got into the queue`() {
        assertEquals(QueueSource.Smart to "MoreLikeThis", track("a").toMediaItem(null, QueueSource.Smart, "MoreLikeThis").queueSource())
        assertEquals(QueueSource.Unknown to null, track("a").toMediaItem(null).queueSource())
    }
}
