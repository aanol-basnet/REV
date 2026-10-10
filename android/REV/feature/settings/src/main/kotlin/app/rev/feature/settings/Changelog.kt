package app.rev.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One version's notes from CHANGELOG.md. */
internal data class Release(val version: String, val date: String?, val sections: List<ChangeSection>)

internal data class ChangeSection(val title: String?, val items: List<String>)

/**
 * Reads CHANGELOG.md: `## version — date` starts a release, `### Title` a section and `- ` an item.
 * Indented lines continue the item above; anything else (the intro) is skipped.
 */
internal fun parseChangelog(text: String): List<Release> {
    val releases = mutableListOf<Release>()
    var version: String? = null
    var date: String? = null
    val sections = mutableListOf<ChangeSection>()
    var title: String? = null
    val items = mutableListOf<String>()

    fun endSection() {
        if (items.isNotEmpty()) sections += ChangeSection(title, items.toList())
        items.clear()
    }
    fun endRelease() {
        endSection()
        version?.let { releases += Release(it, date, sections.toList()) }
        sections.clear()
        title = null
    }

    for (line in text.lines()) {
        when {
            line.startsWith("## ") -> {
                endRelease()
                val parts = line.removePrefix("## ").split(" — ", " - ", limit = 2)
                version = parts[0].trim().removePrefix("v")
                date = parts.getOrNull(1)?.trim()?.ifEmpty { null }
            }
            version == null -> Unit
            line.startsWith("### ") -> {
                endSection()
                title = line.removePrefix("### ").trim()
            }
            line.startsWith("- ") -> items += plain(line.removePrefix("- "))
            line.startsWith("  ") && line.isNotBlank() && items.isNotEmpty() ->
                items[items.lastIndex] = items.last() + " " + plain(line)
        }
    }
    endRelease()
    return releases
}

/** Drops Markdown emphasis and code marks; the notes are shown as plain text. */
private fun plain(text: String) = text.trim().replace("**", "").replace("`", "")

private suspend fun loadChangelog(context: Context): List<Release> = withContext(Dispatchers.IO) {
    runCatching { context.assets.open("CHANGELOG.md").bufferedReader().use { parseChangelog(it.readText()) } }
        .getOrDefault(emptyList())
}

/** The patch notes for every version, newest first, with the installed one marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangelogSheet(installedVersion: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val releases by produceState<List<Release>?>(null) { value = loadChangelog(context) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.changelog_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(8.dp))
        val list = releases
        if (list != null && list.isEmpty()) {
            Text(
                stringResource(R.string.changelog_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).navigationBarsPadding(),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(list.orEmpty(), key = { it.version }) { release ->
                ReleaseNotes(release, installed = release.version == installedVersion)
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ReleaseNotes(release: Release, installed: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(release.version, style = MaterialTheme.typography.titleMedium)
            if (installed) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        stringResource(R.string.changelog_installed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        release.date?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        release.sections.forEach { section ->
            section.title?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            section.items.forEach { item ->
                Row(Modifier.padding(top = 4.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
