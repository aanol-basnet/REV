package app.rev.domain.taste

import app.rev.domain.model.Track
import app.rev.domain.playback.Autoplay

/** What kind of queue is playing, which decides what skipping in it means. */
sealed interface QueueShape {
    /** Whole albums played in order. */
    data object Album : QueueShape

    /** One artist's songs, such as their top songs, in order or shuffled. */
    data class Artist(val artistId: String) : QueueShape

    /** Anything else: a playlist, a mix, a radio, everything on shuffle. */
    data object Mixed : QueueShape

    companion object {
        fun of(queue: List<Track>, shuffle: Boolean): QueueShape = when (val plan = Autoplay.plan(queue)) {
            is Autoplay.Plan.NextAlbum -> when {
                !shuffle -> Album
                // A shuffled album, or albums by one artist.
                else -> queue.mapNotNull { it.artistId }.distinct().singleOrNull()?.let(::Artist) ?: Mixed
            }
            is Autoplay.Plan.Artist -> Artist(plan.artistId)
            else -> Mixed
        }
    }
}

/** Where the songs that go into the queue come from. */
sealed interface CandidateRequest {
    /** Songs not to pick: already in the queue. */
    val exclude: List<String>

    /** Songs like [seed]: its artist, similar artists, its genre. */
    data class LikeTrack(val seed: Track, val avoidArtists: List<String>, override val exclude: List<String>) : CandidateRequest

    /** More by one artist, from albums other than [excludeAlbums]. */
    data class ArtistSongs(
        val artistId: String,
        val genres: List<String>,
        val excludeAlbums: List<String>,
        override val exclude: List<String>,
    ) : CandidateRequest

    /** A whole album to play next, in order. */
    data class NextAlbum(
        val albumId: String,
        val artistId: String?,
        val genre: String?,
        val excludeAlbums: List<String>,
        override val exclude: List<String>,
    ) : CandidateRequest

    /** Songs from [artistIds], artists like them and [genres], never by [avoidArtists]. */
    data class Mood(
        val artistIds: List<String>,
        val genres: List<String>,
        val avoidArtists: List<String>,
        override val exclude: List<String>,
    ) : CandidateRequest
}

/** Why the queue changed; shown in the app. */
enum class QueueChange {
    /** The rest of the queue reordered: songs like the ones you skipped moved back. */
    Reshuffled,

    /** A run of skips noticed a pattern (an artist or genre) and moved those songs back. */
    MoodShift,

    /** Songs like the one you skipped to and stayed on. */
    MoreLikeThis,

    /** Another album by the same artist, after skipping through one. */
    OtherAlbum,

    /** Similar artists, after skipping through one artist's songs. */
    OtherArtist,

    /** The next album, after skipping through most of this one. */
    NextAlbum,

    /** Something different, after skipping through everything. */
    FreshStart,
}

sealed interface Move {
    val change: QueueChange

    /** Reorder the songs after the current one by how well they fit. */
    data class Reorder(override val change: QueueChange) : Move

    /** Replace the songs after the current one with new ones. */
    data class Replace(val request: CandidateRequest, override val change: QueueChange) : Move
}

/** What just happened that might change the queue. */
sealed interface Trigger {
    /** Another early skip; [streak] is every early skip in a row so far, oldest first, including this one. */
    data class Skipped(val streak: List<Track>) : Trigger

    /** You stayed on [track] after skipping your way to it ([streak]: the skips before). */
    data class Stayed(val track: Track, val streak: List<Track>) : Trigger

    /** You pressed "More like this" on [track]. */
    data class AskedForMore(val track: Track, val streak: List<Track>) : Trigger
}

/**
 * The smart queue's rules: reads what skipping means in the queue that's playing and decides how
 * to change what comes next. Never touches the song that's playing or what was already played.
 *
 * - **Shuffle or a playlist:** three skips reshuffle the rest by mood; on shuffle, when what you've
 *   been skipping (an artist or genre, twice) is coming up, those songs move back; six skips start fresh from what you did
 *   like this session (or what you usually play at this time of day).
 * - **One artist:** three skips mostly from one album switch to their other albums; skips across
 *   albums switch to similar artists.
 * - **An album in order:** skipping to a song is picking it, so only four skips in a row line up
 *   the next album, and eight start fresh.
 * - **Staying** on a song after skips: songs like it (more of the artist's songs like it, in an
 *   artist's queue), except in an album.
 * - **More like this** (the button): the same, anywhere.
 */
class QueueDirector(private val hour: () -> Int = { 12 }) {

    fun decide(
        trigger: Trigger,
        shape: QueueShape,
        queue: List<Track>,
        current: Track,
        upcoming: List<Track>,
        session: SessionTaste,
        profile: TasteProfile,
        movedThisStreak: Boolean,
        shuffle: Boolean,
    ): Move? {
        val exclude = queue.map { it.id }.distinct().takeLast(MAX_EXCLUDED)
        return when (trigger) {
            is Trigger.Skipped -> skipped(trigger.streak, shape, queue, current, upcoming, session, profile, exclude, shuffle && !movedThisStreak)
            is Trigger.Stayed -> if (trigger.streak.size >= STREAK || movedThisStreak) {
                stayed(trigger.track, trigger.streak, shape, exclude)
            } else {
                null
            }
            // Asked for, so even in an album.
            is Trigger.AskedForMore -> stayed(trigger.track, trigger.streak, if (shape == QueueShape.Album) QueueShape.Mixed else shape, exclude)
        }
    }

