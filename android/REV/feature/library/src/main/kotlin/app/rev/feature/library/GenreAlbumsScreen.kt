package app.rev.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.ui.components.AlbumCard
import app.rev.core.ui.components.CenteredMessage
import app.rev.core.ui.components.LoadingView
import app.rev.core.ui.components.message

@Composable
internal fun GenreAlbumsScreen(navigator: LibraryNavigator, viewModel: GenreAlbumsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val nearEnd by remember(gridState) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, state.items.size, state.endReached, state.error) {
        if (nearEnd && !state.endReached && !state.isLoading && state.error == null) viewModel.loadMore()
    }

    DetailScaffold(onBack = navigator.back, snackbar = remember { SnackbarHostState() }, title = viewModel.genre) {
        when {
            state.items.isEmpty() && state.error != null -> CenteredMessage(state.error?.message().orEmpty()) {
                TextButton(onClick = viewModel::reload) { Text(stringResource(app.rev.core.R.string.action_retry)) }
            }
            state.items.isEmpty() && !state.endReached -> LoadingView()
            state.items.isEmpty() -> CenteredMessage(stringResource(R.string.empty_albums))
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { it.id }) { album ->
                    AlbumCard(album, onClick = { navigator.toAlbum(album.id) })
                }
                if (state.isLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) { LoadingView(Modifier.padding(16.dp)) }
                } else if (state.error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.load_more_failed), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::loadMore) { Text(stringResource(app.rev.core.R.string.action_retry)) }
                        }
                    }
                }
            }
        }
    }
}
