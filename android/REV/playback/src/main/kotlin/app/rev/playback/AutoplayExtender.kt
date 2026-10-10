package app.rev.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.rev.domain.model.Track
import app.rev.domain.playback.AutoplayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Autoplay: when the last song in the queue starts, appends what comes next (the next album, or
 * similar songs; see [app.rev.domain.playback.Autoplay]) so playback carries on, gaplessly and with
 * crossfade. If the queue had already ended by the time the songs arrive, playback resumes with
 * them. Nothing is added with repeat on (the queue loops) or with the setting off.
 */
internal class AutoplayExtender(
    private val player: Player,
    private val scope: CoroutineScope,
    private val autoplay: AutoplayRepository,
    private val enabled: () -> Boolean,
    private val toItem: (Track) -> MediaItem,
) : Player.Listener {
    private var job: Job? = null

    /** The queue end last handled, so each end is extended (or tried) once. */
    private var handledEnd: String? = null

    fun start() = player.addListener(this)

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
            maybeExtend()
        }
    }

    private fun maybeExtend() {
        val count = player.mediaItemCount
        if (count == 0 || !enabled() || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.hasNextMediaItem() || job?.isActive == true) return
        val lastId = player.getMediaItemAt(count - 1).mediaId
        val end = "$count:$lastId"
        if (end == handledEnd) return
        handledEnd = end
        val queue = (0 until count).map { player.getMediaItemAt(it).toTrack() }
        job = scope.launch {
            val more = runCatching { autoplay.continuation(queue) }.getOrDefault(emptyList())
            // The user may have started something else meanwhile.
            val unchanged = player.mediaItemCount == count && player.getMediaItemAt(count - 1).mediaId == lastId
            if (more.isEmpty() || !unchanged) return@launch
            val ended = player.playbackState == Player.STATE_ENDED
            val at = player.endOfPlayOrder()
            player.addMediaItems(at, more.map(toItem))
            if (ended) {
                player.seekTo(at, 0)
                player.prepare()
                player.play()
            }
        }
    }
}
