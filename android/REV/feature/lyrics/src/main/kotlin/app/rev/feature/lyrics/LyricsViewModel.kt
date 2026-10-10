package app.rev.feature.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rev.domain.model.Lyrics
import app.rev.domain.model.LyricsSync
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LyricsRepository
import app.rev.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface LyricsUiState {
    data object NothingPlaying : LyricsUiState
    data object Loading : LyricsUiState
    data class Loaded(val track: Track, val lyrics: Lyrics) : LyricsUiState
    data class Failed(val track: Track, val error: LibraryError) : LyricsUiState
}

/**
 * Lyrics for whatever is playing, and which line is current. Position comes from the player
 * (updated a few times a second while playing), so highlighting follows real playback — seeking,
 * buffering and pausing included — without a timer of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val playback: PlaybackController,
    private val lyrics: LyricsRepository,
) : ViewModel() {

    /** Bumped to reload after a failure. */
    private val attempt = MutableStateFlow(0)

    init {
        playback.connect()
    }

    val state: StateFlow<LyricsUiState> =
        combine(playback.state.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id }, attempt) { track, _ -> track }
            .flatMapLatest { track -> track?.let(::load) ?: flowOf(LyricsUiState.NothingPlaying) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUiState.Loading)

    /** Index of the line being sung, or -1 (before the first line, or not synced). */
    val currentLine: StateFlow<Int> =
        combine(state, playback.progress) { s, progress ->
            val synced = ((s as? LyricsUiState.Loaded)?.lyrics as? Lyrics.Synced) ?: return@combine -1
            LyricsSync.currentLine(synced.lines, progress.positionMs)
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    fun retry() {
        attempt.value++
    }

    private fun load(track: Track): Flow<LyricsUiState> = flow {
        emit(LyricsUiState.Loading)
        emit(
            when (val result = lyrics.lyrics(track.id)) {
                is Result.Ok -> LyricsUiState.Loaded(track, result.value)
                is Result.Err -> LyricsUiState.Failed(track, result.error)
            },
        )
    }
}
