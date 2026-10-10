package app.rev.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rev.core.R
import app.rev.core.ui.icons.RevIcons
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.OfflineState

/**
 * §48 download control for an album or playlist: [Download] → [████░ 72%] → [✓ Downloaded].
 * Removing asks for confirmation.
 */
@Composable
fun OfflineButton(
    collection: OfflineCollection?,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val state = collection?.state
    OutlinedButton(
        onClick = {
            when (state) {
                null -> onDownload()
                OfflineState.Failed -> onRetry()
                else -> confirmRemove = true
            }
        },
        modifier = modifier,
    ) {
        when (state) {
            null -> {
                Icon(RevIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.offline_download).uppercase(), style = MaterialTheme.typography.labelLarge)
            }
            OfflineState.Downloaded -> {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.offline_downloaded).uppercase(), style = MaterialTheme.typography.labelLarge)
            }
            OfflineState.Failed ->
                Text(stringResource(R.string.offline_retry).uppercase(), style = MaterialTheme.typography.labelLarge)
            OfflineState.Queued, OfflineState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(72.dp)) {
                    LinearProgressIndicator(progress = { collection.progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.offline_progress, (collection.progress * 100).toInt()),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text(stringResource(R.string.offline_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(stringResource(R.string.offline_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
