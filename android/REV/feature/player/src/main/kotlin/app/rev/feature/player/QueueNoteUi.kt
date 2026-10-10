package app.rev.feature.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rev.core.ui.icons.RevIcons
import app.rev.domain.taste.QueueChange
import app.rev.playback.PlayerState
import app.rev.playback.QueueNote

/** What the smart queue did, in words. */
@Composable
internal fun queueNoteText(note: QueueNote): String = when (note.change) {
    QueueChange.Reshuffled -> stringResource(R.string.smart_queue_reshuffled)
    QueueChange.MoodShift -> stringResource(R.string.smart_queue_mood_shift)
    QueueChange.MoreLikeThis -> stringResource(R.string.smart_queue_more_like_this, note.title)
    QueueChange.OtherAlbum -> stringResource(R.string.smart_queue_other_album)
    QueueChange.OtherArtist -> stringResource(R.string.smart_queue_other_artist)
    QueueChange.NextAlbum -> stringResource(R.string.smart_queue_next_album)
    QueueChange.FreshStart -> stringResource(R.string.smart_queue_fresh_start)
    QueueChange.ModeChanged -> stringResource(R.string.smart_queue_mode_changed)
    QueueChange.ModeMix -> stringResource(R.string.smart_queue_mode_mix, note.title)
}

/** Shows each new smart queue change once, as it happens. */
@Composable
internal fun QueueNoteSnackbarEffect(state: PlayerState, snackbar: SnackbarHostState) {
    val note = state.queueNote ?: return
    val text = queueNoteText(note)
    // Only changes made while this screen is open; not the one from before it opened.
    val openedAt = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.queueNoteAt) {
        if (state.queueNoteAt >= openedAt.longValue) snackbar.showSnackbar(text)
    }
}

/** The latest smart queue change, at the top of the queue. */
@Composable
internal fun QueueNoteHeader(note: QueueNote, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            RevIcons.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp).size(18.dp),
        )
        Text(queueNoteText(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
