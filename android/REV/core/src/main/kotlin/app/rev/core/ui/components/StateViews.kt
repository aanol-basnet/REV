package app.rev.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.rev.core.R
import app.rev.core.ui.state.ContentState
import app.rev.domain.repository.LibraryError

fun LibraryError.text(resources: Resources): String = when (this) {
    LibraryError.Unreachable -> resources.getString(R.string.error_unreachable)
    is LibraryError.MusicServerUnavailable -> resources.getString(R.string.error_music_server)
    LibraryError.NotFound -> resources.getString(R.string.error_not_found)
    LibraryError.SignedOut -> resources.getString(R.string.error_signed_out)
    is LibraryError.Unexpected -> resources.getString(R.string.error_unexpected, message)
}

@Composable
fun LibraryError.message(): String = text(LocalResources.current)

/** Shows each error from [errors] as a snackbar. */
@Composable
fun ErrorSnackbarEffect(errors: Flow<LibraryError>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(errors) {
        errors.collect { snackbar.showSnackbar(it.text(resources)) }
    }
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

/** Slim notice shown above cached content when the latest refresh failed. */
@Composable
fun StaleContentBanner(error: LibraryError, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.error_showing_cached, error.message()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * Standard body for cache-backed screens: spinner on first load, full-screen error when nothing is
 * cached, an empty message, or the content with pull-to-refresh and a stale-data banner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ContentScaffold(
    state: ContentState<T>,
    onRefresh: () -> Unit,
    isEmpty: (T) -> Boolean,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val data = state.content
    when {
        data == null && state.error != null -> CenteredMessage(state.error.message(), modifier) {
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_retry)) }
        }
        data == null -> LoadingView(modifier)
        else -> PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                state.error?.let { StaleContentBanner(it) }
                if (isEmpty(data)) CenteredMessage(emptyMessage) else content(data)
            }
        }
    }
}
