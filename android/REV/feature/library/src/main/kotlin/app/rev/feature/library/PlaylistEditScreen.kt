package app.rev.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.rev.core.R as CoreR
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.dropTarget
import app.rev.core.ui.components.ErrorSnackbarEffect
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.icons.RevIcons
import app.rev.domain.model.PlaylistDetail
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A song in the editor; [key] tells apart two copies of the same song. */
data class EditableSong(val key: Int, val track: Track)

data class PlaylistEditState(
    val original: PlaylistDetail? = null,
    val name: String = "",
    val comment: String = "",
    val songs: List<EditableSong> = emptyList(),
    val busy: Boolean = false,
) {
    val nameChanged: Boolean get() = original != null && name.trim() != original.playlist.name
    val commentChanged: Boolean get() = original != null && comment.trim() != original.playlist.comment.orEmpty()
    val songsChanged: Boolean get() = original != null && songs.map { it.track.id } != original.tracks.map { it.id }
    val hasChanges: Boolean get() = nameChanged || commentChanged || songsChanged
    val canSave: Boolean get() = hasChanges && name.isNotBlank() && !busy
}

sealed interface PlaylistEditEvent {
    data object Saved : PlaylistEditEvent
    data object Deleted : PlaylistEditEvent
}

/**
 * Edits a copy of the playlist; nothing reaches the server until Save, which sends the new name,
 * description and song order together.
 */
@HiltViewModel
class PlaylistEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {
    private val id: String = savedStateHandle.requireArg(PlaylistEditRoute::id.name)
    private val _state = MutableStateFlow(PlaylistEditState())
    val state: StateFlow<PlaylistEditState> = _state.asStateFlow()
    private val _errors = Channel<LibraryError>(Channel.BUFFERED)
    val errors: Flow<LibraryError> = _errors.receiveAsFlow()
    private val _events = Channel<PlaylistEditEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistEditEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            // Start from the latest version, or what's cached when the server can't be reached.
            library.refreshPlaylist(id)
            library.observePlaylist(id).first()?.let(::load)
        }
    }

    private fun load(detail: PlaylistDetail) = _state.update {
        PlaylistEditState(
            original = detail,
            name = detail.playlist.name,
            comment = detail.playlist.comment.orEmpty(),
            songs = detail.tracks.mapIndexed { i, track -> EditableSong(i, track) },
        )
    }

    fun setName(name: String) = _state.update { it.copy(name = name.take(MAX_NAME)) }
    fun setComment(comment: String) = _state.update { it.copy(comment = comment.take(MAX_COMMENT)) }
    fun remove(key: Int) = _state.update { s -> s.copy(songs = s.songs.filterNot { it.key == key }) }

    fun move(from: Int, to: Int) = _state.update { s ->
        if (from !in s.songs.indices || to !in s.songs.indices || from == to) return@update s
        s.copy(songs = s.songs.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val details = if (s.nameChanged || s.commentChanged) {
                playlists.update(
                    id,
                    name = s.name.trim().takeIf { s.nameChanged },
                    comment = s.comment.trim().takeIf { s.commentChanged },
                )
            } else {
                Result.Ok(Unit)
            }
            val songs = if (details is Result.Ok && s.songsChanged) playlists.setTracks(id, s.songs.map { it.track.id }) else details
            when (songs) {
                is Result.Ok -> _events.send(PlaylistEditEvent.Saved)
                is Result.Err -> {
                    _state.update { it.copy(busy = false) }
                    _errors.send(songs.error)
                }
            }
        }
    }

    fun delete() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = playlists.delete(id)) {
                is Result.Ok -> _events.send(PlaylistEditEvent.Deleted)
                is Result.Err -> {
                    _state.update { it.copy(busy = false) }
                    _errors.send(result.error)
                }
            }
        }
    }

    private companion object {
        const val MAX_COMMENT = 1000
    }
}

private val ROW_HEIGHT = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistEditScreen(
    onClose: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: PlaylistEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ErrorSnackbarEffect(viewModel.errors, snackbar)
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                PlaylistEditEvent.Saved -> onClose()
                PlaylistEditEvent.Deleted -> onDeleted()
            }
        }
    }
    val close = { if (state.hasChanges) confirmDiscard = true else onClose() }
    BackHandler(enabled = state.hasChanges) { confirmDiscard = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.playlist_edit)) },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(CoreR.string.navigate_back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave) { Text(stringResource(R.string.playlist_save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.original == null) {
                LoadingView()
                return@Box
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            EditList(state, viewModel, onDelete = { confirmDelete = true })
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.playlist_discard_title)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.playlist_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.playlist_keep_editing)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.playlist_delete_title, state.original?.playlist?.name.orEmpty())) },
            text = { Text(stringResource(R.string.playlist_delete_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) {
                    Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.playlist_cancel)) } },
        )
    }
}

@Composable
private fun EditList(state: PlaylistEditState, viewModel: PlaylistEditViewModel, onDelete: () -> Unit) {
    // Drag handlers outlive recompositions; they read the songs as they are when the drag ends.
    val songs by rememberUpdatedState(state.songs)
    val listState = rememberLazyListState()
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "fields") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.playlist_name)) },
                    isError = state.name.isBlank(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.comment,
                    onValueChange = viewModel::setComment,
                    label = { Text(stringResource(R.string.playlist_description)) },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    pluralStringResource(CoreR.plurals.track_count, state.songs.size, state.songs.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.songs.size > 1) {
                    Text(
                        stringResource(R.string.playlist_edit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.songs.isEmpty()) {
            item(key = "empty") { CenteredMessage(stringResource(R.string.playlist_edit_empty), Modifier.height(140.dp)) }
        }
        items(state.songs, key = { song -> song.key }) { song ->
            val isDragging = dragging == song.key
            Row(
                Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .fillMaxWidth()
                    .heightIn(min = ROW_HEIGHT)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    RevIcons.DragHandle,
                    contentDescription = stringResource(R.string.playlist_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .pointerInput(song.key) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = song.key
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                },
                                onDragEnd = {
                                    val from = songs.indexOfFirst { it.key == song.key }
                                    val target = listState.dropTarget(song.key, dragOffset) { key -> songs.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
                                    if (from >= 0 && target != null) viewModel.move(from, target)
                                    dragging = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    dragging = null
                                    dragOffset = 0f
                                },
                            )
                        },
                )
                Spacer(Modifier.width(12.dp))
                Artwork(song.track.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { viewModel.remove(song.key) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.playlist_remove_song, song.track.title))
                }
            }
        }
        item(key = "delete") {
            TextButton(onClick = onDelete, enabled = !state.busy, modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp)) {
                Text(stringResource(R.string.playlist_delete_action), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
