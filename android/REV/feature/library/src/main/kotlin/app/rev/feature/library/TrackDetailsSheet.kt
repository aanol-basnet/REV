package app.rev.feature.library

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rev.core.format.formatFileSize
import app.rev.core.format.formatTrackDuration
import app.rev.core.ui.components.Artwork
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.FavoriteButton
import app.rev.domain.model.Track
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TrackDetailsSheet(
    track: Track,
    onDismiss: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    onGoToAlbum: ((String) -> Unit)?,
    onGoToArtist: ((String) -> Unit)?,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    downloaded: Boolean,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.coverArtId, ArtworkSize.THUMBNAIL, contentDescription = null, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FavoriteButton(track.starred, onFavoriteChange)
            }
            FlowRow(Modifier.padding(top = 12.dp)) {
                TextButton(onClick = onPlayNext) { Text(stringResource(R.string.action_play_next)) }
                TextButton(onClick = onAddToQueue) { Text(stringResource(R.string.action_add_to_queue)) }
                TextButton(onClick = onAddToPlaylist) { Text(stringResource(R.string.action_add_to_playlist)) }
                if (downloaded) {
                    TextButton(onClick = {}, enabled = false) { Text(stringResource(app.rev.core.R.string.offline_downloaded)) }
                } else {
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.action_download_track)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            DetailRow(R.string.detail_album, track.album)
            DetailRow(R.string.detail_artist, track.artist)
            track.trackNumber?.let { DetailRow(R.string.detail_track, it.toString()) }
            track.discNumber?.let { DetailRow(R.string.detail_disc, it.toString()) }
            track.year?.let { DetailRow(R.string.detail_year, it.toString()) }
            track.genre?.let { DetailRow(R.string.detail_genre, it) }
            DetailRow(R.string.detail_duration, formatTrackDuration(track.durationMs))
            track.format?.let { DetailRow(R.string.detail_format, it.uppercase(Locale.ROOT)) }
            track.bitRateKbps?.let { DetailRow(R.string.detail_bitrate, "$it kbps") }
            track.sampleRateHz?.let { DetailRow(R.string.detail_sample_rate, String.format(Locale.ROOT, "%.1f kHz", it / 1000.0)) }
            track.bitDepth?.let { DetailRow(R.string.detail_bit_depth, "$it-bit") }
            track.sizeBytes?.let { DetailRow(R.string.detail_size, formatFileSize(it)) }

            Spacer(Modifier.height(8.dp))
            Row {
                val albumId = track.albumId
                val artistId = track.artistId
                if (onGoToAlbum != null && albumId != null) {
                    TextButton(onClick = { onGoToAlbum(albumId) }) { Text(stringResource(R.string.go_to_album)) }
                }
                if (onGoToArtist != null && artistId != null) {
                    TextButton(onClick = { onGoToArtist(artistId) }) { Text(stringResource(R.string.go_to_artist)) }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: Int, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
