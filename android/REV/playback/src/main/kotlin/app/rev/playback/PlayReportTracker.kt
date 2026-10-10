package app.rev.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.rev.domain.playback.PlayCountPolicy
import app.rev.domain.playback.PlaybackReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reports "now playing" when a track starts, a completed play once it has been listened to long
 * enough ([PlayCountPolicy]), and a skip when you move on before that. Counts real listening time,
 * so seeking doesn't fake a play. Follows the session player, so casting counts too.
 */
internal class PlayReportTracker(
    private val player: Player,
    private val reporter: PlaybackReporter,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : Player.Listener {
    private var trackId: String? = null
    private var durationMs = 0L
    private var startedAt = 0L
    private var listenedMs = 0L
    private var reported = false
    private var announced = false
    private var ticker: Job? = null

    fun start() {
        player.addListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId
        // Handing playback between this device and a Cast receiver re-sets the same queue mid-track:
        // it's the same listen, so keep counting.
        val handedOver = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            id != null && id == trackId && player.currentPosition > HANDOVER_MIN_POSITION_MS
        if (handedOver) return
        // Next, previous or a jump in the queue: the user moved on.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) maybeReportSkip()
        trackId = id
        durationMs = mediaItem?.toTrack()?.durationMs ?: 0
        startedAt = clock()
        listenedMs = 0
        reported = false
        announced = false
        if (player.isPlaying) announce()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        ticker?.cancel()
        if (!isPlaying) return
        // Covers a restored queue that starts playing later, without a transition.
        announce()
        ticker = scope.launch {
            var last = clock()
            while (isActive) {
                delay(TICK_MS)
                val now = clock()
                listenedMs += now - last
                last = now
                maybeReportPlayed()
            }
        }
    }

    private fun announce() {
        val id = trackId ?: player.currentMediaItem?.mediaId?.also { trackId = it } ?: return
        if (announced) return
        announced = true
        if (durationMs == 0L) durationMs = player.currentMediaItem?.toTrack()?.durationMs ?: 0
        scope.launch { reporter.nowPlaying(id) }
    }

    private fun maybeReportSkip() {
        val id = trackId ?: return
        if (reported || !PlayCountPolicy.isSkip(durationMs, listenedMs)) return
        reported = true
        val started = startedAt
        val heard = listenedMs
        scope.launch { reporter.skipped(id, started, heard) }
    }

    private fun maybeReportPlayed() {
        val id = trackId ?: return
        if (reported || !PlayCountPolicy.counts(durationMs, listenedMs)) return
        reported = true
        val started = startedAt
        scope.launch { reporter.played(id, started) }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val HANDOVER_MIN_POSITION_MS = 5_000L
    }
}