    private fun skipped(
        streak: List<Track>,
        shape: QueueShape,
        queue: List<Track>,
        current: Track,
        upcoming: List<Track>,
        session: SessionTaste,
        profile: TasteProfile,
        exclude: List<String>,
        canShift: Boolean,
    ): Move? {
        val n = streak.size
        return when (shape) {
            QueueShape.Album -> when (n) {
                ALBUM_STREAK -> current.albumId?.let { albumId ->
                    Move.Replace(
                        CandidateRequest.NextAlbum(albumId, current.artistId, current.genre, queue.mapNotNull { it.albumId }.distinct(), exclude),
                        QueueChange.NextAlbum,
                    )
                }
                2 * ALBUM_STREAK -> Move.Replace(mood(streak, session, profile, current, exclude), QueueChange.FreshStart)
                else -> null
            }
            is QueueShape.Artist -> when (n) {
                STREAK -> {
                    val albums = streak.mapNotNull { it.albumId }.groupingBy { it }.eachCount()
                    val tiredOf = albums.filterValues { it >= 2 }.keys
                    if (tiredOf.isNotEmpty()) {
                        Move.Replace(
                            CandidateRequest.ArtistSongs(shape.artistId, genres(streak + current), tiredOf.toList(), exclude),
                            QueueChange.OtherAlbum,
                        )
                    } else {
                        otherArtist(shape.artistId, streak, current, exclude)
                    }
                }
                2 * STREAK -> otherArtist(shape.artistId, streak, current, exclude)
                else -> null
            }
            QueueShape.Mixed -> when {
                n == STREAK -> Move.Reorder(QueueChange.Reshuffled)
                n == 2 * STREAK -> Move.Replace(mood(streak, session, profile, current, exclude), QueueChange.FreshStart)
                n < STREAK && canShift && moodShift(session, upcoming) -> Move.Reorder(QueueChange.MoodShift)
                else -> null
            }
        }
    }

    private fun stayed(track: Track, streak: List<Track>, shape: QueueShape, exclude: List<String>): Move? {
        val avoid = streak.mapNotNull { it.artistId }.distinct() - setOfNotNull(track.artistId)
        return when (shape) {
            QueueShape.Album -> null
            is QueueShape.Artist -> if (track.artistId == shape.artistId) {
                // The rest of their songs, ranked so ones like this (album, era) come first.
                val skippedAlbums = streak.mapNotNull { it.albumId }.toSet() - setOfNotNull(track.albumId)
                Move.Replace(
                    CandidateRequest.ArtistSongs(shape.artistId, genres(listOf(track)), skippedAlbums.toList(), exclude),
                    QueueChange.MoreLikeThis,
                )
            } else {
                Move.Replace(CandidateRequest.LikeTrack(track, avoid, exclude), QueueChange.MoreLikeThis)
            }
            QueueShape.Mixed -> Move.Replace(CandidateRequest.LikeTrack(track, avoid, exclude), QueueChange.MoreLikeThis)
        }
    }

    private fun otherArtist(artistId: String, streak: List<Track>, current: Track, exclude: List<String>) = Move.Replace(
        CandidateRequest.Mood(listOf(artistId), genres(streak + current), avoidArtists = listOf(artistId), exclude = exclude),
        QueueChange.OtherArtist,
    )

    /**
     * Something different: what you did like this session, else what you usually play at this
     * time of day, else your favorite artists; never the artists you just skipped.
     */
    private fun mood(streak: List<Track>, session: SessionTaste, profile: TasteProfile, current: Track, exclude: List<String>): CandidateRequest.Mood {
        val avoid = (streak.mapNotNull { it.artistId } + session.artists.filterValues { it < AVOID }.keys).distinct()
        fun Map<String, Double>.best(n: Int) = filterValues { it > LIKED }.entries.sortedByDescending { it.value }.map { it.key }.take(n)
        val daypart = profile.daypart(hour())
        val artists = session.artists.best(3).ifEmpty { daypart?.artists?.best(3).orEmpty() }.ifEmpty { profile.artists.best(3) } - avoid.toSet()
        val genres = session.genres.best(2).ifEmpty { daypart?.genres?.best(2).orEmpty() }.ifEmpty { profile.genres.best(2) }
            .ifEmpty { genres(listOf(current)) }
        return CandidateRequest.Mood(artists, genres, avoid, exclude)
    }

    /** Something you keep skipping (two recent skips, no plays) is coming up next. */
    private fun moodShift(session: SessionTaste, upcoming: List<Track>): Boolean = upcoming.take(LOOK_AHEAD).any {
        session.artist(it.artistId) < SKIPPING || session.genre(it.genre) < SKIPPING
    }

    private fun genres(tracks: List<Track>): List<String> =
        tracks.mapNotNull { it.genre?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(2)

    companion object {
        /** Early skips in a row before the queue reacts. */
        const val STREAK = 3
        const val ALBUM_STREAK = 4
        private const val LOOK_AHEAD = 3
        private const val MAX_EXCLUDED = 1000

        /** Session scores: about two recent skips with no plays, one, and liked. */
        private const val SKIPPING = -0.75
        private const val AVOID = -0.5
        private const val LIKED = 0.2
    }
}
