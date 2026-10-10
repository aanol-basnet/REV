package app.rev.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.rev.domain.model.Track
import app.rev.domain.playback.AutoplayRepository
import app.rev.domain.taste.CandidateRequest
import app.rev.domain.taste.Candidates
import app.rev.domain.taste.ListeningSession
import app.rev.domain.taste.Move
import app.rev.domain.taste.Outcome
import app.rev.domain.taste.QueueChange
import app.rev.domain.taste.QueueDirector
import app.rev.domain.taste.QueueShape
import app.rev.domain.taste.TasteRanker
import app.rev.domain.taste.TasteRepository
import app.rev.domain.taste.TasteProfile
import app.rev.domain.taste.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A change the smart queue made, for the app to show: what changed, and the song it was about. */
data class QueueNote(val change: QueueChange, val title: String)

/**
 * The smart queue (Settings → Playback). Watches how each song ends (played, skipped early or
 * late, skipped to and stayed on) and records it in the [ListeningSession]; on a run of skips or a
 * song you stay on, asks the [QueueDirector] what that means and reorders or replaces what comes
 * after the current song, ranked by the session and your taste profile. Songs already played and
 * the current one are never touched. Nothing changes with repeat on or the setting off.
 */
@OptIn(UnstableApi::class)
internal class SmartQueue(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val autoplay: AutoplayRepository,
    private val session: ListeningSession,
    private val taste: TasteRepository,
    private val ranker: TasteRanker,
    private val director: QueueDirector,
    private val enabled: () -> Boolean,
    private val toItem: (Track, QueueChange) -> MediaItem,
    private val onChange: (QueueNote) -> Unit,
) : Player.Listener {
    /** Early skips in a row, oldest first. */
    private val streak = mutableListOf<Track>()
    private var movedThisStreak = false

    /** The current song was reached by skipping (or going back), so staying on it means something. */
    private var arrivedBySkip = false
    private var stayed = false

    /** "Not now" was pressed on the current song: leaving it is that, whenever it happens. */
    private var notNow = false
    private var watch: Job? = null
    private var work: Job? = null

    fun start() = player.addListener(this)

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
        val leftIndex = oldPosition.mediaItemIndex
        val left = if (leftIndex in 0 until player.mediaItemCount) player.getMediaItemAt(leftIndex).toTrack() else null
        watch?.cancel()
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            if (left != null && !stayed) session.record(left, Outcome.Played)
            endStreak()
            arrivedBySkip = false
        } else if (reason == Player.DISCONTINUITY_REASON_SEEK && left != null) {
            arrivedBySkip = true
            // Going back to an earlier song isn't a verdict on the one you left, and keeps the streak.
            if (playPosition(newPosition.mediaItemIndex) > playPosition(leftIndex)) {
                left(left, oldPosition.positionMs)
            }
        }
        stayed = false
        notNow = false
        watchCurrent()
    }

    /** The "Not now" button: an early skip whenever it's pressed, and a strong one. The caller then skips. */
    fun notNow() {
        val track = player.currentMediaItem?.toTrack() ?: return
        watch?.cancel()
        session.record(track, Outcome.NotNow)
        notNow = true
    }

    /** The "More like this" button: songs like this one next, even in an album or with the setting off. */
    fun moreLikeThis() {
        val track = player.currentMediaItem?.toTrack() ?: return
        watch?.cancel()
        stayed = true
        session.record(track, Outcome.MoreLikeThis)
        consider(Trigger.AskedForMore(track, streak.toList()), asked = true)
        endStreak()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A new queue starts afresh (the session remembers the mood).
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            watch?.cancel()
            endStreak()
            arrivedBySkip = false
            stayed = false
        }
    }

    private fun left(track: Track, positionMs: Long) {
        val outcome = when {
            notNow -> Outcome.NotNow
            stayed -> null
            isQuickSkip(track.durationMs, positionMs) -> Outcome.QuickSkip
            track.durationMs > 0 && positionMs < track.durationMs / 2 -> Outcome.LateSkip
            else -> Outcome.Played
        }
        // "Not now" was already recorded when pressed.
        if (outcome != null && outcome != Outcome.NotNow) session.record(track, outcome)
        if (outcome == Outcome.QuickSkip || outcome == Outcome.NotNow) {
            streak += track
            consider(Trigger.Skipped(streak.toList()))
        } else {
            endStreak()
        }
    }

    /** You switched listening mode: what's next is reordered for it (not an album played in order). */
    fun modeChanged() {
        if (!enabled() || player.repeatMode != Player.REPEAT_MODE_OFF || player.currentMediaItem == null) return
        val current = player.currentMediaItem?.toTrack() ?: return
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() }
        if (QueueShape.of(queue, player.shuffleModeEnabled) == QueueShape.Album) return
        work?.cancel()
        work = scope.launch {
            if (apply(Move.Reorder(QueueChange.ModeChanged), current, queue.size)) onChange(QueueNote(QueueChange.ModeChanged, current.title))
        }
    }

    /** "Play for this mode": a fresh mix fitted to the listening mode, replacing the queue. */
    fun playModeMix(modeName: String) {
        work?.cancel()
        work = scope.launch {
            val profile = runCatching { taste.profile() }.getOrDefault(TasteProfile())
            val recent = session.signals().map { it.track.id }
            val request = director.modeMix(session.taste(profile), profile, recent)
            val songs = ranker.rank(runCatching { autoplay.candidates(request) }.getOrDefault(emptyList())).take(Candidates.QUEUE_SIZE)
            if (songs.isEmpty()) return@launch
            player.setMediaItems(songs.map { toItem(it, QueueChange.ModeMix) })
            player.prepare()
            player.play()
            onChange(QueueNote(QueueChange.ModeMix, modeName))
        }
    }

    /** Once you've stayed on a song you skipped to for a while, it counts as what you wanted. */
    private fun watchCurrent() {
        if (!arrivedBySkip) return
        val id = player.currentMediaItem?.mediaId ?: return
        watch = scope.launch {
            while (player.currentMediaItem?.mediaId == id) {
                if (player.currentPosition >= SETTLE_MS) {
                    val track = player.currentMediaItem?.toTrack() ?: return@launch
                    stayed = true
                    session.record(track, Outcome.Stayed)
                    consider(Trigger.Stayed(track, streak.toList()))
                    endStreak()
                    return@launch
                }
                delay(CHECK_MS)
            }
        }
    }

    private fun endStreak() {
        streak.clear()
        movedThisStreak = false
    }

    private fun consider(trigger: Trigger, asked: Boolean = false) {
        if (!(enabled() || asked) || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val current = player.currentMediaItem?.toTrack() ?: return
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() }
        val upcoming = upcomingIndices().map { queue[it] }
        val moved = movedThisStreak
        // A newer decision replaces one still waiting for songs.
        work?.cancel()
        work = scope.launch {
            val profile = runCatching { taste.profile() }.getOrDefault(TasteProfile())
            val shape = QueueShape.of(queue, player.shuffleModeEnabled)
            val move = director.decide(
                trigger, shape, queue, current, upcoming, session.taste(profile), profile, moved, player.shuffleModeEnabled,
            ) ?: return@launch
            if (apply(move, current, queue.size)) {
                movedThisStreak = true
                onChange(QueueNote(move.change, current.title))
            }
        }
    }

    private suspend fun apply(move: Move, current: Track, queueSize: Int): Boolean {
        val unchanged = { player.currentMediaItem?.mediaId == current.id && player.mediaItemCount == queueSize }
        return when (move) {
            is Move.Reorder -> {
                val upcoming = upcomingIndices()
                if (upcoming.size < 2) return false
                val tracks = upcoming.associateWith { player.getMediaItemAt(it).toTrack() }
                val scorer = ranker.scorer()
                if (!unchanged()) return false
                val ranked = scorer.rank(upcoming.map { tracks.getValue(it) })
                // Same songs, new order; duplicates of one song keep their slots in turn.
                val slots = upcoming.groupBy { tracks.getValue(it).id }.mapValues { it.value.toMutableList() }
                reorder(ranked.map { slots.getValue(it.id).removeAt(0) })
                true
            }
            is Move.Replace -> {
                val found = runCatching { autoplay.candidates(move.request) }.getOrDefault(emptyList())
                val songs = when (move.request) {
                    is CandidateRequest.NextAlbum -> found
                    is CandidateRequest.ArtistSongs -> ranker.rank(found, spread = false).take(Candidates.QUEUE_SIZE)
                    else -> ranker.rank(found).take(Candidates.QUEUE_SIZE)
                }
                if (songs.isEmpty() || !unchanged()) return false
                replaceUpcoming(songs.map { toItem(it, move.change) })
                true
            }
        }
    }

    /** Puts the songs after the current one in [order] (queue indices). */
    private fun reorder(order: List<Int>) {
        val currentIndex = player.currentMediaItemIndex
        if (player.shuffleModeEnabled) {
            val timeline = player.currentTimeline
            val before = mutableListOf<Int>()
            var i = timeline.getFirstWindowIndex(true)
            while (i != C.INDEX_UNSET && i != currentIndex) {
                before += i
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            }
            player.setShuffleOrder(QueueShuffleOrder.of(before + currentIndex + order))
        } else {
            // In order, the songs after the current one are the rest of the queue.
            val base = currentIndex + 1
            val now = (base until player.mediaItemCount).toMutableList()
            order.forEachIndexed { k, wanted ->
                val from = now.indexOf(wanted)
                if (from != k) {
                    player.moveMediaItem(base + from, base + k)
                    now.add(k, now.removeAt(from))
                }
            }
        }
    }

    /** Swaps everything after the current song for [songs]; added first so the queue never looks finished. */
    private fun replaceUpcoming(songs: List<MediaItem>) {
        val upcoming = upcomingIndices()
        val at = player.endOfPlayOrder()
        player.addMediaItems(at, songs)
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

    companion object {
        /** How long you stay on a song you skipped to before it counts as what you wanted. */
        const val SETTLE_MS = 30_000L
        private const val QUICK_SKIP_MS = 30_000L
        private const val CHECK_MS = 1_000L

        /** Leaving within the first 30 seconds (or the first half of a short song). */
        fun isQuickSkip(durationMs: Long, positionMs: Long): Boolean {
            val limit = if (durationMs > 0) minOf(QUICK_SKIP_MS, durationMs / 2) else QUICK_SKIP_MS
            return positionMs < limit
        }
    }
}

/** How a [QueueNote] travels from the playback service to the app: the media session's extras. */
internal object QueueNotes {
    const val CHANGE = "app.rev.queue_note.change"
    const val TITLE = "app.rev.queue_note.title"
    const val AT = "app.rev.queue_note.at"

    fun read(extras: android.os.Bundle): Pair<QueueNote, Long>? {
        val change = extras.getString(CHANGE)?.let { name -> QueueChange.entries.firstOrNull { it.name == name } } ?: return null
        return QueueNote(change, extras.getString(TITLE).orEmpty()) to extras.getLong(AT)
    }
}

/** The feedback buttons, sent from the app, the notification or the lock screen to the playback service. */
object FeedbackCommands {
    val NOT_NOW = androidx.media3.session.SessionCommand("app.rev.feedback.NOT_NOW", android.os.Bundle.EMPTY)
    val MORE_LIKE_THIS = androidx.media3.session.SessionCommand("app.rev.feedback.MORE_LIKE_THIS", android.os.Bundle.EMPTY)

    /** Play a mix for the current listening mode. */
    val PLAY_MODE_MIX = androidx.media3.session.SessionCommand("app.rev.mode.PLAY_MIX", android.os.Bundle.EMPTY)
}
