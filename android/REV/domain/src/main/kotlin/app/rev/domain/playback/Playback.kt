package app.rev.domain.playback

import app.rev.domain.model.Track

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
    suspend fun played(trackId: String, startedAt: Long)

    /** Moved on to another track after [listenedMs], before it counted as a play. */
    suspend fun skipped(trackId: String, startedAt: Long, listenedMs: Long)
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
