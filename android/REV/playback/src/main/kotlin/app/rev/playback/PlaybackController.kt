package app.rev.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.rev.domain.learning.InteractionKind
import app.rev.domain.learning.InteractionLog
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.playback.QueueSource
import app.rev.domain.playback.RepeatMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One queue entry in playback order. [index] is the item's position in the underlying queue. */
data class QueueEntry(val index: Int, val track: Track)

data class PlayerState(
    /** Queue in the order it will play (reflects shuffle). */
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = C.INDEX_UNSET,
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** Set when playback stopped on an error it could not skip past. */
    val error: String? = null,
    /** The smart queue's latest change to this queue, with when it happened. */
    val queueNote: QueueNote? = null,
    val queueNoteAt: Long = 0,
) {
    val isActive: Boolean get() = current != null
}

data class PlaybackProgress(val positionMs: Long = 0, val durationMs: Long = 0, val bufferedMs: Long = 0)

/**
 * The app's handle on playback. Wraps a MediaController connected to [PlaybackService], exposes
 * state as flows, and implements [PlaybackQueue] for screens that only need to enqueue tracks.
 * Commands issued before the controller connects are run once it does.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    /** Queue edits and repeat say something about the songs; the smart queue learns from them. */
    private val interactions: InteractionLog,
) : PlaybackQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = CompletableDeferred<MediaController>()
    private var connecting = false
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /** Connects to the playback service. Safe to call repeatedly; the UI calls it on start. */
    fun connect() {
        if (connecting) return
        connecting = true
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val mediaController = MediaController.Builder(context, token)
                .setListener(object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        QueueNotes.read(extras)?.let { (note, at) -> _state.value = _state.value.copy(queueNote = note, queueNoteAt = at) }
                    }
                })
                .buildAsync().await()
            mediaController.addListener(listener)
            controller.complete(mediaController)
            publish(mediaController)
        }
    }

    // ---- PlaybackQueue ---------------------------------------------------------------------

    override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) = command {
        if (tracks.isEmpty()) return@command
        // A new queue: the smart queue's last change was about the old one.
        _state.value = _state.value.copy(queueNote = null, queueNoteAt = 0)
        shuffleModeEnabled = shuffle
        // Shuffled, start from the first song of the new play order so every song gets played.
        if (shuffle) setMediaItems(tracks.map { it.toItem(QueueSource.Picked) }, true)
        else setMediaItems(tracks.map { it.toItem(QueueSource.Picked) }, startIndex.coerceIn(tracks.indices), 0)
        prepare()
        play()
    }

    override fun playNext(tracks: List<Track>) = command {
        interactions.record(InteractionKind.PlayNext, tracks.map { it.id })
        if (mediaItemCount == 0) return@command play(tracks, 0, false)
        // Shuffled too: QueueShuffleOrder plays songs inserted after the current one next.
        addMediaItems(currentMediaItemIndex + 1, tracks.map { it.toItem() })
    }

    override fun addToQueue(tracks: List<Track>) = command {
        interactions.record(InteractionKind.QueueAdd, tracks.map { it.id })
        val wasEmpty = mediaItemCount == 0
        addMediaItems(endOfPlayOrder(), tracks.map { it.toItem() })
        if (wasEmpty) prepare()
    }

    // ---- Transport -------------------------------------------------------------------------

    fun togglePlayPause() = command {
        if (isPlaying) {
            pause()
        } else {
            if (playbackState == Player.STATE_IDLE) prepare()
            if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
            play()
        }
    }

    fun next() = command { seekToNext() }

    /** Restarts the current track if past its first few seconds, otherwise goes back. */
    fun previous() = command { seekToPrevious() }

    fun seekTo(positionMs: Long) = command {
        seekTo(positionMs)
        _progress.value = _progress.value.copy(positionMs = positionMs)
    }

    fun skipTo(index: Int) = command {
        if (index !in 0 until mediaItemCount) return@command
        seekTo(index, 0)
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }

    /** "Not now": skips the song and teaches the personal model you didn't want it now. */
    fun notNow() = command { sendCustomCommand(FeedbackCommands.NOT_NOW, Bundle.EMPTY) }

    /** "More like this": songs like it next, and the personal model learns you wanted it. */
    fun moreLikeThis() = command { sendCustomCommand(FeedbackCommands.MORE_LIKE_THIS, Bundle.EMPTY) }

    /** Plays a fresh mix for the current listening mode. */
    override fun playModeMix() = command { sendCustomCommand(FeedbackCommands.PLAY_MODE_MIX, Bundle.EMPTY) }

    fun toggleShuffle() = command { shuffleModeEnabled = !shuffleModeEnabled }

    fun cycleRepeat() = command {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        // Wanting a song again and again.
        if (repeatMode == Player.REPEAT_MODE_ONE) currentMediaItem?.mediaId?.let { interactions.record(InteractionKind.RepeatOne, listOf(it)) }
    }

    // ---- Queue editing ---------------------------------------------------------------------

    fun removeAt(index: Int) = command {
        if (index !in 0 until mediaItemCount) return@command
        // Taking out a song that's still to come is passing on it; tidying up played ones isn't.
        val order = playbackOrder(currentTimeline, shuffleModeEnabled)
        if (order.indexOf(index) > order.indexOf(currentMediaItemIndex)) {
            interactions.record(InteractionKind.QueueRemove, listOf(getMediaItemAt(index).mediaId))
        }
        removeMediaItem(index)
    }

    /** Moves the song at play position [from] (as in [PlayerState.queue]) to play position [to]. */
    fun move(from: Int, to: Int) = command {
        val (index, newIndex) = queueMove(playbackOrder(currentTimeline, shuffleModeEnabled), from, to) ?: return@command
        moveMediaItem(index, newIndex)
    }

    fun clearQueue() = command {
        stop()
        clearMediaItems()
    }

    // ---- Internals -------------------------------------------------------------------------

    private fun command(block: MediaController.() -> Unit) {
        connect()
        scope.launch { controller.await().block() }
    }

    private fun Track.toItem(source: QueueSource = QueueSource.Queued): MediaItem = toMediaItem(systemArtworkUri(), source)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    private fun publish(player: Player) {
        val queue = playbackOrder(player.currentTimeline, player.shuffleModeEnabled)
            .map { QueueEntry(it, player.getMediaItemAt(it).toTrack()) }
        _state.value = PlayerState(
            queue = queue,
            currentIndex = player.currentMediaItemIndex.takeIf { player.mediaItemCount > 0 } ?: C.INDEX_UNSET,
            current = player.currentMediaItem?.toTrack(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode.toRepeatMode(),
            hasPrevious = player.mediaItemCount > 0,
            hasNext = player.hasNextMediaItem(),
            error = player.playerError?.let(::describe),
            queueNote = _state.value.queueNote,
            queueNoteAt = _state.value.queueNoteAt,
        )
        updateProgress(player)
        if (player.isPlaying) startProgressUpdates(player) else progressJob?.cancel()
    }

    /** Position only matters while playing; 4 updates a second is plenty for a seek bar and lyrics. */
    private fun startProgressUpdates(player: Player) {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                updateProgress(player)
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun updateProgress(player: Player) {
        _progress.value = PlaybackProgress(
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }
                ?: player.currentMediaItem?.toTrack()?.durationMs ?: 0,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0),
        )
    }

    private fun describe(error: PlaybackException): String =
        if (error.cause is TrackUnavailableException) "This track isn't available offline." else "Playback failed."

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}

/** Indices of the timeline's items in the order they will play. */
internal fun playbackOrder(timeline: Timeline, shuffle: Boolean): List<Int> {
    if (timeline.isEmpty) return emptyList()
    val order = ArrayList<Int>(timeline.windowCount)
    var index = timeline.getFirstWindowIndex(shuffle)
    while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
        order += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
    }
    return order
}
