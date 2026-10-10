package app.rev.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rev.core.R
import app.rev.core.ui.icons.RevIcons
import app.rev.domain.modes.ActiveMode
import app.rev.domain.modes.BuiltInModes
import app.rev.domain.modes.ListeningMode
import app.rev.domain.modes.ModeLean
import app.rev.domain.modes.ModeSource
import app.rev.domain.modes.ModeRepository
import app.rev.domain.playback.PlaybackQueue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A mode's name: the built-in ones translated, yours as you named them. */
@Composable
fun modeName(mode: ListeningMode): String = when (mode.id) {
    BuiltInModes.DrivingSolo.id -> stringResource(R.string.mode_driving_solo)
    BuiltInModes.DrivingFamily.id -> stringResource(R.string.mode_driving_family)
    BuiltInModes.Work.id -> stringResource(R.string.mode_work)
    BuiltInModes.Home.id -> stringResource(R.string.mode_home)
    BuiltInModes.Jam.id -> stringResource(R.string.mode_jam)
    else -> mode.name
}

/** What you're doing right now, as a chip that opens the [ModeSheet]. */
@Composable
fun ModeChip(active: ActiveMode?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = when {
        active == null -> stringResource(R.string.mode_choose)
        active.source == ModeSource.Auto -> stringResource(R.string.mode_auto_label, modeName(active.mode))
        else -> modeName(active.mode)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(RevIcons.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = modifier,
    )
}

/**
 * Pick what you're doing, or let REV work it out (Automatic). Your own modes can be added (with
 * what they lean towards) and removed; "Play for this mode" starts a mix fitted to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSheet(
    modes: List<ListeningMode>,
    active: ActiveMode?,
    onPick: (String?) -> Unit,
    onAdd: (String, ModeLean) -> Unit,
    onRemove: (String) -> Unit,
    onPlayMix: () -> Unit,
    onDismiss: () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                Text(stringResource(R.string.mode_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                Text(
                    stringResource(R.string.mode_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                ModeRow(
                    name = stringResource(R.string.mode_automatic),
                    detail = active?.takeIf { it.source == ModeSource.Auto }?.let { stringResource(R.string.mode_automatic_now, modeName(it.mode)) }
                        ?: stringResource(R.string.mode_automatic_detail),
                    selected = active == null || active.source == ModeSource.Auto,
                    onClick = { onPick(null) },
                )
            }
            items(modes, key = { it.id }) { mode ->
                ModeRow(
                    name = modeName(mode),
                    detail = when (mode.lean) {
                        ModeLean.Familiar -> stringResource(R.string.mode_lean_familiar)
                        ModeLean.Discover -> stringResource(R.string.mode_lean_discover)
                        ModeLean.None -> null
                    },
                    selected = active?.source == ModeSource.Picked && active.mode.id == mode.id,
                    onClick = { onPick(mode.id) },
                    onRemove = if (mode.builtIn) null else ({ onRemove(mode.id) }),
                )
            }
            item {
                if (adding) {
                    AddMode(onAdd = { name, lean -> onAdd(name, lean); adding = false }, onCancel = { adding = false })
                } else {
                    TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(stringResource(R.string.mode_add))
                    }
                }
                if (active != null) {
                    TextButton(onClick = { onPlayMix(); onDismiss() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Icon(RevIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mode_play_mix, modeName(active.mode)))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ModeRow(name: String, detail: String?, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        onRemove?.let { TextButton(onClick = it) { Text(stringResource(R.string.mode_remove)) } }
    }
}

@Composable
private fun AddMode(onAdd: (String, ModeLean) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var lean by rememberSaveable { mutableStateOf(ModeLean.None) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text(stringResource(R.string.mode_add_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.mode_add_lean),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(lean == ModeLean.None, { lean = ModeLean.None }, label = { Text(stringResource(R.string.mode_lean_none_short)) })
            FilterChip(lean == ModeLean.Familiar, { lean = ModeLean.Familiar }, label = { Text(stringResource(R.string.mode_lean_familiar_short)) })
            FilterChip(lean == ModeLean.Discover, { lean = ModeLean.Discover }, label = { Text(stringResource(R.string.mode_lean_discover_short)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.mode_cancel)) }
            TextButton(onClick = { onAdd(name, lean) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.mode_save)) }
        }
    }
}

/** The listening mode for a screen's view model: what there is, what's on, and changing it. */
class ModeControls(private val scope: CoroutineScope, private val repository: ModeRepository, private val queue: PlaybackQueue) {
    val modes: StateFlow<List<ListeningMode>> = repository.modes.stateIn(scope, SharingStarted.WhileSubscribed(5_000), BuiltInModes.all)
    val active: StateFlow<ActiveMode?> = repository.active.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun pick(id: String?) {
        scope.launch { repository.pick(id) }
    }

    fun add(name: String, lean: ModeLean) {
        scope.launch { repository.pick(repository.addCustom(name, lean).id) }
    }

    fun remove(id: String) {
        scope.launch { repository.removeCustom(id) }
    }

    fun playMix() = queue.playModeMix()
}

/** The mode chip and, when tapped, the [ModeSheet]. */
@Composable
fun ModePicker(controls: ModeControls, modifier: Modifier = Modifier) {
    val modes by controls.modes.collectAsState()
    val active by controls.active.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    ModeChip(active, onClick = { open = true }, modifier = modifier)
    if (open) {
        ModeSheet(
            modes = modes,
            active = active,
            onPick = controls::pick,
            onAdd = controls::add,
            onRemove = controls::remove,
            onPlayMix = controls::playMix,
            onDismiss = { open = false },
        )
    }
}
