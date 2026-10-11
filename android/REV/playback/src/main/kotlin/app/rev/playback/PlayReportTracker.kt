package app.rev.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.rev.domain.learning.InteractionLog
import app.rev.domain.learning.ListenFacts
import app.rev.domain.learning.ListenOutcome
import app.rev.domain.learning.ListenOutcomes
import app.rev.domain.model.Track
import app.rev.domain.modes.ActiveMode
import app.rev.domain.playback.AudioOutput
import app.rev.domain.playback.DeviceSnapshot
import app.rev.domain.playback.EndReason
import app.rev.domain.playback.ListenBehaviour
import app.rev.domain.playback.ListenContext
import app.rev.domain.playback.PlayCountPolicy
import app.rev.domain.playback.PlaybackReporter
import app.rev.domain.taste.QueueShape
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reports "now playing" when a track starts, a completed play once it has been listened to long
 * enough ([PlayCountPolicy]), and a skip when you move on before that (to the next song, back, to
 * another song in the queue, or to a new queue). Counts real listening time, so seeking doesn't
 * fake a play. Follows the session player, so casting counts too.
 *
 * Each listen carries its [ListenContext], the training data for the smart queue: how the song got
 * into the queue, the queue and session around it, the phone's situation ([device]), what you did
 * while it played, and the smart queue's guess ([predict]).
 *
 * When a song ends, its [ListenOutcome] says how it went: rejected, got bored, skipped the outro,
 * finished, or interrupted (left while paused to talk, after a call) and so not about the song. A
 * play is reported as soon as it counts, then again when it ends, with how it ended.
 */
