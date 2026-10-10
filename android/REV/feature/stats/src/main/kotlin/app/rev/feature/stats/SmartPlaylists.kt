package app.rev.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rev.core.ui.components.CollageCard
import app.rev.core.ui.components.SectionHeader
import app.rev.domain.model.SmartPlaylist
import app.rev.domain.model.SmartPlaylistKind

/** Smart playlists as a row of cards; the card opens [onOpen], its play button plays it. */
@Composable
internal fun SmartPlaylistRow(
    state: SmartPlaylistsState,
    onOpen: (SmartPlaylist) -> Unit,
    onPlay: (SmartPlaylist) -> Unit,
) {
    if (state.playlists.isEmpty()) return
    Column {
        SectionHeader(stringResource(R.string.smart_title))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.playlists, key = { it.id }) { playlist ->
                val title = smartTitle(playlist)
                CollageCard(
                    coverArtIds = playlist.coverArtIds,
                    label = smartLabel(playlist),
                    title = title,
                    subtitle = smartDetail(playlist),
                    playDescription = stringResource(R.string.smart_play_description, title),
                    starting = state.starting == playlist.id,
                    onClick = { onOpen(playlist) },
                    onPlay = { onPlay(playlist) },
                )
            }
        }
    }
}

/** What can be done with a smart playlist: play, shuffle, save to (or update in) Navidrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartPlaylistSheet(
    playlist: SmartPlaylist,
    saving: Boolean,
    onPlay: (shuffle: Boolean) -> Unit,
    onSave: () -> Unit,
    onOpenSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(smartTitle(playlist), style = MaterialTheme.typography.titleLarge)
            Text(
                pluralStringResource(R.plurals.smart_track_count, playlist.trackCount, playlist.trackCount) + " · " + smartDetail(playlist),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onPlay(false) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.smart_play)) }
                OutlinedButton(onClick = { onPlay(true) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.smart_shuffle)) }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                if (saving) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(stringResource(if (playlist.savedPlaylistId == null) R.string.smart_save else R.string.smart_update))
                }
            }
            Text(
                stringResource(R.string.smart_save_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            playlist.savedPlaylistId?.let { id ->
                TextButton(onClick = { onOpenSaved(id) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.smart_open_saved))
                }
            }
        }
    }
}

@Composable
internal fun smartTitle(playlist: SmartPlaylist): String = when (playlist.kind) {
    SmartPlaylistKind.OnRepeat -> stringResource(R.string.smart_on_repeat)
    SmartPlaylistKind.Forgotten -> stringResource(R.string.smart_forgotten)
    SmartPlaylistKind.Year -> stringResource(R.string.smart_year, playlist.year ?: 0)
}

/** The short label printed on the card. */
@Composable
private fun smartLabel(playlist: SmartPlaylist): String = when (playlist.kind) {
    SmartPlaylistKind.Year -> playlist.year?.toString().orEmpty()
    else -> smartTitle(playlist)
}

@Composable
private fun smartDetail(playlist: SmartPlaylist): String = stringResource(
    when (playlist.kind) {
        SmartPlaylistKind.OnRepeat -> R.string.smart_on_repeat_detail
        SmartPlaylistKind.Forgotten -> R.string.smart_forgotten_detail
        SmartPlaylistKind.Year -> R.string.smart_year_detail
    },
)
