package app.rev.feature.library

import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Toggles favorites and reports failures as one-off messages (the repository already reverted). */
internal class FavoriteActions(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
) {
    private val _errors = Channel<LibraryError>(Channel.BUFFERED)
    val errors: Flow<LibraryError> = _errors.receiveAsFlow()

    fun set(kind: FavoriteKind, id: String, favorite: Boolean, onSuccess: () -> Unit = {}) {
        scope.launch {
            when (val result = library.setFavorite(kind, id, favorite)) {
                is Result.Ok -> onSuccess()
                is Result.Err -> _errors.send(result.error)
            }
        }
    }
}

internal enum class QueueMessage { PlayingNext, AddedToQueue }

/** Queue commands from library screens, with a confirmation for the ones that don't start playback. */
internal class QueueActions(private val queue: PlaybackQueue) {
    private val _messages = Channel<QueueMessage>(Channel.BUFFERED)
    val messages: Flow<QueueMessage> = _messages.receiveAsFlow()

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) =
        queue.play(tracks, startIndex, shuffle)

    fun playNext(tracks: List<Track>) {
        queue.playNext(tracks)
        _messages.trySend(QueueMessage.PlayingNext)
    }

    fun addToQueue(tracks: List<Track>) {
        queue.addToQueue(tracks)
        _messages.trySend(QueueMessage.AddedToQueue)
    }
}
