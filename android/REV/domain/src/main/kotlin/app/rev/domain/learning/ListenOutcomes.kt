package app.rev.domain.learning

/**
 * How a listen went, beyond "play" or "skip": the phone works it out as each song ends, learns
 * from it, and sends it with the listen. The server's copy (server/src/stats/outcomes.ts) must
 * agree: both are checked against shared/model/outcome-fixtures.json.
 *
 * [label]: what learning makes of it, skipped (1) or not (0), null when there's nothing to learn
 * (it wasn't about the song); [weight]: how much it counts.
 */
enum class ListenOutcome(val wire: String, val label: Int?, val weight: Double) {
    /** Added to a playlist while it played. */
    Saved("saved", 0, 1.5),

    /** Went back to it (previous) and listened. */
    Replayed("replayed", 0, 1.5),

    /** Played to the end. */
    Completed("completed", 0, 1.0),

    /** Counted as a play, then you moved on with some of it left: skipping a long outro. */
    Outro("outro", 0, 1.0),

    /** Counted as a play; how it ended isn't known (yet). */
    Played("played", 0, 1.0),

    /** Left partway, after the first half-minute: tired of it. */
    Bored("bored", 1, 0.5),

    /** Left within the first half-minute: not what you wanted. */
    Rejected("rejected", 1, 1.0),

    /** Left while paused, or just after a long pause or one the phone made (a call, unplugged headphones). */
    Interrupted("interrupted", null, 0.0),

    /** Left quickly while putting a playlist together: looking for songs, not judging them. */
    Browsing("browsing", null, 0.0),

    /** Went back to the song before: not a verdict on this one. */
    WentBack("went_back", null, 0.0),
    ;

    val skipped: Boolean get() = label == 1
    val learnable: Boolean get() = label != null
}

/** What's known about a listen when it ends (see [ListenOutcomes.classify]). */
data class ListenFacts(
    /** It was heard long enough to count as a play. */
    val counted: Boolean,
    /** 0 when unknown. */
    val durationMs: Long,
    /** Actually heard (not counting pauses or seeking). */
    val heardMs: Long,
    val endPositionMs: Long?,
    /** next, previous, jump, new_queue, stopped or ended (played to the end); null while it's playing. */
    val endReason: String?,
    /** Paused at the moment you left it. */
    val pausedAtEnd: Boolean = false,
    /** The most recent pause's length (up to leaving, if still paused); 0 for none. */
    val lastPauseMs: Long = 0,
    /** Heard after that pause ended; null with no pause or when still paused. */
    val heardSinceResumeMs: Long? = null,
    /** That pause was the phone's doing: a call, unplugged headphones, another app's sound. */
    val systemPause: Boolean = false,
    /** How the song began: previous, next, jump, auto or new_queue. */
    val arrivedBy: String? = null,
    val addedToPlaylist: Boolean = false,
    /** Songs were being added to a playlist lately. */
    val curating: Boolean = false,
)

object ListenOutcomes {
    /** A skip within this long (or half the song, if shorter) is a rejection. */
    private const val QUICK_MS = 30_000L

    /** Leaving a counted play with more than this left is skipping the outro, not finishing it. */
    private const val OUTRO_LEFT_MS = 15_000L

    /** Leaving while paused this long was an interruption. */
    private const val PAUSED_LEAVE_MS = 20_000L

    /** A pause this long, then leaving soon after resuming, was an interruption. */
    private const val LONG_PAUSE_MS = 60_000L
    private const val RESUME_GRACE_MS = 15_000L

    /** Songs added to a playlist this recently mean you're putting one together. */
    const val CURATING_MS = 10 * 60_000L

    fun classify(f: ListenFacts): ListenOutcome {
        if (f.addedToPlaylist) return ListenOutcome.Saved
        if (f.counted) {
            if (f.arrivedBy == "previous") return ListenOutcome.Replayed
            if (f.endReason == "ended") return ListenOutcome.Completed
            if (f.endReason != null && f.endReason != "stopped" && f.endPositionMs != null && f.durationMs > 0) {
                return if (f.durationMs - f.endPositionMs > OUTRO_LEFT_MS) ListenOutcome.Outro else ListenOutcome.Completed
            }
            return ListenOutcome.Played
        }
        val resumedJustNow = f.heardSinceResumeMs != null && f.heardSinceResumeMs < RESUME_GRACE_MS
        val interrupted = (f.pausedAtEnd && f.lastPauseMs >= PAUSED_LEAVE_MS) ||
            (f.lastPauseMs >= LONG_PAUSE_MS && resumedJustNow) ||
            (f.systemPause && (f.pausedAtEnd || resumedJustNow)) ||
            (f.endReason == "stopped" && f.pausedAtEnd)
        if (interrupted) return ListenOutcome.Interrupted
        if (f.endReason == "previous") return ListenOutcome.WentBack
        val quickMs = if (f.durationMs > 0) minOf(QUICK_MS.toDouble(), f.durationMs / 2.0) else QUICK_MS.toDouble()
        if (f.heardMs < quickMs) return if (f.curating) ListenOutcome.Browsing else ListenOutcome.Rejected
        return ListenOutcome.Bored
    }

    fun fromWire(wire: String?): ListenOutcome? = ListenOutcome.entries.firstOrNull { it.wire == wire }
}
