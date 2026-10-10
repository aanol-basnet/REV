package app.rev.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import app.rev.domain.model.Track
import app.rev.domain.playback.QueueSnapshot
import app.rev.domain.playback.QueueSource
import app.rev.domain.playback.QueueStore
import app.rev.domain.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the persisted queue in step with the player: after queue/mode changes (debounced) and
 * periodically while playing, so position survives the process being killed.
 */
internal class QueuePersister(
    private val player: Player,
    private val store: QueueStore,
    private val scope: CoroutineScope,
    private val artworkUrl: (Track) -> String?,
) : Player.Listener {
    private var pendingSave: Job? = null
    private var periodic: Job? = null

    fun start() {
        player.addListener(this)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
            )
        ) {
            scheduleSave()
        }
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
            scheduleSave()
            if (player.isPlaying) startPeriodicSaves() else periodic?.cancel()
        }
    }

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(DEBOUNCE_MS)
            saveNow()
        }
    }

    private fun startPeriodicSaves() {
        periodic?.cancel()
        periodic = scope.launch {
            while (isActive) {
                delay(PERIODIC_MS)
                saveNow()
            }
        }
    }

    /** Must be called on the player's thread. */
    suspend fun saveNow() {
        val snapshot = snapshot()
        withContext(NonCancellable) {
            if (snapshot == null) store.clear() else store.save(snapshot)
        }
    }

    private fun snapshot(): QueueSnapshot? {
        if (player.mediaItemCount == 0) return null
        val tracks = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() }
        return QueueSnapshot(
            tracks = tracks,
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode.toRepeatMode(),
        )
    }

    /** Loads the saved queue into an empty player without starting playback. */
    suspend fun restoreInto(player: Player, playWhenReady: Boolean) {
        if (player.mediaItemCount > 0) return // A controller already set a queue.
        val saved = store.load() ?: return
        if (player.mediaItemCount > 0) return
        player.setMediaItems(saved.items(), saved.currentIndex, saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeat.toPlayerRepeatMode()
        player.playWhenReady = playWhenReady
    }

    @OptIn(UnstableApi::class)
    suspend fun loadForResumption(): MediaItemsWithStartPosition? {
        val saved = store.load() ?: return null
        return MediaItemsWithStartPosition(saved.items(), saved.currentIndex, saved.positionMs)
    }

    private fun QueueSnapshot.items(): List<MediaItem> = tracks.map { it.toMediaItem(artworkUrl(it), QueueSource.Restored) }

    private companion object {
        const val DEBOUNCE_MS = 1_000L
        const val PERIODIC_MS = 15_000L
    }
}

internal fun Int.toRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ALL -> RepeatMode.All
    Player.REPEAT_MODE_ONE -> RepeatMode.One
    else -> RepeatMode.Off
}

internal fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.Off -> Player.REPEAT_MODE_OFF
    RepeatMode.All -> Player.REPEAT_MODE_ALL
    RepeatMode.One -> Player.REPEAT_MODE_ONE
}
