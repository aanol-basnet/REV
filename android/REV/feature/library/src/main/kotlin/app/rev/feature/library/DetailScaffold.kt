package app.rev.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow

/** Top bar with a back button over a transparent background; detail screens own their headers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    title: String = "",
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(app.rev.core.R.string.navigate_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/** Shows queue confirmations ("Playing next", "Added to queue") as snackbars. */
@Composable
internal fun QueueMessagesEffect(messages: Flow<QueueMessage>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(messages) {
        messages.collect {
            snackbar.showSnackbar(
                resources.getString(
                    when (it) {
                        QueueMessage.PlayingNext -> R.string.queued_next
                        QueueMessage.AddedToQueue -> R.string.queued_added
                    },
                ),
            )
        }
    }
}
