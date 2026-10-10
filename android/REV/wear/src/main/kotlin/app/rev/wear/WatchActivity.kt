package app.rev.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import app.rev.wearprotocol.Control

class WatchActivity : ComponentActivity() {
    private val viewModel: WatchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchApp(viewModel) } }
    }
}

@Composable
private fun WatchApp(vm: WatchViewModel) {
    val nav = rememberSwipeDismissableNavController()
    AppScaffold {
        SwipeDismissableNavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScreen(vm, openAlbum = { nav.navigate("album/$it") }, addFromPhone = {
                    vm.loadPhoneAlbums()
                    nav.navigate("phone")
                })
            }
            composable("album/{id}") { entry ->
                AlbumScreen(vm, entry.arguments?.getString("id").orEmpty(), onDeleted = { nav.popBackStack() })
            }
            composable("phone") { PhoneAlbumsScreen(vm, onRequested = { nav.popBackStack() }) }
        }
    }
}

@Composable
private fun Screen(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    ScreenScaffold(scrollState = state) { padding ->
        ScalingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun HomeScreen(vm: WatchViewModel, openAlbum: (String) -> Unit, addFromPhone: () -> Unit) {
    val phone by vm.phoneNowPlaying.collectAsStateWithLifecycle()
    val watch by vm.watchNowPlaying.collectAsStateWithLifecycle()
    val albums by vm.library.albums.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()

    Screen {
        watch?.let { now ->
            item { ListHeader { Text(stringResource(R.string.app_name)) } }
            item {
                NowPlayingCard(now.title, now.artist, null, now.playing, now.hasNext,
                    onPrevious = vm::watchPrevious, onPlayPause = vm::watchPlayPause, onNext = vm::watchNext)
            }
        }
        item { ListHeader { Text(stringResource(R.string.phone)) } }
        item {
            val p = phone
            if (p == null) {
                Text(stringResource(R.string.phone_idle), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            } else {
                NowPlayingCard(p.title, p.artist, p.artwork, p.playing, p.hasNext,
                    onPrevious = { vm.phoneControl(Control.Previous) },
                    onPlayPause = { vm.phoneControl(Control.PlayPause) },
                    onNext = { vm.phoneControl(Control.Next) })
            }
        }
        item { ListHeader { Text(stringResource(R.string.on_watch)) } }
        transfer?.let { t ->
            item {
                Text(
                    "${t.albumTitle}\n" + stringResource(R.string.copying, (t.received + 1).coerceAtMost(t.total), t.total),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (albums.isEmpty()) {
            item { Text(stringResource(R.string.on_watch_empty), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
        }
        albums.forEach { album ->
            item {
                Button(
                    onClick = { openAlbum(album.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        item {
            Button(onClick = addFromPhone, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.add_from_phone)) })
        }
    }
}

@Composable
private fun NowPlayingCard(
    title: String,
    artist: String,
    artwork: android.graphics.Bitmap?,
    playing: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Card(onClick = onPlayPause, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (artwork != null) {
                Image(artwork.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.size(8.dp))
            }
            androidx.compose.foundation.layout.Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(WatchIcons.SkipPrevious, stringResource(R.string.previous), onPrevious)
            ControlButton(
                if (playing) WatchIcons.Pause else WatchIcons.Play,
                stringResource(if (playing) R.string.pause else R.string.play),
                onPlayPause,
            )
            ControlButton(WatchIcons.SkipNext, stringResource(R.string.next), onNext, enabled = hasNext)
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
}

@Composable
private fun AlbumScreen(vm: WatchViewModel, albumId: String, onDeleted: () -> Unit) {
    val albums by vm.library.albums.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.id == albumId } ?: return
    Screen {
        item { ListHeader { Text(album.title, textAlign = TextAlign.Center) } }
        item {
            Button(
                onClick = { vm.play(album, 0) },
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(WatchIcons.Play, contentDescription = null) },
                label = { Text(stringResource(R.string.play)) },
                secondaryLabel = { Text(stringResource(R.string.needs_headphones), maxLines = 2) },
            )
        }
        album.sortedTracks.forEachIndexed { index, track ->
            item {
                Button(
                    onClick = { vm.play(album, index) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        item {
            Button(
                onClick = {
                    vm.delete(album)
                    onDeleted()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(),
                label = { Text(stringResource(R.string.delete)) },
            )
        }
    }
}

@Composable
private fun PhoneAlbumsScreen(vm: WatchViewModel, onRequested: () -> Unit) {
    val state by vm.phoneAlbums.collectAsStateWithLifecycle()
    Screen {
        item { ListHeader { Text(stringResource(R.string.phone_albums)) } }
        when (val s = state) {
            PhoneAlbums.Idle, PhoneAlbums.Loading -> item { Text(stringResource(R.string.phone_albums_loading)) }
            PhoneAlbums.Unreachable -> item { Text(stringResource(R.string.phone_unreachable), textAlign = TextAlign.Center) }
            is PhoneAlbums.Loaded -> {
                if (s.albums.isEmpty()) {
                    item { Text(stringResource(R.string.phone_albums_empty), textAlign = TextAlign.Center) }
                }
                s.albums.forEach { album ->
                    item {
                        Button(
                            onClick = {
                                vm.copyFromPhone(album.collectionId)
                                onRequested()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            label = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = {
                                Text("${album.artist.orEmpty()} · ${formatSize(album.sizeBytes)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes / (1L shl 20)} MB"
}
