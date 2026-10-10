package app.rev.feature.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rev.core.ui.components.RevPrimaryButton
import app.rev.core.ui.theme.RevTheme
import app.rev.domain.repository.LoginError

@Composable
fun LoginRoute(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginScreen(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
        onChangeServer = viewModel::changeServer,
    )
}

@Composable
internal fun LoginScreen(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onChangeServer: () -> Unit,
) {
    AuthScaffold {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleLarge)
        state.serverUrl?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.login_server, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.login_username)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Username },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            isError = state.error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Password },
        )
        state.error?.let { FormMessage(it.message(), isError = true) }
        Spacer(Modifier.height(24.dp))
        RevPrimaryButton(
            text = stringResource(R.string.login_submit),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.isSubmitting,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onChangeServer,
            enabled = !state.isSubmitting,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.login_change_server))
        }
    }
}

@Composable
private fun LoginError.message(): String = when (this) {
    LoginError.InvalidCredentials -> stringResource(R.string.login_error_credentials)
    LoginError.RateLimited -> stringResource(R.string.login_error_rate_limited)
    LoginError.Unreachable -> stringResource(R.string.login_error_unreachable)
    LoginError.NoServer -> stringResource(R.string.login_error_no_server)
    is LoginError.Unexpected -> stringResource(R.string.login_error_unexpected, message)
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C)
@Composable
private fun LoginPreview() {
    RevTheme {
        LoginScreen(
            LoginUiState(serverUrl = "http://100.64.0.1:4600", username = "alice", error = LoginError.InvalidCredentials),
            {}, {}, {}, {},
        )
    }
}
