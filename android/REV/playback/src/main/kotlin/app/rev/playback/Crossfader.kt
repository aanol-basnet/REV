package app.rev.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.rev.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Crossfade between consecutive tracks using a second ("tail") player.
 *
 * Shortly before a track ends, the tail player is prepared on the *same* track a little further
 * ahead. When playback reaches that point, the tail takes over the outgoing track (fading out)
 * while the main player — which owns the queue, notification and session — moves on to the next
 * track and fades in. Nothing outside this class needs to know a fade is happening.
 *
 * Consecutive tracks of the same album are never crossfaded, so gapless albums stay gapless.
 */
internal class Crossfader(
    private val main: ExoPlayer,
    private val scope: CoroutineScope,
    private val createTail: () -> Pair<ExoPlayer, ReplayGainAudioProcessor>,
    private val crossfadeMs: () -> Long,
    private val gainFor: (MediaItem?) -> Float,
) : Player.Listener {

    private var tail: Pair<ExoPlayer, ReplayGainAudioProcessor>? = null
    private var monitor: Job? = null
    private var fade: Job? = null
    /** Index we prepared a handoff for, so it happens once per track. */
    private var armedFor = C.INDEX_UNSET

    fun start() {
        main.addListener(this)
    }

    fun release() {
        cancelFade()
        tail?.first?.release()
        tail = null
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startMonitor()
        } else {
            monitor?.cancel()
            // Pausing mid-fade: finish cleanly instead of leaving two tracks half-faded.
            if (main.playbackState != Player.STATE_BUFFERING) cancelFade()
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // The user seeked or skipped: the planned handoff no longer applies.
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SKIP) {
            if (fade == null) disarm()
        }
    }

    private fun startMonitor() {
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            while (isActive) {
                check()
                delay(TICK_MS)
            }
        }
    }

    private fun check() {
        val fadeMs = crossfadeMs()
        if (fadeMs <= 0 || fade != null || !main.isPlaying || main.repeatMode == Player.REPEAT_MODE_ONE) return
        val index = main.currentMediaItemIndex
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration < fadeMs * 3 || !main.hasNextMediaItem()) return
        val next = main.getMediaItemAt(main.nextMediaItemIndex)
        val current = main.currentMediaItem ?: return
        if (isGaplessSequence(current.toTrack(), next.toTrack())) return

        val handoffAt = duration - fadeMs
        val position = main.currentPosition
        if (armedFor != index && position >= handoffAt - PREPARE_LEAD_MS) arm(current, handoffAt)
        if (armedFor == index && position >= handoffAt) handOff(fadeMs)
    }

    /** Buffers the outgoing track on the tail player at the handoff point, paused and silent. */
    private fun arm(item: MediaItem, handoffAt: Long) {
        val (player, gain) = tail ?: createTail().also { tail = it }
        player.volume = 0f
        gain.setGain(gainFor(item))
        player.setMediaItem(item, handoffAt)
        player.prepare()
        player.playWhenReady = false
        armedFor = main.currentMediaItemIndex
    }

    private fun handOff(fadeMs: Long) {
        val player = tail?.first ?: return
        if (player.playbackState != Player.STATE_READY) {
            disarm() // tail couldn't buffer in time (slow network): just play on without a fade
            return
        }
        player.volume = 1f
        player.play()
        main.volume = 0f
        main.seekToNextMediaItem()
        fade = scope.launch {
            val started = System.currentTimeMillis()
            while (isActive) {
                val t = ((System.currentTimeMillis() - started).toFloat() / fadeMs).coerceIn(0f, 1f)
                // Equal-power curve keeps loudness steady through the overlap.
                main.volume = kotlin.math.sin(t * Math.PI.toFloat() / 2)
                player.volume = kotlin.math.cos(t * Math.PI.toFloat() / 2)
                if (t >= 1f) break
                delay(FADE_STEP_MS)
            }
            finishFade()
        }
    }

    private fun cancelFade() {
        fade?.cancel()
        finishFade()
    }

    private fun finishFade() {
        fade = null
        main.volume = 1f
        disarm()
    }

    private fun disarm() {
        armedFor = C.INDEX_UNSET
        tail?.first?.run {
            stop()
            clearMediaItems()
        }
    }

    private companion object {
        const val TICK_MS = 50L
        const val FADE_STEP_MS = 40L
        /** How early the tail starts buffering before the handoff. */
        const val PREPARE_LEAD_MS = 4_000L
    }
}

/** Next track continues the same album in order (e.g. a live album or a DJ mix). */
internal fun isGaplessSequence(current: Track, next: Track): Boolean {
    val album = current.albumId ?: return false
    if (album != next.albumId) return false
    val currentNo = current.trackNumber ?: return false
    val nextNo = next.trackNumber ?: return false
    val sameDisc = (current.discNumber ?: 1) == (next.discNumber ?: 1)
    return (sameDisc && nextNo == currentNo + 1) || (!sameDisc && nextNo == 1)
}
