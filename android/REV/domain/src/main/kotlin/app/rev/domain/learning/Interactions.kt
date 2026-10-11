package app.rev.domain.learning

/**
 * Things you do with a song besides listening to it, each a hint about what you like: kept on the
 * phone until the server has them, where they feed the taste profile, and used right away to read
 * listens (a quick skip while you're adding songs to a playlist is browsing, not a rejection).
 */
enum class InteractionKind(val wire: String) {
    PlaylistAdd("playlist_add"),
    PlayNext("play_next"),
    QueueAdd("queue_add"),

    /** Taken out of the queue before it played. */
    QueueRemove("queue_remove"),
    Favorite("favorite"),
    Unfavorite("unfavorite"),

    /** Put on repeat-one while it played. */
    RepeatOne("repeat_one"),

    /** Opened its lyrics. */
    Lyrics("lyrics"),
}

data class Interaction(val kind: InteractionKind, val trackId: String, val at: Long)

interface InteractionLog {
    fun record(kind: InteractionKind, trackIds: List<String>)

    /** [trackId] was added to a playlist at [since] or later. */
    fun addedToPlaylist(trackId: String, since: Long): Boolean

    /** You've been adding songs to playlists within [ListenOutcomes.CURATING_MS] of [now]. */
    fun curating(now: Long): Boolean
}

/** Remembers recent interactions in memory, for reading listens; [onRecord] persists and sends them. */
open class RecentInteractions(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecord: (List<Interaction>) -> Unit = {},
) : InteractionLog {
    private val recent = ArrayDeque<Interaction>()

    override fun record(kind: InteractionKind, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val now = clock()
        val events = trackIds.distinct().map { Interaction(kind, it, now) }
        synchronized(recent) {
            recent.addAll(events)
            while (recent.size > KEEP) recent.removeFirst()
        }
        onRecord(events)
    }

    override fun addedToPlaylist(trackId: String, since: Long): Boolean = synchronized(recent) {
        recent.any { it.kind == InteractionKind.PlaylistAdd && it.trackId == trackId && it.at >= since }
    }

    override fun curating(now: Long): Boolean = synchronized(recent) {
        recent.any { it.kind == InteractionKind.PlaylistAdd && now - it.at in 0..ListenOutcomes.CURATING_MS }
    }

    private companion object {
        const val KEEP = 500
    }
}
