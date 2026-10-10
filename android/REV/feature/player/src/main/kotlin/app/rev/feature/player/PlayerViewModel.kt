package app.rev.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.FavoriteKind
import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.playback.PlaybackController
import app.rev.playback.PlaybackProgress
import app.rev.playback.PlayerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.rev.core.ui.components.ModeControls
import app.rev.domain.modes.ModeRepository

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackController,
    private val library: LibraryRepository,
    modes: ModeRepository,
) : ViewModel() {
    val mode = ModeControls(viewModelScope, modes, playback)

    val state: StateFlow<PlayerState> = playback.state
    val progress: StateFlow<PlaybackProgress> = playback.progress

    /** Favorite toggles made while this screen is open, until the queue item reflects them. */
    private val favoriteOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val isFavorite: StateFlow<Boolean> = combine(state, favoriteOverrides) { s, overrides ->
        s.current?.let { overrides[it.id] ?: it.starred } ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _errors = Channel<LibraryError>(Channel.BUFFERED)
    val errors: Flow<LibraryError> = _errors.receiveAsFlow()

    init {
        playback.connect()
    }

    fun togglePlayPause() = playback.togglePlayPause()
    fun next() = playback.next()
    fun notNow() = playback.notNow()
    fun moreLikeThis() = playback.moreLikeThis()
    fun previous() = playback.previous()
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)
    fun toggleShuffle() = playback.toggleShuffle()
    fun cycleRepeat() = playback.cycleRepeat()
    fun skipTo(index: Int) = playback.skipTo(index)
    fun removeAt(index: Int) = playback.removeAt(index)
    fun move(from: Int, to: Int) = playback.move(from, to)
    fun clearQueue() = playback.clearQueue()

    fun setFavorite(favorite: Boolean) {
        val track = state.value.current ?: return
        favoriteOverrides.update { it + (track.id to favorite) }
        viewModelScope.launch {
            val result = library.setFavorite(FavoriteKind.Track, track.id, favorite)
            if (result is Result.Err) {
                favoriteOverrides.update { it + (track.id to !favorite) }
                _errors.send(result.error)
            }
        }
    }
}
