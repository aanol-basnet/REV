package app.rev.feature.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.ui.components.RevPrimaryButton
import app.rev.core.ui.theme.RevTheme

@Composable
fun ServerSetupRoute(viewModel: ServerSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ServerSetupScreen(state, viewModel::onUrlChange, viewModel::connect, viewModel::onLocalUrlChange)
}

@Composable
internal fun ServerSetupScreen(
    state: ServerSetupUiState,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onLocalUrlChange: (String) -> Unit = {},
) {
    AuthScaffold {
        Text(stringResource(R.string.server_setup_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.server_setup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.server_setup_url_label)) },
            placeholder = { Text(stringResource(R.string.server_setup_url_placeholder)) },
            singleLine = true,
            isError = state.error != null,
            enabled = !state.isChecking,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onConnect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.localUrl,
            onValueChange = onLocalUrlChange,
            label = { Text(stringResource(R.string.server_setup_local_label)) },
            placeholder = { Text(stringResource(R.string.server_setup_local_placeholder)) },
            supportingText = { Text(stringResource(R.string.server_setup_local_hint)) },
            singleLine = true,
            enabled = !state.isChecking,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onConnect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.error != null -> FormMessage(stringResource(state.error.messageRes()), isError = true)
            state.isInsecure -> FormMessage(stringResource(R.string.server_setup_insecure_warning), isError = false)
        }
        Spacer(Modifier.height(24.dp))
        RevPrimaryButton(
            text = stringResource(R.string.server_setup_connect),
            onClick = onConnect,
            loading = state.isChecking,
        )
    }
}

private fun ServerSetupError.messageRes() = when (this) {
    ServerSetupError.Empty -> R.string.server_setup_error_empty
    ServerSetupError.Invalid -> R.string.server_setup_error_invalid
    ServerSetupError.UnsupportedScheme -> R.string.server_setup_error_scheme
    ServerSetupError.Unreachable -> R.string.server_setup_error_unreachable
    ServerSetupError.NotRev -> R.string.server_setup_error_not_rev
    ServerSetupError.LocalInvalid -> R.string.server_setup_error_local_invalid
    ServerSetupError.LocalNotLocal -> R.string.server_setup_error_local_not_local
    ServerSetupError.LocalDifferentServer -> R.string.server_setup_error_local_different
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C)
@Composable
private fun ServerSetupPreview() {
    RevTheme {
        ServerSetupScreen(ServerSetupUiState(url = "100.64.0.1:4600"), {}, {})
    }
}
