package app.rev.data.stats

import app.rev.domain.learning.InteractionKind
import app.rev.domain.learning.RecentInteractions
import app.rev.domain.playback.FavoriteEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The interaction log: recent ones in memory for reading listens, every one queued for the server
 * ([ListenQueue]), so they reach it even when recorded offline. Favorites are picked up from
 * [FavoriteEvents], wherever they're changed.
 */
class InteractionLogImpl(
    private val queue: ListenQueue,
    private val scope: CoroutineScope,
    favorites: FavoriteEvents,
    clock: () -> Long = System::currentTimeMillis,
) : RecentInteractions(clock, onRecord = { events -> scope.launch { runCatching { queue.addInteractions(events) } } }) {
    init {
        scope.launch {
            favorites.changes.collect { (id, favorite) ->
                record(if (favorite) InteractionKind.Favorite else InteractionKind.Unfavorite, listOf(id))
            }
        }
    }
}
