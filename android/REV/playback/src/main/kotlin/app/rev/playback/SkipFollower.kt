package app.rev.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.rev.domain.model.Track
import app.rev.domain.playback.Autoplay
import app.rev.domain.playback.AutoplayRepository
import app.rev.domain.playback.SkipStreak
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Follow my skips: after a few early skips in a row, the song you stay on (for
 * [SkipStreak.SETTLE_MS]) replaces everything after it with songs like it (see
 * [app.rev.domain.playback.SkipSteering]). Songs already played stay in the queue. Not with repeat
 * on, or for an album played in order, where skipping ahead is picking a song, not rejecting a mood.
 */
internal class SkipFollower(
    private val player: Player,
    private val scope: CoroutineScope,
    private val autoplay: AutoplayRepository,
    private val enabled: () -> Boolean,
    private val toItem: (Track) -> MediaItem,
) : Player.Listener {
    private val streak = SkipStreak()
    private var watch: Job? = null

    fun start() = player.addListener(this)

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
        watch?.cancel()
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            streak.reset()
        } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            val leftIndex = oldPosition.mediaItemIndex
            if (leftIndex !in 0 until player.mediaItemCount) return
            val left = player.getMediaItemAt(leftIndex).toTrack()
            // Going back to an earlier song isn't a skip.
            if (playPosition(newPosition.mediaItemIndex) < playPosition(oldPosition.mediaItemIndex)) return
            streak.left(left, oldPosition.positionMs)
            if (streak.ready) watchCurrent()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A new queue starts a new streak.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            watch?.cancel()
            streak.reset()
        }
    }

    /** Waits until the current song has played long enough, then follows it. */
    private fun watchCurrent() {
        val id = player.currentMediaItem?.mediaId ?: return
        watch = scope.launch {
            while (player.currentMediaItem?.mediaId == id) {
                if (player.currentPosition >= SkipStreak.SETTLE_MS) {
                    follow()
                    return@launch
                }
                delay(CHECK_MS)
            }
        }
    }

    private suspend fun follow() {
        val skipped = streak.skipped
        streak.reset()
        val seed = player.currentMediaItem?.toTrack() ?: return
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() }
        if (!enabled() || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (!player.shuffleModeEnabled && Autoplay.plan(queue) is Autoplay.Plan.NextAlbum) return

        val songs = runCatching { autoplay.songsLike(seed, queue, skipped) }.getOrDefault(emptyList())
        // The user may have moved on or started something else meanwhile.
        if (songs.isEmpty() || player.currentMediaItem?.mediaId != seed.id || player.mediaItemCount != queue.size) return

        val upcoming = upcomingIndices()
        // Add before removing, so the queue never looks finished (autoplay would step in).
        val at = player.endOfPlayOrder()
        player.addMediaItems(at, songs.map(toItem))
        upcoming.map { if (it >= at) it + songs.size else it }
            .sortedDescending()
            .forEach(player::removeMediaItem)
    }

    /** Queue indices of the songs after the current one, in play order. */
    private fun upcomingIndices(): List<Int> {
        val timeline = player.currentTimeline
        val out = mutableListOf<Int>()
        var index = player.currentMediaItemIndex
        while (true) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) return out
            out += index
        }
    }

    /** Where queue index [index] comes in play order. */
    private fun playPosition(index: Int): Int {
        val timeline = player.currentTimeline
        if (index !in 0 until timeline.windowCount) return -1
        var at = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
        var position = 0
        while (at != C.INDEX_UNSET && at != index) {
            at = timeline.getNextWindowIndex(at, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            position++
        }
        return position
    }

    private companion object {
        const val CHECK_MS = 1_000L
    }
}
