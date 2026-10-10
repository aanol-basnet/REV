package app.rev.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.rev.core.R as CoreR
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.text
import app.rev.domain.model.Playlist
import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What happened after picking a playlist; shown as a snackbar on the screen that opened the sheet. */
sealed interface PlaylistMessage {
    data class Added(val playlist: String, val added: Int, val skipped: Int) : PlaylistMessage
    data class Created(val playlist: String, val songs: Int) : PlaylistMessage
    data class Failed(val error: LibraryError) : PlaylistMessage
}

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {
    /**
     * Every playlist, ones songs can be added to first. Read-only ones (other Navidrome users')
     * are still listed, so it's clear why they can't be picked.
     */
    val choices: StateFlow<List<Playlist>> = library.observePlaylists()
        .map { all -> all.sortedByDescending { it.canEdit } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<PlaylistMessage>(Channel.BUFFERED)
    val messages: Flow<PlaylistMessage> = _messages.receiveAsFlow()

    /** Brings the list up to date when the sheet opens. */
    fun refresh() {
        viewModelScope.launch { library.refreshPlaylists() }
    }

    fun add(playlist: Playlist, trackIds: List<String>) {
        viewModelScope.launch {
            _messages.send(
                when (val result = playlists.add(playlist.id, trackIds)) {
                    is Result.Ok -> PlaylistMessage.Added(playlist.name, result.value.added, result.value.skipped)
                    is Result.Err -> PlaylistMessage.Failed(result.error)
                },
            )
        }
    }

    fun create(name: String, trackIds: List<String>) {
        viewModelScope.launch {
            _messages.send(
                when (val result = playlists.create(name.trim(), trackIds)) {
                    is Result.Ok -> PlaylistMessage.Created(result.value.playlist.name, trackIds.size)
                    is Result.Err -> PlaylistMessage.Failed(result.error)
                },
            )
        }
    }
}

/** Opens the "Add to playlist" sheet from anywhere on a screen. */
@Stable
class AddToPlaylistState {
    internal var trackIds by mutableStateOf<List<String>?>(null)

    fun open(trackIds: List<String>) {
        if (trackIds.isNotEmpty()) this.trackIds = trackIds
    }
}

@Composable
fun rememberAddToPlaylist(): AddToPlaylistState = remember { AddToPlaylistState() }

/** Place once per screen: shows the sheet when [state] is opened, and the outcome in [snackbar]. */
@Composable
fun AddToPlaylistHost(
    state: AddToPlaylistState,
    snackbar: SnackbarHostState,
    viewModel: AddToPlaylistViewModel = hiltViewModel(),
) {
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(
                when (message) {
                    is PlaylistMessage.Added -> when {
                        message.added == 0 -> resources.getString(R.string.playlist_already_in, message.playlist)
                        message.skipped > 0 -> resources.getQuantityString(
                            R.plurals.playlist_added_some,
                            message.added,
                            message.added,
                            message.playlist,
                            message.skipped,
                        )
                        else -> resources.getQuantityString(R.plurals.playlist_added, message.added, message.added, message.playlist)
                    }
                    is PlaylistMessage.Created -> resources.getString(R.string.playlist_created, message.playlist)
                    is PlaylistMessage.Failed -> message.error.text(resources)
                },
            )
        }
    }
    val trackIds = state.trackIds ?: return
    val playlists by viewModel.choices.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    AddToPlaylistSheet(
        songCount = trackIds.size,
        playlists = playlists,
        onPick = { playlist ->
            viewModel.add(playlist, trackIds)
            state.trackIds = null
        },
        onCreate = { name ->
            viewModel.create(name, trackIds)
            state.trackIds = null
        },
        onDismiss = { state.trackIds = null },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(
    songCount: Int,
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                pluralStringResource(R.plurals.playlist_add_title, songCount, songCount),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (creating) {
                NewPlaylistField(onCreate = onCreate, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            } else {
                PickerRow(onClick = { creating = true }) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null) }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.playlist_new), style = MaterialTheme.typography.bodyLarge)
                }
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    PickerRow(onClick = { onPick(playlist) }, enabled = playlist.canEdit) {
                        Artwork(playlist.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (playlist.canEdit) {
                                    pluralStringResource(CoreR.plurals.track_count, playlist.trackCount, playlist.trackCount)
                                } else {
                                    playlist.owner?.let { stringResource(R.string.playlist_pick_read_only_owner, it) }
                                        ?: stringResource(R.string.playlist_pick_read_only)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PickerRow(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** A name field and a Create button; used by the sheet and the Playlists tab. */
@Composable
internal fun NewPlaylistField(onCreate: (String) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    val valid = name.isNotBlank()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME) },
            label = { Text(stringResource(R.string.playlist_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onCreate(name) }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onCreate(name) }, enabled = valid) { Text(stringResource(R.string.playlist_create)) }
    }
}

internal const val MAX_NAME = 200