internal class PlayReportTracker(
    private val player: Player,
    private val reporter: PlaybackReporter,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val device: () -> DeviceSnapshot? = { null },
    private val predict: suspend (Track) -> Double? = { null },
    /** The personal skip model's chance of skipping the song as it starts. */
    private val predictSkip: suspend (Track, ListenContext) -> Double? = { _, _ -> null },
    /** The listening mode as a song starts ([newSession]: the first song after a break). */
    private val modeAtStart: (DeviceSnapshot?, Boolean) -> ActiveMode? = { _, _ -> null },
    /** Each song as it starts: the situation for ranking what comes next. */
    private val onStart: (ListenContext) -> Unit = {},
    /** Each listen once it has ended, to learn from (not when a feedback button already said more). */
    private val onListen: (Track, ListenContext, Long, ListenOutcome) -> Unit = { _, _, _, _ -> },
    /** Playlist adds, for telling browsing from skipping. */
    private val interactions: InteractionLog? = null,
    private val favoriteChanges: Flow<Pair<String, Boolean>>? = null,
    private val utcOffsetMinutes: (Long) -> Int = { TimeZone.getDefault().getOffset(it) / 60_000 },
) : Player.Listener {
    private var trackId: String? = null
    private var track: Track? = null
    private var feedbackGiven = false
    private var durationMs = 0L
    private var startedAt = 0L
    private var listenedMs = 0L
    private var reported = false
    private var reportedKind: String? = null
    private var finished = false
    private var announced = false
    private var ticker: Job? = null

    private var context: ListenContext? = null
    private var behaviour = ListenBehaviour()
    private var pausedSince: Long? = null
    private var queueIndex = -1

    /** Where the song you just left was, from the position jump that came with leaving it. */
    private var leftAtMs: Long? = null

    /** How the current song began: previous, next, jump, auto, repeat or new_queue. */
    private var arrivedBy: String? = null

    // Pauses of any kind (yours, a call, unplugged headphones), for telling interruptions from skips.
    private var pauseStartedAt: Long? = null
    private var pauseBySystem = false
    private var lastPauseMs = 0L
    private var lastPauseBySystem = false
    private var listenedAtResume: Long? = null

    private var previousTrackId: String? = null
    private var previousOutcome: String? = null
    private var sessionPosition = 0
    private var sessionSkips = 0
    private var lastActivity = 0L

    fun start() {
        player.addListener(this)
        favoriteChanges?.let { changes ->
            scope.launch {
                changes.collect { (id, favorite) -> if (id == trackId) behaviour = behaviour.copy(favoritedDuring = favorite) }
            }
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex || reason == Player.DISCONTINUITY_REASON_REMOVE) {
            leftAtMs = oldPosition.positionMs
            return
        }
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        val moved = newPosition.positionMs - oldPosition.positionMs
        behaviour = if (moved >= 0) {
            behaviour.copy(seeksForward = behaviour.seeksForward + 1, seekedForwardMs = behaviour.seekedForwardMs + moved)
        } else {
            behaviour.copy(seeksBack = behaviour.seeksBack + 1, seekedBackMs = behaviour.seekedBackMs - moved)
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (trackId == null) return
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            behaviour = behaviour.copy(pauses = behaviour.pauses + 1)
            pausedSince = clock()
            pauseStarted(system = false)
        } else if (!playWhenReady && reason in SYSTEM_PAUSES) {
            pauseStarted(system = true)
        } else if (playWhenReady) {
            pausedSince?.let { behaviour = behaviour.copy(pausedMs = behaviour.pausedMs + (clock() - it)) }
            pausedSince = null
            pauseEnded()
        }
    }

    /** A call or another app's sound holds playback without pausing it: that's an interruption too. */
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        if (trackId == null) return
        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
            pauseStarted(system = true)
        } else if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && pauseBySystem && player.playWhenReady) {
            pauseEnded()
        }
    }

    private fun pauseStarted(system: Boolean) {
        if (pauseStartedAt != null) return
        pauseStartedAt = clock()
        pauseBySystem = system
    }

    private fun pauseEnded() {
        val started = pauseStartedAt ?: return
        lastPauseMs = clock() - started
        lastPauseBySystem = pauseBySystem
        listenedAtResume = listenedMs
        pauseStartedAt = null
        pauseBySystem = false
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId
        // Handing playback between this device and a Cast receiver re-sets the same queue mid-track:
        // it's the same listen, so keep counting.
        val handedOver = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            id != null && id == trackId && player.currentPosition > HANDOVER_MIN_POSITION_MS
        if (handedOver) return
        // Next, previous, a jump in the queue, or a new queue: the user moved on; or it played out.
        val endReason = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> endReasonFor(queueIndex, player.currentMediaItemIndex)
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> if (mediaItem == null) EndReason.Stopped else EndReason.NewQueue
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> EndReason.Ended
            else -> null
        }
        endReason?.let { finish(it) }

        if (trackId != null) {
            previousTrackId = trackId
            previousOutcome = reportedKind ?: "none"
        }
        trackId = id
        val track = mediaItem?.toTrack()
        this.track = track
        feedbackGiven = false
        durationMs = track?.durationMs ?: 0
        startedAt = clock()
        listenedMs = 0
        reported = false
        reportedKind = null
        finished = false
        announced = false
        leftAtMs = null
        pausedSince = null
        pauseStartedAt = null
        pauseBySystem = false
        lastPauseMs = 0
        lastPauseBySystem = false
        listenedAtResume = null
        arrivedBy = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "new_queue"
            else -> endReason?.wire
        }
        queueIndex = player.currentMediaItemIndex
        behaviour = ListenBehaviour(startPositionMs = player.currentPosition.coerceAtLeast(0))
        context = null
        if (mediaItem != null && track != null) begin(mediaItem, track)
        if (player.isPlaying) announce()
    }

    /** The situation the song starts in, and the smart queue's guess about it. */
    private fun begin(item: MediaItem, track: Track) {
        val now = clock()
        if (now - lastActivity > SESSION_GAP_MS) {
            sessionPosition = 0
            sessionSkips = 0
            previousTrackId = null
            previousOutcome = null
        }
        lastActivity = now
        val (source, change) = item.queueSource()
        val shuffle = player.shuffleModeEnabled
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() }
        val snapshot = runCatching { device() }.getOrNull()
        val mode = runCatching { modeAtStart(snapshot, sessionPosition == 0) }.getOrNull()
        val started = ListenContext(
            source = source,
            smartChange = change,
            queueShape = when (QueueShape.of(queue, shuffle)) {
                QueueShape.Album -> "album"
                is QueueShape.Artist -> "artist"
                QueueShape.Mixed -> "mixed"
            },
            shuffle = shuffle,
            repeat = player.repeatMode.toRepeatMode(),
            queuePosition = playbackOrder(player.currentTimeline, shuffle).indexOf(player.currentMediaItemIndex).coerceAtLeast(0),
            queueLength = queue.size,
            previousTrackId = previousTrackId,
            previousOutcome = previousOutcome,
            sessionPosition = sessionPosition,
            sessionSkips = sessionSkips,
            output = snapshot?.output ?: AudioOutput.Other,
            utcOffsetMinutes = utcOffsetMinutes(now),
            favorite = track.starred,
            device = snapshot,
            mode = mode?.mode?.id,
            modeName = mode?.mode?.name,
            modeSource = mode?.source?.wire,
            arrivedBy = arrivedBy,
        )
        context = started
        onStart(started)
        sessionPosition += 1
        val id = track.id
        scope.launch {
            val score = runCatching { predict(track) }.getOrNull()
            val skip = runCatching { predictSkip(track, started) }.getOrNull()
            if (trackId == id) context = context?.copy(predictedScore = score, predictedSkip = skip)
        }
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
                lastActivity = now
                maybeReportPlayed()
            }
        }
    }

    private fun announce() {
        val id = trackId ?: player.currentMediaItem?.mediaId?.also { trackId = it } ?: return
        if (announced) return
        announced = true
        if (durationMs == 0L) durationMs = player.currentMediaItem?.toTrack()?.durationMs ?: 0
        if (context == null) player.currentMediaItem?.let { item -> begin(item, item.toTrack()) }
        scope.launch { reporter.nowPlaying(id) }
    }

    /** The song was left ([reason]): a skip if it hadn't counted, else the end of a play. */
    /** Returns the report's job, or null when there was nothing to report. */
    private fun finish(reason: EndReason, at: Long? = null): Job? {
        val id = trackId ?: return null
        if (finished) return null
        finished = true
        val position = if (reason == EndReason.Ended) durationMs.takeIf { it > 0 } ?: leftAtMs else at ?: leftAtMs
        if (reported && reportedKind == "play") {
            val outcome = ListenOutcomes.classify(facts(reason, position, counted = true))
            val ctx = finalContext(position)?.copy(endReason = reason, outcome = outcome)
            val started = startedAt
            val heard = listenedMs
            val job = scope.launch { reporter.ended(id, started, heard, ctx) }
            learnFrom(ctx, outcome)
            return job
        }
        if (reported || !PlayCountPolicy.isSkip(durationMs, listenedMs)) return null
        reported = true
        reportedKind = "skip"
        val outcome = ListenOutcomes.classify(facts(reason, position, counted = false))
        if (outcome.skipped) sessionSkips += 1
        val started = startedAt
        val heard = listenedMs
        val ctx = finalContext(position)?.copy(endReason = reason, outcome = outcome)
        val job = scope.launch { reporter.skipped(id, started, heard, ctx) }
        learnFrom(ctx, outcome)
        return job
    }

    /**
     * How the song playing now would go if it were left at [positionMs] for [reason]: what the
     * smart queue reads a skip as. Null with nothing playing.
     */
    fun outcomeIfLeft(positionMs: Long, reason: EndReason): ListenOutcome? {
        if (trackId == null) return null
        val counted = reportedKind == "play" || PlayCountPolicy.counts(durationMs, listenedMs)
        return ListenOutcomes.classify(facts(reason, positionMs, counted))
    }

    private fun facts(reason: EndReason, positionMs: Long?, counted: Boolean): ListenFacts {
        val now = clock()
        val ongoing = pauseStartedAt
        val id = trackId
        return ListenFacts(
            counted = counted,
            durationMs = durationMs,
            heardMs = listenedMs,
            endPositionMs = positionMs,
            endReason = reason.wire,
            pausedAtEnd = ongoing != null,
            lastPauseMs = if (ongoing != null) now - ongoing else lastPauseMs,
            heardSinceResumeMs = if (ongoing != null) null else listenedAtResume?.let { listenedMs - it },
            systemPause = if (ongoing != null) pauseBySystem else lastPauseBySystem,
            arrivedBy = arrivedBy,
            addedToPlaylist = id != null && interactions?.addedToPlaylist(id, startedAt) == true,
            curating = interactions?.curating(now) == true,
        )
    }

    private fun maybeReportPlayed() {
        val id = trackId ?: return
        if (reported || !PlayCountPolicy.counts(durationMs, listenedMs)) return
        reported = true
        reportedKind = "play"
        val started = startedAt
        val ctx = finalContext(player.currentPosition)
        scope.launch { reporter.played(id, started, ctx) }
    }

    /** The service is going away: the song playing now ends here. Returns the report's job, to wait for. */
    fun stop(): Job? = finish(EndReason.Stopped, at = player.currentPosition)

    private fun learnFrom(ctx: ListenContext?, outcome: ListenOutcome) {
        val t = track ?: return
        if (ctx != null && !feedbackGiven && outcome.learnable) onListen(t, ctx, startedAt, outcome)
    }

    /** The listen a feedback button is about: the song playing, when it started, where it is now. */
    data class FeedbackListen(val track: Track, val startedAt: Long, val positionMs: Long, val context: ListenContext?)

    /**
     * A feedback button was pressed: it's what to learn from for this listen (more than how it
     * ends), so the play or skip that follows isn't learned from again.
     */
    fun feedbackGiven(): FeedbackListen? {
        val t = track ?: return null
        feedbackGiven = true
        return FeedbackListen(t, startedAt, player.currentPosition, finalContext(player.currentPosition))
    }

    /** The context with what you did up to now: where you were in the song, pauses, volume. */
    private fun finalContext(positionMs: Long?): ListenContext? {
        val paused = pausedSince?.let { clock() - it } ?: 0
        val volume = runCatching { device()?.volume }.getOrNull()
        val now = clock()
        val ongoing = pauseStartedAt
        val id = trackId
        return context?.copy(
            behaviour = behaviour.copy(
                endPositionMs = positionMs,
                pausedMs = behaviour.pausedMs + paused,
                volumeEnd = volume,
                pausedAtEnd = ongoing != null,
                lastPauseMs = (if (ongoing != null) now - ongoing else lastPauseMs).takeIf { it > 0 },
                heardSinceResumeMs = if (ongoing != null) null else listenedAtResume?.let { listenedMs - it },
                systemPause = if (ongoing != null) pauseBySystem else lastPauseBySystem,
                addedToPlaylist = id != null && interactions?.addedToPlaylist(id, startedAt) == true,
                curating = interactions?.curating(now) == true,
            ),
        )
    }

    /** How you got from queue index [from] to [to]: the next song, the one before, or a jump. */
    private fun endReasonFor(from: Int, to: Int): EndReason {
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        if (from !in 0 until timeline.windowCount) return EndReason.Jump
        return when (to) {
            timeline.getNextWindowIndex(from, Player.REPEAT_MODE_ALL, shuffle) -> EndReason.Next
            timeline.getPreviousWindowIndex(from, Player.REPEAT_MODE_ALL, shuffle) -> EndReason.Previous
            else -> EndReason.Jump
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val HANDOVER_MIN_POSITION_MS = 5_000L

        /** Matches the server's listening sessions. */
        const val SESSION_GAP_MS = 30 * 60_000L

        /** Pauses the phone makes: losing audio focus for good, or headphones unplugged. */
        val SYSTEM_PAUSES = setOf(
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
    }
}
