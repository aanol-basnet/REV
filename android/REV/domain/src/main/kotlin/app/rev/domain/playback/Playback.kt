package app.rev.domain.playback

import app.rev.domain.model.Track
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Queue commands available to any screen that lists tracks. */
interface PlaybackQueue {
    /** Replaces the queue with [tracks] and starts playing at [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false)

    /** Inserts [tracks] right after the current track. */
    fun playNext(tracks: List<Track>)

    /** Appends [tracks] to the end of the queue. */
    fun addToQueue(tracks: List<Track>)
}

/** Where a track's audio comes from when it is about to play. */
sealed interface TrackSource {
    /** A file downloaded to this device; plays without a network. */
    data class Local(val path: String) : TrackSource

    /** Streamed from the REV server. */
    data class Remote(val url: String) : TrackSource
}

/**
 * Chooses a track's source at the moment it starts loading: a downloaded copy if present,
 * otherwise the server stream. Null when neither is possible (not downloaded and no server).
 * Called on a playback loader thread; may block briefly.
 */
interface TrackSourceResolver {
    fun resolve(trackId: String): TrackSource?
}

/** URLs for artwork shown outside the app UI (notification, lock screen, Bluetooth displays). */
interface ArtworkUrls {
    fun url(coverArtId: String, size: Int): String?
}

/**
 * Reports listening to the server: play counts and "recently played", and REV's listening log
 * that reports are built from. Plays and skips are kept on the device until the server has them.
 */
interface PlaybackReporter {
    suspend fun nowPlaying(trackId: String)
    suspend fun played(trackId: String, startedAt: Long, context: ListenContext? = null)

    /** Moved on to another track after [listenedMs], before it counted as a play. */
    suspend fun skipped(trackId: String, startedAt: Long, listenedMs: Long, context: ListenContext? = null)
}

/** How a song got into the queue. */
enum class QueueSource(val wire: String) {
    /** Part of a queue you started: an album, playlist, mix, search results. */
    Picked("picked"),

    /** Play next or Add to queue. */
    Queued("queued"),
    Autoplay("autoplay"),

    /** Added by the smart queue. */
    Smart("smart"),

    /** Back from the saved queue after the app restarted; how it got there is lost. */
    Restored("restored"),

    /** Chosen in the car (Android Auto). */
    Car("car"),
    Unknown("unknown"),
}

/** How you left a song before it counted as a play. */
enum class EndReason(val wire: String) { Next("next"), Previous("previous"), Jump("jump"), NewQueue("new_queue"), Stopped("stopped") }

enum class AudioOutput(val wire: String) { Speaker("speaker"), Headphones("headphones"), Bluetooth("bluetooth"), Car("car"), Cast("cast"), Other("other") }

/**
 * The situation a song played in, sent with each listen so the listening log can train the smart
 * queue (docs/training-data.md). Taken when the song starts; [endReason] is added for skips.
 */
data class ListenContext(
    val source: QueueSource,
    /** For [QueueSource.Smart]: what the smart queue did, e.g. "MoreLikeThis". */
    val smartChange: String? = null,
    /** album, artist or mixed. */
    val queueShape: String? = null,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    /** Place in the play order, from 0, and the queue's length. */
    val queuePosition: Int = 0,
    val queueLength: Int = 0,
    val previousTrackId: String? = null,
    /** play, skip or none (left too soon after a seek to say), for the song before. */
    val previousOutcome: String? = null,
    /** Songs started this session before this one, and how many of them were skipped. */
    val sessionPosition: Int = 0,
    val sessionSkips: Int = 0,
    val endReason: EndReason? = null,
    val output: AudioOutput = AudioOutput.Other,
    val utcOffsetMinutes: Int = 0,
    val favorite: Boolean = false,
    /** Where you were: the device and connection when the song started. */
    val device: DeviceSnapshot? = null,
    /** What you did while it played, up to the moment the listen was logged. */
    val behaviour: ListenBehaviour = ListenBehaviour(),
    /** How well the smart queue thought the song would fit when it started (its score), to check its guesses later. */
    val predictedScore: Double? = null,
)

/** The phone's situation when a song started. Names of Bluetooth devices help tell the car from earbuds. */
data class DeviceSnapshot(
    val output: AudioOutput,
    /** The audio device's name, e.g. a car kit or earbuds; null for the speaker. */
    val outputName: String? = null,
    /** wifi, cellular, ethernet or none. */
    val network: String? = null,
    val charging: Boolean? = null,
    val screenOn: Boolean? = null,
    /** Media volume, 0–1. */
    val volume: Double? = null,
)

/** What you did during a song: seeking around, pausing, the volume, liking it. */
data class ListenBehaviour(
    /** Where the song started (not 0 when resumed mid-song) and where you left it. */
    val startPositionMs: Long = 0,
    val endPositionMs: Long? = null,
    val seeksForward: Int = 0,
    val seeksBack: Int = 0,
    val seekedForwardMs: Long = 0,
    val seekedBackMs: Long = 0,
    val pauses: Int = 0,
    val pausedMs: Long = 0,
    /** Media volume when the listen was logged, 0–1. */
    val volumeEnd: Double? = null,
    /** Favorited (true) or unfavorited (false) while it played; null if neither. */
    val favoritedDuring: Boolean? = null,
)

/** Songs favorited or unfavorited, as it happens, so the listening log can note it for the song playing. */
class FavoriteEvents {
    private val events = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 16)
    val changes: SharedFlow<Pair<String, Boolean>> = events

    fun changed(trackId: String, favorite: Boolean) {
        events.tryEmit(trackId to favorite)
    }
}

enum class RepeatMode { Off, All, One }

/** What is persisted so the queue survives the app and service being killed. */
data class QueueSnapshot(
    val tracks: List<Track>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: RepeatMode,
)

interface QueueStore {
    suspend fun save(snapshot: QueueSnapshot)
    suspend fun load(): QueueSnapshot?
    suspend fun clear()
}

/**
 * When a play counts, following the common scrobbling rule: the track is longer than 30 seconds
 * and was listened to for half its length or four minutes, whichever comes first.
 */
object PlayCountPolicy {
    private const val MIN_TRACK_MS = 30_000L
    private const val MAX_REQUIRED_MS = 240_000L

    fun counts(durationMs: Long, listenedMs: Long): Boolean {
        if (durationMs in 1..MIN_TRACK_MS) return false
        val required = if (durationMs > 0) minOf(durationMs / 2, MAX_REQUIRED_MS) else MAX_REQUIRED_MS
        return listenedMs >= required
    }

    /**
     * Leaving a track after [listenedMs] is a skip when it was actually heard but not long enough
     * to count. Very short tracks never count as plays, so leaving them isn't a skip either.
     */
    fun isSkip(durationMs: Long, listenedMs: Long): Boolean =
        listenedMs > 0 && durationMs !in 1..MIN_TRACK_MS && !counts(durationMs, listenedMs)
}

/**
 * URLs a Cast receiver can load without REV's session token (media-only, time-limited). Null when
 * casting isn't set up on the server or no token has been obtained yet.
 */
interface CastLinks {
    fun streamUrl(trackId: String): String?
    fun artworkUrl(coverArtId: String): String?

    /** Gets a fresh token if the current one is missing or about to expire. */
    suspend fun refresh()
}
