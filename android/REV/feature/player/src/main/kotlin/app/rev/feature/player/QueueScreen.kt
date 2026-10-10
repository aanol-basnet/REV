package app.rev.feature.player

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import app.rev.feature.library.AddToPlaylistHost
import app.rev.feature.library.rememberAddToPlaylist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.R as CoreR
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.dropTarget
import app.rev.core.ui.icons.RevIcons
import app.rev.playback.QueueEntry

private val ROW_HEIGHT = 64.dp

private val QueueEntry.key: String get() = "$index:${track.id}"

@Composable
internal fun QueueScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val addToPlaylist = rememberAddToPlaylist()
    AddToPlaylistHost(addToPlaylist, snackbar)

    DetailScaffoldWithAction(
        title = stringResource(R.string.queue_title),
        onBack = onBack,
        snackbar = snackbar,
        action = {
            if (state.queue.isNotEmpty()) {
                TextButton(onClick = { addToPlaylist.open(state.queue.map { it.track.id }) }) {
                    Text(stringResource(R.string.queue_save_as_playlist))
                }
                TextButton(onClick = viewModel::clearQueue) { Text(stringResource(R.string.queue_clear)) }
            }
        },
    ) {
        if (state.queue.isEmpty()) {
            CenteredMessage(stringResource(R.string.queue_empty))
            return@DetailScaffoldWithAction
        }
        // Drag handlers outlive recompositions; they read the queue as it is when the drag ends.
        val queue by rememberUpdatedState(state.queue)
        val listState = rememberLazyListState()
        var dragging by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            // Positions are in play order (shuffled or not); the controller turns them into queue moves.
            items(state.queue, key = QueueEntry::key) { entry ->
                val isDragging = dragging == entry.key
                QueueRow(
                    entry = entry,
                    isCurrent = entry.index == state.currentIndex,
                    onClick = { viewModel.skipTo(entry.index) },
                    onRemove = { viewModel.removeAt(entry.index) },
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f },
                    dragHandleModifier = Modifier.pointerInput(entry.key) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = entry.key
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                val from = queue.indexOfFirst { it.key == entry.key }
                                val target = listState.dropTarget(entry.key, dragOffset) { key ->
                                    queue.indexOfFirst { it.key == key }.takeIf { it >= 0 }
                                }
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
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
        LaunchedEffect(Unit) { onRemove() }
    }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(entry.track.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                RevIcons.DragHandle,
                contentDescription = stringResource(R.string.queue_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier
                    .padding(start = 8.dp)
                    .size(24.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffoldWithAction(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    action: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreR.string.navigate_back))
                    }
                },
                actions = { action() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}
